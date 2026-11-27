#!/usr/bin/env python3
"""ORIVA open plugin registry validator and deterministic static site builder.

Uses Python 3 standard library plus JDK `java` executing `JarSignerVerifier.java`
(JarFile(file, true) cryptographic per-entry CodeSigner verification aligned with
Android `SignedJarPluginPackageVerifier`). Implements Morphology B catalog output
compatible with `PluginCatalogCodec` and `PluginSourcePolicy`.
"""

from __future__ import annotations

import argparse
import ctypes
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
import time
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
MAX_SIGNATURE_ALIASES = 16
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


def _resolve_non_symlink_path(
    path: Path,
    label: str,
    must_exist: bool = True,
    stop_at: Path | None = None,
) -> Path:
    """Verify every ancestor is a non-symlink/non-reparse point, then resolve 8.3/case equivalence cleanly."""
    if ".." in path.parts:
        raise RegistryValidationError(f"Unanchored '..' traversal is forbidden in {label}: {path}")

    real_stop: Path | None = None
    if stop_at is not None:
        real_stop = _resolve_non_symlink_path(stop_at, f"trusted root for {label}", must_exist=True, stop_at=None)

    if path.is_absolute():
        abs_path = Path(os.path.abspath(path))
        anchor = Path(abs_path.anchor)
        _lstat_no_symlink(anchor, f"root anchor of {label}")
        current = anchor
        deepest_existing = anchor
        suffix_parts: list[str] = []
        missing_seen = False
        for part in abs_path.parts[1:]:
            if part in {"", "."}:
                continue
            current = current / part
            if not missing_seen and os.path.lexists(current):
                _lstat_no_symlink(current, f"ancestor '{part}' of {label}")
                deepest_existing = current
            else:
                if must_exist and not os.path.lexists(current):
                    _lstat_no_symlink(current, label)
                missing_seen = True
                suffix_parts.append(part)
        if os.path.lexists(abs_path):
            _lstat_no_symlink(abs_path, label)
        real_prefix = Path(os.path.realpath(deepest_existing))
        canonical = real_prefix.joinpath(*suffix_parts) if suffix_parts else real_prefix
    else:
        cwd_real = _resolve_non_symlink_path(Path.cwd(), f"working directory for {label}", must_exist=True, stop_at=None)
        current = cwd_real
        deepest_existing = cwd_real
        suffix_parts = []
        missing_seen = False
        for part in path.parts:
            if part in {"", "."}:
                continue
            current = current / part
            if not missing_seen and os.path.lexists(current):
                _lstat_no_symlink(current, f"path segment '{part}' in {label}")
                deepest_existing = current
            else:
                if must_exist and not os.path.lexists(current):
                    _lstat_no_symlink(current, label)
                missing_seen = True
                suffix_parts.append(part)
        if os.path.lexists(cwd_real / path):
            _lstat_no_symlink(cwd_real / path, label)
        real_prefix = Path(os.path.realpath(deepest_existing))
        canonical = real_prefix.joinpath(*suffix_parts) if suffix_parts else real_prefix

    if real_stop is not None:
        if not canonical.is_relative_to(real_stop):
            raise RegistryValidationError(f"{label} escapes trusted root {real_stop}: {canonical}")
    return canonical


def _check_ancestor_chain_no_symlinks(
    path: Path,
    label: str,
    must_exist: bool = True,
    stop_at: Path | None = None,
) -> Path:
    """Backward-compatible wrapper returning canonical non-symlink path."""
    return _resolve_non_symlink_path(path, label, must_exist=must_exist, stop_at=stop_at)


def ensure_no_symlinks_to(target: Path, anchor: Path) -> None:
    """Verify anchor and every path segment from anchor to target are non-symlinks."""
    real_anchor = _resolve_non_symlink_path(anchor, "trusted root", must_exist=True)
    _resolve_non_symlink_path(target, target.name, must_exist=True, stop_at=real_anchor)


def _win_final_path_for_fd(fd: int) -> Path | None:
    if os.name != "nt":
        return None
    import ctypes.wintypes as wt
    import msvcrt

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.GetFinalPathNameByHandleW.argtypes = [wt.HANDLE, wt.LPWSTR, wt.DWORD, wt.DWORD]
    k32.GetFinalPathNameByHandleW.restype = wt.DWORD

    handle = msvcrt.get_osfhandle(fd)
    buf = ctypes.create_unicode_buffer(4096)
    length = k32.GetFinalPathNameByHandleW(handle, buf, len(buf), 0)
    if length == 0 or length >= len(buf):
        raise RegistryValidationError(f"Cannot resolve OS handle path (winerr={ctypes.get_last_error()})")
    raw_final = buf.value
    if raw_final.startswith("\\\\?\\UNC\\"):
        raw_final = "\\\\" + raw_final[8:]
    elif raw_final.startswith("\\\\?\\"):
        raw_final = raw_final[4:]
    return Path(raw_final)


def _win_norm_path(path: Path) -> str:
    return os.path.normcase(os.path.normpath(str(path)))


