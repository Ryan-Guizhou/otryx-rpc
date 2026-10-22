#!/usr/bin/env python3
"""Positive scheduling and negative-host/policy cases for the controlled AB BA AB runner."""
from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from run_v2_19_controlled_campaign import ORDER, execute, plan, policy

BASE = "a" * 40
CANDIDATE = "b" * 40


def environment() -> dict[str, str]:
    return {
        "OTRYX_RPC_RUNNER_ID": "dedicated-fixed-01",
        "OTRYX_RPC_EXPECT_HOST_FINGERPRINT_SHA256": "c" * 64,
        "OTRYX_RPC_JVM_FLAGS": "-Xms256m -Xmx256m",
        "OTRYX_RPC_SOAK_CONCURRENCY": "10000",
        "OTRYX_RPC_SOAK_DURATION_SECONDS": "3600",
        "OTRYX_RPC_SOAK_MAX_ERROR_RATE": "0.005",
        "OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT": "8000",
        "OTRYX_RPC_SOAK_MAX_P99_MICROS": "100000",
    }


class V219ControlledCampaignTest(unittest.TestCase):
    def test_exact_order_and_immutable_paths(self) -> None:
        campaign = plan(BASE, CANDIDATE)
        self.assertEqual(list(ORDER), [run["side"] for run in campaign["runs"]])
        self.assertEqual(6, len(set(run["path"] for run in campaign["runs"])))
        self.assertEqual(3, sum(run["side"] == "baseline" for run in campaign["runs"]))

    def test_refuse_invalid_or_identical_source(self) -> None:
        for base, candidate in (("short", CANDIDATE), (BASE, BASE),
                                (BASE, "z" * 40)):
            with self.assertRaisesRegex(ValueError, "different full"):
                plan(base, candidate)

    def test_policy_requires_real_fixed_runner(self) -> None:
        env = environment()
        validated = policy(env)
        self.assertEqual(3600, validated["durationSeconds"])
        self.assertEqual("dedicated-fixed-01", validated["runnerId"])
        for missing in ("OTRYX_RPC_RUNNER_ID", "OTRYX_RPC_JVM_FLAGS",
                        "OTRYX_RPC_EXPECT_HOST_FINGERPRINT_SHA256",
                        "OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT",
                        "OTRYX_RPC_SOAK_MAX_P99_MICROS"):
            changed = copy.deepcopy(env)
            del changed[missing]
            with self.assertRaisesRegex(ValueError, missing):
                policy(changed)
        env["GITHUB_ACTIONS"] = "true"
        with self.assertRaisesRegex(ValueError, "self-hosted"):
            policy(env)
        env["RUNNER_ENVIRONMENT"] = "self-hosted"
        self.assertIsNotNone(policy(env))

    def test_bad_numbers_and_shared_runner_rejected(self) -> None:
        for key, value in (("OTRYX_RPC_SOAK_DURATION_SECONDS", "3599"),
                           ("OTRYX_RPC_SOAK_CONCURRENCY", "9999"),
                           ("OTRYX_RPC_SOAK_MAX_ERROR_RATE", "NaN"),
                           ("OTRYX_RPC_SOAK_MAX_ERROR_RATE", "-1"),
                           ("OTRYX_RPC_SOAK_MAX_ERROR_RATE", "1"),
                           ("OTRYX_RPC_SOAK_MAX_P99_MICROS", "0"),
                           ("OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT", "10001"),
                           ("OTRYX_RPC_EVIDENCE_PROFILE", "smoke"),
                           ("OTRYX_RPC_RUNNER_ID", "github-hosted-ephemeral"),
                           ("OTRYX_RPC_EXPECT_HOST_FINGERPRINT_SHA256", "not-a-hash")):
            env = environment()
            env[key] = value
            with self.assertRaises(ValueError, msg=f"Expected rejection: {key}={value}"):
                policy(env)

    def test_preexisting_evidence_must_not_be_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / "evidence"
            output.mkdir()
            (output / "evidence-manifest.json").write_text("original")
            with self.assertRaisesRegex(ValueError, "overwrite"):
                execute(root, output, plan(BASE, CANDIDATE), policy(environment()), environment())
            self.assertEqual("original", (output / "evidence-manifest.json").read_text())

    def _fixture_repository(self, root: Path, fail_run: str = "") -> tuple[Path, str, str]:
        repo = root / "source"
        repo.mkdir()
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.test"],
                       cwd=repo, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=repo, check=True)
        scripts = repo / "scripts"
        scripts.mkdir()
        # The fixture's minimal native file is TEST-ONLY, never performance data.
        (scripts / "manage_v2d2_evidence_manifest.py").write_text(
            'import pathlib,sys,json,hashlib\n'
            'from os import environ as env\n'
            'root=pathlib.Path(sys.argv[3])\n'
            'def files():\n'
            ' return [{"path":p.name,"sizeBytes":p.stat().st_size,\n'
            '          "sha256":hashlib.sha256(p.read_bytes()).hexdigest()}\n'
            '         for p in sorted(root.iterdir()) if p.is_file()\n'
            '         and p.name!="evidence-manifest.json"]\n'
            'manifest=root/"evidence-manifest.json"\n'
            'if sys.argv[1]=="create":\n'
            ' json.dump({"commit":env["OTRYX_RPC_BENCHMARK_COMMIT"],\n'
            '  "runId":env["OTRYX_RPC_EVIDENCE_RUN_ID"],\n'
            '  "runnerId":env["OTRYX_RPC_RUNNER_ID"],\n'
            '  "evidenceClass":"controlled","files":files()},manifest.open("w"))\n'
            'elif sys.argv[1]=="verify":\n'
            ' assert json.loads(manifest.read_text())["files"]==files()\n',
            encoding="utf-8")
        fake_runner = scripts / "run_v2d2_fixed_evidence.sh"
        fake_runner.write_text(
            '#!/usr/bin/env bash\nset -euo pipefail\n'
            'mkdir -p "$1"\n'
            'if [[ "${OTRYX_RPC_TEST_FAIL_RUN:-}" == '
            '"$OTRYX_RPC_EVIDENCE_RUN_ID" ]]; then '
            'echo incomplete > "$1/incomplete.txt"; exit 17; fi\n'
            'cat > "$1/environment.properties" <<EOF\n'
            'commit=$OTRYX_RPC_BENCHMARK_COMMIT\n'
            'run_id=$OTRYX_RPC_EVIDENCE_RUN_ID\n'
            'runner_id=$OTRYX_RPC_RUNNER_ID\n'
            'host_fingerprint_sha256=$OTRYX_RPC_EXPECT_HOST_FINGERPRINT_SHA256\n'
            'evidence_class=controlled\n'
            'EOF\n'
            'printf "test-only %s" "$OTRYX_RPC_EVIDENCE_RUN_ID" > "$1/native-sample.txt"\n'
            'python3 scripts/manage_v2d2_evidence_manifest.py create --bundle "$1"\n',
            encoding="utf-8")
        (scripts / "check_u13_campaign.py").write_text(
            '#!/usr/bin/env python3\nimport pathlib,sys,os\n'
            'if os.getenv("OTRYX_RPC_TEST_FAIL_VALIDATION"): sys.exit(19)\n'
            'p=pathlib.Path(sys.argv[-1]);p.mkdir(parents=True)\n'
            '(p/"test-only").write_text("fixture,not performance evidence")\n',
            encoding="utf-8")
        subprocess.run(["git", "add", "scripts"], cwd=repo, check=True)
        subprocess.run(["git", "commit", "-qm", "baseline"], cwd=repo, check=True)
        baseline = subprocess.check_output(["git", "rev-parse", "HEAD"],
                                           cwd=repo, text=True).strip()
        (repo / "marker.txt").write_text("version two", encoding="utf-8")
        subprocess.run(["git", "add", "marker.txt"], cwd=repo, check=True)
        subprocess.run(["git", "commit", "-qm", "candidate"], cwd=repo, check=True)
        candidate = subprocess.check_output(["git", "rev-parse", "HEAD"],
                                            cwd=repo, text=True).strip()
        return repo, baseline, candidate

    def test_six_real_git_worktrees_are_serial_and_cleaned_up(self) -> None:
        # The subprocess fixture writes run IDs only; no performance evidence is synthesized.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo, base, candidate = self._fixture_repository(root)
            output = root / "evidence"
            execute(repo, output, plan(base, candidate), policy(environment()), environment())
            recorded = [json.loads((output / f"runs/{i:02d}-{side}/evidence-manifest.json")
                                   .read_text(encoding="utf-8"))["runId"]
                        for i, side in enumerate(ORDER, 1)]
            self.assertEqual([f"run{i:02d}-{side}" for i, side in enumerate(ORDER, 1)],
                             recorded)
            self.assertTrue((output / "campaign.json").is_file())
            self.assertTrue((output / "campaign-report/test-only").is_file())
            self.assertEqual(1, len(subprocess.check_output(
                ["git", "worktree", "list", "--porcelain"], cwd=repo,
                text=True).split("worktree ")) - 1)

    def test_partial_failure_preserves_partial_and_no_campaign_claim(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo, base, candidate = self._fixture_repository(root)
            output = root / "evidence"
            failing = environment()
            failing["OTRYX_RPC_TEST_FAIL_RUN"] = "run03-candidate"
            with self.assertRaises(subprocess.CalledProcessError):
                execute(repo, output, plan(base, candidate),
                        policy(failing), failing)
            self.assertFalse((output / "campaign.json").exists())
            self.assertTrue((output / "campaign-failure.json").is_file())
            self.assertTrue((output / "runs/02-candidate/evidence-manifest.json").is_file())
            self.assertFalse((output / "runs/03-candidate/evidence-manifest.json").exists())

    def test_resume_reuses_verified_prefix_and_preserves_failed_attempt(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo, base, candidate = self._fixture_repository(root)
            campaign = plan(base, candidate)
            output = root / "evidence"
            failing = environment()
            failing["OTRYX_RPC_TEST_FAIL_RUN"] = "run03-candidate"
            with self.assertRaises(subprocess.CalledProcessError):
                execute(repo, output, campaign, policy(failing), failing)
            first = output / "runs/01-baseline/native-sample.txt"
            original_mtime = first.stat().st_mtime_ns
            stable = environment()
            execute(repo, output, campaign, policy(stable), stable, resume=True)
            self.assertEqual(original_mtime, first.stat().st_mtime_ns)
            self.assertTrue((output / "campaign.json").exists())
            self.assertFalse((output / "campaign-failure.json").exists())
            self.assertTrue((output / "failed-attempts/campaign-status/attempt-001").is_file())
            self.assertTrue((output / "failed-attempts/run03-candidate"
                             / "attempt-001/incomplete.txt").exists())
            with self.assertRaisesRegex(ValueError, "completed campaign"):
                execute(repo, output, campaign, policy(stable), stable, resume=True)

    def test_resume_rejects_changed_policy_and_tampered_prefix(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo, base, candidate = self._fixture_repository(root)
            campaign = plan(base, candidate)
            output = root / "evidence"
            failing = environment()
            failing["OTRYX_RPC_TEST_FAIL_RUN"] = "run03-candidate"
            with self.assertRaises(subprocess.CalledProcessError):
                execute(repo, output, campaign, policy(failing), failing)
            changed = environment()
            changed["OTRYX_RPC_SOAK_MAX_P99_MICROS"] = "90000"
            with self.assertRaisesRegex(ValueError, "policy"):
                execute(repo, output, campaign, policy(changed), changed, resume=True)
            original = output / "runs/01-baseline/native-sample.txt"
            original.write_text("tampered", encoding="utf-8")
            with self.assertRaises(subprocess.CalledProcessError):
                execute(repo, output, campaign, policy(environment()),
                        environment(), resume=True)
            self.assertFalse((output / "campaign.json").exists())

    def test_final_validator_failure_never_publishes_campaign_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo, base, candidate = self._fixture_repository(root)
            output = root / "evidence"
            # Fake checker fails only by an explicit test-only env control.
            with patch.dict(os.environ, {"OTRYX_RPC_TEST_FAIL_VALIDATION": "1"}):
                with self.assertRaises(subprocess.CalledProcessError):
                    execute(repo, output, plan(base, candidate),
                            policy(environment()), environment())
            self.assertFalse((output / "campaign.json").exists())
            self.assertTrue((output / "campaign-failure.json").exists())
            self.assertTrue((output / ".campaign-validation-pending.json").exists())
            execute(repo, output, plan(base, candidate), policy(environment()),
                    environment(), resume=True)
            self.assertTrue((output / "campaign.json").exists())

    def test_out_of_order_completed_run_rejected_without_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo, base, candidate = self._fixture_repository(root)
            output = root / "evidence"
            fail = environment()
            fail["OTRYX_RPC_TEST_FAIL_RUN"] = "run03-candidate"
            with self.assertRaises(subprocess.CalledProcessError):
                execute(repo, output, plan(base, candidate), policy(fail), fail)
            late = output / "runs/04-baseline"
            late.mkdir(parents=True)
            (late / "evidence-manifest.json").write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Out-of-order"):
                execute(repo, output, plan(base, candidate),
                        policy(environment()), environment(), resume=True)
            self.assertEqual("{}", (late / "evidence-manifest.json").read_text())

    def test_cli_fails_before_create_on_missing_host(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "bundles"
            script = Path(__file__).with_name("run_v2_19_controlled_campaign.py")
            with patch.dict(os.environ, {}, clear=True):
                result = subprocess.run([sys.executable, str(script), "--baseline-sha", BASE,
                                         "--candidate-sha", CANDIDATE,
                                         "--output-dir", str(output)],
                                        capture_output=True, text=True, check=False)
            self.assertEqual(2, result.returncode)
            self.assertFalse(output.exists())
            self.assertIn("BLOCKED", result.stderr)


if __name__ == "__main__":
    unittest.main()
