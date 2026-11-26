#!/usr/bin/env python3
"""ORIVA open plugin registry validator and deterministic static site builder.

Uses Python 3 standard library plus JDK `java` executing `JarSignerVerifier.java`
(JarFile(file, true) cryptographic per-entry CodeSigner verification aligned with
Android `SignedJarPluginPackageVerifier`). Implements Morphology B catalog output
compatible with `PluginCatalogCodec` and `PluginSourcePolicy`.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.parse
import zipfile

CURRENT_PLUGIN_CONTRACT_VERSION = 2
MAX_JAVA_INT = 2_147_483_647
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
SYSTEM_CAPABILITIES = frozenset(
    {"USAGE_STATS", "ACCESSIBILITY", "DND", "NOTIFICATIONS", "LOCATION"}
)
VALID_PLUGIN_TYPES = frozenset({"dex", "js"})
REGISTERED_SLOTS = frozenset({"plan.detail.protection", "plan.edit.configuration"})
CONTRIBUTION_TONES = frozenset({"neutral", "active", "warn"})
CONTRIBUTION_INPUTS = frozenset({"NONE", "APPLICATION_SINGLE"})

SOURCE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
SAFE_SEGMENT_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")
ARTIFACT_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")
JS_ENTRY_PATTERN = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")
HEX64_PATTERN = re.compile(r"^[0-9A-F]{64}$")
META_SIG_PATTERN = re.compile(r"^META-INF/([A-Za-z0-9_-]{1,64})\.(SF|RSA|DSA|EC)$")

MAX_ARCHIVE_MEMBERS = 2048
MAX_ENTRY_BYTES = 20 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 50 * 1024 * 1024
MAX_PACKAGE_FILE_BYTES = 25 * 1024 * 1024
READ_CHUNK_BYTES = 256 * 1024
MAX_TEXT_METADATA_LEN = 200
MAX_URL_LEN = 2048
TOOL_TIMEOUT_SECONDS = 30

VERIFIER_JAVA_PATH = Path(__file__).resolve().with_name("JarSignerVerifier.java")


class RegistryValidationError(ValueError):
    """Raised when registry metadata, URLs, paths, archives, or signatures fail validation."""


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
    sha256: str
    signer_fingerprint: str
    verified_bytes: bytes
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


def require_strict_int(
    value: object,
    field: str,
    minimum: int = 1,
    maximum: int = MAX_JAVA_INT,
) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise RegistryValidationError(f"{field} must be a JSON integer, not {type(value).__name__}")
    if value < minimum or value > maximum:
        raise RegistryValidationError(f"{field} must be in {minimum}..{maximum}, got {value}")
    return value


def require_non_blank_str(obj: dict[str, object], field: str) -> str:
    value = obj.get(field)
    if not isinstance(value, str) or not value.strip():
        raise RegistryValidationError(f"Missing or blank required string field: {field}")
    if has_control_chars(value):
        raise RegistryValidationError(f"Control characters are not allowed in {field}")
    return value.strip()


def validate_path_segment(value: str, field: str) -> str:
    """Enforce FilePluginArtifactStore.safeSegment() rules for id and version."""
    if value in {".", ".."} or not SAFE_SEGMENT_PATTERN.fullmatch(value):
        raise RegistryValidationError(
            f"{field} contains unsafe path characters (must match [A-Za-z0-9._-]+ and not be '.'/'..'): {value!r}"
        )
    return value


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
        _ = parsed.port
    except ValueError as exc:
        raise RegistryValidationError(f"Malformed URL for {field}: {exc}") from exc
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


def _lstat_no_symlink(path: Path, label: str) -> os.stat_result:
    try:
        st = os.lstat(path)
    except OSError as exc:
        raise RegistryValidationError(f"{label} is not accessible: {exc}") from exc
    if stat.S_ISLNK(st.st_mode):
        raise RegistryValidationError(f"Symbolic link is forbidden for {label}: {path.name}")
    return st


def ensure_no_symlinks_to(target: Path, anchor: Path) -> None:
    """Verify anchor and every path segment from anchor to target are non-symlinks."""
    _lstat_no_symlink(anchor, "trusted root")
    try:
        rel = target.relative_to(anchor)
    except ValueError as exc:
        raise RegistryValidationError(f"Path escapes trusted root: {target.name}") from exc
    current = anchor
    for part in rel.parts:
        current = current / part
        _lstat_no_symlink(current, f"path segment '{part}'")
    real_anchor = os.path.realpath(anchor)
    real_target = os.path.realpath(target)
    if os.path.commonpath([real_anchor, real_target]) != real_anchor:
        raise RegistryValidationError(f"Real path escapes trusted root: {target.name}")


def read_bounded_package(artifact_path: Path, artifacts_dir: Path) -> tuple[bytes, str]:
    """Read a non-symlink artifact once within MAX_PACKAGE_FILE_BYTES and compute SHA-256."""
    ensure_no_symlinks_to(artifact_path, artifacts_dir)
    st = _lstat_no_symlink(artifact_path, f"artifact {artifact_path.name}")
    if not stat.S_ISREG(st.st_mode):
        raise RegistryValidationError(f"Artifact is not a regular file: {artifact_path.name}")
    if st.st_size <= 0 or st.st_size > MAX_PACKAGE_FILE_BYTES:
        raise RegistryValidationError(
            f"Artifact size out of bounds (1..{MAX_PACKAGE_FILE_BYTES} bytes): {artifact_path.name}"
        )

    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(artifact_path, flags)
    except OSError as exc:
        raise RegistryValidationError(f"Cannot open artifact {artifact_path.name}: {exc}") from exc

    hasher = hashlib.sha256()
    buf = bytearray()
    try:
        fst = os.fstat(fd)
        if not stat.S_ISREG(fst.st_mode) or fst.st_size > MAX_PACKAGE_FILE_BYTES:
            raise RegistryValidationError(f"Artifact descriptor invalid or oversized: {artifact_path.name}")
        with os.fdopen(fd, "rb") as stream:
            fd = -1  # owned by stream now
            while True:
                chunk = stream.read(READ_CHUNK_BYTES)
                if not chunk:
                    break
                if len(buf) + len(chunk) > MAX_PACKAGE_FILE_BYTES:
                    raise RegistryValidationError(
                        f"Artifact stream exceeded maximum size ({MAX_PACKAGE_FILE_BYTES} bytes): {artifact_path.name}"
                    )
                buf.extend(chunk)
                hasher.update(chunk)
    finally:
        if fd != -1:
            os.close(fd)

    if not buf:
        raise RegistryValidationError(f"Artifact file is empty: {artifact_path.name}")
    return bytes(buf), hasher.hexdigest().upper()


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


def _run_tool(args: list[str]) -> subprocess.CompletedProcess[str]:
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
        raise RegistryValidationError(f"JDK verifier timed out after {TOOL_TIMEOUT_SECONDS}s") from exc


def inspect_zip_bytes(pkg_bytes: bytes, label: str) -> dict[str, bytes]:
    """Validate raw ZIP structure, strict uppercase META-INF scope, and bounded payload extraction."""
    payload_entries: dict[str, bytes] = {}
    seen_names: set[str] = set()
    sf_aliases: set[str] = set()
    sig_aliases: set[str] = set()
    ci_aliases: set[str] = set()
    has_manifest = False
    total_uncompressed = 0

    try:
        with zipfile.ZipFile(io.BytesIO(pkg_bytes), "r") as zf:
            infos = zf.infolist()
            if len(infos) > MAX_ARCHIVE_MEMBERS:
                raise RegistryValidationError(f"Archive has too many entries ({len(infos)}): {label}")

            for info in infos:
                name = info.filename
                if not name or "\\" in name or "\x00" in name or has_control_chars(name):
                    raise RegistryValidationError(f"Unsafe archive entry path: {name!r}")
                if name.startswith("/") or (len(name) >= 2 and name[1] == ":"):
                    raise RegistryValidationError(f"Absolute archive entry path forbidden: {name!r}")

                unix_mode = (info.external_attr >> 16) & 0o170000
                if unix_mode == 0o120000:
                    raise RegistryValidationError(f"Symbolic link entry forbidden in archive: {name!r}")

                trimmed = name[:-1] if name.endswith("/") else name
                parts = trimmed.split("/")
                if any(part in {"", ".", ".."} for part in parts):
                    raise RegistryValidationError(f"Unsafe or traversal path segment in archive: {name!r}")

                if name in seen_names:
                    raise RegistryValidationError(f"Duplicate archive entry: {name!r}")
                seen_names.add(name)

                if info.is_dir():
                    if name.lower().startswith("meta-inf/") and not name.startswith("META-INF/"):
                        raise RegistryValidationError(f"Case-variant META-INF directory forbidden: {name!r}")
                    if info.file_size > 0 or info.compress_size > 0:
                        raise RegistryValidationError(f"Directory entry has non-zero size: {name!r}")
                    with zf.open(info, "r") as dstream:
                        if dstream.read(1):
                            raise RegistryValidationError(f"Directory entry contains payload bytes: {name!r}")
                    continue

                if info.file_size > MAX_ENTRY_BYTES:
                    raise RegistryValidationError(f"Archive entry exceeds size limit: {name!r}")

                with zf.open(info, "r") as stream:
                    data = stream.read(MAX_ENTRY_BYTES + 1)
                if len(data) > MAX_ENTRY_BYTES:
                    raise RegistryValidationError(f"Archive entry actual stream exceeds size limit: {name!r}")
                total_uncompressed += len(data)
                if total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
                    raise RegistryValidationError(f"Archive exceeds total uncompressed limit: {label}")

                if name.lower().startswith("meta-inf/"):
                    if not name.startswith("META-INF/"):
                        raise RegistryValidationError(f"Case-variant META-INF entry forbidden: {name!r}")
                    if name == "META-INF/MANIFEST.MF":
                        has_manifest = True
                    else:
                        sig_match = META_SIG_PATTERN.fullmatch(name)
                        if not sig_match:
                            raise RegistryValidationError(f"Disallowed file inside META-INF/: {name!r}")
                        alias, ext = sig_match.groups()
                        if ext == "SF":
                            sf_aliases.add(alias)
                        else:
                            if alias in sig_aliases:
                                raise RegistryValidationError(f"Duplicate signature block for alias: {alias!r}")
                            sig_aliases.add(alias)
                        ci_aliases.add(alias.upper())
                else:
                    if not data:
                        raise RegistryValidationError(f"Empty signed payload entry is forbidden: {name!r}")
                    payload_entries[name] = data
    except zipfile.BadZipFile as exc:
        raise RegistryValidationError(f"Corrupt or non-ZIP plugin archive {label}: {exc}") from exc

    if not has_manifest:
        raise RegistryValidationError(f"Plugin package {label} is missing META-INF/MANIFEST.MF")
    if not sf_aliases or sf_aliases != sig_aliases or len(ci_aliases) != len(sf_aliases):
        raise RegistryValidationError(f"Plugin package {label} has mismatched or missing META-INF signature pairs")
    if "plugin.json" not in payload_entries:
        raise RegistryValidationError(f"Plugin package {label} is missing plugin.json")

    return payload_entries


def verify_jar_cryptographic_signers(
    pkg_bytes: bytes,
    label: str,
    trusted_fingerprints: set[str] | None = None,
) -> dict[str, object]:
    """Run JDK JarFile(file, true) verifier over the exact verified bytes and match trusted anchors."""
    if not VERIFIER_JAVA_PATH.is_file():
        raise RegistryValidationError(f"Missing JarSignerVerifier helper: {VERIFIER_JAVA_PATH}")
    java_bin = _find_jdk_tool("java")

    with tempfile.TemporaryDirectory(prefix="oriva-jar-verify-") as tmp_dir:
        tmp_pkg = Path(tmp_dir) / "package.jar"
        tmp_pkg.write_bytes(pkg_bytes)
        proc = _run_tool([java_bin, str(VERIFIER_JAVA_PATH), str(tmp_pkg)])

    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip()
        raise RegistryValidationError(f"Cryptographic JAR verification failed for {label}: {detail}")

    try:
        report = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RegistryValidationError(f"Invalid verifier output for {label}: {exc}") from exc

    common_signers = report.get("commonSigners", [])
    if trusted_fingerprints is not None:
        common_fps = {normalize_fingerprint(s["certSha256"]) for s in common_signers}
        matched = sorted(common_fps & trusted_fingerprints)
        if not matched:
            raise RegistryValidationError(
                f"Plugin package {label} common payload signer(s) {sorted(common_fps)} do not match trustedFingerprints"
            )
        report["matchedSigner"] = matched[0]
    return report


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


def validate_contributions(
    raw_contributions: object,
    contract_version: int,
    declared_capabilities: set[str],
) -> None:
    """Validate optional manifest contributions aligned with Android PluginContribution.kt."""
    if raw_contributions is None:
        return
    if not isinstance(raw_contributions, list):
        raise RegistryValidationError("plugin.json contributions must be a JSON array")

    contrib_ids: set[str] = set()
    field_ids: set[str] = set()

    for idx, item in enumerate(raw_contributions):
        if not isinstance(item, dict):
            raise RegistryValidationError(f"contributions[{idx}] must be a JSON object")
        cid = require_non_blank_str(item, "id")
        if cid in contrib_ids:
            raise RegistryValidationError(f"Duplicate contribution id: {cid!r}")
        contrib_ids.add(cid)

        slot = item.get("slot")
        if not isinstance(slot, str) or slot not in REGISTERED_SLOTS:
            raise RegistryValidationError(f"Unsupported contribution slot: {slot!r}")

        shape_version = item.get("shapeVersion", 1)
        if require_strict_int(shape_version, f"contributions[{cid}].shapeVersion") != 1:
            raise RegistryValidationError(f"Unsupported contribution shapeVersion: {shape_version!r}")

        kind = item.get("kind")
        if kind == "FormSection":
            if contract_version < 2 or slot != "plan.edit.configuration":
                raise RegistryValidationError(f"FormSection {cid!r} requires contractVersion >= 2 and plan.edit.configuration slot")
            require_non_blank_str(item, "title")
            fields = item.get("fields")
            if not isinstance(fields, list) or not fields or len(fields) > 32:
                raise RegistryValidationError(f"FormSection {cid!r} must define 1..32 fields")
            local_field_ids: set[str] = set()
            for field in fields:
                if not isinstance(field, dict):
                    raise RegistryValidationError(f"FormSection {cid!r} field must be an object")
                fid = require_non_blank_str(field, "id")
                if fid in local_field_ids or fid in field_ids:
                    raise RegistryValidationError(f"Duplicate plugin form field: {fid!r}")
                local_field_ids.add(fid)
                field_ids.add(fid)
                require_non_blank_str(field, "label")
                require_non_blank_str(field, "onEvent")
                fkind = field.get("kind")
                fval = field.get("value")
                if fkind == "switch":
                    if not isinstance(fval, bool):
                        raise RegistryValidationError(f"Invalid switch value in form field {fid!r}")
                elif fkind == "text":
                    if not isinstance(fval, str) or len(fval) > 4096:
                        raise RegistryValidationError(f"Invalid text value in form field {fid!r}")
                elif fkind == "choice":
                    options = field.get("options")
                    if not isinstance(fval, str) or not isinstance(options, list) or not options or len(options) > 64:
                        raise RegistryValidationError(f"Invalid choice options in form field {fid!r}")
                    opt_values: set[str] = set()
                    for opt in options:
                        if not isinstance(opt, dict):
                            raise RegistryValidationError(f"Invalid choice option in form field {fid!r}")
                        oval = require_non_blank_str(opt, "value")
                        require_non_blank_str(opt, "label")
                        if oval in opt_values:
                            raise RegistryValidationError(f"Duplicate choice option value {oval!r} in {fid!r}")
                        opt_values.add(oval)
                    if fval not in opt_values:
                        raise RegistryValidationError(f"Choice default value {fval!r} missing from options in {fid!r}")
                else:
                    raise RegistryValidationError(f"Invalid form field kind {fkind!r} in {fid!r}")

        elif kind == "StatusLine":
            require_non_blank_str(item, "text")
            tone = item.get("tone", "neutral")
            if not isinstance(tone, str) or tone not in CONTRIBUTION_TONES:
                raise RegistryValidationError(f"Unsupported StatusLine tone: {tone!r}")
            req_cap = item.get("requiresCapability")
            if req_cap is not None:
                if not isinstance(req_cap, str) or req_cap not in SYSTEM_CAPABILITIES or req_cap not in declared_capabilities:
                    raise RegistryValidationError(f"Undeclared StatusLine capability: {req_cap!r}")
                require_non_blank_str(item, "unavailableText")

        elif kind == "ActionRow":
            require_non_blank_str(item, "label")
            require_non_blank_str(item, "actionId")
            inp = item.get("input", "NONE")
            if not isinstance(inp, str) or inp not in CONTRIBUTION_INPUTS:
                raise RegistryValidationError(f"Unsupported ActionRow input: {inp!r}")

        elif kind == "CapabilityRequest":
            require_non_blank_str(item, "reason")
            require_non_blank_str(item, "actionId")
            cap_id = item.get("capabilityId")
            if not isinstance(cap_id, str) or cap_id not in SYSTEM_CAPABILITIES or cap_id not in declared_capabilities:
                raise RegistryValidationError(f"Undeclared or unsupported CapabilityRequest capabilityId: {cap_id!r}")

        else:
            raise RegistryValidationError(f"Unsupported contribution kind: {kind!r}")


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

    pkg_id = validate_path_segment(require_non_blank_str(pkg_manifest, "id"), "plugin.json id")
    pkg_name = require_non_blank_str(pkg_manifest, "name")
    pkg_version = validate_path_segment(require_non_blank_str(pkg_manifest, "version"), "plugin.json version")
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
    validate_contributions(pkg_manifest.get("contributions"), pkg_contract, set(pkg_caps))

    pkg_desc = pkg_manifest.get("description", "")
    if not isinstance(pkg_desc, str) or has_control_chars(pkg_desc):
        raise RegistryValidationError("plugin.json description must be a valid string without control chars")

    code_entry = "index.js" if pkg_type == "js" else "classes.dex"
    if code_entry not in payload_entries:
        raise RegistryValidationError(f"Package {pkg_id} ({pkg_type}) is missing required entry {code_entry}")
    if pkg_type == "dex" and not payload_entries["classes.dex"].startswith(b"dex\n"):
        raise RegistryValidationError(f"Package {pkg_id} classes.dex does not have a valid DEX header")

    pkg_author = sanitize_optional_text(pkg_manifest.get("author"), "plugin.json author")
    pkg_license = sanitize_optional_text(pkg_manifest.get("license"), "plugin.json license")
    pkg_homepage = validate_http_url(pkg_manifest.get("homepage"), "plugin.json homepage")
    pkg_repository = validate_http_url(pkg_manifest.get("repository"), "plugin.json repository")

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
    """Validate registry.json, paths, bounded packages, cryptographic signers, and metadata."""
    _lstat_no_symlink(registry_path, "registry.json")
    resolved_registry = registry_path.resolve()
    if not resolved_registry.is_file():
        raise RegistryValidationError(f"Registry manifest not found: {registry_path}")

    base_dir = resolved_registry.parent
    _lstat_no_symlink(base_dir, "registry directory")
    artifacts_dir = base_dir / "artifacts"

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

    if raw_plugins:
        ensure_no_symlinks_to(artifacts_dir, base_dir)
        if not artifacts_dir.is_dir():
            raise RegistryValidationError(f"Missing artifacts directory: {artifacts_dir}")

    seen_ids: set[str] = set()
    validated_plugins: list[ValidatedPlugin] = []

    for idx, entry in enumerate(raw_plugins):
        if not isinstance(entry, dict):
            raise RegistryValidationError(f"plugins[{idx}] must be a JSON object")

        artifact = validate_artifact_name(entry.get("artifact"))
        plugin_id = validate_path_segment(require_non_blank_str(entry, "id"), "id")
        if plugin_id in seen_ids:
            raise RegistryValidationError(f"Duplicate plugin id in registry: {plugin_id!r}")
        seen_ids.add(plugin_id)

        name = require_non_blank_str(entry, "name")
        version = validate_path_segment(require_non_blank_str(entry, "version"), f"{plugin_id}.version")
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

        artifact_path = artifacts_dir / artifact
        pkg_bytes, actual_sha256 = read_bounded_package(artifact_path, artifacts_dir)

        declared_sha = entry.get("sha256")
        if declared_sha is not None:
            if not isinstance(declared_sha, str) or not HEX64_PATTERN.fullmatch(declared_sha.strip().upper()):
                raise RegistryValidationError(f"{plugin_id}.sha256 must be a 64-character hex digest")
            if declared_sha.strip().upper() != actual_sha256:
                raise RegistryValidationError(
                    f"{plugin_id}.sha256 mismatch: declared {declared_sha.strip().upper()}, actual {actual_sha256}"
                )

        payload_entries = inspect_zip_bytes(pkg_bytes, artifact)
        verifier_report = verify_jar_cryptographic_signers(pkg_bytes, artifact, trusted_fp_set)
        signer_fp = str(verifier_report["matchedSigner"])

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
                sha256=actual_sha256,
                signer_fingerprint=signer_fp,
                verified_bytes=pkg_bytes,
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
    """Validate registry fail-closed, then deterministically emit static site from verified bytes."""
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
        (artifacts_out / plugin.artifact).write_bytes(plugin.verified_bytes)

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
    """Extract package SHA-256 and cryptographic CodeSigner certificate/SPKI fingerprints via JarSignerVerifier."""
    _lstat_no_symlink(pkg_path, f"package {pkg_path.name}")
    resolved = pkg_path.resolve()
    pkg_bytes, pkg_sha256 = read_bounded_package(resolved, resolved.parent)
    payload_entries = inspect_zip_bytes(pkg_bytes, resolved.name)
    report = verify_jar_cryptographic_signers(pkg_bytes, resolved.name, trusted_fingerprints=None)
    pkg_manifest = json.loads(payload_entries["plugin.json"].decode("utf-8"))
    return {
        "package": resolved.name,
        "sha256": pkg_sha256,
        "id": pkg_manifest.get("id"),
        "sourceId": pkg_manifest.get("sourceId"),
        "commonSigners": report["commonSigners"],
        "payloadEntries": report["payloadEntries"],
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
        "inspect-cert",
        help="Print cryptographic per-entry and common leaf signer SHA-256/SPKI fingerprints for a .plp/.pljs package.",
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
