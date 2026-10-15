#!/usr/bin/env python3
"""Self-test V2-D.2 evidence validation and repeatability tooling."""

from __future__ import annotations

import csv
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

RESILIENCE = (
    "retryBudgetAcquire",
    "circuitClosedAcquireAndSuccess",
    "circuitOpenReject",
    "outlierHealthyRead",
    "outlierEjectedRead",
    "outlierFailureAccounting",
)


def run(*args: str, expect_success: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [sys.executable, *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if expect_success and result.returncode != 0:
        raise AssertionError(result.stdout)
    if not expect_success and result.returncode == 0:
        raise AssertionError("Expected command failure:\n" + result.stdout)
    return result


def environment(
    commit: str = "test-commit",
    host_fingerprint: str = "host-fingerprint-a",
    run_id: str = "test-run",
) -> str:
    return "\n".join(
        [
            "schema_version=1",
            "captured_at=2026-09-30T00:00:00Z",
            f"commit={commit}",
            "evidence_class=controlled",
            "runner_id=otryx-perf-01",
            f"host_fingerprint_sha256={host_fingerprint}",
            "runner_labels=self-hosted,linux,x64,otryx-perf",
            f"run_id={run_id}",
            "hostname=perf-host",
            "kernel=Linux test",
            "cpu_model=Test CPU",
            "logical_cores=16",
            "physical_cores=8",
            "threads_per_core=2",
            "numa_nodes=1",
            "memory_bytes=34359738368",
            "cpu_governor=performance",
            "java=OpenJDK 21",
            "maven=Apache Maven test",
            "openssl=OpenSSL test",
            "containerized=false",
            "jvm_flags=-Xms4g -Xmx4g",
            "profile=smoke",
            "",
        ]
    )


def write_summary(path: Path, score_scale: float) -> None:
    fields = [
        "family",
        "benchmark",
        "scenario",
        "security",
        "mode",
        "payload_bytes",
        "connections",
        "threads",
        "score",
        "score_unit",
        "p50",
        "p99",
        "p999",
        "alloc_b_op",
        "alloc_mb_s",
        "gc_count",
        "gc_time_ms",
        "successes",
        "errors",
    ]
    rows = [
        {
            "family": "payload",
            "benchmark": "echo",
            "scenario": "-",
            "security": "PLAINTEXT",
            "mode": "sample",
            "payload_bytes": "256",
            "connections": "1",
            "threads": "1",
            "score": str(10.0 * score_scale),
            "score_unit": "us/op",
            "p50": "9",
            "p99": "12",
            "p999": "15",
            "alloc_b_op": str(128.0 * score_scale),
            "alloc_mb_s": "10",
            "gc_count": "1",
            "gc_time_ms": "2",
            "successes": "",
            "errors": "",
        },
        {
            "family": "scenario",
            "benchmark": "invoke",
            "scenario": "NOOP",
            "security": "PLAINTEXT",
            "mode": "sample",
            "payload_bytes": "0",
            "connections": "1",
            "threads": "1",
            "score": str(11.0 * score_scale),
            "score_unit": "us/op",
            "p50": "10",
            "p99": "13",
            "p999": "16",
            "alloc_b_op": str(130.0 * score_scale),
            "alloc_mb_s": "10",
            "gc_count": "1",
            "gc_time_ms": "2",
            "successes": "100",
            "errors": "0",
        },
        {
            "family": "security",
            "benchmark": "echo",
            "scenario": "-",
            "security": "PLAINTEXT",
            "mode": "sample",
            "payload_bytes": "256",
            "connections": "1",
            "threads": "1",
            "score": str(10.0 * score_scale),
            "score_unit": "us/op",
            "p50": "9",
            "p99": "12",
            "p999": "15",
            "alloc_b_op": str(128.0 * score_scale),
            "alloc_mb_s": "10",
            "gc_count": "1",
            "gc_time_ms": "2",
            "successes": "",
            "errors": "",
        },
    ]
    for index, benchmark in enumerate(RESILIENCE):
        rows.append(
            {
                "family": "resilience",
                "benchmark": benchmark,
                "scenario": "-",
                "security": "N/A",
                "mode": "sample",
                "payload_bytes": "0",
                "connections": "0",
                "threads": "1",
                "score": str((1.0 + index) * score_scale),
                "score_unit": "ns/op",
                "p50": "1",
                "p99": "2",
                "p999": "3",
                "alloc_b_op": str(8.0 * score_scale),
                "alloc_mb_s": "1",
                "gc_count": "0",
                "gc_time_ms": "0",
                "successes": "",
                "errors": "",
            }
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_bundle(
    root: Path,
    score_scale: float,
    commit: str = "test-commit",
    host_fingerprint: str = "host-fingerprint-a",
    run_id: str = "test-run",
) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / "environment.properties").write_text(
        environment(commit, host_fingerprint, run_id),
        encoding="utf-8",
    )
    write_summary(root / "matrix" / "summary.csv", score_scale)
    soak = {
        "schemaVersion": "1",
        "commit": commit,
        "concurrency": 10000,
        "durationSeconds": 1800.0,
        "successes": 1000000,
        "errors": 0,
        "errorRate": 0.0,
        "throughputOpsPerSecond": 50000.0 * score_scale,
        "p50Micros": 100.0 * score_scale,
        "p99Micros": 300.0 * score_scale,
        "p999Micros": 500.0 * score_scale,
        "gcCountDelta": 10,
        "gcTimeMillisDelta": 100,
        "processCpuCoresAverage": 4.0,
    }
    (root / "soak.json").write_text(
        json.dumps(soak),
        encoding="utf-8",
    )
    (root / "validation-report.json").write_text(
        json.dumps({"status": "PASS"}),
        encoding="utf-8",
    )
    (root / "decision-inputs.json").write_text(
        json.dumps({"schemaVersion": 1, "evidenceClass": "controlled"}),
        encoding="utf-8",
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="otryx-evidence-") as raw:
        temp = Path(raw)
        runs = []
        for index, scale in enumerate((1.00, 1.01, 0.99), start=1):
            root = temp / f"run-{index}"
            write_bundle(root, scale, run_id=f"run-{index}")
            runs.append(root)

        for root in runs:
            run(
                "scripts/manage_v2d2_evidence_manifest.py",
                "create",
                "--bundle",
                str(root),
            )

        e1_output = temp / "e1-handoff"
        run(
            "scripts/finalize_v2d2_e1.py",
            "--run",
            str(runs[0]),
            "--run",
            str(runs[1]),
            "--run",
            str(runs[2]),
            "--output-dir",
            str(e1_output),
        )
        e1_handoff = json.loads(
            (e1_output / "e1-handoff.json").read_text(
                encoding="utf-8"
            )
        )
        if e1_handoff.get("status") != "PASS":
            raise AssertionError(e1_handoff)
        if e1_handoff.get("nextStage") != "V2-D.2-E2":
            raise AssertionError(e1_handoff)

        output = temp / "repeatability"
        args = [
            "scripts/compare_v2d2_evidence.py",
            "--run",
            str(runs[0]),
            "--run",
            str(runs[1]),
            "--run",
            str(runs[2]),
            "--output-dir",
            str(output),
        ]
        run(*args)
        report = json.loads(
            (output / "repeatability-report.json").read_text(
                encoding="utf-8"
            )
        )
        if report.get("status") != "REPORT_ONLY":
            raise AssertionError(report)
        if not report.get("matrixVariability"):
            raise AssertionError(
                "Repeatability report lost full matrix variability"
            )

        threshold_output = temp / "threshold-pass"
        run(
            *args[:-1],
            str(threshold_output),
            "--max-matrix-score-cv-percent",
            "5",
            "--max-matrix-allocation-cv-percent",
            "5",
            "--max-soak-throughput-cv-percent",
            "5",
            "--max-soak-p99-cv-percent",
            "5",
        )
        threshold_report = json.loads(
            (threshold_output / "repeatability-report.json").read_text(
                encoding="utf-8"
            )
        )
        if threshold_report.get("status") != "PASS":
            raise AssertionError(threshold_report)

        candidate_output = temp / "baseline-candidate"
        run(
            "scripts/promote_v2d2_baseline.py",
            "--repeatability-report",
            str(threshold_output / "repeatability-report.json"),
            "--output-dir",
            str(candidate_output),
        )
        candidate = json.loads(
            (candidate_output / "baseline-candidate.json").read_text(
                encoding="utf-8"
            )
        )
        if candidate.get("status") != "CANDIDATE":
            raise AssertionError(candidate)

        baseline_path = temp / "runner-baseline.json"
        run(
            "scripts/check_v2d2_runner_baseline.py",
            "--environment",
            str(runs[0] / "environment.properties"),
            "--baseline",
            str(baseline_path),
            "--initialize-if-missing",
        )
        run(
            "scripts/check_v2d2_runner_baseline.py",
            "--environment",
            str(runs[1] / "environment.properties"),
            "--baseline",
            str(baseline_path),
        )

        manifest_bundle = temp / "manifest-bundle"
        write_bundle(manifest_bundle, 1.0)
        run(
            "scripts/manage_v2d2_evidence_manifest.py",
            "create",
            "--bundle",
            str(manifest_bundle),
        )
        run(
            "scripts/manage_v2d2_evidence_manifest.py",
            "verify",
            "--bundle",
            str(manifest_bundle),
        )
        (manifest_bundle / "soak.json").write_text(
            "{}",
            encoding="utf-8",
        )
        run(
            "scripts/manage_v2d2_evidence_manifest.py",
            "verify",
            "--bundle",
            str(manifest_bundle),
            expect_success=False,
        )

        host_mismatch = temp / "run-host-mismatch"
        write_bundle(
            host_mismatch,
            1.0,
            host_fingerprint="host-fingerprint-b",
        )
        run(
            "scripts/check_v2d2_runner_baseline.py",
            "--environment",
            str(host_mismatch / "environment.properties"),
            "--baseline",
            str(baseline_path),
            expect_success=False,
        )
        run(
            "scripts/compare_v2d2_evidence.py",
            "--run",
            str(runs[0]),
            "--run",
            str(runs[1]),
            "--run",
            str(host_mismatch),
            "--output-dir",
            str(temp / "host-mismatch-output"),
            expect_success=False,
        )

        mismatch = temp / "run-mismatch"
        write_bundle(mismatch, 1.0, commit="different-commit")
        # Same runner and JVM must support A/B commits without changing hardware baseline.
        run(
            "scripts/check_v2d2_runner_baseline.py",
            "--environment",
            str(mismatch / "environment.properties"),
            "--baseline",
            str(baseline_path),
        )
        run(
            "scripts/compare_v2d2_evidence.py",
            "--run",
            str(runs[0]),
            "--run",
            str(runs[1]),
            "--run",
            str(mismatch),
            "--output-dir",
            str(temp / "mismatch-output"),
            expect_success=False,
        )

    print("V2-D.2 evidence tooling self-test passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
