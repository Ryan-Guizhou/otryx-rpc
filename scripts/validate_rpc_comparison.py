#!/usr/bin/env python3
"""Validate isolated OTRYX/Dubbo RPC result parity without inventing missing metrics."""

import argparse
import json
import math
from pathlib import Path
import re

REQUIRED_NUMERIC = (
    "concurrency", "payload_bytes", "warmup_seconds", "measurement_seconds",
    "attempts", "success", "errors", "error_rate", "throughput_qps",
    "client_gc_count_delta", "client_gc_millis_delta",
    "client_heap_start_bytes", "client_heap_end_bytes", "latency_samples",
)
SCENARIO_FIELDS = (
    "run_id", "git_sha", "concurrency", "payload_bytes",
    "warmup_seconds", "measurement_seconds", "workload",
)


def read_json(path):
    with open(path, encoding="utf-8") as stream:
        return json.load(stream)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_result(data, framework):
    require(data.get("schema") == "otryx.rpc.comparison.v1",
            f"{framework}: unsupported comparison schema")
    require(data.get("framework") == framework,
            f"{framework}: wrong framework field")
    require(data.get("workload") == "sync-byte-array-echo-closed-loop",
            f"{framework}: unexpected workload; fair comparison requires same semantics")
    require(data.get("protocol") and data.get("serializer"),
            f"{framework}: missing protocol/serializer provenance")
    require(data.get("evidence_class") in ("smoke", "controlled"),
            f"{framework}: invalid provenance class")
    for name in REQUIRED_NUMERIC:
        value = data.get(name)
        require(isinstance(value, (int, float)) and not isinstance(value, bool)
                and math.isfinite(value) and value >= 0,
                f"{framework}: invalid numeric field {name}")
    require(isinstance(data["concurrency"], int) and data["concurrency"] > 0,
            f"{framework}: invalid concurrency")
    require(isinstance(data["payload_bytes"], int),
            f"{framework}: payload bytes must be integer")
    require(data["warmup_seconds"] > 0 and data["measurement_seconds"] > 0,
            f"{framework}: warmup/measurement must be positive")
    require(data["attempts"] == data["success"] + data["errors"],
            f"{framework}: inconsistent request counts")
    require(data["success"] > 0, f"{framework}: no successful RPC requests")
    require(data["latency_samples"] > 0
            and data["latency_samples"] <= data["success"],
            f"{framework}: missing or invalid success latency samples")
    require(abs(data["error_rate"] - data["errors"] / data["attempts"]) < 1e-9,
            f"{framework}: error rate does not match request counts")
    require(data["error_rate"] <= 1, f"{framework}: error rate must not exceed 1")
    for name in ("p50_us", "p99_us", "p999_us"):
        value = data.get(name)
        require(isinstance(value, (int, float)) and not isinstance(value, bool)
                and math.isfinite(value) and value >= 0,
                f"{framework}: {name} is not a nonnegative finite value")
    require(data["p50_us"] <= data["p99_us"] <= data["p999_us"],
            f"{framework}: inconsistent latency percentiles")
    by_type = data.get("errors_by_type")
    require(isinstance(by_type, dict) and
            all(isinstance(k, str) and isinstance(v, int) and v >= 0
                for k, v in by_type.items()),
            f"{framework}: invalid error type breakdown")
    require(sum(by_type.values()) == data["errors"],
            f"{framework}: error breakdown does not reconcile")
    require("allocation_bytes_per_op" in data
            and "server_cpu_cores" in data,
            f"{framework}: missing explicit unmeasured-metric markers")


