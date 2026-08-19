from __future__ import annotations

import hashlib
import http.server
import io
import json
import shutil
import stat
import subprocess
import tempfile
import threading
import unittest
import zipfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from scripts.maps.archive_reader import ArchiveLimits, ArchiveSafetyError, safe_extract


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]

class LocalHttpSource:
    def __init__(self, payload: bytes) -> None:
        self.payload = payload
        self.requests: dict[str, int] = {}

        source = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                source.requests[self.path] = source.requests.get(self.path, 0) + 1
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", source.url("/target"))
                    self.end_headers()
                    return
                if self.path in ("/archive", "/target"):
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(source.payload)))
                    self.end_headers()
                    self.wfile.write(source.payload)
                    return
                self.send_error(404)

            def log_message(self, format: str, *args: object) -> None:
                pass

        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def url(self, path: str) -> str:
        return f"http://127.0.0.1:{self._server.server_port}{path}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)

    def __enter__(self) -> "LocalHttpSource":
        return self

    def __exit__(self, *args: object) -> None:
        self.close()


@contextmanager
def isolated_fetch_repository(approved_url: str, payload: bytes) -> Iterator[tuple[Path, Path]]:
    with tempfile.TemporaryDirectory() as temporary_directory:
        root = Path(temporary_directory)
        script_directory = root / "scripts" / "maps"
        script_directory.mkdir(parents=True)
        script_path = script_directory / "fetch_map_source.ps1"
        shutil.copy2(REPOSITORY_ROOT / "scripts" / "maps" / "fetch_map_source.ps1", script_path)

        ledger = {
            "schemaVersion": 1,
            "researchRoot": "runtime/map-research",
            "sources": {
                "local-test": {
                    "licenseStatus": "verified",
                    "bundleEligible": True,
                    "archive": {
                        "url": approved_url,
                        "filename": "fixture.zip",
                        "size": len(payload),
                        "sha1": hashlib.sha1(payload).hexdigest(),
                        "sha512": hashlib.sha512(payload).hexdigest(),
                        "sha256": None,
                    },
                }
            },
        }
        ledger_path = root / "maps" / "source-ledger.json"
        ledger_path.parent.mkdir(parents=True)
        ledger_path.write_text(json.dumps(ledger), encoding="utf-8")
        yield root, script_path


def zip_fixture(entries: list[tuple[str, bytes, int | None]]) -> io.BytesIO:
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name, content, external_attr in entries:
            member = zipfile.ZipInfo(name)
            member.compress_type = zipfile.ZIP_DEFLATED
            if external_attr is not None:
                member.create_system = 3
                member.external_attr = external_attr
            output.writestr(member, content)
    archive.seek(0)
    return archive


