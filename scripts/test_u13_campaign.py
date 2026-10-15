#!/usr/bin/env python3
"""Negative fixtures for U13 fixed-runner AB/BA evidence acceptance."""

from __future__ import annotations

import hashlib
import json
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from check_u13_campaign import validate_campaign
from manage_v2d2_evidence_manifest import create

BASE = "a" * 40
CANDIDATE = "b" * 40
HOST = "c" * 64
ORDER = ("baseline", "candidate", "candidate", "baseline", "baseline", "candidate")
START = datetime(2026, 10, 14, tzinfo=timezone.utc)


def iso(value: datetime) -> str:
    """Write real-run-compatible UTC timestamps for synthetic fixtures."""
    return value.isoformat().replace("+00:00", "Z")


def write_json(path: Path, data: dict) -> None:
    """Write one synthetic fixture file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data) + "\n", encoding="utf-8")


def make_bundle(path: Path, run_id: str, commit: str, captured_at: datetime,
                host: str = HOST) -> None:
    """Build structurally consistent, explicitly synthetic controlled evidence."""
    path.mkdir(parents=True)
    completed_at = captured_at + timedelta(hours=1, seconds=20)
    (path / "preflight.properties").write_text(
        f"schema_version=1\nstatus=PASS\ncaptured_at={iso(captured_at - timedelta(minutes=1))}\n"
        f"commit={commit}\nevidence_class=controlled\nrun_id={run_id}\n"
        f"runner_id=otryx-perf-fixture\nhost_fingerprint_sha256={host}\n"
        "java_major=21\njvm_flags=-Xms4g -Xmx4g\n",
        encoding="utf-8",
    )
    (path / "environment.properties").write_text(
        f"captured_at={iso(captured_at)}\n"
        f"evidence_class=controlled\ncommit={commit}\nrunner_id=otryx-perf-fixture\n"
        f"host_fingerprint_sha256={host}\nrun_id={run_id}\n"
        "cpu_model=fixture-cpu\nlogical_cores=16\nphysical_cores=8\n"
        "threads_per_core=2\nnuma_nodes=1\nmemory_bytes=34359738368\n"
        "cpu_governor=performance\nkernel=Linux fixture\njava=OpenJDK 21\n"
        "jvm_flags=-Xms4g -Xmx4g\ncontainerized=false\n",
        encoding="utf-8",
    )
    (path / "matrix").mkdir()
    (path / "matrix" / "summary.csv").write_text(
        "family,benchmark\npayload,echo\n", encoding="utf-8"
    )
    soak = {
        "commit": commit,
        "completedAt": iso(completed_at),
        "concurrency": 10000,
        "payloadBytes": 256,
        "connectionsPerEndpoint": 4,
        "durationSeconds": 3600.0,
        "throughputOpsPerSecond": 50000.0,
        "successes": 100000,
        "errors": 0,
        "errorRate": 0.0,
        "maxLogicalInflight": 1000,
        "p50Micros": 100.0,
        "p99Micros": 200.0,
        "p999Micros": 300.0,
        "gcCountDelta": 0,
        "gcTimeMillisDelta": 0,
        "processCpuCoresAverage": 4.0,
        "errorsByType": {},
    }
    write_json(path / "soak.json", soak)
    write_json(path / "validation-report.json", {"status": "PASS"})
    write_json(path / "decision-inputs.json", {"evidenceClass": "controlled"})
    write_json(path / "soak-policy" / "report.json", {
        "status": "POLICY_PASS",
        "soakSha256": hashlib.sha256((path / "soak.json").read_bytes()).hexdigest(),
        "policy": {
            "maxErrorRate": 0.01,
            "minObservedLogicalInflight": 500,
            "maxP99Micros": 500.0,
        },
    })
    create(path)


def rejected(action) -> None:
    """Assert fail-closed behavior for invalid or incomplete provenance."""
    try:
        action()
    except (ValueError, OSError, KeyError):
        return
    raise AssertionError("U13 accepted invalid evidence")


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="otryx-u13-fixtures-") as temporary:
        root = Path(temporary)
        data = {
            "schemaVersion": 1,
            "baselineSha": BASE,
            "candidateSha": CANDIDATE,
            "runs": [],
        }
        for index, side in enumerate(ORDER):
            path = root / f"run-{index + 1}"
            sha = BASE if side == "baseline" else CANDIDATE
            make_bundle(path, f"run-{index + 1}", sha,
                        START + timedelta(hours=2 * index))
            data["runs"].append({"side": side, "path": path.name})
        report = validate_campaign(data, root)
        assert report["status"] == "REPORT_ONLY"
        assert report["integrity"] == "PASS"
        assert report["productionSlo"] == "NOT_ESTABLISHED"
        assert report["metrics"]["baseline"]["samples"] == 3
        assert report["metrics"]["candidate"]["samples"] == 3

        bad_order = dict(data, runs=list(data["runs"]))
        bad_order["runs"][0] = {"side": "candidate", "path": "run-1"}
        rejected(lambda: validate_campaign(bad_order, root))
        swapped_same_side = dict(data, runs=list(data["runs"]))
        swapped_same_side["runs"][1], swapped_same_side["runs"][2] = (
            swapped_same_side["runs"][2], swapped_same_side["runs"][1]
        )
        rejected(lambda: validate_campaign(swapped_same_side, root))
        rejected(lambda: validate_campaign(dict(data, candidateSha=BASE), root))
        rejected(lambda: validate_campaign(dict(data, runs=data["runs"][:-1]), root))

        first = root / "run-1"
        (first / "matrix" / "summary.csv").write_text("tampered", encoding="utf-8")
        rejected(lambda: validate_campaign(data, root))
        (first / "matrix" / "summary.csv").write_text(
            "family,benchmark\npayload,echo\n", encoding="utf-8"
        )
        create(first)
        old = json.loads((first / "soak.json").read_text(encoding="utf-8"))
        old["durationSeconds"] = 1800.0
        write_json(first / "soak.json", old)
        create(first)
        rejected(lambda: validate_campaign(data, root))
        old["durationSeconds"] = 3600.0
        write_json(first / "soak.json", old)
        create(first)

        second = root / "run-2"
        env = (second / "environment.properties").read_text(encoding="utf-8")
        (second / "environment.properties").write_text(
            env.replace(HOST, "d" * 64), encoding="utf-8"
        )
        create(second)
        rejected(lambda: validate_campaign(data, root))
        (second / "environment.properties").write_text(env, encoding="utf-8")
        create(second)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        initial_environment = (first / "environment.properties").read_text(encoding="utf-8")
        (first / "environment.properties").write_text(
            initial_environment.replace("java=OpenJDK 21",
                                        'java=openjdk version "17.0.21"'),
            encoding="utf-8",
        )
        create(first)
        rejected(lambda: validate_campaign(data, root))
        (first / "environment.properties").write_text(initial_environment, encoding="utf-8")
        create(first)

        preflight = first / "preflight.properties"
        original_preflight = preflight.read_text(encoding="utf-8")
        preflight.write_text(original_preflight.replace("java_major=21", "java_major=17"),
                             encoding="utf-8")
        create(first)
        rejected(lambda: validate_campaign(data, root))
        preflight.write_text(original_preflight, encoding="utf-8")
        create(first)

        original_soak = json.loads((first / "soak.json").read_text(encoding="utf-8"))
        incomplete_soak = dict(original_soak, completedAt="invalid-timestamp")
        write_json(first / "soak.json", incomplete_soak)
        create(first)
        rejected(lambda: validate_campaign(data, root))
        write_json(first / "soak.json", original_soak)
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        policy = second / "soak-policy" / "report.json"
        saved = json.loads(policy.read_text(encoding="utf-8"))
        saved["soakSha256"] = "0" * 64
        write_json(policy, saved)
        create(second)
        rejected(lambda: validate_campaign(data, root))

    print("U13 AB/BA campaign synthetic validation fixtures passed")


if __name__ == "__main__":
    main()