class _WinAnchoredDirHandle:
    """Hold an open non-reparse Win32 directory handle across anchored checks, cleanup, and atomic publish."""

    def __init__(
        self,
        dir_path: Path,
        label: str,
        expected_id: tuple[int, int] | None = None,
        allow_delete_share: bool = False,
    ) -> None:
        self.dir_path = dir_path
        self.label = label
        self.expected_id = expected_id
        self.handle = None
        self.final_path: Path | None = None
        self.dir_id: tuple[int, int] | None = None
        self._k32 = None
        self._wt = None

        if os.name != "nt":
            return
        import ctypes
        import ctypes.wintypes as wt

        class _FILE_INFO(ctypes.Structure):
            _fields_ = [
                ("dwFileAttributes", wt.DWORD),
                ("ftCreationTime", wt.FILETIME),
                ("ftLastAccessTime", wt.FILETIME),
                ("ftLastWriteTime", wt.FILETIME),
                ("dwVolumeSerialNumber", wt.DWORD),
                ("nFileSizeHigh", wt.DWORD),
                ("nFileSizeLow", wt.DWORD),
                ("nNumberOfLinks", wt.DWORD),
                ("nFileIndexHigh", wt.DWORD),
                ("nFileIndexLow", wt.DWORD),
            ]

        self._ctypes = ctypes
        self._wt = wt
        self._file_info_cls = _FILE_INFO
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, wt.LPVOID, wt.DWORD, wt.DWORD, wt.HANDLE]
        k32.CreateFileW.restype = wt.HANDLE
        k32.GetFileInformationByHandle.argtypes = [wt.HANDLE, ctypes.POINTER(_FILE_INFO)]
        k32.GetFileInformationByHandle.restype = wt.BOOL
        k32.GetFinalPathNameByHandleW.argtypes = [wt.HANDLE, wt.LPWSTR, wt.DWORD, wt.DWORD]
        k32.GetFinalPathNameByHandleW.restype = wt.DWORD
        k32.CloseHandle.argtypes = [wt.HANDLE]
        k32.CloseHandle.restype = wt.BOOL
        self._k32 = k32

        lst = _lstat_no_symlink(dir_path, label)
        if not stat.S_ISDIR(lst.st_mode):
            raise RegistryValidationError(f"{label} is not a directory: {dir_path}")
        if expected_id is not None and (lst.st_dev, lst.st_ino) != expected_id:
            raise RegistryValidationError(f"{label} identity changed before opening handle: {dir_path.name}")

        # Open the directory itself without traversing reparse points; restrict delete share unless caller controls removal.
        share_mode = 0x0001 | 0x0002 | (0x0004 if allow_delete_share else 0)
        flags = 0x02000000 | 0x00200000  # FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT
        invalid_handle = wt.HANDLE(-1).value
        handle = k32.CreateFileW(str(dir_path), 0x0001, share_mode, None, 3, flags, None)
        if not handle or handle == invalid_handle:
            raise RegistryValidationError(
                f"Cannot open anchored Windows handle for {label} (winerr={ctypes.get_last_error()}): {dir_path.name}"
            )
        self.handle = handle
        try:
            self.dir_id, self.final_path = self._query_handle_state()
            self.verify_matches(dir_path, expected_id=expected_id or (lst.st_dev, lst.st_ino))
        except Exception:
            self.close()
            raise

    def _query_handle_state(self) -> tuple[tuple[int, int], Path]:
        if self.handle is None:
            raise RegistryValidationError(f"Directory handle is closed for {self.label}")
        info = self._file_info_cls()
        if not self._k32.GetFileInformationByHandle(self.handle, self._ctypes.byref(info)):
            raise RegistryValidationError(
                f"Cannot inspect handle identity for {self.label} (winerr={self._ctypes.get_last_error()})"
            )
        reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x0400)
        dir_flag = getattr(stat, "FILE_ATTRIBUTE_DIRECTORY", 0x0010)
        if (info.dwFileAttributes & reparse_flag) or not (info.dwFileAttributes & dir_flag):
            raise RegistryValidationError(f"Reparse point or non-directory handle is forbidden for {self.label}")

        buf = self._ctypes.create_unicode_buffer(4096)
        length = self._k32.GetFinalPathNameByHandleW(self.handle, buf, len(buf), 0)
        if length == 0 or length >= len(buf):
            raise RegistryValidationError(
                f"Cannot resolve final handle path for {self.label} (winerr={self._ctypes.get_last_error()})"
            )
        raw_final = buf.value
        if raw_final.startswith("\\\\?\\UNC\\"):
            raw_final = "\\\\" + raw_final[8:]
        elif raw_final.startswith("\\\\?\\"):
            raw_final = raw_final[4:]
        file_index = (int(info.nFileIndexHigh) << 32) | int(info.nFileIndexLow)
        return (int(info.dwVolumeSerialNumber), file_index), Path(raw_final)

    def verify_matches(
        self,
        expected_path: Path | None = None,
        expected_id: tuple[int, int] | None = None,
        expected_parent: Path | None = None,
    ) -> None:
        if os.name != "nt":
            return
        check_path = expected_path or self.dir_path
        handle_id, cur_final = self._query_handle_state()
        if self.final_path is not None and _win_norm_path(cur_final) != _win_norm_path(self.final_path):
            raise RegistryValidationError(f"{self.label} moved or changed while handle was held")
        if expected_parent is not None and _win_norm_path(cur_final.parent) != _win_norm_path(expected_parent):
            raise RegistryValidationError(f"{self.label} escaped verified parent handle")
        cur_st = _lstat_no_symlink(check_path, self.label)
        target_id = expected_id if expected_id is not None else self.expected_id
        if target_id is not None and (cur_st.st_dev, cur_st.st_ino) != target_id:
            raise RegistryValidationError(f"{self.label} identity changed while handle was held")
        if self.dir_id is not None and handle_id != self.dir_id:
            raise RegistryValidationError(f"{self.label} OS handle file index mismatch")

    def close(self) -> None:
        if self.handle is not None and self._k32 is not None:
            try:
                self._k32.CloseHandle(self.handle)
            finally:
                self.handle = None

    def __enter__(self) -> "_WinAnchoredDirHandle":
        return self

    def __exit__(self, *args: object) -> None:
        self.close()


def _open_anchored_dir_fd(target_dir: Path, label: str, stop_at: Path | None = None) -> int:
    """Open a directory descriptor walking segment-by-segment from OS root (/) with O_NOFOLLOW and identity checks."""
    canonical_dir = _resolve_non_symlink_path(target_dir, label, must_exist=True, stop_at=stop_at)
    dir_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    cur_fd = -1
    next_fd = -1
    try:
        if stop_at is not None:
            canonical_stop = _resolve_non_symlink_path(stop_at, f"trusted root for {label}", must_exist=True)
            # Always walk from filesystem root (/) down to trusted_root before traversing to target_dir.
            cur_fd = _open_anchored_dir_fd(canonical_stop, f"trusted root for {label}", stop_at=None)
            rel_parts = canonical_dir.relative_to(canonical_stop).parts
        else:
            if canonical_dir.is_absolute():
                start = Path(canonical_dir.anchor)
                rel_parts = canonical_dir.parts[1:]
            else:
                start = Path.cwd()
                rel_parts = canonical_dir.parts
            start_st = _lstat_no_symlink(start, f"anchor start of {label}")
            cur_fd = os.open(start, dir_flags)
            cur_fst = os.fstat(cur_fd)
            if not stat.S_ISDIR(cur_fst.st_mode) or (cur_fst.st_dev, cur_fst.st_ino) != (start_st.st_dev, start_st.st_ino):
                raise RegistryValidationError(f"Directory anchor identity mismatch for {label}")

        for seg in rel_parts:
            if seg in {"", "."}:
                continue
            seg_lst = os.lstat(seg, dir_fd=cur_fd)
            if stat.S_ISLNK(seg_lst.st_mode) or not stat.S_ISDIR(seg_lst.st_mode):
                raise RegistryValidationError(f"Symbolic link or non-directory ancestor '{seg}' in {label}")
            next_fd = os.open(seg, dir_flags, dir_fd=cur_fd)
            next_fst = os.fstat(next_fd)
            if (next_fst.st_dev, next_fst.st_ino) != (seg_lst.st_dev, seg_lst.st_ino):
                raise RegistryValidationError(f"Directory identity changed during traversal for '{seg}' in {label}")
            os.close(cur_fd)
            cur_fd = next_fd
            next_fd = -1
        opened_fd = cur_fd
        cur_fd = -1
        return opened_fd
    except OSError as exc:
        raise RegistryValidationError(f"Cannot open anchored directory {label}: {exc}") from exc
    finally:
        for fd_to_close in (next_fd, cur_fd):
            if fd_to_close != -1:
                try:
                    os.close(fd_to_close)
                except OSError:
                    pass


