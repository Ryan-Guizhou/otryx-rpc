#!/usr/bin/env python3
"""Adversarial tests for immutable controlled-performance evidence inventories."""
from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

from manage_v2d2_evidence_manifest import create, validate_manifest, verify


class EvidenceManifestIntegrityTest(unittest.TestCase):
    def bundle(self, root: Path) -> Path:
        root.mkdir()
        (root / "environment.properties").write_text(
            "run_id=run01-baseline\n"
            + "commit=" + "a" * 40 + "\n"
            + "runner_id=fixed-perf-01\n"
            + "evidence_class=controlled\n", encoding="utf-8")
        (root / "soak.json").write_text('{"durationSeconds":3600}\n', encoding="utf-8")
        create(root)
        return root

    def update(self, root: Path, updater) -> None:
        marker = root / "evidence-manifest.json"
        record = json.loads(marker.read_text(encoding="utf-8"))
        updater(record)
        marker.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")

    def test_valid_inventory_and_cli(self) -> None:
        with tempfile.TemporaryDirectory() as scratch:
            bundle = self.bundle(Path(scratch) / "bundle")
            record = validate_manifest(bundle)
            self.assertEqual(2, record["fileCount"])
            self.assertEqual(0, verify(bundle))

    def test_falsified_counts_and_duplicate_entries_fail_even_with_correct_hashes(self) -> None:
        for name, mutate, error in (
            ("fileCount", lambda r: r.__setitem__("fileCount", 999), "fileCount"),
            ("totalBytes", lambda r: r.__setitem__("totalBytes", 0), "totalBytes"),
            ("duplicate", lambda r: r["files"].append(dict(r["files"][0])), "Duplicate"),
        ):
            with self.subTest(case=name), tempfile.TemporaryDirectory() as scratch:
                bundle = self.bundle(Path(scratch) / "bundle")
                self.update(bundle, mutate)
                with self.assertRaisesRegex(ValueError, error):
                    validate_manifest(bundle)
                with self.assertRaises(SystemExit):
                    verify(bundle)

    def test_identity_time_and_schema_are_not_ignored(self) -> None:
        for name, mutate, error in (
            ("runner", lambda r: r.__setitem__("runnerId", "impostor"), "provenance"),
            ("runId", lambda r: r.__setitem__("runId", "different"), "provenance"),
            ("schema", lambda r: r.__setitem__("schemaVersion", 2), "schema"),
            ("timezone", lambda r: r.__setitem__("createdAt",
                                                "2026-10-22T08:00:00"), "timezone"),
        ):
            with self.subTest(case=name), tempfile.TemporaryDirectory() as scratch:
                bundle = self.bundle(Path(scratch) / "bundle")
                self.update(bundle, mutate)
                with self.assertRaisesRegex(ValueError, error):
                    validate_manifest(bundle)

    def test_path_traversal_aliases_and_broken_checksums_fail(self) -> None:
        for name, corrupt, error in (
            ("traversal", lambda r: r["files"][0].__setitem__(
                "path", "../outside"), "unsafe"),
            ("alias", lambda r: r["files"][0].__setitem__(
                "path", "./environment.properties"), "noncanonical"),
            ("backslash", lambda r: r["files"][0].__setitem__(
                "path", "matrix\\sample.json"), "Unsafe"),
            ("checksum", lambda r: r["files"][0].__setitem__(
                "sha256", "not-a-sha256"), "SHA-256"),
        ):
            with self.subTest(case=name), tempfile.TemporaryDirectory() as scratch:
                bundle = self.bundle(Path(scratch) / "bundle")
                self.update(bundle, corrupt)
                with self.assertRaisesRegex(ValueError, error):
                    validate_manifest(bundle)

    @unittest.skipIf(os.name == "nt", "Symlink privileges are not guaranteed on Windows")
    def test_symlinked_files_directories_and_manifest_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as scratch:
            scratch = Path(scratch)
            bundle = self.bundle(scratch / "bundle")
            outside = scratch / "outside"
            outside.mkdir()
            (outside / "foreign.json").write_text('{"forged":true}', encoding="utf-8")
            linked_file = bundle / "foreign.json"
            linked_file.symlink_to(outside / "foreign.json")
            with self.assertRaisesRegex(ValueError, "Symlink"):
                validate_manifest(bundle)
            linked_file.unlink()
            linked_dir = bundle / "external"
            linked_dir.symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "Symlink"):
                validate_manifest(bundle)
            linked_dir.unlink()
            marker = bundle / "evidence-manifest.json"
            original = marker.read_bytes()
            marker.unlink()
            (outside / "copy.json").write_bytes(original)
            marker.symlink_to(outside / "copy.json")
            with self.assertRaisesRegex(ValueError, "symlink"):
                validate_manifest(bundle)
            with self.assertRaisesRegex(ValueError, "Symlink"):
                create(bundle)


if __name__ == "__main__":
    unittest.main()
