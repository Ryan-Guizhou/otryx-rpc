#!/usr/bin/env python3
"""Enforce current OTRYX coordinates and the application-only registry contract.

The lexical cleanup gate covers production, tests, descriptors, configuration,
benchmarks and current documentation. Archived release notes and engineering
history are excluded only from documentation prose; runnable source is never
excluded by directory name. It does not replace behavioral registry contracts.
"""
from __future__ import annotations

import sys
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
REMOVED_TYPES = (
    "ServiceRegistrar", "ServiceDiscovery", "RegistryFactory", "RegistrySnapshot", "RegistryListener",
    "RpcCompatibilityMetadata", "MemoryRegistry", "MemoryRegistryFactory", "NacosRegistry",
    "NacosRegistryFactory", "NacosRegistrySubscription", "EtcdRegistry", "EtcdRegistryFactory",
    "HttpRegistry", "HttpRegistryBackend", "HttpRegistryIdentity", "ConsulRegistry", "ConsulRegistryFactory",
    "ConsulRegistryBackend", "EurekaRegistry", "EurekaRegistryFactory", "EurekaRegistryBackend",
    "NacosInstanceMapper", "NacosReservedMetadata", "NacosServiceNames", "RegistryContractTestKit",
)
REMOVED_SYMBOLS = re.compile(r"\b(?:" + "|".join(REMOVED_TYPES) + r")\b")
REMOVED_WIRING = re.compile(r"\.\s*(?:serviceDiscovery|serviceRegistrar)\s*\(")
REMOVED_REGISTRY = re.compile(r"com\.peachsoft\.otryx\.registry\.Registry\b")
REGISTRY_PACKAGE = re.compile(r"\bpackage\s+com\.peachsoft\.otryx\.registry\s*;")
REGISTRY_DECLARATION = re.compile(r"\b(?:interface|class)\s+Registry\b")
REMOVED_KEYS = re.compile(r"peach\.rpc\.(?:protocol\.version|schema\.(?:version|fingerprint))")
REMOVED_PATHS = re.compile(r"""["'\x60]\/otryx\/["'\x60]|\/otryx\/ns\/""")
REMOVED_MODES = re.compile(r"registry[-.]mode\s*[:=]\s*(?:interface|legacy)|registry-migration")
SKIPPED_PARTS = {".git", "target", "node_modules", "__pycache__"}
SOURCE_SUFFIXES = {".java", ".xml", ".properties", ".imports", ".yml", ".yaml", ".json",
                   ".Processor", ".sh", ".svg"}
# These exact literals assert that removed classes/descriptors cannot be loaded.
# Only the named negative contracts may mention them; imports, declarations,
# calls and other strings in those same files remain subject to the full gate.
ABSENCE_CONTRACT_LITERALS = {
    "otryx-core/src/test/java/com/peachsoft/otryx/core/ApplicationOnlyRuntimeContractTest.java": {
        '"ServiceRegistrar"', '"ServiceDiscovery"', '"RegistryFactory"', '"RegistryListener"',
        '"RegistrySnapshot"', '"META-INF/services/com.peachsoft.otryx.registry.RegistryFactory"',
    },
    "otryx-spring-boot/otryx-spring-boot-autoconfigure/src/test/java/com/peachsoft/otryx/"
    "spring/autoconfigure/OtryxRpcApplicationConfigurationTest.java": {
        '"com.peachsoft.otryx.registry.Registry"',
    },
}
JAVA_STRING = re.compile(r'"(?:\\.|[^"\\])*"')


def lexical_input(relative: str, text: str) -> str:
    """Mask only approved absence assertions while retaining exact line numbers."""
    allowed = ABSENCE_CONTRACT_LITERALS.get(relative, set())
    if not allowed:
        return text
    return JAVA_STRING.sub(lambda match: " " * len(match.group(0))
                           if match.group(0) in allowed else match.group(0), text)


