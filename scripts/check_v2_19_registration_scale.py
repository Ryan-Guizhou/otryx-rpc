#!/usr/bin/env python3
"""Validate measured V2 application-registration control-plane scale, never invent a baseline.

This comparator is REPORT_ONLY. It refuses hypothetical interfaceCount * instanceCount
calculations as substitutes for actual backend measurements.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

SHA = re.compile(r"[0-9a-f]{40}\Z")
HOST = re.compile(r"[0-9a-f]{64}\Z")
BACKENDS = {"etcd", "nacos", "consul", "eureka", "memory"}
METRICS = ("nativeMembers", "activeSubscriptions", "watchEvents",
           "consumerRssBytes", "recoveryMillis")
MIN_REPETITIONS = 3
MIN_SAMPLE_SECONDS = 60.0


def require(value: bool, reason: str) -> None:
    """Fail without returning a misleading performance result."""
    if not value:
        raise ValueError(reason)


def integer(value: object, minimum: int = 0) -> bool:
    """Reject booleans and negative/broken record counts."""
    return type(value) is int and value >= minimum


def number(value: object, minimum: float = 0.0) -> bool:
    """Accept only finite measurements; reject strings and booleans."""
    return type(value) in (int, float) and math.isfinite(value) and value >= minimum


def iso(value: object) -> datetime:
    """Parse a timezone-aware measurement timestamp."""
    require(isinstance(value, str), "capturedAt must be a timestamp")
    try:
        ts = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("capturedAt must be ISO-8601") from error
    require(ts.tzinfo is not None and ts.utcoffset() is not None,
            "capturedAt must have a timezone")
    return ts


def validate_side(data: dict, model: str) -> dict:
    """Validate one full measured campaign and its repeating observations."""
    require(isinstance(data, dict), "Campaign must be a JSON object")
    require(data.get("registryModel") == model, "Wrong registry model: " + model)
    require(isinstance(data.get("commit"), str)
            and SHA.fullmatch(data["commit"]) is not None,
            "Missing exact source SHA")
    require(isinstance(data.get("hostFingerprintSha256"), str)
            and HOST.fullmatch(data["hostFingerprintSha256"]) is not None,
            "Missing fixed host fingerprint")
    require(data.get("backend") in BACKENDS, "Unknown backend type")
    require(integer(data.get("jdkMajor")) and data["jdkMajor"] == 21,
            "JDK 21 must be recorded")
    require(isinstance(data.get("jvmFlags"), str) and bool(data["jvmFlags"].strip()),
            "Explicit JVM flags are required")
    require(integer(data.get("interfaces"), 1) and integer(data.get("instances"), 1),
            "Positive interface and instance dimensions are required")
    require(isinstance(data.get("workloadId"), str)
            and bool(data["workloadId"].strip()) and len(data["workloadId"]) <= 128,
            "A bounded workload ID is required")
    runs = data.get("runs")
    require(isinstance(runs, list) and len(runs) >= MIN_REPETITIONS,
            "At least three real repeated measurements are required")
    seen = set()
    for run in runs:
        require(isinstance(run, dict), "Each run must be a JSON object")
        run_id = run.get("runId")
        require(isinstance(run_id, str) and 0 < len(run_id) <= 128
                and run_id not in seen, "Unique nonempty runId required")
        seen.add(run_id)
        iso(run.get("capturedAt"))
        require(number(run.get("sampleSeconds"), MIN_SAMPLE_SECONDS),
                "Observation window shorter than 60 seconds")
        require(run.get("origin") == "backend-native",
                "Only native backend measurements can establish registry scale")
        for metric in METRICS:
            minimum = 1 if metric in ("nativeMembers", "consumerRssBytes") else 0
            require(number(run.get(metric), minimum),
                    "Missing/non-finite measured field: " + metric)
        require(integer(run["nativeMembers"], 1)
                and integer(run["activeSubscriptions"])
                and integer(run["watchEvents"]), "Native counters must be integers")
    return data


def compare(base: dict, candidate: dict) -> dict:
    """Compare repeated native observations from one host with the same workload."""
    base = validate_side(base, "interface")
    candidate = validate_side(candidate, "application")
    require(base["commit"] != candidate["commit"],
            "Legacy and application versions must use distinct commits")
    for key in ("hostFingerprintSha256", "backend", "jdkMajor",
                "jvmFlags", "interfaces", "instances", "workloadId"):
        require(base[key] == candidate[key], "Uncontrolled experiment mismatch: " + key)
    combined = [r["runId"] for side in (base, candidate) for r in side["runs"]]
    require(len(combined) == len(set(combined)), "Run IDs must be unique across both versions")
    values = {}
    for metric in METRICS:
        prev = statistics.median(float(r[metric]) for r in base["runs"])
        next_ = statistics.median(float(r[metric]) for r in candidate["runs"])
        values[metric] = {
            "legacyMedian": prev, "applicationMedian": next_,
            "delta": next_ - prev,
            "ratio": next_ / prev if prev > 0 else None,
        }
    return {
        "status": "REPORT_ONLY",
        "claim": "Measured control-plane scale, not production RPC SLO",
        "backend": base["backend"],
        "dimensions": {"interfaces": base["interfaces"], "instances": base["instances"]},
        "hostFingerprintSha256": base["hostFingerprintSha256"],
        "legacyCommit": base["commit"],
        "applicationCommit": candidate["commit"],
        "legacyRuns": len(base["runs"]),
        "applicationRuns": len(candidate["runs"]),
        "metrics": values,
    }


def main() -> int:
    """Validate both raw files and atomically write a noncertifying JSON comparison."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy", type=Path, required=True)
    parser.add_argument("--application", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        legacy = json.loads(args.legacy.read_text(encoding="utf-8"))
        application = json.loads(args.application.read_text(encoding="utf-8"))
        report = compare(legacy, application)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print("BLOCKED: missing/invalid measured control-plane evidence: " + str(error),
              file=sys.stderr)
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        print("BLOCKED: will not overwrite an existing comparison", file=sys.stderr)
        return 2
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                           encoding="utf-8")
    print("REPORT_ONLY: measured registry scale compared; manual review still required")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
