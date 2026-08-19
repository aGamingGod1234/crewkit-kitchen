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
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator

from scripts.maps.archive_reader import ArchiveLimits, ArchiveSafetyError, safe_extract


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]

class LocalHttpSource:
    def __init__(
        self,
        payload: bytes,
        wait_for_archive_requests: int | None = None,
        on_archive_request: Callable[[], None] | None = None,
    ) -> None:
        self.payload = payload
        self.requests: dict[str, int] = {}
        self.wait_for_archive_requests = wait_for_archive_requests
        self.on_archive_request = on_archive_request
        self._request_condition = threading.Condition()

        source = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                with source._request_condition:
                    source.requests[self.path] = source.requests.get(self.path, 0) + 1
                    source._request_condition.notify_all()
                    if self.path == "/archive" and source.wait_for_archive_requests is not None:
                        source._request_condition.wait_for(
                            lambda: source.requests.get("/archive", 0) >= source.wait_for_archive_requests,
                            timeout=2,
                        )
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", source.url("/target"))
                    self.end_headers()
                    return
                if self.path in ("/archive", "/target"):
                    if self.path == "/archive" and source.on_archive_request is not None:
                        source.on_archive_request()
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
def isolated_fetch_repository(
    approved_url: str,
    payload: bytes,
    *,
    ledger_sha256: str | None = None,
) -> Iterator[tuple[Path, Path]]:
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
                        "sha256": ledger_sha256,
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
        kill_after_move: int | None = None,
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
        if kill_after_move is not None:
            move_interceptor = (
                "$global:MapTestMoveCount = 0; "
                "function Move-Item { param([string]$LiteralPath, [string]$Destination); "
                "$global:MapTestMoveCount++; "
                "Microsoft.PowerShell.Management\\Move-Item @PSBoundParameters; "
                f"if ($global:MapTestMoveCount -eq {kill_after_move}) {{ "
                "Stop-Process -Id $PID -Force } }; "
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

    def test_concurrent_same_destination_serializes_initial_acquisition(self) -> None:
        payload = b"concurrent map archive"
        with LocalHttpSource(payload, wait_for_archive_requests=2) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "concurrent"

                with ThreadPoolExecutor(max_workers=2) as executor:
                    futures = [
                        executor.submit(
                            self.run_isolated_fetch,
                            repository,
                            script_path,
                            destination,
                            source.url("/archive"),
                        )
                        for _ in range(2)
                    ]
                    results = [future.result(timeout=20) for future in futures]

                archive_path = destination / "fixture.zip"
                evidence_path = destination / "fixture.zip.sha256.json"
                self.assertTrue(archive_path.is_file())
                self.assertTrue(evidence_path.is_file())
                self.assertEqual(payload, archive_path.read_bytes())
                evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
                self.assertEqual(hashlib.sha256(payload).hexdigest(), evidence["sha256"])
                self.assertEqual([0, 0], sorted(result.returncode for result in results))
                self.assertEqual(1, source.requests.get("/archive", 0))
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_concurrent_different_destinations_acquire_independently(self) -> None:
        payload = b"independent map archive"
        with LocalHttpSource(payload, wait_for_archive_requests=2) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destinations = [
                    repository / "runtime" / "map-research" / "destination-a",
                    repository / "runtime" / "map-research" / "destination-b",
                ]

                with ThreadPoolExecutor(max_workers=2) as executor:
                    futures = [
                        executor.submit(
                            self.run_isolated_fetch,
                            repository,
                            script_path,
                            destination,
                            source.url("/archive"),
                        )
                        for destination in destinations
                    ]
                    results = [future.result(timeout=20) for future in futures]

                self.assertEqual([0, 0], sorted(result.returncode for result in results))
                self.assertEqual(2, source.requests.get("/archive", 0))
                for destination in destinations:
                    self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                    self.assertTrue((destination / "fixture.zip.sha256.json").is_file())

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

                retry = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                self.assertEqual(0, retry.returncode, retry.stderr)
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())

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

    def test_archive_promotion_failure_after_evidence_only_recovery_leaves_no_pair(self) -> None:
        payload = b"preexisting evidence archive"
        approved_url = "https://approved.invalid/archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository(approved_url, payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "preexisting"
                destination.mkdir(parents=True)
                evidence_path = destination / "fixture.zip.sha256.json"
                evidence_path.write_text(
                    json.dumps(
                        {
                            "schemaVersion": 1,
                            "sourceKey": "local-test",
                            "url": approved_url,
                            "filename": "fixture.zip",
                            "retrievedAtUtc": "2026-08-19T00:00:00Z",
                            "size": len(payload),
                            "sha256": hashlib.sha256(payload).hexdigest(),
                        }
                    ),
                    encoding="utf-8",
                )

                result = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    fail_after_move=1,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertFalse((destination / "fixture.zip").exists())
                self.assertFalse(evidence_path.exists())
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_hard_kill_after_archive_promotion_recovers_on_retry(self) -> None:
        payload = b"hard-kill map archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "hard-kill-first"

                interrupted = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    kill_after_move=1,
                )
                self.assertNotEqual(0, interrupted.returncode)
                self.assertTrue((destination / "fixture.zip").is_file())
                self.assertFalse((destination / "fixture.zip.sha256.json").exists())

                retry = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                reuse = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, retry.returncode, retry.stderr)
                self.assertEqual(0, reuse.returncode, reuse.stderr)
                self.assertIn("False", retry.stdout)
                self.assertIn("True", reuse.stdout)
                self.assertEqual(2, source.requests.get("/archive", 0))
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                evidence = json.loads((destination / "fixture.zip.sha256.json").read_text(encoding="utf-8"))
                self.assertEqual(hashlib.sha256(payload).hexdigest(), evidence["sha256"])
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_archive_only_with_ledger_sha256_is_reacquired_not_reused(self) -> None:
        payload = b"ledger-locked orphan archive"
        approved_url = "https://approved.invalid/archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository(
                approved_url,
                payload,
                ledger_sha256=hashlib.sha256(payload).hexdigest(),
            ) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "archive-only"
                destination.mkdir(parents=True)
                (destination / "fixture.zip").write_bytes(payload)

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("False", result.stdout)
                self.assertEqual(1, source.requests.get("/archive", 0))
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())

    def test_evidence_only_is_replaced_during_recovery(self) -> None:
        payload = b"orphan evidence archive"
        approved_url = "https://approved.invalid/archive"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository(approved_url, payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "evidence-only"
                destination.mkdir(parents=True)
                evidence_path = destination / "fixture.zip.sha256.json"
                evidence_path.write_text("not trusted as evidence", encoding="utf-8")

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("False", result.stdout)
                self.assertEqual(1, source.requests.get("/archive", 0))
                replacement = json.loads(evidence_path.read_text(encoding="utf-8"))
                self.assertEqual("local-test", replacement["sourceKey"])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), replacement["sha256"])
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())

    def test_interrupted_recovery_removes_only_source_scoped_uuid_partials(self) -> None:
        payload = b"stale partial recovery archive"
        approved_url = "https://approved.invalid/archive"
        acquisition_id = "a" * 32
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository(
                approved_url,
                payload,
                ledger_sha256=hashlib.sha256(payload).hexdigest(),
            ) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "stale-partials"
                destination.mkdir(parents=True)
                (destination / "fixture.zip").write_bytes(payload)
                stale_archive_partial = destination / f".fixture.zip.{acquisition_id}.partial"
                stale_evidence_partial = destination / f"fixture.zip.sha256.json.{acquisition_id}.partial"
                unrelated_partial = destination / f".other.zip.{acquisition_id}.partial"
                stale_archive_partial.write_bytes(b"stale")
                stale_evidence_partial.write_bytes(b"stale")
                unrelated_partial.write_bytes(b"unrelated")

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse(stale_archive_partial.exists())
                self.assertFalse(stale_evidence_partial.exists())
                self.assertEqual(b"unrelated", unrelated_partial.read_bytes())
                self.assertTrue((destination / "fixture.zip").is_file())
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())

    def test_hard_kill_after_evidence_promotion_leaves_valid_pair_for_reuse(self) -> None:
        payload = b"hard-kill complete pair"
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "hard-kill-second"

                interrupted = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    kill_after_move=2,
                )
                self.assertNotEqual(0, interrupted.returncode)
                self.assertTrue((destination / "fixture.zip").is_file())
                self.assertTrue((destination / "fixture.zip.sha256.json").is_file())

                reuse = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, reuse.returncode, reuse.stderr)
                self.assertIn("True", reuse.stdout)
                self.assertEqual(1, source.requests.get("/archive", 0))
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())

    def test_hard_kill_during_evidence_only_recovery_retries_from_clean_state(self) -> None:
        payload = b"evidence orphan hard-kill archive"
        approved_url = "https://approved.invalid/archive"
        observed_orphan_at_request: list[bool] = []
        evidence_holder: dict[str, Path] = {}

        def observe_evidence_orphan() -> None:
            observed_orphan_at_request.append(evidence_holder["path"].exists())

        with LocalHttpSource(payload, on_archive_request=observe_evidence_orphan) as source:
            with isolated_fetch_repository(approved_url, payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "evidence-hard-kill"
                destination.mkdir(parents=True)
                evidence_path = destination / "fixture.zip.sha256.json"
                evidence_path.write_text("orphan evidence must not be trusted", encoding="utf-8")
                evidence_holder["path"] = evidence_path

                interrupted = self.run_isolated_fetch(
                    repository,
                    script_path,
                    destination,
                    source.url("/archive"),
                    kill_after_move=1,
                )
                retry = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                reuse = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertNotEqual(0, interrupted.returncode)
                self.assertEqual([False, False], observed_orphan_at_request)
                self.assertEqual(0, retry.returncode, retry.stderr)
                self.assertEqual(0, reuse.returncode, reuse.stderr)
                self.assertIn("False", retry.stdout)
                self.assertIn("True", reuse.stdout)
                self.assertEqual(payload, (destination / "fixture.zip").read_bytes())
                evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
                self.assertEqual(hashlib.sha256(payload).hexdigest(), evidence["sha256"])
                self.assertEqual([], list(destination.glob("*.partial")))
                self.assertEqual([], list(destination.glob(".*.partial")))

    def test_stale_source_partials_are_removed_when_neither_final_exists(self) -> None:
        payload = b"neither-final stale partial archive"
        acquisition_id = "b" * 32
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "neither-stale"
                destination.mkdir(parents=True)
                stale_archive = destination / f".fixture.zip.{acquisition_id}.partial"
                stale_evidence = destination / f"fixture.zip.sha256.json.{acquisition_id}.partial"
                unrelated = destination / f".other.zip.{acquisition_id}.partial"
                stale_archive.write_bytes(b"stale")
                stale_evidence.write_bytes(b"stale")
                unrelated.write_bytes(b"unrelated")

                result = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse(stale_archive.exists())
                self.assertFalse(stale_evidence.exists())
                self.assertEqual(b"unrelated", unrelated.read_bytes())

    def test_complete_pair_reuse_also_cleans_stale_source_partials(self) -> None:
        payload = b"complete pair stale partial archive"
        acquisition_id = "c" * 32
        with LocalHttpSource(payload) as source:
            with isolated_fetch_repository("https://approved.invalid/archive", payload) as (repository, script_path):
                destination = repository / "runtime" / "map-research" / "complete-stale"
                first = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))
                self.assertEqual(0, first.returncode, first.stderr)
                stale_archive = destination / f".fixture.zip.{acquisition_id}.partial"
                stale_evidence = destination / f"fixture.zip.sha256.json.{acquisition_id}.partial"
                stale_archive.write_bytes(b"stale")
                stale_evidence.write_bytes(b"stale")

                reuse = self.run_isolated_fetch(repository, script_path, destination, source.url("/archive"))

                self.assertEqual(0, reuse.returncode, reuse.stderr)
                self.assertIn("True", reuse.stdout)
                self.assertEqual(1, source.requests.get("/archive", 0))
                self.assertFalse(stale_archive.exists())
                self.assertFalse(stale_evidence.exists())

    def test_hostile_expected_paths_fail_closed_without_deletion(self) -> None:
        payload = b"hostile final path archive"
        approved_url = "https://approved.invalid/archive"
        for hostile_name in ("fixture.zip", "fixture.zip.sha256.json", ".arenaagents-acquisition.lock"):
            with self.subTest(hostile_name=hostile_name):
                with LocalHttpSource(payload) as source:
                    with isolated_fetch_repository(
                        approved_url,
                        payload,
                        ledger_sha256=hashlib.sha256(payload).hexdigest(),
                    ) as (repository, script_path):
                        destination = repository / "runtime" / "map-research" / "hostile"
                        destination.mkdir(parents=True)
                        hostile_path = destination / hostile_name
                        hostile_path.mkdir()

                        result = self.run_isolated_fetch(
                            repository,
                            script_path,
                            destination,
                            source.url("/archive"),
                        )

                        self.assertNotEqual(0, result.returncode)
                        self.assertTrue(hostile_path.is_dir())
                        self.assertEqual(0, source.requests.get("/archive", 0))

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