def _open_anchored_regular_file(
    anchor_dir: Path,
    rel_parts: tuple[str, ...],
    label: str,
    trusted_root: Path | None = None,
) -> int:
    """Open a regular file anchored from trusted_root/anchor_dir without following symlinks on any component."""
    if not rel_parts or any(part in {"", ".", ".."} or "/" in part or "\\" in part for part in rel_parts):
        raise RegistryValidationError(f"Unanchored relative path for {label}")

    canonical_anchor = _resolve_non_symlink_path(anchor_dir, f"anchor for {label}", must_exist=True, stop_at=trusted_root)
    canonical_target = _resolve_non_symlink_path(canonical_anchor.joinpath(*rel_parts), label, must_exist=True, stop_at=canonical_anchor)
    st = _lstat_no_symlink(canonical_target, label)
    if not stat.S_ISREG(st.st_mode):
        raise RegistryValidationError(f"{label} is not a regular file: {canonical_target.name}")

    dir_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    file_flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)

    if os.open in os.supports_dir_fd:
        cur_fd = -1
        file_fd = -1
        try:
            cur_fd = _open_anchored_dir_fd(canonical_anchor, f"anchor for {label}", stop_at=trusted_root)
            for seg in rel_parts[:-1]:
                seg_lst = os.lstat(seg, dir_fd=cur_fd)
                if stat.S_ISLNK(seg_lst.st_mode) or not stat.S_ISDIR(seg_lst.st_mode):
                    raise RegistryValidationError(f"Symbolic link ancestor '{seg}' for {label}")
                next_fd = os.open(seg, dir_flags, dir_fd=cur_fd)
                next_fst = os.fstat(next_fd)
                if (next_fst.st_dev, next_fst.st_ino) != (seg_lst.st_dev, seg_lst.st_ino):
                    os.close(next_fd)
                    raise RegistryValidationError(f"Subdirectory identity changed for '{seg}' in {label}")
                os.close(cur_fd)
                cur_fd = next_fd
            leaf_st = os.lstat(rel_parts[-1], dir_fd=cur_fd)
            if stat.S_ISLNK(leaf_st.st_mode) or not stat.S_ISREG(leaf_st.st_mode):
                raise RegistryValidationError(f"Symbolic link or non-regular file for {label}")
            file_fd = os.open(rel_parts[-1], file_flags, dir_fd=cur_fd)
            fst = os.fstat(file_fd)
            if not stat.S_ISREG(fst.st_mode) or (fst.st_dev, fst.st_ino) != (leaf_st.st_dev, leaf_st.st_ino) or (fst.st_dev, fst.st_ino) != (st.st_dev, st.st_ino):
                raise RegistryValidationError(f"File identity changed during anchored open for {label}")
            opened_fd = file_fd
            file_fd = -1
            return opened_fd
        except OSError as exc:
            raise RegistryValidationError(f"Cannot open {label}: {exc}") from exc
        finally:
            if file_fd != -1:
                os.close(file_fd)
            if cur_fd != -1:
                os.close(cur_fd)

    file_fd = -1
    try:
        file_fd = os.open(canonical_target, file_flags)
        fst = os.fstat(file_fd)
        if not stat.S_ISREG(fst.st_mode) or (fst.st_dev, fst.st_ino) != (st.st_dev, st.st_ino):
            raise RegistryValidationError(f"File identity changed during open for {label}: {canonical_target.name}")
        _resolve_non_symlink_path(canonical_target, label, must_exist=True, stop_at=trusted_root)
        final_path = _win_final_path_for_fd(file_fd)
        if final_path is not None:
            if not final_path.is_relative_to(canonical_anchor) or final_path != canonical_target:
                raise RegistryValidationError(f"Opened handle escapes anchored directory for {label}")
        opened_fd = file_fd
        file_fd = -1
        return opened_fd
    except OSError as exc:
        raise RegistryValidationError(f"Cannot open {label}: {exc}") from exc
    finally:
        if file_fd != -1:
            os.close(file_fd)


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
    max_allowed_bytes: int = MAX_PACKAGE_FILE_BYTES,
    trusted_root: Path | None = None,
) -> tuple[int, str]:
    """Stream a single non-symlink artifact into dest_path within bounded limits and compute SHA-256."""
    ensure_no_symlinks_to(artifact_path, artifacts_dir)
    st = _lstat_no_symlink(artifact_path, f"artifact {artifact_path.name}")
    if not stat.S_ISREG(st.st_mode):
        raise RegistryValidationError(f"Artifact is not a regular file: {artifact_path.name}")
    effective_limit = min(MAX_PACKAGE_FILE_BYTES, max_allowed_bytes)
    if st.st_size <= 0 or st.st_size > effective_limit:
        if st.st_size > MAX_PACKAGE_FILE_BYTES or st.st_size <= 0:
            raise RegistryValidationError(
                f"Artifact size out of bounds (1..{MAX_PACKAGE_FILE_BYTES} bytes): {artifact_path.name}"
            )
        raise RegistryValidationError(
            f"Registry cumulative package size exceeds limit ({MAX_TOTAL_REGISTRY_BYTES} bytes)"
        )

    fd = _open_anchored_regular_file(
        artifacts_dir, (artifact_path.name,), f"artifact {artifact_path.name}", trusted_root=trusted_root
    )
    hasher = hashlib.sha256()
    total_read = 0
    out_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    try:
        fst = os.fstat(fd)
        if not stat.S_ISREG(fst.st_mode) or fst.st_size <= 0 or fst.st_size > effective_limit:
            raise RegistryValidationError(f"Artifact descriptor invalid or oversized: {artifact_path.name}")
        out_fd = os.open(dest_path, out_flags, 0o600)
        with os.fdopen(fd, "rb") as src_stream, os.fdopen(out_fd, "wb") as dst_stream:
            fd = -1
            while True:
                chunk = src_stream.read(READ_CHUNK_BYTES)
                if not chunk:
                    break
                total_read += len(chunk)
                if total_read > effective_limit:
                    raise RegistryValidationError(
                        f"Artifact stream exceeded maximum size ({effective_limit} bytes): {artifact_path.name}"
                    )
                hasher.update(chunk)
                dst_stream.write(chunk)
    except Exception:
        if dest_path.exists():
            dest_path.unlink(missing_ok=True)
        raise
    finally:
        if fd != -1:
            os.close(fd)

    if total_read <= 0:
        dest_path.unlink(missing_ok=True)
        raise RegistryValidationError(f"Artifact file is empty: {artifact_path.name}")
    return total_read, hasher.hexdigest().upper()


