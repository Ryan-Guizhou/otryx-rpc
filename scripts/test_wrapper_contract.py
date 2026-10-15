#!/usr/bin/env python3
"""Verify source-checkout Maven Wrapper integrity and Starter boundaries."""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SHA256 = "0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb"
MAVEN_URL = (
    "https://repo.maven.apache.org/maven2/org/apache/maven/"
    "apache-maven/3.9.11/apache-maven-3.9.11-bin.zip"
)


def test_wrapper_scripts() -> None:
    props = dict(
        line.split("=", 1) for line in
        (ROOT / ".mvn/wrapper/maven-wrapper.properties")
        .read_text(encoding="utf-8").splitlines()
        if line and not line.lstrip().startswith("#")
    )
    assert props["wrapperVersion"] == "3.3.4"
    assert props["distributionType"] == "only-script"
    assert props["distributionUrl"] == MAVEN_URL
    assert props["distributionSha256Sum"] == SHA256
    assert re.fullmatch("[0-9a-f]{64}", SHA256)
    assert (ROOT / "mvnw").read_text(encoding="utf-8").startswith("#!/bin/sh")
    assert "Apache Maven Wrapper" in (
        ROOT / "mvnw.cmd").read_text(encoding="utf-8")


def test_starter_classpath() -> None:
    """The only public Starter is lightweight and adapters stay opt-in."""
    def coordinates(relative: str) -> set[str]:
        pom = ET.parse(ROOT / relative).getroot()
        return {
            dep.findtext("m:artifactId", namespaces=NS)
            for dep in pom.findall("m:dependencies/m:dependency", NS)
        }

    starter = coordinates("otryx-spring-boot/otryx-spring-boot-starter/pom.xml")
    auto = coordinates("otryx-spring-boot/otryx-spring-boot-autoconfigure/pom.xml")
    assert starter == {"otryx-spring-boot-autoconfigure", "spring-boot-starter"}
    assert {"otryx-core", "otryx-serialization-fory", "otryx-transport-vertx"} <= auto
    assert not (ROOT / "otryx-spring-boot/otryx-spring-boot-starter-lite").exists()



if __name__ == "__main__":
    test_wrapper_scripts()
    test_starter_classpath()
    print("Maven Wrapper and Starter contracts verified")
