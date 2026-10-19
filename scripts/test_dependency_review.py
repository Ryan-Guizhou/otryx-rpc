#!/usr/bin/env python3
"""Dependency review must reject incomplete extraction, even with valid JSON."""

from __future__ import annotations

import json
import copy
import hashlib
import concurrent.futures
import os
import re
import signal
import subprocess
import tempfile
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE, HEAD, MERGE = "a" * 40, "b" * 40, "c" * 40
ANCHORS = (
    "io.etcd:jetcd-core", "io.grpc:grpc-core", "com.google.protobuf:protobuf-java",
    "com.google.guava:guava", "io.vertx:vertx-core", "io.vertx:vertx-grpc",
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def component(name: str, version: str = "1.2.3") -> dict:
    group, artifact = name.split(":")
    purl = f"pkg:maven/{group}/{artifact}@{version}"
    return {"type": "library", "group": group, "name": artifact, "version": version,
            "purl": purl, "bom-ref": purl}


def save_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value), encoding="utf-8")


def make_evidence(root: Path) -> dict:
    from dependency_review import CYCLONEDX_VERSION, OSV_SHA256, OSV_VERSION

    manifest = {"schemaVersion": 1, "status": "complete", "baseSha": BASE,
                "headSha": HEAD, "mergeSha": MERGE, "mergeParents": [BASE, HEAD],
                "cycloneDxVersion": CYCLONEDX_VERSION, "sides": {}}
    roots = (
        ("main", "pom.xml", "com.example:reactor", list(ANCHORS)),
        ("comparison", "benchmarks/rpc-comparison/pom.xml", "com.example:comparison",
         ["org.apache.dubbo:dubbo"]),
        ("eureka", ".github/test-fixtures/eureka-server/pom.xml", "com.example:eureka",
         ["org.springframework:spring-core"]),
    )
    for side, source_sha, result_name in (
            ("baseline", BASE, "old-results.json"), ("candidate", MERGE, "new-results.json")):
        directory = root / side
        directory.mkdir()
        records, sources = [], []
        for name, pom, local, external in roots:
            own = component(local, "1.0.0-SNAPSHOT")
            components = [own, *[component(item) for item in external]]
            bom = {"bomFormat": "CycloneDX", "specVersion": "1.6",
                   "metadata": {"component": own}, "components": components,
                   "dependencies": [{"ref": entry["bom-ref"], "dependsOn": []}
                                    for entry in components]}
            file = directory / f"{name}.cdx.json"
            save_json(file, bom)
            logs = []
            for operation in ("install", "sbom"):
                log = directory / f"{name}-{operation}.log"
                log.write_text("[INFO] BUILD SUCCESS\n")
                logs.append({"path": str(log.relative_to(root)), "sha256": sha256(log),
                             "exitCode": 0})
            records.append({"root": name, "path": str(file.relative_to(root)),
                            "sha256": sha256(file), "modules": [{
                                "pom": pom, "name": local, "version": "1.0.0-SNAPSHOT",
                                "packaging": "pom"}], "logs": logs})
            sources.append({"source": {"path": str(file), "type": "sbom"},
                            "packages": [{"package": {"name": package, "version": "1.2.3",
                                                       "ecosystem": "Maven"}}
                                         for package in external]})
        manifest["sides"][side] = {
            "sourceSha": source_sha, "worktree": str(root / "work" / side / "source"),
            "mavenRepository": str(root / "work" / side / "m2"),
            "trackedPoms": [item[1] for item in roots], "sboms": records}
        result = root / result_name
        save_json(result, {"results": sources})
        log = root / f"{side}-scan.log"
        log.write_text("Scanned all supplied SBOM packages\n")
        save_json(root / f"{side}-scan.json", {
            "version": OSV_VERSION, "binarySha256": OSV_SHA256, "allPackages": True,
            "exitCode": 0, "inputs": [record["path"] for record in records],
            "result": result_name, "resultSha256": sha256(result),
            "log": log.name, "logSha256": sha256(log)})
    save_json(root / "manifest.json", manifest)
    return manifest


class EvidenceValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        from dependency_review import validate_evidence
        self.validate = validate_evidence
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifest = make_evidence(self.root)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def accept(self) -> dict:
        return self.validate(self.root, BASE, HEAD, MERGE)

    def save_manifest(self) -> None:
        save_json(self.root / "manifest.json", self.manifest)

    def change_json(self, name: str, change, scan_hash: bool = False) -> None:
        path = self.root / name
        value = json.loads(path.read_text())
        change(value)
        save_json(path, value)
        if scan_hash:
            scan = self.root / "candidate-scan.json"
            status = json.loads(scan.read_text())
            status["resultSha256"] = sha256(path)
            save_json(scan, status)

    def test_all_roots_and_all_transitive_packages_are_accepted(self) -> None:
        summary = self.accept()
        self.assertEqual(summary["candidate"]["main"]["externalPackages"], len(ANCHORS))

    def test_vulnerability_exit_one_reaches_the_unchanged_diff_reporter(self) -> None:
        self.change_json("candidate-scan.json", lambda value: value.update(exitCode=1))
        self.accept()
        workflow = (ROOT / ".github/workflows/dependency-review.yml").read_text()
        self.assertIn("osv-reporter-action@0a65f82af05cd58cf587827b0bb0489548c18346", workflow)
        self.assertIn("--fail-on-vuln=true", workflow)

    def test_missing_transitive_package_is_rejected(self) -> None:
        self.change_json("new-results.json", lambda value: value["results"][0]["packages"].pop(), True)
        with self.assertRaisesRegex(ValueError, "coverage"):
            self.accept()

    def test_valid_empty_scanner_result_is_rejected(self) -> None:
        self.change_json("new-results.json", lambda value: value.update(results=[]), True)
        with self.assertRaisesRegex(ValueError, "coverage"):
            self.accept()

    def test_one_root_missing_from_scan_is_rejected(self) -> None:
        self.change_json("new-results.json", lambda value: value["results"].pop(), True)
        with self.assertRaisesRegex(ValueError, "coverage"):
            self.accept()

    def test_extraction_and_api_errors_are_not_vulnerability_exit_codes(self) -> None:
        for code in (2, 127, 128, 129, 130):
            with self.subTest(code=code):
                self.change_json("candidate-scan.json", lambda value: value.update(exitCode=code))
                with self.assertRaisesRegex(ValueError, "exit"):
                    self.accept()

    def test_extraction_diagnostic_is_rejected_even_when_exit_is_zero(self) -> None:
        log = self.root / "candidate-scan.log"
        for diagnostic in ("Error during extraction: failed resolution for etcd/pom.xml",
                           "failed to query OSV: connection interrupted",
                           "ERROR: vulnerability API response was incomplete"):
            with self.subTest(diagnostic=diagnostic):
                log.write_text(diagnostic + "\n")
                self.change_json("candidate-scan.json", lambda value: value.update(logSha256=sha256(log)))
                with self.assertRaisesRegex(ValueError, "extraction"):
                    self.accept()

    def test_hash_drift_and_unresolved_sbom_versions_are_rejected(self) -> None:
        record = self.manifest["sides"]["candidate"]["sboms"][0]
        self.change_json(record["path"], lambda value: value["components"][1].update(version="${revision}"))
        with self.assertRaisesRegex(ValueError, "hash"):
            self.accept()
        record["sha256"] = sha256(self.root / record["path"])
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "version"):
            self.accept()

    def test_empty_sbom_or_missing_purl_is_rejected(self) -> None:
        record = self.manifest["sides"]["candidate"]["sboms"][0]
        path = self.root / record["path"]
        original = json.loads(path.read_text())
        for mutate in (lambda value: value.update(components=[]),
                       lambda value: value["components"][1].pop("purl")):
            with self.subTest(mutate=mutate):
                value = copy.deepcopy(original)
                mutate(value)
                save_json(path, value)
                record["sha256"] = sha256(path)
                self.save_manifest()
                with self.assertRaises(ValueError):
                    self.accept()

    def test_missing_reactor_module_or_independent_root_is_rejected(self) -> None:
        self.manifest["sides"]["candidate"]["trackedPoms"].append("missed/pom.xml")
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "POM coverage"):
            self.accept()
        self.manifest["sides"]["candidate"]["trackedPoms"].pop()
        self.manifest["sides"]["candidate"]["sboms"].pop()
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "roots"):
            self.accept()

    def test_wrong_revision_or_shared_snapshot_repository_is_rejected(self) -> None:
        self.manifest["sides"]["candidate"]["sourceSha"] = HEAD
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "SHA"):
            self.accept()
        self.manifest["sides"]["candidate"]["sourceSha"] = MERGE
        self.manifest["sides"]["candidate"]["mavenRepository"] = \
            self.manifest["sides"]["baseline"]["mavenRepository"]
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "isolated"):
            self.accept()

    def test_missing_all_packages_or_wrong_scanner_binary_is_rejected(self) -> None:
        self.change_json("candidate-scan.json", lambda value: value.update(allPackages=False))
        with self.assertRaises(ValueError):
            self.accept()
        self.change_json("candidate-scan.json", lambda value: value.update(
            allPackages=True, binarySha256="0" * 64))
        with self.assertRaises(ValueError):
            self.accept()

    def test_reporter_paths_are_stable_without_changing_raw_packages_or_vulnerabilities(self) -> None:
        from dependency_review import prepare_reporter_inputs
        results = self.root / "new-results.json"
        candidate = json.loads(results.read_text())
        candidate["results"][0]["packages"][0]["vulnerabilities"] = [{
            "id": "GHSA-fixture-existing", "summary": "Unmodified evidence"}]
        save_json(results, candidate)
        self.change_json("candidate-scan.json", lambda value: value.update(resultSha256=sha256(results)))
        hashes = {name: sha256(self.root / name) for name in ("old-results.json", "new-results.json")}
        prepare_reporter_inputs(self.root, BASE, HEAD, MERGE)
        for prefix in ("old", "new"):
            expected = json.loads((self.root / f"{prefix}-results.json").read_text())
            normalized = json.loads((self.root / f"{prefix}-reporter.json").read_text())
            self.assertEqual(sha256(self.root / f"{prefix}-results.json"), hashes[f"{prefix}-results.json"])
            for source in expected["results"]:
                source["source"]["path"] = f"dependency-sbom/{Path(source['source']['path']).name}"
            self.assertEqual(normalized, expected)
        old = json.loads((self.root / "old-reporter.json").read_text())
        new = json.loads((self.root / "new-reporter.json").read_text())
        self.assertEqual([source["source"] for source in old["results"]],
                         [source["source"] for source in new["results"]])


