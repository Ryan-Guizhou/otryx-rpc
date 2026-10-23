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
from zipfile import BadZipFile, ZIP_STORED, ZipFile

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
            if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f"Release artifact missing/empty/linked: {relative}")
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


def verify_public_artifact_archive(
        path: Path, inventory: list[dict[str, object]]
) -> None:
    """Read back the actual public JAR bytes; reject omissions and wrong digests."""
    expected = {item["path"]: item for item in inventory}
    if len(expected) != len(inventory):
        raise ValueError("Duplicate paths in release artifact inventory")
    try:
        with ZipFile(path, "r") as archive:
            names = archive.namelist()
            if len(names) != len(expected) or set(names) != set(expected):
                raise ValueError("Unsigned archive is missing or has extra public JARs")
            for name, item in expected.items():
                info = archive.getinfo(name)
                if info.is_dir() or info.file_size != item["size"]:
                    raise ValueError(f"Unexpected archived JAR size: {name}")
                digest = hashlib.sha256()
                with archive.open(info, "r") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                if digest.hexdigest() != item["sha256"]:
                    raise ValueError(f"Archived JAR does not match manifest: {name}")
    except (BadZipFile, KeyError, EOFError) as error:
        raise ValueError(f"Unsigned public artifact archive is invalid: {error}") from error


def build_public_artifact_archive(
        root: Path, output_dir: Path, inventory: list[dict[str, object]]
) -> dict[str, object]:
    """Store only the inspected public JARs; no Quickstart/benchmark or signatures."""
    archive_path = output_dir / "public-artifacts.zip"
    if archive_path.exists() or archive_path.is_symlink():
        raise ValueError(f"Refusing to overwrite an existing unsigned archive: {archive_path}")
    pending = output_dir / ".public-artifacts.partial"
    if pending.exists():
        raise ValueError("A prior incomplete release archive must be inspected first")
    try:
        with ZipFile(pending, "w", compression=ZIP_STORED, allowZip64=True) as archive:
            for item in inventory:
                relative = Path(item["path"])
                path = root / relative
                if (relative.is_absolute() or ".." in relative.parts
                        or path.is_symlink() or not path.is_file()
                        or not path.resolve().is_relative_to(root.resolve())
                        or path.stat().st_size != item["size"]
                        or sha256(path) != item["sha256"]):
                    raise ValueError(f"Source JAR changed since evidence inspection: {relative}")
                archive.write(path, arcname=relative.as_posix())
        verify_public_artifact_archive(pending, inventory)
        pending.replace(archive_path)
    except (ValueError, OSError, BadZipFile):
        pending.unlink(missing_ok=True)
        raise
    return {"path": archive_path.name, "size": archive_path.stat().st_size,
            "sha256": sha256(archive_path), "fileCount": len(inventory)}


def save_evidence(
        evidence: dict[str, object], output_dir: Path, bom_path: Path,
        root: Path = ROOT,
) -> None:
    """Finish source-linked evidence after archiving actual public JARs."""
    output_dir.mkdir(parents=True, exist_ok=True)
    archive = build_public_artifact_archive(root, output_dir, evidence["artifacts"])
    report = dict(evidence, archive=archive)
    (output_dir / "manifest.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    lines = [
        f"{item['sha256']}  {item['path']}"
        for item in evidence["artifacts"]
    ]
    lines.append(f"{evidence['sbom']['sha256']}  {bom_path.as_posix()}")
    try:
        archive_ref = (output_dir / archive["path"]).relative_to(root).as_posix()
    except ValueError:
        archive_ref = archive["path"]
    lines.append(f"{archive['sha256']}  {archive_ref}")
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
        save_evidence(evidence, output, args.sbom, root=ROOT)
        print(f"Unsigned dry-run evidence verified: {args.version}, "
              f"{len(evidence['artifacts'])} public artifact files")
        return 0
    except (ValueError, OSError, ET.ParseError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
