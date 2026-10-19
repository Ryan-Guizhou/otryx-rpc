#!/usr/bin/env python3
"""Build isolated Maven SBOMs and reject incomplete OSV dependency evidence.

The vulnerability policy remains in the pinned OSV diff reporter. This script
proves that its inputs cover every resolved package from every Maven root.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import selectors
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote


CYCLONEDX_VERSION = "2.9.3"
OSV_VERSION = "2.7.0"
OSV_SHA256 = "160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b"
ROOT_POMS = {
    "main": "pom.xml",
    "comparison": "benchmarks/rpc-comparison/pom.xml",
    "eureka": ".github/test-fixtures/eureka-server/pom.xml",
}
SIDES = {"baseline": "old-results.json", "candidate": "new-results.json"}
ETCD_TRANSITIVES = {
    "io.etcd:jetcd-core", "io.grpc:grpc-core", "com.google.protobuf:protobuf-java",
    "com.google.guava:guava", "io.vertx:vertx-core", "io.vertx:vertx-grpc",
}
EXTRACTION_ERROR = re.compile(
    r"error during extraction|failed (?:resolution|resolving)|"
    r"unable to resolve|could not resolve|failed to resolve|"
    r"failed to (?:extract|parse|query)|could not query|no packages found|"
    r"(?:error|failed).*osv.*(?:api|query)|\[ERROR\]|"
    r"^\s*(?:\x1b\[[0-9;]*m)*(?:error|fatal)(?:[:\s]|\x1b)", re.IGNORECASE | re.MULTILINE)
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Missing or invalid JSON evidence: {path}") from error
    require(isinstance(value, dict), f"Expected JSON object: {path}")
    return value


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def checked_file(root: Path, relative: str, digest: str) -> Path:
    require(isinstance(relative, str) and relative, "Missing evidence path")
    path = (root / relative).resolve()
    require(path.is_relative_to(root.resolve()) and path.is_file(),
            f"Missing or external evidence file: {relative}")
    require(sha256(path) == digest, f"Evidence hash mismatch: {relative}")
    return path


def concrete(value: str) -> bool:
    return (isinstance(value, str) and bool(value.strip()) and "${" not in value
            and not any(character in value for character in "[](),")
            and value.upper() not in {"LATEST", "RELEASE"})


def component_package(component: dict) -> tuple[str, str]:
    version = component.get("version")
    require(concrete(version), "SBOM contains an unresolved or missing version")
    purl = component.get("purl", "")
    match = re.fullmatch(r"pkg:maven/([^/]+)/([^@]+)@([^?#]+)(?:\?[^#]*)?(?:#.*)?", purl)
    require(match is not None, "SBOM component has no concrete Maven purl")
    group, artifact, purl_version = (unquote(part) for part in match.groups())
    require(version == purl_version, "SBOM version differs from its purl")
    require(component.get("group") == group and component.get("name") == artifact,
            "SBOM component identity differs from its purl")
    return f"{group}:{artifact}", version


def sbom_packages(path: Path, modules: list[dict]) -> set[tuple[str, str]]:
    bom = read_json(path)
    require(bom.get("bomFormat") == "CycloneDX" and bom.get("specVersion") == "1.6",
            f"Unsupported SBOM format: {path}")
    components = bom.get("components")
    require(isinstance(components, list) and bool(components), f"Empty SBOM: {path}")
    require(bool(modules), "Missing reactor module inventory")
    own = bom.get("metadata", {}).get("component", {})
    require(component_package(own) == (modules[0]["name"], modules[0]["version"]),
            f"SBOM root differs from the source POM: {path}")
    packages = {component_package(component) for component in components}
    packages.add(component_package(own))
    expected = {(module["name"], module["version"]) for module in modules}
    require(expected.issubset(packages), f"SBOM reactor module coverage is incomplete: {path}")
    references = {component.get("bom-ref") for component in [own, *components]}
    require(None not in references and "" not in references, "SBOM has an unnamed graph component")
    graph = bom.get("dependencies")
    require(isinstance(graph, list) and bool(graph), "SBOM has no dependency graph")
    for dependency in graph:
        require(dependency.get("ref") in references, "SBOM graph refers to an absent component")
        require(set(dependency.get("dependsOn", [])).issubset(references),
                "SBOM graph has an unresolved transitive reference")
    return packages


def validate_evidence(
        root: Path, base_sha: str, head_sha: str, merge_sha: str,
        require_scans: bool = True) -> dict:
    root = root.resolve()
    manifest = read_json(root / "manifest.json")
    for label, expected in (("baseSha", base_sha), ("headSha", head_sha), ("mergeSha", merge_sha)):
        require(re.fullmatch(r"[0-9a-f]{40}", expected) is not None
                and manifest.get(label) == expected, f"Evidence SHA mismatch: {label}")
    require(manifest.get("mergeParents") == [base_sha, head_sha], "Merge SHA parents do not match base and Head")
    require(manifest.get("schemaVersion") == 1 and manifest.get("status") == "complete",
            "Dependency build evidence is incomplete")
    require(manifest.get("cycloneDxVersion") == CYCLONEDX_VERSION,
            "Unpinned CycloneDX generator")
    sides = manifest.get("sides", {})
    require(set(sides) == set(SIDES), "Both baseline and candidate builds are required")
    for field in ("mavenRepository", "worktree"):
        values = [Path(sides[side].get(field, "")).resolve() for side in SIDES]
        require(values[0] != values[1] and not values[0].is_relative_to(values[1])
                and not values[1].is_relative_to(values[0]), f"Builds must use isolated {field} paths")
    summary = {}
    for side, expected_sha in (("baseline", base_sha), ("candidate", merge_sha)):
        data = sides[side]
        require(data.get("sourceSha") == expected_sha, f"Build source SHA mismatch: {side}")
        records = data.get("sboms", [])
        require(len(records) == len(ROOT_POMS) and {record.get("root") for record in records} == set(ROOT_POMS),
                f"SBOM roots are incomplete: {side}")
        modules = [module for record in records for module in record.get("modules", [])]
        paths = [module["pom"] for module in modules]
        require(len(paths) == len(set(paths)) and set(paths) == set(data.get("trackedPoms", [])),
                f"Source POM coverage is incomplete: {side}")
        local = {(module["name"], module["version"]) for module in modules}
        inventory = {}
        summary[side] = {}
        for record in records:
            name = record["root"]
            require(record["modules"][0]["pom"] == ROOT_POMS[name], "Wrong Maven root inventory")
            logs = record.get("logs", [])
            require(len(logs) == 2, "Missing install or SBOM build log")
            for log in logs:
                require(log.get("exitCode") == 0, "Maven evidence build did not exit successfully")
                text = checked_file(root, log["path"], log["sha256"]).read_text(encoding="utf-8")
                require("BUILD SUCCESS" in text and not EXTRACTION_ERROR.search(text),
                        f"Maven dependency extraction was incomplete: {log['path']}")
            file = checked_file(root, record["path"], record["sha256"])
            external = sbom_packages(file, record["modules"]) - local
            require(bool(external), f"SBOM contains no external dependencies: {name}")
            inventory[file.name] = external
            summary[side][name] = {"modules": len(record["modules"]), "externalPackages": len(external)}
            if name == "main":
                require(ETCD_TRANSITIVES.issubset({package for package, _ in external}),
                        "Etcd transitive dependency coverage is incomplete")
                summary[side][name]["etcdTransitives"] = {
                    package: sorted(version for item, version in external if item == package)
                    for package in sorted(ETCD_TRANSITIVES)}
        if require_scans:
            validate_scan(root, side, records, inventory, local)
    return summary


def validate_scan(root: Path, side: str, records: list[dict],
                  inventory: dict, local: set[tuple[str, str]]) -> None:
    status = read_json(root / f"{side}-scan.json")
    require(status.get("version") == OSV_VERSION and status.get("binarySha256") == OSV_SHA256,
            "OSV scanner version or binary hash is not pinned")
    require(status.get("allPackages") is True, "OSV all-packages evidence is required")
    require(status.get("exitCode") in (0, 1), f"OSV extraction/API exit code: {status.get('exitCode')}")
    require(status.get("inputs") == [record["path"] for record in records], "OSV scan inputs differ from the SBOMs")
    log = checked_file(root, status["log"], status["logSha256"]).read_text(encoding="utf-8")
    require(not EXTRACTION_ERROR.search(log), "OSV dependency extraction failed")
    require(status.get("result") == SIDES[side], "OSV result path differs from the reviewed side")
    results = read_json(checked_file(root, status["result"], status["resultSha256"]))
    actual: dict[str, set[tuple[str, str]]] = {}
    for source in results.get("results", []):
        name = Path(source.get("source", {}).get("path", "")).name
        require(name in inventory and name not in actual, "OSV source coverage has unexpected or duplicate inputs")
        packages = set()
        for entry in source.get("packages", []):
            package = entry.get("package", {})
            require(package.get("ecosystem") == "Maven" and concrete(package.get("version")),
                    "OSV package coverage has an invalid Maven identity")
            packages.add((package.get("name"), package["version"]))
        actual[name] = packages - local
    require(set(actual) == set(inventory), "OSV SBOM source coverage is incomplete")
    for name, packages in inventory.items():
        missing, unexpected = packages - actual[name], actual[name] - packages
        require(not missing and not unexpected,
                f"OSV package coverage mismatch for {name}: missing={sorted(missing)}, unexpected={sorted(unexpected)}")


def prepare_reporter_inputs(root: Path, base_sha: str, head_sha: str, merge_sha: str) -> dict:
    """Normalize only validated source paths; retain the scanner's raw evidence."""
    summary = validate_evidence(root, base_sha, head_sha, merge_sha)
    ledger = {"schemaVersion": 1, "transformation": "source.path only", "files": []}
    for raw_name in SIDES.values():
        raw = root / raw_name
        results = read_json(raw)
        paths = {}
        for source in results["results"]:
            original = source["source"]["path"]
            logical = f"dependency-sbom/{Path(original).name}"
            source["source"]["path"] = logical
            paths[original] = logical
        normalized = root / raw_name.replace("-results.json", "-reporter.json")
        write_json(normalized, results)
        ledger["files"].append({"raw": raw.name, "rawSha256": sha256(raw),
                                 "reporter": normalized.name, "reporterSha256": sha256(normalized),
                                 "sourcePaths": paths})
    write_json(root / "reporter-normalization.json", ledger)
    return summary


