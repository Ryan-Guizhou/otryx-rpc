#!/usr/bin/env python3
"""Create or validate the fixed-runner baseline used by V2-D.2-E1."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

STABLE_KEYS = (
    # Git commit is a run provenance field, not an attribute of fixed hardware.
    "evidence_class",
    "runner_id",
    "host_fingerprint_sha256",
    "cpu_model",
    "logical_cores",
    "physical_cores",
    "threads_per_core",
    "numa_nodes",
    "memory_bytes",
    "cpu_governor",
    "kernel",
    "java",
    "jvm_flags",
    "containerized",
)


def properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def snapshot(environment: dict[str, str]) -> dict[str, str]:
    missing = [key for key in STABLE_KEYS if key not in environment]
    if missing:
        raise SystemExit("Environment is missing baseline keys: " + ", ".join(missing))
    return {key: environment[key] for key in STABLE_KEYS}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--environment", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--initialize-if-missing", action="store_true")
    args = parser.parse_args()

    current = snapshot(properties(args.environment))
    if not args.baseline.exists():
        if not args.initialize_if_missing:
            raise SystemExit(f"Runner baseline does not exist: {args.baseline}")
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        args.baseline.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "status": "BASELINE",
                    "environment": current,
                },
                indent=2,
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"Initialized V2-D.2-E1 runner baseline: {args.baseline}")
        return 0

    baseline = json.loads(args.baseline.read_text(encoding="utf-8")).get(
        "environment", {}
    )
    errors = [
        f"{key}: baseline={baseline.get(key)!r}, current={current.get(key)!r}"
        for key in STABLE_KEYS
        if baseline.get(key) != current.get(key)
    ]
    if errors:
        raise SystemExit(
            "Fixed-runner baseline mismatch:\n- " + "\n- ".join(errors)
        )

    print("V2-D.2-E1 runner baseline matches")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
