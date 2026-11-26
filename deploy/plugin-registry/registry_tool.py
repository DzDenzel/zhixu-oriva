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
MAX_REGISTRY_JSON_BYTES = 2 * 1024 * 1024
MAX_REGISTRY_PLUGINS = 256
MAX_TOTAL_REGISTRY_BYTES = 100 * 1024 * 1024
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
    package_size: int
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


def utf16_len(value: str) -> int:
    """Return JVM/Kotlin String.length (UTF-16 code unit count)."""
    return len(value.encode("utf-16-le")) // 2


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


def require_raw_non_blank_str(obj: dict[str, object], field: str) -> str:
    """Validate required string without stripping whitespace (preserves Android exact matching)."""
    value = obj.get(field)
    if not isinstance(value, str) or not value.strip():
        raise RegistryValidationError(f"Missing or blank required string field: {field}")
    if has_control_chars(value):
        raise RegistryValidationError(f"Control characters are not allowed in {field}")
    return value


def validate_path_segment(value: str, field: str) -> str:
    """Enforce FilePluginArtifactStore.safeSegment() rules on the raw unstripped value."""
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
    if utf16_len(trimmed) > max_length or has_control_chars(trimmed):
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
    if utf16_len(trimmed) > MAX_URL_LEN or any(ch.isspace() for ch in trimmed) or has_control_chars(trimmed) or "\\" in trimmed:
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
    if raw != raw.strip():
        raise RegistryValidationError(f"sourceId must not have leading or trailing whitespace: {raw!r}")
    if raw in {".", ".."} or not SOURCE_ID_PATTERN.fullmatch(raw):
        raise RegistryValidationError(
            "sourceId must be 1..64 characters of [A-Za-z0-9._-] and cannot be '.' or '..'"
        )
    if raw.lower() in RESERVED_SOURCE_IDS:
        raise RegistryValidationError(f"Reserved sourceId is forbidden: {raw}")
    return raw


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
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    file_attrs = getattr(st, "st_file_attributes", 0)
    if stat.S_ISLNK(st.st_mode) or (reparse_flag and (file_attrs & reparse_flag)):
        raise RegistryValidationError(f"Symbolic link is forbidden for {label}: {path.name}")
    return st


def _check_input_path_chain(path: Path, label: str, must_exist: bool = True) -> None:
    """Reject '..' traversal and symlinked directories before reading or cleaning."""
    if ".." in path.parts:
        raise RegistryValidationError(f"Unanchored '..' traversal is forbidden in {label}: {path}")
    if not path.is_absolute():
        current = Path.cwd()
        _lstat_no_symlink(current, f"working directory for {label}")
        for part in path.parts:
            if part in {"", "."}:
                continue
            current = current / part
            if os.path.lexists(current):
                _lstat_no_symlink(current, f"path segment '{part}' in {label}")
    if must_exist or os.path.lexists(path):
        _lstat_no_symlink(path, label)
    parent = path.parent
    if str(parent) and parent != path and os.path.lexists(parent):
        _lstat_no_symlink(parent, f"parent directory of {label}")


def ensure_no_symlinks_to(target: Path, anchor: Path) -> None:
    """Verify anchor and every path segment from anchor to target are non-symlinks."""
    _lstat_no_symlink(anchor, "trusted root")
    try:
        rel = target.relative_to(anchor)
    except ValueError as exc:
        raise RegistryValidationError(f"Path escapes trusted root: {target.name}") from exc
    if ".." in rel.parts:
        raise RegistryValidationError(f"Path traversal escapes trusted root: {target.name}")
    current = anchor
    for part in rel.parts:
        current = current / part
        _lstat_no_symlink(current, f"path segment '{part}'")
    real_anchor = os.path.realpath(anchor)
    real_target = os.path.realpath(target)
    if os.path.commonpath([real_anchor, real_target]) != real_anchor:
        raise RegistryValidationError(f"Real path escapes trusted root: {target.name}")


