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
                    u = subprocess.check_output([sh_exe, str(helper), "Username for 'https://github.com': "], env=git_env, text=True).strip()
                    p = subprocess.check_output([sh_exe, str(helper), "Password for 'https://x-access-token@github.com': "], env=git_env, text=True).strip()
                    p_repo = subprocess.check_output([sh_exe, str(helper), "Password for 'https://github.com/owner/repo.git': "], env=git_env, text=True).strip()
                else:
                    u = subprocess.check_output([str(helper), "Username for 'https://github.com': "], env=git_env, text=True).strip()
                    p = subprocess.check_output([str(helper), "Password for 'https://x-access-token@github.com': "], env=git_env, text=True).strip()
                    p_repo = subprocess.check_output([str(helper), "Password for 'https://github.com/owner/repo.git': "], env=git_env, text=True).strip()
                self.assertEqual(u, "x-access-token")
                self.assertEqual(p, token_val)
                self.assertEqual(p_repo, token_val)

                # Non-target hosts, HTTP, custom ports, wrong repo path, wrong user, trailing text, and unknown prompts must FAIL
                for bad_prompt in (
                    "Username for 'https://evil.example.org': ",
                    "Password for 'http://x-access-token@github.com': ",
                    "Password for 'https://x-access-token@github.com:8443': ",
                    "Password for 'https://other-user@github.com': ",
                    "Password for 'https://x-access-token@github.com/other/repo.git': ",
                    "Username for 'https://github.com' extra",
                    "Password for 'https://x-access-token@github.com?x=1': ",
                    "Enter passphrase for key: ",
                ):
                    proc = subprocess.run([sys.executable, str(py_file), bad_prompt], env=git_env, capture_output=True, text=True)
                    self.assertNotEqual(proc.returncode, 0, bad_prompt)

            self.assertFalse((special_base / "ak").exists())

        # Cleanup failure must surface visibly AND redact token
        with patch.object(r.shutil, "rmtree", side_effect=PermissionError(f"locked {token_val}")):
            with self.assertRaises(r.ReleaseError) as ctx:
                with r.git_askpass_env(os.environ.copy(), token_val):
                    pass
            self.assertIn("Failed to remove temporary GIT_ASKPASS", str(ctx.exception))
            self.assertNotIn(token_val, str(ctx.exception))

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
        cleaned = r.sanitize_git_env(dirty, empty_gitconfig_path="/tmp/empty.cfg")
        self.assertEqual(cleaned["GIT_TRACE"], "0")
        self.assertEqual(cleaned["GIT_TRACE_CURL"], "0")
        self.assertEqual(cleaned["GIT_CURL_VERBOSE"], "0")
        self.assertEqual(cleaned["GIT_TERMINAL_PROMPT"], "0")
        self.assertEqual(cleaned["GIT_CONFIG_NOSYSTEM"], "1")
        self.assertEqual(cleaned["GIT_CONFIG_GLOBAL"], "/tmp/empty.cfg")
        for forbidden in ("GIT_SSL_NO_VERIFY", "GIT_TRACE2_PERF", "GCM_INTERACTIVE", "GIT_CREDENTIAL_HELPER", "GIT_CONFIG_PARAMETERS", "GIT_ASKPASS", "SSH_ASKPASS"):
            self.assertNotIn(forbidden, cleaned)

        secret = "synthetic-secret-token-999"
        b64_auth = base64.b64encode(("x-access-token:" + secret).encode("utf8")).decode("ascii")
        log = self.root / "leak.log"
        env = os.environ.copy()
        env["ORIVA_GIT_TOKEN"] = secret
        with self.assertRaises(r.ReleaseError):
            r.execute(
                [sys.executable, "-c", f"import sys; sys.stderr.write('tok={secret} Authorization: Basic {b64_auth} https://tok@github.com/o/r?X-Amz-Signature=sigsecret\\n'); sys.exit(2)"],
                self.root, env, log,
            )
        log_text = log.read_text(encoding="utf8")
        self.assertNotIn(secret, log_text)
        self.assertNotIn(b64_auth, log_text)
        self.assertNotIn("sigsecret", log_text)
        self.assertNotIn("tok@", log_text)
        self.assertIn("[REDACTED]", log_text)

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

        # Verify termination diagnostic warning is surfaced (never silently swallowed) when tree termination fails
        diag_log = self.root / "tree-diag.log"
        if os.name == "nt":
            with patch.object(r, "_create_windows_job_for_process", return_value=(None, "simulated-job-attach-failure")):
                with patch.object(r.subprocess, "run", side_effect=OSError("simulated taskkill failure")):
                    with self.assertRaisesRegex(r.ReleaseError, "timed out"):
                        r.execute([sys.executable, "-c", "import time; time.sleep(5)"], self.root, env, diag_log, timeout=0.3)
            self.assertIn("TREE_CLEANUP_DIAG", diag_log.read_text(encoding="utf8"))
        else:
            with patch.object(r.os, "killpg", side_effect=PermissionError("simulated EPERM")):
                with self.assertRaisesRegex(r.ReleaseError, "timed out"):
                    r.execute([sys.executable, "-c", "import time; time.sleep(5)"], self.root, env, diag_log, timeout=0.3)
            self.assertIn("TREE_CLEANUP_DIAG", diag_log.read_text(encoding="utf8"))

    def test_r1_publish_https_origin_routes_via_askpass_and_disables_redirects(self) -> None:
        plan = r.make_plan(self.root)
        head, notes = self._prepare_packaged_assets(plan)
        secret_token = "synthetic-release-token-secret"
        args = SimpleNamespace(repo="owner/repo", notes=str(notes), push_protocol="auto", git_timeout=120, upload_timeout=900)
        seen_cmds: list[list[str]] = []

        original_git = r.git

        def fake_git(root, *items, timeout=None, env=None):
            if items == ("remote", "get-url", "origin"):
                return "https://github.com/owner/repo.git"
            if "ls-remote" in items:
                self.assertIn("http.followRedirects=false", items)
                self.assertIn("GIT_ASKPASS", env or {})
                return ""
            return original_git(root, *items)

        def fake_execute(cmd, cwd, exec_env, logfile=None, timeout=None):
            seen_cmds.append(list(map(str, cmd)))
            if cmd[:3] == ["git", "tag", "-a"]:
                self.git(*cmd[1:])
            elif "push" in cmd:
                self.assertIn("http.followRedirects=false", cmd)
                self.assertIn("credential.helper=", cmd)
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
            t_bad.with_name("bad_cr.apk.part").write_bytes(payload[:split_at])
            opener_bad = SimpleNamespace(open=lambda req, timeout=None, h=bad_hdr, c=body_chunks: Resp(206, c, h))
            with self.assertRaises(r.ReleaseError):
                r.download_asset(
                    "https://github.com/o/r/releases/download/v2.2.0/bad_cr.apk",
                    t_bad, expected_size=len(payload), expected_sha256=expected_sha, opener=opener_bad, sleep_fn=lambda _: None,
                )
            self.assertFalse(t_bad.exists())

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

        # 5. Real multi-hop redirect + blocking socket read shares total_timeout=1.0 deadline and interrupts during read()
        import http.server, threading
        class RedirectThenStallHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
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
        try:
            with patch.object(r, "require_https_url", return_value=None):
                t0 = time.monotonic()
                with self.assertRaises(r.ReleaseError):
                    r.download_asset(
                        f"http://127.0.0.1:{port}/hop1",
                        t_deadline, expected_size=10,
                        expected_sha256=hashlib.sha256(b"0123456789").hexdigest().upper(),
                        download_timeout=5, total_timeout=1.0, sleep_fn=lambda _: None,
                    )
                elapsed = time.monotonic() - t0
            self.assertLess(elapsed, 2.2)
            self.assertFalse(t_deadline.exists())
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
        def _checking_git(root, *gargs, timeout=None, env=None):
            seen_git_args.append((gargs, env))
            return f"{commit}\trefs/tags/{plan['tag']}"

        with (
            patch.object(r.urllib.request, "build_opener", return_value=SimpleNamespace(open=lambda req, timeout=None: FakeMetaResp(json.dumps(release_payload).encode("utf8")))),
            patch.object(r, "git", _checking_git),
        ):
            r.verify(self.root, plan, args, {})

        self.assertTrue(any("core.askPass=" in gargs and "http.sslVerify=true" in gargs and "credential.helper=" in gargs for gargs, _ in seen_git_args))
        report = json.loads(stale_report.read_text(encoding="utf8"))
        self.assertEqual(report["verifyMode"], "api-digest")
        self.assertFalse(report["downloadVerified"])


if __name__ == "__main__":
    unittest.main()