def read_bounded_package(
    artifact_path: Path,
    artifacts_dir: Path,
    trusted_root: Path | None = None,
) -> tuple[bytes, str]:
    """Read a non-symlink artifact once within MAX_PACKAGE_FILE_BYTES and compute SHA-256."""
    ensure_no_symlinks_to(artifact_path, artifacts_dir)
    st = _lstat_no_symlink(artifact_path, f"artifact {artifact_path.name}")
    if not stat.S_ISREG(st.st_mode):
        raise RegistryValidationError(f"Artifact is not a regular file: {artifact_path.name}")
    if st.st_size <= 0 or st.st_size > MAX_PACKAGE_FILE_BYTES:
        raise RegistryValidationError(
            f"Artifact size out of bounds (1..{MAX_PACKAGE_FILE_BYTES} bytes): {artifact_path.name}"
        )

    fd = _open_anchored_regular_file(
        artifacts_dir, (artifact_path.name,), f"artifact {artifact_path.name}", trusted_root=trusted_root
    )
    raw = _read_bounded_fd(fd, MAX_PACKAGE_FILE_BYTES, f"artifact {artifact_path.name}")
    return raw, hashlib.sha256(raw).hexdigest().upper()


def read_bounded_registry_json(
    registry_path: Path,
    trusted_root: Path | None = None,
) -> tuple[dict[str, object], Path, Path | None]:
    """Validate ancestor chain, open anchored regular file, enforce <= 2 MiB, and decode JSON object."""
    real_trusted = (
        _resolve_non_symlink_path(trusted_root, "trusted root", must_exist=True)
        if trusted_root is not None
        else None
    )
    resolved_registry = _resolve_non_symlink_path(
        registry_path, "registry.json", must_exist=True, stop_at=real_trusted
    )
    base_dir = resolved_registry.parent
    _lstat_no_symlink(base_dir, "registry directory")
    reg_fd = _open_anchored_regular_file(
        base_dir, (resolved_registry.name,), "registry.json", trusted_root=real_trusted
    )
    raw_reg_bytes = _read_bounded_fd(reg_fd, MAX_REGISTRY_JSON_BYTES, "registry.json")
    try:
        raw_doc = json.loads(raw_reg_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RegistryValidationError(f"Invalid UTF-8 JSON in {resolved_registry.name}: {exc}") from exc
    if not isinstance(raw_doc, dict):
        raise RegistryValidationError("Registry root must be a JSON object")
    return raw_doc, resolved_registry, real_trusted


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
                    if len(sf_aliases) > MAX_SIGNATURE_ALIASES:
                        raise RegistryValidationError(
                            f"Too many META-INF signature pairs (max {MAX_SIGNATURE_ALIASES}): {label}"
                        )
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
    """Run JDK JarFile(file, true) verifier inside an exclusive reclaimable temp dir."""
    if not VERIFIER_JAVA_PATH.is_file():
        raise RegistryValidationError(f"Missing JarSignerVerifier helper: {VERIFIER_JAVA_PATH}")
    java_bin = _find_jdk_tool("java")

    tmp_dir = Path(tempfile.mkdtemp(prefix="oriva-jar-verify-"))
    try:
        iso_temp_dir = tmp_dir / "iso"
        iso_temp_dir.mkdir(parents=True, exist_ok=True)
        if isinstance(pkg_source, Path):
            pkg_arg = pkg_source
        else:
            pkg_arg = tmp_dir / "package.jar"
            pkg_arg.write_bytes(pkg_source)
        proc = _run_tool(
            [
                java_bin,
                f"-Djava.io.tmpdir={iso_temp_dir}",
                str(VERIFIER_JAVA_PATH),
                str(pkg_arg),
                str(iso_temp_dir),
            ]
        )
    finally:
        cleanup_exc: OSError | None = None
        for attempt in range(4):
            try:
                if tmp_dir.exists():
                    shutil.rmtree(tmp_dir)
                cleanup_exc = None
                break
            except OSError as exc:
                cleanup_exc = exc
                time.sleep(0.05 * (attempt + 1))
        if cleanup_exc is not None:
            print(
                f"WARNING: Failed to clean verifier temp directory {tmp_dir}: {cleanup_exc}",
                file=sys.stderr,
            )

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
    """Validate manifest contributions aligned 1:1 with Android PluginContribution.kt."""
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

                # PluginFormField.options is List<PluginFormChoice> = emptyList() across ALL field kinds.
                raw_options: list[object] = []
                if "options" in field:
                    opts_val = field["options"]
                    if not isinstance(opts_val, list) or len(opts_val) > 64:
                        raise RegistryValidationError(f"Invalid options list in form field {fid!r}")
                    for opt in opts_val:
                        if (
                            not isinstance(opt, dict)
                            or not isinstance(opt.get("value"), str)
                            or not isinstance(opt.get("label"), str)
                        ):
                            raise RegistryValidationError(f"Invalid option entry in form field {fid!r}")
                    raw_options = opts_val

                fkind = field.get("kind")
                fval = field.get("value")
                if fkind == "switch":
                    if not isinstance(fval, bool):
                        raise RegistryValidationError(f"Invalid switch value in form field {fid!r}")
                elif fkind == "text":
                    if not isinstance(fval, str) or utf16_len(fval) > 4096:
                        raise RegistryValidationError(f"Invalid text value in form field {fid!r}")
                elif fkind == "choice":
                    if not isinstance(fval, str) or not raw_options:
                        raise RegistryValidationError(f"Invalid choice options in form field {fid!r}")
                    opt_values: set[str] = set()
                    for opt in raw_options:
                        assert isinstance(opt, dict)
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
            unavail = item.get("unavailableText")
            if unavail is not None:
                if not isinstance(unavail, str) or has_control_chars(unavail) or utf16_len(unavail) > 4096:
                    raise RegistryValidationError(f"Invalid StatusLine unavailableText: {unavail!r}")
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
    trusted_root: Path | None = None,
) -> ValidatedRegistry:
    """Validate registry.json, paths, bounded streaming packages, cryptographic signers, and metadata."""
    raw_doc, resolved_registry, real_trusted = read_bounded_registry_json(registry_path, trusted_root=trusted_root)
    base_dir = resolved_registry.parent
    artifacts_dir = base_dir / "artifacts"

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
        remaining_repo_budget = MAX_TOTAL_REGISTRY_BYTES - cumulative_package_bytes

        def _verify_staged(staged_pkg: Path) -> tuple[int, str, str]:
            pkg_size, actual_sha256 = stream_bounded_package_to_file(
                artifact_path,
                artifacts_dir,
                staged_pkg,
                max_allowed_bytes=remaining_repo_budget,
                trusted_root=real_trusted,
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


def audit_governance_diff(
    base_registry: Path,
    candidate_registry: Path,
    trusted_root: Path | None = None,
    candidate_root: Path | None = None,
) -> dict[str, object]:
    """Bounded, symlink-safe governance comparison of trust-anchor and source metadata keys."""
    gov_keys = ("trustedFingerprints", "sourceId", "sourceType", "catalogFormatVersion")
    base_present = os.path.lexists(base_registry)
    cand_present = os.path.lexists(candidate_registry)
    anchor_changed = False

    if cand_present:
        cand_doc, _, _ = read_bounded_registry_json(candidate_registry, trusted_root=candidate_root)
        if base_present:
            base_doc, _, _ = read_bounded_registry_json(base_registry, trusted_root=trusted_root)
            for key in gov_keys:
                if base_doc.get(key) != cand_doc.get(key):
                    anchor_changed = True
                    break
        else:
            anchor_changed = True
    elif base_present:
        anchor_changed = True

    return {
        "anchorChanged": anchor_changed,
        "basePresent": base_present,
        "candidatePresent": cand_present,
    }


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


def _validate_output_dir_anchor(
    output_dir: Path,
    protected_exact_or_ancestors: tuple[Path, ...],
    protected_subtrees: tuple[Path, ...],
    output_root: Path | None = None,
) -> tuple[Path, Path, tuple[int, int]]:
    """Ensure output_dir is strictly anchored inside its non-symlink parent and never overlaps inputs."""
    real_out_root = (
        _resolve_non_symlink_path(output_root, "output root", must_exist=True)
        if output_root is not None
        else None
    )
    canonical_out = _resolve_non_symlink_path(output_dir, "output directory", must_exist=False, stop_at=real_out_root)
    if not canonical_out.name or canonical_out.name in {".", ".."}:
        raise RegistryValidationError(f"Unsafe output directory name: {output_dir}")
    parent = output_dir.parent
    if not str(parent) or parent == output_dir:
        raise RegistryValidationError(f"Output directory must specify a non-root parent directory: {output_dir}")

    _resolve_non_symlink_path(parent, "output parent directory", must_exist=False, stop_at=real_out_root)
    parent.mkdir(parents=True, exist_ok=True)
    parent_st = _lstat_no_symlink(parent, "output parent directory")
    if not stat.S_ISDIR(parent_st.st_mode):
        raise RegistryValidationError(f"Output parent is not a directory: {parent}")

    real_parent = _resolve_non_symlink_path(parent, "output parent directory", must_exist=True, stop_at=real_out_root)
    target_out = real_parent / canonical_out.name

    if target_out == real_parent or target_out.parent != real_parent:
        raise RegistryValidationError(f"Output directory escapes specified parent: {output_dir}")
    if os.path.commonpath([str(real_parent), str(target_out)]) != str(real_parent):
        raise RegistryValidationError(f"Output directory escapes specified parent: {output_dir}")

    for protected in protected_exact_or_ancestors:
        real_prot = _resolve_non_symlink_path(protected, f"protected directory {protected.name}", must_exist=False)
        if target_out == real_prot or real_prot.is_relative_to(target_out):
            raise RegistryValidationError(
                f"Output directory {target_out} overlaps or encloses protected directory {real_prot}"
            )
    for subtree in protected_subtrees:
        real_sub = _resolve_non_symlink_path(subtree, f"protected subtree {subtree.name}", must_exist=False)
        if target_out == real_sub or real_sub.is_relative_to(target_out) or target_out.is_relative_to(real_sub):
            raise RegistryValidationError(
                f"Output directory {target_out} overlaps protected input subtree {real_sub}"
            )

    if os.path.lexists(target_out):
        st = _lstat_no_symlink(target_out, "output directory")
        if not stat.S_ISDIR(st.st_mode):
            raise RegistryValidationError(f"Output path exists and is not a directory: {target_out}")
        if Path(os.path.realpath(target_out)) != target_out:
            raise RegistryValidationError(f"Output directory resolves outside anchored parent: {target_out}")

    return real_parent, target_out, (parent_st.st_dev, parent_st.st_ino)


def _safe_rmtree_dir_fd(parent_fd: int, name: str, expected_dir_id: tuple[int, int] | None = None) -> None:
    dir_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    lst = os.lstat(name, dir_fd=parent_fd)
    if stat.S_ISLNK(lst.st_mode) or not stat.S_ISDIR(lst.st_mode):
        raise RegistryValidationError(f"Output path is not a safe directory for --clean: {name}")
    if expected_dir_id is not None and (lst.st_dev, lst.st_ino) != expected_dir_id:
        raise RegistryValidationError("Output directory identity changed before --clean")
    child_fd = os.open(name, dir_flags, dir_fd=parent_fd)
    try:
        fst = os.fstat(child_fd)
        if (fst.st_dev, fst.st_ino) != (lst.st_dev, lst.st_ino):
            raise RegistryValidationError(f"Directory identity changed during --clean open: {name}")
        for entry in os.listdir(child_fd):
            st = os.lstat(entry, dir_fd=child_fd)
            if stat.S_ISLNK(st.st_mode):
                raise RegistryValidationError(f"Symbolic link inside output tree forbidden during --clean: {entry}")
            if stat.S_ISDIR(st.st_mode):
                _safe_rmtree_dir_fd(child_fd, entry, (st.st_dev, st.st_ino))
            else:
                os.unlink(entry, dir_fd=child_fd)
    finally:
        os.close(child_fd)
    os.rmdir(name, dir_fd=parent_fd)


def _safe_rmtree_windows_anchored(
    dir_path: Path,
    expected_dir_id: tuple[int, int],
    parent_handle: _WinAnchoredDirHandle | None = None,
) -> None:
    if parent_handle is not None:
        parent_handle.verify_matches()
    with _WinAnchoredDirHandle(
        dir_path,
        f"output directory {dir_path.name}",
        expected_id=expected_dir_id,
        allow_delete_share=False,
    ) as dir_handle:
        expected_parent = parent_handle.final_path if parent_handle is not None else None
        dir_handle.verify_matches(dir_path, expected_id=expected_dir_id, expected_parent=expected_parent)
        with os.scandir(dir_path) as it:
            entries = list(it)
        for entry in entries:
            if parent_handle is not None:
                parent_handle.verify_matches()
            dir_handle.verify_matches(dir_path, expected_id=expected_dir_id, expected_parent=expected_parent)
            child_path = dir_path / entry.name
            child_st = _lstat_no_symlink(child_path, f"output entry {entry.name}")
            if stat.S_ISDIR(child_st.st_mode):
                _safe_rmtree_windows_anchored(child_path, (child_st.st_dev, child_st.st_ino), parent_handle=dir_handle)
            else:
                os.unlink(child_path)
        if parent_handle is not None:
            parent_handle.verify_matches()
        dir_handle.verify_matches(dir_path, expected_id=expected_dir_id, expected_parent=expected_parent)

    if parent_handle is not None:
        parent_handle.verify_matches()
    st_after = _lstat_no_symlink(dir_path, f"output directory {dir_path.name}")
    if (st_after.st_dev, st_after.st_ino) != expected_dir_id:
        raise RegistryValidationError(f"Output directory identity changed before rmdir: {dir_path.name}")
    os.rmdir(dir_path)


def _safe_rmtree_anchored(
    target_out: Path,
    real_parent: Path,
    expected_parent_id: tuple[int, int],
    parent_handle: _WinAnchoredDirHandle | None = None,
) -> None:
    cur_parent_st = _lstat_no_symlink(real_parent, "output parent directory")
    if (cur_parent_st.st_dev, cur_parent_st.st_ino) != expected_parent_id:
        raise RegistryValidationError("Output parent directory identity changed before --clean")
    out_st = _lstat_no_symlink(target_out, "output directory")
    if not stat.S_ISDIR(out_st.st_mode):
        raise RegistryValidationError(f"Output path is not a directory for --clean: {target_out}")

    if os.open in os.supports_dir_fd:
        parent_fd = _open_anchored_dir_fd(real_parent, "output parent directory")
        try:
            fst = os.fstat(parent_fd)
            if (fst.st_dev, fst.st_ino) != expected_parent_id:
                raise RegistryValidationError("Output parent descriptor mismatch before --clean")
            _safe_rmtree_dir_fd(parent_fd, target_out.name, (out_st.st_dev, out_st.st_ino))
            return
        finally:
            os.close(parent_fd)

    if parent_handle is not None:
        parent_handle.verify_matches(real_parent, expected_id=expected_parent_id)
        _safe_rmtree_windows_anchored(target_out, (out_st.st_dev, out_st.st_ino), parent_handle=parent_handle)
        return

    with _WinAnchoredDirHandle(real_parent, "output parent directory", expected_id=expected_parent_id) as owned_parent:
        _safe_rmtree_windows_anchored(target_out, (out_st.st_dev, out_st.st_ino), parent_handle=owned_parent)


def _write_anchored_output_file_fd(
    out_fd: int,
    rel: Path,
    src_file: Path,
    expected_out_id: tuple[int, int],
) -> None:
    """Write src_file using fresh temp file inside verified out_fd and atomic os.replace (never O_TRUNC)."""
    out_fst = os.fstat(out_fd)
    if (out_fst.st_dev, out_fst.st_ino) != expected_out_id:
        raise RegistryValidationError("Output directory descriptor mismatch during publish")
    dir_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    excl_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    cur_fd = os.dup(out_fd)
    tmp_name = f".{rel.parts[-1]}.{os.getpid()}.stage.tmp"
    created_tmp = False
    try:
        for seg in rel.parts[:-1]:
            try:
                seg_lst = os.lstat(seg, dir_fd=cur_fd)
                if stat.S_ISLNK(seg_lst.st_mode) or not stat.S_ISDIR(seg_lst.st_mode):
                    raise RegistryValidationError(f"Output subdirectory path is unsafe: {seg}")
            except FileNotFoundError:
                os.mkdir(seg, 0o755, dir_fd=cur_fd)
                seg_lst = os.lstat(seg, dir_fd=cur_fd)
            next_fd = os.open(seg, dir_flags, dir_fd=cur_fd)
            next_fst = os.fstat(next_fd)
            if (next_fst.st_dev, next_fst.st_ino) != (seg_lst.st_dev, seg_lst.st_ino):
                os.close(next_fd)
                raise RegistryValidationError(f"Output subdirectory identity changed: {seg}")
            os.close(cur_fd)
            cur_fd = next_fd

        leaf = rel.parts[-1]
        try:
            leaf_st = os.lstat(leaf, dir_fd=cur_fd)
            if stat.S_ISLNK(leaf_st.st_mode):
                raise RegistryValidationError(f"Symbolic link is forbidden at output target: {leaf}")
            if not stat.S_ISREG(leaf_st.st_mode):
                raise RegistryValidationError(f"Existing output target is not a regular file: {leaf}")
            if leaf_st.st_nlink > 1:
                raise RegistryValidationError(f"Hard-linked output target is forbidden (st_nlink={leaf_st.st_nlink}): {leaf}")
        except FileNotFoundError:
            pass

        tmp_fd = os.open(tmp_name, excl_flags, 0o644, dir_fd=cur_fd)
        created_tmp = True
        with src_file.open("rb") as fin, os.fdopen(tmp_fd, "wb") as fout:
            while True:
                chunk = fin.read(READ_CHUNK_BYTES)
                if not chunk:
                    break
                fout.write(chunk)
        os.replace(tmp_name, leaf, src_dir_fd=cur_fd, dst_dir_fd=cur_fd)
        created_tmp = False
    finally:
        if created_tmp:
            try:
                os.unlink(tmp_name, dir_fd=cur_fd)
            except OSError:
                pass
        os.close(cur_fd)


def _write_anchored_output_file(
    target_out: Path,
    rel: Path,
    src_file: Path,
    expected_out_id: tuple[int, int],
    out_handle: _WinAnchoredDirHandle | None = None,
) -> None:
    """Write src_file into target_out / rel via held dir handles + new temp file + atomic replace (never O_TRUNC)."""
    cur_out_st = _lstat_no_symlink(target_out, "output directory")
    if (cur_out_st.st_dev, cur_out_st.st_ino) != expected_out_id:
        raise RegistryValidationError("Output directory identity changed during publish")

    if os.open in os.supports_dir_fd:
        out_fd = _open_anchored_dir_fd(target_out, "output directory")
        try:
            _write_anchored_output_file_fd(out_fd, rel, src_file, expected_out_id)
        finally:
            os.close(out_fd)
        return

    owned_out = None
    try:
        if out_handle is None:
            owned_out = _WinAnchoredDirHandle(target_out, "output directory", expected_id=expected_out_id)
            active_out = owned_out
        else:
            active_out = out_handle
        active_out.verify_matches(target_out, expected_id=expected_out_id)

        held_subdirs: list[_WinAnchoredDirHandle] = []
        try:
            current = target_out
            parent_final = active_out.final_path
            for seg in rel.parts[:-1]:
                current = current / seg
                if os.path.lexists(current):
                    st = _lstat_no_symlink(current, f"output subdirectory {seg}")
                    if not stat.S_ISDIR(st.st_mode):
                        raise RegistryValidationError(f"Output subdirectory path is not a directory: {current}")
                else:
                    os.mkdir(current)
                    st = _lstat_no_symlink(current, f"output subdirectory {seg}")
                sub_h = _WinAnchoredDirHandle(current, f"output subdirectory {seg}", expected_id=(st.st_dev, st.st_ino))
                held_subdirs.append(sub_h)
                sub_h.verify_matches(current, expected_id=(st.st_dev, st.st_ino), expected_parent=parent_final)
                parent_final = sub_h.final_path

            leaf_parent_handle = held_subdirs[-1] if held_subdirs else active_out
            dst_path = target_out / rel
            if os.path.lexists(dst_path):
                st = _lstat_no_symlink(dst_path, f"existing output file {dst_path.name}")
                if not stat.S_ISREG(st.st_mode):
                    raise RegistryValidationError(f"Existing output target is not a regular file: {dst_path}")
                if st.st_nlink > 1:
                    raise RegistryValidationError(
                        f"Hard-linked output target is forbidden (st_nlink={st.st_nlink}): {dst_path.name}"
                    )

            excl_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
            tmp_path = dst_path.with_name(f".{dst_path.name}.{os.getpid()}.stage.tmp")
            tmp_fd = -1
            created_tmp = False
            try:
                tmp_fd = os.open(tmp_path, excl_flags, 0o644)
                created_tmp = True
                final_path = _win_final_path_for_fd(tmp_fd)
                if final_path is not None and leaf_parent_handle.final_path is not None:
                    if _win_norm_path(final_path.parent) != _win_norm_path(leaf_parent_handle.final_path):
                        raise RegistryValidationError(
                            f"Output temp file handle escaped anchored directory: {dst_path.name}"
                        )
                with src_file.open("rb") as fin, os.fdopen(tmp_fd, "wb") as fout:
                    tmp_fd = -1
                    while True:
                        chunk = fin.read(READ_CHUNK_BYTES)
                        if not chunk:
                            break
                        fout.write(chunk)
                active_out.verify_matches(target_out, expected_id=expected_out_id)
                leaf_parent_handle.verify_matches(current)
                if os.path.lexists(dst_path):
                    st_after = _lstat_no_symlink(dst_path, f"existing output file {dst_path.name}")
                    if not stat.S_ISREG(st_after.st_mode) or st_after.st_nlink > 1:
                        raise RegistryValidationError(f"Unsafe output target before atomic replace: {dst_path.name}")
                os.replace(tmp_path, dst_path)
                created_tmp = False
            finally:
                if tmp_fd != -1:
                    os.close(tmp_fd)
                if created_tmp and os.path.lexists(tmp_path):
                    tmp_path.unlink(missing_ok=True)
        finally:
            for sub_h in reversed(held_subdirs):
                sub_h.close()
    finally:
        if owned_out is not None:
            owned_out.close()


def build_static_site(
    registry_path: Path,
    output_dir: Path,
    web_dir: Path | None = None,
    clean: bool = False,
    trusted_root: Path | None = None,
    output_root: Path | None = None,
) -> ValidatedRegistry:
    """Validate registry fail-closed via streaming staging, then emit deterministic static site."""
    real_trusted = (
        _resolve_non_symlink_path(trusted_root, "trusted root", must_exist=True)
        if trusted_root is not None
        else None
    )
    resolved_registry = _resolve_non_symlink_path(registry_path, "registry.json", must_exist=True, stop_at=real_trusted)
    resolved_registry_dir = resolved_registry.parent
    resolved_artifacts_dir = resolved_registry_dir / "artifacts"
    resolved_tests_dir = resolved_registry_dir / "tests"

    raw_web = web_dir or (Path(__file__).resolve().parent / "web")
    resolved_web = _resolve_non_symlink_path(raw_web, "web directory", must_exist=True)
    required_web_files = ("index.html", "style.css", "app.js")
    for asset_name in required_web_files:
        asset_path = resolved_web / asset_name
        ensure_no_symlinks_to(asset_path, resolved_web)
        if not asset_path.is_file():
            raise RegistryValidationError(f"Missing web asset: {asset_path}")

    protected_exact_or_ancestors = (resolved_registry_dir, Path(__file__).resolve().parent)
    protected_subtrees = (resolved_artifacts_dir, resolved_web, resolved_tests_dir)
    real_parent, target_out, parent_id = _validate_output_dir_anchor(
        output_dir, protected_exact_or_ancestors, protected_subtrees, output_root=output_root
    )

    with tempfile.TemporaryDirectory(prefix="oriva-site-stage-") as stage_tmp:
        stage_dir = Path(stage_tmp)
        stage_artifacts = stage_dir / "artifacts"
        stage_artifacts.mkdir(parents=True, exist_ok=True)

        validated = validate_registry(
            resolved_registry, stage_artifacts_dir=stage_artifacts, trusted_root=real_trusted
        )

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

        real_parent_after, target_out_after, parent_id_after = _validate_output_dir_anchor(
            output_dir, protected_exact_or_ancestors, protected_subtrees, output_root=output_root
        )
        if real_parent_after != real_parent or target_out_after != target_out or parent_id_after != parent_id:
            raise RegistryValidationError("Output directory anchor changed during build")

        staged_files = [src_file for src_file in sorted(stage_dir.rglob("*")) if not src_file.is_dir()]
        if os.open in os.supports_dir_fd:
            dir_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
            parent_fd = _open_anchored_dir_fd(real_parent, "output parent directory")
            out_fd = -1
            try:
                pfst = os.fstat(parent_fd)
                if (pfst.st_dev, pfst.st_ino) != parent_id:
                    raise RegistryValidationError("Output parent descriptor changed before publish")
                if clean and os.path.lexists(target_out):
                    cur_out_st = os.lstat(target_out.name, dir_fd=parent_fd)
                    _safe_rmtree_dir_fd(parent_fd, target_out.name, (cur_out_st.st_dev, cur_out_st.st_ino))
                try:
                    cur_out_st = os.lstat(target_out.name, dir_fd=parent_fd)
                    if stat.S_ISLNK(cur_out_st.st_mode) or not stat.S_ISDIR(cur_out_st.st_mode):
                        raise RegistryValidationError(f"Output path exists and is not a directory: {target_out}")
                except FileNotFoundError:
                    os.mkdir(target_out.name, 0o755, dir_fd=parent_fd)
                    cur_out_st = os.lstat(target_out.name, dir_fd=parent_fd)
                out_fd = os.open(target_out.name, dir_flags, dir_fd=parent_fd)
                out_fst = os.fstat(out_fd)
                if (out_fst.st_dev, out_fst.st_ino) != (cur_out_st.st_dev, cur_out_st.st_ino):
                    raise RegistryValidationError("Output directory identity mismatch on open")
                out_id = (out_fst.st_dev, out_fst.st_ino)
                for src_file in staged_files:
                    _write_anchored_output_file_fd(out_fd, src_file.relative_to(stage_dir), src_file, out_id)
            finally:
                if out_fd != -1:
                    os.close(out_fd)
                os.close(parent_fd)
        else:
            with _WinAnchoredDirHandle(real_parent, "output parent directory", expected_id=parent_id) as parent_handle:
                if clean and os.path.lexists(target_out):
                    _safe_rmtree_anchored(target_out, real_parent, parent_id, parent_handle=parent_handle)
                parent_handle.verify_matches(real_parent, expected_id=parent_id)

                if os.path.lexists(target_out):
                    out_st = _lstat_no_symlink(target_out, "output directory")
                    if not stat.S_ISDIR(out_st.st_mode):
                        raise RegistryValidationError(f"Output path exists and is not a directory: {target_out}")
                else:
                    os.mkdir(target_out)
                    out_st = _lstat_no_symlink(target_out, "output directory")

                out_id = (out_st.st_dev, out_st.st_ino)
                with _WinAnchoredDirHandle(target_out, "output directory", expected_id=out_id) as out_handle:
                    out_handle.verify_matches(target_out, expected_id=out_id, expected_parent=parent_handle.final_path)
                    for src_file in staged_files:
                        parent_handle.verify_matches(real_parent, expected_id=parent_id)
                        out_handle.verify_matches(target_out, expected_id=out_id, expected_parent=parent_handle.final_path)
                        rel = src_file.relative_to(stage_dir)
                        _write_anchored_output_file(target_out, rel, src_file, out_id, out_handle=out_handle)

    return validated


def inspect_package_certificates(pkg_path: Path, trusted_root: Path | None = None) -> dict[str, object]:
    """Diagnostic-only inspection of package SHA-256 and CodeSigner certificates (grants NO trust)."""
    real_trusted = (
        _resolve_non_symlink_path(trusted_root, "trusted root", must_exist=True)
        if trusted_root is not None
        else None
    )
    resolved = _resolve_non_symlink_path(pkg_path, f"package {pkg_path.name}", must_exist=True, stop_at=real_trusted)
    with tempfile.TemporaryDirectory(prefix="oriva-inspect-stage-") as tmp_dir:
        staged = Path(tmp_dir) / resolved.name
        _, pkg_sha256 = stream_bounded_package_to_file(
            resolved, resolved.parent, staged, trusted_root=real_trusted
        )
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


PLUGIN_REGISTRY_WORKFLOW_PATH = ".github/workflows/plugin-registry.yml"
PLUGIN_REGISTRY_PREFIX = "deploy/plugin-registry/"
PRIVILEGED_EXACT_PATHS = frozenset({
    PLUGIN_REGISTRY_WORKFLOW_PATH,
    "deploy/plugin-registry/registry_tool.py",
    "deploy/plugin-registry/JarSignerVerifier.java",
})
PRIVILEGED_DIR_PREFIXES = (
    "deploy/plugin-registry/web/",
    "deploy/plugin-registry/tests/",
)


def parse_nul_diff_paths(raw_diff: bytes) -> list[str]:
    """Decode NUL-delimited `git diff -z --no-renames --name-only` output without C-unescaping."""
    return [os.fsdecode(entry) for entry in raw_diff.split(b"\x00") if entry]


def classify_pr_changed_paths(changed_paths: list[str] | tuple[str, ...]) -> dict[str, object]:
    """Classify PR changed paths consistently for both scope gate and privileged governance alerts."""
    touches_registry = False
    privileged_hits: list[str] = []
    for rel_path in changed_paths:
        if rel_path == PLUGIN_REGISTRY_WORKFLOW_PATH or rel_path.startswith(PLUGIN_REGISTRY_PREFIX):
            touches_registry = True
        if rel_path in PRIVILEGED_EXACT_PATHS or rel_path.startswith(PRIVILEGED_DIR_PREFIXES):
            privileged_hits.append(rel_path)
    return {
        "changedPaths": list(changed_paths),
        "touchesRegistry": touches_registry,
        "privilegedHits": privileged_hits,
    }


def _build_parser() -> argparse.ArgumentParser:
    default_registry = Path(__file__).resolve().parent / "registry.json"
    parser = argparse.ArgumentParser(description="ORIVA open plugin registry validator & static site builder.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    val_parser = subparsers.add_parser("validate", help="Validate registry.json and all signed plugin packages.")
    val_parser.add_argument("--registry", type=Path, default=default_registry, help="Path to registry.json")
    val_parser.add_argument("--trusted-root", type=Path, default=None, help="Explicit trusted ancestor root for input")
    val_parser.add_argument("--json", action="store_true", help="Output validation summary as JSON")

    build_parser = subparsers.add_parser("build", help="Validate and deterministically build static site output.")
    build_parser.add_argument("--registry", type=Path, default=default_registry, help="Path to registry.json")
    build_parser.add_argument("--trusted-root", type=Path, default=None, help="Explicit trusted ancestor root for input")
    build_parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "dist",
        help="Destination directory for static site build",
    )
    build_parser.add_argument("--output-root", type=Path, default=None, help="Explicit trusted ancestor root for output")
    build_parser.add_argument("--web-dir", type=Path, default=None, help="Optional override for web assets directory")
    build_parser.add_argument("--clean", action="store_true", help="Remove existing output directory before writing")

    inspect_parser = subparsers.add_parser(
        "inspect-cert",
        help="Diagnostic-only: print per-entry and common leaf signer SHA-256/SPKI fingerprints (grants no trust).",
    )
    inspect_parser.add_argument("--package", type=Path, required=True, help="Path to .plp or .pljs package")
    inspect_parser.add_argument("--trusted-root", type=Path, default=None, help="Explicit trusted ancestor root")

    gov_parser = subparsers.add_parser(
        "audit-governance",
        help="Safely inspect base vs candidate registry.json governance metadata with bounded anchored reads.",
    )
    gov_parser.add_argument("--base-registry", type=Path, required=True, help="Path to base registry.json")
    gov_parser.add_argument("--candidate-registry", type=Path, required=True, help="Path to candidate registry.json")
    gov_parser.add_argument("--trusted-root", type=Path, default=None, help="Explicit trusted ancestor root for base")
    gov_parser.add_argument("--candidate-root", type=Path, default=None, help="Explicit trusted ancestor root for candidate")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            validated = validate_registry(args.registry, trusted_root=args.trusted_root)
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
            validated = build_static_site(
                args.registry,
                args.output,
                web_dir=args.web_dir,
                clean=args.clean,
                trusted_root=args.trusted_root,
                output_root=args.output_root,
            )
            print(
                f"BUILT {args.output} ({len(validated.plugins)} plugin(s), sourceId={validated.source_id})"
            )
            return 0

        if args.command == "inspect-cert":
            info = inspect_package_certificates(args.package, trusted_root=args.trusted_root)
            print(json.dumps(info, ensure_ascii=False, indent=2))
            return 0

        if args.command == "audit-governance":
            result = audit_governance_diff(
                args.base_registry,
                args.candidate_registry,
                trusted_root=args.trusted_root,
                candidate_root=args.candidate_root,
            )
            print(json.dumps(result, ensure_ascii=False))
            return 0

    except RegistryValidationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 1


if __name__ == "__main__":
    sys.exit(main())
