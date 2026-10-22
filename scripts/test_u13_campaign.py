#!/usr/bin/env python3
"""Negative fixtures for U13 fixed-runner AB/BA evidence acceptance."""

from __future__ import annotations

import hashlib
import json
import tempfile
from itertools import product
from datetime import datetime, timedelta, timezone
from pathlib import Path

from check_u13_campaign import validate_campaign
from manage_v2d2_evidence_manifest import create
from summarize_v2d2_results import load_rows, write_csv
from validate_v2d2_evidence import PROFILES, RESILIENCE

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


def write_full_matrix(path: Path) -> None:
    """Populate the complete synthetic JMH full profile for validation."""
    profile = PROFILES["full"]
    raw: dict[str, list[dict]] = {}

    def add(family: str, **changes: object) -> None:
        item = {
            "family": family, "benchmark": "synthetic-fixture",
            "scenario": "-", "security": "N/A", "mode": "sample",
            "payload_bytes": 0, "connections": 0, "threads": 1,
            "alloc_b_op": 128,
        }
        item.update(changes)
        mode = item["mode"]
        threads = item["threads"]
        connections = item["connections"]
        if family == "payload":
            filename = (
                f"{mode}-p{item['payload_bytes']}-c{connections}-t{threads}.json"
            )
        elif family == "scenario":
            filename = (
                f"scenario-{item['scenario']}-{mode}-c{connections}-t{threads}.json"
            )
        elif family == "security":
            filename = (
                f"security-{item['security']}-{mode}-p{item['payload_bytes']}"
                f"-c{connections}-t{threads}.json"
            )
        else:
            filename = f"resilience-{mode}-t{threads}.json"
        raw.setdefault(filename, []).append({
            "benchmark": f"synthetic.{item['benchmark']}",
            "primaryMetric": {
                "score": 1.0,
                "scoreUnit": "us/op",
                "scorePercentiles": {
                    "50.0": 1.0, "99.0": 2.0, "99.9": 3.0
                },
            },
            "secondaryMetrics": {
                "gc.alloc.rate.norm": {"score": 128.0},
            },
        })

    for mode, size, connections, threads in product(
            profile["modes"], profile["payloads"],
            profile["shards"], profile["threads"]):
        add("payload", mode=mode, payload_bytes=size,
            connections=connections, threads=threads)
    for scenario, mode, connections, threads in product(
            profile["scenarios"], profile["modes"],
            profile["scenario_shards"], profile["scenario_threads"]):
        add("scenario", scenario=scenario, mode=mode,
            connections=connections, threads=threads)
    for security, mode, size, connections, threads in product(
            profile["security_modes"], profile["modes"],
            profile["security_payloads"], profile["security_shards"],
            profile["security_threads"]):
        add("security", security=security, mode=mode, payload_bytes=size,
            connections=connections, threads=threads)
    for benchmark, mode, threads in product(
            RESILIENCE, profile["modes"], profile["resilience_threads"]):
        add("resilience", benchmark=benchmark, mode=mode, threads=threads)
    path.parent.mkdir(parents=True, exist_ok=True)
    for filename, results in raw.items():
        (path.parent / filename).write_text(
            json.dumps(results) + "\n", encoding="utf-8"
        )
    write_csv(path.parent, load_rows(path.parent))