def git(repository: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", "-C", str(repository), *arguments], text=True, timeout=120).strip()


def maven_inventory(repository: Path) -> dict[str, list[dict]]:
    """Read tracked reactor membership; Maven itself resolves all dependencies."""
    repository = repository.resolve()
    cache = {}

    def project(path: Path) -> tuple[dict, dict, ET.Element]:
        path = path.resolve()
        require(path.is_relative_to(repository), "Maven module or parent escapes the checkout")
        if path in cache:
            return cache[path]
        xml = ET.parse(path).getroot()

        def text(name: str) -> str:
            return (xml.findtext(name, default="", namespaces=MAVEN_NAMESPACE) or "").strip()

        properties = {}
        parent = {}
        if xml.find("m:parent", MAVEN_NAMESPACE) is not None:
            relative = text("m:parent/m:relativePath") or "../pom.xml"
            parent_file = path.parent / relative
            if parent_file.is_file():
                parent, inherited, _ = project(parent_file)
                properties.update(inherited)
        props = xml.find("m:properties", MAVEN_NAMESPACE)
        if props is not None:
            properties.update({item.tag.split("}")[-1]: (item.text or "").strip() for item in props})

        def expand(value: str) -> str:
            for _ in range(30):
                updated = re.sub(r"\$\{([^}]+)}", lambda match: properties.get(match.group(1), match.group(0)), value)
                if updated == value:
                    break
                value = updated
            require(concrete(value), f"Unresolved source POM coordinate in {path}: {value}")
            return value

        group = expand(text("m:groupId") or text("m:parent/m:groupId"))
        artifact = expand(text("m:artifactId"))
        version = expand(text("m:version") or text("m:parent/m:version"))
        properties.update({"project.groupId": group, "project.artifactId": artifact,
                           "project.version": version, "pom.version": version})
        identity = {"pom": path.relative_to(repository).as_posix(), "name": f"{group}:{artifact}",
                    "version": version, "packaging": text("m:packaging") or "jar"}
        cache[path] = identity, properties, xml
        return cache[path]

    inventory = {}
    visited = set()

    def walk(path: Path, modules: list[dict]) -> None:
        path = path.resolve()
        require(path not in visited, "Maven reactor lists a module more than once")
        visited.add(path)
        identity, _, xml = project(path)
        modules.append(identity)
        for module in xml.findall("m:modules/m:module", MAVEN_NAMESPACE):
            child = path.parent / (module.text or "").strip()
            walk(child if child.name == "pom.xml" else child / "pom.xml", modules)

    for name, pom in ROOT_POMS.items():
        modules = []
        walk(repository / pom, modules)
        inventory[name] = modules
    tracked = {name for name in git(repository, "ls-tree", "-r", "--name-only", "HEAD").splitlines()
               if name == "pom.xml" or name.endswith("/pom.xml")}
    covered = {module["pom"] for modules in inventory.values() for module in modules}
    require(covered == tracked, f"Source POM coverage differs from the three roots: missing={sorted(tracked - covered)}")
    return inventory


def run_logged(command: list[str], cwd: Path, log: Path, *,
               idle_timeout: float = 600, total_timeout: float = 1800,
               terminate_grace: float = 10) -> int:
    """Keep output visible; stop only this process group after ten idle minutes."""
    print(f"Running: {' '.join(command)}", flush=True)
    process = subprocess.Popen(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True)
    last_output = time.monotonic()
    started = last_output
    expired = False
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        with log.open("wb") as stream:
            while selector.get_map() or process.poll() is None:
                now = time.monotonic()
                remaining = min(idle_timeout - (now - last_output), total_timeout - (now - started))
                if remaining <= 0:
                    stream.write(b"\nERROR: dependency evidence command exceeded its progress budget\n")
                    expired = True
                    break
                if not selector.get_map():
                    try:
                        process.wait(timeout=min(remaining, 0.1))
                    except subprocess.TimeoutExpired:
                        pass
                    continue
                events = selector.select(timeout=min(30, remaining))
                for key, _ in events:
                    block = os.read(key.fileobj.fileno(), 65536)
                    if not block:
                        selector.unregister(key.fileobj)
                        continue
                    stream.write(block)
                    stream.flush()
                    sys.stdout.buffer.write(block)
                    sys.stdout.buffer.flush()
                    last_output = time.monotonic()
    finally:
        # A reaped parent can leave descendants holding the pipe. Always address
        # the group we created, including exceptions while opening/writing logs.
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=terminate_grace)
        except subprocess.TimeoutExpired:
            pass
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        selector.close()
        process.stdout.close()
        process.wait()
    code = process.wait()
    return 124 if expired else code


