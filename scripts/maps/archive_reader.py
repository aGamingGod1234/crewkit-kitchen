"""Bounded ZIP inspection and extraction for offline map research."""

from __future__ import annotations

import os
import re
import shutil
import stat
import tempfile
import unicodedata
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import BinaryIO


class ArchiveSafetyError(ValueError):
    """Raised before unsafe or excessive archive content is written."""


@dataclass(frozen=True)
class ArchiveLimits:
    max_members: int = 25_000
    max_member_size: int = 512 * 1024 * 1024
    max_expanded_size: int = 4 * 1024 * 1024 * 1024
    max_compression_ratio: float = 200.0

    def __post_init__(self) -> None:
        if self.max_members < 1:
            raise ValueError("max_members must be positive")
        if self.max_member_size < 1:
            raise ValueError("max_member_size must be positive")
        if self.max_expanded_size < 1:
            raise ValueError("max_expanded_size must be positive")
        if self.max_compression_ratio < 1:
            raise ValueError("max_compression_ratio must be at least 1")


@dataclass(frozen=True)
class _ApprovedMember:
    info: zipfile.ZipInfo
    relative_path: Path
    is_directory: bool


_DRIVE_PATH = re.compile(r"^[A-Za-z]:")
_WINDOWS_DEVICE = re.compile(
    r"^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\..*)?$",
    re.IGNORECASE,
)
_WINDOWS_REPARSE_POINT = 0x0400
_COPY_CHUNK_SIZE = 1024 * 1024


def _has_reparse_attribute(path: Path) -> bool:
    try:
        attributes = path.lstat().st_file_attributes
    except (AttributeError, FileNotFoundError):
        return False
    return bool(attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT)


def _reject_linked_path(path: Path) -> None:
    current = path
    while current != current.parent:
        if current.exists() or current.is_symlink():
            if current.is_symlink() or _has_reparse_attribute(current):
                raise ArchiveSafetyError(f"destination contains a link or reparse point: {current}")
        current = current.parent


def _member_path(info: zipfile.ZipInfo) -> tuple[Path, str]:
    name = unicodedata.normalize("NFC", info.filename.replace("\\", "/"))
    if not name or "\x00" in name:
        raise ArchiveSafetyError("archive member has an empty or invalid path")
    if name.startswith("/") or name.startswith("//"):
        raise ArchiveSafetyError(f"archive member uses a rooted path: {info.filename!r}")
    if _DRIVE_PATH.match(name):
        raise ArchiveSafetyError(f"archive member uses a drive-qualified path: {info.filename!r}")

    pure_path = PurePosixPath(name)
    if not pure_path.parts or any(part in ("", ".", "..") for part in pure_path.parts):
        raise ArchiveSafetyError(f"archive member uses path traversal: {info.filename!r}")
    if any(":" in part for part in pure_path.parts):
        raise ArchiveSafetyError(f"archive member uses a drive or alternate-stream path: {info.filename!r}")
    if any(part.endswith((" ", ".")) or _WINDOWS_DEVICE.match(part) for part in pure_path.parts):
        raise ArchiveSafetyError(f"archive member uses an invalid Windows path: {info.filename!r}")

    relative_path = Path(*pure_path.parts)
    normalized_key = "/".join(pure_path.parts).casefold()
    return relative_path, normalized_key


def _member_kind(info: zipfile.ZipInfo) -> tuple[bool, bool]:
    unix_mode = info.external_attr >> 16
    file_type = stat.S_IFMT(unix_mode)
    is_directory = info.is_dir() or file_type == stat.S_IFDIR
    is_link = file_type == stat.S_IFLNK
    if file_type not in (0, stat.S_IFREG, stat.S_IFDIR, stat.S_IFLNK):
        is_link = True
    if info.create_system == 0 and info.external_attr & _WINDOWS_REPARSE_POINT:
        is_link = True
    return is_directory, is_link


