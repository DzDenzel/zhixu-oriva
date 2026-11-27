"""Offline unit and adversarial tests for release.py R1-R5 and T1-T3 hardening."""

from __future__ import annotations

import base64
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import zipfile

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release.py"))
r = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r)


class T1MavenAndArchiveTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="oriva-t1-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        (self.root / "android/app").mkdir(parents=True)
        (self.root / "android/app/build.gradle.kts").write_text(
            'versionCode = 7\nversionName = "2.2.0"\n', encoding="utf8"
        )
        (self.root / "backend").mkdir()
        (self.root / "android/tools/plugin-sdk").mkdir(parents=True)

    def test_make_plan_defaults_maven_repo_and_rejects_public_source_subdirs(self) -> None:
        plan = r.make_plan(self.root)
        mvn_cmd = plan["commands"][2]
        expected_repo = str((self.root / "work/maven-repo").resolve())
        self.assertEqual(plan["mavenRepo"], expected_repo)
        self.assertIn("-B", mvn_cmd)
        self.assertIn("-ntp", mvn_cmd)
        self.assertIn("-Dmaven.repo.local=" + expected_repo, mvn_cmd)

        for bad_sub in ("backend/.m2", "android/m2-cache", "deploy/m2", ".github/m2"):
            with self.assertRaisesRegex(r.ReleaseError, "outside public source directories"):
                r.make_plan(self.root, maven_repo=self.root / bad_sub)

    def test_prepare_backend_env_strips_maven_args_and_sets_utf8_encoding(self) -> None:
        env = {"MAVEN_ARGS": "-Dmaven.repo.local=bad", "MAVEN_OPTS": "-Xmx1g", "PATH": "x"}
        cleaned = r.prepare_backend_env(env)
        self.assertNotIn("MAVEN_ARGS", cleaned)
        self.assertEqual(cleaned["MAVEN_OPTS"], "-Xmx1g -Dfile.encoding=UTF-8")

    def test_audit_archive_rejects_case_variant_and_nested_forbidden_entries(self) -> None:
        # Case-variant forbidden folder inside allowed top-level tree
        for bad_path in (
            "zhixu-oriva-2.2.0/backend/.M2/repository/pkg.jar",
            "zhixu-oriva-2.2.0/WORK/leak.txt",
            "zhixu-oriva-2.2.0/deploy/Build/out.txt",
        ):
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as z:
                z.writestr(bad_path, b"x")
            with zipfile.ZipFile(io.BytesIO(buf.getvalue())) as z:
                with self.assertRaises(r.ReleaseError, msg=bad_path):
                    r.audit_archive(z)

        # Nested ZIP containing forbidden .p12, .m2/, traversal, .env*, local.properties, backslash, or absolute path
        for nested_entry in (
            "keys/release.p12",
            ".m2/settings.xml",
            "../escape.txt",
            ".env",
            "config/.env.production",
            "android/local.properties",
            "android/LOCAL.PROPERTIES",
            "sub\\evil.txt",
            "/etc/passwd",
            "C:/Windows/win.ini",
        ):
            inner = io.BytesIO()
            with zipfile.ZipFile(inner, "w") as iz:
                iz.writestr(nested_entry.replace("\\", "/"), b"nested")
            inner_bytes = inner.getvalue()
            if "\\" in nested_entry:
                inner_bytes = inner_bytes.replace(
                    nested_entry.replace("\\", "/").encode("ascii"),
                    nested_entry.encode("ascii"),
                )
            outer = io.BytesIO()
            with zipfile.ZipFile(outer, "w") as oz:
                oz.writestr("zhixu-oriva-2.2.0/deploy/bundle.zip", inner_bytes)
            with zipfile.ZipFile(io.BytesIO(outer.getvalue())) as z:
                with self.assertRaises(r.ReleaseError, msg=nested_entry):
                    r.audit_archive(z)

        # Malicious directory entries (outer & nested) must be rejected before is_dir() skip
        for bad_dir in (
            "zhixu-oriva-2.2.0/../bad/",
            "zhixu-oriva-2.2.0/android/build/",
            "zhixu-oriva-2.2.0/sub\\dir/",
            "/abs/",
            "C:/Windows/",
        ):
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as z:
                z.writestr(bad_dir.replace("\\", "/"), b"")
                z.writestr("zhixu-oriva-2.2.0/README.md", b"ok")
            raw = buf.getvalue()
            if "\\" in bad_dir:
                raw = raw.replace(bad_dir.replace("\\", "/").encode("ascii"), bad_dir.encode("ascii"))
            with zipfile.ZipFile(io.BytesIO(raw)) as z:
                with self.assertRaises(r.ReleaseError, msg=bad_dir):
                    r.audit_archive(z)

        for bad_nested_dir in ("../bad/", "sub\\dir/", "/abs/", "build/"):
            inner = io.BytesIO()
            with zipfile.ZipFile(inner, "w") as iz:
                iz.writestr(bad_nested_dir.replace("\\", "/"), b"")
            inner_bytes = inner.getvalue()
            if "\\" in bad_nested_dir:
                inner_bytes = inner_bytes.replace(bad_nested_dir.replace("\\", "/").encode("ascii"), bad_nested_dir.encode("ascii"))
            outer = io.BytesIO()
            with zipfile.ZipFile(outer, "w") as oz:
                oz.writestr("zhixu-oriva-2.2.0/deploy/nested-dir.zip", inner_bytes)
            with zipfile.ZipFile(io.BytesIO(outer.getvalue())) as z:
                with self.assertRaises(r.ReleaseError, msg=bad_nested_dir):
                    r.audit_archive(z)

        # Valid directory entries inside public boundary + nested ZIP must be allowed and files counted accurately
        valid_buf = io.BytesIO()
        inner_ok = io.BytesIO()
        with zipfile.ZipFile(inner_ok, "w") as iz:
            iz.writestr("ok-dir/", b"")
            iz.writestr("ok-dir/plugin.json", b"{}")
        with zipfile.ZipFile(valid_buf, "w") as z:
            for d in (
                "zhixu-oriva-2.2.0/",
                "zhixu-oriva-2.2.0/docs/",
                "zhixu-oriva-2.2.0/docs/screenshots/",
                "zhixu-oriva-2.2.0/.github/",
                "zhixu-oriva-2.2.0/.github/workflows/",
                "zhixu-oriva-2.2.0/android/",
            ):
                z.writestr(d, b"")
            z.writestr("zhixu-oriva-2.2.0/docs/screenshots/today-ink.png", b"PNG")
            z.writestr("zhixu-oriva-2.2.0/deploy/ok.zip", inner_ok.getvalue())
        with zipfile.ZipFile(io.BytesIO(valid_buf.getvalue())) as z:
            self.assertEqual(r.audit_archive(z), 2)