def build(args: argparse.Namespace) -> None:
    repository = args.repository.resolve()
    expected = [args.base_sha, args.head_sha, args.merge_sha]
    for sha in expected:
        require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "A full source SHA is required")
        require(git(repository, "rev-parse", f"{sha}^{{commit}}") == sha, "Source SHA is unavailable")
    parents = git(repository, "rev-list", "--parents", "-n", "1", args.merge_sha).split()[1:]
    require(parents == expected[:2], "Proposed merge parents must be the exact base and PR Head")
    evidence = args.evidence_dir.resolve()
    work = args.work_dir.resolve()
    require(not work.exists() and not evidence.exists(), "Refusing to reuse dependency evidence or Maven caches")
    work.mkdir(parents=True)
    evidence.mkdir(parents=True)
    manifest = {"schemaVersion": 1, "status": "building", "baseSha": args.base_sha,
                "headSha": args.head_sha, "mergeSha": args.merge_sha, "mergeParents": parents,
                "cycloneDxVersion": CYCLONEDX_VERSION, "sides": {}}
    write_json(evidence / "manifest.json", manifest)
    for side, sha in (("baseline", args.base_sha), ("candidate", args.merge_sha)):
        source = work / side / "source"
        cache = work / side / "m2"
        source.parent.mkdir()
        cache.mkdir()
        destination = evidence / side
        destination.mkdir()
        git(repository, "worktree", "add", "--detach", str(source), sha)
        try:
            inventory = maven_inventory(source)
            data = {"sourceSha": git(source, "rev-parse", "HEAD"), "worktree": str(source),
                    "mavenRepository": str(cache), "trackedPoms": sorted(
                        module["pom"] for modules in inventory.values() for module in modules), "sboms": []}
            manifest["sides"][side] = data
            for name, pom in ROOT_POMS.items():
                record = {"root": name, "path": f"{side}/{name}.cdx.json",
                          "modules": inventory[name], "logs": []}
                data["sboms"].append(record)
                common = [args.maven, "-B", "-ntp", f"-Dmaven.repo.local={cache}", "-f", pom]
                commands = {
                    "install": [*common, "-DskipTests", "clean", "install"],
                    "sbom": [*common, "-DoutputFormat=json", "-DoutputName=bom", "-DschemaVersion=1.6",
                             "-DincludeTestScope=true", "-DincludeCompileScope=true", "-DincludeRuntimeScope=true",
                             "-DincludeProvidedScope=true", "-DincludeSystemScope=true", "-DoutputReactorProjects=false",
                             "-DexcludeTestProject=false", "-Dcyclonedx.skipAttach=true", "-Dcyclonedx.skipNotDeployed=false",
                             f"org.cyclonedx:cyclonedx-maven-plugin:{CYCLONEDX_VERSION}:makeAggregateBom"],
                }
                for operation, command in commands.items():
                    log = destination / f"{name}-{operation}.log"
                    code = run_logged(command, source, log)
                    record["logs"].append({"path": log.relative_to(evidence).as_posix(),
                                           "sha256": sha256(log), "exitCode": code})
                    write_json(evidence / "manifest.json", manifest)
                    require(code == 0, f"Maven {side}/{name}/{operation} failed with exit {code}")
                output = source / Path(pom).parent / "target/bom.json"
                require(output.is_file(), f"Maven did not generate the {name} SBOM")
                copy = evidence / record["path"]
                shutil.copyfile(output, copy)
                record["sha256"] = sha256(copy)
                sbom_packages(copy, inventory[name])
                write_json(evidence / "manifest.json", manifest)
            require(git(source, "rev-parse", "HEAD") == sha, "Source SHA changed during the build")
        finally:
            git(repository, "worktree", "remove", "--force", str(source))
    manifest["status"] = "complete"
    write_json(evidence / "manifest.json", manifest)
    summary = validate_evidence(evidence, *expected, require_scans=False)
    write_json(evidence / "build-coverage.json", summary)


