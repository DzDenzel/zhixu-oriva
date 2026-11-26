"""Offline unit and adversarial tests for release.py T1-T3 hardening."""

from __future__ import annotations

import hashlib
import http.client
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import zipfile

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release.py"))
r = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r)


class T1MavenIsolationTests(unittest.TestCase):
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

    def test_make_plan_defaults_maven_repo_into_work_and_supports_settings(self) -> None:
        plan = r.make_plan(self.root)
        mvn_cmd = plan["commands"][2]
        expected_repo = str((self.root / "work/maven-repo").resolve())
        self.assertEqual(plan["mavenRepo"], expected_repo)
        self.assertIn("-B", mvn_cmd)
        self.assertIn("-ntp", mvn_cmd)
        self.assertIn("-Dmaven.repo.local=" + expected_repo, mvn_cmd)
        self.assertNotIn("-s", mvn_cmd)

        custom_repo = self.root / "custom repo with spaces"
        settings = self.root / "settings.xml"
        settings.write_text("<settings/>", encoding="utf8")
        custom_plan = r.make_plan(self.root, maven_repo=custom_repo, maven_settings=settings)
        custom_mvn = custom_plan["commands"][2]
        self.assertIn("-Dmaven.repo.local=" + str(custom_repo.resolve()), custom_mvn)
        self.assertIn("-s", custom_mvn)
        self.assertIn(str(settings.resolve()), custom_mvn)

    def test_prepare_backend_env_strips_maven_args_and_sets_utf8_encoding(self) -> None:
        env = {"MAVEN_ARGS": "-Dmaven.repo.local=bad", "MAVEN_OPTS": "-Xmx1g", "PATH": "x"}
        cleaned = r.prepare_backend_env(env)
        self.assertNotIn("MAVEN_ARGS", cleaned)
        self.assertEqual(cleaned["MAVEN_OPTS"], "-Xmx1g -Dfile.encoding=UTF-8")
        # Caller env is not mutated and existing encoding is preserved.
        self.assertIn("MAVEN_ARGS", env)
        kept = r.prepare_backend_env({"MAVEN_OPTS": "-Dfile.encoding=UTF-8 -Xmx2g"})
        self.assertEqual(kept["MAVEN_OPTS"], "-Dfile.encoding=UTF-8 -Xmx2g")

    def test_build_sanitizes_maven_args_before_backend_execution(self) -> None:
        sdk = self.root / "sdk"
        java_home = self.root / "jdk"
        (java_home / "bin").mkdir(parents=True)
        sdk.mkdir()
        (self.root / "android/app/build/outputs/apk/release").mkdir(parents=True)
        apk = self.root / "android/app/build/outputs/apk/release/app-release-unsigned.apk"
        apk.write_bytes(b"unsigned-apk")

        plan = r.make_plan(self.root)
        args = SimpleNamespace(java_home=str(java_home), sdk=str(sdk), api_base_url=None, maven_settings=None)
        env = {"PATH": os.environ.get("PATH", ""), "MAVEN_ARGS": "--settings evil.xml"}
        seen: list[tuple[list[str], str | None, str | None]] = []

        def fake_execute(cmd, cwd, exec_env, logfile=None, timeout=None):
            seen.append((list(map(str, cmd)), exec_env.get("MAVEN_ARGS"), exec_env.get("MAVEN_OPTS")))
            if "java" in Path(str(cmd[0])).name.lower():
                return 'openjdk version "21.0.4"'
            return "ok"

        with (
            patch.object(r, "require_clean"),
            patch.object(r, "git", return_value="deadbeef" * 5),
            patch.object(r, "execute", fake_execute),
        ):
            r.build(self.root, plan, args, env)

        self.assertTrue((self.root / "work/maven-repo").is_dir())
        backend_calls = [item for item in seen if "-Dmaven.repo.local=" in " ".join(item[0])]
        self.assertEqual(len(backend_calls), 1)
        cmd, mvn_args, mvn_opts = backend_calls[0]
        self.assertIsNone(mvn_args)
        self.assertIn("-Dfile.encoding=UTF-8", mvn_opts or "")
        self.assertIn("-Dmaven.repo.local=" + plan["mavenRepo"], cmd)

    def test_audit_archive_forbids_dot_m2_directory(self) -> None:
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("zhixu-oriva-2.2.0/.m2/repository/pkg.jar", b"cached")
        with zipfile.ZipFile(io.BytesIO(buf.getvalue())) as z:
            with self.assertRaises(r.ReleaseError):
                r.audit_archive(z)


class T2PublishFallbackAndTimeoutTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="oriva-t2-")
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

    def test_execute_enforces_timeout_and_writes_log(self) -> None:
        log = self.root / "timeout.log"
        with self.assertRaisesRegex(r.ReleaseError, "timed out"):
            r.execute([sys.executable, "-c", "import time; time.sleep(5)"], self.root, os.environ.copy(), log, timeout=0.2)
        self.assertIn("[TIMEOUT", log.read_text(encoding="utf8"))

    def test_git_askpass_helper_executes_and_cleans_up_even_on_error(self) -> None:
        created_helper: Path | None = None
        token_val = "synthetic-release-token-value"
        try:
            with r.git_askpass_env(os.environ.copy(), token_val) as git_env:
                created_helper = Path(git_env["GIT_ASKPASS"])
                self.assertTrue(created_helper.is_file())
                self.assertEqual(git_env["GIT_TERMINAL_PROMPT"], "0")
                u = subprocess.check_output([str(created_helper), "Username for 'https://github.com': "], env=git_env, text=True).strip()
                p = subprocess.check_output([str(created_helper), "Password for 'https://x-access-token@github.com': "], env=git_env, text=True).strip()
                self.assertEqual(u, "x-access-token")
                self.assertEqual(p, token_val)
                raise RuntimeError("boom")
        except RuntimeError:
            pass
        assert created_helper is not None
        self.assertFalse(created_helper.exists())
        self.assertFalse(created_helper.parent.exists())

    def test_publish_auto_falls_back_to_https_askpass_without_token_leak(self) -> None:
        plan = r.make_plan(self.root)
        head, notes = self._prepare_packaged_assets(plan)
        secret_token = "synthetic-release-token-secret"
        args = SimpleNamespace(
            repo="owner/repo", notes=str(notes), push_protocol="auto", git_timeout=120, upload_timeout=900
        )

        observed_cmds: list[list[str]] = []
        askpass_paths: list[Path] = []
        refs_after_probe: dict[str, str] = {}

        original_git = r.git

        def fake_git(root, *items, timeout=None, env=None):
            if items == ("remote", "get-url", "origin"):
                return "git@github.com:owner/repo.git"
            if "ls-remote" in items and "origin" in items:
                raise r.ReleaseError("ssh port 22 blocked")
            if "ls-remote" in items and "https://github.com/owner/repo.git" in items:
                self.assertIsNotNone(env)
                self.assertIn("GIT_ASKPASS", env)
                self.assertEqual(env.get("GIT_TERMINAL_PROMPT"), "0")
                self.assertTrue(Path(env["GIT_ASKPASS"]).is_file())
                askpass_paths.append(Path(env["GIT_ASKPASS"]))
                return "\n".join(v + "\t" + k for k, v in refs_after_probe.items())
            return original_git(root, *items)

        def fake_execute(cmd, cwd, exec_env, logfile=None, timeout=None):
            observed_cmds.append(list(map(str, cmd)))
            joined = " ".join(map(str, cmd))
            self.assertNotIn(secret_token, joined)
            if "push" in cmd:
                self.assertEqual(timeout, 120)
                self.assertIn("GIT_ASKPASS", exec_env)
                self.assertTrue(Path(exec_env["GIT_ASKPASS"]).is_file())
                if logfile:
                    Path(logfile).write_text("pushed cleanly to https://github.com/owner/repo.git\n", encoding="utf8")
            elif cmd[:3] == ["git", "tag", "-a"]:
                self.git(*cmd[1:])
            return ""

        class FakeGithub:
            def __init__(self, repo):
                self.base = "https://api.github.com/repos/" + repo
                self.upload_timeout = 900

            def call(self, url, method="GET", payload=None, missing_ok=False, timeout=None):
                if method == "GET":
                    if "/releases?" in url:
                        return []
                    return None
                if method == "POST" and url == self.base + "/releases":
                    self.release = dict(
                        id=10, tag_name=plan["tag"], target_commitish=head, draft=True,
                        assets=[], upload_url="https://uploads.github.com/assets{?name}",
                        html_url="https://github.com/owner/repo/releases/tag/" + plan["tag"],
                    )
                    return self.release
                if isinstance(payload, bytes):
                    assert timeout == 900, f"expected 900s upload timeout, got {timeout}"
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

        self.assertTrue(any("https://github.com/owner/repo.git" in " ".join(c) for c in observed_cmds))
        self.assertTrue(askpass_paths)
        for p in askpass_paths:
            self.assertFalse(p.exists())
            self.assertFalse(p.parent.exists())
        push_log = (Path(plan["evidence"]) / "git-push.log").read_text(encoding="utf8")
        self.assertNotIn(secret_token, push_log)

    def test_publish_ssh_mode_refuses_https_fallback(self) -> None:
        plan = r.make_plan(self.root)
        _, notes = self._prepare_packaged_assets(plan)
        args = SimpleNamespace(repo="owner/repo", notes=str(notes), push_protocol="ssh", git_timeout=120, upload_timeout=900)

        def fake_git(root, *items, timeout=None, env=None):
            if items == ("remote", "get-url", "origin"):
                return "git@github.com:owner/repo.git"
            if "ls-remote" in items:
                raise r.ReleaseError("ssh failed")
            return self.git(*items)

        with patch.object(r, "git", fake_git):
            with self.assertRaisesRegex(r.ReleaseError, "ssh failed"):
                r.publish(self.root, plan, args, os.environ.copy())


class T3VerifyStreamingAndApiDigestTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="oriva-t3-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        (self.root / "android/app").mkdir(parents=True)
        (self.root / "android/app/build.gradle.kts").write_text(
            'versionCode = 7\nversionName = "2.2.0"\n', encoding="utf8"
        )

    def test_github_call_uses_900s_timeout_for_binary_uploads(self) -> None:
        captured: list[float] = []

        class FakeResp:
            def __enter__(self): return self
            def __exit__(self, *a): pass
            def read(self): return b'{"ok":true}'

        class FakeOpener:
            def open(self, req, timeout=None):
                captured.append(timeout)
                return FakeResp()

        api = r.Github("owner/repo")
        with (
            patch.dict(os.environ, {"GITHUB_TOKEN": "tok"}),
            patch.object(r.urllib.request, "build_opener", return_value=FakeOpener()),
        ):
            api.call(api.base + "/releases", "GET")
            api.call("https://uploads.github.com/assets?name=a.apk", "POST", b"binary-payload")

        self.assertEqual(captured, [60, 900])

    def test_redirect_handler_allows_github_cdn_and_blocks_untrusted_host(self) -> None:
        handler = r.AllowedAssetRedirectHandler()
        req = urllib.request.Request("https://github.com/owner/repo/releases/download/v2.2.0/app.apk")
        allowed = handler.redirect_request(
            req, None, 302, "Found", {}, "https://release-assets.githubusercontent.com/some/blob"
        )
        self.assertEqual(allowed.full_url, "https://release-assets.githubusercontent.com/some/blob")

        for evil in [
            "https://evil.example.org/payload.apk",
            "http://objects.githubusercontent.com/downgrade.apk",
        ]:
            with self.assertRaisesRegex(r.ReleaseError, "Blocked redirect"):
                handler.redirect_request(req, None, 302, "Found", {}, evil)

    def test_download_asset_resumes_with_206_and_resets_on_200(self) -> None:
        payload = b"0123456789" * 60000  # ~600 KB (> 256 KiB chunk)
        split_at = 300000

        class ResumingResponse:
            def __init__(self, status: int, chunks: list[bytes], content_range: str | None = None):
                self.status = status
                self._chunks = list(chunks)
                self.headers = {"Content-Range": content_range} if content_range else {}

            def __enter__(self): return self
            def __exit__(self, *a): pass

            def read(self, size: int = -1) -> bytes:
                if not self._chunks:
                    return b""
                nxt = self._chunks.pop(0)
                if isinstance(nxt, BaseException):
                    raise nxt
                return nxt

        class FakeOpener:
            def __init__(self, behavior: str, test_case: unittest.TestCase):
                self.behavior = behavior
                self.test_case = test_case
                self.ranges: list[str | None] = []

            def open(self, req, timeout=None):
                rng = req.headers.get("Range")
                self.ranges.append(rng)
                if self.behavior == "resume_206":
                    if len(self.ranges) == 1:
                        return ResumingResponse(200, [payload[:split_at], TimeoutError("read stalled")])
                    self.test_case.assertEqual(rng, f"bytes={split_at}-")
                    return ResumingResponse(
                        206, [payload[split_at:]], content_range=f"bytes {split_at}-{len(payload)-1}/{len(payload)}"
                    )
                if self.behavior == "reset_200":
                    if len(self.ranges) == 1:
                        return ResumingResponse(200, [payload[:1000], http.client.IncompleteRead(b"")])
                    self.test_case.assertEqual(rng, "bytes=1000-")
                    return ResumingResponse(200, [payload])
                raise AssertionError(self.behavior)

        target1 = self.root / "app.apk"
        sleeps: list[float] = []
        opener1 = FakeOpener("resume_206", self)
        r.download_asset(
            "https://github.com/o/r/releases/download/v2.2.0/app.apk",
            target1, expected_size=len(payload), opener=opener1, sleep_fn=sleeps.append,
        )
        self.assertEqual(target1.read_bytes(), payload)
        self.assertFalse(target1.with_name("app.apk.part").exists())
        self.assertEqual(sleeps, [2])

        target2 = self.root / "app2.apk"
        opener2 = FakeOpener("reset_200", self)
        r.download_asset(
            "https://github.com/o/r/releases/download/v2.2.0/app2.apk",
            target2, expected_size=len(payload), opener=opener2, sleep_fn=lambda _: None,
        )
        self.assertEqual(target2.read_bytes(), payload)

    def test_download_asset_reports_tls_stage_on_ssl_error(self) -> None:
        class FailingOpener:
            def open(self, req, timeout=None):
                raise urllib.error.URLError(ssl.SSLError("handshake timed out"))

        with self.assertRaisesRegex(r.ReleaseError, "TLS handshake"):
            r.download_asset(
                "https://github.com/o/r/releases/download/v2.2.0/app.apk",
                self.root / "fail.apk",
                download_timeout=5,
                total_timeout=10,
                opener=FailingOpener(),
                sleep_fn=lambda _: None,
            )

    def test_verify_api_digest_mode_marks_download_verified_false(self) -> None:
        plan = r.make_plan(self.root)
        directory = Path(plan["assets"])
        directory.mkdir(parents=True)
        commit = "a" * 40
        names = [
            f"zhixu-oriva-{plan['version']}.apk",
            f"zhixu-oriva-{plan['version']}-source.zip",
            "oriva-release.cer",
            "release-manifest.json",
        ]
        for asset in names[:3]:
            (directory / asset).write_bytes(asset.encode("utf8"))
        r.write_json(directory / "release-manifest.json", dict(versionName=plan["version"], commit=commit, certificateSHA256="A" * 64))
        (directory / "SHA256SUMS").write_text(
            "".join(r.sha256(directory / asset) + "  " + asset + "\n" for asset in names), encoding="utf8"
        )
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

        class FakeUrlOpenResp:
            def __init__(self, data: bytes):
                self._buf = io.BytesIO(data)
            def __enter__(self): return self._buf
            def __exit__(self, *a): pass

        args = SimpleNamespace(
            repo="owner/repo", clone_check=False, verify_mode="api-digest",
            download_timeout=60, download_total_timeout=300, git_timeout=120,
        )
        with (
            patch.object(r.urllib.request, "urlopen", return_value=FakeUrlOpenResp(json.dumps(release_payload).encode("utf8"))),
            patch.object(r, "git", return_value=f"{commit}\trefs/tags/{plan['tag']}"),
            patch.object(r, "download_asset", side_effect=AssertionError("api-digest must not download assets")),
        ):
            r.verify(self.root, plan, args, {})

        report = json.loads((Path(plan["evidence"]) / "recipient-verification.json").read_text(encoding="utf8"))
        self.assertEqual(report["verifyMode"], "api-digest")
        self.assertFalse(report["downloadVerified"])
        self.assertEqual(report["status"], "PASS")

        # api-digest + clone_check must be rejected fail-closed.
        bad_args = SimpleNamespace(
            repo="owner/repo", clone_check=True, verify_mode="api-digest",
            download_timeout=60, download_total_timeout=300, git_timeout=120,
        )
        with self.assertRaisesRegex(r.ReleaseError, "clone-check"):
            r.verify(self.root, plan, bad_args, {})

    def test_verify_full_mode_writes_download_verified_true_and_cli_plan_works(self) -> None:
        script = Path(r.__file__).resolve()
        cli = subprocess.run(
            [sys.executable, str(script), "plan", "--root", str(self.root), "--push-protocol", "https", "--verify-mode", "full", "--upload-timeout", "900"],
            capture_output=True, text=True, encoding="utf8",
        )
        self.assertEqual(cli.returncode, 0, cli.stderr)
        parsed = json.loads(cli.stdout)
        self.assertEqual(parsed["mavenRepo"], str((self.root / "work/maven-repo").resolve()))

        plan = r.make_plan(self.root)
        directory = Path(plan["assets"])
        directory.mkdir(parents=True)
        src_zip = directory / f"zhixu-oriva-{plan['version']}-source.zip"
        with zipfile.ZipFile(src_zip, "w") as z:
            z.writestr(f"zhixu-oriva-{plan['version']}/README.md", b"ORIVA\n")
        for asset in (f"zhixu-oriva-{plan['version']}.apk", "oriva-release.cer"):
            (directory / asset).write_bytes(asset.encode("utf8"))
        commit = "b" * 40
        r.write_json(directory / "release-manifest.json", dict(versionName=plan["version"], commit=commit, certificateSHA256="A" * 64))
        names = [f"zhixu-oriva-{plan['version']}.apk", src_zip.name, "oriva-release.cer", "release-manifest.json"]
        (directory / "SHA256SUMS").write_text("".join(r.sha256(directory / a) + "  " + a + "\n" for a in names), encoding="utf8")
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

        class FakeUrlOpenResp:
            def __init__(self, data: bytes):
                self._buf = io.BytesIO(data)
            def __enter__(self): return self._buf
            def __exit__(self, *a): pass

        def fake_download(url, target, expected_size=None, download_timeout=60, total_timeout=300):
            name = url.rsplit("/", 1)[-1]
            target.write_bytes((directory / name).read_bytes())

        def fake_archive_source(root, c, v, out):
            Path(out).write_bytes(src_zip.read_bytes())
            return 1

        args = SimpleNamespace(
            repo="owner/repo", clone_check=False, verify_mode="full",
            download_timeout=60, download_total_timeout=300, git_timeout=120,
        )
        with (
            patch.object(r.urllib.request, "urlopen", return_value=FakeUrlOpenResp(json.dumps(release_payload).encode("utf8"))),
            patch.object(r, "git", return_value=f"{commit}\trefs/tags/{plan['tag']}"),
            patch.object(r, "download_asset", fake_download),
            patch.object(r, "archive_source", fake_archive_source),
        ):
            r.verify(self.root, plan, args, {})

        report = json.loads((Path(plan["evidence"]) / "recipient-verification.json").read_text(encoding="utf8"))
        self.assertEqual(report["verifyMode"], "full")
        self.assertTrue(report["downloadVerified"])
        self.assertEqual(report["status"], "PASS")


if __name__ == "__main__":
    unittest.main()
