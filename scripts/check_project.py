#!/usr/bin/env python3
"""Repository quality gate for OTRYX migration and historical Peach 1.0.x."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SECTION = re.compile(r"<!--\s*doc-section:([a-zA-Z0-9_.-]+)\s*-->")
RELEASE_STATUS = re.compile(
    r"<!--\s*release-status:([a-zA-Z0-9_.-]+)=([a-zA-Z0-9_.-]+)\s*-->"
)
MARKDOWN_LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
CHINESE = re.compile(r"[\u4e00-\u9fff]")
LOGGER_WITH_CHINESE = re.compile(
    r"LOGGER\.(?:trace|debug|info|warn|error)\([^\n]*[\u4e00-\u9fff]"
)

EXPECTED_MODULES = (
    "otryx-core",
    "otryx-codegen",
    "otryx-registry",
    "otryx-serialization",
    "otryx-transport",
    "otryx-proxy",
    "otryx-observability",
    "otryx-spring-boot",
    "otryx-quickstart",
    "otryx-benchmarks",
)

FORBIDDEN_CORE_IMPORTS = (
    "io.vertx.",
    "io.etcd.",
    "com.alibaba.nacos.",
    "net.sf.cglib.",
    "org.apache.fory.",
    "org.springframework.",
    "io.micrometer.",
    "io.opentelemetry.",
    "jdk.jfr.",
)

REQUIRED_FILES = (
    "README.md",
    "README.en-US.md",
    "LICENSE",
    "CONTRIBUTING.md",
    "CODE_OF_CONDUCT.md",
    "SECURITY.md",
    "CHANGELOG.md",
    "ROADMAP.md",
    "docs/design/requirements-blueprint.md",
    "docs/design/technical-solution.md",
    "docs/features.md",
    "docs/design/detailed-design.md",
    "docs/design/project-structure.md",
    "docs/index.md",
    "docs/user-guide.md",
    "docs/operations.md",
    "docs/getting-started.md",
    "docs/architecture.md",
    "docs/protocol.md",
    "docs/wire-compatibility.md",
    "docs/reference/starter.md",
    "docs/spi.md",
    "docs/reference/registry-nacos.md",
    "docs/security.md",
    "docs/observability.md",
    "docs/reference/production-observability.md",
    "docs/configuration.md",
    "docs/performance.md",
    "docs/reference/performance-evidence.md",
    "docs/reference/capacity-planning.md",
    "docs/upgrade-rollback.md",
    "docs/archive/releases/release-policy.md",
    "docs/archive/releases/release-readiness.md",
    "docs/reference/maven.md",
    "docs/engineering/development.md",
    "docs/faq.md",
    "docs/archive/releases/release-notes-1.0.0-RC1.md",
    "docs/archive/releases/release-notes-1.0.0.md",
    "docs/release-status.properties",
    "docs/publication-readiness.md",
    "docs/brand-guidelines.md",
    "docs/migration.md",
    "docs/images/brand/otryx-banner.svg",
    "docs/images/mascot/otti-main.svg",
    "docs/images/architecture/system-overview.svg",
    "docs/images/flows/rpc-lifecycle.svg",
    ".github/workflows/ci.yml",
    ".github/workflows/release-readiness.yml",
    ".github/workflows/release.yml",
    ".github/workflows/rolling-compatibility.yml",
    ".github/workflows/etcd-chaos.yml",
    ".github/workflows/nacos-chaos.yml",
    "scripts/check_central_publication.py",
)

DEPRECATED_DOCS = (
    "docs/high-performance-kernel-v2-plan.md",
    "docs/high-performance-kernel-v2b.md",
    "docs/implementation-plan.md",
    "docs/performance-evidence-v2d2.md",
    "docs/performance-kernel-v2d.md",
    "docs/production-kernel-v2b1-phase2.md",
    "docs/production-kernel-v2b1.md",
    "docs/production-kernel-v2b2.md",
    "docs/production-kernel-v2c2.md",
    "docs/production-kernel-v2c3-plan.md",
    "docs/production-roadmap.md",
    "docs/readiness.md",
    "docs/v2d2-e1-controlled-evidence.md",
    "docs/version-roadmap-to-ga.md",
)


def fail(message: str) -> None:
    print(f"ERROR: {message}")
    raise SystemExit(1)


def properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"Invalid properties line: {raw}")
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def check_required_files() -> None:
    missing = [path for path in REQUIRED_FILES if not (ROOT / path).is_file()]
    if missing:
        fail("Missing required project files: " + ", ".join(missing))
    stale = [path for path in DEPRECATED_DOCS if (ROOT / path).exists()]
    if stale:
        fail("Deprecated stage documents must be removed: " + ", ".join(stale))


def check_release_status() -> None:
    status = properties(ROOT / "docs" / "release-status.properties")
    version = status.get("version", "")
    is_development = version == "1.0.0-SNAPSHOT"
    if not is_development and not re.fullmatch(r"1\.0\.\d+", version):
        fail(f"Unsupported source release channel/version: {version!r}")

    expected = {
        "project": "development" if is_development else "ga",
        "release_candidate": "1.0.0-RC1",
        "wire": "v1",
        "java": "21",
    }
    for key, value in expected.items():
        if status.get(key) != value:
            fail(f"Unexpected release status: {key}={status.get(key)!r}, expected {value!r}")

    if not is_development:
        notes = ROOT / "docs/archive/releases" / f"release-notes-{version}.md"
        if not notes.is_file():
            fail("Missing release notes for current source version: "
                 + str(notes.relative_to(ROOT)))

    pom = ET.parse(ROOT / "pom.xml").getroot()
    revision = pom.findtext("m:properties/m:revision", namespaces=NS)
    if revision != version:
        fail(f"Maven revision {revision!r} does not match "
             f"release status version {version!r}")

    for relative in ("README.md", "README.en-US.md"):
        text = (ROOT / relative).read_text(encoding="utf-8")
        markers = dict(RELEASE_STATUS.findall(text))
        for key in ("project", "version", "wire"):
            if markers.get(key) != status[key]:
                fail(f"Release status drift in {relative}: "
                     f"{key}={markers.get(key)!r}, expected {status[key]!r}")


def check_repository_identity() -> None:
    from check_repository_identity import validate

    problems = validate(ROOT)
    if problems:
        fail("Repository identity preflight failed: " + "; ".join(problems))


def check_readme_parity() -> None:
    zh = (ROOT / "README.md").read_text(encoding="utf-8")
    en = (ROOT / "README.en-US.md").read_text(encoding="utf-8")
    zh_sections = SECTION.findall(zh)
    en_sections = SECTION.findall(en)
    if not zh_sections or zh_sections != en_sections:
        fail(f"README section markers differ: ZH={zh_sections}, EN={en_sections}")


def check_public_docs_are_ga_clean() -> None:
    forbidden = (
        "0.1.0-SNAPSHOT",
        "capability-status:v2-",
        "project=preview",
        "Production Roadmap / Capability Matrix",
    )
    paths = [ROOT / "README.md", ROOT / "README.en-US.md"]
    paths.extend(ROOT.glob("docs/*.md"))
    for path in paths:
        text = path.read_text(encoding="utf-8")
        for token in forbidden:
            if token in text:
                fail(f"Stale pre-1.0 wording in {path.relative_to(ROOT)}: {token}")


def check_current_dependency_coordinates() -> None:
    """Reject historical Maven dependency snippets in current OTRYX docs."""
    for directory in (ROOT / "docs", ROOT / "docs/design", ROOT / "docs/reference"):
        for path in directory.glob("*.md"):
            if "<version>1.0.1</version>" in path.read_text(encoding="utf-8"):
                fail(f"Legacy 1.0.1 dependency in current documentation: {path.relative_to(ROOT)}")


def check_current_documentation_version() -> None:
    """Enforce active OTRYX docs against the source release-status version."""
    version = properties(ROOT / "docs" / "release-status.properties")["version"]
    stale_versions = ("2.0.0-SNAPSHOT", "OTRYX RPC 2.0", "OTRYX 2.0")
    current_files = [
        ROOT / "ROADMAP.md",
        ROOT / "CHANGELOG.md",
        *(ROOT / "docs").glob("*.md"),
        *(ROOT / "docs" / "design").glob("*.md"),
        *(ROOT / "docs" / "reference").glob("*.md"),
    ]
    for path in current_files:
        data = path.read_text(encoding="utf-8")
        for stale in stale_versions:
            if stale in data:
                fail(f"Stale OTRYX version in {path.relative_to(ROOT)}: {stale}")
    active_coordinates = (
        ROOT / "docs" / "getting-started.md",
        ROOT / "docs" / "user-guide.md",
        ROOT / "docs" / "reference" / "starter.md",
    )
    for path in active_coordinates:
        data = path.read_text(encoding="utf-8")
        if version not in data:
            fail(f"Missing source version {version} in {path.relative_to(ROOT)}")


def check_chinese_first_docs() -> None:
    for path in [ROOT / "CONTRIBUTING.md", *(ROOT / "docs").glob("*.md")]:
        if not CHINESE.search(path.read_text(encoding="utf-8")):
            fail(f"Chinese-first documentation expected: {path.relative_to(ROOT)}")


def check_markdown_links() -> None:
    for path in ROOT.rglob("*.md"):
        if "target" in path.parts:
            continue
        text = path.read_text(encoding="utf-8")
        for raw in MARKDOWN_LINK.findall(text):
            target = raw.split("#", 1)[0].strip()
            if not target or target.startswith(("http://", "https://", "mailto:")):
                continue
            if not (path.parent / target).resolve().exists():
                fail(f"Broken local link in {path.relative_to(ROOT)}: {raw}")


def check_retired_paths() -> None:
    """Reject removed root directories and stale active-documentation references."""
    for retired in ("tools", "config"):
        if (ROOT / retired).exists():
            fail(f"Retired repository directory still exists: {retired}")

    obsolete = (
        "tools" + "/rpc-comparison",
        "config" + "/agent-mcp.json",
        "config" + "/java-api-rules.json",
        "config" + "/otryx-checkstyle.xml",
        "docs" + "/requirements-blueprint.md",
        "docs" + "/technical-solution.md",
        "docs" + "/detailed-design.md",
        "docs" + "/project-structure.md",
        "docs" + "/production-configuration.md",
        "docs" + "/migration-to-otryx.md",
        "docs" + "/maven.md",
        "docs" + "/starter.md",
        "docs" + "/release-notes-",
        "docs" + "/release-policy.md",
        "docs" + "/release-readiness.md",
    )
    extensions = {".md", ".py", ".sh", ".xml", ".yml", ".yaml", ".toml", ".json"}
    for path in ROOT.rglob("*"):
        if (not path.is_file() or path.suffix not in extensions
                or path == Path(__file__).resolve()
                or any(part in {"target", ".git", "node_modules", "__pycache__"}
                       for part in path.parts)):
            continue
        content = path.read_text(encoding="utf-8", errors="replace")
        for removed in obsolete:
            for match in re.finditer(re.escape(removed), content):
                # Permit newly relocated paths such as .agents/config/...
                if match.start() > 0 and (content[match.start() - 1].isalnum()
                                           or content[match.start() - 1] in "/._-"):
                    continue
                fail(f"Stale reference to retired path in {path.relative_to(ROOT)}: {removed}")


def check_maven_reactor() -> None:
    root = ET.parse(ROOT / "pom.xml").getroot()
    modules = tuple(node.text for node in root.findall("m:modules/m:module", NS))
    if modules != EXPECTED_MODULES:
        fail(f"Unexpected Maven modules: {modules}")
    for module in modules:
        if not (ROOT / module / "pom.xml").is_file():
            fail(f"Missing module pom.xml: {module}")


def check_core_boundaries() -> None:
    for path in (ROOT / "otryx-core" / "src/main/java").rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        for prefix in FORBIDDEN_CORE_IMPORTS:
            if f"import {prefix}" in text:
                fail(f"Forbidden infrastructure import in {path.relative_to(ROOT)}: {prefix}")


def check_java_hygiene() -> None:
    for path in ROOT.rglob("*.java"):
        if path.is_relative_to(ROOT / "benchmarks" / "rpc-comparison"):
            continue
        if any(part in {"target", ".git"} for part in path.parts):
            continue
        text = path.read_text(encoding="utf-8")
        if "System.out" in text or "System.err" in text:
            fail(f"System output is not allowed: {path.relative_to(ROOT)}")
        if LOGGER_WITH_CHINESE.search(text):
            fail(f"Runtime log message must be English: {path.relative_to(ROOT)}")
        for number, line in enumerate(text.splitlines(), 1):
            if line.rstrip() != line:
                fail(f"Trailing whitespace: {path.relative_to(ROOT)}:{number}")
            if "\t" in line:
                fail(f"Tab indentation: {path.relative_to(ROOT)}:{number}")
            if len(line) > 120:
                fail(f"Java line exceeds 120 characters: {path.relative_to(ROOT)}:{number}")
            stripped = line.strip()
            if stripped.startswith("import ") and stripped.endswith(".*;"):
                fail(f"Wildcard import is not allowed: {path.relative_to(ROOT)}:{number}")


def main() -> int:
    check_required_files()
    check_release_status()
    check_repository_identity()
    check_readme_parity()
    check_public_docs_are_ga_clean()
    check_current_dependency_coordinates()
    check_current_documentation_version()
    check_chinese_first_docs()
    check_markdown_links()
    check_retired_paths()
    check_maven_reactor()
    check_core_boundaries()
    check_java_hygiene()
    print("OTRYX RPC repository checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