def _open_anchored_regular_file(anchor_dir: Path, rel_parts: tuple[str, ...], label: str) -> int:
    """Open a regular file anchored to anchor_dir without following symlinks on any component."""
    if not rel_parts or any(part in {"", ".", ".."} or "/" in part or "\\" in part for part in rel_parts):
        raise RegistryValidationError(f"Unanchored relative path for {label}")
    target = anchor_dir.joinpath(*rel_parts)
    ensure_no_symlinks_to(target, anchor_dir)
    st = _lstat_no_symlink(target, label)
    if not stat.S_ISREG(st.st_mode):
        raise RegistryValidationError(f"{label} is not a regular file: {target.name}")

    dir_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    file_flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)

    if os.open in os.supports_dir_fd:
        cur_fd = -1
        try:
            cur_fd = os.open(anchor_dir, dir_flags)
            for seg in rel_parts[:-1]:
                next_fd = os.open(seg, dir_flags, dir_fd=cur_fd)
                os.close(cur_fd)
                cur_fd = next_fd
            file_fd = os.open(rel_parts[-1], file_flags, dir_fd=cur_fd)
            return file_fd
        except OSError as exc:
            raise RegistryValidationError(f"Cannot open {label}: {exc}") from exc
        finally:
            if cur_fd != -1:
                os.close(cur_fd)

    try:
        return os.open(target, file_flags)
    except OSError as exc:
        raise RegistryValidationError(f"Cannot open {label}: {exc}") from exc


def _read_bounded_fd(fd: int, max_bytes: int, label: str, allow_empty: bool = False) -> bytes:
    buf = bytearray()
    try:
        fst = os.fstat(fd)
        if not stat.S_ISREG(fst.st_mode) or fst.st_size > max_bytes or (not allow_empty and fst.st_size <= 0):
            raise RegistryValidationError(f"{label} descriptor invalid or size out of bounds (max {max_bytes})")
        with os.fdopen(fd, "rb") as stream:
            fd = -1
            while True:
                chunk = stream.read(READ_CHUNK_BYTES)
                if not chunk:
                    break
                if len(buf) + len(chunk) > max_bytes:
                    raise RegistryValidationError(f"{label} stream exceeded maximum size ({max_bytes} bytes)")
                buf.extend(chunk)
    finally:
        if fd != -1:
            os.close(fd)
    if not allow_empty and not buf:
        raise RegistryValidationError(f"{label} file is empty")
    return bytes(buf)


def stream_bounded_package_to_file(
    artifact_path: Path,
    artifacts_dir: Path,
    dest_path: Path,
) -> tuple[int, str]:
    """Stream a single non-symlink artifact into dest_path within MAX_PACKAGE_FILE_BYTES and compute SHA-256."""
    ensure_no_symlinks_to(artifact_path, artifacts_dir)
    st = _lstat_no_symlink(artifact_path, f"artifact {artifact_path.name}")
    if not stat.S_ISREG(st.st_mode):
        raise RegistryValidationError(f"Artifact is not a regular file: {artifact_path.name}")
    if st.st_size <= 0 or st.st_size > MAX_PACKAGE_FILE_BYTES:
        raise RegistryValidationError(
            f"Artifact size out of bounds (1..{MAX_PACKAGE_FILE_BYTES} bytes): {artifact_path.name}"
        )

    fd = _open_anchored_regular_file(artifacts_dir, (artifact_path.name,), f"artifact {artifact_path.name}")
    hasher = hashlib.sha256()
    total_read = 0
    out_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    try:
        fst = os.fstat(fd)
        if not stat.S_ISREG(fst.st_mode) or fst.st_size <= 0 or fst.st_size > MAX_PACKAGE_FILE_BYTES:
            raise RegistryValidationError(f"Artifact descriptor invalid or oversized: {artifact_path.name}")
        out_fd = os.open(dest_path, out_flags, 0o600)
        with os.fdopen(fd, "rb") as src_stream, os.fdopen(out_fd, "wb") as dst_stream:
            fd = -1
            while True:
                chunk = src_stream.read(READ_CHUNK_BYTES)
                if not chunk:
                    break
                total_read += len(chunk)
                if total_read > MAX_PACKAGE_FILE_BYTES:
                    raise RegistryValidationError(
                        f"Artifact stream exceeded maximum size ({MAX_PACKAGE_FILE_BYTES} bytes): {artifact_path.name}"
                    )
                hasher.update(chunk)
                dst_stream.write(chunk)
    finally:
        if fd != -1:
            os.close(fd)

    if total_read <= 0:
        raise RegistryValidationError(f"Artifact file is empty: {artifact_path.name}")
    return total_read, hasher.hexdigest().upper()


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

    fd = _open_anchored_regular_file(artifacts_dir, (artifact_path.name,), f"artifact {artifact_path.name}")
    raw = _read_bounded_fd(fd, MAX_PACKAGE_FILE_BYTES, f"artifact {artifact_path.name}")
    return raw, hashlib.sha256(raw).hexdigest().upper()


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


