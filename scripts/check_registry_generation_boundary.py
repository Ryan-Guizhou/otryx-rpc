#!/usr/bin/env python3
"""Require application registry wiring on both sides of the five-pair rolling gate.

The factory and Consumer/Provider wiring of the runnable Starter must agree.
Historical interface-registry baselines are rejected before starting a process.
This is a source wiring guard, not proof of RPC or backend interoperability.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

SPRING = Path("otryx-spring-boot/otryx-spring-boot-autoconfigure/src/main/java/"
              "com/peachsoft/otryx/spring")
JAVA_NONCODE = re.compile(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.DOTALL)


def source(root: Path, relative: str) -> str:
    """Ignore comments and literals so historical descriptions cannot select a gate."""
    return JAVA_NONCODE.sub(" ", (root / SPRING / relative).read_text(encoding="utf-8"))


def classify(text: str, interface_pattern: str, application_pattern: str, label: str) -> str:
    """Require exactly one recognized wiring form; never treat unknown as migration."""
    interface = re.search(interface_pattern, text) is not None
    application = re.search(application_pattern, text) is not None
    if interface and application:
        raise ValueError(f"Ambiguous {label}: both interface and application wiring are present")
    if not interface and not application:
        raise ValueError(f"Unknown {label}: no recognized registry wiring")
    return "application" if application else "interface"


def registry_generation(root: Path) -> str:
    """Require factory, Consumer discovery, and Provider membership to agree."""
    configuration = source(root, "autoconfigure/OtryxRpcAutoConfiguration.java")
    runtime = source(root, "runtime/OtryxRpcRuntimeCoordinator.java")
    factory = classify(configuration,
                       r"\brequireExtension\s*\(\s*RegistryFactory\s*\.\s*class\b",
                       r"\brequireExtension\s*\(\s*ApplicationRegistryFactory\s*\.\s*class\b",
                       "Starter factory")
    consumer = classify(runtime, r"\.\s*serviceDiscovery\s*\(",
                        r"\.\s*applicationControlPlane\s*\(", "Consumer discovery")
    provider = classify(runtime, r"\.\s*serviceRegistrar\s*\(",
                        r"\.\s*applicationMembership\s*\(", "Provider membership")
    if len({factory, consumer, provider}) != 1:
        raise ValueError("Inconsistent Starter registry wiring: "
                         f"factory={factory}, consumer={consumer}, provider={provider}")
    return factory


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("current", type=Path)
    args = parser.parse_args()
    try:
        baseline = registry_generation(args.baseline)
        current = registry_generation(args.current)
        for label, generation in (("baseline", baseline), ("current", current)):
            if generation != "application":
                raise ValueError(f"Unsupported {label} registry mode: {generation}; "
                                 "rolling requires application-level Starter wiring on both sides")
    except (OSError, ValueError) as error:
        print(f"Registry generation check failed: {error}", file=sys.stderr)
        return 1
    print(f"Starter registry generation: baseline={baseline}, current={current}", file=sys.stderr)
    print("same-generation")
    return 0


if __name__ == "__main__":
    sys.exit(main())