def scan(args: argparse.Namespace) -> None:
    evidence = args.evidence_dir.resolve()
    manifest = read_json(evidence / "manifest.json")
    validate_evidence(evidence, manifest["baseSha"], manifest["headSha"], manifest["mergeSha"], require_scans=False)
    scanner = args.scanner.resolve()
    require(scanner.is_file() and sha256(scanner) == OSV_SHA256, "OSV scanner binary hash mismatch")
    records = manifest["sides"][args.side]["sboms"]
    result = evidence / SIDES[args.side]
    log = evidence / f"{args.side}-scan.log"
    require(not result.exists() and not log.exists(), "Refusing to reuse an OSV scan result")
    inputs = [record["path"] for record in records]
    command = [str(scanner), "scan", "source", "--all-packages", "--format=json", f"--output={result}",
               *[f"--lockfile={evidence / relative}" for relative in inputs]]
    code = run_logged(command, evidence, log)
    status = {"version": OSV_VERSION, "binarySha256": sha256(scanner), "allPackages": True,
              "exitCode": code, "inputs": inputs, "result": result.name,
              "resultSha256": sha256(result) if result.is_file() else None,
              "log": log.name, "logSha256": sha256(log)}
    write_json(evidence / f"{args.side}-scan.json", status)
    require(code in (0, 1), f"OSV extraction/API failed with exit code {code}")
    require(result.is_file(), "OSV did not produce a package inventory")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    builder = commands.add_parser("build")
    builder.add_argument("--repository", type=Path, default=Path.cwd())
    builder.add_argument("--work-dir", type=Path, required=True)
    builder.add_argument("--maven", default="mvn")
    scanner = commands.add_parser("scan")
    scanner.add_argument("--scanner", type=Path, required=True)
    scanner.add_argument("--side", choices=SIDES, required=True)
    validator = commands.add_parser("validate")
    for command in (builder, validator):
        for name in ("base", "head", "merge"):
            command.add_argument(f"--{name}-sha", required=True)
    for command in (builder, scanner, validator):
        command.add_argument("--evidence-dir", type=Path, default=Path("dependency-evidence"))
    args = parser.parse_args()
    try:
        if args.command == "build":
            build(args)
        elif args.command == "scan":
            scan(args)
        else:
            summary = prepare_reporter_inputs(args.evidence_dir, args.base_sha, args.head_sha, args.merge_sha)
            write_json(args.evidence_dir / "verified-coverage.json", summary)
            print(json.dumps(summary, indent=2, sort_keys=True))
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"Dependency evidence rejected: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
