#!/usr/bin/env python3
"""Adversarial fixtures for the V2-19 measured backend control-plane comparator."""
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_v2_19_registration_scale import compare


def campaign(model: str) -> dict:
    """Synthetic data used only inside tests; never written as real evidence."""
    return {
        "registryModel": model,
        "commit": ("a" if model == "interface" else "b") * 40,
        "hostFingerprintSha256": "c" * 64,
        "backend": "etcd", "jdkMajor": 21, "jvmFlags": "-Xms256m -Xmx256m",
        "interfaces": 30, "instances": 10, "workloadId": "30x10-restored",
        "runs": [{
            "runId": model + "-" + str(i),
            "capturedAt": "2026-10-21T10:00:00Z",
            "sampleSeconds": 60.0,
            "origin": "backend-native",
            "nativeMembers": (300 if model == "interface" else 10),
            "activeSubscriptions": 10,
            "watchEvents": 30,
            "consumerRssBytes": 16384000,
            "recoveryMillis": 120.0,
        } for i in range(3)],
    }


class ControlPlaneEvidenceTest(unittest.TestCase):
    def test_complete_inputs_only_report_not_certify(self):
        result = compare(campaign("interface"), campaign("application"))
        self.assertEqual("REPORT_ONLY", result["status"])
        self.assertEqual(3, result["applicationRuns"])
        self.assertEqual(10.0 / 300.0, result["metrics"]["nativeMembers"]["ratio"])

    def test_missing_native_origin_rejected(self):
        app = campaign("application")
        app["runs"][0]["origin"] = "formula-estimate"
        with self.assertRaisesRegex(ValueError, "native backend"):
            compare(campaign("interface"), app)

    def test_cannot_supply_only_analytical_interface_times_instances(self):
        base = campaign("interface")
        base["runs"] = []
        with self.assertRaisesRegex(ValueError, "three real"):
            compare(base, campaign("application"))

    def test_host_and_commit_mismatch_rejected(self):
        app = campaign("application")
        app["hostFingerprintSha256"] = "d" * 64
        with self.assertRaisesRegex(ValueError, "hostFingerprintSha256"):
            compare(campaign("interface"), app)
        app = campaign("application")
        app["commit"] = "a" * 40
        with self.assertRaisesRegex(ValueError, "distinct commits"):
            compare(campaign("interface"), app)

    def test_duplicate_run_id_and_negative_counter_rejected(self):
        app = campaign("application")
        app["runs"][1]["runId"] = app["runs"][0]["runId"]
        with self.assertRaisesRegex(ValueError, "Unique"):
            compare(campaign("interface"), app)
        app = campaign("application")
        app["runs"][0]["watchEvents"] = -1
        with self.assertRaisesRegex(ValueError, "measured field"):
            compare(campaign("interface"), app)

    def test_missing_timestamp_and_invalid_float_rejected(self):
        app = campaign("application")
        app["runs"][0]["capturedAt"] = "2026-10-21T10:00:00"
        with self.assertRaisesRegex(ValueError, "timezone"):
            compare(campaign("interface"), app)
        app = campaign("application")
        app["runs"][0]["consumerRssBytes"] = float("nan")
        with self.assertRaisesRegex(ValueError, "consumerRssBytes"):
            compare(campaign("interface"), app)

    def test_cli_fails_closed_without_raw_data(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            proc = subprocess.run(
                [sys.executable, str(Path(__file__).with_name(
                    "check_v2_19_registration_scale.py")),
                 "--legacy", str(root / "missing.json"),
                 "--application", str(root / "missing2.json"),
                 "--output", str(root / "result.json")],
                capture_output=True, text=True, check=False)
            self.assertEqual(2, proc.returncode)
            self.assertIn("BLOCKED", proc.stderr)
            self.assertFalse((root / "result.json").exists())


if __name__ == "__main__":
    unittest.main()
