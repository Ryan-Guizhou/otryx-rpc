#!/usr/bin/env python3
"""Adversarial fixtures for application-only source, descriptor and documentation cleanup."""

from pathlib import Path
import tempfile
import unittest

from check_migration_readiness import ABSENCE_CONTRACT_LITERALS, cleanup_violations


class MigrationCleanupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path: str, text: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def test_service_contract_and_local_lb_dto_are_allowed(self) -> None:
        self.write("otryx-core/src/main/java/Directory.java", """
            ApplicationRegistry registry;
            ServiceKey contract;
            ServiceInstance candidate;
            RegistrySubscription subscription;
            ApplicationDirectorySnapshot snapshot;
            ApplicationRegistryFactory factory;
            HttpRegistryClient client;
            String prefix = "/otryx/v2/applications/";
        """)
        self.assertEqual(cleanup_violations(self.root), [])

    def test_production_tests_benchmarks_and_empty_descriptor_are_checked(self) -> None:
        paths = (
            "otryx-core/src/main/java/Legacy.java",
            "otryx-core/src/test/java/LegacyTest.java",
            "benchmarks/rpc-comparison/src/main/java/Legacy.java",
            "otryx-core/src/main/resources/META-INF/services/com.peachsoft.otryx.registry.RegistryFactory",
        )
        for path in paths:
            self.write(path, "ServiceRegistrar registrar;" if path.endswith(".java") else "")
        findings = cleanup_violations(self.root)
        for path in paths:
            self.assertTrue(any(line.startswith(path) for line in findings), path)

    def test_old_keys_builder_paths_and_modes_are_rejected(self) -> None:
        tokens = (
            "client.serviceDiscovery(registry)", "server.serviceRegistrar(registrar)",
            "peach.rpc.schema.fingerprint", "peach.rpc.protocol.version", '"/otryx/"',
            '"/otryx/ns/"', "registry-mode: legacy", "registry-migration",
            "import com.peachsoft.otryx.registry.Registry;",
        )
        for index, token in enumerate(tokens):
            self.write(f"otryx-core/src/main/resources/fixture-{index}.properties", token)
        findings = cleanup_violations(self.root)
        for index in range(len(tokens)):
            self.assertTrue(any(f"fixture-{index}.properties" in line for line in findings))

    def test_current_docs_cannot_advertise_retired_api(self) -> None:
        for path in ("README.md", "README.en-US.md", "docs/spi.md", "docs/design/current.md",
                     "docs/reference/current.md", "otryx-quickstart/README.md"):
            self.write(path, "Use RegistryFactory and ServiceDiscovery")
        self.assertEqual(len(cleanup_violations(self.root)), 12)

    def test_archived_prose_and_build_output_do_not_create_false_findings(self) -> None:
        self.write("docs/archive/releases/old.md", "ServiceDiscovery historical release")
        self.write("docs/engineering/agent-governance-checkpoint.md", "ServiceRegistrar removal evidence")
        self.write("otryx-core/target/old.java", "class Registry {}")
        self.assertEqual(cleanup_violations(self.root), [])

    def test_java_under_archive_is_never_exempted(self) -> None:
        self.write("docs/archive/Legacy.java", "class ServiceRegistrar {}")
        self.assertTrue(cleanup_violations(self.root))

    def test_configuration_registry_class_is_not_the_removed_spi(self) -> None:
        self.write("otryx-spring-boot/src/main/java/Properties.java", """
            package com.peachsoft.otryx.spring.autoconfigure;
            class Properties { public static class Registry {} }
        """)
        self.assertEqual(cleanup_violations(self.root), [])
        self.write("otryx-core/src/main/java/Obsolete.java", """
            package com.peachsoft.otryx.registry;
            interface Registry {}
        """)
        self.assertTrue(cleanup_violations(self.root))

    def test_absence_contract_literals_are_scoped_to_named_negative_tests(self) -> None:
        for path, literals in ABSENCE_CONTRACT_LITERALS.items():
            self.write(path, "\n".join(sorted(literals)))
        self.assertEqual(cleanup_violations(self.root), [])
        self.write("otryx-core/src/main/java/Legacy.java", 'String type = "ServiceRegistrar";')
        self.assertTrue(cleanup_violations(self.root))

    def test_absence_contract_never_exempts_imports_or_other_legacy_usage(self) -> None:
        path = next(iter(ABSENCE_CONTRACT_LITERALS))
        self.write(path, '"ServiceRegistrar"\n'
                   'import com.peachsoft.otryx.registry.ServiceRegistrar;\n'
                   'client.serviceDiscovery(registry);\n'
                   'String hidden = "com.peachsoft.otryx.registry.ServiceRegistrar";')
        findings = cleanup_violations(self.root)
        self.assertEqual(len(findings), 3)
        self.assertTrue(any(":2:" in line for line in findings))
        self.assertTrue(any(":3:" in line for line in findings))
        self.assertTrue(any(":4:" in line for line in findings))


if __name__ == "__main__":
    unittest.main(verbosity=2)
