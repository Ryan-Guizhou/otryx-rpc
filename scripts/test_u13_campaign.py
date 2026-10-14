#!/usr/bin/env python3
"""Negative fixtures for U13 fixed-runner AB/BA evidence acceptance."""

from __future__ import annotations

import hashlib
import json
import tempfile
from pathlib import Path

from check_u13_campaign import validate_campaign
from manage_v2d2_evidence_manifest import create

BASE = "a" * 40
CANDIDATE = "b" * 40
HOST = "c" * 64
ORDER = ("baseline", "candidate", "candidate", "baseline", "baseline", "candidate")


def write_json(path: Path, data: dict) -> None:
    """Write one synthetic fixture file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data) + "\n", encoding="utf-8")


def make_bundle(path: Path, run_id: str, commit: str, host: str = HOST) -> None:
    """Build structurally consistent, explicitly synthetic controlled evidence."""
    path.mkdir(parents=True)
    (path / "environment.properties").write_text(
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
            make_bundle(path, f"run-{index + 1}", sha)
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

        policy = second / "soak-policy" / "report.json"
        saved = json.loads(policy.read_text(encoding="utf-8"))
        saved["soakSha256"] = "0" * 64
        write_json(policy, saved)
        create(second)
        rejected(lambda: validate_campaign(data, root))

    print("U13 AB/BA campaign synthetic validation fixtures passed")


if __name__ == "__main__":
    main()
