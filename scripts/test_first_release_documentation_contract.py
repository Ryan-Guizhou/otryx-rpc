#!/usr/bin/env python3
"""Negative regression fixtures for PR-only first-release engineering acceptance."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_project


class FirstReleaseDocumentationContractTest(unittest.TestCase):
    def _source(self, root: Path) -> None:
        work = root / ".github/workflows"
        docs = root / "docs/reference"
        work.mkdir(parents=True)
        docs.mkdir(parents=True)
        (work / "first-release-dry-run.yml").write_text(
            "name: Unsigned RC and GA\non:\n  pull_request:\n"
            "  workflow_dispatch:\njobs:\n  verify:\n", encoding="utf-8")
        (docs / "first-release-acceptance.md").write_text(
            "PR Head is checked; no duplicate post-merge builds.\n", encoding="utf-8")
        (docs / "v2-20-final-gates.md").write_text(
            "A maintainer starts the release for a verified SHA.\n", encoding="utf-8")

    def test_current_repository_contract(self) -> None:
        check_project.check_first_release_documentation_contract()

    def test_fail_closed_if_automatic_main_rebuild_returns(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._source(root)
            wf = root / ".github/workflows/first-release-dry-run.yml"
            wf.write_text(wf.read_text() + "\n  push:\n    branches: [main]\n",
                          encoding="utf-8")
            with patch.object(check_project, "ROOT", root):
                with self.assertRaises(SystemExit):
                    check_project.check_first_release_documentation_contract()

    def test_fail_closed_if_docs_claim_duplicate_rc_ga(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._source(root)
            doc = root / "docs/reference/first-release-acceptance.md"
            doc.write_text("合并 main 后会再次执行两个阶段\n", encoding="utf-8")
            with patch.object(check_project, "ROOT", root):
                with self.assertRaises(SystemExit):
                    check_project.check_first_release_documentation_contract()


if __name__ == "__main__":
    unittest.main()
