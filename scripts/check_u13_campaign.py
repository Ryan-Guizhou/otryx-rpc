#!/usr/bin/env python3
"""Validate six controlled AB/BA bundles without declaring a production SLO."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

from check_soak_quality import check_soak
from check_v2d2_runner_baseline import STABLE_KEYS, properties
from manage_v2d2_evidence_manifest import files
from validate_v2d2_evidence import validate_matrix

ORDER = ("baseline", "candidate", "candidate", "baseline", "baseline", "candidate")
MIN_SECONDS = 3600
MIN_CONCURRENCY = 10000
GIT_SHA = re.compile(r"[0-9a-f]{40}\Z")
HOST_SHA = re.compile(r"[0-9a-f]{64}\Z")
JAVA_VERSION = re.compile(
    r'^\s*(?:openjdk|java)(?:\s+version)?\s+"?(\d+)(?=[.\s"+_-]|$)', re.IGNORECASE
)


def require(condition: bool, message: str) -> None:
    """Reject missing or invalid evidence rather than silently excluding a run."""
    if not condition:
        raise ValueError(message)


def parse_timestamp(value: object, field: str) -> datetime:
    """Require an unambiguous timezone-aware evidence timestamp."""
    require(isinstance(value, str) and bool(value.strip()),
            f"Missing {field}")
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"Invalid {field}: expected ISO-8601 timestamp") from error
    require(parsed.tzinfo is not None and parsed.utcoffset() is not None,
            f"Timezone missing for {field}")
    return parsed.astimezone(timezone.utc)


def recorded_java_major(raw: object) -> int:
    """Parse the runtime's major version rather than a numeric substring."""
    match = JAVA_VERSION.match(raw) if isinstance(raw, str) else None
    require(match is not None, "Unable to parse recorded Java runtime version")
    return int(match.group(1))