def cleanup_violations(root: Path) -> list[str]:
    """Report residual legacy paths without banning ServiceKey or the local LB DTO."""
    findings: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or any(part in SKIPPED_PARTS for part in path.relative_to(root).parts):
            continue
        relative = path.relative_to(root).as_posix()
        current_doc = path.suffix == ".md" and (relative in {"README.md", "README.en-US.md"}
                or relative.startswith("otryx-")
                or (relative.startswith("docs/") and not relative.startswith(("docs/archive/", "docs/engineering/"))))
        descriptor = "/META-INF/" in relative
        if path.suffix not in SOURCE_SUFFIXES and not current_doc and not descriptor:
            continue
        text = lexical_input(relative, path.read_text(encoding="utf-8"))
        for label, pattern in (("removed type", REMOVED_SYMBOLS), ("removed builder wiring", REMOVED_WIRING),
                               ("removed registry SPI", REMOVED_REGISTRY), ("removed metadata key", REMOVED_KEYS),
                               ("removed registry path", REMOVED_PATHS), ("removed mode", REMOVED_MODES)):
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append(f"{relative}:{line}: {label}: {match.group(0)}")
        if path.suffix == ".java" and REGISTRY_PACKAGE.search(text):
            for match in REGISTRY_DECLARATION.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append(f"{relative}:{line}: removed registry SPI: {match.group(0)}")
        if (path.stem in REMOVED_TYPES or path.name == "com.peachsoft.otryx.registry.RegistryFactory"
                or (path.name == "Registry.java" and "/registry/" in relative)):
            findings.append(f"{relative}: removed registry file or descriptor")
    return findings


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def main() -> int:
    pom = ET.parse(ROOT / "pom.xml").getroot()
    require(pom.findtext("m:groupId", namespaces=NS) == "com.peachsoft.otryx",
            "Incorrect OTRYX groupId")
    require(pom.findtext("m:artifactId", namespaces=NS) == "otryx-rpc",
            "Incorrect OTRYX parent artifactId")
    require(pom.findtext("m:properties/m:revision", namespaces=NS) == "1.0.0-SNAPSHOT",
            "Incorrect development version")
    status = (ROOT / "docs/release-status.properties").read_text(encoding="utf-8")
    require("project=development" in status and "version=1.0.0-SNAPSHOT" in status,
            "Development source status must explicitly be migration")
    require(not list(ROOT.glob("peach-rpc-*")), "Legacy module directories remain")

    for module in ROOT.glob("otryx-*"):
        for path in module.rglob("*"):
            if not path.is_file() or "target" in path.parts:
                continue
            if "/src/" not in ("/" + path.as_posix().split(module.name, 1)[-1]):
                continue
            if path.suffix not in (".java", ".xml", ".properties", ".imports", ".yml", ".yaml", ".json", ".Processor"):
                continue
            value = path.read_text(encoding="utf-8")
            require("io.peach.rpc" not in value,
                    "Legacy Java namespace remains in " + str(path.relative_to(ROOT)))
            if path.suffix == ".java":
                require("/io/peach/rpc/" not in path.as_posix(),
                        "Legacy Java source path remains: " + str(path.relative_to(ROOT)))

    findings = cleanup_violations(ROOT)
    require(not findings, "Application registry cleanup failed:\n" + "\n".join(findings))
    props = (ROOT / "otryx-spring-boot/otryx-spring-boot-autoconfigure/src/main/java/com/peachsoft/otryx/"
             "spring/autoconfigure/OtryxRpcProperties.java").read_text(encoding="utf-8")
    require('@ConfigurationProperties(prefix = "otryx.rpc")' in props,
            "Spring Boot config prefix did not migrate")
    processor = (ROOT / "otryx-codegen/src/main/java/com/peachsoft/otryx/codegen/"
                 "OtryxRpcContractProcessor.java").read_text(encoding="utf-8")
    require('@SupportedAnnotationTypes("com.peachsoft.otryx.api.OtryxRpcContract")' in processor,
            "Processor must discover new contract annotation")

    for relative in ("docs/images/brand/otryx-banner.svg",
                     "docs/images/mascot/otti-main.svg",
                     "docs/images/architecture/system-overview.svg",
                     "docs/images/architecture/control-data-plane.svg",
                     "docs/images/flows/rpc-lifecycle.svg"):
        img = ROOT / relative
        require(img.is_file(), "Missing image: " + relative)
        require(ET.parse(img).getroot().tag.endswith("svg"),
                "Invalid SVG image: " + relative)

    for relative in ("docs/brand-guidelines.md", "docs/migration.md"):
        require((ROOT / relative).is_file(), "Missing migration doc: " + relative)
    require((ROOT / "docs/archive/releases/release-notes-1.0.1.md").is_file(),
            "Historical release notes must survive renaming")
    print("OTRYX application-only migration readiness passed")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as exc:
        print("ERROR:", exc)
        sys.exit(1)