def _approve_members(archive: zipfile.ZipFile, limits: ArchiveLimits) -> list[_ApprovedMember]:
    members = archive.infolist()
    if len(members) > limits.max_members:
        raise ArchiveSafetyError(
            f"archive member count {len(members)} exceeds limit {limits.max_members}"
        )

    approved: list[_ApprovedMember] = []
    seen: dict[str, bool] = {}
    expanded_size = 0
    for info in members:
        relative_path, normalized_key = _member_path(info)
        is_directory, is_link = _member_kind(info)
        if is_link:
            raise ArchiveSafetyError(f"archive member is a link or reparse-like entry: {info.filename!r}")
        if normalized_key in seen:
            raise ArchiveSafetyError(f"archive contains duplicate output path: {info.filename!r}")

        parent_key = normalized_key
        while "/" in parent_key:
            parent_key = parent_key.rsplit("/", 1)[0]
            if parent_key in seen and not seen[parent_key]:
                raise ArchiveSafetyError(f"archive output path has a file parent: {info.filename!r}")
        if not is_directory and any(key.startswith(normalized_key + "/") for key in seen):
            raise ArchiveSafetyError(f"archive output path conflicts with a parent directory: {info.filename!r}")

        if info.flag_bits & 0x1:
            raise ArchiveSafetyError(f"archive member is encrypted: {info.filename!r}")
        if info.file_size > limits.max_member_size:
            raise ArchiveSafetyError(
                f"archive member size {info.file_size} exceeds limit {limits.max_member_size}: {info.filename!r}"
            )
        expanded_size += info.file_size
        if expanded_size > limits.max_expanded_size:
            raise ArchiveSafetyError(
                f"archive expanded size {expanded_size} exceeds limit {limits.max_expanded_size}"
            )
        if not is_directory and info.file_size:
            ratio = info.file_size / max(info.compress_size, 1)
            if ratio > limits.max_compression_ratio:
                raise ArchiveSafetyError(
                    f"archive member compression ratio {ratio:.1f} exceeds limit "
                    f"{limits.max_compression_ratio:.1f}: {info.filename!r}"
                )

        seen[normalized_key] = is_directory
        approved.append(_ApprovedMember(info, relative_path, is_directory))
    return approved


def safe_extract(
    archive: str | os.PathLike[str] | BinaryIO,
    destination: str | os.PathLike[str],
    limits: ArchiveLimits,
) -> list[Path]:
    """Extract a ZIP atomically after validating every member and size budget.

    ``destination`` must not already exist. The caller owns policy about where
    that resolved directory lives; this function guarantees that no member can
    escape it or turn into a link-like filesystem object.
    """

    requested_destination = Path(destination)
    _reject_linked_path(requested_destination)
    if requested_destination.exists():
        raise ArchiveSafetyError(f"destination already exists: {requested_destination}")

    resolved_destination = requested_destination.resolve(strict=False)
    resolved_parent = resolved_destination.parent
    resolved_parent.mkdir(parents=True, exist_ok=True)
    temporary_root = Path(tempfile.mkdtemp(prefix=".map-extract-", dir=resolved_parent))

    try:
        with zipfile.ZipFile(archive, "r") as source:
            approved = _approve_members(source, limits)
            for member in approved:
                output_path = temporary_root / member.relative_path
                if member.is_directory:
                    output_path.mkdir(parents=True, exist_ok=True)
                    continue

                output_path.parent.mkdir(parents=True, exist_ok=True)
                written = 0
                with source.open(member.info, "r") as input_stream, output_path.open("xb") as output_stream:
                    while chunk := input_stream.read(_COPY_CHUNK_SIZE):
                        written += len(chunk)
                        if written > member.info.file_size or written > limits.max_member_size:
                            raise ArchiveSafetyError(
                                f"archive member expanded beyond declared size: {member.info.filename!r}"
                            )
                        output_stream.write(chunk)
                if written != member.info.file_size:
                    raise ArchiveSafetyError(
                        f"archive member size differs from declaration: {member.info.filename!r}"
                    )

        temporary_root.replace(resolved_destination)
        return [resolved_destination / member.relative_path for member in approved if not member.is_directory]
    except (ArchiveSafetyError, zipfile.BadZipFile, RuntimeError, OSError) as error:
        if isinstance(error, ArchiveSafetyError):
            raise
        raise ArchiveSafetyError(f"archive extraction failed safely: {error}") from error
    finally:
        if temporary_root.exists():
            shutil.rmtree(temporary_root)
