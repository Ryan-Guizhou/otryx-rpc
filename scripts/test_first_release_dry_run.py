#!/usr/bin/env python3
"""Self-tests: first-release dry-run must fail closed on version/artifact/SBOM drift."""

from __future__ import annotations

import json
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

from check_central_publication import PUBLIC_MODULES, module_path
from first_release_dry_run import (
    SNAPSHOT,
    build_manifest,
    check_development_source,
    check_stage,
    public_artifacts,
    save_evidence,
)

FAKE_SHA = "a" * 40


def expect_invalid(operation) -> None:
    try:
        operation()
    except ValueError:
        return
    raise AssertionError("Expected the dry-run gate to reject invalid evidence")


def make_fixture(root: Path, version: str) -> Path:
    (root / "docs").mkdir()
    (root / "docs/release-status.properties").write_text(
        f"project=development\nversion={SNAPSHOT}\n", encoding="utf-8"
    )
    pom = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
    props = ET.SubElement(pom, "properties")
    ET.SubElement(props, "revision").text = SNAPSHOT
    ET.ElementTree(pom).write(root / "pom.xml", encoding="unicode")
    for module in PUBLIC_MODULES:
        target = root / module_path(module) / "target"
        target.mkdir(parents=True)
        for classifier in ("", "-sources", "-javadoc"):
            (target / f"{module}-{version}{classifier}.jar").write_bytes(b"JAR")
    bom = root / "target/bom.json"
    bom.parent.mkdir()
    bom.write_text(json.dumps({
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "metadata": {"component": {
            "name": "otryx-rpc", "version": version
        }},
        "components": [{"type": "library", "name": "demo", "version": "1"}],
    }), encoding="utf-8")
    return bom


def main() -> None:
    check_stage("rc1", "1.0.0-RC1")
    check_stage("ga", "1.0.0")
    expect_invalid(lambda: check_stage("rc1", "1.0.0"))
    expect_invalid(lambda: check_stage("ga", "1.0.1"))
    expect_invalid(lambda: check_stage("patch", "1.0.1"))
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        version = "1.0.0-RC1"
        bom = make_fixture(root, version)
        check_development_source(root)
        evidence = build_manifest(root, "rc1", version, FAKE_SHA, bom)
        assert len(evidence["artifacts"]) == len(PUBLIC_MODULES) * 3
        assert evidence["publication"] == "disabled"
        output = root / "evidence"
        save_evidence(evidence, output, Path("target/bom.json"))
        assert (output / "manifest.json").is_file()
        assert len((output / "SHA256SUMS").read_text().splitlines()) == len(PUBLIC_MODULES) * 3 + 1

        original = root / module_path(PUBLIC_MODULES[0]) / "target"
        (original / f"{PUBLIC_MODULES[0]}-{version}-sources.jar").unlink()
        expect_invalid(lambda: public_artifacts(root, version))
        (original / f"{PUBLIC_MODULES[0]}-{version}-sources.jar").write_bytes(b"JAR")
        expect_invalid(lambda: build_manifest(root, "rc1", version, "invalid", bom))

        source_bom = json.loads(bom.read_text(encoding="utf-8"))
        source_bom["metadata"]["component"]["version"] = "1.0.0"
        bom.write_text(json.dumps(source_bom), encoding="utf-8")
        expect_invalid(lambda: build_manifest(root, "rc1", version, FAKE_SHA, bom))
        (root / "docs/release-status.properties").write_text(
            "project=ga\nversion=1.0.0\n", encoding="utf-8"
        )
        expect_invalid(lambda: check_development_source(root))
    workflow = (Path(__file__).resolve().parents[1]
                / ".github/workflows/first-release-dry-run.yml").read_text(encoding="utf-8")
    for required in ("  pull_request:", "  push:", "  workflow_dispatch:",
                     "fail-fast: false", '["rc1","ga"]',
                     "github.event.pull_request.head.sha || github.sha",
                     'SOURCE_SHA:', '--source-sha "$SOURCE_SHA"'):
        assert required in workflow, f"Unsigned release workflow lost required guard: {required}"
    assert "central-release" not in workflow
    assert "--source-sha \"$GITHUB_SHA\"" not in workflow
    print("OTRYX first-release dry-run negative fixtures passed")


if __name__ == "__main__":
    main()