class R1R2R3PublishAndProcessTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="oriva-r123-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        for args in [
            ["init", "-b", "main"],
            ["config", "user.name", "Release Test"],
            ["config", "user.email", "release@example.invalid"],
            ["config", "core.autocrlf", "false"],
        ]:
            self.git(*args)
        (self.root / "android/app").mkdir(parents=True)
        (self.root / "android/app/build.gradle.kts").write_text(
            'versionCode = 7\nversionName = "2.2.0"\n', encoding="utf8"
        )
        (self.root / ".gitignore").write_text("/work/\n/release/\n", encoding="utf8")
        (self.root / "README.md").write_text("ORIVA\n", encoding="utf8")
        self.git("add", ".")
        self.git("commit", "-m", "initial")

    def git(self, *args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=self.root, text=True, stderr=subprocess.DEVNULL).strip()

    def _prepare_packaged_assets(self, plan: dict) -> tuple[str, Path]:
        head = self.git("rev-parse", "HEAD")
        directory = Path(plan["assets"])
        directory.mkdir(parents=True, exist_ok=True)
        names = [
            f"zhixu-oriva-{plan['version']}.apk",
            f"zhixu-oriva-{plan['version']}-source.zip",
            "oriva-release.cer",
            "release-manifest.json",
        ]
        for asset in names[:3]:
            (directory / asset).write_bytes(asset.encode("utf8"))
        r.write_json(directory / "release-manifest.json", dict(versionName=plan["version"], commit=head, certificateSHA256="A" * 64))
        (directory / "SHA256SUMS").write_text(
            "".join(r.sha256(directory / asset) + "  " + asset + "\n" for asset in names), encoding="utf8"
        )
        notes = self.root / "work/notes.md"
        notes.parent.mkdir(parents=True, exist_ok=True)
        notes.write_text("Release 2.2.0 notes", encoding="utf8")
        return head, notes

    def test_r1_rejects_mismatched_pushurl_multi_pushurl_and_insteadof_rewrite(self) -> None:
        plan = r.make_plan(self.root)
        _, notes = self._prepare_packaged_assets(plan)
        args = SimpleNamespace(repo="owner/repo", notes=str(notes), push_protocol="auto", git_timeout=120, upload_timeout=900)

        # 1. Fetch URL matches owner/repo, but separate pushurl points elsewhere
        self.git("remote", "add", "origin", "https://github.com/owner/repo.git")
        self.git("remote", "set-url", "--push", "origin", "https://evil.example.org/owner/repo.git")
        with self.assertRaisesRegex(r.ReleaseError, "push URL does not match"):
            r.publish(self.root, plan, args, os.environ.copy())

        # 2. Multiple push URLs configured on origin
        self.git("remote", "set-url", "--push", "origin", "https://github.com/owner/repo.git")
        self.git("remote", "set-url", "--add", "--push", "origin", "https://mirror.example.org/owner/repo.git")
        with self.assertRaisesRegex(r.ReleaseError, "exactly one push URL"):
            r.publish(self.root, plan, args, os.environ.copy())

        # 3. Single valid push URL, but url.*.insteadOf / pushInsteadOf rewrites (including generic https:// & lowercase normalization)
        self.git("remote", "remove", "origin")
        self.git("remote", "add", "origin", "https://github.com/owner/repo.git")
        self.git("config", "url.https://evil.example.org/.pushInsteadOf", "https://")
        with self.assertRaisesRegex(r.ReleaseError, "push URL does not match|rewrite rule"):
            r.publish(self.root, plan, args, os.environ.copy())
        self.git("config", "--unset", "url.https://evil.example.org/.pushInsteadOf")
        self.git("config", "url.https://evil.example.org/.insteadOf", "https://")
        with self.assertRaisesRegex(r.ReleaseError, "origin does not match|rewrite rule"):
            r.publish(self.root, plan, args, os.environ.copy())
        self.git("config", "--unset", "url.https://evil.example.org/.insteadOf")

        # 4. http.sslVerify=false in local config is rejected
        self.git("config", "http.sslVerify", "false")
        with self.assertRaisesRegex(r.ReleaseError, "TLS verification downgrade"):
            r.publish(self.root, plan, args, os.environ.copy())
        self.git("config", "--unset", "http.sslVerify")

        # 5. SSH origin fallback to HTTPS when generic url.*.insteadOf rewrite exists must still be rejected
        self.git("remote", "remove", "origin")
        self.git("remote", "add", "origin", "git@github.com:owner/repo.git")
        self.git("config", "url.https://evil.example.org/.insteadOf", "https://")
        with patch.object(r, "git", side_effect=lambda root, *a, **kw: (
            "git@github.com:owner/repo.git" if a == ("remote", "get-url", "origin")
            else (_ for _ in ()).throw(r.ReleaseError("ssh probe failed")) if "ls-remote" in a
            else subprocess.check_output(["git", *a], cwd=root, text=True).strip()
        )):
            with self.assertRaisesRegex(r.ReleaseError, "rewrite rule"):
                r.publish(self.root, plan, args, os.environ.copy())
        self.git("config", "--unset", "url.https://evil.example.org/.insteadOf")

        # 6. Missing origin must hard fail (no silent fallback)
        self.git("remote", "remove", "origin")
        with self.assertRaises(r.ReleaseError):
            r.publish(self.root, plan, args, os.environ.copy())

        # 7. Mixed SSH fetch + HTTPS pushurl forbidden in explicit SSH mode
        self.git("remote", "add", "origin", "git@github.com:owner/repo.git")
        self.git("remote", "set-url", "--push", "origin", "https://github.com/owner/repo.git")
        ssh_args = SimpleNamespace(repo="owner/repo", notes=str(notes), push_protocol="ssh", git_timeout=120, upload_timeout=900)
        with self.assertRaisesRegex(r.ReleaseError, "forbids non-SSH push URL"):
            r.publish(self.root, plan, ssh_args, os.environ.copy())

        # 8. URL-specific http.<url>.* priority overrides must be rejected cleanly
        self.git("remote", "set-url", "--push", "origin", "https://github.com/owner/repo.git")
        for cfg_key, cfg_val, err_pat in (
            ("http.https://github.com.sslVerify", "false", "TLS verification downgrade"),
            ("http.https://github.com.followRedirects", "true", "HTTP redirect rule"),
            ("http.https://github.com.extraHeader", "Authorization: Bearer leak", "undeclared HTTP header"),
            ("credential.https://github.com.helper", "store", "credential helper"),
        ):
            self.git("config", cfg_key, cfg_val)
            with self.assertRaisesRegex(r.ReleaseError, err_pat, msg=cfg_key):
                r.publish(self.root, plan, args, os.environ.copy())
            self.git("config", "--unset", cfg_key)

    def _find_posix_sh(self) -> str:
        if os.name != "nt":
            return "/bin/sh"
        git_bin = r.shutil.which("git")
        if git_bin:
            git_path = Path(git_bin).resolve()
            for candidate in (
                git_path.parents[1] / "bin" / "sh.exe",
                git_path.parents[1] / "usr" / "bin" / "sh.exe",
            ):
                if candidate.is_file():
                    return str(candidate)
        found = r.shutil.which("sh")
        if found:
            return found
        self.skipTest("Git for Windows sh.exe not found")

    def test_r1_r3_askpass_binds_host_user_repo_has_lf_and_runs_under_real_sh(self) -> None:
        token_val = "synthetic-release-token-value"
        special_base = self.root / "space dir & bang!"
        special_base.mkdir()
        with patch.object(r.tempfile, "mkdtemp", return_value=str(special_base / "ak")):
            (special_base / "ak").mkdir()
            with r.git_askpass_env(os.environ.copy(), token_val, expected_repo="owner/repo") as git_env:
                helper = Path(git_env["GIT_ASKPASS"])
                py_file = helper.parent / "askpass.py"
                py_raw = py_file.read_bytes()
                self.assertTrue(py_raw.startswith(b"#!/usr/bin/env python3\n"))
                self.assertNotIn(b"\r\n", py_raw)

                if os.name == "nt":
                    sh_raw = helper.read_bytes()
                    self.assertTrue(sh_raw.startswith(b"#!/bin/sh\n"))
                    self.assertNotIn(b"\r\n", sh_raw)
                    sh_exe = self._find_posix_sh()
                    u = subprocess.check_output([sh_exe, str(helper), "Username for 'https://github.com/owner/repo.git': "], env=git_env, text=True).strip()
                    p = subprocess.check_output([sh_exe, str(helper), "Password for 'https://x-access-token@github.com/owner/repo.git': "], env=git_env, text=True).strip()
                    p_repo = subprocess.check_output([sh_exe, str(helper), "Password for 'https://github.com/owner/repo': "], env=git_env, text=True).strip()
                else:
                    u = subprocess.check_output([str(helper), "Username for 'https://github.com/owner/repo.git': "], env=git_env, text=True).strip()
                    p = subprocess.check_output([str(helper), "Password for 'https://x-access-token@github.com/owner/repo.git': "], env=git_env, text=True).strip()
                    p_repo = subprocess.check_output([str(helper), "Password for 'https://github.com/owner/repo': "], env=git_env, text=True).strip()
                self.assertEqual(u, "x-access-token")
                self.assertEqual(p, token_val)
                self.assertEqual(p_repo, token_val)

                # Non-target hosts, bare root '/', empty path, HTTP, custom ports, wrong repo path, wrong user, trailing text must FAIL
                for bad_prompt in (
                    "Username for 'https://github.com': ",
                    "Password for 'https://x-access-token@github.com': ",
                    "Password for 'https://x-access-token@github.com/': ",
                    "Username for 'https://evil.example.org/owner/repo.git': ",
                    "Password for 'http://x-access-token@github.com/owner/repo.git': ",
                    "Password for 'https://x-access-token@github.com:8443/owner/repo.git': ",
                    "Password for 'https://other-user@github.com/owner/repo.git': ",
                    "Password for 'https://x-access-token@github.com/other/repo.git': ",
                    "Username for 'https://github.com/owner/repo.git' extra",
                    "Password for 'https://x-access-token@github.com/owner/repo.git?x=1': ",
                    "Enter passphrase for key: ",
                ):
                    proc = subprocess.run([sys.executable, str(py_file), bad_prompt], env=git_env, capture_output=True, text=True)
                    self.assertNotEqual(proc.returncode, 0, bad_prompt)

            self.assertFalse((special_base / "ak").exists())

        # Real git process over TLS + useHttpPath triggers generated GIT_ASKPASS (and anonymous=True strips it!)
        import http.server, ssl as _ssl, threading
        sh_path = Path(self._find_posix_sh()).resolve()
        openssl_candidates = [
            sh_path.with_name("openssl.exe" if os.name == "nt" else "openssl"),
            sh_path.parents[1] / "usr" / "bin" / ("openssl.exe" if os.name == "nt" else "openssl"),
            sh_path.parents[1] / "mingw64" / "bin" / ("openssl.exe" if os.name == "nt" else "openssl"),
        ]
        found_which = r.shutil.which("openssl")
        if found_which:
            openssl_candidates.append(Path(found_which))
        openssl_bin = next((c for c in openssl_candidates if c.is_file()), None)
        if openssl_bin is None:
            self.fail(f"Expected openssl binary near {sh_path}")
        cert_pem = self.root / "local-https.crt"
        key_pem = self.root / "local-https.key"
        subprocess.run(
            [
                str(openssl_bin), "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1",
                "-keyout", str(key_pem), "-out", str(cert_pem),
            ],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20,
        )

        auth_seen = []
        all_https_headers = []
        class RealGitAuthServer(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                all_https_headers.append({k.lower(): v for k, v in self.headers.items()})
                auth = self.headers.get("Authorization")
                if auth:
                    auth_seen.append((self.path, auth))
                    self.send_response(403)
                    self.end_headers()
                else:
                    self.send_response(401)
                    self.send_header("WWW-Authenticate", 'Basic realm="Git"')
                    self.end_headers()
            def log_message(self, *args): pass

        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), RealGitAuthServer)
        ssl_ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_SERVER)
        ssl_ctx.load_cert_chain(certfile=str(cert_pem), keyfile=str(key_pem))
        srv.socket = ssl_ctx.wrap_socket(srv.socket, server_side=True)
        t_srv = threading.Thread(target=srv.serve_forever, daemon=True)
        t_srv.start()
        try:
            port = srv.server_address[1]
            # Poison host env with fake HOME/.netrc/_netrc/cookies targeting 127.0.0.1 on the SAME HTTPS test!
            fake_home = self.root / "https_fake_home"
            fake_home.mkdir()
            (fake_home / ".netrc").write_text("machine 127.0.0.1 login netrcuser password netrcpass\n", encoding="utf8")
            (fake_home / "_netrc").write_text("machine 127.0.0.1 login netrcuser password netrcpass\n", encoding="utf8")
            cookie_file = fake_home / "cookies.txt"
            cookie_file.write_text("127.0.0.1\tFALSE\t/\tTRUE\t2147483647\tsid\thttps_secret_cookie\n", encoding="utf8")
            (fake_home / ".gitconfig").write_text(f"[http]\n\tcookieFile = {cookie_file.as_posix()}\n", encoding="utf8")
            host_https_env = {**os.environ, "HOME": str(fake_home), "USERPROFILE": str(fake_home)}

            popen_envs: list[dict[str, str]] = []
            orig_popen = subprocess.Popen
            def _capturing_popen(*pargs, **pkwargs):
                popen_envs.append(dict(pkwargs.get("env") or {}))
                return orig_popen(*pargs, **pkwargs)

            with r.git_askpass_env(
                host_https_env, token_val, expected_host="127.0.0.1", expected_user="x-access-token", expected_repo="owner/repo", expected_port=port,
            ) as ask_env:
                # 1. release.git with anonymous=False preserves controlled GIT_ASKPASS + isolated home and sends ONLY controlled askpass token
                with patch.object(r.subprocess, "Popen", side_effect=_capturing_popen):
                    with self.assertRaises(r.ReleaseError):
                        r.git(
                            self.root,
                            "-c", "credential.helper=", "-c", "credential.useHttpPath=true",
                            "-c", "http.cookieFile=", "-c", "http.saveCookies=false",
                            "-c", "http.sslBackend=openssl", "-c", f"http.sslCAInfo={cert_pem}", "-c", "http.sslVerify=true",
                            "ls-remote", f"https://127.0.0.1:{port}/owner/repo.git",
                            timeout=15, env=ask_env, anonymous=False,
                        )
                self.assertEqual(len(auth_seen), 1)
                self.assertTrue(auth_seen[0][0].startswith("/owner/repo.git/"))
                expected_basic = "Basic " + base64.b64encode(f"x-access-token:{token_val}".encode("utf8")).decode("ascii")
                self.assertEqual(auth_seen[0][1], expected_basic)
                for hdrs in all_https_headers:
                    self.assertNotIn("cookie", hdrs)

                # 2. Mismatched repo path is blocked by askpass BEFORE sending token
                auth_seen.clear()
                all_https_headers.clear()
                with self.assertRaises(r.ReleaseError):
                    r.git(
                        self.root,
                        "-c", "credential.helper=", "-c", "credential.useHttpPath=true",
                        "-c", "http.cookieFile=", "-c", "http.saveCookies=false",
                        "-c", "http.sslBackend=openssl", "-c", f"http.sslCAInfo={cert_pem}", "-c", "http.sslVerify=true",
                        "ls-remote", f"https://127.0.0.1:{port}/other/repo.git",
                        timeout=15, env=ask_env, anonymous=False,
                    )
                self.assertEqual(auth_seen, [])

                # 3. anonymous=True strips GIT_ASKPASS AND preserves empty home at Popen boundary (no netrc, no cookie over real HTTPS!)
                all_https_headers.clear()
                popen_envs.clear()
                with r.isolated_git_env(host_https_env, anonymous=True) as anon_env:
                    with patch.object(r.subprocess, "Popen", side_effect=_capturing_popen):
                        with self.assertRaises(r.ReleaseError):
                            r.git(
                                self.root,
                                "-c", "credential.helper=", "-c", "core.askPass=", "-c", "credential.useHttpPath=true",
                                "-c", "http.cookieFile=", "-c", "http.saveCookies=false",
                                "-c", "http.sslBackend=openssl", "-c", f"http.sslCAInfo={cert_pem}", "-c", "http.sslVerify=true",
                                "ls-remote", f"https://127.0.0.1:{port}/owner/repo.git",
                                timeout=15, env=anon_env, anonymous=True,
                            )
                        with self.assertRaises(r.ReleaseError):
                            r.execute(
                                [
                                    "git", "-c", "credential.helper=", "-c", "core.askPass=",
                                    "-c", "http.cookieFile=", "-c", "http.saveCookies=false",
                                    "-c", "http.sslBackend=openssl", "-c", f"http.sslCAInfo={cert_pem}", "-c", "http.sslVerify=true",
                                    "ls-remote", f"https://127.0.0.1:{port}/owner/repo.git",
                                ],
                                self.root, anon_env, timeout=15,
                            )
                self.assertEqual(auth_seen, [])
                self.assertGreaterEqual(len(all_https_headers), 2)
                for hdrs in all_https_headers:
                    self.assertNotIn("authorization", hdrs)
                    self.assertNotIn("cookie", hdrs)
                self.assertEqual(len(popen_envs), 2)
                for penv in popen_envs:
                    for hk in ("HOME", "USERPROFILE", "XDG_CONFIG_HOME", "XDG_CACHE_HOME"):
                        self.assertEqual(penv.get(hk), anon_env.get(hk))
                        self.assertNotEqual(penv.get(hk), str(fake_home))
                    if os.name == "nt":
                        self.assertIn("HOMEDRIVE", penv)
                        self.assertIn("HOMEPATH", penv)
        finally:
            srv.shutdown()
            srv.server_close()
            t_srv.join(timeout=1)

        # Cleanup failure must surface visibly, redact token, AND recycle temp dir created under %TEMP%
        real_rmtree = r.shutil.rmtree
        real_mkdtemp = tempfile.mkdtemp
        created_dirs: list[str] = []
        def _tracking_mkdtemp(*a, **kw):
            d = real_mkdtemp(*a, **kw)
            created_dirs.append(d)
            return d
        try:
            with (
                patch.object(r.tempfile, "mkdtemp", side_effect=_tracking_mkdtemp),
                patch.object(r.shutil, "rmtree", side_effect=PermissionError(f"locked {token_val}")),
            ):
                with self.assertRaises(r.ReleaseError) as ctx:
                    with r.git_askpass_env(os.environ.copy(), token_val, expected_repo="owner/repo"):
                        pass
                self.assertIn("Failed to remove temporary GIT_ASKPASS", str(ctx.exception))
                self.assertNotIn(token_val, str(ctx.exception))
        finally:
            for d in created_dirs:
                real_rmtree(d, ignore_errors=True)

    def test_r2_sanitize_git_env_and_centralized_log_redaction(self) -> None:
        dirty = {
            "PATH": os.environ.get("PATH", ""),
            "GIT_TRACE": "1",
            "GIT_TRACE_CURL": "1",
            "GIT_TRACE2_PERF": "1",
            "GIT_CURL_VERBOSE": "1",
            "GIT_SSL_NO_VERIFY": "1",
            "GIT_CONFIG_GLOBAL": "/tmp/evil.gitconfig",
            "GCM_INTERACTIVE": "always",
            "GIT_CREDENTIAL_HELPER": "store",
            "GIT_CONFIG_PARAMETERS": "'http.extraHeader=x'",
            "GIT_ASKPASS": "/evil/askpass",
            "SSH_ASKPASS": "/evil/ssh_askpass",
        }
        cleaned = r.sanitize_git_env(dirty, empty_gitconfig_path="/tmp/empty.cfg", empty_home_path="/tmp/empty-home")
        self.assertEqual(cleaned["GIT_TERMINAL_PROMPT"], "0")
        self.assertEqual(cleaned["GIT_CONFIG_NOSYSTEM"], "1")
        self.assertEqual(cleaned["GIT_CONFIG_GLOBAL"], "/tmp/empty.cfg")
        self.assertEqual(cleaned["HOME"], "/tmp/empty-home")
        for forbidden in ("GIT_TRACE", "GIT_TRACE_CURL", "GIT_CURL_VERBOSE", "GIT_SSL_NO_VERIFY", "GIT_TRACE2_PERF", "GCM_INTERACTIVE", "GIT_CREDENTIAL_HELPER", "GIT_CONFIG_PARAMETERS", "GIT_ASKPASS", "SSH_ASKPASS"):
            self.assertNotIn(forbidden, cleaned)

        # Minimal env inheritance must drop unrelated secrets while keep_ssh_env isolates SSH runtime vars.
        rich_env = {
            **os.environ,
            "CUSTOM_APP_TOKEN": "hidden-custom-tok",
            "DB_PASS": "s3c",
            "PRIVATE_KEY": "pw!9",
            "SSH_AUTH_SOCK": "/tmp/ssh-agent.sock",
            "SSH_AGENT_PID": "4242",
            "GIT_SSH": "/usr/bin/ssh",
            "GIT_SSH_COMMAND": "ssh -i /home/user/.ssh/id_ed25519",
        }
        anon_cleaned = r.sanitize_git_env(rich_env, empty_gitconfig_path="/tmp/empty.cfg", empty_home_path="/tmp/empty-home", keep_ssh_env=False)
        for dropped in ("CUSTOM_APP_TOKEN", "DB_PASS", "PRIVATE_KEY", "SSH_AUTH_SOCK", "SSH_AGENT_PID", "GIT_SSH", "GIT_SSH_COMMAND"):
            self.assertNotIn(dropped, anon_cleaned)
        # Explicit SSH mode keeps all SSH & agent vars even when allow_controlled_askpass=True, and survives Popen sanitization
        ssh_cleaned = r.sanitize_git_env(rich_env, empty_gitconfig_path="/tmp/empty.cfg", allow_controlled_askpass=True, keep_ssh_env=True)
        for kept in ("SSH_AUTH_SOCK", "SSH_AGENT_PID", "GIT_SSH", "GIT_SSH_COMMAND"):
            self.assertEqual(ssh_cleaned.get(kept), rich_env[kept])
        for dropped in ("CUSTOM_APP_TOKEN", "DB_PASS", "PRIVATE_KEY"):
            self.assertNotIn(dropped, ssh_cleaned)

        ssh_popen_envs: list[dict[str, str]] = []
        orig_popen_ssh = subprocess.Popen
        def _fake_ssh_popen(cmd, **kw):
            ssh_popen_envs.append(dict(kw.get("env") or {}))
            raise OSError("stop before launch")
        with r.isolated_git_env(rich_env, anonymous=False, keep_ssh_env=True) as ssh_env:
            with patch.object(r.subprocess, "Popen", side_effect=_fake_ssh_popen):
                with self.assertRaises(OSError):
                    r.git(self.root, "status", env=ssh_env, anonymous=False)
                with self.assertRaises(OSError):
                    r.execute(["git", "status"], self.root, ssh_env)
        self.assertEqual(len(ssh_popen_envs), 2)
        for penv in ssh_popen_envs:
            for kept in ("SSH_AUTH_SOCK", "SSH_AGENT_PID", "GIT_SSH", "GIT_SSH_COMMAND"):
                self.assertEqual(penv.get(kept), rich_env[kept])
            for dropped in ("CUSTOM_APP_TOKEN", "DB_PASS", "PRIVATE_KEY"):
                self.assertNotIn(dropped, penv)

        secret = "synthetic/secret+tok=999"
        short_secret = "pw9"
        unicode_secret = "密钥=秘密値"
        b64_auth = base64.b64encode(("x-access-token:" + secret).encode("utf8")).decode("ascii")
        pct_upper = r.urllib.parse.quote(secret, safe="")
        pct_lower = pct_upper.replace("%2F", "%2f").replace("%2B", "%2b").replace("%3D", "%3d")
        pct_mixed = pct_upper.replace("%2F", "%2f")  # mixed %2f + %2B + %3D
        unicode_pct_mixed = "%e5%af%86%E9%92%A5%3d%E7%A7%98%e5%af%86%E5%80%A4"
        log = self.root / "leak.log"
        env = os.environ.copy()
        env["CUSTOM_CHILD_SECRET"] = secret
        env["DB_PASS"] = short_secret
        env["UNICODE_SECRET"] = unicode_secret
        with self.assertRaises(r.ReleaseError):
            r.execute(
                [sys.executable, "-c", (
                    f"import sys; sys.stderr.write('raw={secret} short={short_secret} pct1={pct_upper} pct2={pct_lower} pct3={pct_mixed} "
                    f"upct={unicode_pct_mixed} uplain={unicode_secret} "
                    f"Authorization: Bearer {secret} Authorization: Basic {b64_auth} "
                    f"HTTPS://tok@github.com/o/r?X-Amz-Signature=sigsecret#fragsecret "
                    f"https://github.com/o/r#onlyfrag\\n'); sys.exit(2)"
                )],
                self.root, env, log,
            )
        log_text = log.read_text(encoding="utf8")
        for leaked in (secret, short_secret, unicode_secret, b64_auth, pct_upper, pct_lower, pct_mixed, unicode_pct_mixed, "sigsecret", "fragsecret", "onlyfrag", "tok@"):
            self.assertNotIn(leaked, log_text)
        self.assertIn("[REDACTED]", log_text)
        self.assertIn("/tmp/ssh-agent.sock", r.redact_secrets("/tmp/ssh-agent.sock", extra_secrets=r._extract_secrets_from_env(rich_env)))
        self.assertEqual(
            r.redact_secrets("url=https://x/%E7%A7%98%e5%af%86%E5%80%A4", extra_secrets=("秘密値",)),
            "url=https://x/[REDACTED]",
        )

        # archive_source must route via controlled git runner & redact stderr/error messages
        with patch.object(r, "_run_bounded_subprocess", return_value=(False, 1, b"", f"fatal token={secret}".encode("utf8"), [])):
            with self.assertRaises(r.ReleaseError) as arch_ctx:
                with patch.dict(os.environ, {"CUSTOM_CHILD_SECRET": secret}):
                    r.archive_source(self.root, "HEAD", "2.2.0", self.root / "out.zip")
            self.assertNotIn(secret, str(arch_ctx.exception))

        # isolated_git_env cleanup failure must surface diagnostic visibly AND recycle TEMP dir
        real_rmtree = r.shutil.rmtree
        real_mkdtemp = tempfile.mkdtemp
        created_iso_dirs: list[str] = []
        def _tracking_iso_mkdtemp(*a, **kw):
            d = real_mkdtemp(*a, **kw)
            created_iso_dirs.append(d)
            return d
        try:
            with (
                patch.object(r.tempfile, "mkdtemp", side_effect=_tracking_iso_mkdtemp),
                patch.object(r.shutil, "rmtree", side_effect=PermissionError(f"locked {secret}")),
            ):
                with self.assertRaises(r.ReleaseError) as iso_ctx:
                    with r.isolated_git_env({"ORIVA_GIT_TOKEN": secret}):
                        pass
                self.assertIn("Failed to remove temporary isolated Git config", str(iso_ctx.exception))
                self.assertNotIn(secret, str(iso_ctx.exception))
        finally:
            for d in created_iso_dirs:
                real_rmtree(d, ignore_errors=True)

    @unittest.skipUnless(r.os.name == "nt", "Windows CMD delayed expansion guard")
    def test_r3_windows_batch_disables_delayed_expansion_for_bang_paths(self) -> None:
        script = self.root / "echo_bang.cmd"
        script.write_bytes(b"@echo off\r\necho [%1]\r\n")
        out = r.execute([str(script), "folder!with!bangs"], self.root, os.environ.copy())
        self.assertIn('["folder!with!bangs"]', out)

    def test_r3_timeout_terminates_orphaned_descendant_holding_pipe_and_redacts_output(self) -> None:
        pid_file = self.root / "child.pid"
        log = self.root / "tree-timeout.log"
        secret = "synthetic-tree-secret"
        env = os.environ.copy()
        env["ORIVA_GIT_TOKEN"] = secret
        # Direct parent prints secret + grandchild PID, then EXITS IMMEDIATELY while grandchild holds stdout pipe!
        launcher_code = (
            "import subprocess, sys\n"
            "p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])\n"
            f"open({json.dumps(str(pid_file))}, 'w').write(str(p.pid))\n"
            f"sys.stdout.write('Authorization: Bearer {secret}\\n'); sys.stdout.flush()\n"
        )
        t0 = time.monotonic()
        with self.assertRaisesRegex(r.ReleaseError, "timed out"):
            r.execute([sys.executable, "-c", launcher_code], self.root, env, log, timeout=0.6)
        elapsed = time.monotonic() - t0
        self.assertLess(elapsed, 8.0, "Timeout should not hang waiting for grandchild holding pipe")
        log_text = log.read_text(encoding="utf8")
        self.assertIn("[TIMEOUT", log_text)
        self.assertNotIn(secret, log_text)
        self.assertTrue(pid_file.exists(), "Expected grandchild PID file to be written before parent exit")
        child_pid = int(pid_file.read_text(encoding="utf8"))
        time.sleep(0.2)
        if os.name == "nt":
            probe = subprocess.run(["tasklist", "/FI", f"PID eq {child_pid}"], capture_output=True, text=True)
            self.assertNotIn(str(child_pid), probe.stdout)
        else:
            with self.assertRaises(OSError):
                os.kill(child_pid, 0)

        # Verify termination failure + exited parent + pipe-holding grandchild combination does NOT deadlock on close()
        diag_log = self.root / "tree-diag.log"
        surv_pid_file = self.root / "survivor.pid"
        surv_code = (
            "import subprocess, sys\n"
            "p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(20)'])\n"
            f"open({json.dumps(str(surv_pid_file))}, 'w').write(str(p.pid))\n"
        )
        t_combo0 = time.monotonic()
        try:
            with patch.object(r, "_terminate_process_tree", return_value=["simulated tree termination failure"]):
                with patch.object(r, "PIPE_DRAIN_TIMEOUT_SECONDS", 0.3):
                    with self.assertRaisesRegex(r.ReleaseError, "timed out"):
                        r.execute([sys.executable, "-c", surv_code], self.root, env, diag_log, timeout=0.3)
            self.assertLess(time.monotonic() - t_combo0, 4.0)
            diag_text = diag_log.read_text(encoding="utf8")
            self.assertIn("TREE_CLEANUP_DIAG", diag_text)
            self.assertIn("detached daemon pipe reader", diag_text)
        finally:
            if surv_pid_file.exists():
                surv_pid = int(surv_pid_file.read_text(encoding="utf8"))
                if os.name == "nt":
                    subprocess.run(["taskkill", "/F", "/PID", str(surv_pid)], capture_output=True)
                else:
                    try:
                        os.kill(surv_pid, 9)
                    except OSError:
                        pass

    def test_r1_publish_https_origin_routes_via_askpass_and_disables_redirects(self) -> None:
        self.git("remote", "add", "origin", "https://github.com/owner/repo.git")
        plan = r.make_plan(self.root)
        head, notes = self._prepare_packaged_assets(plan)
        secret_token = "synthetic-release-token-secret"
        args = SimpleNamespace(repo="owner/repo", notes=str(notes), push_protocol="auto", git_timeout=120, upload_timeout=900)
        seen_cmds: list[list[str]] = []

        original_git = r.git

        def fake_git(root, *items, timeout=None, env=None, anonymous=False):
            if items == ("remote", "get-url", "origin"):
                return "https://github.com/owner/repo.git"
            if "ls-remote" in items:
                self.assertFalse(anonymous)
                self.assertIn("http.followRedirects=false", items)
                self.assertIn("credential.useHttpPath=true", items)
                cleaned = r.sanitize_git_env(env or {}, empty_gitconfig_path=env.get("GIT_CONFIG_GLOBAL"), allow_controlled_askpass=True)
                self.assertIn("GIT_ASKPASS", cleaned)
                return ""
            return original_git(root, *items)

        def fake_execute(cmd, cwd, exec_env, logfile=None, timeout=None):
            seen_cmds.append(list(map(str, cmd)))
            if cmd[:3] == ["git", "tag", "-a"]:
                self.git(*cmd[1:])
            elif "push" in cmd:
                self.assertIn("http.followRedirects=false", cmd)
                self.assertIn("credential.helper=", cmd)
                self.assertIn("credential.useHttpPath=true", cmd)
                self.assertIn("https://github.com/owner/repo.git", cmd)
                self.assertIn("GIT_ASKPASS", exec_env)
            return ""

        class FakeGithub:
            def __init__(self, repo):
                self.base = "https://api.github.com/repos/" + repo
                self.upload_timeout = 900

            def call(self, url, method="GET", payload=None, missing_ok=False, timeout=None):
                if method == "GET":
                    return [] if "/releases?" in url else None
                if method == "POST" and url == self.base + "/releases":
                    self.release = dict(
                        id=10, tag_name=plan["tag"], target_commitish=head, draft=True, assets=[],
                        upload_url="https://uploads.github.com/assets{?name}",
                        html_url="https://github.com/owner/repo/releases/tag/" + plan["tag"],
                    )
                    return self.release
                if isinstance(payload, bytes):
                    asset = r.urllib.parse.parse_qs(r.urllib.parse.urlparse(url).query)["name"][0]
                    item = dict(name=asset, size=len(payload), digest="sha256:" + hashlib.sha256(payload).hexdigest())
                    self.release["assets"].append(item)
                    return item
                if method == "PATCH":
                    self.release.update(payload)
                    return self.release
                raise AssertionError((url, method))

        with (
            patch.dict(os.environ, {"GITHUB_TOKEN": secret_token}),
            patch.object(r, "git", fake_git),
            patch.object(r, "execute", fake_execute),
            patch.object(r, "Github", FakeGithub),
        ):
            r.publish(self.root, plan, args, os.environ.copy())

        self.assertTrue(any("http.followRedirects=false" in c for c in seen_cmds))
        pub_evidence = json.loads((Path(plan["evidence"]) / "github-published-release.json").read_text(encoding="utf8"))
        self.assertEqual(set(pub_evidence.keys()), {"id", "tag_name", "target_commitish", "draft", "html_url", "assets"})
        self.assertNotIn("upload_url", pub_evidence)


class R4R5VerifyAndDownloadHardeningTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="oriva-r45-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        (self.root / "android/app").mkdir(parents=True)
        (self.root / "android/app/build.gradle.kts").write_text(
            'versionCode = 7\nversionName = "2.2.0"\n', encoding="utf8"
        )

    def test_r4_https_origin_policy_redacts_query_and_strips_sensitive_redirect_headers(self) -> None:
        # Bad URLs rejected and signed query stripped in error text
        user_pass_url = "https://" + "user:pass@github.com/o/r/releases/download/v2.2.0/app.apk"
        for bad_url in (
            "http://github.com/o/r/app.apk",
            "https://evil.example.org/app.apk?token=secret123",
            "https://github.com:8443/o/r/app.apk",
            user_pass_url,
            "https://github.com/o/r/app.apk#frag",
        ):
            with self.assertRaises(r.ReleaseError) as ctx:
                r.require_https_url(bad_url, r.ALLOWED_DOWNLOAD_HOSTS, "Asset redirect", allow_query=True)
            self.assertNotIn("secret123", str(ctx.exception))
            self.assertNotIn("user:pass", str(ctx.exception))

        # Redirect strips Authorization / Cookie / Proxy-Authorization and preserves Range & signed query
        handler = r.AllowedAssetRedirectHandler(deadline=time.monotonic() + 60, download_timeout=30)
        req = urllib.request.Request(
            "https://github.com/o/r/releases/download/v2.2.0/app.apk",
            headers={
                "User-Agent": "ORIVA-public-verification",
                "Range": "bytes=100-",
                "Authorization": "Bearer secret",
                "Cookie": "sid=abc",
                "Proxy-Authorization": "Basic xyz",
            },
        )
        new_req = handler.redirect_request(
            req, None, 302, "Found", {},
            "https://objects.githubusercontent.com/github-production-release-asset/1?X-Amz-Signature=ok",
        )
        self.assertEqual(new_req.headers.get("Range"), "bytes=100-")
        for hdr in ("Authorization", "Cookie", "Proxy-Authorization"):
            self.assertNotIn(hdr, new_req.headers)
            self.assertNotIn(hdr, new_req.unredirected_hdrs)

    def test_r5_strict_content_range_size_and_stale_part_and_deadline(self) -> None:
        payload = b"0123456789" * 60000
        expected_sha = hashlib.sha256(payload).hexdigest().upper()
        split_at = 300000

        class Resp:
            def __init__(self, status: int, chunks: list, headers: dict[str, str] | None = None):
                self.status = status
                self._chunks = list(chunks)
                self.headers = headers or {}

            def __enter__(self): return self
            def __exit__(self, *a): pass

            def read(self, size: int = -1) -> bytes:
                if not self._chunks:
                    return b""
                nxt = self._chunks.pop(0)
                if isinstance(nxt, BaseException):
                    raise nxt
                return nxt

        # 1. Valid 206 resume succeeds when Content-Range & Content-Length & SHA-256 all match
        class ResumeOpener:
            def __init__(self): self.calls = 0
            def open(self, req, timeout=None):
                self.calls += 1
                if self.calls == 1:
                    return Resp(200, [payload[:split_at], TimeoutError("stalled")], {"Content-Length": str(len(payload))})
                return Resp(
                    206, [payload[split_at:]],
                    {"Content-Range": f"bytes {split_at}-{len(payload)-1}/{len(payload)}", "Content-Length": str(len(payload) - split_at)},
                )

        t1 = self.root / "ok.apk"
        r.download_asset(
            "https://github.com/o/r/releases/download/v2.2.0/ok.apk",
            t1, expected_size=len(payload), expected_sha256=expected_sha, opener=ResumeOpener(), sleep_fn=lambda _: None,
        )
        self.assertEqual(t1.read_bytes(), payload)

        # 2. Bad Content-Range cases (missing header, wrong offset, wrong total, wildcard '*' when expected_size known, body span mismatch) must FAIL
        for bad_hdr, body_chunks in (
            ({}, [payload[split_at:]]),
            ({"Content-Range": f"bytes 0-{len(payload)-1}/{len(payload)}"}, [payload[split_at:]]),
            ({"Content-Range": f"bytes {split_at}-{len(payload)-1}/999999"}, [payload[split_at:]]),
            ({"Content-Range": f"bytes {split_at}-{len(payload)-1}/*"}, [payload[split_at:]]),
            ({"Content-Range": f"bytes {split_at}-{len(payload)-1}/{len(payload)}"}, [payload[split_at:-10]]),
        ):
            t_bad = self.root / "bad_cr.apk"
            bad_url = "https://github.com/o/r/releases/download/v2.2.0/bad_cr.apk"
            t_bad.with_name("bad_cr.apk.part").write_bytes(payload[:split_at])
            r.write_json(
                t_bad.with_name("bad_cr.apk.part.meta.json"),
                {"url": r.sanitize_url_for_log(bad_url), "expectedSize": len(payload), "expectedSha256": expected_sha},
            )
            opener_bad = SimpleNamespace(open=lambda req, timeout=None, h=bad_hdr, c=body_chunks: Resp(206, c, h))
            with self.assertRaises(r.ReleaseError):
                r.download_asset(
                    bad_url,
                    t_bad, expected_size=len(payload), expected_sha256=expected_sha, opener=opener_bad, sleep_fn=lambda _: None,
                )
            self.assertFalse(t_bad.exists())
            self.assertFalse(t_bad.with_name("bad_cr.apk.part").exists())
            self.assertFalse(t_bad.with_name("bad_cr.apk.part.meta.json").exists())

        # 2c. Non-retryable HTTP error (e.g. 404) must fail immediately and clean pre-existing .part/.part.meta.json
        t_404 = self.root / "missing.apk"
        url_404 = "https://github.com/o/r/releases/download/v2.2.0/missing.apk"
        t_404.with_name("missing.apk.part").write_bytes(payload[:split_at])
        r.write_json(
            t_404.with_name("missing.apk.part.meta.json"),
            {"url": r.sanitize_url_for_log(url_404), "expectedSize": len(payload), "expectedSha256": expected_sha},
        )
        hits_404 = {"n": 0}
        def _open_404(req, timeout=None):
            hits_404["n"] += 1
            raise urllib.error.HTTPError(url_404, 404, "Not Found", {}, io.BytesIO(b""))
        with self.assertRaisesRegex(r.ReleaseError, "HTTP 404"):
            r.download_asset(
                url_404, t_404, expected_size=len(payload), expected_sha256=expected_sha,
                opener=SimpleNamespace(open=_open_404), sleep_fn=lambda _: None,
            )
        self.assertEqual(hits_404["n"], 1)
        self.assertFalse(t_404.with_name("missing.apk.part").exists())
        self.assertFalse(t_404.with_name("missing.apk.part.meta.json").exists())

        # 2b. Unprovenanced or mismatched-source .part must be discarded and restarted cleanly from offset 0
        t_orphan = self.root / "orphan.apk"
        t_orphan.with_name("orphan.apk.part").write_bytes(payload[:split_at])
        seen_orphan_ranges = []
        def _open_orphan(req, timeout=None):
            seen_orphan_ranges.append(req.headers.get("Range"))
            return Resp(200, [payload], {"Content-Length": str(len(payload))})
        r.download_asset(
            "https://github.com/o/r/releases/download/v2.2.0/orphan.apk",
            t_orphan, expected_size=len(payload), expected_sha256=expected_sha,
            opener=SimpleNamespace(open=_open_orphan), sleep_fn=lambda _: None,
        )
        self.assertEqual(seen_orphan_ranges, [None])
        self.assertEqual(t_orphan.read_bytes(), payload)
        self.assertFalse(t_orphan.with_name("orphan.apk.part.meta.json").exists())

        # 3. Short EOF and oversized stream must NOT promote .part
        t_short = self.root / "short.apk"
        opener_short = SimpleNamespace(open=lambda req, timeout=None: Resp(200, [payload[:100]]))
        with self.assertRaises(r.ReleaseError):
            r.download_asset(
                "https://github.com/o/r/releases/download/v2.2.0/short.apk",
                t_short, expected_size=len(payload), expected_sha256=expected_sha, opener=opener_short, sleep_fn=lambda _: None,
            )
        self.assertFalse(t_short.exists())

        t_over = self.root / "over.apk"
        opener_over = SimpleNamespace(open=lambda req, timeout=None: Resp(200, [payload + b"extra"]))
        with self.assertRaisesRegex(r.ReleaseError, "exceeded allowed size"):
            r.download_asset(
                "https://github.com/o/r/releases/download/v2.2.0/over.apk",
                t_over, expected_size=len(payload), expected_sha256=expected_sha, opener=opener_over, sleep_fn=lambda _: None,
            )
        self.assertFalse(t_over.exists())

        # 4. Pre-existing full-length .part (corrupt OR matching) must NOT bypass remote download
        t_stale = self.root / "stale.apk"
        t_stale.with_name("stale.apk.part").write_bytes(payload)
        remote_hits = {"n": 0}
        def _counting_open(req, timeout=None):
            remote_hits["n"] += 1
            return Resp(200, [payload], {"Content-Length": str(len(payload))})
        r.download_asset(
            "https://github.com/o/r/releases/download/v2.2.0/stale.apk",
            t_stale, expected_size=len(payload), expected_sha256=expected_sha,
            opener=SimpleNamespace(open=_counting_open), sleep_fn=lambda _: None,
        )
        self.assertEqual(remote_hits["n"], 1)
        self.assertEqual(t_stale.read_bytes(), payload)

        # 5. Real multi-hop redirect + blocking socket read AND continuous trickle stream + MAX_REDIRECTS > 10 enforcement
        import http.server, threading
        visited_paths = []
        class RedirectThenStallHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                visited_paths.append(self.path)
                if self.path == "/hop1":
                    time.sleep(0.35)
                    self.send_response(302)
                    self.send_header("Location", "/hop2?X-Amz-Signature=ok")
                    self.end_headers()
                elif self.path.startswith("/hop2"):
                    time.sleep(0.35)
                    self.send_response(302)
                    self.send_header("Location", "/final?X-Amz-Signature=ok")
                    self.end_headers()
                elif self.path.startswith("/chain/"):
                    hop_n = int(self.path.split("/")[-1])
                    if hop_n <= 12:
                        self.send_response(302)
                        self.send_header("Location", f"/chain/{hop_n + 1}")
                        self.end_headers()
                    else:
                        self.send_response(200); self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"ok")
                elif self.path == "/trickle":
                    self.send_response(200)
                    self.send_header("Content-Length", "40")
                    self.end_headers()
                    for _ in range(40):
                        try:
                            self.wfile.write(b"x")
                            self.wfile.flush()
                            time.sleep(0.15)
                        except OSError:
                            break
                else:
                    self.send_response(200)
                    self.send_header("Content-Length", "10")
                    self.end_headers()
                    self.wfile.write(b"01234")
                    self.wfile.flush()
                    time.sleep(2.0)  # Stall longer than remaining ~0.3s deadline!
            def log_message(self, *args): pass

        httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), RedirectThenStallHandler)
        port = httpd.server_address[1]
        t_srv = threading.Thread(target=httpd.serve_forever, daemon=True)
        t_srv.start()
        t_deadline = self.root / "deadline.apk"
        t_trickle = self.root / "trickle.apk"
        t_redir = self.root / "toomany.apk"
        try:
            with patch.object(r, "require_https_url", return_value=None):
                t0 = time.monotonic()
                with self.assertRaisesRegex(r.ReleaseError, "data read"):
                    r.download_asset(
                        f"http://127.0.0.1:{port}/hop1",
                        t_deadline, expected_size=10,
                        expected_sha256=hashlib.sha256(b"0123456789").hexdigest().upper(),
                        download_timeout=5, total_timeout=1.0, sleep_fn=lambda _: None,
                    )
                elapsed = time.monotonic() - t0
                self.assertIn("/final?X-Amz-Signature=ok", visited_paths)
                self.assertLess(elapsed, 2.2)
                self.assertFalse(t_deadline.exists())

                # Continuous trickle stream (0.15s/byte * 40 bytes = 6s) MUST be cut off repeatedly at data read stage!
                for trial in range(3):
                    t_trickle_run = self.root / f"trickle_{trial}.apk"
                    t1 = time.monotonic()
                    with self.assertRaisesRegex(r.ReleaseError, "data read"):
                        r.download_asset(
                            f"http://127.0.0.1:{port}/trickle",
                            t_trickle_run, expected_size=40,
                            expected_sha256=hashlib.sha256(b"x" * 40).hexdigest().upper(),
                            download_timeout=5, total_timeout=0.6, sleep_fn=lambda _: None,
                        )
                    self.assertLess(time.monotonic() - t1, 2.0)
                    self.assertFalse(t_trickle_run.exists())
                    self.assertFalse(t_trickle_run.with_name(f"trickle_{trial}.apk.part").exists())
                    self.assertFalse(t_trickle_run.with_name(f"trickle_{trial}.apk.part.meta.json").exists())

                # >10 redirects must fail cleanly with Too many redirects (HTTP 302)
                with self.assertRaisesRegex(r.ReleaseError, "HTTP 302"):
                    r.download_asset(
                        f"http://127.0.0.1:{port}/chain/1",
                        t_redir, expected_size=2,
                        expected_sha256=hashlib.sha256(b"ok").hexdigest().upper(),
                        download_timeout=5, total_timeout=5.0, sleep_fn=lambda _: None,
                    )
        finally:
            httpd.shutdown()
            httpd.server_close()

        # 6. Post-SHA-256 expired deadline prevents promotion
        t_posthash = self.root / "posthash.apk"
        real_sha = r.sha256
        def _slow_sha(p):
            time.sleep(1.1)
            return real_sha(p)
        with patch.object(r, "sha256", side_effect=_slow_sha):
            with self.assertRaisesRegex(r.ReleaseError, "total timeout"):
                r.download_asset(
                    "https://github.com/o/r/releases/download/v2.2.0/posthash.apk",
                    t_posthash, expected_size=10,
                    expected_sha256=hashlib.sha256(b"0123456789").hexdigest().upper(),
                    download_timeout=5, total_timeout=1.0,
                    opener=SimpleNamespace(open=lambda req, timeout=None: Resp(200, [b"0123456789"], {"Content-Length": "10"})),
                    sleep_fn=lambda _: None,
                )
        self.assertFalse(t_posthash.exists())

    def test_verify_clears_stale_report_before_local_manifest_failure_and_uses_anonymous_flags(self) -> None:
        plan = r.make_plan(self.root)
        directory = Path(plan["assets"])
        directory.mkdir(parents=True)
        evidence = Path(plan["evidence"])
        evidence.mkdir(parents=True)
        stale_report = evidence / "recipient-verification.json"
        r.write_json(stale_report, dict(status="PASS", verifyMode="full", downloadVerified=True))

        names = [
            f"zhixu-oriva-{plan['version']}.apk",
            f"zhixu-oriva-{plan['version']}-source.zip",
            "oriva-release.cer",
            "release-manifest.json",
        ]
        for asset in names[:3]:
            (directory / asset).write_bytes(asset.encode("utf8"))
        commit = "c" * 40
        r.write_json(directory / "release-manifest.json", dict(versionName="WRONG-VERSION", commit=commit, certificateSHA256="A" * 64))
        (directory / "SHA256SUMS").write_text("".join(r.sha256(directory / a) + "  " + a + "\n" for a in names), encoding="utf8")

        args = SimpleNamespace(
            repo="owner/repo", clone_check=False, verify_mode="api-digest",
            download_timeout=60, download_total_timeout=300, git_timeout=120,
        )
        # Failing local manifest check MUST already have deleted stale recipient-verification.json!
        with self.assertRaisesRegex(r.ReleaseError, "Asset version differs"):
            r.verify(self.root, plan, args, {})
        self.assertFalse(stale_report.exists())

        # Fix manifest and test anonymous git flags + api-digest atomic receipt
        r.write_json(stale_report, dict(status="PASS", verifyMode="full", downloadVerified=True))
        r.write_json(directory / "release-manifest.json", dict(versionName=plan["version"], commit=commit, certificateSHA256="A" * 64))
        (directory / "SHA256SUMS").write_text("".join(r.sha256(directory / a) + "  " + a + "\n" for a in names), encoding="utf8")

        with patch.object(r.urllib.request, "build_opener", return_value=SimpleNamespace(open=lambda req, timeout=None: (_ for _ in ()).throw(r.ReleaseError("network error")))):
            with self.assertRaises(r.ReleaseError):
                r.verify(self.root, plan, args, {})
        self.assertFalse(stale_report.exists())

        all_names = names + ["SHA256SUMS"]
        release_payload = {
            "draft": False,
            "tag_name": plan["tag"],
            "html_url": "https://github.com/owner/repo/releases/tag/" + plan["tag"],
            "assets": [
                {
                    "name": n,
                    "size": (directory / n).stat().st_size,
                    "digest": "sha256:" + r.sha256(directory / n),
                    "browser_download_url": f"https://github.com/owner/repo/releases/download/{plan['tag']}/{n}",
                }
                for n in all_names
            ],
        }

        class FakeMetaResp:
            def __init__(self, data: bytes): self._buf = io.BytesIO(data)
            def __enter__(self): return self._buf
            def __exit__(self, *a): pass

        seen_git_args: list[tuple] = []
        def _checking_git(root, *gargs, timeout=None, env=None, anonymous=False):
            seen_git_args.append((gargs, env, anonymous))
            return f"{commit}\trefs/tags/{plan['tag']}"

        # Local .git/config rewrites or extraHeader must fail verify() BEFORE anonymous ls-remote runs!
        subprocess.run(["git", "init", "-b", "main"], cwd=self.root, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for bad_cfg_k, bad_cfg_v, pat in (
            ("url.https://evil.example.org/.insteadOf", "https://github.com/", "rewrite rule"),
            ("http.https://github.com.sslVerify", "false", "TLS verification downgrade"),
            ("http.https://github.com.followRedirects", "true", "HTTP redirect rule"),
            ("http.https://github.com.extraHeader", "Authorization: Bearer leak", "undeclared HTTP header"),
            ("http.cookieFile", "/tmp/evil.cookies", "cookie file"),
            ("http.saveCookies", "true", "cookie saving"),
            ("core.sshCommand", "ssh -o ProxyCommand=evil", "SSH command|core.sshCommand"),
        ):
            subprocess.run(["git", "config", bad_cfg_k, bad_cfg_v], cwd=self.root, check=True)
            with (
                patch.object(r.urllib.request, "build_opener", return_value=SimpleNamespace(open=lambda req, timeout=None: FakeMetaResp(json.dumps(release_payload).encode("utf8")))),
                patch.object(r, "git", _checking_git),
            ):
                with self.assertRaisesRegex(r.ReleaseError, pat):
                    r.verify(self.root, plan, args, {})
            self.assertEqual(seen_git_args, [])
            subprocess.run(["git", "config", "--unset", bad_cfg_k], cwd=self.root, check=True)

        with (
            patch.object(r.urllib.request, "build_opener", return_value=SimpleNamespace(open=lambda req, timeout=None: FakeMetaResp(json.dumps(release_payload).encode("utf8")))),
            patch.object(r, "git", _checking_git),
        ):
            r.verify(self.root, plan, args, {})

        self.assertTrue(any(
            anon and "core.askPass=" in gargs and "http.sslVerify=true" in gargs and "credential.helper=" in gargs
            and "http.cookieFile=" in gargs and "http.saveCookies=false" in gargs
            for gargs, _, anon in seen_git_args
        ))
        report = json.loads(stale_report.read_text(encoding="utf8"))
        self.assertEqual(report["verifyMode"], "api-digest")
        self.assertFalse(report["downloadVerified"])

    def test_r4_real_anonymous_git_ignores_netrc_and_clone_check_recreates_fresh_dir(self) -> None:
        import http.server, threading

        received_headers = []
        class GitProbeHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                received_headers.append({k.lower(): v for k, v in self.headers.items()})
                self.send_response(404)
                self.end_headers()
            def log_message(self, *args): pass

        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), GitProbeHandler)
        port = srv.server_address[1]
        t_srv = threading.Thread(target=srv.serve_forever, daemon=True)
        t_srv.start()
        try:
            fake_home = self.root / "fake_home"
            fake_home.mkdir()
            (fake_home / ".netrc").write_text(f"machine 127.0.0.1 login netrcuser password netrcpass\n", encoding="utf8")
            (fake_home / "_netrc").write_text(f"machine 127.0.0.1 login netrcuser password netrcpass\n", encoding="utf8")
            cookie_file = fake_home / "cookies.txt"
            cookie_file.write_text("127.0.0.1\tFALSE\t/\tFALSE\t2147483647\tsid\tsecret_cookie\n", encoding="utf8")
            (fake_home / ".gitconfig").write_text(f"[http]\n\tcookieFile = {cookie_file.as_posix()}\n", encoding="utf8")

            host_env = {**os.environ, "HOME": str(fake_home), "USERPROFILE": str(fake_home)}
            with r.isolated_git_env(host_env, anonymous=True) as clean_env:
                with self.assertRaises(r.ReleaseError):
                    r._call_git(
                        self.root,
                        "-c", "credential.helper=", "-c", "core.askPass=",
                        "-c", "http.cookieFile=", "-c", "http.saveCookies=false",
                        "-c", "http.followRedirects=false", "-c", "http.sslVerify=true",
                        "ls-remote", f"http://127.0.0.1:{port}/owner/repo.git", "refs/tags/v2.2.0",
                        timeout=10, env=clean_env, anonymous=True,
                    )
            self.assertGreaterEqual(len(received_headers), 1)
            for hdrs in received_headers:
                self.assertNotIn("authorization", hdrs)
                self.assertNotIn("cookie", hdrs)
        finally:
            srv.shutdown()
            srv.server_close()
            t_srv.join(timeout=1)

        # verify --clone-check must remove pre-existing stale recipient-clone and run fresh anonymous clone
        plan = r.make_plan(self.root)
        directory = Path(plan["assets"])
        directory.mkdir(parents=True)
        evidence = Path(plan["evidence"])
        evidence.mkdir(parents=True)
        stale_clone = evidence / "recipient-clone"
        stale_clone.mkdir()
        (stale_clone / "TAMPERED.txt").write_text("stale", encoding="utf8")

        subprocess.run(["git", "init", "-b", "main"], cwd=self.root, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=self.root, check=True)
        (self.root / "README.md").write_text("hello", encoding="utf8")
        subprocess.run(["git", "add", "README.md", "android/app/build.gradle.kts"], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-m", "init"], cwd=self.root, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()

        apk = f"zhixu-oriva-{plan['version']}.apk"
        src_zip = f"zhixu-oriva-{plan['version']}-source.zip"
        cer = "oriva-release.cer"
        manifest = "release-manifest.json"
        (directory / apk).write_bytes(b"apk")
        (directory / cer).write_bytes(b"cer")
        r.write_json(directory / manifest, dict(versionName=plan["version"], commit=commit, certificateSHA256="A" * 64))
        r.archive_source(self.root, commit, plan["version"], directory / src_zip)
        names = [apk, src_zip, cer, manifest]
        (directory / "SHA256SUMS").write_text("".join(r.sha256(directory / a) + "  " + a + "\n" for a in names), encoding="utf8")

        all_names = names + ["SHA256SUMS"]
        payloads = {n: (directory / n).read_bytes() for n in all_names}
        release_payload = {
            "draft": False,
            "tag_name": plan["tag"],
            "html_url": "https://github.com/owner/repo/releases/tag/" + plan["tag"],
            "assets": [
                {
                    "name": n,
                    "size": len(payloads[n]),
                    "digest": "sha256:" + hashlib.sha256(payloads[n]).hexdigest(),
                    "browser_download_url": f"https://github.com/owner/repo/releases/download/{plan['tag']}/{n}",
                }
                for n in all_names
            ],
        }
        class MetaResp:
            def __init__(self, data: bytes): self._b = io.BytesIO(data)
            def __enter__(self): return self._b
            def __exit__(self, *a): pass

        clone_cmds = []
        def _fake_clone_exec(cmd, cwd, env, log_path=None, timeout=None):
            clone_cmds.append(cmd)
            dest = Path(cmd[-1])
            subprocess.run(["git", "clone", "--quiet", "--config", "core.autocrlf=false", str(self.root), str(dest)], check=True)
            return ""

        def _fake_download(url, target, expected_size=None, expected_sha256=None, download_timeout=None, total_timeout=None):
            name = url.rsplit("/", 1)[-1]
            target.write_bytes(payloads[name])

        args = SimpleNamespace(
            repo="owner/repo", clone_check=True, verify_mode="full",
            download_timeout=60, download_total_timeout=300, git_timeout=120,
        )
        with (
            patch.object(r.urllib.request, "build_opener", return_value=SimpleNamespace(open=lambda req, timeout=None: MetaResp(json.dumps(release_payload).encode("utf8")))),
            patch.object(r, "_call_git", side_effect=lambda *gargs, **kw: (f"{commit}\trefs/tags/{plan['tag']}" if "ls-remote" in gargs else subprocess.check_output(["git", *gargs[1:]], cwd=gargs[0], text=True).strip())),
            patch.object(r, "_call_execute", side_effect=_fake_clone_exec),
            patch.object(r, "download_asset", side_effect=_fake_download),
        ):
            r.verify(self.root, plan, args, {})

        self.assertFalse((stale_clone / "TAMPERED.txt").exists())
        self.assertEqual(len(clone_cmds), 1)
        self.assertIn("http.cookieFile=", clone_cmds[0])
        self.assertIn("http.saveCookies=false", clone_cmds[0])

    def test_r5_slow_tls_handshake_aborted_at_total_timeout_without_part_leak(self) -> None:
        import socket, threading

        stop_evt = threading.Event()
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.bind(("127.0.0.1", 0))
        srv.listen(4)
        srv.settimeout(0.2)
        port = srv.getsockname()[1]

        conns = []
        def _accept_loop():
            while not stop_evt.is_set():
                try:
                    c, _ = srv.accept()
                    conns.append(c)
                except socket.timeout:
                    continue
                except OSError:
                    break

        t = threading.Thread(target=_accept_loop, daemon=True)
        t.start()
        target = self.root / "slow_tls.apk"
        try:
            with patch.object(r, "require_https_url", return_value=None):
                t0 = time.monotonic()
                with self.assertRaisesRegex(r.ReleaseError, "TLS handshake"):
                    r.download_asset(
                        f"https://127.0.0.1:{port}/slow-tls.apk",
                        target,
                        expected_size=10,
                        expected_sha256="A" * 64,
                        download_timeout=10,
                        total_timeout=0.7,
                        sleep_fn=lambda _: None,
                    )
                self.assertLess(time.monotonic() - t0, 1.8)
            self.assertFalse(target.exists())
            self.assertFalse(target.with_name("slow_tls.apk.part").exists())
            self.assertFalse(target.with_name("slow_tls.apk.part.meta.json").exists())
        finally:
            stop_evt.set()
            srv.close()
            for c in conns:
                try:
                    c.close()
                except OSError:
                    pass
            t.join(timeout=1)

    def test_r5_backoff_deadline_exhaustion_preserves_stage_and_retryable_http_clears_part(self) -> None:
        payload = b"0123456789ABCDEF" * 64
        expected_sha = hashlib.sha256(payload).hexdigest().upper()

        class ChunkResp:
            def __init__(self, status: int, chunks: list, headers: dict[str, str] | None = None):
                self.status = status
                self._chunks = list(chunks)
                self.headers = headers or {}
            def __enter__(self): return self
            def __exit__(self, *a): pass
            def read(self, size: int = -1) -> bytes:
                if not self._chunks:
                    return b""
                nxt = self._chunks.pop(0)
                if isinstance(nxt, BaseException):
                    raise nxt
                return nxt

        # 1a. When DOWNLOAD_BACKOFF_SECONDS[0] >= remaining budget, backoff must NOT sleep into 'before starting'; must raise previous stage error!
        now_box = [1000.0]
        def _fake_monotonic():
            return now_box[0]

        def _stall_open(req, timeout=None):
            # Advance clock inside read to leave only 0.5s (< 2s backoff)
            def _slow_fail():
                now_box[0] += 0.5
                return TimeoutError("read stall")
            return ChunkResp(200, [payload[:100], TimeoutError("read stall")], {"Content-Length": str(len(payload))})

        t_clock = self.root / "clock_backoff.apk"
        def _advancing_open(req, timeout=None):
            now_box[0] += 9.5  # total_timeout=10.0 -> remaining=0.5s (< backoff 2s)
            return ChunkResp(200, [payload[:64], TimeoutError("read stall")], {"Content-Length": str(len(payload))})
        sleep_calls: list[float] = []
        with patch.object(r.time, "monotonic", side_effect=_fake_monotonic):
            with self.assertRaises(r.ReleaseError) as ctx1:
                r.download_asset(
                    "https://github.com/o/r/releases/download/v2.2.0/clock.apk",
                    t_clock, expected_size=len(payload), expected_sha256=expected_sha,
                    total_timeout=10.0,
                    opener=SimpleNamespace(open=_advancing_open),
                    sleep_fn=lambda s: sleep_calls.append(s),
                )
        msg1 = str(ctx1.exception)
        self.assertIn("during data read", msg1)
        self.assertIn("read stall", msg1)
        self.assertNotIn("before starting", msg1)
        self.assertEqual(sleep_calls, [])
        self.assertFalse(t_clock.with_name("clock_backoff.apk.part").exists())
        self.assertFalse(t_clock.with_name("clock_backoff.apk.part.meta.json").exists())

        # 1b. When sleep_fn itself advances clock across deadline, still preserves stage & error (never enters next loop 'before starting')
        now_box[0] = 2000.0
        t_sleep_over = self.root / "sleep_over.apk"
        def _open_plenty_time(req, timeout=None):
            now_box[0] += 1.0  # remaining=9.0s > backoff 2.0s
            return ChunkResp(200, [payload[:64], TimeoutError("read stall after sleep")], {"Content-Length": str(len(payload))})
        def _slow_sleep(sec: float):
            now_box[0] += 15.0  # pushes past total_timeout=10.0
        with patch.object(r.time, "monotonic", side_effect=_fake_monotonic):
            with self.assertRaises(r.ReleaseError) as ctx2:
                r.download_asset(
                    "https://github.com/o/r/releases/download/v2.2.0/sleep_over.apk",
                    t_sleep_over, expected_size=len(payload), expected_sha256=expected_sha,
                    total_timeout=10.0,
                    opener=SimpleNamespace(open=_open_plenty_time),
                    sleep_fn=_slow_sleep,
                )
        msg2 = str(ctx2.exception)
        self.assertIn("during data read", msg2)
        self.assertIn("read stall after sleep", msg2)
        self.assertNotIn("before starting", msg2)
        self.assertFalse(t_sleep_over.with_name("sleep_over.apk.part").exists())

        # 2. Retryable HTTP 429/503 MUST clear existing .part & .part.meta.json BEFORE next retry (restarting cleanly from byte 0)
        for http_err_code in (429, 503):
            t_http = self.root / f"retry_{http_err_code}.apk"
            url_http = f"https://github.com/o/r/releases/download/v2.2.0/retry_{http_err_code}.apk"
            seen_ranges: list[str | None] = []
            part_exists_on_retry: list[bool] = []
            state = {"attempt": 0}

            def _http_retry_open(req, timeout=None, st=state, ranges=seen_ranges, exists_log=part_exists_on_retry, code=http_err_code, target_p=t_http, u=url_http):
                st["attempt"] += 1
                ranges.append(req.headers.get("Range"))
                if st["attempt"] == 1:
                    return ChunkResp(200, [payload[:200], TimeoutError("transient stall")], {"Content-Length": str(len(payload))})
                if st["attempt"] == 2:
                    raise urllib.error.HTTPError(u, code, "Retryable", {}, io.BytesIO(b""))
                exists_log.append(target_p.with_name(target_p.name + ".part").exists() or target_p.with_name(target_p.name + ".part.meta.json").exists())
                return ChunkResp(200, [payload], {"Content-Length": str(len(payload))})

            r.download_asset(
                url_http, t_http,
                expected_size=len(payload), expected_sha256=expected_sha,
                opener=SimpleNamespace(open=_http_retry_open),
                sleep_fn=lambda _: None,
            )
            self.assertEqual(len(seen_ranges), 3)
            self.assertIsNone(seen_ranges[0])
            self.assertEqual(seen_ranges[1], "bytes=200-")
            self.assertIsNone(seen_ranges[2], f"HTTP {http_err_code} retry must clear .part and restart without Range")
            self.assertEqual(part_exists_on_retry, [False])
            self.assertEqual(t_http.read_bytes(), payload)


if __name__ == "__main__":
    unittest.main()
