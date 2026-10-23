#!/usr/bin/env python3
"""Regression gate: one modular Starter and complete runnable Quickstart."""

from __future__ import annotations

import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
QUICKSTART = ROOT / "otryx-quickstart"
BOOT = ROOT / "otryx-spring-boot"
MODULES = (
    "otryx-quickstart-api",
    "otryx-quickstart-provider",
    "otryx-quickstart-consumer",
    "otryx-quickstart-memory",
)


def pom(relative: str) -> ET.Element:
    """Return parsed Maven POM; fail on missing source or invalid XML."""
    return ET.parse(ROOT / relative).getroot()


def dependencies(root: ET.Element) -> set[str]:
    """Return production dependencies without mistaking tests or dependency management."""
    return {
        dep.findtext("m:artifactId", namespaces=NS)
        for dep in root.findall("m:dependencies/m:dependency", NS)
        if dep.findtext("m:scope", default="compile", namespaces=NS).strip() != "test"
    }


def modules(root: ET.Element) -> set[str]:
    """Return Maven aggregator module entries."""
    return {
        mod.text.strip()
        for mod in root.findall("m:modules/m:module", NS)
        if mod.text
    }


def test_memory_runtime_dependency_scope_guard() -> None:
    """Test libraries must be excluded without allowing an extra runtime SDK."""
    required = {"otryx-quickstart-api", "otryx-spring-boot-starter"}

    def fixture(scope: str | None) -> ET.Element:
        scope_xml = "" if scope is None else f"<scope>{scope}</scope>"
        return ET.fromstring(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>'
            '<dependency><artifactId>otryx-quickstart-api</artifactId></dependency>'
            '<dependency><artifactId>otryx-spring-boot-starter</artifactId></dependency>'
            f'<dependency><artifactId>extra-sdk</artifactId>{scope_xml}</dependency>'
            '</dependencies></project>'
        )

    assert dependencies(fixture("test")) == required
    for scope in (None, "compile", "runtime", "provided", "system", "unknown", ""):
        assert dependencies(fixture(scope)) != required, f"Extra {scope!r} dependency escaped the Memory gate"


def test_single_starter() -> None:
    """Lite must be absent; optional SDKs must never enter the base Starter."""
    assert not (BOOT / "otryx-spring-boot-starter-lite").exists()
    assert modules(pom("otryx-spring-boot/pom.xml")) == {
        "otryx-spring-boot-autoconfigure", "otryx-spring-boot-starter"
    }
    assert dependencies(pom("otryx-spring-boot/otryx-spring-boot-starter/pom.xml")) == {
        "otryx-spring-boot-autoconfigure", "spring-boot-starter"
    }
    auto = dependencies(pom("otryx-spring-boot/otryx-spring-boot-autoconfigure/pom.xml"))
    assert {"otryx-core", "otryx-serialization-fory", "otryx-transport-vertx"} <= auto
    adapter_names = {
        "otryx-registry-nacos", "otryx-registry-etcd",
        "otryx-registry-consul", "otryx-registry-eureka",
        "otryx-proxy-cglib", "otryx-proxy-bytebuddy",
    }
    assert not adapter_names.intersection(
        dependencies(pom("otryx-spring-boot/otryx-spring-boot-starter/pom.xml"))
    )
    dependency_management = pom("pom.xml").find("m:dependencyManagement", NS)
    assert dependency_management is not None
    assert "otryx-spring-boot-starter-lite" not in ET.tostring(
        dependency_management, encoding="unicode"
    )


def test_quickstart_maven_modules() -> None:
    """The runnable modules must preserve API, Provider, Consumer and Memory paths."""
    assert not (ROOT / "otryx-examples").exists()
    assert "otryx-quickstart" in modules(pom("pom.xml"))
    assert "otryx-examples" not in modules(pom("pom.xml"))
    assert modules(pom("otryx-quickstart/pom.xml")) == set(MODULES)
    for artifact in MODULES:
        child = pom(f"otryx-quickstart/{artifact}/pom.xml")
        assert child.findtext("m:artifactId", namespaces=NS) == artifact
    for side in ("provider", "consumer"):
        deps = dependencies(pom(f"otryx-quickstart/otryx-quickstart-{side}/pom.xml"))
        assert {"otryx-quickstart-api", "otryx-spring-boot-starter",
                "otryx-registry-nacos", "otryx-registry-etcd", "otryx-registry-consul",
                "otryx-registry-eureka"} <= deps
    memory = dependencies(pom("otryx-quickstart/otryx-quickstart-memory/pom.xml"))
    assert memory == {"otryx-quickstart-api", "otryx-spring-boot-starter"}
    api_deps = dependencies(pom("otryx-quickstart/otryx-quickstart-api/pom.xml"))
    assert {"otryx-core", "otryx-codegen"} <= api_deps


