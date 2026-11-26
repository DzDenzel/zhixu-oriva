#!/usr/bin/env python3
"""ORIVA open plugin registry validator and deterministic static site builder.

Uses Python 3 standard library plus JDK `jarsigner` and `keytool` (invoked
without a shell and with bounded timeouts). Implements Morphology B catalog
output compatible with `PluginCatalogCodec` and `PluginSourcePolicy`.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import zipfile

CURRENT_PLUGIN_CONTRACT_VERSION = 2
RESERVED_SOURCE_IDS = frozenset({"official", "third-party"})
VALID_CAPABILITIES = frozenset(
    {
        "OVERLAY",
        "HOME",
        "HAPTIC",
        "FEEDBACK",
        "USAGE_STATS",
        "ACCESSIBILITY",
        "DND",
        "NOTIFICATIONS",
        "LOCATION",
        "QUERY_TODAY",
        "QUERY_PLAN",
        "QUERY_REPORT",
    }
)
VALID_PLUGIN_TYPES = frozenset({"dex", "js"})

SOURCE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
ARTIFACT_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")
JS_ENTRY_PATTERN = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")
HEX64_PATTERN = re.compile(r"^[0-9A-F]{64}$")

MAX_ARCHIVE_MEMBERS = 2048
MAX_ENTRY_BYTES = 20 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 50 * 1024 * 1024
MAX_PACKAGE_FILE_BYTES = 25 * 1024 * 1024
MAX_TEXT_METADATA_LEN = 200
MAX_URL_LEN = 2048
TOOL_TIMEOUT_SECONDS = 30

JARSIGNER_ENTRY_LINE = re.compile(
    r"^\s*([smkiXa]+)\s+(\d+)\s+[A-Za-z]{3}\s+[A-Za-z]{3}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2}\s+\S+\s+\d{4}\s+(.+?)\r?$"
)
KEYTOOL_SHA256_LINE = re.compile(r"SHA256:\s*([0-9A-Fa-f:]{95}|[0-9A-Fa-f]{64})")
SIG_BLOCK_PATTERN = re.compile(r"^META-INF/(.+)\.(RSA|DSA|EC)$", re.IGNORECASE)
SF_FILE_PATTERN = re.compile(r"^META-INF/(.+)\.SF$", re.IGNORECASE)


class RegistryValidationError(ValueError):
    """Raised when registry metadata, URLs, archives, or signatures fail validation."""


@dataclass(frozen=True)
class ValidatedPlugin:
    plugin_id: str
    name: str
    version: str
    contract_version: int
    plugin_type: str
    entry_point: str
    required_capabilities: tuple[str, ...]
    description: str
    artifact: str
    artifact_path: Path
    sha256: str
    signer_fingerprint: str
    author: str | None
    homepage: str | None
    repository: str | None
    license_name: str | None

    def to_catalog_item(self) -> dict[str, object]:
        item: dict[str, object] = {
            "id": self.plugin_id,
            "name": self.name,
            "version": self.version,
            "contractVersion": self.contract_version,
            "type": self.plugin_type,
            "entryPoint": self.entry_point,
            "requiredCapabilities": list(self.required_capabilities),
            "description": self.description,
            "downloadUrl": f"./artifacts/{self.artifact}",
            "sha256": self.sha256,
        }
        if self.author is not None:
            item["author"] = self.author
        if self.homepage is not None:
            item["homepage"] = self.homepage
        if self.repository is not None:
            item["repository"] = self.repository
        if self.license_name is not None:
            item["license"] = self.license_name
        return item


@dataclass(frozen=True)
class ValidatedRegistry:
    registry_path: Path
    artifacts_dir: Path
    catalog_format_version: int
    source_id: str
    source_type: str
    source_name: str
    source_description: str
    trusted_fingerprints: tuple[str, ...]
    plugins: tuple[ValidatedPlugin, ...]

    def to_catalog_document(self) -> dict[str, object]:
        return {
            "catalogFormatVersion": self.catalog_format_version,
            "sourceId": self.source_id,
            "sourceType": self.source_type,
            "plugins": [plugin.to_catalog_item() for plugin in self.plugins],
        }

    def to_site_metadata(self) -> dict[str, object]:
        return {
            "catalogFormatVersion": self.catalog_format_version,
            "sourceId": self.source_id,
            "sourceType": self.source_type,
            "sourceName": self.source_name,
            "sourceDescription": self.source_description,
            "trustedFingerprints": list(self.trusted_fingerprints),
            "pluginCount": len(self.plugins),
        }


def has_control_chars(value: str) -> bool:
    return any(ord(ch) < 0x20 or 0x7F <= ord(ch) <= 0x9F for ch in value)


def require_strict_int(value: object, field: str, minimum: int = 1) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise RegistryValidationError(f"{field} must be a JSON integer, not {type(value).__name__}")
    if value < minimum:
        raise RegistryValidationError(f"{field} must be >= {minimum}, got {value}")
    return value


def require_non_blank_str(obj: dict[str, object], field: str) -> str:
    value = obj.get(field)
    if not isinstance(value, str) or not value.strip():
        raise RegistryValidationError(f"Missing or blank required string field: {field}")
    if has_control_chars(value):
        raise RegistryValidationError(f"Control characters are not allowed in {field}")
    return value.strip()


def sanitize_optional_text(
    raw: object,
    field: str,
    max_length: int = MAX_TEXT_METADATA_LEN,
) -> str | None:
    if raw is None:
        return None
    if not isinstance(raw, str):
        raise RegistryValidationError(f"{field} must be a string when provided")
    trimmed = raw.strip()
    if not trimmed:
        return None
    if len(trimmed) > max_length or has_control_chars(trimmed):
        raise RegistryValidationError(f"Invalid metadata text for {field}")
    return trimmed


def validate_http_url(raw: object, field: str) -> str | None:
    if raw is None:
        return None
    if not isinstance(raw, str):
        raise RegistryValidationError(f"{field} must be a URL string")
    trimmed = raw.strip()
    if not trimmed:
        return None
    if len(trimmed) > MAX_URL_LEN or any(ch.isspace() for ch in trimmed) or has_control_chars(trimmed) or "\\" in trimmed:
        raise RegistryValidationError(f"Unsafe URL syntax for {field}")
    lower = trimmed.lower()
    if not (lower.startswith("http://") or lower.startswith("https://")):
        raise RegistryValidationError(f"Unsafe URL scheme for {field}: only http:// and https:// are allowed")
    try:
        parsed = urllib.parse.urlsplit(trimmed)
        port = parsed.port
    except ValueError as exc:
        raise RegistryValidationError(f"Malformed URL for {field}: {exc}") from exc
    _ = port
    scheme = (parsed.scheme or "").lower()
    if scheme not in {"http", "https"}:
        raise RegistryValidationError(f"Unsafe URL scheme for {field}")
    if not parsed.hostname or not parsed.hostname.strip():
        raise RegistryValidationError(f"Missing hostname in URL for {field}")
    if parsed.username is not None or parsed.password is not None or "@" in parsed.netloc:
        raise RegistryValidationError(f"Embedded credentials are forbidden in URL for {field}")
    return trimmed


def normalize_fingerprint(raw: object) -> str:
    if not isinstance(raw, str):
        raise RegistryValidationError("Fingerprint must be a string")
    compact = raw.replace(":", "").replace(" ", "").strip().upper()
    if not HEX64_PATTERN.fullmatch(compact):
        raise RegistryValidationError(f"Invalid SHA-256 certificate fingerprint: {raw!r}")
    return ":".join(compact[i : i + 2] for i in range(0, 64, 2))


def validate_source_id(raw: object) -> str:
    if not isinstance(raw, str):
        raise RegistryValidationError("sourceId must be a string")
    trimmed = raw.strip()
    if trimmed in {".", ".."} or not SOURCE_ID_PATTERN.fullmatch(trimmed):
        raise RegistryValidationError(
            "sourceId must be 1..64 characters of [A-Za-z0-9._-] and cannot be '.' or '..'"
        )
    if trimmed.lower() in RESERVED_SOURCE_IDS:
        raise RegistryValidationError(f"Reserved sourceId is forbidden: {trimmed}")
    return trimmed


def validate_artifact_name(raw: object) -> str:
    if not isinstance(raw, str):
        raise RegistryValidationError("artifact must be a string")
    if raw in {".", ".."} or not ARTIFACT_NAME_PATTERN.fullmatch(raw):
        raise RegistryValidationError(f"Unsafe artifact file name: {raw!r}")
    if not (raw.endswith(".plp") or raw.endswith(".pljs")):
        raise RegistryValidationError(f"Artifact must have .plp or .pljs extension: {raw!r}")
    return raw


def _find_jdk_tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        suffix = ".exe" if os.name == "nt" else ""
        candidate = Path(java_home) / "bin" / f"{name}{suffix}"
        if candidate.is_file():
            return str(candidate)
    raise RegistryValidationError(f"Required JDK tool '{name}' not found on PATH or in JAVA_HOME")


def _run_jdk_tool(args: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            args,
            shell=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=TOOL_TIMEOUT_SECONDS,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise RegistryValidationError(f"JDK tool timed out: {Path(args[0]).name}") from exc


def inspect_zip_security(pkg_path: Path) -> tuple[dict[str, bytes], dict[str, bytes]]:
    """Verify ZIP member safety and extract payload & META-INF entries."""
    if not pkg_path.is_file():
        raise RegistryValidationError(f"Artifact file does not exist: {pkg_path.name}")
    if pkg_path.stat().st_size > MAX_PACKAGE_FILE_BYTES:
        raise RegistryValidationError(f"Artifact exceeds maximum size ({MAX_PACKAGE_FILE_BYTES} bytes): {pkg_path.name}")

    payload_entries: dict[str, bytes] = {}
    meta_inf_entries: dict[str, bytes] = {}
    seen_names: set[str] = set()
    total_uncompressed = 0

    try:
        with zipfile.ZipFile(pkg_path, "r") as zf:
            infos = zf.infolist()
            if len(infos) > MAX_ARCHIVE_MEMBERS:
                raise RegistryValidationError(f"Archive has too many entries ({len(infos)}): {pkg_path.name}")

            for info in infos:
                name = info.filename
                if not name or "\\" in name or "\x00" in name or has_control_chars(name):
                    raise RegistryValidationError(f"Unsafe archive entry path: {name!r}")
                if name.startswith("/") or (len(name) >= 2 and name[1] == ":"):
                    raise RegistryValidationError(f"Absolute archive entry path forbidden: {name!r}")

                unix_mode = (info.external_attr >> 16) & 0o170000
                if unix_mode == 0o120000:
                    raise RegistryValidationError(f"Symbolic link entry forbidden in archive: {name!r}")

                posix = PurePosixPath(name)
                if ".." in posix.parts:
                    raise RegistryValidationError(f"Path traversal entry forbidden in archive: {name!r}")

                if name in seen_names:
                    raise RegistryValidationError(f"Duplicate archive entry: {name!r}")
                seen_names.add(name)

                if info.is_dir():
                    continue

                if info.file_size > MAX_ENTRY_BYTES:
                    raise RegistryValidationError(f"Archive entry exceeds size limit: {name!r}")

                with zf.open(info, "r") as stream:
                    data = stream.read(MAX_ENTRY_BYTES + 1)
                if len(data) > MAX_ENTRY_BYTES:
                    raise RegistryValidationError(f"Archive entry actual stream exceeds size limit: {name!r}")
                total_uncompressed += len(data)
                if total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
                    raise RegistryValidationError(f"Archive exceeds total uncompressed limit: {pkg_path.name}")

                if name.upper().startswith("META-INF/"):
                    meta_inf_entries[name] = data
                else:
                    if not data:
                        raise RegistryValidationError(f"Empty signed payload entry is forbidden: {name!r}")
                    payload_entries[name] = data
    except zipfile.BadZipFile as exc:
        raise RegistryValidationError(f"Corrupt or non-ZIP plugin archive {pkg_path.name}: {exc}") from exc

    if "plugin.json" not in payload_entries:
        raise RegistryValidationError(f"Plugin package {pkg_path.name} is missing plugin.json")

    return payload_entries, meta_inf_entries


def _parse_sf_covered_entries(sf_bytes: bytes) -> set[str]:
    text = sf_bytes.decode("utf-8", errors="strict")
    # Unfold RFC-822 manifest continuation lines ("\r\n " or "\n ").
    unfolded = text.replace("\r\n ", "").replace("\n ", "")
    sections = re.split(r"\r?\n\r?\n+", unfolded.strip())
    covered: set[str] = set()
    for section in sections[1:]:
        for line in section.splitlines():
            if line.startswith("Name: "):
                covered.add(line[6:].strip())
    return covered


def _extract_leaf_cert_fingerprint(sig_block_bytes: bytes, keytool_bin: str) -> str:
    with tempfile.TemporaryDirectory(prefix="oriva-cert-") as tmp_dir:
        sig_path = Path(tmp_dir) / "SIGNER.RSA"
        sig_path.write_bytes(sig_block_bytes)
        proc = _run_jdk_tool(
            [keytool_bin, "-J-Duser.language=en", "-J-Duser.country=US", "-printcert", "-file", str(sig_path)]
        )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip()
        raise RegistryValidationError(f"Cannot parse JAR signature certificate: {detail}")
    match = KEYTOOL_SHA256_LINE.search(proc.stdout)
    if not match:
        raise RegistryValidationError("Certificate SHA-256 fingerprint missing from keytool output")
    return normalize_fingerprint(match.group(1))


def _parse_jarsigner_entries(stdout: str) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in stdout.splitlines():
        match = JARSIGNER_ENTRY_LINE.match(line)
        if match:
            flags, _size, entry_name = match.groups()
            entries[entry_name] = flags
    return entries


def verify_jar_signatures(
    pkg_path: Path,
    payload_names: set[str],
    meta_inf_entries: dict[str, bytes],
    trusted_fingerprints: set[str],
) -> str:
    """Verify JAR signatures via JDK tools and match signer intersection to trusted anchors."""
    jarsigner_bin = _find_jdk_tool("jarsigner")
    keytool_bin = _find_jdk_tool("keytool")

    sf_by_alias: dict[str, bytes] = {}
    sig_by_alias: dict[str, bytes] = {}
    for name, data in meta_inf_entries.items():
        sf_match = SF_FILE_PATTERN.fullmatch(name)
        if sf_match:
            sf_by_alias[sf_match.group(1).upper()] = data
            continue
        sig_match = SIG_BLOCK_PATTERN.fullmatch(name)
        if sig_match:
            sig_by_alias[sig_match.group(1).upper()] = data

    common_aliases = sorted(set(sf_by_alias) & set(sig_by_alias))
    if not common_aliases:
        raise RegistryValidationError(f"Plugin package {pkg_path.name} has no valid META-INF signature block")

    proc = _run_jdk_tool(
        [jarsigner_bin, "-J-Duser.language=en", "-J-Duser.country=US", "-verify", "-verbose", "-certs", str(pkg_path)]
    )
    combined = proc.stdout + "\n" + proc.stderr
    if proc.returncode != 0 or "jar verified." not in proc.stdout or "jar is unsigned" in combined.lower():
        raise RegistryValidationError(f"JAR signature verification failed for {pkg_path.name}: {combined.strip()}")

    verified_entries = _parse_jarsigner_entries(proc.stdout)
    for payload_name in sorted(payload_names):
        flags = verified_entries.get(payload_name)
        if not flags or "s" not in flags or "m" not in flags or "i" in flags or "X" in flags:
            raise RegistryValidationError(
                f"Payload entry {payload_name!r} in {pkg_path.name} is not fully signed and manifest-verified (flags={flags!r})"
            )

    signers_per_entry: dict[str, set[str]] = {name: set() for name in payload_names}
    for alias in common_aliases:
        fp = _extract_leaf_cert_fingerprint(sig_by_alias[alias], keytool_bin)
        covered = _parse_sf_covered_entries(sf_by_alias[alias])
        for entry_name in payload_names:
            if entry_name in covered:
                signers_per_entry[entry_name].add(fp)

    common_signers: set[str] | None = None
    for entry_name in sorted(payload_names):
        entry_signers = signers_per_entry[entry_name]
        if not entry_signers:
            raise RegistryValidationError(f"Unsigned plugin payload entry {entry_name!r} in {pkg_path.name}")
        common_signers = entry_signers if common_signers is None else (common_signers & entry_signers)

    assert common_signers is not None
    matched = sorted(common_signers & trusted_fingerprints)
    if not matched:
        raise RegistryValidationError(
            f"Plugin package {pkg_path.name} signer(s) {sorted(common_signers)} do not match trustedFingerprints"
        )
    return matched[0]


def _validate_capabilities(raw: object, field: str = "requiredCapabilities") -> tuple[str, ...]:
    if not isinstance(raw, list):
        raise RegistryValidationError(f"{field} must be a JSON array")
    result: list[str] = []
    for cap in raw:
        if not isinstance(cap, str) or cap not in VALID_CAPABILITIES:
            raise RegistryValidationError(f"Unsupported plugin capability: {cap!r}")
        if cap not in result:
            result.append(cap)
    return tuple(result)


def _validate_plugin_type_and_contract(
    raw_type: object,
    contract_version: int,
    entry_point: str,
    context_label: str,
) -> str:
    plugin_type = "dex" if raw_type is None else raw_type
    if not isinstance(plugin_type, str) or plugin_type not in VALID_PLUGIN_TYPES:
        raise RegistryValidationError(f"{context_label}: unsupported plugin type {plugin_type!r}")
    if contract_version > CURRENT_PLUGIN_CONTRACT_VERSION:
        raise RegistryValidationError(
            f"{context_label}: unsupported contractVersion {contract_version} (max {CURRENT_PLUGIN_CONTRACT_VERSION})"
        )
    if plugin_type == "js":
        if contract_version != 2:
            raise RegistryValidationError(f"{context_label}: js plugin requires contractVersion == 2")
        if not JS_ENTRY_PATTERN.fullmatch(entry_point):
            raise RegistryValidationError(f"{context_label}: invalid JS entryPoint {entry_point!r}")
    return plugin_type


def validate_package_against_entry(
    pkg_manifest_bytes: bytes,
    payload_entries: dict[str, bytes],
    expected_source_id: str,
    entry_meta: dict[str, object],
) -> None:
    try:
        pkg_manifest = json.loads(pkg_manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RegistryValidationError(f"Invalid UTF-8 JSON in package plugin.json: {exc}") from exc
    if not isinstance(pkg_manifest, dict):
        raise RegistryValidationError("Package plugin.json must be a JSON object")

    pkg_id = require_non_blank_str(pkg_manifest, "id")
    pkg_name = require_non_blank_str(pkg_manifest, "name")
    pkg_version = require_non_blank_str(pkg_manifest, "version")
    pkg_entry = require_non_blank_str(pkg_manifest, "entryPoint")
    pkg_source_id = require_non_blank_str(pkg_manifest, "sourceId")
    pkg_source_type = require_non_blank_str(pkg_manifest, "sourceType")
    pkg_contract = require_strict_int(pkg_manifest.get("contractVersion"), "plugin.json contractVersion", minimum=1)
    pkg_type = _validate_plugin_type_and_contract(
        pkg_manifest.get("type"), pkg_contract, pkg_entry, f"plugin.json({pkg_id})"
    )

    if pkg_source_id != expected_source_id:
        raise RegistryValidationError(
            f"Package plugin.json sourceId {pkg_source_id!r} does not match registry sourceId {expected_source_id!r}"
        )
    if pkg_source_type != "THIRD_PARTY":
        raise RegistryValidationError(
            f"Package plugin.json sourceType must be 'THIRD_PARTY', got {pkg_source_type!r}"
        )

    pkg_caps = _validate_capabilities(pkg_manifest.get("requiredCapabilities", []), "plugin.json requiredCapabilities")

    pkg_desc = pkg_manifest.get("description", "")
    if not isinstance(pkg_desc, str):
        raise RegistryValidationError("plugin.json description must be a string")

    code_entry = "index.js" if pkg_type == "js" else "classes.dex"
    if code_entry not in payload_entries:
        raise RegistryValidationError(f"Package {pkg_id} ({pkg_type}) is missing required entry {code_entry}")
    if pkg_type == "dex" and not payload_entries["classes.dex"].startswith(b"dex\n"):
        raise RegistryValidationError(f"Package {pkg_id} classes.dex does not have a valid DEX header")

    # Validate optional metadata inside plugin.json too.
    pkg_author = sanitize_optional_text(pkg_manifest.get("author"), "plugin.json author")
    pkg_license = sanitize_optional_text(pkg_manifest.get("license"), "plugin.json license")
    pkg_homepage = validate_http_url(pkg_manifest.get("homepage"), "plugin.json homepage")
    pkg_repository = validate_http_url(pkg_manifest.get("repository"), "plugin.json repository")

    # Cross-check registry entry vs package plugin.json.
    mismatches: list[str] = []
    if pkg_id != entry_meta["id"]:
        mismatches.append(f"id ({entry_meta['id']!r} != {pkg_id!r})")
    if pkg_name != entry_meta["name"]:
        mismatches.append(f"name ({entry_meta['name']!r} != {pkg_name!r})")
    if pkg_version != entry_meta["version"]:
        mismatches.append(f"version ({entry_meta['version']!r} != {pkg_version!r})")
    if pkg_contract != entry_meta["contractVersion"]:
        mismatches.append(f"contractVersion ({entry_meta['contractVersion']!r} != {pkg_contract!r})")
    if pkg_type != entry_meta["type"]:
        mismatches.append(f"type ({entry_meta['type']!r} != {pkg_type!r})")
    if pkg_entry != entry_meta["entryPoint"]:
        mismatches.append(f"entryPoint ({entry_meta['entryPoint']!r} != {pkg_entry!r})")
    if set(pkg_caps) != set(entry_meta["requiredCapabilities"]):  # type: ignore[arg-type]
        mismatches.append("requiredCapabilities")
    for opt_name, pkg_val in (
        ("author", pkg_author),
        ("license", pkg_license),
        ("homepage", pkg_homepage),
        ("repository", pkg_repository),
    ):
        reg_val = entry_meta[opt_name]
        if pkg_val is not None and reg_val is not None and pkg_val != reg_val:
            mismatches.append(f"{opt_name} ({reg_val!r} != {pkg_val!r})")
    if mismatches:
        raise RegistryValidationError(
            f"Registry entry {entry_meta['id']!r} mismatches signed plugin.json fields: {', '.join(mismatches)}"
        )


def validate_registry(registry_path: Path) -> ValidatedRegistry:
    """Validate registry.json, all referenced packages, signatures, and metadata fail-closed."""
    resolved_registry = registry_path.resolve()
    if not resolved_registry.is_file():
        raise RegistryValidationError(f"Registry manifest not found: {registry_path}")

    try:
        raw_doc = json.loads(resolved_registry.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RegistryValidationError(f"Invalid UTF-8 JSON in {registry_path.name}: {exc}") from exc

    if not isinstance(raw_doc, dict):
        raise RegistryValidationError("Registry root must be a JSON object")

    if "catalogFormatVersion" in raw_doc:
        catalog_format_version = require_strict_int(
            raw_doc["catalogFormatVersion"], "catalogFormatVersion", minimum=1
        )
    else:
        catalog_format_version = 1

    source_id = validate_source_id(raw_doc.get("sourceId"))
    source_type = raw_doc.get("sourceType")
    if source_type != "THIRD_PARTY":
        raise RegistryValidationError(
            f"Custom registry sourceType must be 'THIRD_PARTY', got {source_type!r}"
        )

    source_name = sanitize_optional_text(raw_doc.get("sourceName"), "sourceName", max_length=80) or source_id
    source_description = (
        sanitize_optional_text(raw_doc.get("sourceDescription"), "sourceDescription", max_length=500) or ""
    )

    raw_fps = raw_doc.get("trustedFingerprints")
    if not isinstance(raw_fps, list) or not raw_fps:
        raise RegistryValidationError("trustedFingerprints must be a non-empty list of SHA-256 fingerprints")
    trusted_fp_set = {normalize_fingerprint(fp) for fp in raw_fps}

    raw_plugins = raw_doc.get("plugins")
    if not isinstance(raw_plugins, list):
        raise RegistryValidationError("plugins must be a JSON array")

    base_dir = resolved_registry.parent
    artifacts_dir = (base_dir / "artifacts").resolve()

    seen_ids: set[str] = set()
    validated_plugins: list[ValidatedPlugin] = []

    for idx, entry in enumerate(raw_plugins):
        if not isinstance(entry, dict):
            raise RegistryValidationError(f"plugins[{idx}] must be a JSON object")

        artifact = validate_artifact_name(entry.get("artifact"))
        plugin_id = require_non_blank_str(entry, "id")
        if plugin_id in seen_ids:
            raise RegistryValidationError(f"Duplicate plugin id in registry: {plugin_id!r}")
        seen_ids.add(plugin_id)

        name = require_non_blank_str(entry, "name")
        version = require_non_blank_str(entry, "version")
        entry_point = require_non_blank_str(entry, "entryPoint")
        contract_version = require_strict_int(entry.get("contractVersion"), f"{plugin_id}.contractVersion", minimum=1)
        plugin_type = _validate_plugin_type_and_contract(
            entry.get("type"), contract_version, entry_point, f"registry({plugin_id})"
        )
        required_caps = _validate_capabilities(entry.get("requiredCapabilities"), f"{plugin_id}.requiredCapabilities")

        description = entry.get("description")
        if not isinstance(description, str) or has_control_chars(description):
            raise RegistryValidationError(f"{plugin_id}.description must be a valid string without control chars")

        author = sanitize_optional_text(entry.get("author"), f"{plugin_id}.author")
        license_name = sanitize_optional_text(entry.get("license"), f"{plugin_id}.license")
        homepage = validate_http_url(entry.get("homepage"), f"{plugin_id}.homepage")
        repository = validate_http_url(entry.get("repository"), f"{plugin_id}.repository")

        if "downloadUrl" in entry and entry["downloadUrl"] is not None:
            validate_http_url(entry["downloadUrl"], f"{plugin_id}.downloadUrl")

        artifact_path = (artifacts_dir / artifact).resolve()
        if artifact_path.parent != artifacts_dir or not artifact_path.is_file():
            raise RegistryValidationError(f"Artifact missing or outside artifacts/: {artifact}")

        actual_sha256 = hashlib.sha256(artifact_path.read_bytes()).hexdigest().upper()
        declared_sha = entry.get("sha256")
        if declared_sha is not None:
            if not isinstance(declared_sha, str) or not HEX64_PATTERN.fullmatch(declared_sha.strip().upper()):
                raise RegistryValidationError(f"{plugin_id}.sha256 must be a 64-character hex digest")
            if declared_sha.strip().upper() != actual_sha256:
                raise RegistryValidationError(
                    f"{plugin_id}.sha256 mismatch: declared {declared_sha.strip().upper()}, actual {actual_sha256}"
                )

        payload_entries, meta_inf_entries = inspect_zip_security(artifact_path)
        signer_fp = verify_jar_signatures(
            artifact_path, set(payload_entries.keys()), meta_inf_entries, trusted_fp_set
        )
        validate_package_against_entry(
            payload_entries["plugin.json"],
            payload_entries,
            source_id,
            {
                "id": plugin_id,
                "name": name,
                "version": version,
                "contractVersion": contract_version,
                "type": plugin_type,
                "entryPoint": entry_point,
                "requiredCapabilities": required_caps,
                "author": author,
                "license": license_name,
                "homepage": homepage,
                "repository": repository,
            },
        )

        validated_plugins.append(
            ValidatedPlugin(
                plugin_id=plugin_id,
                name=name,
                version=version,
                contract_version=contract_version,
                plugin_type=plugin_type,
                entry_point=entry_point,
                required_capabilities=required_caps,
                description=description,
                artifact=artifact,
                artifact_path=artifact_path,
                sha256=actual_sha256,
                signer_fingerprint=signer_fp,
                author=author,
                homepage=homepage,
                repository=repository,
                license_name=license_name,
            )
        )

    validated_plugins.sort(key=lambda p: (p.plugin_id, p.version))
    return ValidatedRegistry(
        registry_path=resolved_registry,
        artifacts_dir=artifacts_dir,
        catalog_format_version=catalog_format_version,
        source_id=source_id,
        source_type=source_type,
        source_name=source_name,
        source_description=source_description,
        trusted_fingerprints=tuple(sorted(trusted_fp_set)),
        plugins=tuple(validated_plugins),
    )


def _write_utf8_lf(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


def build_static_site(
    registry_path: Path,
    output_dir: Path,
    web_dir: Path | None = None,
    clean: bool = False,
) -> ValidatedRegistry:
    """Validate registry fail-closed, then deterministically emit static site + catalog.json."""
    validated = validate_registry(registry_path)
    resolved_web = (web_dir or (Path(__file__).resolve().parent / "web")).resolve()
    required_web_files = ("index.html", "style.css", "app.js")
    for asset_name in required_web_files:
        if not (resolved_web / asset_name).is_file():
            raise RegistryValidationError(f"Missing web asset: {resolved_web / asset_name}")

    resolved_out = output_dir.resolve()
    if clean and resolved_out.exists():
        shutil.rmtree(resolved_out)
    resolved_out.mkdir(parents=True, exist_ok=True)

    artifacts_out = resolved_out / "artifacts"
    artifacts_out.mkdir(parents=True, exist_ok=True)
    for plugin in validated.plugins:
        (artifacts_out / plugin.artifact).write_bytes(plugin.artifact_path.read_bytes())

    for asset_name in required_web_files:
        (resolved_out / asset_name).write_bytes((resolved_web / asset_name).read_bytes())

    catalog_text = json.dumps(validated.to_catalog_document(), ensure_ascii=False, indent=2) + "\n"
    site_meta_text = json.dumps(validated.to_site_metadata(), ensure_ascii=False, indent=2) + "\n"
    _write_utf8_lf(resolved_out / "catalog.json", catalog_text)
    _write_utf8_lf(resolved_out / "site-meta.json", site_meta_text)

    checksum_lines: list[str] = []
    files = sorted(
        (p for p in resolved_out.rglob("*") if p.is_file() and p.name != "SHA256SUMS"),
        key=lambda p: p.relative_to(resolved_out).as_posix(),
    )
    for file_path in files:
        digest = hashlib.sha256(file_path.read_bytes()).hexdigest().lower()
        rel_posix = file_path.relative_to(resolved_out).as_posix()
        checksum_lines.append(f"{digest}  {rel_posix}")
    _write_utf8_lf(resolved_out / "SHA256SUMS", "\n".join(checksum_lines) + "\n")

    return validated


def inspect_package_certificates(pkg_path: Path) -> dict[str, object]:
    """Extract package SHA-256 and per-alias leaf certificate SHA-256 fingerprints."""
    resolved = pkg_path.resolve()
    payload_entries, meta_inf_entries = inspect_zip_security(resolved)
    keytool_bin = _find_jdk_tool("keytool")
    jarsigner_bin = _find_jdk_tool("jarsigner")

    proc = _run_jdk_tool(
        [jarsigner_bin, "-J-Duser.language=en", "-J-Duser.country=US", "-verify", "-verbose", "-certs", str(resolved)]
    )
    if proc.returncode != 0 or "jar verified." not in proc.stdout:
        raise RegistryValidationError(f"Package failed jarsigner verification: {(proc.stdout + proc.stderr).strip()}")

    signers: dict[str, str] = {}
    for name, data in sorted(meta_inf_entries.items()):
        sig_match = SIG_BLOCK_PATTERN.fullmatch(name)
        if sig_match:
            alias = sig_match.group(1).upper()
            signers[alias] = _extract_leaf_cert_fingerprint(data, keytool_bin)

    if not signers:
        raise RegistryValidationError(f"No certificate blocks found in {pkg_path.name}")

    pkg_manifest = json.loads(payload_entries["plugin.json"].decode("utf-8"))
    return {
        "package": resolved.name,
        "sha256": hashlib.sha256(resolved.read_bytes()).hexdigest().upper(),
        "id": pkg_manifest.get("id"),
        "sourceId": pkg_manifest.get("sourceId"),
        "signers": signers,
    }


def _build_parser() -> argparse.ArgumentParser:
    default_registry = Path(__file__).resolve().parent / "registry.json"
    parser = argparse.ArgumentParser(description="ORIVA open plugin registry validator & static site builder.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    val_parser = subparsers.add_parser("validate", help="Validate registry.json and all signed plugin packages.")
    val_parser.add_argument("--registry", type=Path, default=default_registry, help="Path to registry.json")
    val_parser.add_argument("--json", action="store_true", help="Output validation summary as JSON")

    build_parser = subparsers.add_parser("build", help="Validate and deterministically build static site output.")
    build_parser.add_argument("--registry", type=Path, default=default_registry, help="Path to registry.json")
    build_parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "dist",
        help="Destination directory for static site build",
    )
    build_parser.add_argument("--web-dir", type=Path, default=None, help="Optional override for web assets directory")
    build_parser.add_argument("--clean", action="store_true", help="Remove existing output directory before writing")

    inspect_parser = subparsers.add_parser(
        "inspect-cert", help="Print leaf signer certificate SHA-256 fingerprint(s) for a signed .plp/.pljs package."
    )
    inspect_parser.add_argument("--package", type=Path, required=True, help="Path to .plp or .pljs package")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            validated = validate_registry(args.registry)
            summary = {
                "status": "ok",
                "sourceId": validated.source_id,
                "sourceType": validated.source_type,
                "catalogFormatVersion": validated.catalog_format_version,
                "pluginCount": len(validated.plugins),
                "plugins": [
                    {"id": p.plugin_id, "version": p.version, "sha256": p.sha256, "signer": p.signer_fingerprint}
                    for p in validated.plugins
                ],
            }
            if args.json:
                print(json.dumps(summary, ensure_ascii=False, indent=2))
            else:
                print(
                    f"VALIDATED {validated.source_id} (format v{validated.catalog_format_version}, {len(validated.plugins)} plugin(s))"
                )
            return 0

        if args.command == "build":
            validated = build_static_site(args.registry, args.output, web_dir=args.web_dir, clean=args.clean)
            print(
                f"BUILT {args.output} ({len(validated.plugins)} plugin(s), sourceId={validated.source_id})"
            )
            return 0

        if args.command == "inspect-cert":
            info = inspect_package_certificates(args.package)
            print(json.dumps(info, ensure_ascii=False, indent=2))
            return 0

    except RegistryValidationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 1


if __name__ == "__main__":
    sys.exit(main())