def _inspect_zipfile_core(
    zf: zipfile.ZipFile,
    label: str,
    retain_all_payloads: bool,
) -> tuple[dict[str, bytes], set[str], bytes]:
    """Validate ZIP structure, strict uppercase non-empty META-INF scope, and bounded entry sizes."""
    payload_entries: dict[str, bytes] = {}
    payload_names: set[str] = set()
    dex_header = b""
    seen_names: set[str] = set()
    sf_aliases: set[str] = set()
    sig_aliases: set[str] = set()
    ci_aliases: set[str] = set()
    has_manifest = False
    total_uncompressed = 0

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

        keep_bytes = retain_all_payloads or name == "plugin.json"
        entry_buf = bytearray() if keep_bytes else None
        entry_len = 0
        first_chunk = b""
        with zf.open(info, "r") as stream:
            while True:
                chunk = stream.read(READ_CHUNK_BYTES)
                if not chunk:
                    break
                if not first_chunk:
                    first_chunk = chunk[:4]
                entry_len += len(chunk)
                if entry_len > MAX_ENTRY_BYTES:
                    raise RegistryValidationError(f"Archive entry actual stream exceeds size limit: {name!r}")
                total_uncompressed += len(chunk)
                if total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
                    raise RegistryValidationError(f"Archive exceeds total uncompressed limit: {label}")
                if entry_buf is not None:
                    entry_buf.extend(chunk)

        if name.lower().startswith("meta-inf/"):
            if not name.startswith("META-INF/"):
                raise RegistryValidationError(f"Case-variant META-INF entry forbidden: {name!r}")
            if entry_len <= 0:
                raise RegistryValidationError(f"Empty META-INF metadata entry is forbidden: {name!r}")
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
            if entry_len <= 0:
                raise RegistryValidationError(f"Empty signed payload entry is forbidden: {name!r}")
            payload_names.add(name)
            if name == "classes.dex":
                dex_header = first_chunk
            if entry_buf is not None:
                payload_entries[name] = bytes(entry_buf)

    if not has_manifest:
        raise RegistryValidationError(f"Plugin package {label} is missing META-INF/MANIFEST.MF")
    if not sf_aliases or sf_aliases != sig_aliases or len(ci_aliases) != len(sf_aliases):
        raise RegistryValidationError(f"Plugin package {label} has mismatched or missing META-INF signature pairs")
    if "plugin.json" not in payload_names:
        raise RegistryValidationError(f"Plugin package {label} is missing plugin.json")

    return payload_entries, payload_names, dex_header


def inspect_zip_file(pkg_path: Path, label: str) -> tuple[bytes, set[str], bytes]:
    """Validate ZIP archive from disk without holding unrelated payload entries in memory."""
    try:
        with zipfile.ZipFile(pkg_path, "r") as zf:
            payload_entries, payload_names, dex_header = _inspect_zipfile_core(
                zf, label, retain_all_payloads=False
            )
    except zipfile.BadZipFile as exc:
        raise RegistryValidationError(f"Corrupt or non-ZIP plugin archive {label}: {exc}") from exc
    return payload_entries["plugin.json"], payload_names, dex_header


def inspect_zip_bytes(pkg_bytes: bytes, label: str) -> dict[str, bytes]:
    """Validate raw ZIP structure, strict uppercase META-INF scope, and bounded payload extraction."""
    try:
        with zipfile.ZipFile(io.BytesIO(pkg_bytes), "r") as zf:
            payload_entries, _, _ = _inspect_zipfile_core(zf, label, retain_all_payloads=True)
    except zipfile.BadZipFile as exc:
        raise RegistryValidationError(f"Corrupt or non-ZIP plugin archive {label}: {exc}") from exc
    return payload_entries


