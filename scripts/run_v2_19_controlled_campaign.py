#!/usr/bin/env python3
"""Execute six immutable, serial AB/BA controlled bundles with verified resume.

This script does not set production SLOs or certify capacity. It requires a
preapproved fixed host and policy, then delegates every run to the repository's
existing controlled preflight, full matrix, 10k soak and evidence validators.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ORDER = ("baseline", "candidate", "candidate", "baseline", "baseline", "candidate")
SHA = re.compile(r"[0-9a-f]{40}\Z")
FINGERPRINT = re.compile(r"[0-9a-f]{64}\Z")
REQUIRED_POLICY = (
    "OTRYX_RPC_RUNNER_ID", "OTRYX_RPC_EXPECT_HOST_FINGERPRINT_SHA256",
    "OTRYX_RPC_JVM_FLAGS", "OTRYX_RPC_SOAK_MAX_ERROR_RATE",
    "OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT",
    "OTRYX_RPC_SOAK_MAX_P99_MICROS",
)
RUN_SCRIPT = Path("scripts/run_v2d2_fixed_evidence.sh")


def require(condition: bool, message: str) -> None:
    """Reject incomplete or implausible campaign configuration before any worktree IO."""
    if not condition:
        raise ValueError(message)


def policy(environment: dict[str, str]) -> dict[str, object]:
    """Freeze explicit operator controls; reject estimates and unsafe defaults."""
    for name in REQUIRED_POLICY:
        require(bool(environment.get(name, "").strip()), f"Missing {name}")
    fingerprint = environment["OTRYX_RPC_EXPECT_HOST_FINGERPRINT_SHA256"]
    require(FINGERPRINT.fullmatch(fingerprint) is not None, "Invalid host fingerprint SHA-256")
    require(environment["OTRYX_RPC_RUNNER_ID"] != "github-hosted-ephemeral",
            "Shared CI runners are not controlled hosts")
    require(environment.get("OTRYX_RPC_EVIDENCE_CLASS", "controlled") == "controlled",
            "Only controlled evidence is accepted")
    require(environment.get("OTRYX_RPC_EVIDENCE_PROFILE", "full") == "full",
            "A controlled campaign requires the full matrix")
    if environment.get("GITHUB_ACTIONS") == "true":
        require(environment.get("RUNNER_ENVIRONMENT") == "self-hosted",
                "GitHub controlled campaigns require a self-hosted runner")
    try:
        concurrency = int(environment.get("OTRYX_RPC_SOAK_CONCURRENCY", "10000"))
        duration = int(environment.get("OTRYX_RPC_SOAK_DURATION_SECONDS", "3600"))
        min_inflight = int(environment["OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT"])
        max_error = float(environment["OTRYX_RPC_SOAK_MAX_ERROR_RATE"])
        p99 = float(environment["OTRYX_RPC_SOAK_MAX_P99_MICROS"])
    except ValueError as error:
        raise ValueError("Invalid controlled numeric policy") from error
    require(concurrency >= 10000 and duration >= 3600,
            "Controlled runs require 10k callers and at least 3600 seconds")
    require(1 <= min_inflight <= concurrency, "Invalid observed inflight floor")
    require(math.isfinite(max_error) and 0 <= max_error < 1,
            "Invalid maximum error rate")
    require(math.isfinite(p99) and p99 > 0,
            "Invalid p99 budget")
    return {
        "runnerId": environment["OTRYX_RPC_RUNNER_ID"],
        "hostFingerprintSha256": fingerprint,
        "jvmFlags": environment["OTRYX_RPC_JVM_FLAGS"],
        "concurrency": concurrency,
        "durationSeconds": duration,
        "minObservedInflight": min_inflight,
        "maxErrorRate": max_error,
        "maxP99Micros": p99,
    }


def plan(baseline: str, candidate: str) -> dict[str, object]:
    """Pin two distinct full source commits and one exact AB BA AB schedule."""
    require(bool(SHA.fullmatch(baseline)) and bool(SHA.fullmatch(candidate))
            and baseline != candidate, "Two different full 40-hex Git SHAs are required")
    return {
        "baselineSha": baseline,
        "candidateSha": candidate,
        "runs": [
            {"side": side, "path": f"runs/{idx:02d}-{side}"}
            for idx, side in enumerate(ORDER, start=1)
        ],
    }


def checked_run(args: list[str], cwd: Path, environment: dict[str, str] | None = None) -> None:
    """Propagate nonzero failures immediately; do not silently skip a run."""
    subprocess.run(args, cwd=cwd, env=environment, check=True)


def _properties(path: Path) -> dict[str, str]:
    """Load only the fixed identity fields, never derive an identity from filenames."""
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def _policy_record(campaign: dict, settings: dict) -> str:
    """Canonical policy bytes allow exact comparison before any resumed operation."""
    return json.dumps({"status": "LOCKED_BEFORE_MEASUREMENT", "policy": settings,
                       "baselineSha": campaign["baselineSha"],
                       "candidateSha": campaign["candidateSha"]},
                      indent=2, sort_keys=True) + "\n"


def _verify_bundle(repo: Path, bundle: Path, commit: str, run_id: str,
                   settings: dict) -> None:
    """Require a matching run identity and recomputed checksums before reusing evidence."""
    require(not bundle.is_symlink(), f"Evidence directory is symlinked: {bundle}")
    marker = bundle / "evidence-manifest.json"
    require(marker.is_file() and not marker.is_symlink(),
            f"Missing original evidence manifest: {bundle}")
    record = json.loads(marker.read_text(encoding="utf-8"))
    require(isinstance(record, dict), "Invalid evidence manifest")
    for key, value in (("commit", commit), ("runId", run_id),
                       ("runnerId", settings["runnerId"]),
                       ("evidenceClass", "controlled")):
        require(record.get(key) == value,
                f"Existing evidence provenance mismatch: {key} ({bundle})")
    origin = _properties(bundle / "environment.properties")
    for key, value in (("commit", commit), ("run_id", run_id),
                       ("runner_id", settings["runnerId"]),
                       ("host_fingerprint_sha256", settings["hostFingerprintSha256"]),
                       ("evidence_class", "controlled")):
        require(origin.get(key) == value,
                f"Existing environment provenance mismatch: {key} ({bundle})")
    checked_run(["python3", "scripts/manage_v2d2_evidence_manifest.py", "verify",
                 "--bundle", str(bundle)], repo)


def _archive_partial(output: Path, source: Path, label: str) -> None:
    """Move a partial attempt aside without erasing any original data."""
    archive = output / "failed-attempts" / label
    require(not archive.is_symlink() and not archive.parent.is_symlink(),
            "Refusing symlinked partial evidence archive")
    archive.mkdir(parents=True, exist_ok=True)
    attempt = 1
    while (archive / f"attempt-{attempt:03d}").exists():
        attempt += 1
    source.rename(archive / f"attempt-{attempt:03d}")


def execute(repo: Path, output: Path, campaign: dict, settings: dict,
            environment: dict[str, str], resume: bool = False) -> None:
    """Reuse only checksum-verified prefix runs, then finish the original AB/BA order."""
    record = _policy_record(campaign, settings)
    if resume:
        require(output.is_dir() and not output.is_symlink(),
                "Resume requires an existing nonsymlink evidence directory")
        require((output / "predeclared-policy.json").read_text(encoding="utf-8")
                == record, "Resume policy or pinned source SHAs do not match")
        require(not (output / "campaign.json").exists(),
                "A completed campaign cannot be resumed or overwritten")
    else:
        require(not output.exists(), "Refusing to overwrite existing evidence directory")
    for sha in (campaign["baselineSha"], campaign["candidateSha"]):
        checked_run(["git", "cat-file", "-e", f"{sha}^{{commit}}"], repo)
    for binary in ("git", "bash", "java", "python3"):
        require(shutil.which(binary) is not None,
                f"Required executable missing: {binary}")
    if not resume:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.mkdir()
        (output / "predeclared-policy.json").write_text(record, encoding="utf-8")
    # Validate already completed bundles before creating a worktree or altering any output.
    resume_prefix = 0
    if resume:
        found_gap = False
        for index, item in enumerate(campaign["runs"], 1):
            path = output / item["path"]
            if path.is_symlink():
                raise ValueError(f"Existing run path is a symlink: {path}")
            if (path / "evidence-manifest.json").is_file():
                require(not found_gap, "Out-of-order completed run; refusing to resume")
                _verify_bundle(repo, path, campaign[item["side"] + "Sha"],
                               f"run{index:02d}-{item['side']}", settings)
                resume_prefix += 1
            else:
                found_gap = True
    scratch = Path(tempfile.mkdtemp(prefix="otryx-v2-19-worktrees-"))
    worktrees: list[Path] = []
    try:
        for side in ("baseline", "candidate"):
            target = scratch / side
            checked_run(["git", "worktree", "add", "--detach", str(target),
                         campaign[side + "Sha"]], repo)
            worktrees.append(target)
            require((target / RUN_SCRIPT).is_file(),
                    f"{side} must contain the actual fixed-evidence runner")
        for index, item in enumerate(campaign["runs"], 1):
            if index <= resume_prefix:
                continue
            side = item["side"]
            target = worktrees[0] if side == "baseline" else worktrees[1]
            run_id = f"run{index:02d}-{side}"
            bundle = output / item["path"]
            if bundle.exists():
                # Only failed attempts may be moved, retaining every raw byte.
                require(bundle.is_dir() and not bundle.is_symlink()
                        and not (bundle / "evidence-manifest.json").exists(),
                        f"Refusing to replace completed or unsafe evidence: {bundle}")
                _archive_partial(output, bundle, run_id)
            run_env = dict(environment)
            run_env.update({
                "OTRYX_RPC_EVIDENCE_CLASS": "controlled",
                "OTRYX_RPC_EVIDENCE_PROFILE": "full",
                "OTRYX_RPC_EVIDENCE_RUN_ID": run_id,
                "OTRYX_RPC_BENCHMARK_COMMIT": campaign[side + "Sha"],
                "OTRYX_RPC_REQUIRE_BARE_METAL": "true",
                "OTRYX_RPC_EXPECT_RUNNER_ID": settings["runnerId"],
                "OTRYX_RPC_RUNNER_BASELINE": str(output / "fixed-runner-baseline.json"),
                "OTRYX_RPC_SOAK_CONCURRENCY": str(settings["concurrency"]),
                "OTRYX_RPC_SOAK_DURATION_SECONDS": str(settings["durationSeconds"]),
            })
            checked_run(["bash", str(RUN_SCRIPT), str(bundle)], target, run_env)
            _verify_bundle(repo, bundle, campaign[side + "Sha"], run_id, settings)
        # The final campaign.json must never exist until the six-run checker passes.
        pending = output / ".campaign-validation-pending.json"
        pending.write_text(json.dumps(campaign, indent=2, sort_keys=True) + "\n",
                           encoding="utf-8")
        checked_run(["python3", "scripts/check_u13_campaign.py", "--campaign",
                     str(pending), "--output-dir", str(output / "campaign-report")], repo)
        pending.replace(output / "campaign.json")
        print("Six controlled bundles verified; results remain REPORT_ONLY")
    except (OSError, ValueError, subprocess.CalledProcessError,
            json.JSONDecodeError) as error:
        (output / "campaign-failure.json").write_text(
            json.dumps({"status": "INCOMPLETE", "errorType": type(error).__name__,
                        "verifiedPrefix": resume_prefix,
                        "completedRuns": sum((output / r["path"] / "evidence-manifest.json").is_file()
                                             for r in campaign["runs"])},
                       sort_keys=True) + "\n", encoding="utf-8")
        raise
    finally:
        for worktree in reversed(worktrees):
            subprocess.run(["git", "worktree", "remove", "--force", str(worktree)],
                           cwd=repo, check=False)
        shutil.rmtree(scratch, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline-sha", required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--resume", action="store_true",
                        help="Verify and reuse completed prefix runs after interruption")
    arguments = parser.parse_args()
    try:
        campaign = plan(arguments.baseline_sha, arguments.candidate_sha)
        environment = dict(os.environ)
        settings = policy(environment)
        repo = Path(__file__).resolve().parents[1]
        output = arguments.output_dir.resolve()
        require(output != repo and repo not in output.parents,
                "Evidence output must be outside the source checkout")
        execute(repo, output, campaign, settings, environment, resume=arguments.resume)
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError,
            json.JSONDecodeError) as error:
        print(f"BLOCKED: controlled campaign did not complete: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
