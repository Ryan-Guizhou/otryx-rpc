#!/usr/bin/env python3
"""Build an unsigned, non-publishing RC/GA evidence manifest for the OTRYX source tree."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from check_central_publication import PUBLIC_MODULES, module_path

ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
STAGES = {"rc1": "1.0.0-RC1", "ga": "1.0.0"}
SNAPSHOT = "1.0.0-SNAPSHOT"
COMMIT_SHA = re.compile(r"[0-9a-f]{40}\Z")


def check_stage(stage: str, version: str) -> None:
    """Reject arbitrary or historical patch versions in the first-release dry-run."""
    if stage not in STAGES or version != STAGES[stage]:
        raise ValueError(f"Unsupported first-release stage/version: {stage!r}/{version!r}")


def check_development_source(root: Path) -> None:
    """Ensure the dry-run leaves checked-in development versions unchanged."""
    status_file = root / "docs/release-status.properties"
    status = dict(
        line.split("=", 1)
        for line in status_file.read_text(encoding="utf-8").splitlines()
        if line and not line.lstrip().startswith("#") and "=" in line
    )
    if status.get("project") != "development" or status.get("version") != SNAPSHOT:
        raise ValueError("Dry-run requires the unchanged development release status")
    pom = ET.parse(root / "pom.xml").getroot()
    revision = pom.findtext("m:properties/m:revision", namespaces=NAMESPACE)
    if revision != SNAPSHOT:
        raise ValueError(f"Dry-run cannot mutate root Maven revision: {revision!r}")


def sha256(path: Path) -> str:
    """Hash a file in bounded chunks so large artifact sets do not expand memory."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def public_artifacts(root: Path, version: str) -> list[dict[str, object]]:
    """Require all public artifacts and their real source/Javadoc classifiers."""
    inventory: list[dict[str, object]] = []
    for module in PUBLIC_MODULES:
        for classifier in ("", "-sources", "-javadoc"):
            relative = (
                module_path(module)
                / "target"
                / f"{module}-{version}{classifier}.jar"
            )
            path = root / relative
            if not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f"Release artifact missing/empty: {relative}")
            inventory.append({
                "path": relative.as_posix(),
                "size": path.stat().st_size,
                "sha256": sha256(path),
            })
    return inventory


def check_sbom(path: Path, version: str) -> dict[str, object]:
    """Require an actual CycloneDX aggregate with a matching stage version."""
    if not path.is_file():
        raise ValueError(f"Missing SBOM: {path}")
    bom = json.loads(path.read_text(encoding="utf-8"))
    if bom.get("bomFormat") != "CycloneDX":
        raise ValueError("SBOM is not CycloneDX")
    if str(bom.get("specVersion")) not in {"1.6", "1.7"}:
        raise ValueError("Unsupported CycloneDX schema version")
    component = bom.get("metadata", {}).get("component", {})
    if component.get("name") != "otryx-rpc" or component.get("version") != version:
        raise ValueError("Aggregate SBOM has the wrong root artifact/version")
    if not bom.get("components"):
        raise ValueError("Aggregate SBOM has no dependency components")
    return {
        "path": path.name,
        "sha256": sha256(path),
        "components": len(bom["components"]),
        "specVersion": bom["specVersion"],
    }


def build_manifest(
        root: Path,
        stage: str,
        version: str,
        source_sha: str,
        bom_path: Path,
) -> dict[str, object]:
    """Build evidence without performing any signing, publishing, or tagging."""
    check_stage(stage, version)
    check_development_source(root)
    if not COMMIT_SHA.fullmatch(source_sha):
        raise ValueError("source SHA must be an exact 40-character Git commit SHA")
    return {
        "project": "otryx-rpc",
        "stage": stage,
        "version": version,
        "sourceSha": source_sha,
        "publication": "disabled",
        "signing": "not-run",
        "classification": "unsigned-rc-ga-dry-run",
        "artifacts": public_artifacts(root, version),
        "sbom": check_sbom(bom_path, version),
    }


def save_evidence(
        evidence: dict[str, object], output_dir: Path, bom_path: Path
) -> None:
    """Write source-linked checksums and a machine-readable dry-run result."""
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "manifest.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    lines = [
        f"{item['sha256']}  {item['path']}"
        for item in evidence["artifacts"]
    ]
    lines.append(f"{evidence['sbom']['sha256']}  {bom_path.as_posix()}")
    (output_dir / "SHA256SUMS").write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--preflight-only", action="store_true")
    parser.add_argument("--sbom", type=Path, default=Path("target/bom.json"))
    parser.add_argument("--source-sha", default="")
    parser.add_argument("--output-dir", type=Path,
                        default=Path("target/first-release-evidence"))
    args = parser.parse_args()
    try:
        check_stage(args.stage, args.version)
        check_development_source(ROOT)
        if args.preflight_only:
            print(f"OTRYX first-release dry-run preflight OK: {args.version}")
            return 0
        current_sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip()
        if args.source_sha != current_sha:
            raise ValueError("source SHA does not match checked-out HEAD")
        sbom = args.sbom if args.sbom.is_absolute() else ROOT / args.sbom
        evidence = build_manifest(ROOT, args.stage, args.version,
                                  current_sha, sbom)
        output = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
        save_evidence(evidence, output, args.sbom)
        print(f"Unsigned dry-run evidence verified: {args.version}, "
              f"{len(evidence['artifacts'])} public artifact files")
        return 0
    except (ValueError, OSError, ET.ParseError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
