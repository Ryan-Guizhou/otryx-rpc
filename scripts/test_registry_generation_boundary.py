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
import signal
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
        self.addCleanup(self.stop_fixture_processes)

    def stop_fixture_processes(self) -> None:
        """A timeout in a broken runner must not leave its deliberately stubborn fixtures alive."""
        for event in self.events():
            if event["kind"] == "application":
                try:
                    os.kill(event["pid"], signal.SIGKILL)
                except ProcessLookupError:
                    pass

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
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("baseline", result.stderr.lower())
        self.assertIn("application", result.stderr.lower())
        self.assertEqual(result.stdout, "")

    def test_same_generation_keeps_full_rolling_matrix(self) -> None:
        for generation in ("application",):
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
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("current", result.stderr.lower())
        self.assertEqual(result.stdout, "")

    def test_comments_and_string_literals_do_not_change_generation(self) -> None:
        write_starter(self.old, "application")
        write_starter(self.new, "application")
        config = self.old / SPRING / "autoconfigure/OtryxRpcAutoConfiguration.java"
        config.write_text(config.read_text(encoding="utf-8") +
                          '/* requireExtension(RegistryFactory.class, key, name); */\n'
                          'class Note { String s = "requireExtension(RegistryFactory.class)"; }\n',
                          encoding="utf-8")
        runtime = self.old / SPRING / "runtime/OtryxRpcRuntimeCoordinator.java"
        runtime.write_text(runtime.read_text(encoding="utf-8") +
                           '// client.serviceDiscovery(); server.serviceRegistrar();\n',
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
                           ("OTRYX_RPC_COMPAT_PORT", "OTRYX_RPC_CLIENT_TIMEOUT", "ROLLING_COMPAT_STOP_SECONDS")}
            if os.environ.get("FIXTURE_IGNORE_TERM") == "true":
                signal.signal(signal.SIGTERM, signal.SIG_IGN)
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
                                         "pid": os.getpid(), "environment": environment}) + "\\n")
            active = Path(os.environ["FIXTURE_ACTIVE_PROVIDER"])
            if "provider" in jar.name:
                starts = json.loads(active.read_text(encoding="utf-8"))["starts"] + 1 if active.exists() else 1
                active.write_text(json.dumps({"version": version, "pid": os.getpid(), "starts": starts}),
                                  encoding="utf-8")
                print("OTRYX RPC server started: bind=127.0.0.1:19090, advertised=127.0.0.1:19090", flush=True)
            else:
                provider = json.loads(active.read_text(encoding="utf-8"))
                pair = version + "->" + provider["version"]
                with Path(os.environ["FIXTURE_PAIRS"]).open("a", encoding="utf-8") as output:
                    output.write(pair + "\\n")
                failure = os.environ.get("FIXTURE_FAIL_PAIR")
                if pair == failure or (failure == "rollback" and provider["starts"] == 3):
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
        result, _ = self.run_matrix("application", "application")
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
        result, pairs = self.run_matrix("application", "application", fail_bootstrap=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(pairs, [])
        self.assertEqual([event["kind"] for event in self.events()], ["bootstrap"])
        self.assertNotIn("Compatibility PASS", result.stdout)
        self.assertNotIn("rolling compatibility matrix passed", result.stdout)

    def test_script_port_control_does_not_leak_into_application_environment(self) -> None:
        result, _ = self.run_matrix("application", "application", extra_environment={
            "OTRYX_RPC_COMPAT_PORT": "19443", "OTRYX_RPC_CLIENT_TIMEOUT": "4s",
            "ROLLING_COMPAT_STOP_SECONDS": "1"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        events = self.events()
        self.assertTrue(events)
        for event in events:
            self.assertIsNone(event["environment"]["OTRYX_RPC_COMPAT_PORT"])
            self.assertIsNone(event["environment"]["ROLLING_COMPAT_STOP_SECONDS"])
            self.assertEqual(event["environment"]["OTRYX_RPC_CLIENT_TIMEOUT"], "4s")
            if event["kind"] == "application" and "provider" in Path(event["jar"]).name:
                self.assertIn("--otryx.rpc.server.port=19443", event["args"])
                self.assertIn("--otryx.rpc.server.advertised-port=19443", event["args"])

    def test_interface_baseline_blocks_all_processes(self) -> None:
        result, pairs = self.run_matrix("interface", "application")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(pairs, [])
        self.assertEqual(self.events(), [])
        self.assertNotIn("rolling compatibility matrix passed", result.stdout)

    def test_same_generation_runs_all_five_real_runner_pairs(self) -> None:
        result, pairs = self.run_matrix("application", "application")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(pairs, ["N->N", "N+1->N", "N->N+1", "N+1->N+1", "N+1->N"])
        self.assertIn("rolling compatibility matrix passed", result.stdout)

    def test_term_ignoring_owned_processes_are_killed_and_reaped_within_budget(self) -> None:
        result, pairs = self.run_matrix("application", "application", extra_environment={
            "FIXTURE_IGNORE_TERM": "true", "ROLLING_COMPAT_STOP_SECONDS": "1"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("forcing termination", result.stderr)
        self.assertEqual(pairs, ["N->N", "N+1->N", "N->N+1", "N+1->N+1", "N+1->N"])
        processes = [event["pid"] for event in self.events() if event["kind"] == "application"]
        self.assertEqual(len(processes), 8)
        for pid in processes:
            with self.assertRaises(ProcessLookupError, msg=f"Owned fixture process remained alive: {pid}"):
                os.kill(pid, 0)

    def test_invalid_stop_budget_blocks_processes(self) -> None:
        for budget in ("0", "36", "-1", "invalid"):
            with self.subTest(budget=budget):
                result, pairs = self.run_matrix("application", "application", extra_environment={
                    "ROLLING_COMPAT_STOP_SECONDS": budget})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("ROLLING_COMPAT_STOP_SECONDS", result.stderr)
                self.assertEqual(pairs, [])
                self.assertEqual(self.events(), [])

    def test_two_interface_checkouts_are_not_a_supported_rolling_mode(self) -> None:
        result, pairs = self.run_matrix("interface", "interface")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(pairs, [])
        self.assertEqual(self.events(), [])

    def test_new_consumer_old_provider_failure_blocks_rolling(self) -> None:
        result, pairs = self.run_matrix("application", "application", "N+1->N")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(pairs[0], "N->N")
        self.assertNotIn("N->N+1", pairs)
        self.assertNotIn("rolling compatibility matrix passed", result.stdout)

    def test_old_consumer_new_provider_failure_blocks_rolling(self) -> None:
        result, pairs = self.run_matrix("application", "application", "N->N+1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(pairs[:2], ["N->N", "N+1->N"])
        self.assertNotIn("N+1->N+1", pairs)
        self.assertNotIn("rolling compatibility matrix passed", result.stdout)

    def test_rollback_failure_cannot_publish_full_matrix_success(self) -> None:
        result, pairs = self.run_matrix("application", "application", "rollback")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(pairs[:4], ["N->N", "N+1->N", "N->N+1", "N+1->N+1"])
        self.assertIn("N rollback provider", result.stderr)
        self.assertNotIn("rolling compatibility matrix passed", result.stdout)

    def test_baseline_rpc_failure_blocks_rolling(self) -> None:
        result, pairs = self.run_matrix("application", "application", "N->N")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(set(pairs), {"N->N"})
        self.assertNotIn("rolling compatibility matrix passed", result.stdout)

    def test_current_rpc_failure_blocks_rolling(self) -> None:
        result, pairs = self.run_matrix("application", "application", "N+1->N+1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("N->N", pairs)
        self.assertIn("N+1->N+1", pairs)
        self.assertNotIn("rolling compatibility matrix passed", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
