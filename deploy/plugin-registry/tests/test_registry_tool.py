"""Unit and adversarial tests for deploy/plugin-registry/registry_tool.py and web/."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

REGISTRY_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REGISTRY_ROOT))

import registry_tool  # noqa: E402


def _create_ephemeral_signer(workspace: Path, alias: str, cn: str) -> tuple[Path, dict[str, str], str]:
    ks_path = workspace / f"{alias}.p12"
    env = os.environ.copy()
    env_var = f"ORIVA_TEST_KS_{alias.upper()}"
    env[env_var] = secrets.token_hex(16)
    keytool = registry_tool._find_jdk_tool("keytool")
    subprocess.run(
        [
            keytool,
            "-genkeypair",
            "-alias",
            alias,
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "365",
            "-storetype",
            "PKCS12",
            "-keystore",
            str(ks_path),
            "-storepass:env",
            env_var,
            "-keypass:env",
            env_var,
            "-dname",
            f"CN={cn}, O=ORIVA Test, C=CN",
        ],
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )
    list_proc = subprocess.run(
        [
            keytool,
            "-J-Duser.language=en",
            "-J-Duser.country=US",
            "-list",
            "-v",
            "-keystore",
            str(ks_path),
            "-storepass:env",
            env_var,
            "-alias",
            alias,
        ],
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )
    match = re.search(r"SHA256:\s*([0-9A-Fa-f:]{95})", list_proc.stdout)
    if not match:
        raise AssertionError(f"Missing SHA256 fingerprint in keytool output: {list_proc.stdout}")
    return ks_path, env, match.group(1).upper()


def _sign_package(pkg_path: Path, ks_path: Path, env: dict[str, str], alias: str) -> None:
    jarsigner = registry_tool._find_jdk_tool("jarsigner")
    env_var = f"ORIVA_TEST_KS_{alias.upper()}"
    subprocess.run(
        [
            jarsigner,
            "-keystore",
            str(ks_path),
            "-storetype",
            "PKCS12",
            "-storepass:env",
            env_var,
            "-keypass:env",
            env_var,
            str(pkg_path),
            alias,
        ],
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )


def _base_manifest(source_id: str = "demo-community", plugin_type: str = "js") -> dict[str, object]:
    if plugin_type == "js":
        return {
            "id": "demo-js",
            "name": "示例 JS 插件",
            "version": "1.0.0",
            "contractVersion": 2,
            "type": "js",
            "entryPoint": "entryPoint",
            "sourceId": source_id,
            "sourceType": "THIRD_PARTY",
            "requiredCapabilities": ["QUERY_TODAY"],
            "description": "测试用 JS 插件",
            "author": "Test Author",
            "homepage": "https://example.org/demo-js",
            "repository": "https://github.com/example/demo-js",
            "license": "MIT",
        }
    return {
        "id": "demo-dex",
        "name": "示例 DEX 插件",
        "version": "1.0.0",
        "contractVersion": 1,
        "type": "dex",
        "entryPoint": "org.example.DemoPlugin",
        "sourceId": source_id,
        "sourceType": "THIRD_PARTY",
        "requiredCapabilities": ["FEEDBACK"],
        "description": "测试用 DEX 插件",
    }


def _write_unsigned_package(pkg_path: Path, manifest: dict[str, object], extra_files: dict[str, bytes] | None = None) -> None:
    files = dict(extra_files or {})
    if manifest.get("type", "dex") == "js":
        files.setdefault("index.js", b"function entryPoint() { return { message: 'ok' }; }\n")
    else:
        files.setdefault("classes.dex", b"dex\n037\x00" + b"\x00" * 32)
    with zipfile.ZipFile(pkg_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("plugin.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for name, data in files.items():
            zf.writestr(name, data)


class RegistryToolTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="oriva-reg-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.ks_dir = self.root / "ks"
        self.ks_dir.mkdir()
        self.ks_a, self.env_a, self.fp_a = _create_ephemeral_signer(self.ks_dir, "alpha", "Alpha Dev")
        self.ks_b, self.env_b, self.fp_b = _create_ephemeral_signer(self.ks_dir, "beta", "Beta Dev")

    def _make_workspace(self, name: str = "site") -> tuple[Path, Path]:
        reg_dir = self.root / name
        (reg_dir / "artifacts").mkdir(parents=True, exist_ok=True)
        return reg_dir, reg_dir / "registry.json"

    def _entry_from_manifest(self, artifact: str, manifest: dict[str, object]) -> dict[str, object]:
        entry: dict[str, object] = {
            "artifact": artifact,
            "id": manifest["id"],
            "name": manifest["name"],
            "version": manifest["version"],
            "contractVersion": manifest["contractVersion"],
            "type": manifest.get("type", "dex"),
            "entryPoint": manifest["entryPoint"],
            "requiredCapabilities": manifest["requiredCapabilities"],
            "description": manifest["description"],
        }
        for opt in ("author", "homepage", "repository", "license"):
            if opt in manifest:
                entry[opt] = manifest[opt]
        return entry

    def test_repo_sample_registry_validates_and_builds_deterministically(self) -> None:
        sample_registry = REGISTRY_ROOT / "registry.json"
        validated = registry_tool.validate_registry(sample_registry)
        self.assertEqual(validated.source_id, "oriva-community-examples")
        self.assertEqual(validated.source_type, "THIRD_PARTY")
        self.assertEqual(len(validated.plugins), 2)

        out1 = self.root / "dist1"
        out2 = self.root / "dist2"
        registry_tool.build_static_site(sample_registry, out1, clean=True)
        registry_tool.build_static_site(sample_registry, out2, clean=True)

        for rel in ("catalog.json", "site-meta.json", "SHA256SUMS", "index.html", "style.css", "app.js"):
            self.assertEqual((out1 / rel).read_bytes(), (out2 / rel).read_bytes(), f"Non-deterministic output: {rel}")

        catalog = json.loads((out1 / "catalog.json").read_text(encoding="utf-8"))
        self.assertEqual(catalog["catalogFormatVersion"], 1)
        self.assertEqual(catalog["sourceId"], "oriva-community-examples")
        self.assertEqual(catalog["sourceType"], "THIRD_PARTY")
        self.assertEqual([p["id"] for p in catalog["plugins"]], ["ink-focus-cue", "quiet-rhythm-tip"])
        for item in catalog["plugins"]:
            self.assertTrue(item["downloadUrl"].startswith("./artifacts/"))
            self.assertRegex(item["sha256"], r"^[0-9A-F]{64}$")
            artifact_name = item["downloadUrl"].split("/")[-1]
            expected_sha = hashlib.sha256((out1 / "artifacts" / artifact_name).read_bytes()).hexdigest().upper()
            self.assertEqual(item["sha256"], expected_sha)

    def test_valid_js_and_dex_registry_and_inspect_cert(self) -> None:
        reg_dir, reg_path = self._make_workspace("valid_both")
        js_m = _base_manifest("demo-community", "js")
        dex_m = _base_manifest("demo-community", "dex")

        js_pkg = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
        dex_pkg = reg_dir / "artifacts" / "demo-dex-1.0.0.plp"
        _write_unsigned_package(js_pkg, js_m)
        _write_unsigned_package(dex_pkg, dex_m)
        _sign_package(js_pkg, self.ks_a, self.env_a, "alpha")
        _sign_package(dex_pkg, self.ks_a, self.env_a, "alpha")

        reg_path.write_text(
            json.dumps(
                {
                    "catalogFormatVersion": 1,
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a.lower().replace(":", "")],
                    "plugins": [
                        self._entry_from_manifest("demo-dex-1.0.0.plp", dex_m),
                        self._entry_from_manifest("demo-js-1.0.0.pljs", js_m),
                    ],
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

        validated = registry_tool.validate_registry(reg_path)
        self.assertEqual([p.plugin_id for p in validated.plugins], ["demo-dex", "demo-js"])

        cert_info = registry_tool.inspect_package_certificates(js_pkg)
        self.assertEqual(cert_info["id"], "demo-js")
        self.assertIn(self.fp_a, cert_info["signers"].values())

    def test_empty_plugins_list_is_valid(self) -> None:
        reg_dir, reg_path = self._make_workspace("empty_reg")
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "empty-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [],
                }
            ),
            encoding="utf-8",
        )
        out = self.root / "empty-dist"
        validated = registry_tool.build_static_site(reg_path, out)
        self.assertEqual(len(validated.plugins), 0)
        catalog = json.loads((out / "catalog.json").read_text(encoding="utf-8"))
        self.assertEqual(catalog["catalogFormatVersion"], 1)
        self.assertEqual(catalog["plugins"], [])

    def test_rejects_reserved_source_ids_and_official_source_type(self) -> None:
        for bad_id in ("official", "OFFICIAL", "third-party", "THIRD-PARTY", "../bad", "a/b"):
            reg_dir, reg_path = self._make_workspace(f"bad_id_{abs(hash(bad_id))}")
            reg_path.write_text(
                json.dumps(
                    {
                        "sourceId": bad_id,
                        "sourceType": "THIRD_PARTY",
                        "trustedFingerprints": [self.fp_a],
                        "plugins": [],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(registry_tool.RegistryValidationError):
                registry_tool.validate_registry(reg_path)

        reg_dir, reg_path = self._make_workspace("bad_type")
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "community-x",
                    "sourceType": "OFFICIAL",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.validate_registry(reg_path)

    def test_rejects_invalid_catalog_format_version(self) -> None:
        for bad_ver in (0, -1, "1", True, 1.5, None):
            reg_dir, reg_path = self._make_workspace(f"bad_ver_{type(bad_ver).__name__}_{bad_ver}")
            reg_path.write_text(
                json.dumps(
                    {
                        "catalogFormatVersion": bad_ver,
                        "sourceId": "community-x",
                        "sourceType": "THIRD_PARTY",
                        "trustedFingerprints": [self.fp_a],
                        "plugins": [],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(registry_tool.RegistryValidationError):
                registry_tool.validate_registry(reg_path)

    def test_rejects_unsigned_tampered_wrong_signer_and_mixed_signer_packages(self) -> None:
        manifest = _base_manifest("demo-community", "js")

        # 1. Unsigned package
        reg_dir, reg_path = self._make_workspace("unsigned")
        pkg = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
        _write_unsigned_package(pkg, manifest)
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [self._entry_from_manifest("demo-js-1.0.0.pljs", manifest)],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.validate_registry(reg_path)

        # 2. Wrong signer (signed with B, registry trusts only A)
        _sign_package(pkg, self.ks_b, self.env_b, "beta")
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.validate_registry(reg_path)

        # 3. Signed with A, then tampered entry bytes
        _sign_package(pkg, self.ks_a, self.env_a, "alpha")
        tampered_pkg = reg_dir / "artifacts" / "tampered.pljs"
        with zipfile.ZipFile(pkg, "r") as zin, zipfile.ZipFile(tampered_pkg, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == "index.js":
                    data = b"function entryPoint() { return { message: 'evil' }; }\n"
                zout.writestr(item, data)
        shutil.move(str(tampered_pkg), str(pkg))
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.validate_registry(reg_path)

        # 4. Signed with A, then extra unsigned payload added
        _write_unsigned_package(pkg, manifest)
        _sign_package(pkg, self.ks_a, self.env_a, "alpha")
        extra_pkg = reg_dir / "artifacts" / "extra.pljs"
        with zipfile.ZipFile(pkg, "r") as zin, zipfile.ZipFile(extra_pkg, "w") as zout:
            for item in zin.infolist():
                zout.writestr(item, zin.read(item.filename))
            zout.writestr("extra-unsigned.js", b"console.log(1);")
        shutil.move(str(extra_pkg), str(pkg))
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.validate_registry(reg_path)

        # 5. Mixed-signer attack: plugin.json signed by A+B, index.js signed ONLY by B (trusted=A)
        mixed_manifest = _base_manifest("official", "js")
        mixed_pkg = Path(__file__).resolve().parents[3] / "android/core-plugin/src/test/resources/signing/mixed.pljs"
        if mixed_pkg.is_file():
            # Verify directly via verify_jar_signatures against trusted A (official fp) -> must fail!
            official_fp = "C5:9E:81:65:8F:31:08:29:B4:23:BE:24:2D:C3:28:B3:76:CB:CE:1B:C6:1C:1F:F2:31:31:61:12:41:2B:8C:44"
            payloads, meta_inf = registry_tool.inspect_zip_security(mixed_pkg)
            with self.assertRaises(registry_tool.RegistryValidationError):
                registry_tool.verify_jar_signatures(mixed_pkg, set(payloads.keys()), meta_inf, {official_fp})
            _ = mixed_manifest

    def test_rejects_zip_path_traversal_and_missing_code_entry(self) -> None:
        reg_dir, reg_path = self._make_workspace("zip_traversal")
        pkg = reg_dir / "artifacts" / "bad.pljs"
        with zipfile.ZipFile(pkg, "w") as zf:
            zf.writestr("../escape.txt", b"boom")
            zf.writestr("plugin.json", json.dumps(_base_manifest("demo-community", "js")))
            zf.writestr("index.js", b"function entryPoint() {}")
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.inspect_zip_security(pkg)

        # Missing index.js
        pkg2 = reg_dir / "artifacts" / "missing-code.pljs"
        with zipfile.ZipFile(pkg2, "w") as zf:
            zf.writestr("plugin.json", json.dumps(_base_manifest("demo-community", "js")))
        _sign_package(pkg2, self.ks_a, self.env_a, "alpha")
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [self._entry_from_manifest("missing-code.pljs", _base_manifest("demo-community", "js"))],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.validate_registry(reg_path)

    def test_rejects_dangerous_urls_and_manifest_mismatch(self) -> None:
        bad_urls = [
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "https://" + "user:pass@example.org/repo",
            "https://example.org/with space",
            "ftp://example.org/repo",
        ]
        for idx, bad_url in enumerate(bad_urls):
            reg_dir, reg_path = self._make_workspace(f"bad_url_{idx}")
            manifest = _base_manifest("demo-community", "js")
            pkg = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
            _write_unsigned_package(pkg, manifest)
            _sign_package(pkg, self.ks_a, self.env_a, "alpha")
            entry = self._entry_from_manifest("demo-js-1.0.0.pljs", manifest)
            entry["homepage"] = bad_url
            reg_path.write_text(
                json.dumps(
                    {
                        "sourceId": "demo-community",
                        "sourceType": "THIRD_PARTY",
                        "trustedFingerprints": [self.fp_a],
                        "plugins": [entry],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(registry_tool.RegistryValidationError, msg=f"Should reject {bad_url}"):
                registry_tool.validate_registry(reg_path)

        # Manifest mismatch: registry claims version 2.0.0 while signed plugin.json says 1.0.0
        reg_dir, reg_path = self._make_workspace("mismatch")
        manifest = _base_manifest("demo-community", "js")
        pkg = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
        _write_unsigned_package(pkg, manifest)
        _sign_package(pkg, self.ks_a, self.env_a, "alpha")
        entry = self._entry_from_manifest("demo-js-1.0.0.pljs", manifest)
        entry["version"] = "2.0.0"
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [entry],
                }
            ),
            encoding="utf-8",
        )
        out_dir = self.root / "should-not-exist"
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.build_static_site(reg_path, out_dir)
        self.assertFalse(out_dir.exists(), "Failed validation must not create output directory")

    def test_static_web_assets_have_zero_external_cdn_or_unsafe_dom_sinks(self) -> None:
        web_dir = REGISTRY_ROOT / "web"
        for filename in ("index.html", "style.css", "app.js"):
            text = (web_dir / filename).read_text(encoding="utf-8")
            self.assertNotRegex(text, r"https?://(?!localhost\b)[A-Za-z0-9.-]+", f"External URL found in {filename}")
        app_js = (web_dir / "app.js").read_text(encoding="utf-8")
        for forbidden_sink in ("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval("):
            self.assertNotIn(forbidden_sink, app_js, f"Unsafe DOM sink {forbidden_sink} found in app.js")

    def test_web_app_js_escapes_xss_and_blocks_unsafe_urls_in_dom(self) -> None:
        node = shutil.which("node")
        if not node:
            self.skipTest("node runtime unavailable")
        app_js_path = (REGISTRY_ROOT / "web" / "app.js").resolve().as_posix()
        script = f"""