def make_bundle(path: Path, run_id: str, commit: str, captured_at: datetime,
                host: str = HOST) -> None:
    """Build structurally consistent, explicitly synthetic controlled evidence."""
    path.mkdir(parents=True)
    completed_at = captured_at + timedelta(hours=1, minutes=3)
    (path / "preflight.properties").write_text(
        f"schema_version=1\nstatus=PASS\ncaptured_at={iso(captured_at - timedelta(minutes=1))}\n"
        f"commit={commit}\nevidence_class=controlled\nrun_id={run_id}\n"
        f"runner_id=otryx-perf-fixture\nhost_fingerprint_sha256={host}\n"
        "java_major=21\njvm_flags=-Xms4g -Xmx4g\n"
        "soak_concurrency=10000\nsoak_payload_size=256\nsoak_connections=4\n"
        "soak_warmup_seconds=30\nsoak_duration_seconds=3600\n"
        "soak_client_timeout_ms=15000\nsoak_max_error_rate=0.01\n"
        "soak_min_observed_inflight=500\nsoak_max_p99_micros=500.0\n",
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
    write_full_matrix(path / "matrix" / "summary.csv")
    root_environment = (path / "environment.properties").read_text(encoding="utf-8")
    matrix_environment = root_environment.replace(
        f"captured_at={iso(captured_at)}",
        f"captured_at={iso(captured_at + timedelta(minutes=2))}",
    )
    (path / "matrix" / "environment.properties").write_text(
        matrix_environment + "profile=full\n", encoding="utf-8"
    )
    soak = {
        "commit": commit,
        "runnerId": "otryx-perf-fixture",
        "runId": run_id,
        "hostFingerprintSha256": host,
        "jvmFlags": "-Xms4g -Xmx4g",
        "javaVersion": "21.0.9",
        "jvmArguments": "-Xms4g -Xmx4g",
        "vmName": "OpenJDK 64-Bit Server VM",
        "os": "Linux amd64",
        "availableProcessors": 16,
        "completedAt": iso(completed_at),
        "concurrency": 10000,
        "payloadBytes": 256,
        "connectionsPerEndpoint": 4,
        "warmupSeconds": 30,
        "requestedDurationSeconds": 3600,
        "clientTimeoutMillis": 15000,
        "durationSeconds": 3600.0,
        "throughputOpsPerSecond": 50000.0,
        "successes": 180000000,
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
    write_json(path / "decision-inputs.json", {
        "evidenceClass": "controlled", "commit": commit,
        "runnerId": "otryx-perf-fixture",
    })
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
    workflow_path = (
        Path(__file__).resolve().parents[1]
        / ".github" / "workflows" / "performance-evidence.yml"
    )
    workflow = workflow_path.read_text(encoding="utf-8")
    matrix_step = workflow.split("      - name: Run JMH matrix\n", 1)[1].split(
        "      - name: Run virtual-thread soak\n", 1
    )[0]
    for mapping in (
            "OTRYX_RPC_EVIDENCE_CLASS: ${{ inputs.evidence_class }}",
            "OTRYX_RPC_EVIDENCE_RUN_ID: ${{ inputs.run_id }}",
            "OTRYX_RPC_RUNNER_ID: ${{ runner.name }}",
            "OTRYX_RPC_RUNNER_LABELS: ${{ inputs.runner_labels_json }}"):
        assert mapping in matrix_step, f"JMH matrix workflow lost provenance: {mapping}"
    guard_step = workflow.split("      - name: Guard controlled evidence\n", 1)[1].split(
        "      - uses: actions/checkout@", 1
    )[0]
    assert "RUNNER_ENVIRONMENT: ${{ runner.environment }}" in guard_step
    assert '"$RUNNER_ENVIRONMENT" != "self-hosted"' in guard_step
    preflight_step = workflow.split("      - name: Preflight controlled runner\n", 1)[1].split(
        "      - name: Capture evidence environment\n", 1
    )[0]
    soak_step = workflow.split("      - name: Run virtual-thread soak\n", 1)[1].split(
        "      - name: Enforce predeclared controlled soak policy\n", 1
    )[0]
    for mapping in (
            "OTRYX_RPC_RUNNER_ID: ${{ runner.name }}",
            "OTRYX_RPC_EVIDENCE_RUN_ID: ${{ inputs.run_id }}",
            "OTRYX_RPC_HOST_FINGERPRINT_SHA256: ${{ inputs.host_fingerprint_sha256 }}"):
        assert mapping in soak_step, f"Soak lost runner identity: {mapping}"
    for mapping in (
            "OTRYX_RPC_SOAK_MAX_ERROR_RATE: ${{ inputs.soak_max_error_rate }}",
            "OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT: ${{ inputs.soak_min_observed_inflight }}",
            "OTRYX_RPC_SOAK_MAX_P99_MICROS: ${{ inputs.soak_max_p99_micros }}"):
        assert mapping in preflight_step, f"Preflight lost policy threshold: {mapping}"

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
        raw_path = first / "matrix" / "sample-p64-c1-t1.json"
        saved_raw = raw_path.read_text(encoding="utf-8")
        raw_path.unlink()
        create(first)
        rejected(lambda: validate_campaign(data, root))
        raw_path.write_text(saved_raw, encoding="utf-8")
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        matrix_path = first / "matrix" / "summary.csv"
        matrix_path.write_text("tampered", encoding="utf-8")
        rejected(lambda: validate_campaign(data, root))
        write_full_matrix(matrix_path)
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        # Re-signing an incomplete matrix must not let a fake PASS report through.
        matrix_path.write_text(
            "family,benchmark,scenario,security,mode,payload_bytes,"
            "connections,threads,alloc_b_op\n"
            "payload,echo,-,N/A,sample,256,1,1,128\n",
            encoding="utf-8",
        )
        create(first)
        rejected(lambda: validate_campaign(data, root))
        write_full_matrix(matrix_path)
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"
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

        # Removing a predeclared p99 ceiling must never yield POLICY_PASS.
        preflight = second / "preflight.properties"
        saved_preflight = preflight.read_text(encoding="utf-8")
        assert "soak_max_p99_micros=500.0" in saved_preflight
        preflight.write_text(
            saved_preflight.replace("soak_max_p99_micros=500.0",
                                    "soak_max_p99_micros="), encoding="utf-8"
        )
        create(second)
        rejected(lambda: validate_campaign(data, root))
        preflight.write_text(saved_preflight, encoding="utf-8")
        create(second)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        # A copied JMH matrix from another build must fail even with a new manifest.
        matrix_environment_path = second / "matrix" / "environment.properties"
        original_matrix_environment = matrix_environment_path.read_text(encoding="utf-8")
        for expected, incorrect in (
                (f"commit={CANDIDATE}", f"commit={BASE}"),
                (f"host_fingerprint_sha256={HOST}",
                 f"host_fingerprint_sha256={'d' * 64}"),
                ("runner_id=otryx-perf-fixture", "runner_id=wrong-runner"),
                ("jvm_flags=-Xms4g -Xmx4g", "jvm_flags=-Xms2g -Xmx2g"),
                ("profile=full", "profile=standard")):
            assert expected in original_matrix_environment
            matrix_environment_path.write_text(
                original_matrix_environment.replace(expected, incorrect),
                encoding="utf-8",
            )
            create(second)
            rejected(lambda: validate_campaign(data, root))
        matrix_environment_path.write_text(
            original_matrix_environment, encoding="utf-8"
        )
        create(second)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        # Re-signing a fabricated throughput report must not bias A/B medians.
        soak_path = first / "soak.json"
        saved_soak = json.loads(soak_path.read_text(encoding="utf-8"))
        first_policy_path = first / "soak-policy" / "report.json"
        original_policy = json.loads(first_policy_path.read_text(encoding="utf-8"))
        write_json(soak_path, dict(
            saved_soak, throughputOpsPerSecond=100000.0
        ))
        write_json(first_policy_path, dict(
            original_policy, soakSha256=hashlib.sha256(soak_path.read_bytes()).hexdigest()
        ))
        create(first)
        rejected(lambda: validate_campaign(data, root))
        write_json(soak_path, saved_soak)
        write_json(first_policy_path, original_policy)
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        # Even correctly hashed data cannot claim more soak time than elapsed.
        write_json(soak_path, dict(
            saved_soak, completedAt=iso(START + timedelta(minutes=59))
        ))
        write_json(first_policy_path, dict(
            original_policy, soakSha256=hashlib.sha256(soak_path.read_bytes()).hexdigest()
        ))
        create(first)
        rejected(lambda: validate_campaign(data, root))
        write_json(soak_path, saved_soak)
        write_json(first_policy_path, original_policy)
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        # A copied soak from a different runner cannot be re-signed as local.
        for field, incorrect in (
                ("runnerId", "foreign-runner"),
                ("runId", "wrong-run"),
                ("hostFingerprintSha256", "d" * 64),
                ("jvmFlags", "-Xmx1g"),
                ("javaVersion", "17.0.21")):
            forged_soak = dict(saved_soak, **{field: incorrect})
            write_json(soak_path, forged_soak)
            write_json(first_policy_path, dict(
                original_policy,
                soakSha256=hashlib.sha256(soak_path.read_bytes()).hexdigest()
            ))
            create(first)
            rejected(lambda: validate_campaign(data, root))
        write_json(soak_path, saved_soak)
        write_json(first_policy_path, original_policy)
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        # Effective JVM arguments and allocated processors must match A/B peers.
        for field, incorrect in (
                ("availableProcessors", 999),
                ("jvmArguments", "-XX:ActiveProcessorCount=999"),
                ("vmName", "Other VM"),
                ("os", "Other OS")):
            write_json(soak_path, dict(saved_soak, **{field: incorrect}))
            write_json(first_policy_path, dict(
                original_policy,
                soakSha256=hashlib.sha256(soak_path.read_bytes()).hexdigest()
            ))
            create(first)
            rejected(lambda: validate_campaign(data, root))
        write_json(soak_path, saved_soak)
        write_json(first_policy_path, original_policy)
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        # Reject any changed workload, including identical values in policy.
        for field, incorrect in (
                ("concurrency", 20000),
                ("payloadBytes", 1048576),
                ("connectionsPerEndpoint", 8),
                ("requestedDurationSeconds", 3700),
                ("clientTimeoutMillis", 20000)):
            forged_soak = dict(saved_soak, **{field: incorrect})
            write_json(soak_path, forged_soak)
            write_json(first_policy_path, dict(
                original_policy,
                soakSha256=hashlib.sha256(soak_path.read_bytes()).hexdigest()
            ))
            create(first)
            rejected(lambda: validate_campaign(data, root))
        write_json(soak_path, saved_soak)
        write_json(first_policy_path, original_policy)
        create(first)
        assert validate_campaign(data, root)["integrity"] == "PASS"

        # Changing post-soak thresholds cannot override the predeclared policy.
        modified_policy = dict(original_policy, policy=dict(
            original_policy["policy"], maxErrorRate=0.5
        ))
        write_json(first_policy_path, modified_policy)
        create(first)
        rejected(lambda: validate_campaign(data, root))
        write_json(first_policy_path, original_policy)
        create(first)
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