def test_quickstart_actual_runtime() -> None:
    """No old package names or inert demo stubs may masquerade as Quickstart."""
    expected = {
        "otryx-quickstart/otryx-quickstart-memory/src/main/java/"
        "com/peachsoft/otryx/quickstart/memory/MemoryQuickstartApplication.java",
        "otryx-quickstart/otryx-quickstart-memory/src/main/java/"
        "com/peachsoft/otryx/quickstart/memory/MemoryGreetingServiceImpl.java",
        "otryx-quickstart/otryx-quickstart-memory/src/main/java/"
        "com/peachsoft/otryx/quickstart/memory/MemoryGreetingRunner.java",
    }
    assert all((ROOT / file).is_file() for file in expected)
    for src in QUICKSTART.rglob("*.java"):
        content = src.read_text(encoding="utf-8")
        assert "com.peachsoft.otryx.examples" not in content, src
        assert "otryx.rpc.example" not in content, src
        assert "package com.peachsoft.otryx.quickstart" in content, src
    provider = (ROOT / next(x for x in expected if x.endswith("MemoryGreetingServiceImpl.java"))
                ).read_text(encoding="utf-8")
    consumer = (ROOT / next(x for x in expected if x.endswith("MemoryGreetingRunner.java"))
                ).read_text(encoding="utf-8")
    assert "@OtryxRpcService" in provider and "implements GreetingService" in provider
    assert "@OtryxRpcReference" in consumer and ".hello(" in consumer
    assert "Quickstart memory RPC succeeded:" in consumer


def test_scripts_and_documentation() -> None:
    """Old module links are forbidden in active entrypoints."""
    scripts = (
        "run_quickstart_memory.sh", "run_quickstart_process_e2e.sh",
        "run_three_process_deadline_e2e.sh", "run_quickstart_revision_e2e.sh",
        "run_quickstart_mtls_e2e.sh", "run_quickstart_e2e.sh",
        "observe_quickstart_nacos.sh", "observe_quickstart_registry.sh", "quickstart_e2e_common.sh",
    )
    for script in scripts:
        path = ROOT / "scripts" / script
        assert path.is_file(), script
        subprocess.run(["bash", "-n", str(path)], check=True)
    assert not (ROOT / "scripts/run_example_process_e2e.sh").exists()
    current_docs = (
        "README.md", "README.en-US.md", "docs/getting-started.md",
        "docs/user-guide.md", "docs/reference/starter.md",
        "docs/reference/maven.md", "docs/design/project-structure.md",
        "otryx-quickstart/README.md", "otryx-quickstart/README.en-US.md",
    )
    for name in current_docs:
        doc = (ROOT / name).read_text(encoding="utf-8")
        for obsolete in (
            "otryx-examples", "otryx-example-", "otryx-spring-boot-starter-lite",
            "com.peachsoft.otryx.examples", "run_example_process_e2e.sh",
        ):
            assert obsolete not in doc, f"{name} contains obsolete {obsolete}"
    for readme in ("README.md", "README.en-US.md"):
        text = (QUICKSTART / readme).read_text(encoding="utf-8")
        for term in (
            "otryx-quickstart-memory", "otryx-quickstart-provider",
            "otryx-quickstart-consumer", "run_quickstart_memory.sh",
            "run_three_process_deadline_e2e.sh", "NETWORK_MTLS",
            "run_quickstart_revision_e2e.sh", "run_quickstart_mtls_e2e.sh",
            "run_quickstart_e2e.sh", "observe_quickstart_nacos.sh",
        ):
            assert term in text, (readme, term)


if __name__ == "__main__":
    test_memory_runtime_dependency_scope_guard()
    test_single_starter()
    test_quickstart_maven_modules()
    test_quickstart_actual_runtime()
    test_scripts_and_documentation()
    print("Single Starter and runnable Quickstart contract verified.")