const assert = require('assert');
const app = require({json.dumps(app_js_path)});

class FakeElement {{
  constructor(tag) {{
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.attributes = {{}};
    this._text = '';
    this.className = '';
    this.hidden = false;
    this.title = '';
    this.type = '';
    this.href = '';
    this.target = '';
    this.rel = '';
  }}
  set textContent(v) {{ this._text = String(v); this.children = []; }}
  get textContent() {{
    if (this.children.length === 0) return this._text;
    return this._text + this.children.map(c => c.textContent).join('');
  }}
  appendChild(child) {{ this.children.push(child); return child; }}
  replaceChildren(...nodes) {{ this.children = nodes; }}
  setAttribute(k, v) {{ this.attributes[k] = String(v); }}
  addEventListener() {{}}
}}
function walk(node, out = []) {{
  out.push(node);
  for (const c of node.children) walk(c, out);
  return out;
}}
const fakeDoc = {{ createElement: (t) => new FakeElement(t) }};
const elements = {{
  siteTitle: new FakeElement('h1'),
  siteDescription: new FakeElement('p'),
  sourceBadges: new FakeElement('div'),
  catalogUrlText: new FakeElement('code'),
  sourceIdText: new FakeElement('code'),
  fingerprintList: new FakeElement('div'),
  resultSummary: new FakeElement('p'),
  statusPanel: new FakeElement('div'),
  pluginGrid: new FakeElement('div'),
}};