def load_json(path: Path) -> dict:
    """Load a required JSON object with a path-specific failure."""
    require(path.is_file(), f"Missing evidence file: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(data, dict), f"Not a JSON object: {path}")
    return data


def inspect_bundle(path: Path, sha: str, run_ids: set[str]) -> tuple[dict, dict, datetime, datetime]:
    """Recheck provenance, hashes, thresholds and observed soak duration."""
    require(path.is_dir(), f"Missing controlled bundle: {path}")
    manifest = load_json(path / "evidence-manifest.json")
    declared = manifest.get("files")
    require(isinstance(declared, list), f"Missing evidence manifest files: {path}")
    expected = {item["path"]: item for item in declared}
    require(len(expected) == len(declared), f"Duplicate manifest paths: {path}")
    actual = {item["path"]: item for item in files(path)}
    require(actual == expected, f"Bundle file hashes or inventory changed: {path}")
    env = properties(path / "environment.properties")
    require(env.get("evidence_class") == "controlled", f"Not controlled evidence: {path}")
    require(env.get("commit") == sha, f"Unexpected commit: {path}")
    require(manifest.get("commit") == sha, f"Manifest commit mismatch: {path}")
    require(manifest.get("runnerId") == env.get("runner_id"), f"Runner mismatch: {path}")
    require(manifest.get("runId") == env.get("run_id"), f"Manifest run ID mismatch: {path}")
    require(manifest.get("evidenceClass") == "controlled", f"Manifest class mismatch: {path}")
    require(bool(HOST_SHA.fullmatch(env.get("host_fingerprint_sha256", ""))),
            f"Missing stable host fingerprint: {path}")
    require(bool(env.get("runner_id")) and env["runner_id"] != "unknown",
            f"Missing fixed runner ID: {path}")
    require(bool(env.get("jvm_flags")), f"Unspecified JVM flags: {path}")
    require(recorded_java_major(env.get("java")) == 21,
            f"JDK 21 not recorded: {path}")
    preflight_path = path / "preflight.properties"
    require(preflight_path.is_file(), f"Missing controlled runner preflight: {path}")
    preflight = properties(preflight_path)
    require(preflight.get("status") == "PASS" and preflight.get("java_major") == "21",
            f"Invalid JDK 21 runner preflight: {path}")
    for key in ("commit", "run_id", "runner_id", "host_fingerprint_sha256",
                "evidence_class", "jvm_flags"):
        require(preflight.get(key) == env.get(key),
                f"Runner preflight/environment mismatch ({key}): {path}")
    captured_at = parse_timestamp(env.get("captured_at"), f"environment.captured_at: {path}")
    preflight_at = parse_timestamp(
        preflight.get("captured_at"), f"preflight.captured_at: {path}"
    )
    require(preflight_at <= captured_at, f"Preflight is later than environment capture: {path}")
    require(all(env.get(key) not in (None, "", "unknown") for key in STABLE_KEYS),
            f"Unrecorded stable runner attribute: {path}")
    run_id = env.get("run_id", "")
    require(bool(run_id) and run_id not in run_ids, f"Duplicate/missing run ID: {path}")
    run_ids.add(run_id)
    matrix_dir = path / "matrix"
    matrix_environment_path = matrix_dir / "environment.properties"
    require(matrix_environment_path.is_file(),
            f"Missing nested JMH matrix environment provenance: {path}")
    matrix_environment = properties(matrix_environment_path)
    require(matrix_environment.get("profile") == "full",
            f"Nested JMH matrix is not the full profile: {path}")
    for key in (*STABLE_KEYS, "commit", "run_id"):
        require(matrix_environment.get(key) == env.get(key),
                f"Matrix/root environment mismatch ({key}): {path}")
    matrix_captured_at = parse_timestamp(
        matrix_environment.get("captured_at"), f"matrix.captured_at: {path}"
    )
    require(matrix_captured_at >= captured_at,
            f"Matrix capture predates bundle environment: {path}")
    matrix_errors: list[str] = []
    validate_matrix(matrix_dir, "full", matrix_errors)
    require(not matrix_errors,
            f"Incomplete full-profile JMH matrix: {path}: {'; '.join(matrix_errors)}")
    require(load_json(path / "validation-report.json").get("status") == "PASS",
            f"Existing full matrix/soak validator failed: {path}")
    decision = load_json(path / "decision-inputs.json")
    require(decision.get("evidenceClass") == "controlled"
            and decision.get("commit") == sha
            and decision.get("runnerId") == env.get("runner_id"),
            f"Controlled decision evidence provenance mismatch: {path}")
    soak_path = path / "soak.json"
    soak_bytes = soak_path.read_bytes()
    soak = load_json(soak_path)
    completed_at = parse_timestamp(soak.get("completedAt"), f"soak.completedAt: {path}")
    require(completed_at > matrix_captured_at,
            f"Soak finished before nested matrix capture: {path}")
    require(soak.get("commit") == sha, f"Soak commit mismatch: {path}")
    require(isinstance(soak.get("durationSeconds"), (int, float))
            and math.isfinite(soak["durationSeconds"])
            and soak["durationSeconds"] >= MIN_SECONDS,
            f"Controlled soak shorter than 60 minutes: {path}")
    require(type(soak.get("concurrency")) is int
            and soak["concurrency"] >= MIN_CONCURRENCY,
            f"Not 10k logical concurrency: {path}")
    policy = load_json(path / "soak-policy" / "report.json")
    require(policy.get("status") == "POLICY_PASS", f"Unaccepted soak policy: {path}")
    require(policy.get("soakSha256") == hashlib.sha256(soak_bytes).hexdigest(),
            f"Stale policy checksum: {path}")
    thresholds = policy.get("policy", {})
    require(isinstance(thresholds, dict), f"Missing thresholds: {path}")
    result = check_soak(
        soak,
        "controlled",
        thresholds.get("maxErrorRate"),
        thresholds.get("minObservedLogicalInflight"),
        thresholds.get("maxP99Micros"),
    )
    require(result["status"] == "POLICY_PASS",
            f"Soak no longer satisfies predeclared thresholds: {path}")
    expected_qps = soak["successes"] / soak["durationSeconds"]
    require(math.isclose(soak["throughputOpsPerSecond"], expected_qps,
                         rel_tol=1e-6, abs_tol=1e-6),
            f"Soak QPS does not match successful calls / duration: {path}")
    return {key: env[key] for key in STABLE_KEYS}, {
        "runId": run_id,
        "commit": sha,
        "capturedAt": captured_at.isoformat(),
        "completedAt": completed_at.isoformat(),
        "durationSeconds": soak["durationSeconds"],
        "observedInflight": soak["maxLogicalInflight"],
        "qps": soak["throughputOpsPerSecond"],
        "p99Micros": soak["p99Micros"],
        "errorRate": soak["errorRate"],
    }, captured_at, completed_at


def validate_campaign(config: dict, directory: Path) -> dict:
    """Require three ordered AB/BA pairs on the same fixed runner."""
    baseline = config.get("baselineSha")
    candidate = config.get("candidateSha")
    require(isinstance(baseline, str) and bool(GIT_SHA.fullmatch(baseline)),
            "Invalid baseline full SHA")
    require(isinstance(candidate, str) and bool(GIT_SHA.fullmatch(candidate))
            and candidate != baseline, "Invalid or identical candidate full SHA")
    runs = config.get("runs")
    require(isinstance(runs, list) and len(runs) == len(ORDER),
            "Exactly six AB/BA run bundles are required")
    require(all(isinstance(item, dict) for item in runs), "Invalid campaign run entries")
    require(tuple(item.get("side") for item in runs) == ORDER,
            "Run order must be baseline,candidate,candidate,baseline,baseline,candidate")
    stable = None
    labels: set[str] = set()
    summaries: dict[str, list[dict]] = {"baseline": [], "candidate": []}
    previous_completed: datetime | None = None
    for position, run in enumerate(runs, start=1):
        raw_path = run.get("path")
        require(isinstance(raw_path, str) and raw_path.strip(),
                f"Invalid bundle path at run {position}")
        bundle = (directory / raw_path).resolve()
        side = run["side"]
        env, summary, captured_at, completed_at = inspect_bundle(
            bundle, baseline if side == "baseline" else candidate, labels
        )
        if previous_completed is not None:
            require(captured_at > previous_completed,
                    f"Runs overlap or are not chronological at position {position}")
        previous_completed = completed_at
        if stable is None:
            stable = env
        else:
            require(env == stable, f"Changed hardware/JVM baseline in run {position}")
        summaries[side].append(summary)
    metrics = {}
    for side, values in summaries.items():
        metrics[side] = {
            "samples": len(values),
            "medianQps": statistics.median(item["qps"] for item in values),
            "medianP99Micros": statistics.median(item["p99Micros"] for item in values),
            "medianErrorRate": statistics.median(item["errorRate"] for item in values),
            "runs": values,
        }
    return {
        "schemaVersion": 1,
        "status": "REPORT_ONLY",
        "integrity": "PASS",
        "productionSlo": "NOT_ESTABLISHED",
        "humanReviewRequired": True,
        "baselineSha": baseline,
        "candidateSha": candidate,
        "runOrder": list(ORDER),
        "runnerId": stable["runner_id"],
        "hostFingerprintSha256": stable["host_fingerprint_sha256"],
        "metrics": metrics,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--campaign", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = validate_campaign(load_json(args.campaign), args.campaign.parent)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        (args.output_dir / "campaign-report.json").write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        (args.output_dir / "campaign-report.md").write_text(
            "# U13 AB/BA campaign\n\n"
            "Integrity: PASS; result: REPORT_ONLY; production SLO: NOT ESTABLISHED.\n"
            f"\nBase: {report['baselineSha']}\n"
            f"Candidate: {report['candidateSha']}\n"
            "\nSix verified 60-minute controlled runs in AB BA AB order. "
            "Human latency, allocation, error and variance review remains required.\n",
            encoding="utf-8",
        )
        print("U13 controlled AB/BA evidence integrity passed (REPORT_ONLY)")
        return 0
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"ERROR: invalid U13 campaign: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
