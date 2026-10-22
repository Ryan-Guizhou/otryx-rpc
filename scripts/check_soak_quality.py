#!/usr/bin/env python3
"""Validate real observed RPC soak execution, not only configured worker count.

A shared-runner smoke result is REPORT_ONLY. Controlled policy PASS requires
operator-selected limits; neither status constitutes a production SLO claim.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path


REQUIRED_KEYS = (
    "concurrency", "payloadBytes", "connectionsPerEndpoint",
    "durationSeconds", "throughputOpsPerSecond", "successes", "errors",
    "errorRate", "maxLogicalInflight", "p50Micros", "p99Micros",
    "p999Micros", "gcCountDelta", "gcTimeMillisDelta",
    "processCpuCoresAverage", "errorsByType",
)


def finite(value: object) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def count(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def check_soak(
    data: dict,
    mode: str,
    max_error_rate: float | None = None,
    min_observed_inflight: int | None = None,
    max_p99_micros: float | None = None,
) -> dict:
    errors: list[str] = []
    absent = sorted(set(REQUIRED_KEYS) - set(data))
    if absent:
        errors.append("Missing soak fields: " + ", ".join(absent))

    configured = data.get("concurrency")
    observed = data.get("maxLogicalInflight")
    successes = data.get("successes")
    failures = data.get("errors")
    reported_rate = data.get("errorRate")
    rate = None

    if not count(configured) or configured <= 0:
        errors.append("Invalid configured logical concurrency")
    if not count(observed) or observed <= 0:
        errors.append("Missing actual observed logical inflight")
    elif count(configured) and observed > configured:
        errors.append("Observed inflight exceeds configured caller count")
    if not count(successes) or successes == 0:
        errors.append("Soak produced no successful RPC calls")
    if not count(failures):
        errors.append("Invalid failure count")
    if count(successes) and count(failures) and successes + failures > 0:
        rate = failures / (successes + failures)
        if not finite(reported_rate) or not 0 <= reported_rate <= 1:
            errors.append("Invalid recorded errorRate")
        elif abs(reported_rate - rate) > 1e-6:
            errors.append("Recorded errorRate disagrees with successes/errors")

    durations = data.get("durationSeconds")
    if not finite(durations) or durations <= 0:
        errors.append("Invalid actual soak durationSeconds")
    throughput = data.get("throughputOpsPerSecond")
    if not finite(throughput) or throughput <= 0:
        errors.append("Missing positive measured throughput")
    for field in ("payloadBytes", "connectionsPerEndpoint"):
        value = data.get(field)
        if not count(value) or value <= 0:
            errors.append(f"Invalid {field}")

    pct = []
    for field in ("p50Micros", "p99Micros", "p999Micros"):
        value = data.get(field)
        if not finite(value) or value < 0:
            errors.append(f"Invalid {field}")
        else:
            pct.append(value)
    if len(pct) == 3 and not (pct[0] <= pct[1] <= pct[2]):
        errors.append("Latency percentiles must be monotonic")

    classes = data.get("errorsByType")
    if not isinstance(classes, dict) or any(
        not isinstance(key, str) or not count(value)
        for key, value in classes.items()
    ):
        errors.append("Invalid errorsByType breakdown")
    elif count(failures) and sum(classes.values()) != failures:
        errors.append("Error category counts disagree with total errors")

    policy = {
        "maxErrorRate": max_error_rate,
        "minObservedLogicalInflight": min_observed_inflight,
        "maxP99Micros": max_p99_micros,
    }
    if mode not in ("smoke", "controlled"):
        errors.append("Unexpected soak acceptance mode")
    if mode == "controlled":
        if not finite(max_error_rate) or not 0 <= max_error_rate < 1:
            errors.append("Controlled acceptance requires maxErrorRate in [0,1)")
        if not count(min_observed_inflight) or min_observed_inflight <= 0:
            errors.append("Controlled acceptance requires minObservedLogicalInflight")
        if not finite(max_p99_micros) or max_p99_micros <= 0:
            errors.append("Controlled acceptance requires positive maxP99Micros")
        if rate is not None and finite(max_error_rate) and rate > max_error_rate:
            errors.append(
                f"Observed error rate {rate:.6f} exceeds maxErrorRate {max_error_rate:.6f}"
            )
        if count(observed) and count(min_observed_inflight) and (
            observed < min_observed_inflight
        ):
            errors.append(
                f"Observed inflight {observed} below min {min_observed_inflight}"
            )
        if (
            finite(data.get("p99Micros"))
            and finite(max_p99_micros)
            and data["p99Micros"] > max_p99_micros
        ):
            errors.append(
                f"Measured p99 {data['p99Micros']}us exceeds maxP99Micros"
            )
    status = "FAIL" if errors else (
        "POLICY_PASS" if mode == "controlled" else "REPORT_ONLY"
    )
    return {
        "schemaVersion": 1,
        "status": status,
        "evidenceScope": "soak-result-policy-check-not-a-production-SLO",
        "mode": mode,
        "configuredLogicalCallers": configured,
        "observedMaxLogicalInflight": observed,
        "successes": successes,
        "errors": failures,
        "measuredErrorRate": rate,
        "measuredP99Micros": data.get("p99Micros"),
        "policy": policy,
        "validationErrors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--soak", type=Path, required=True)
    parser.add_argument("--mode", choices=("smoke", "controlled"), required=True)
    parser.add_argument("--max-error-rate", type=float)
    parser.add_argument("--min-observed-inflight", type=int)
    parser.add_argument("--max-p99-micros", type=float)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        raw = args.soak.read_bytes()
        data = json.loads(raw)
        if not isinstance(data, dict):
            raise ValueError("Soak report must be a JSON object")
        result = check_soak(
            data,
            args.mode,
            args.max_error_rate,
            args.min_observed_inflight,
            args.max_p99_micros,
        )
        result["soakSha256"] = hashlib.sha256(raw).hexdigest()
        args.output_dir.mkdir(parents=True, exist_ok=True)
        (args.output_dir / "report.json").write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        lines = [
            "# OTRYX RPC Soak Acceptance",
            "",
            f"- Status: **{result['status']}**",
            f"- Evidence mode: `{args.mode}`",
            f"- Caller threads: `{result['configuredLogicalCallers']}`",
            f"- Measured peak logical inflight: `{result['observedMaxLogicalInflight']}`",
            f"- Measured error rate: `{result['measuredErrorRate']}`",
            f"- Acceptance limits: `{result['policy']}`",
            "",
            "> Passing structural/policy checks does not establish production availability or throughput guarantees.",
            "",
        ]
        lines.extend(f"- ERROR: {error}" for error in result["validationErrors"])
        (args.output_dir / "report.md").write_text(
            "\n".join(lines) + "\n", encoding="utf-8"
        )
        if result["status"] == "FAIL":
            for error in result["validationErrors"]:
                print("ERROR:", error)
            return 1
        print("Soak quality result:", result["status"])
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as error:
        parser.exit(1, f"Invalid soak result: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