class SafeExtractTests(unittest.TestCase):
    def test_extracts_regular_files_beneath_destination(self) -> None:
        archive = zip_fixture(
            [
                ("world/level.dat", b"level", None),
                ("world/region/r.0.0.mca", b"region", None),
            ]
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            destination = Path(temporary_directory) / "research"
            extracted = safe_extract(archive, destination, ArchiveLimits())

            self.assertEqual(
                [destination / "world" / "level.dat", destination / "world" / "region" / "r.0.0.mca"],
                extracted,
            )
            self.assertEqual(b"level", (destination / "world" / "level.dat").read_bytes())
            self.assertEqual(b"region", (destination / "world" / "region" / "r.0.0.mca").read_bytes())

    def test_accepts_explicit_directory_after_its_child(self) -> None:
        archive = zip_fixture(
            [
                ("world/file.txt", b"safe", None),
                ("world/", b"", (stat.S_IFDIR | 0o755) << 16),
            ]
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            destination = Path(temporary_directory) / "research"
            extracted = safe_extract(archive, destination, ArchiveLimits())

            self.assertEqual([destination / "world" / "file.txt"], extracted)
            self.assertEqual(b"safe", extracted[0].read_bytes())

    def test_rejects_parent_traversal_without_partial_extraction(self) -> None:
        archive = zip_fixture(
            [
                ("world/safe.txt", b"safe", None),
                ("../escape.txt", b"escape", None),
            ]
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            destination = Path(temporary_directory) / "research"
            with self.assertRaisesRegex(ArchiveSafetyError, "traversal"):
                safe_extract(archive, destination, ArchiveLimits())

            self.assertFalse((Path(temporary_directory) / "escape.txt").exists())
            self.assertFalse((destination / "world" / "safe.txt").exists())

    def test_rejects_rooted_and_drive_qualified_paths(self) -> None:
        unsafe_names = ["/absolute.txt", "\\rooted.txt", "C:/drive.txt", "D:relative.txt", "//server/share.txt"]
        for unsafe_name in unsafe_names:
            with self.subTest(unsafe_name=unsafe_name):
                archive = zip_fixture([(unsafe_name, b"unsafe", None)])
                with tempfile.TemporaryDirectory() as temporary_directory:
                    with self.assertRaisesRegex(ArchiveSafetyError, "rooted|drive"):
                        safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_symlink_members(self) -> None:
        symlink_mode = (stat.S_IFLNK | 0o777) << 16
        archive = zip_fixture([("world/link", b"../../escape", symlink_mode)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "link|reparse"):
                safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_reparse_like_members(self) -> None:
        archive = zip_fixture([("world/junction", b"target", 0x0400)])
        with zipfile.ZipFile(archive, "a") as output:
            output.infolist()[-1].create_system = 0

        # Rebuild because changing an in-memory ZipInfo after writing does not update the central directory.
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            member = zipfile.ZipInfo("world/junction")
            member.create_system = 0
            member.external_attr = 0x0400
            output.writestr(member, b"target")
        archive.seek(0)

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "link|reparse"):
                safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_duplicate_normalized_output_paths(self) -> None:
        archive = zip_fixture(
            [
                ("world/region/data.bin", b"one", None),
                ("WORLD\\REGION\\data.bin", b"two", None),
            ]
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "duplicate"):
                safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_member_count_over_limit(self) -> None:
        archive = zip_fixture([("one", b"1", None), ("two", b"2", None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "member count"):
                safe_extract(
                    archive,
                    Path(temporary_directory) / "research",
                    ArchiveLimits(max_members=1),
                )

    def test_rejects_single_member_over_limit(self) -> None:
        archive = zip_fixture([("large.bin", b"12345", None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "member size"):
                safe_extract(
                    archive,
                    Path(temporary_directory) / "research",
                    ArchiveLimits(max_member_size=4),
                )

    def test_rejects_total_expanded_size_over_limit(self) -> None:
        archive = zip_fixture([("one.bin", b"123", None), ("two.bin", b"456", None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "expanded size"):
                safe_extract(
                    archive,
                    Path(temporary_directory) / "research",
                    ArchiveLimits(max_expanded_size=5),
                )

    def test_rejects_extreme_compression_ratio(self) -> None:
        archive = zip_fixture([("bomb.bin", b"0" * 20_000, None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "compression ratio"):
                safe_extract(
                    archive,
                    Path(temporary_directory) / "research",
                    ArchiveLimits(max_compression_ratio=5.0),
                )

    def test_rejects_windows_alternate_data_stream_paths(self) -> None:
        archive = zip_fixture([("world/level.dat:payload", b"unsafe", None)])

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ArchiveSafetyError, "drive|alternate-stream"):
                safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_windows_device_and_ambiguous_paths(self) -> None:
        unsafe_names = ["NUL", "world/CON.txt", "world/name.", "world/name ", "."]
        for unsafe_name in unsafe_names:
            with self.subTest(unsafe_name=unsafe_name):
                archive = zip_fixture([(unsafe_name, b"unsafe", None)])
                with tempfile.TemporaryDirectory() as temporary_directory:
                    with self.assertRaisesRegex(ArchiveSafetyError, "Windows|invalid|traversal"):
                        safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())

    def test_rejects_unicode_dos_device_names(self) -> None:
        unsafe_names = ["COM¹", "world/com².txt", "WORLD/LpT³.log"]
        for unsafe_name in unsafe_names:
            with self.subTest(unsafe_name=unsafe_name):
                archive = zip_fixture([(unsafe_name, b"unsafe", None)])
                with tempfile.TemporaryDirectory() as temporary_directory:
                    with self.assertRaisesRegex(ArchiveSafetyError, "Windows"):
                        safe_extract(archive, Path(temporary_directory) / "research", ArchiveLimits())


class SourceLedgerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.ledger = json.loads((REPOSITORY_ROOT / "maps" / "source-ledger.json").read_text(encoding="utf-8"))

    def test_restructured_has_verified_exact_version_and_retained_license(self) -> None:
        source = self.ledger["sources"]["re-structured"]

        self.assertEqual("ShB7QWuY", source["projectId"])
        self.assertEqual("NNsq5KuW", source["versionId"])
        self.assertEqual("1.2", source["version"])
        self.assertEqual(
            "https://cdn.modrinth.com/data/ShB7QWuY/versions/NNsq5KuW/re-structured.zip",
            source["archive"]["url"],
        )
        self.assertEqual("verified", source["licenseStatus"])
        self.assertTrue(source["bundleEligible"])
        self.assertEqual("MIT", source["license"]["spdx"])

        retained_license = REPOSITORY_ROOT / source["license"]["retainedText"]
        self.assertEqual(
            "a6814afbcf66038d02c80d905d4969b944f1d89b872bf89bc9699c7d1391ef31",
            hashlib.sha256(retained_license.read_bytes()).hexdigest(),
        )
        self.assertEqual(source["license"]["sha256"], hashlib.sha256(retained_license.read_bytes()).hexdigest())

    def test_curseforge_sources_remain_provisional_and_not_acquirable(self) -> None:
        expected = {
            "parkour-masters": (
                "1020454",
                "5353095",
                "https://www.curseforge.com/minecraft/worlds/parkour-masters/files/5353095",
            ),
            "minegpt-worlds": (
                "1092626",
                "5798831",
                "https://www.curseforge.com/minecraft/worlds/bigyous-minegpt-worlds/files/5798831",
            ),
            "bunker-survival": (
                "1010696",
                "5300058",
                "https://www.curseforge.com/minecraft/worlds/bunker-survival/files/5300058",
            ),
        }

        for source_key, (project_id, file_id, landing_url) in expected.items():
            with self.subTest(source_key=source_key):
                source = self.ledger["sources"][source_key]
                self.assertEqual(project_id, source["projectId"])
                self.assertEqual(file_id, source["fileId"])
                self.assertEqual(landing_url, source["officialLandingUrl"])
                self.assertEqual("declared-platform-awaiting-retained-text", source["licenseStatus"])
                self.assertFalse(source["bundleEligible"])
                self.assertIsNone(source["archive"]["url"])


class FetchMapSourceTests(unittest.TestCase):
    def run_fetch(self, source_key: str, destination: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(REPOSITORY_ROOT / "scripts" / "maps" / "fetch_map_source.ps1"),
                "-SourceKey",
                source_key,
                "-Destination",
                str(destination),
            ],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def run_isolated_fetch(
        self,
        repository: Path,
        script_path: Path,
        destination: Path,
        transport_url: str,
        *,
        fail_after_move: int | None = None,
    ) -> subprocess.CompletedProcess[str]:
        escaped_script = str(script_path).replace("'", "''")
        escaped_destination = str(destination).replace("'", "''")
        escaped_transport_url = transport_url.replace("'", "''")
        move_interceptor = ""
        if fail_after_move is not None:
            move_interceptor = (
                "$global:MapTestMoveCount = 0; "
                "function Move-Item { param([string]$LiteralPath, [string]$Destination); "
                "$global:MapTestMoveCount++; "
                "Microsoft.PowerShell.Management\\Move-Item @PSBoundParameters; "
                f"if ($global:MapTestMoveCount -eq {fail_after_move}) {{ "
                "throw 'Injected failure after move side effect.' } }; "
            )
        command = move_interceptor + (
            "function Invoke-WebRequest { param([switch]$UseBasicParsing, [int]$MaximumRedirection, "
            "[uri]$Uri, [string]$OutFile); "
            f"$parameters = @{{ UseBasicParsing = $true; Uri = '{escaped_transport_url}'; "
            "OutFile = $OutFile }; "
            "if ($PSBoundParameters.ContainsKey('MaximumRedirection')) { "
            "$parameters.MaximumRedirection = $MaximumRedirection }; "
            "Microsoft.PowerShell.Utility\\Invoke-WebRequest @parameters }; "
            f"& '{escaped_script}' -SourceKey local-test -Destination '{escaped_destination}'"
        )
        return subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
            cwd=repository,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def test_local_acquisition_publishes_pair_and_reuses_it(self) -> None:
        payload = b"local map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "success"

                first = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                second = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, first.returncode, first.stderr)
                self.assertEqual(0, second.returncode, second.stderr)
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())
                self.assertIn("False", first.stdout)
                self.assertIn("True", second.stdout)

    def test_rejects_redirect_without_contacting_unapproved_target(self) -> None:
        payload = b"redirected map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/redirect", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "redirect"

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/redirect"))

                self.assertEqual(1, source.requests.get("/redirect", 0))
                self.assertEqual(0, source.requests.get("/target", 0))
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((destination / "fixture.zip").exists())
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_evidence_promotion_failure_rolls_back_entire_pair(self) -> None:
        payload = b"transaction map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "rollback"

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    fail_after_move=2,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn("after move side effect", (result.stdout + result.stderr).lower())
                self.assertFalse((destination / "fixture.zip").exists())
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_archive_promotion_failure_preserves_preexisting_evidence(self) -> None:
        payload = b"preexisting evidence archive"
        approved_url = "https://approved.invalid/archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository(approved_url, payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "preexisting"
                destination.mkdir(parents=True)
                evidence_path = destination / "fixture.zip.sha256.json"
                evidence_bytes = json.dumps(
                    {
                        "schemaVersion": 1,
                        "sourceKey": "local-test",
                        "url": approved_url,
                        "filename": "fixture.zip",
                        "retrievedAtUtc": "2026-08-19T00:00:00Z",
                        "size": len(payload),
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    }
                ).encode()
                evidence_path.write_bytes(evidence_bytes)

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    fail_after_move=1,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertFalse((destination / "fixture.zip").exists())
                self.assertEqual(evidence_bytes, evidence_path.read_bytes())
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_rejects_unknown_source_before_network_access(self) -> None:
        result = self.run_fetch("not-in-ledger", REPOSITORY_ROOT / "runtime" / "map-research" / "test")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("not present in the source ledger", result.stderr)

    def test_rejects_destination_outside_ignored_research_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            result = self.run_fetch("re-structured", Path(temporary_directory) / "download")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("runtime/map-research", result.stderr.replace("\\", "/"))

    def test_rejects_existing_archive_with_wrong_ledger_size(self) -> None:
        research_root = REPOSITORY_ROOT / "runtime" / "map-research"
        research_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=research_root) as temporary_directory:
            destination = Path(temporary_directory)
            archive = destination / "re-structured.zip"
            archive.write_bytes(b"not the approved archive")
            checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
            evidence = {
                "schemaVersion": 1,
                "sourceKey": "re-structured",
                "url": "https://cdn.modrinth.com/data/ShB7QWuY/versions/NNsq5KuW/re-structured.zip",
                "filename": "re-structured.zip",
                "retrievedAtUtc": "2026-08-19T00:00:00Z",
                "size": archive.stat().st_size,
                "sha256": checksum,
            }
            (destination / "re-structured.zip.sha256.json").write_text(
                json.dumps(evidence), encoding="utf-8"
            )

            result = self.run_fetch("re-structured", destination)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("size", result.stderr.lower())

    def test_rejects_self_attested_sha256_when_official_sha512_differs(self) -> None:
        research_root = REPOSITORY_ROOT / "runtime" / "map-research"
        research_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=research_root) as temporary_directory:
            destination = Path(temporary_directory)
            archive = destination / "re-structured.zip"
            archive.write_bytes(b"x" * 97_543)
            checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
            evidence = {
                "schemaVersion": 1,
                "sourceKey": "re-structured",
                "url": "https://cdn.modrinth.com/data/ShB7QWuY/versions/NNsq5KuW/re-structured.zip",
                "filename": "re-structured.zip",
                "retrievedAtUtc": "2026-08-19T00:00:00Z",
                "size": archive.stat().st_size,
                "sha256": checksum,
            }
            (destination / "re-structured.zip.sha256.json").write_text(
                json.dumps(evidence), encoding="utf-8"
            )

            result = self.run_fetch("re-structured", destination)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("sha512", result.stderr.lower())


if __name__ == "__main__":
    unittest.main()
