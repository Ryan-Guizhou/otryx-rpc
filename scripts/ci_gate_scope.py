#!/usr/bin/env python3
"""Classify PR changes into stable, fail-closed OTRYX quality gate scopes."""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC_EXTENSIONS = {".md", ".mdx", ".mmd", ".svg", ".png", ".webp", ".jpg", ".jpeg"}
RUNTIME_ROOT_FILES = {"pom.xml", "mvnw", "mvnw.cmd", ".github/workflows/ci.yml"}
RUNTIME_SCRIPTS = {
    "run_quickstart_memory.sh", "run_quickstart_process_e2e.sh",
    "run_quickstart_revision_e2e.sh", "run_quickstart_mtls_e2e.sh",
    "run_three_process_deadline_e2e.sh", "etcd_security_fixture.py",
    "run_v2d2_benchmark_matrix.sh", "run_v2d2_soak.sh",
}
PERFORMANCE_SCRIPTS = {"run_v2d2_benchmark_matrix.sh", "run_v2d2_soak.sh"}
TEST_ALIASES = {
    "run_v2_19_controlled_campaign.py": "test_v2_19_controlled_campaign.py",
    "check_v2_19_registration_scale.py": "test_v2_19_registration_scale.py",
    "check_u13_campaign.py": "test_u13_campaign.py",
}


def classify(paths: list[str]) -> dict[str, bool]:
    """Fail closed when the PR changed-file set is missing or unsafe."""
    if not paths:
        raise ValueError("Missing changed-file evidence; refusing to skip runtime checks")
    flags = {key: False for key in ("runtime", "performance", "security", "docs", "scripts")}
    for raw in paths:
        name = raw.strip()
        if not name or name.startswith("/") or "\\" in name or "\x00" in name \
                or ".." in Path(name).parts or name.startswith("-"):
            raise ValueError("Unsafe or empty PR file path")
        path = Path(name)
        module = name.startswith("otryx-") or name.startswith("benchmarks/")
        is_doc = path.suffix.lower() in DOC_EXTENSIONS
        if is_doc or name in ("README.md", "README.en-US.md",
                              "scripts/check_doc_previews.py",
                              ".github/workflows/documentation-previews.yml"):
            flags["docs"] = True
        if name.startswith("scripts/") and path.suffix in (".py", ".sh"):
            flags["scripts"] = True
        runtime = (name in RUNTIME_ROOT_FILES or name.startswith(".mvn/")
                   or (module and not is_doc)
                   or (name.startswith("scripts/") and path.name in RUNTIME_SCRIPTS)
                   or (name.startswith("scripts/") and not path.name.startswith("test_")
                       and any(part in path.name for part in
                               ("security", "tls", "mtls", "registry"))))
        if runtime:
            flags["runtime"] = True
        if (name.startswith(("otryx-benchmarks/", "otryx-transport/",
                             "otryx-serialization/"))
                or (name.startswith("scripts/") and path.name in PERFORMANCE_SCRIPTS)
                or name == ".github/workflows/ci.yml"):
            flags["performance"] = True
        if ((runtime and name.startswith(("otryx-core/", "otryx-registry/",
                                          "otryx-transport/", "otryx-spring-boot/",
                                          "otryx-quickstart/")))
                or name in ("pom.xml", ".github/workflows/ci.yml")
                or (name.startswith("scripts/") and "security" in path.name)):
            flags["security"] = True
    return flags


def affected_tests(paths: list[str], root: Path = ROOT) -> list[Path]:
    """Run only directly affected, existing Python regression suites."""
    selected = set()
    for raw in paths:
        p = Path(raw.strip())
        if p.parent != Path("scripts") or p.suffix != ".py":
            continue
        name = p.name if p.name.startswith("test_") else TEST_ALIASES.get(
            p.name, "test_" + p.name)
        path = root / "scripts" / name
        if path.is_file():
            selected.add(path)
    return sorted(selected)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--changed", type=Path, required=True)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--run-tests", action="store_true")
    args = parser.parse_args()
    try:
        paths = args.changed.read_text(encoding="utf-8").splitlines()
        flags = classify(paths)
        if args.github_output is not None:
            with args.github_output.open("a", encoding="utf-8") as output:
                for key, value in flags.items():
                    output.write(f"{key}={'true' if value else 'false'}\n")
        if args.run_tests:
            subprocess.run([sys.executable, "-m", "compileall", "-q", "scripts"],
                           cwd=ROOT, check=True)
            for suite in affected_tests(paths):
                subprocess.run([sys.executable, str(suite)], cwd=ROOT, check=True,
                               timeout=180)
        print("PR scope: " + ", ".join(k for k, enabled in flags.items() if enabled))
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError,
            subprocess.TimeoutExpired) as error:
        print(f"BLOCKED: PR scope verification failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