def verify_jar_cryptographic_signers(
    pkg_source: bytes | Path,
    label: str,
    trusted_fingerprints: set[str] | None = None,
) -> dict[str, object]:
    """Run JDK JarFile(file, true) verifier over the exact verified artifact and match trusted anchors."""
    if not VERIFIER_JAVA_PATH.is_file():
        raise RegistryValidationError(f"Missing JarSignerVerifier helper: {VERIFIER_JAVA_PATH}")
    java_bin = _find_jdk_tool("java")

    if isinstance(pkg_source, Path):
        proc = _run_tool([java_bin, str(VERIFIER_JAVA_PATH), str(pkg_source)])
    else:
        with tempfile.TemporaryDirectory(prefix="oriva-jar-verify-") as tmp_dir:
            tmp_pkg = Path(tmp_dir) / "package.jar"
            tmp_pkg.write_bytes(pkg_source)
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
    obj: dict[str, object],
    contract_version: int,
    entry_point: str,
    context_label: str,
) -> str:
    if "type" in obj:
        plugin_type = obj["type"]
    else:
        plugin_type = "dex"
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
    """Validate manifest contributions aligned with Android PluginContribution.kt (rejects explicit null)."""
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

        shape_version = item["shapeVersion"] if "shapeVersion" in item else 1
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
                    if not isinstance(fval, str) or utf16_len(fval) > 4096:
                        raise RegistryValidationError(f"Invalid text value in form field {fid!r}")
                elif fkind == "choice":
                    options = field["options"] if "options" in field else []
                    if not isinstance(fval, str) or not isinstance(options, list) or not options or len(options) > 64:
                        raise RegistryValidationError(f"Invalid choice options in form field {fid!r}")
                    opt_values: set[str] = set()
                    for opt in options:
                        if not isinstance(opt, dict):
                            raise RegistryValidationError(f"Invalid choice option in form field {fid!r}")
                        oval = require_raw_non_blank_str(opt, "value")
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
            tone = item["tone"] if "tone" in item else "neutral"
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
            inp = item["input"] if "input" in item else "NONE"
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
    payload_names: set[str],
    dex_header: bytes,
    expected_source_id: str,
    entry_meta: dict[str, object],
) -> None:
    try:
        pkg_manifest = json.loads(pkg_manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RegistryValidationError(f"Invalid UTF-8 JSON in package plugin.json: {exc}") from exc
    if not isinstance(pkg_manifest, dict):
        raise RegistryValidationError("Package plugin.json must be a JSON object")

    pkg_id = validate_path_segment(require_raw_non_blank_str(pkg_manifest, "id"), "plugin.json id")
    pkg_name = require_non_blank_str(pkg_manifest, "name")
    pkg_version = validate_path_segment(require_raw_non_blank_str(pkg_manifest, "version"), "plugin.json version")
    pkg_entry = require_raw_non_blank_str(pkg_manifest, "entryPoint")
    pkg_source_id = require_raw_non_blank_str(pkg_manifest, "sourceId")
    pkg_source_type = require_raw_non_blank_str(pkg_manifest, "sourceType")
    pkg_contract = require_strict_int(pkg_manifest.get("contractVersion"), "plugin.json contractVersion", minimum=1)
    pkg_type = _validate_plugin_type_and_contract(
        pkg_manifest, pkg_contract, pkg_entry, f"plugin.json({pkg_id})"
    )

    if pkg_source_id != expected_source_id:
        raise RegistryValidationError(
            f"Package plugin.json sourceId {pkg_source_id!r} does not match registry sourceId {expected_source_id!r}"
        )
    if pkg_source_type != "THIRD_PARTY":
        raise RegistryValidationError(
            f"Package plugin.json sourceType must be 'THIRD_PARTY', got {pkg_source_type!r}"
        )

    raw_caps = pkg_manifest["requiredCapabilities"] if "requiredCapabilities" in pkg_manifest else []
    pkg_caps = _validate_capabilities(raw_caps, "plugin.json requiredCapabilities")
    if "contributions" in pkg_manifest:
        validate_contributions(pkg_manifest["contributions"], pkg_contract, set(pkg_caps))

    pkg_desc = pkg_manifest["description"] if "description" in pkg_manifest else ""
    if not isinstance(pkg_desc, str) or has_control_chars(pkg_desc):
        raise RegistryValidationError("plugin.json description must be a valid string without control chars")

    code_entry = "index.js" if pkg_type == "js" else "classes.dex"
    if code_entry not in payload_names:
        raise RegistryValidationError(f"Package {pkg_id} ({pkg_type}) is missing required entry {code_entry}")
    if pkg_type == "dex" and not dex_header.startswith(b"dex\n"):
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


def validate_registry(
    registry_path: Path,
    stage_artifacts_dir: Path | None = None,
) -> ValidatedRegistry:
    """Validate registry.json, paths, bounded streaming packages, cryptographic signers, and metadata."""
    _check_input_path_chain(registry_path, "registry.json", must_exist=True)
    resolved_registry = registry_path.resolve()
    if not resolved_registry.is_file():
        raise RegistryValidationError(f"Registry manifest not found: {registry_path}")

    base_dir = resolved_registry.parent
    _lstat_no_symlink(base_dir, "registry directory")
    artifacts_dir = base_dir / "artifacts"

    reg_fd = _open_anchored_regular_file(base_dir, (resolved_registry.name,), "registry.json")
    raw_reg_bytes = _read_bounded_fd(reg_fd, MAX_REGISTRY_JSON_BYTES, "registry.json")

    try:
        raw_doc = json.loads(raw_reg_bytes.decode("utf-8"))
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
    if len(raw_plugins) > MAX_REGISTRY_PLUGINS:
        raise RegistryValidationError(
            f"Registry exceeds max plugin count ({len(raw_plugins)} > {MAX_REGISTRY_PLUGINS})"
        )

    if raw_plugins:
        ensure_no_symlinks_to(artifacts_dir, base_dir)
        if not artifacts_dir.is_dir():
            raise RegistryValidationError(f"Missing artifacts directory: {artifacts_dir}")

    seen_ids: set[str] = set()
    seen_artifacts: set[str] = set()
    validated_plugins: list[ValidatedPlugin] = []
    cumulative_package_bytes = 0

    for idx, entry in enumerate(raw_plugins):
        if not isinstance(entry, dict):
            raise RegistryValidationError(f"plugins[{idx}] must be a JSON object")

        artifact = validate_artifact_name(entry.get("artifact"))
        if artifact in seen_artifacts:
            raise RegistryValidationError(f"Duplicate artifact filename in registry: {artifact!r}")
        seen_artifacts.add(artifact)

        plugin_id = validate_path_segment(require_raw_non_blank_str(entry, "id"), "id")
        if plugin_id in seen_ids:
            raise RegistryValidationError(f"Duplicate plugin id in registry: {plugin_id!r}")
        seen_ids.add(plugin_id)

        name = require_non_blank_str(entry, "name")
        version = validate_path_segment(require_raw_non_blank_str(entry, "version"), f"{plugin_id}.version")
        entry_point = require_raw_non_blank_str(entry, "entryPoint")
        contract_version = require_strict_int(entry.get("contractVersion"), f"{plugin_id}.contractVersion", minimum=1)
        plugin_type = _validate_plugin_type_and_contract(
            entry, contract_version, entry_point, f"registry({plugin_id})"
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

        def _verify_staged(staged_pkg: Path) -> tuple[int, str, str]:
            pkg_size, actual_sha256 = stream_bounded_package_to_file(artifact_path, artifacts_dir, staged_pkg)
            if cumulative_package_bytes + pkg_size > MAX_TOTAL_REGISTRY_BYTES:
                raise RegistryValidationError(
                    f"Registry cumulative package size exceeds limit ({MAX_TOTAL_REGISTRY_BYTES} bytes)"
                )

            declared_sha = entry.get("sha256")
            if declared_sha is not None:
                if not isinstance(declared_sha, str) or not HEX64_PATTERN.fullmatch(declared_sha.strip().upper()):
                    raise RegistryValidationError(f"{plugin_id}.sha256 must be a 64-character hex digest")
                if declared_sha.strip().upper() != actual_sha256:
                    raise RegistryValidationError(
                        f"{plugin_id}.sha256 mismatch: declared {declared_sha.strip().upper()}, actual {actual_sha256}"
                    )

            pkg_manifest_bytes, payload_names, dex_header = inspect_zip_file(staged_pkg, artifact)
            verifier_report = verify_jar_cryptographic_signers(staged_pkg, artifact, trusted_fp_set)
            signer_fp = str(verifier_report["matchedSigner"])

            validate_package_against_entry(
                pkg_manifest_bytes,
                payload_names,
                dex_header,
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
            return pkg_size, actual_sha256, signer_fp

        if stage_artifacts_dir is not None:
            staged_pkg = stage_artifacts_dir / artifact
            pkg_size, actual_sha256, signer_fp = _verify_staged(staged_pkg)
        else:
            with tempfile.TemporaryDirectory(prefix="oriva-pkg-stage-") as pkg_tmp:
                staged_pkg = Path(pkg_tmp) / artifact
                pkg_size, actual_sha256, signer_fp = _verify_staged(staged_pkg)

        cumulative_package_bytes += pkg_size
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
                package_size=pkg_size,
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


def _sha256_file_stream(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(READ_CHUNK_BYTES)
            if not chunk:
                break
            hasher.update(chunk)
    return hasher.hexdigest().lower()


def _validate_output_dir_anchor(output_dir: Path, protected_dirs: tuple[Path, ...]) -> tuple[Path, Path]:
    """Ensure output_dir is strictly anchored inside its non-symlink parent and cannot escape on --clean."""
    _check_input_path_chain(output_dir, "output directory", must_exist=False)
    if not output_dir.name or output_dir.name in {".", ".."}:
        raise RegistryValidationError(f"Unsafe output directory name: {output_dir}")
    parent = output_dir.parent
    if not str(parent) or parent == output_dir:
        raise RegistryValidationError(f"Output directory must specify a non-root parent directory: {output_dir}")

    # Verify all existing ancestors along parent have no symlinks/reparse points before mkdir.
    ancestors_to_check: list[Path] = []
    cur = parent
    while cur and cur != cur.parent:
        if os.path.lexists(cur):
            ancestors_to_check.append(cur)
        cur = cur.parent
    # Check nearest existing parent directory (and relative segments already checked above).
    if ancestors_to_check:
        _lstat_no_symlink(ancestors_to_check[0], "output parent directory")

    parent.mkdir(parents=True, exist_ok=True)
    _lstat_no_symlink(parent, "output parent directory")
    real_parent = Path(os.path.realpath(parent))
    target_out = real_parent / output_dir.name

    if target_out == real_parent or target_out.parent != real_parent:
        raise RegistryValidationError(f"Output directory escapes specified parent: {output_dir}")
    if os.path.commonpath([str(real_parent), str(target_out)]) != str(real_parent):
        raise RegistryValidationError(f"Output directory escapes specified parent: {output_dir}")

    for protected in protected_dirs:
        real_prot = Path(os.path.realpath(protected))
        if target_out == real_prot or real_prot.is_relative_to(target_out):
            raise RegistryValidationError(
                f"Output directory {target_out} overlaps or encloses protected directory {real_prot}"
            )

    if os.path.lexists(target_out):
        st = _lstat_no_symlink(target_out, "output directory")
        if not stat.S_ISDIR(st.st_mode):
            raise RegistryValidationError(f"Output path exists and is not a directory: {target_out}")
        if Path(os.path.realpath(target_out)) != target_out:
            raise RegistryValidationError(f"Output directory resolves outside anchored parent: {target_out}")

    return real_parent, target_out


def build_static_site(
    registry_path: Path,
    output_dir: Path,
    web_dir: Path | None = None,
    clean: bool = False,
) -> ValidatedRegistry:
    """Validate registry fail-closed via streaming staging, then emit deterministic static site."""
    _check_input_path_chain(registry_path, "registry.json", must_exist=True)
    resolved_registry_dir = registry_path.resolve().parent

    raw_web = web_dir or (Path(__file__).resolve().parent / "web")
    _check_input_path_chain(raw_web, "web directory", must_exist=True)
    resolved_web = raw_web.resolve()
    _lstat_no_symlink(resolved_web, "web directory")
    required_web_files = ("index.html", "style.css", "app.js")
    for asset_name in required_web_files:
        asset_path = resolved_web / asset_name
        ensure_no_symlinks_to(asset_path, resolved_web)
        if not asset_path.is_file():
            raise RegistryValidationError(f"Missing web asset: {asset_path}")

    protected_dirs = (resolved_registry_dir, resolved_web, Path(__file__).resolve().parent)
    real_parent, target_out = _validate_output_dir_anchor(output_dir, protected_dirs)

    with tempfile.TemporaryDirectory(prefix="oriva-site-stage-") as stage_tmp:
        stage_dir = Path(stage_tmp)
        stage_artifacts = stage_dir / "artifacts"
        stage_artifacts.mkdir(parents=True, exist_ok=True)

        validated = validate_registry(registry_path, stage_artifacts_dir=stage_artifacts)

        for asset_name in required_web_files:
            web_fd = _open_anchored_regular_file(resolved_web, (asset_name,), f"web asset {asset_name}")
            web_bytes = _read_bounded_fd(web_fd, MAX_ENTRY_BYTES, f"web asset {asset_name}")
            (stage_dir / asset_name).write_bytes(web_bytes)

        catalog_text = json.dumps(validated.to_catalog_document(), ensure_ascii=False, indent=2) + "\n"
        site_meta_text = json.dumps(validated.to_site_metadata(), ensure_ascii=False, indent=2) + "\n"
        _write_utf8_lf(stage_dir / "catalog.json", catalog_text)
        _write_utf8_lf(stage_dir / "site-meta.json", site_meta_text)

        checksum_lines: list[str] = []
        files = sorted(
            (p for p in stage_dir.rglob("*") if p.is_file() and p.name != "SHA256SUMS"),
            key=lambda p: p.relative_to(stage_dir).as_posix(),
        )
        for file_path in files:
            digest = _sha256_file_stream(file_path)
            rel_posix = file_path.relative_to(stage_dir).as_posix()
            checksum_lines.append(f"{digest}  {rel_posix}")
        _write_utf8_lf(stage_dir / "SHA256SUMS", "\n".join(checksum_lines) + "\n")

        # Re-verify output anchor right before publishing / cleaning.
        real_parent_after, target_out_after = _validate_output_dir_anchor(output_dir, protected_dirs)
        if real_parent_after != real_parent or target_out_after != target_out:
            raise RegistryValidationError("Output directory anchor changed during build")

        if clean and os.path.lexists(target_out):
            _lstat_no_symlink(target_out, "output directory")
            if Path(os.path.realpath(target_out)) != target_out or target_out.parent != real_parent:
                raise RegistryValidationError(f"Refusing to clean unanchored output directory: {target_out}")
            shutil.rmtree(target_out)

        target_out.mkdir(parents=True, exist_ok=True)
        for src_file in sorted(stage_dir.rglob("*")):
            rel = src_file.relative_to(stage_dir)
            dst_path = target_out / rel
            if src_file.is_dir():
                dst_path.mkdir(parents=True, exist_ok=True)
                continue
            dst_path.parent.mkdir(parents=True, exist_ok=True)
            with src_file.open("rb") as fin, dst_path.open("wb") as fout:
                while True:
                    chunk = fin.read(READ_CHUNK_BYTES)
                    if not chunk:
                        break
                    fout.write(chunk)

    return validated


def inspect_package_certificates(pkg_path: Path) -> dict[str, object]:
    """Diagnostic-only inspection of package SHA-256 and CodeSigner certificates (grants NO trust)."""
    _check_input_path_chain(pkg_path, f"package {pkg_path.name}", must_exist=True)
    resolved = pkg_path.resolve()
    with tempfile.TemporaryDirectory(prefix="oriva-inspect-stage-") as tmp_dir:
        staged = Path(tmp_dir) / resolved.name
        _, pkg_sha256 = stream_bounded_package_to_file(resolved, resolved.parent, staged)
        manifest_bytes, _, _ = inspect_zip_file(staged, resolved.name)
        report = verify_jar_cryptographic_signers(staged, resolved.name, trusted_fingerprints=None)
    pkg_manifest = json.loads(manifest_bytes.decode("utf-8"))
    return {
        "diagnosticOnly": True,
        "trustGranted": False,
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
        help="Diagnostic-only: print per-entry and common leaf signer SHA-256/SPKI fingerprints (grants no trust).",
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
