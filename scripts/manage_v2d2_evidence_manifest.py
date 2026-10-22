#!/usr/bin/env python3
"""Create or verify an auditable SHA-256 manifest for one evidence bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
import re

MANIFEST_NAME = "evidence-manifest.json"
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def files(root: Path) -> list[dict[str, object]]:
    """Hash only regular bundle files; never follow external evidence symlinks."""
    if not root.is_dir() or root.is_symlink():
        raise ValueError(f"Evidence root must be a real directory: {root}")
    entries = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Symlinked evidence is not allowed: {path.relative_to(root)}")
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if relative == MANIFEST_NAME:
            continue
        entries.append({
            "path": relative,
            "sizeBytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    return entries


def create(root: Path) -> int:
    environment_path = root / "environment.properties"
    if root.is_symlink() or environment_path.is_symlink() or (root / MANIFEST_NAME).is_symlink():
        raise ValueError("Symlinked evidence root, environment or manifest is forbidden")
    if not environment_path.is_file():
        raise SystemExit("Evidence bundle is missing environment.properties")
    environment = properties(environment_path)
    manifest = {
        "schemaVersion": 1,
        "createdAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "runId": environment.get("run_id", "unknown"),
        "commit": environment.get("commit", "unknown"),
        "runnerId": environment.get("runner_id", "unknown"),
        "evidenceClass": environment.get("evidence_class", "unknown"),
        "fileCount": 0,
        "totalBytes": 0,
        "files": files(root),
    }
    manifest["fileCount"] = len(manifest["files"])
    manifest["totalBytes"] = sum(
        int(entry["sizeBytes"]) for entry in manifest["files"]
    )
    (root / MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"Evidence manifest created: {root / MANIFEST_NAME}")
    return 0


def validate_manifest(root: Path) -> dict[str, object]:
    """Validate manifest schema, provenance, unique paths and every real byte."""
    if not root.is_dir() or root.is_symlink():
        raise ValueError(f"Invalid or symlinked evidence root: {root}")
    manifest_path = root / MANIFEST_NAME
    environment_path = root / "environment.properties"
    if (manifest_path.is_symlink() or not manifest_path.is_file()
            or environment_path.is_symlink() or not environment_path.is_file()):
        raise ValueError("Missing, invalid or symlinked evidence manifest/environment")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or type(manifest.get("schemaVersion")) is not int \
            or manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported evidence manifest schema")
    created_at = manifest.get("createdAt")
    if not isinstance(created_at, str):
        raise ValueError("Missing manifest creation timestamp")
    try:
        created = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("Malformed manifest creation timestamp") from error
    if created.tzinfo is None or created.utcoffset() is None:
        raise ValueError("Manifest timestamp must contain timezone")
    environment = properties(environment_path)
    for manifest_key, property_key in (
            ("runId", "run_id"), ("commit", "commit"),
            ("runnerId", "runner_id"), ("evidenceClass", "evidence_class")):
        if manifest.get(manifest_key) != environment.get(property_key, "unknown"):
            raise ValueError(f"Manifest/environment provenance mismatch: {manifest_key}")
    declared = manifest.get("files")
    if not isinstance(declared, list):
        raise ValueError("Manifest files must be an array")
    expected: dict[str, dict[str, object]] = {}
    for entry in declared:
        if not isinstance(entry, dict):
            raise ValueError("Malformed manifest entry")
        name = entry.get("path")
        if not isinstance(name, str) or not name or "\\" in name:
            raise ValueError("Unsafe manifest entry path")
        path = PurePosixPath(name)
        if (path.is_absolute() or ".." in path.parts or path.as_posix() != name
                or name == MANIFEST_NAME or name in expected):
            raise ValueError(f"Duplicate, unsafe or noncanonical evidence path: {name}")
        size, digest = entry.get("sizeBytes"), entry.get("sha256")
        if type(size) is not int or size < 0:
            raise ValueError(f"Invalid manifest file size: {name}")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise ValueError(f"Invalid manifest SHA-256: {name}")
        expected[name] = entry
    declared_count = manifest.get("fileCount")
    declared_bytes = manifest.get("totalBytes")
    if type(declared_count) is not int or declared_count != len(declared):
        raise ValueError("Manifest fileCount does not match inventory")
    if type(declared_bytes) is not int or declared_bytes != sum(
            entry["sizeBytes"] for entry in expected.values()):
        raise ValueError("Manifest totalBytes does not match inventory")
    actual = {entry["path"]: entry for entry in files(root)}
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    changed = sorted(
        name for name in set(expected) & set(actual)
        if expected[name].get("sha256") != actual[name]["sha256"]
        or expected[name].get("sizeBytes") != actual[name]["sizeBytes"]
    )
    if missing or extra or changed:
        raise ValueError("Evidence manifest inventory mismatch: "
                         + "; ".join(f"{label}={','.join(names)}"
                                     for label, names in (
                                         ("missing", missing),
                                         ("extra", extra),
                                         ("changed", changed)) if names))
    return manifest


def verify(root: Path) -> int:
    """Verify the evidence manifest and preserve CLI failure semantics."""
    try:
        validate_manifest(root)
    except (ValueError, OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"Evidence manifest verification failed: {error}") from error
    print("Evidence manifest verification passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("create", "verify"))
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "create":
        return create(args.bundle)
    return verify(args.bundle)


if __name__ == "__main__":
    raise SystemExit(main())