class WorkflowEvidenceTest(unittest.TestCase):
    def test_failed_extraction_with_empty_valid_json_is_rejected(self) -> None:
        workflow = (ROOT / ".github/workflows/dependency-review.yml").read_text()
        step = re.search(
            r"      - name: Fail closed if either OSV scan did not produce evidence\n"
            r"(?P<step>.*?)(?=\n      - name:|\Z)", workflow, re.S
        )
        self.assertIsNotNone(step)
        run = step.group("step").split("        run: |\n", 1)[1]
        command = "\n".join(line[10:] for line in run.splitlines() if line.strip())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").symlink_to(ROOT / "scripts", target_is_directory=True)
            evidence = root / "dependency-evidence"
            evidence.mkdir()
            # Same valid-but-empty result shape as PR #160 run 39221767605.
            result = {"results": [], "experimental_config": {
                "licenses": {"summary": False, "allowlist": None}}}
            for directory in (root, evidence):
                for side in ("old", "new"):
                    (directory / f"{side}-results.json").write_text(json.dumps(result))
            (evidence / "baseline-scan.log").write_text(
                "Error during extraction: failed resolving otryx-core:1.0.0-SNAPSHOT\n")
            completed = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", command], cwd=root,
                env={**os.environ, "BASE_SHA": "a" * 40, "HEAD_SHA": "b" * 40,
                     "MERGE_SHA": "c" * 40}, capture_output=True, text=True, check=False)
            self.assertNotEqual(
                completed.returncode, 0,
                "Incomplete dependency extraction was accepted as usable evidence")


class ProcessBudgetTest(unittest.TestCase):
    def test_exited_parent_cannot_leave_a_child_holding_the_log_forever(self) -> None:
        from dependency_review import run_logged
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pid_file = root / "parent.pid"
            program = (
                "import os, pathlib, subprocess, sys; "
                "pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); "
                "subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)']); "
                "print('Parent exits; child still owns stdout', flush=True)"
            )
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                future = executor.submit(
                    run_logged, [sys.executable, "-c", program, str(pid_file)], root, root / "process.log",
                    idle_timeout=0.2, total_timeout=1, terminate_grace=0.1)
                try:
                    code = future.result(timeout=2)
                except concurrent.futures.TimeoutError:
                    self.fail("Exited parent bypassed the process-group progress budget")
                finally:
                    if pid_file.exists():
                        try:
                            os.killpg(int(pid_file.read_text()), signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                self.assertEqual(code, 124)


if __name__ == "__main__":
    unittest.main()
