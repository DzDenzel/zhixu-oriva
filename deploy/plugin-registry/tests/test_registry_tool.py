"""Unit and adversarial tests for deploy/plugin-registry/registry_tool.py, JarSignerVerifier.java, and web/."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

REGISTRY_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REGISTRY_ROOT))

import registry_tool  # noqa: E402


def _create_ephemeral_signer(
    workspace: Path,
    alias: str,
    cn: str,
    ext_args: list[str] | None = None,
) -> tuple[Path, dict[str, str], str]:
    ks_path = workspace / f"{alias}.p12"
    env = os.environ.copy()
    env_var = f"ORIVA_TEST_KS_{alias.upper()}"
    env[env_var] = secrets.token_hex(16)
    keytool = registry_tool._find_jdk_tool("keytool")
    cmd = [
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
    ]
    if ext_args:
        cmd.extend(ext_args)
    subprocess.run(cmd, env=env, check=True, capture_output=True, text=True)
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


def _create_ephemeral_chain_signer(workspace: Path) -> tuple[Path, dict[str, str], str, str]:
    """Create a temporary root CA + leaf certificate chain keystore; return (ks, env, leaf_fp, ca_fp)."""
    keytool = registry_tool._find_jdk_tool("keytool")
    env = os.environ.copy()
    pass_val = secrets.token_hex(16)
    env["ORIVA_TEST_KS_CHAIN"] = pass_val
    ca_ks = workspace / "ca.p12"
    leaf_ks = workspace / "chain.p12"

    subprocess.run(
        [
            keytool, "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
            "-storetype", "PKCS12", "-keystore", str(ca_ks),
            "-storepass:env", "ORIVA_TEST_KS_CHAIN", "-keypass:env", "ORIVA_TEST_KS_CHAIN",
            "-dname", "CN=Test Root CA, O=ORIVA Test, C=CN", "-ext", "bc:c=ca:true",
        ],
        env=env, check=True, capture_output=True, text=True,
    )
    subprocess.run(
        [
            keytool, "-genkeypair", "-alias", "leaf", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
            "-storetype", "PKCS12", "-keystore", str(leaf_ks),
            "-storepass:env", "ORIVA_TEST_KS_CHAIN", "-keypass:env", "ORIVA_TEST_KS_CHAIN",
            "-dname", "CN=Test Leaf Signer, O=ORIVA Test, C=CN",
        ],
        env=env, check=True, capture_output=True, text=True,
    )
    ca_cer = workspace / "ca.cer"
    csr = workspace / "leaf.csr"
    leaf_cer = workspace / "leaf.cer"
    chain_pem = workspace / "chain.pem"
    subprocess.run(
        [keytool, "-exportcert", "-alias", "ca", "-keystore", str(ca_ks), "-storepass:env", "ORIVA_TEST_KS_CHAIN", "-rfc", "-file", str(ca_cer)],
        env=env, check=True, capture_output=True, text=True,
    )
    subprocess.run(
        [keytool, "-certreq", "-alias", "leaf", "-keystore", str(leaf_ks), "-storepass:env", "ORIVA_TEST_KS_CHAIN", "-file", str(csr)],
        env=env, check=True, capture_output=True, text=True,
    )
    subprocess.run(
        [keytool, "-gencert", "-alias", "ca", "-keystore", str(ca_ks), "-storepass:env", "ORIVA_TEST_KS_CHAIN", "-infile", str(csr), "-outfile", str(leaf_cer), "-rfc"],
        env=env, check=True, capture_output=True, text=True,
    )
    chain_pem.write_text(leaf_cer.read_text(encoding="utf-8") + "\n" + ca_cer.read_text(encoding="utf-8"), encoding="utf-8")
    subprocess.run(
        [keytool, "-importcert", "-noprompt", "-alias", "leaf", "-keystore", str(leaf_ks), "-storepass:env", "ORIVA_TEST_KS_CHAIN", "-file", str(chain_pem)],
        env=env, check=True, capture_output=True, text=True,
    )

    def _cert_fp(cer_file: Path) -> str:
        out = subprocess.run(
            [keytool, "-J-Duser.language=en", "-J-Duser.country=US", "-printcert", "-file", str(cer_file)],
            check=True, capture_output=True, text=True,
        ).stdout
        m = re.search(r"SHA256:\s*([0-9A-Fa-f:]{95})", out)
        assert m, out
        return m.group(1).upper()

    return leaf_ks, env, _cert_fp(leaf_cer), _cert_fp(ca_cer)


def _sign_package(pkg_path: Path, ks_path: Path, env: dict[str, str], alias: str, env_var: str | None = None, sigfile: str | None = None) -> None:
    jarsigner = registry_tool._find_jdk_tool("jarsigner")
    resolved_var = env_var or f"ORIVA_TEST_KS_{alias.upper()}"
    cmd = [
        jarsigner,
        "-keystore",
        str(ks_path),
        "-storetype",
        "PKCS12",
        "-storepass:env",
        resolved_var,
        "-keypass:env",
        resolved_var,
    ]
    if sigfile:
        cmd.extend(["-sigfile", sigfile])
    cmd.extend([str(pkg_path), alias])
    subprocess.run(cmd, env=env, check=True, capture_output=True, text=True)


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
        common_fps = {s["certSha256"] for s in cert_info["commonSigners"]}
        self.assertIn(self.fp_a, common_fps)
        self.assertTrue(all("spkiSha256" in s for s in cert_info["commonSigners"]))

    def test_s1_multisigner_intersection_disjoint_signers_and_cert_chain_leaf(self) -> None:
        # 1. Valid multi-signer intersection: signed by BOTH alpha and beta across all payloads
        reg_dir, reg_path = self._make_workspace("multisig_valid")
        manifest = _base_manifest("demo-community", "js")
        pkg = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
        _write_unsigned_package(pkg, manifest)
        _sign_package(pkg, self.ks_a, self.env_a, "alpha", sigfile="ALPHA")
        _sign_package(pkg, self.ks_b, self.env_b, "beta", sigfile="BETA")

        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_b],
                    "plugins": [self._entry_from_manifest("demo-js-1.0.0.pljs", manifest)],
                }
            ),
            encoding="utf-8",
        )
        validated = registry_tool.validate_registry(reg_path)
        self.assertEqual(validated.plugins[0].signer_fingerprint, self.fp_b)

        # 2. Disjoint per-entry signers (plugin.json signed ONLY by alpha, index.js signed ONLY by beta)
        # Even when BOTH fp_a and fp_b are trusted, common payload intersection is empty -> MUST REJECT!
        pkg_a_only = reg_dir / "artifacts" / "a-only.pljs"
        pkg_b_only = reg_dir / "artifacts" / "b-only.pljs"
        _write_unsigned_package(pkg_a_only, manifest)
        _write_unsigned_package(pkg_b_only, manifest)
        _sign_package(pkg_a_only, self.ks_a, self.env_a, "alpha", sigfile="ALPHA")
        _sign_package(pkg_b_only, self.ks_b, self.env_b, "beta", sigfile="BETA")

        disjoint_pkg = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
        with zipfile.ZipFile(pkg_a_only, "r") as za, zipfile.ZipFile(pkg_b_only, "r") as zb, zipfile.ZipFile(disjoint_pkg, "w") as zout:
            # Take MANIFEST + ALPHA signature (covering plugin.json only) from A by filtering out index.js?
            # Simpler: build JAR 1 with only plugin.json signed by A, JAR 2 with both signed by B, take index.js + BETA from B and plugin.json + ALPHA from A?
            # Let's sign a 1-entry zip containing only plugin.json with A, and splice index.js + BETA from a 1-entry zip signed by B + merged MANIFEST.MF.
            pass
        one_a = reg_dir / "artifacts" / "one_a.pljs"
        one_b = reg_dir / "artifacts" / "one_b.pljs"
        with zipfile.ZipFile(one_a, "w") as z1:
            z1.writestr("plugin.json", json.dumps(manifest))
        with zipfile.ZipFile(one_b, "w") as z2:
            z2.writestr("index.js", b"function entryPoint() { return { message: 'ok' }; }\n")
        _sign_package(one_a, self.ks_a, self.env_a, "alpha", sigfile="ALPHA")
        _sign_package(one_b, self.ks_b, self.env_b, "beta", sigfile="BETA")

        with zipfile.ZipFile(one_a, "r") as za, zipfile.ZipFile(one_b, "r") as zb:
            ma_text = za.read("META-INF/MANIFEST.MF").decode("utf-8")
            mb_text = zb.read("META-INF/MANIFEST.MF").decode("utf-8")
            # Combine manifest entry sections
            mb_sections = mb_text.split("\r\n\r\n", 1)[1] if "\r\n\r\n" in mb_text else mb_text.split("\n\n", 1)[1]
            merged_manifest = ma_text.rstrip("\r\n") + "\r\n\r\n" + mb_sections
            with zipfile.ZipFile(disjoint_pkg, "w") as zout:
                zout.writestr("META-INF/MANIFEST.MF", merged_manifest.encode("utf-8"))
                zout.writestr("META-INF/ALPHA.SF", za.read("META-INF/ALPHA.SF"))
                zout.writestr("META-INF/ALPHA.RSA", za.read("META-INF/ALPHA.RSA"))
                zout.writestr("META-INF/BETA.SF", zb.read("META-INF/BETA.SF"))
                zout.writestr("META-INF/BETA.RSA", zb.read("META-INF/BETA.RSA"))
                zout.writestr("plugin.json", za.read("plugin.json"))
                zout.writestr("index.js", zb.read("index.js"))

        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a, self.fp_b],
                    "plugins": [self._entry_from_manifest("demo-js-1.0.0.pljs", manifest)],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "do not match trustedFingerprints"):
            registry_tool.validate_registry(reg_path)

        # 3. Certificate chain: JarSignerVerifier extracts LEAF cert (never intermediate/root CA)
        chain_ks, chain_env, leaf_fp, ca_fp = _create_ephemeral_chain_signer(self.ks_dir)
        chain_pkg = reg_dir / "artifacts" / "chain.pljs"
        _write_unsigned_package(chain_pkg, manifest)
        _sign_package(chain_pkg, chain_ks, chain_env, "leaf", env_var="ORIVA_TEST_KS_CHAIN")
        cert_info = registry_tool.inspect_package_certificates(chain_pkg)
        self.assertTrue(cert_info["diagnosticOnly"])
        self.assertFalse(cert_info["trustGranted"])
        self.assertEqual([s["certSha256"] for s in cert_info["commonSigners"]], [leaf_fp])

        # Disjoint-signed inspect-cert reports empty commonSigners as diagnostic-only (no trust)
        disjoint_info = registry_tool.inspect_package_certificates(disjoint_pkg)
        self.assertTrue(disjoint_info["diagnosticOnly"])
        self.assertFalse(disjoint_info["trustGranted"])
        self.assertEqual(disjoint_info["commonSigners"], [])

        # 4. S1 residual: 1 valid signature pair + 1 empty/corrupt/unattributed extra pair MUST be rejected
        base_signed = reg_dir / "artifacts" / "base-valid.pljs"
        _write_unsigned_package(base_signed, manifest)
        _sign_package(base_signed, self.ks_a, self.env_a, "alpha", sigfile="ALPHA")

        # 4a. Empty auxiliary .SF / .RSA pair alongside valid ALPHA
        empty_pair_pkg = reg_dir / "artifacts" / "empty-pair.pljs"
        with zipfile.ZipFile(base_signed, "r") as zin, zipfile.ZipFile(empty_pair_pkg, "w") as zout:
            for item in zin.infolist():
                zout.writestr(item, zin.read(item.filename))
            zout.writestr("META-INF/BOGUS.SF", b"")
            zout.writestr("META-INF/BOGUS.RSA", b"")
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "Empty META-INF"):
            registry_tool.inspect_zip_bytes(empty_pair_pkg.read_bytes(), "empty-pair.pljs")
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.verify_jar_cryptographic_signers(empty_pair_pkg, "empty-pair.pljs", {self.fp_a})

        # 4b. Non-empty corrupt auxiliary .SF / .RSA pair alongside valid ALPHA
        corrupt_pair_pkg = reg_dir / "artifacts" / "corrupt-pair.pljs"
        with zipfile.ZipFile(base_signed, "r") as zin, zipfile.ZipFile(corrupt_pair_pkg, "w") as zout:
            for item in zin.infolist():
                zout.writestr(item, zin.read(item.filename))
            zout.writestr("META-INF/BOGUS.SF", b"Signature-Version: 1.0\r\nSHA-256-Digest-Manifest: invalid\r\n\r\n")
            zout.writestr("META-INF/BOGUS.RSA", b"not-a-valid-pkcs7-block")
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.verify_jar_cryptographic_signers(corrupt_pair_pkg, "corrupt-pair.pljs", {self.fp_a})

        # 4c. Valid PKCS7 auxiliary pair referencing an absent entry -> rejected
        dummy_zip = reg_dir / "artifacts" / "dummy-other.pljs"
        with zipfile.ZipFile(dummy_zip, "w") as zd:
            zd.writestr("not-in-target.txt", b"unrelated payload")
        _sign_package(dummy_zip, self.ks_b, self.env_b, "beta", sigfile="UNUSED")
        unattributed_pkg = reg_dir / "artifacts" / "unattributed-pair.pljs"
        with zipfile.ZipFile(base_signed, "r") as za, zipfile.ZipFile(dummy_zip, "r") as zb, zipfile.ZipFile(unattributed_pkg, "w") as zout:
            for item in za.infolist():
                zout.writestr(item, za.read(item.filename))
            zout.writestr("META-INF/UNUSED.SF", zb.read("META-INF/UNUSED.SF"))
            zout.writestr("META-INF/UNUSED.RSA", zb.read("META-INF/UNUSED.RSA"))
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "no manifest section|Unattributed"):
            registry_tool.verify_jar_cryptographic_signers(unattributed_pkg, "unattributed-pair.pljs", {self.fp_a})

        # 4d. Valid PKCS7 auxiliary pair signing a main-attributes-only manifest (0 attributed payloads) -> rejected
        manifest_only = reg_dir / "artifacts" / "manifest-only.pljs"
        with zipfile.ZipFile(manifest_only, "w") as zm:
            zm.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n")
        _sign_package(manifest_only, self.ks_b, self.env_b, "beta", sigfile="MANONLY")
        manonly_pkg = reg_dir / "artifacts" / "manonly-extra.pljs"
        with zipfile.ZipFile(base_signed, "r") as za, zipfile.ZipFile(manifest_only, "r") as zm, zipfile.ZipFile(manonly_pkg, "w") as zout:
            for item in za.infolist():
                zout.writestr(item, za.read(item.filename))
            zout.writestr("META-INF/MANONLY.SF", zm.read("META-INF/MANONLY.SF"))
            zout.writestr("META-INF/MANONLY.RSA", zm.read("META-INF/MANONLY.RSA"))
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.verify_jar_cryptographic_signers(manonly_pkg, "manonly-extra.pljs", {self.fp_a})

        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [ca_fp],
                    "plugins": [self._entry_from_manifest("chain.pljs", manifest)],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.validate_registry(reg_path)

        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [leaf_fp],
                    "plugins": [self._entry_from_manifest("chain.pljs", manifest)],
                }
            ),
            encoding="utf-8",
        )
        self.assertEqual(registry_tool.validate_registry(reg_path).plugins[0].signer_fingerprint, leaf_fp)

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

    def test_rejects_invalid_and_overflow_catalog_format_version(self) -> None:
        for bad_ver in (0, -1, 2_147_483_648, "1", True, 1.5, None):
            reg_dir, reg_path = self._make_workspace(f"bad_ver_{type(bad_ver).__name__}_{abs(hash(str(bad_ver)))}")
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

        # 5. Tampered .SF file after signing
        _write_unsigned_package(pkg, manifest)
        _sign_package(pkg, self.ks_a, self.env_a, "alpha")
        bad_sf_pkg = reg_dir / "artifacts" / "badsf.pljs"
        with zipfile.ZipFile(pkg, "r") as zin, zipfile.ZipFile(bad_sf_pkg, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename.endswith(".SF"):
                    data += b"\r\nName: fake.txt\r\nSHA-256-Digest: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\r\n"
                zout.writestr(item, data)
        shutil.move(str(bad_sf_pkg), str(pkg))
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.validate_registry(reg_path)

        # 6. Mixed-signer attack fixture: plugin.json signed by A+B, index.js signed ONLY by B
        mixed_pkg = Path(__file__).resolve().parents[3] / "android/core-plugin/src/test/resources/signing/mixed.pljs"
        self.assertTrue(mixed_pkg.is_file(), "Expected mixed.pljs test resource")
        official_fp = "C5:9E:81:65:8F:31:08:29:B4:23:BE:24:2D:C3:28:B3:76:CB:CE:1B:C6:1C:1F:F2:31:31:61:12:41:2B:8C:44"
        third_party_fp = "71:DC:CF:31:C4:2D:EE:68:04:AA:18:20:0C:5B:E1:E3:7B:07:7C:59:9E:54:92:5F:DE:C9:21:F1:6E:4B:EF:67"
        mixed_bytes = mixed_pkg.read_bytes()
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "do not match trustedFingerprints"):
            registry_tool.verify_jar_cryptographic_signers(mixed_bytes, "mixed.pljs", {official_fp})
        report = registry_tool.verify_jar_cryptographic_signers(mixed_bytes, "mixed.pljs", {third_party_fp})
        self.assertEqual(report["matchedSigner"], third_party_fp)

    def test_s2_strict_meta_inf_scope_paths_symlinks_and_bounded_size(self) -> None:
        manifest = _base_manifest("demo-community", "js")
        reg_dir, reg_path = self._make_workspace("s2_meta")
        pkg = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"

        # 1. Arbitrary ordinary file inside META-INF/ (even if signed!) must be rejected
        _write_unsigned_package(pkg, manifest, extra_files={"META-INF/evil.txt": b"hidden payload"})
        _sign_package(pkg, self.ks_a, self.env_a, "alpha")
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "Disallowed file inside META-INF/"):
            registry_tool.inspect_zip_bytes(pkg.read_bytes(), pkg.name)
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.verify_jar_cryptographic_signers(pkg.read_bytes(), pkg.name, {self.fp_a})

        # 2. Case-variant meta-inf/ must be rejected
        _write_unsigned_package(pkg, manifest, extra_files={"meta-inf/extra.txt": b"case bypass"})
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "Case-variant META-INF"):
            registry_tool.inspect_zip_bytes(pkg.read_bytes(), pkg.name)

        # 3. Zip path traversal & non-zero directory entry rejected
        bad_zip = reg_dir / "artifacts" / "trav.pljs"
        with zipfile.ZipFile(bad_zip, "w") as zf:
            zf.writestr("../escape.txt", b"boom")
            zf.writestr("plugin.json", json.dumps(manifest))
            zf.writestr("index.js", b"function entryPoint() {}")
        with self.assertRaises(registry_tool.RegistryValidationError):
            registry_tool.inspect_zip_bytes(bad_zip.read_bytes(), "trav.pljs")

        # 4. Symlink rejection via lstat check
        _write_unsigned_package(pkg, manifest)
        _sign_package(pkg, self.ks_a, self.env_a, "alpha")
        real_lstat = os.lstat

        def fake_lstat(p, *args, **kwargs):
            st = real_lstat(p, *args, **kwargs)
            if Path(p).name == "artifacts":
                # Simulate S_IFLNK mode bit
                return os.stat_result((stat.S_IFLNK | 0o777, *st[1:]))
            return st

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
        with patch.object(registry_tool.os, "lstat", fake_lstat):
            with self.assertRaisesRegex(registry_tool.RegistryValidationError, "Symbolic link is forbidden"):
                registry_tool.validate_registry(reg_path)

        # 5. Bounded size check refuses oversized artifact BEFORE unbounded read
        oversized = reg_dir / "artifacts" / "huge.pljs"
        oversized.write_bytes(b"0")
        with patch.object(registry_tool, "MAX_PACKAGE_FILE_BYTES", 10):
            oversized.write_bytes(b"0" * 11)
            with self.assertRaisesRegex(registry_tool.RegistryValidationError, "size out of bounds"):
                registry_tool.read_bounded_package(oversized, reg_dir / "artifacts")

        # 6. S2 residual: cumulative repo package size limit & streaming ValidatedPlugin has no verified_bytes
        validated_ok = registry_tool.validate_registry(reg_path)
        self.assertFalse(hasattr(validated_ok.plugins[0], "verified_bytes"))
        self.assertGreater(validated_ok.plugins[0].package_size, 0)
        with patch.object(registry_tool, "MAX_TOTAL_REGISTRY_BYTES", 100):
            with self.assertRaisesRegex(registry_tool.RegistryValidationError, "cumulative package size"):
                registry_tool.validate_registry(reg_path)

        # 7. S2 residual: --clean refuses symlinked dist, '..' traversal, or overlapping source directories
        victim_dir = self.root / "victim_should_survive"
        victim_dir.mkdir()
        (victim_dir / "keep.txt").write_text("must-survive", encoding="utf-8")
        symlinked_out = self.root / "symlink_dist"

        def fake_clean_lstat(p, *args, **kwargs):
            st = real_lstat(p, *args, **kwargs)
            if Path(p) == symlinked_out:
                return os.stat_result((stat.S_IFLNK | 0o777, *st[1:]))
            return st

        symlinked_out.mkdir()
        with patch.object(registry_tool.os, "lstat", fake_clean_lstat):
            with self.assertRaisesRegex(registry_tool.RegistryValidationError, "Symbolic link is forbidden"):
                registry_tool.build_static_site(reg_path, symlinked_out, clean=True)
        self.assertTrue((victim_dir / "keep.txt").is_file())

        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "Unanchored"):
            registry_tool.build_static_site(reg_path, self.root / ".." / "escaped-dist", clean=True)

        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "overlaps or encloses"):
            registry_tool.build_static_site(reg_path, reg_dir, clean=True)

    def test_s4_path_segments_contributions_and_dangerous_urls(self) -> None:
        # Unsafe id/version path segment (FilePluginArtifactStore parity)
        for bad_id in ("bad/id", "bad:id", ".."):
            reg_dir, reg_path = self._make_workspace(f"badseg_{abs(hash(bad_id))}")
            m = _base_manifest("demo-community", "js")
            m["id"] = bad_id
            pkg = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
            _write_unsigned_package(pkg, m)
            _sign_package(pkg, self.ks_a, self.env_a, "alpha")
            reg_path.write_text(
                json.dumps(
                    {
                        "sourceId": "demo-community",
                        "sourceType": "THIRD_PARTY",
                        "trustedFingerprints": [self.fp_a],
                        "plugins": [self._entry_from_manifest("demo-js-1.0.0.pljs", m)],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(registry_tool.RegistryValidationError):
                registry_tool.validate_registry(reg_path)

        # Invalid contributions inside signed plugin.json (FormSection on contractVersion=1)
        reg_dir, reg_path = self._make_workspace("bad_contrib")
        m_bad_contrib = _base_manifest("demo-community", "dex")
        m_bad_contrib["contributions"] = [
            {
                "kind": "FormSection",
                "id": "bad-form",
                "slot": "plan.edit.configuration",
                "title": "Bad",
                "fields": [{"id": "sw", "kind": "switch", "label": "S", "value": True, "onEvent": "e"}],
            }
        ]
        pkg_c = reg_dir / "artifacts" / "demo-dex-1.0.0.plp"
        _write_unsigned_package(pkg_c, m_bad_contrib)
        _sign_package(pkg_c, self.ks_a, self.env_a, "alpha")
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [self._entry_from_manifest("demo-dex-1.0.0.plp", m_bad_contrib)],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "FormSection"):
            registry_tool.validate_registry(reg_path)

        # S4 parity: explicit "contributions": null, unstripped id/version spaces, and UTF-16 length (>4096)
        reg_dir, reg_path = self._make_workspace("null_contrib")
        m_null_c = _base_manifest("demo-community", "js")
        m_null_c["contributions"] = None
        pkg_nc = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
        _write_unsigned_package(pkg_nc, m_null_c)
        _sign_package(pkg_nc, self.ks_a, self.env_a, "alpha")
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [self._entry_from_manifest("demo-js-1.0.0.pljs", m_null_c)],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "JSON array"):
            registry_tool.validate_registry(reg_path)

        # Unstripped leading/trailing whitespace in id must be rejected (no pre-strip)
        reg_dir, reg_path = self._make_workspace("space_id")
        m_space = _base_manifest("demo-community", "js")
        m_space["id"] = " demo-js "
        pkg_sp = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
        _write_unsigned_package(pkg_sp, m_space)
        _sign_package(pkg_sp, self.ks_a, self.env_a, "alpha")
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [self._entry_from_manifest("demo-js-1.0.0.pljs", m_space)],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "unsafe path characters"):
            registry_tool.validate_registry(reg_path)

        # UTF-16 code unit count boundary: 2049 supplementary-plane emojis = 2049 Python chars, 4098 UTF-16 units
        reg_dir, reg_path = self._make_workspace("utf16_limit")
        m_utf16 = _base_manifest("demo-community", "js")
        m_utf16["contributions"] = [
            {
                "kind": "FormSection",
                "id": "u16-form",
                "slot": "plan.edit.configuration",
                "title": "U16",
                "fields": [{"id": "note", "kind": "text", "label": "N", "value": "\U0001F600" * 2049, "onEvent": "e"}],
            }
        ]
        pkg_u16 = reg_dir / "artifacts" / "demo-js-1.0.0.pljs"
        _write_unsigned_package(pkg_u16, m_utf16)
        _sign_package(pkg_u16, self.ks_a, self.env_a, "alpha")
        reg_path.write_text(
            json.dumps(
                {
                    "sourceId": "demo-community",
                    "sourceType": "THIRD_PARTY",
                    "trustedFingerprints": [self.fp_a],
                    "plugins": [self._entry_from_manifest("demo-js-1.0.0.pljs", m_utf16)],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(registry_tool.RegistryValidationError, "Invalid text value"):
            registry_tool.validate_registry(reg_path)

        m_utf16["contributions"][0]["fields"][0]["value"] = "\U0001F600" * 2048  # 4096 UTF-16 units -> valid
        _write_unsigned_package(pkg_u16, m_utf16)
        _sign_package(pkg_u16, self.ks_a, self.env_a, "alpha")
        self.assertEqual(len(registry_tool.validate_registry(reg_path).plugins), 1)

        # Dangerous URLs
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

    def test_static_web_assets_have_zero_external_cdn_or_unsafe_dom_sinks(self) -> None:
        web_dir = REGISTRY_ROOT / "web"
        for filename in ("index.html", "style.css", "app.js"):
            text = (web_dir / filename).read_text(encoding="utf-8")
            self.assertNotRegex(text, r"https?://(?!localhost\b)[A-Za-z0-9.-]+", f"External URL found in {filename}")
        app_js = (web_dir / "app.js").read_text(encoding="utf-8")
        for forbidden_sink in ("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval("):
            self.assertNotIn(forbidden_sink, app_js, f"Unsafe DOM sink {forbidden_sink} found in app.js")

    def test_web_app_js_escapes_xss_and_blocks_unsafe_urls_and_off_origin_downloads(self) -> None:
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
assert.strictEqual(app.safeDownloadUrl('https://evil.example.org/artifacts/ok.pljs', 'https://example.org/catalog.json'), null);
assert.strictEqual(app.safeDownloadUrl('https://example.org/other/ok.pljs', 'https://example.org/catalog.json'), null);
assert.strictEqual(app.safeDownloadUrl('./artifacts/ok.pljs', 'https://example.org/catalog.json'), 'https://example.org/artifacts/ok.pljs');
assert.strictEqual(app.safeDownloadUrl('https://example.org/sub/artifacts/ok.plp', 'https://example.org/sub/index.html'), 'https://example.org/sub/artifacts/ok.plp');
assert.strictEqual(app.safeDownloadUrl('https://example.org/other/artifacts/ok.plp', 'https://example.org/sub/index.html'), null);
assert.strictEqual(app.safeDownloadUrl('https://example.org/sub/artifacts/nested/ok.plp', 'https://example.org/sub/index.html'), null);
assert.strictEqual(app.safeDownloadUrl('https://example.org/sub/artifacts/ok.plp?v=1', 'https://example.org/sub/index.html'), null);
assert.strictEqual(app.safeDownloadUrl('https://example.org/sub/artifacts/ok.plp#frag', 'https://example.org/sub/index.html'), null);
assert.strictEqual(app.safeDownloadUrl('https://example.org/sub/artifacts/bad%20name.plp', 'https://example.org/sub/index.html'), null);

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
