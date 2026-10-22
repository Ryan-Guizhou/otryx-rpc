#!/usr/bin/env python3
"""Regression tests for PR gate routing and main-branch no-duplicate policy."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from ci_gate_scope import classify, affected_tests

ROOT = Path(__file__).resolve().parents[1]


class ScopeTest(unittest.TestCase):
    def test_docs_only_is_fast(self):
        flags = classify(["docs/reference/test.md", "README.en-US.md"])
        self.assertTrue(flags["docs"])
        self.assertFalse(flags["runtime"])
        self.assertFalse(flags["security"])
        self.assertFalse(flags["performance"])

    def test_java_and_build_retain_runtime_and_security(self):
        for file in ("otryx-core/src/main/java/demo/Service.java", "pom.xml",
                     "otryx-registry/otryx-registry-etcd/pom.xml",
                     ".github/workflows/ci.yml"):
            with self.subTest(file=file):
                self.assertTrue(classify([file])["runtime"])
                self.assertTrue(classify([file])["security"])

    def test_only_performance_sensitive_code_triggers_jmh_smoke(self):
        self.assertTrue(classify(["otryx-benchmarks/src/main/java/Jmh.java"])["performance"])
        self.assertTrue(classify(["otryx-transport/src/main/java/Tcp.java"])["performance"])
        self.assertFalse(classify(["otryx-core/src/main/java/Client.java"])["performance"])

    def test_script_only_runs_python_not_maven(self):
        flags = classify(["scripts/run_v2_19_controlled_campaign.py"])
        self.assertTrue(flags["scripts"])
        self.assertFalse(flags["runtime"])

    def test_security_scripts_fail_closed_to_runtime_and_security(self):
        flags = classify(["scripts/run_quickstart_security_runner.py"])
        self.assertTrue(flags["runtime"])
        self.assertTrue(flags["security"])

    def test_unknown_module_resource_still_runs_runtime(self):
        self.assertTrue(classify(["otryx-core/src/main/resources/custom.bin"])["runtime"])
        self.assertFalse(classify(["otryx-core/README.md"])["runtime"])

    def test_missing_or_untrusted_paths_fail_closed(self):
        for files in ([], [""], ["../other"], ["/tmp/file"],
                      ["scripts/../foo"], [".\\unsafe"]):
            with self.subTest(files=files), self.assertRaises(ValueError):
                classify(files)

    def test_targeted_fixtures(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scripts = root / "scripts"
            scripts.mkdir()
            (scripts / "test_v2_19_controlled_campaign.py").write_text("pass")
            (scripts / "test_other.py").write_text("pass")
            self.assertEqual([scripts / "test_v2_19_controlled_campaign.py"],
                             affected_tests(["scripts/run_v2_19_controlled_campaign.py",
                                             "docs/a.md"], root))

    def test_docs_do_not_trigger_java_style_or_rpc_comparison(self):
        style = (ROOT / ".github/workflows/java-style-audit.yml").read_text(encoding="utf-8")
        self.assertNotIn("docs/engineering/**", style)
        comparison = (ROOT / ".github/workflows/rpc-comparison-smoke.yml").read_text(
            encoding="utf-8")
        self.assertNotIn('"benchmarks/rpc-comparison/**"', comparison)

    def test_no_duplicate_main_push_quality_workflows(self):
        for file in sorted((ROOT / ".github/workflows").glob("*.yml")):
            source = file.read_text(encoding="utf-8")
            block = source.split("on:\n", 1)
            self.assertEqual(2, len(block), str(file))
            triggers = block[1].split("\njobs:\n", 1)[0]
            self.assertNotIn("\n  push:", "\n" + triggers, str(file))

    def test_required_context_never_skips_failure(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("\n  verify:\n    if: ${{ always() }}", ci)
        self.assertIn("needs: [scope, integration]", ci)
        self.assertIn('test "$SCOPE_STATUS" = success', ci)
        self.assertIn('test "$INTEGRATION_STATUS" = success', ci)
        self.assertIn('test "$INTEGRATION_STATUS" = skipped', ci)
        self.assertNotIn("\n  push:", ci)
        docs = (ROOT / ".github/workflows/documentation-previews.yml").read_text(
            encoding="utf-8")
        self.assertIn("steps.scope.outputs.docs == 'true'", docs)
        self.assertNotIn("\n  push:", docs)


if __name__ == "__main__":
    unittest.main()
