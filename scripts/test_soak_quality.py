#!/usr/bin/env python3
"""Regression tests for controlled soak policy and actual concurrency evidence."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_soak_quality import check_soak


def valid_soak():
    return {
        "concurrency": 10_000,
        "payloadBytes": 256,
        "connectionsPerEndpoint": 4,
        "durationSeconds": 1805.0,
        "throughputOpsPerSecond": 2000.0,
        "successes": 9900,
        "errors": 100,
        "errorRate": 0.01,
        "maxLogicalInflight": 9000,
        "p50Micros": 110.0,
        "p99Micros": 410.0,
        "p999Micros": 850.0,
        "gcCountDelta": 10,
        "gcTimeMillisDelta": 120,
        "processCpuCoresAverage": 2.1,
        "errorsByType": {"RpcTimeoutException": 100},
    }


class SoakQualityTest(unittest.TestCase):
    def test_controlled_with_explicit_policy_passes(self):
        result = check_soak(
            valid_soak(),
            "controlled",
            max_error_rate=0.02,
            min_observed_inflight=8000,
            max_p99_micros=500.0,
        )
        self.assertEqual(result["status"], "POLICY_PASS")

    def test_one_success_with_many_errors_fails_policy(self):
        data = valid_soak()
        data.update(successes=1, errors=9999, errorRate=0.9999)
        data["errorsByType"] = {"RpcTimeoutException": 9999}
        result = check_soak(
            data,
            "controlled",
            max_error_rate=0.01,
            min_observed_inflight=8000,
        )
        self.assertIn("exceeds maxErrorRate", " ".join(result["validationErrors"]))

    def test_configured_concurrency_is_not_observed_concurrency(self):
        data = valid_soak()
        data["maxLogicalInflight"] = 9
        result = check_soak(
            data,
            "controlled",
            max_error_rate=0.02,
            min_observed_inflight=8000,
        )
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("Observed inflight", " ".join(result["validationErrors"]))

    def test_controlled_without_policy_fails_closed(self):
        result = check_soak(valid_soak(), "controlled")
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("requires maxErrorRate", " ".join(result["validationErrors"]))

    def test_missing_p99_or_permissive_error_budget_never_passes(self):
        for error_budget, p99_budget in ((0.02, None), (1.0, 500.0),
                                        (0.02, float("nan")), (0.02, 0.0)):
            with self.subTest(error_budget=error_budget, p99_budget=p99_budget):
                result = check_soak(
                    valid_soak(), "controlled", max_error_rate=error_budget,
                    min_observed_inflight=8000, max_p99_micros=p99_budget,
                )
                self.assertEqual("FAIL", result["status"])

    def test_smoke_is_never_policy_pass(self):
        result = check_soak(valid_soak(), "smoke")
        self.assertEqual(result["status"], "REPORT_ONLY")

    def test_rate_disagreement_and_bad_breakdown_fail(self):
        data = valid_soak()
        data["errorRate"] = 0.0001
        data["errorsByType"] = {"RpcTimeoutException": 50}
        result = check_soak(data, "smoke")
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("disagrees", " ".join(result["validationErrors"]))

    def test_invalid_percentiles_and_unrealistic_inflight_fail(self):
        data = valid_soak()
        data["maxLogicalInflight"] = 11_000
        data["p99Micros"] = 900.0
        data["p999Micros"] = 400.0
        result = check_soak(data, "smoke")
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("Latency percentiles", " ".join(result["validationErrors"]))

    def test_controlled_structural_validator_rechecks_soak_and_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = root / "environment.properties"
            env.write_text(
                "evidence_class=controlled\n"
                "runner_id=fixed-runner\n"
                "host_fingerprint_sha256=fingerprint\n"
                "jvm_flags=-Xmx512m\n",
                encoding="utf-8",
            )
            soak = root / "soak.json"
            payload = valid_soak()
            soak.write_text(json.dumps(payload), encoding="utf-8")
            policy_folder = root / "soak-policy"
            policy_folder.mkdir()
            policy = check_soak(payload, "controlled", 0.02, 8000, 500.0)
            policy["soakSha256"] = hashlib.sha256(soak.read_bytes()).hexdigest()
            policy_path = policy_folder / "report.json"
            policy_path.write_text(json.dumps(policy), encoding="utf-8")

            command = [
                sys.executable, "scripts/validate_v2d2_evidence.py",
                "--environment", str(env),
                "--soak", str(soak),
                "--require-soak", "--require-controlled",
                "--min-soak-seconds", "1800",
                "--min-concurrency", "10000",
                "--output-dir", str(root / "reports"),
            ]
            repo_root = Path(__file__).resolve().parents[1]
            valid = subprocess.run(
                command, cwd=repo_root, text=True, capture_output=True,
                check=False,
            )
            self.assertEqual(0, valid.returncode, valid.stdout + valid.stderr)

            soak.write_text(json.dumps(payload) + "\n", encoding="utf-8")
            stale = subprocess.run(
                command, cwd=repo_root, text=True, capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, stale.returncode)
            self.assertIn("stale", stale.stdout + stale.stderr)

            payload.update(
                successes=100, errors=9900, errorRate=0.99,
                errorsByType={"RpcTimeoutException": 9900},
            )
            soak.write_text(json.dumps(payload), encoding="utf-8")
            policy["soakSha256"] = hashlib.sha256(soak.read_bytes()).hexdigest()
            policy_path.write_text(json.dumps(policy), encoding="utf-8")
            fabricated = subprocess.run(
                command, cwd=repo_root, text=True, capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, fabricated.returncode)
            self.assertIn("Soak policy does not match", fabricated.stdout)

    def test_p99_policy_rejects_excessive_latency(self):
        result = check_soak(
            valid_soak(), "controlled",
            max_error_rate=0.02,
            min_observed_inflight=8000,
            max_p99_micros=300.0,
        )
        self.assertEqual(result["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
