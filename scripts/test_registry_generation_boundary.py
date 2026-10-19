#!/usr/bin/env python3
"""Fixture regressions for registry generations and required JVM matrix orchestration.

The process fixtures exercise the real shell runner's gate selection and failure
propagation. They do not claim to test RPC, transport, or an actual Nacos server;
those remain the workflow's real Java process checks.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
DETECTOR = ROOT / "scripts/check_registry_generation_boundary.py"
RUNNER = ROOT / "scripts/run_rolling_compatibility_e2e.sh"
SPRING = Path("otryx-spring-boot/otryx-spring-boot-autoconfigure/src/main/java/"
              "com/peachsoft/otryx/spring")


def write_starter(root: Path, generation: str) -> None:
    """Create minimal concrete Starter wiring, not a file-presence generation flag."""
    factory = "ApplicationRegistryFactory" if generation == "application" else "RegistryFactory"
    client = "applicationControlPlane(resolver, membership, namespace)" if generation == "application" \
        else "serviceDiscovery(registry)"
    provider = "applicationMembership(membership)" if generation == "application" \
        else "serviceRegistrar(registry.registrar().orElseThrow())"
    autoconfigure = root / SPRING / "autoconfigure/OtryxRpcAutoConfiguration.java"
    runtime = root / SPRING / "runtime/OtryxRpcRuntimeCoordinator.java"
    autoconfigure.parent.mkdir(parents=True, exist_ok=True)
    runtime.parent.mkdir(parents=True, exist_ok=True)
    autoconfigure.write_text(f"class Config {{ Object create() {{ return requireExtension(\n"
                            f"    {factory}.class, key, name); }} }}\n", encoding="utf-8")
    runtime.write_text(f"class Runtime {{ Object client() {{ return Client.builder().{client}; }}\n"
                       f"Object provider() {{ return Server.builder().{provider}; }} }}\n", encoding="utf-8")


class RegistryGenerationBoundaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old = self.root / "old"
        self.new = self.root / "new"

    def detect(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(DETECTOR), str(self.old), str(self.new)],
                              capture_output=True, text=True, check=False)

    def test_application_types_in_core_do_not_hide_interface_starter(self) -> None:
        write_starter(self.old, "interface")
        write_starter(self.new, "application")
        historical_core = self.old / "otryx-core/ApplicationRegistry.java"
        historical_core.parent.mkdir(parents=True)
        historical_core.write_text("interface ApplicationRegistry {}", encoding="utf-8")
        result = self.detect()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "registry-migration")
        self.assertIn("baseline=interface", result.stderr)
        self.assertIn("current=application", result.stderr)

    def test_same_generation_keeps_full_rolling_matrix(self) -> None:
        for generation in ("interface", "application"):
            with self.subTest(generation=generation):
                write_starter(self.old, generation)
                write_starter(self.new, generation)
                result = self.detect()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), "same-generation")

    def test_reverse_generation_boundary_is_explicit(self) -> None:
        write_starter(self.old, "application")
        write_starter(self.new, "interface")
        result = self.detect()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "registry-migration")

    def test_comments_and_string_literals_do_not_change_generation(self) -> None:
        write_starter(self.old, "interface")
        write_starter(self.new, "interface")
        config = self.old / SPRING / "autoconfigure/OtryxRpcAutoConfiguration.java"
        config.write_text(config.read_text(encoding="utf-8") +
                          '/* requireExtension(ApplicationRegistryFactory.class, key, name); */\n'
                          'class Note { String s = "requireExtension(ApplicationRegistryFactory.class)"; }\n',
                          encoding="utf-8")
        runtime = self.old / SPRING / "runtime/OtryxRpcRuntimeCoordinator.java"
        runtime.write_text(runtime.read_text(encoding="utf-8") +
                           '// client.applicationControlPlane(); server.applicationMembership();\n',
                           encoding="utf-8")
        result = self.detect()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "same-generation")

    def test_partial_migration_fails_instead_of_skipping_interoperation(self) -> None:
        write_starter(self.old, "interface")
        write_starter(self.new, "application")
        runtime = self.new / SPRING / "runtime/OtryxRpcRuntimeCoordinator.java"
        runtime.write_text(runtime.read_text(encoding="utf-8").replace(
            "applicationMembership(membership)", "serviceRegistrar(registry)"), encoding="utf-8")
        result = self.detect()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("inconsistent", result.stderr.lower())
        self.assertEqual(result.stdout, "")

    def test_missing_or_unknown_starter_fails_closed(self) -> None:
        write_starter(self.old, "interface")
        write_starter(self.new, "application")
        config = self.new / SPRING / "autoconfigure/OtryxRpcAutoConfiguration.java"
        for source in (None, "class UnrecognizedConfiguration {}"):
            with self.subTest(source=source):
                if source is None:
                    config.unlink()
                else:
                    config.write_text(source, encoding="utf-8")
                result = self.detect()
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")

    def test_dual_factory_wiring_is_rejected(self) -> None:
        write_starter(self.old, "interface")
        write_starter(self.new, "application")
        config = self.new / SPRING / "autoconfigure/OtryxRpcAutoConfiguration.java"
        config.write_text(config.read_text(encoding="utf-8") +
                          "class Legacy { Object create() { return requireExtension("
                          "RegistryFactory.class, key, name); } }", encoding="utf-8")
        result = self.detect()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ambiguous", result.stderr.lower())

    def run_matrix(self, old_generation: str, new_generation: str,
                   fail_pair: str = "", fail_bootstrap: bool = False,
                   extra_environment: dict[str, str] | None = None
                   ) -> tuple[subprocess.CompletedProcess[str], list[str]]:
        """Launch process fixtures through the real runner and collect attempted pairs."""
        write_starter(self.old, old_generation)
        write_starter(self.new, new_generation)
        for checkout in (self.old, self.new):
            for role in ("provider", "consumer"):
                target = checkout / "otryx-quickstart" / f"otryx-quickstart-{role}" / "target"
                target.mkdir(parents=True, exist_ok=True)
                (target / f"{role}-exec.jar").touch()
        binaries = self.root / "bin"
        binaries.mkdir(exist_ok=True)
        java = binaries / "java"
        java.write_text("#!" + sys.executable + "\n" + textwrap.dedent('''\
            import json
            import os
            from pathlib import Path
            import signal
            import sys
            args = sys.argv[1:]
            events = Path(os.environ["FIXTURE_EVENTS"])
            environment = {key: os.environ.get(key) for key in
                           ("OTRYX_RPC_COMPAT_PORT", "OTRYX_RPC_CLIENT_TIMEOUT")}
            if "org.springframework.boot.loader.launch.PropertiesLauncher" in args:
                jar = Path(args[args.index("-cp") + 1])
                position = args.index("org.springframework.boot.loader.launch.PropertiesLauncher")
                event = {"kind": "bootstrap", "jar": str(jar), "args": args[position + 1:],
                         "timeout": os.environ.get("FIXTURE_BOOTSTRAP_TIMEOUT"),
                         "killAfter": os.environ.get("FIXTURE_BOOTSTRAP_KILL_AFTER"),
                         "environment": environment,
                         "main": args[0]}
                with events.open("a", encoding="utf-8") as output:
                    output.write(json.dumps(event) + "\\n")
                sys.exit(23 if os.environ.get("FIXTURE_FAIL_BOOTSTRAP") == "true" else 0)
            jar = Path(args[args.index("-jar") + 1])
            version = "N+1" if "new" in jar.parts else "N"
            with events.open("a", encoding="utf-8") as output:
                output.write(json.dumps({"kind": "application", "jar": str(jar), "args": args,
                                         "environment": environment}) + "\\n")
            active = Path(os.environ["FIXTURE_ACTIVE_PROVIDER"])
            if "provider" in jar.name:
                active.write_text(json.dumps({"version": version, "pid": os.getpid()}), encoding="utf-8")
                print("OTRYX RPC server started: bind=127.0.0.1:19090, advertised=127.0.0.1:19090", flush=True)
            else:
                pair = version + "->" + json.loads(active.read_text(encoding="utf-8"))["version"]
                with Path(os.environ["FIXTURE_PAIRS"]).open("a", encoding="utf-8") as output:
                    output.write(pair + "\\n")
                if pair == os.environ.get("FIXTURE_FAIL_PAIR"):
                    print("Fixture RPC failure", flush=True)
                    sys.exit(1)
                print("RPC quickstart completed successfully: Hello, OTRYX RPC!", flush=True)
            signal.pause()
            '''), encoding="utf-8")
        java.chmod(0o755)
        timeout = binaries / "timeout"
        timeout.write_text("#!" + sys.executable + "\n" + textwrap.dedent('''\
            import json
            import os
            from pathlib import Path
            import subprocess
            import sys
            args = sys.argv[1:]
            kill_after = ""
            if args and args[0].startswith("--kill-after="):
                kill_after = args.pop(0).split("=", 1)[1]
            if len(args) > 1 and args[1] == "java":
                env = os.environ.copy()
                env["FIXTURE_BOOTSTRAP_TIMEOUT"] = args[0]
                env["FIXTURE_BOOTSTRAP_KILL_AFTER"] = kill_after
                result = subprocess.run(args[1:], env=env, check=False, timeout=5)
                sys.exit(result.returncode)
            try:
                state = json.loads(Path(os.environ["FIXTURE_ACTIVE_PROVIDER"]).read_text(encoding="utf-8"))
                os.kill(state["pid"], 0)
            except (OSError, ValueError):
                sys.exit(1)
            '''), encoding="utf-8")
        timeout.chmod(0o755)
        sleep = binaries / "sleep"
        sleep.write_text("#!/bin/sh\n/bin/sleep 0.01\n", encoding="utf-8")
        sleep.chmod(0o755)
        pairs = self.root / "pairs.txt"
        env = os.environ.copy()
        env.update({"PATH": str(binaries) + os.pathsep + env["PATH"],
                    "FIXTURE_ACTIVE_PROVIDER": str(self.root / "active.txt"),
                    "FIXTURE_PAIRS": str(pairs), "FIXTURE_FAIL_PAIR": fail_pair,
                    "FIXTURE_EVENTS": str(self.root / "events.jsonl"),
                    "FIXTURE_FAIL_BOOTSTRAP": str(fail_bootstrap).lower()})
        env.update(extra_environment or {})
        env.pop("GITHUB_STEP_SUMMARY", None)
        result = subprocess.run(["bash", str(RUNNER), str(self.old), str(self.new)],
                                env=env, capture_output=True, text=True, check=False, timeout=20)
        return result, pairs.read_text(encoding="utf-8").splitlines() if pairs.exists() else []

    def events(self) -> list[dict]:
        path = self.root / "events.jsonl"
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] \
            if path.exists() else []

    def test_bootstrap_uses_current_jar_once_before_any_application(self) -> None:
        result, _ = self.run_matrix("interface", "application")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        events = self.events()
        self.assertTrue(events)
        self.assertEqual(events[0]["kind"], "bootstrap")
        self.assertEqual(sum(event["kind"] == "bootstrap" for event in events), 1)
        self.assertEqual(Path(events[0]["jar"]), self.new / "otryx-quickstart" /
                         "otryx-quickstart-provider/target/provider-exec.jar")
        self.assertEqual(events[0]["main"], "-Dloader.main="
                         "com.peachsoft.otryx.registry.nacos.NacosV2MetadataBootstrap")
        self.assertEqual(events[0]["timeout"], "120s")
        self.assertEqual(events[0]["killAfter"], "5s")

    def test_all_applications_use_the_initialized_isolated_scope(self) -> None:
        result, _ = self.run_matrix("application", "application")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        events = self.events()
        self.assertEqual(events[0]["kind"], "bootstrap")
        endpoint, namespace, group = events[0]["args"]
        self.assertTrue(group.startswith("OTRYX_RPC_E2E_"), group)
        self.assertNotEqual(group, "OTRYX_RPC")
        self.assertEqual(namespace, "public")
        for event in events[1:]:
            self.assertEqual(event["kind"], "application")
            self.assertIn("--otryx.rpc.registry.endpoints=" + endpoint, event["args"])
            self.assertIn("--otryx.rpc.registry.namespace=" + namespace, event["args"])
            self.assertIn("--otryx.rpc.registry.nacos.group=" + group, event["args"])

    def test_bootstrap_failure_blocks_every_application_and_success_summary(self) -> None:
        result, pairs = self.run_matrix("interface", "application", fail_bootstrap=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(pairs, [])
        self.assertEqual([event["kind"] for event in self.events()], ["bootstrap"])
        self.assertNotIn("Compatibility PASS", result.stdout)
        self.assertNotIn("Registry migration verified", result.stdout)

    def test_script_port_control_does_not_leak_into_application_environment(self) -> None:
        result, _ = self.run_matrix("interface", "application", extra_environment={
            "OTRYX_RPC_COMPAT_PORT": "19443", "OTRYX_RPC_CLIENT_TIMEOUT": "4s"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        events = self.events()
        self.assertTrue(events)
        for event in events:
            self.assertIsNone(event["environment"]["OTRYX_RPC_COMPAT_PORT"])
            self.assertEqual(event["environment"]["OTRYX_RPC_CLIENT_TIMEOUT"], "4s")
            if event["kind"] == "application" and "provider" in Path(event["jar"]).name:
                self.assertIn("--otryx.rpc.server.port=19443", event["args"])
                self.assertIn("--otryx.rpc.server.advertised-port=19443", event["args"])

    def test_migration_requires_both_same_version_jvm_pairs(self) -> None:
        result, pairs = self.run_matrix("interface", "application")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(pairs, ["N->N", "N+1->N+1"])
        self.assertIn("Cross-generation rolling compatibility has not been established", result.stdout)
        self.assertNotIn("rolling compatibility matrix passed", result.stdout)

    def test_same_generation_runs_all_five_real_runner_pairs(self) -> None:
        result, pairs = self.run_matrix("application", "application")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(pairs, ["N->N", "N+1->N", "N->N+1", "N+1->N+1", "N+1->N"])
        self.assertIn("rolling compatibility matrix passed", result.stdout)

    def test_old_same_version_failure_blocks_migration(self) -> None:
        result, pairs = self.run_matrix("interface", "application", "N->N")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(set(pairs), {"N->N"})
        self.assertNotIn("Registry migration verified", result.stdout)

    def test_current_same_version_failure_blocks_migration(self) -> None:
        result, pairs = self.run_matrix("interface", "application", "N+1->N+1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("N->N", pairs)
        self.assertIn("N+1->N+1", pairs)
        self.assertNotIn("Registry migration verified", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