assert.strictEqual(app.safeHttpUrl('javascript:alert(1)'), null);
assert.strictEqual(app.safeHttpUrl('data:text/html,<script>alert(1)</script>'), null);
assert.strictEqual(app.safeHttpUrl('https://' + 'user:pass@evil.example/'), null);
assert.strictEqual(app.safeHttpUrl('https://example.org/ok'), 'https://example.org/ok');
assert.strictEqual(app.safeDownloadUrl('../secret.txt', 'https://example.org/catalog.json'), null);
assert.strictEqual(app.safeDownloadUrl('./artifacts/ok.pljs', 'https://example.org/catalog.json'), 'https://example.org/artifacts/ok.pljs');

const xssCatalog = {{
  catalogFormatVersion: 1,
  sourceId: 'community-xss-test',
  sourceType: 'THIRD_PARTY',
  plugins: [{{
    id: 'xss-probe',
    name: '<script>alert(1)</script>测试',
    version: '1.0.0',
    contractVersion: 2,
    type: 'js',
    entryPoint: 'entryPoint',
    requiredCapabilities: ['QUERY_TODAY'],
    description: '<img src=x onerror=alert(1)>描述',
    author: '<svg onload=alert(1)>',
    homepage: 'javascript:alert(1)',
    repository: 'https://github.com/example/safe-repo',
    license: 'MIT',
    downloadUrl: './artifacts/xss-probe.pljs',
    sha256: 'A'.repeat(64)
  }}]
}};
app.renderCatalogState(fakeDoc, elements, xssCatalog, {{
  sourceName: '<b>站名</b>',
  trustedFingerprints: ['AA:BB']
}}, {{ baseHref: 'https://example.org/index.html' }});

const allNodes = walk(elements.pluginGrid);
assert.strictEqual(allNodes.some(n => ['SCRIPT', 'IMG', 'SVG'].includes(n.tagName)), false);
const anchors = allNodes.filter(n => n.tagName === 'A');
assert.strictEqual(anchors.some(a => String(a.href).startsWith('javascript:')), false);
const repoAnchor = anchors.find(a => a.href === 'https://github.com/example/safe-repo');
assert.ok(repoAnchor);
assert.strictEqual(repoAnchor.rel, 'noopener noreferrer');
assert.strictEqual(repoAnchor.target, '_blank');
assert.ok(elements.pluginGrid.textContent.includes('<script>alert(1)</script>测试'));
assert.ok(elements.pluginGrid.textContent.includes('已拦截不安全链接'));
assert.strictEqual(elements.siteTitle.textContent, '<b>站名</b>');
"""
        proc = subprocess.run([node, "-e", script], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)


if __name__ == "__main__":
    unittest.main()
