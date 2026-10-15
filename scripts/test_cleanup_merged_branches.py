#!/usr/bin/env python3
"""Fail-closed integration regression for merged-branch cleanup, using a local bare git remote."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("cleanup_merged_branches.sh")


def git(cwd: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=cwd, check=True, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return result.stdout.strip()


def create_commit(work: Path, name: str, content: str) -> str:
    (work / name).write_text(content, encoding="utf-8")
    git(work, "add", name)
    git(work, "commit", "-m", f"Add {name} on {git(work, 'branch', '--show-current')}")
    return git(work, "rev-parse", "HEAD")


class BranchCleanupTests(unittest.TestCase):
    """Exercise real local git refs, including a squash-merged topic branch."""

    def test_workflow_uses_verified_closed_pr_snapshot(self) -> None:
        workflow = (Path(__file__).resolve().parents[1] / ".github"
                    / "workflows" / "prune-merged-branches.yml").read_text(encoding="utf-8")
        for marker in (
            "pulls?state=open&per_page=100",
            "pulls?state=closed&per_page=100",
            ".merged_at != null",
            ".head.repo.full_name == .base.repo.full_name",
            "MERGED_PR_HEAD_FILE=",
            "OPEN_PR_HEAD_FILE=",
        ):
            self.assertIn(marker, workflow, f"Cleanup workflow missing {marker}")

    def test_only_reachable_and_exact_merged_pr_heads_are_pruned(self) -> None:
        with tempfile.TemporaryDirectory(prefix="otryx-prune-") as temp:
            root = Path(temp)
            bare = root / "remote.git"
            git(root, "init", "--bare", "--initial-branch=main", str(bare))
            work = root / "work"
            git(root, "clone", str(bare), str(work))
            git(work, "config", "user.name", "Test")
            git(work, "config", "user.email", "test@example.com")
            create_commit(work, "README", "base\n")
            git(work, "push", "-u", "origin", "main")

            git(work, "switch", "-c", "feat/squashed")
            squash_tip = create_commit(work, "squashed.txt", "same change\n")
            git(work, "push", "origin", "feat/squashed")
            git(work, "switch", "main")
            merged_commit = create_commit(work, "squashed.txt", "same change\n")
            git(work, "push", "origin", "main")
            self.assertNotEqual(squash_tip, merged_commit)
            self.assertNotEqual(
                subprocess.run(
                    ["git", "merge-base", "--is-ancestor", squash_tip, merged_commit],
                    cwd=work, check=False,
                ).returncode, 0,
                "The squash PR fixture must not be a main ancestor",
            )

            for name in (
                "chore/ancestor", "feat/open", "stable/baseline", "release/candidate"
            ):
                git(work, "branch", name, "main")
                git(work, "push", "origin", name)

            git(work, "switch", "-c", "feat/unmerged")
            unmerged_tip = create_commit(work, "unmerged.txt", "must survive\n")
            git(work, "push", "origin", "feat/unmerged")
            git(work, "switch", "main")

            git(work, "switch", "-c", "feat/advanced")
            old_tip = create_commit(work, "old.txt", "old\n")
            create_commit(work, "new.txt", "new, unmerged\n")
            git(work, "push", "origin", "feat/advanced")
            git(work, "switch", "main")

            open_heads = root / "open-pr-heads.txt"
            open_heads.write_text("feat/open\n", encoding="utf-8")
            merged_heads = root / "merged-pr-heads.tsv"
            merged_heads.write_text(
                f"feat/squashed\t{squash_tip}\t{merged_commit}\n"
                f"feat/advanced\t{old_tip}\t{merged_commit}\n"
                f"feat/unmerged\t{unmerged_tip}\t{'f' * 40}\n",
                encoding="utf-8",
            )
            env = dict(os.environ, GIT_REMOTE="origin", PRIMARY_BRANCH="main",
                       OPEN_PR_HEAD_FILE=str(open_heads),
                       MERGED_PR_HEAD_FILE=str(merged_heads))

            def exists(name: str) -> bool:
                return bool(git(work, "ls-remote", "--heads", "origin", name))

            # A destructive run must fail closed if merged PR inventory is absent.
            missing = subprocess.run(
                ["bash", str(SCRIPT)], cwd=work,
                env=dict(env, CLEANUP_MODE="apply", MERGED_PR_HEAD_FILE=""),
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                check=False,
            )
            self.assertNotEqual(missing.returncode, 0, missing.stdout)
            self.assertTrue(exists("feat/squashed"))

            dry = subprocess.run(
                ["bash", str(SCRIPT)], cwd=work,
                env=dict(env, CLEANUP_MODE="dry-run"),
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                check=False,
            )
            self.assertEqual(dry.returncode, 0, dry.stdout + dry.stderr)
            self.assertIn("feat/squashed", dry.stdout)
            self.assertTrue(exists("feat/squashed"))

            apply = subprocess.run(
                ["bash", str(SCRIPT)], cwd=work,
                env=dict(env, CLEANUP_MODE="apply"),
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                check=False,
            )
            self.assertEqual(apply.returncode, 0, apply.stdout + apply.stderr)
            for deleted in ("feat/squashed", "chore/ancestor"):
                self.assertFalse(exists(deleted), f"Not deleted: {deleted}")
            for kept in (
                "main", "feat/open", "feat/unmerged", "feat/advanced",
                "stable/baseline", "release/candidate",
            ):
                self.assertTrue(exists(kept), f"Incorrectly deleted: {kept}")
            self.assertIn("deleted=2", apply.stdout)
            repeated = subprocess.run(
                ["bash", str(SCRIPT)], cwd=work,
                env=dict(env, CLEANUP_MODE="apply"),
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                check=False,
            )
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            self.assertIn("deleted=0", repeated.stdout)


if __name__ == "__main__":
    unittest.main()