def validate_pair(otryx, dubbo, environment, max_error_rate=0.05):
    validate_result(otryx, "otryx")
    validate_result(dubbo, "dubbo")
    for field in SCENARIO_FIELDS:
        require(otryx.get(field) == dubbo.get(field),
                f"Scenario mismatch: {field}")
    require(otryx["evidence_class"] == dubbo["evidence_class"],
            "Mismatched evidence provenance class")
    require(environment.get("schema") ==
            "otryx.rpc.comparison.environment.v1",
            "Environment provenance record is missing")
    require(environment.get("otryx_registry_backend") == "etcd",
            "OTRYX comparison requires shared Etcd application-registry provenance")
    namespace = environment.get("otryx_registry_namespace")
    require(isinstance(namespace, str) and re.fullmatch(r"[A-Za-z0-9._-]{1,128}", namespace),
            "OTRYX comparison requires an isolated application namespace")
    require(environment.get("registry_traffic_measurement") == "not-measured",
            "Registry traffic must remain explicitly not-measured in this smoke")
    for field in ("run_id", "git_sha", "evidence_class"):
        require(environment.get(field) == otryx.get(field),
                f"Environment mismatch: {field}")
    require(otryx.get("git_sha") not in ("", "unverified", None),
            "Evidence must identify a real source revision")
    require(all(data["error_rate"] <= max_error_rate
                for data in (otryx, dubbo)),
            f"Comparison error rate exceeded {max_error_rate}")
    if otryx["evidence_class"] == "controlled":
        require(environment.get("physical_host_fingerprint")
                and environment.get("runner_id"),
                "Controlled evidence requires fixed runner identity and fingerprint")
        raise ValueError(
            "A single controlled pair cannot establish official performance claims; "
            "collect >=3 independent repeats and controlled soak/JFR evidence")


def markdown(otryx, dubbo, environment):
    provenance = otryx["evidence_class"]
    header = ("SMOKE ONLY - NOT OFFICIAL PERFORMANCE EVIDENCE"
              if provenance == "smoke" else
              "CONTROLLED CANDIDATE - AWAITING INDEPENDENT REVIEW")
    values = [
        f"# OTRYX RPC / Dubbo comparison — {header}",
        "",
        f"Source SHA: `{otryx['git_sha']}`. Run: `{otryx['run_id']}`.",
        f"Runner: `{environment.get('runner_id', 'unknown')}`.",
        f"OTRYX control plane: shared Etcd, namespace `{environment['otryx_registry_namespace']}`.",
        "",
        "| Metric | OTRYX RPC | Apache Dubbo |",
        "|---|---:|---:|",
    ]
    fields = (
        ("Protocol", "protocol"),
        ("Serialization", "serializer"),
        ("Concurrency", "concurrency"),
        ("Payload bytes", "payload_bytes"),
        ("Warmup seconds", "warmup_seconds"),
        ("Measurement seconds", "measurement_seconds"),
        ("Success", "success"),
        ("Errors", "errors"),
        ("Error rate", "error_rate"),
        ("QPS", "throughput_qps"),
        ("p50 (us)", "p50_us"),
        ("p99 (us)", "p99_us"),
        ("p99.9 (us)", "p999_us"),
        ("Client CPU cores (estimate)", "client_process_cpu_cores"),
        ("Client GC millis delta", "client_gc_millis_delta"),
        ("Allocation B/op", "allocation_bytes_per_op"),
        ("Server CPU cores", "server_cpu_cores"),
    )
    for label, field in fields:
        def render(value):
            if value is None:
                return "not measured"
            if isinstance(value, float):
                return f"{value:.5f}"
            return str(value)
        values.append(f"| {label} | {render(otryx.get(field))} "
                      f"| {render(dubbo.get(field))} |")
    values.extend([
        "",
        "Both runtimes used separate provider and client JVMs; "
        "the reported workload is **closed-loop synchronous byte[] echo**.",
        "OTRYX discovers application members through shared Etcd before warmup; "
        "Dubbo uses a direct URL. Backend HTTP, watches and keepalive traffic are not measured.",
        "The protocols and serializers are different (OTRYX Wire v1/Fory vs "
        "Dubbo TCP/Hessian2), so results measure the complete runtime stacks, "
        "not isolated transport efficiency.",
        "Missing allocation/server CPU figures are deliberately marked "
        "as unmeasured. No fixed-host or p99 superiority claim is inferred "
        "from a smoke run.",
        "",
    ])
    return "\n".join(values)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--otryx", required=True)
    parser.add_argument("--dubbo", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--max-error-rate", type=float, default=0.05)
    args = parser.parse_args()
    try:
        require(0 <= args.max_error_rate <= 1, "Invalid max error rate")
        otryx, dubbo, env = (
            read_json(args.otryx), read_json(args.dubbo),
            read_json(args.environment))
        validate_pair(otryx, dubbo, env, args.max_error_rate)
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(markdown(otryx, dubbo, env),
                                     encoding="utf-8")
        print(f"Validated smoke comparison evidence: {args.report}")
    except (ValueError, OSError, json.JSONDecodeError) as error:
        parser.exit(1, f"Invalid comparison evidence: {error}\n")


if __name__ == "__main__":
    main()
