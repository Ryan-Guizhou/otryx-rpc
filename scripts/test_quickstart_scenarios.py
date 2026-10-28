#!/usr/bin/env python3
"""Adversarial snapshot and real-shell orchestration fixtures, separate from RPC E2E."""

from __future__ import annotations

import copy
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from assert_quickstart_snapshot import DETAILS, GREETING, RELAY, SERVICES, assert_snapshot

ROOT = Path(__file__).resolve().parents[1]
PROVIDER = "otryx-quickstart-revision-provider"
CONSUMER = "otryx-quickstart-revision-consumer"


def fixture_snapshot(states: list[dict], names: list[str]) -> dict:
    """Build synthetic backend responses solely for validator and shell control-flow tests."""
    applications = []
    routes = {name: [] for name in SERVICES}
    for app_name in names:
        members = []
        manifests = {}
        for state in states:
            if state["app"] != app_name:
                continue
            exports = state.get("exports", [])
            revision = "sha256:" + hashlib.sha256(
                (app_name + ",".join(sorted(exports))).encode()).hexdigest() if exports else None
            member = {"instanceId": state["instance"], "role": state["role"],
                      "rpcEndpoint": {"host": "127.0.0.1", "port": state["port"]} if exports else None,
                      "weight": 100 if exports else 0, "manifestRevision": revision}
            members.append(member)
            if exports:
                services = []
                for name in sorted(exports):
                    index = sorted(SERVICES).index(name)
                    services.append({"serviceKey": {"serviceName": name, "version": "1.0.0", "group": "default"},
                                     "serviceId": index + 1, "methodIds": [index + 10],
                                     "schemaFingerprint": hashlib.sha256(name.encode()).hexdigest()})
                    routes[name].append({"applicationName": app_name, **{
                        key: value for key, value in member.items() if key != "weight"}})
                manifests[revision] = {"revision": revision, "services": services}
        applications.append({"applicationName": app_name, "members": members,
                             "manifests": list(manifests.values())})
    services = [{"serviceKey": {"serviceName": name, "version": "1.0.0", "group": "default"},
                 "mappedApplications": sorted({entry["applicationName"] for entry in providers}),
                 "verifiedProviders": providers} for name, providers in sorted(routes.items())]
    return {"applicationNamespace": "public", "applications": applications, "services": services}


def revision_fixture(extended: bool = False) -> dict:
    states = [{"app": PROVIDER, "instance": "baseline", "role": "PROVIDER", "port": 19090,
               "exports": [GREETING]}, {"app": CONSUMER, "instance": "consumer-main", "role": "CONSUMER"}]
    if extended:
        states.append({"app": PROVIDER, "instance": "extended", "role": "PROVIDER", "port": 19091,
                       "exports": [GREETING, DETAILS]})
    return fixture_snapshot(states, [PROVIDER, CONSUMER])


class SnapshotAdversarialTest(unittest.TestCase):
    """An attractive success-shaped document must not bypass actual scenario invariants."""

    def test_valid_actual_export_sets_and_prior_revision(self) -> None:
        baseline = revision_fixture()
        assert_snapshot("revision", "baseline", baseline)
        assert_snapshot("revision", "coexist", revision_fixture(True), baseline)
        assert_snapshot("revision", "rolled-back", baseline, baseline)

    def test_consumer_with_any_endpoint_is_rejected(self) -> None:
        for port in (0, 19090):
            with self.subTest(port=port):
                data = revision_fixture()
                data["applications"][1]["members"][0]["rpcEndpoint"] = {"host": "127.0.0.1", "port": port}
                with self.assertRaisesRegex(ValueError, "Consumer-only"):
                    assert_snapshot("revision", "baseline", data)

    def test_consumer_cannot_enter_verified_provider_pool(self) -> None:
        data = revision_fixture()
        service = next(item for item in data["services"] if item["serviceKey"]["serviceName"] == GREETING)
        member = data["applications"][1]["members"][0]
        service["verifiedProviders"].append({"applicationName": CONSUMER, **member})
        with self.assertRaisesRegex(ValueError, "Consumer-only"):
            assert_snapshot("revision", "baseline", data)

    def test_success_labels_without_two_real_manifests_are_rejected(self) -> None:
        data = revision_fixture(True)
        first = data["applications"][0]["members"][0]["manifestRevision"]
        data["applications"][0]["members"][1]["manifestRevision"] = first
        with self.assertRaises(ValueError):
            assert_snapshot("revision", "coexist", data, revision_fixture())

    def test_route_must_match_its_own_instance_manifest(self) -> None:
        data = revision_fixture(True)
        service = next(item for item in data["services"] if item["serviceKey"]["serviceName"] == DETAILS)
        service["verifiedProviders"][0]["instanceId"] = "baseline"
        with self.assertRaisesRegex(ValueError, "Route"):
            assert_snapshot("revision", "coexist", data, revision_fixture())

    def test_common_contract_changes_block_coexistence_and_rollback(self) -> None:
        for stage in ("coexist", "rollback-coexist"):
            for field, value in (("schemaFingerprint", "b" * 64), ("methodIds", [321]), ("serviceId", 987)):
                with self.subTest(stage=stage, field=field):
                    data = revision_fixture(True)
                    extended = next(manifest for manifest in data["applications"][0]["manifests"]
                                    if len(manifest["services"]) == 2)
                    greeting = next(service for service in extended["services"]
                                    if service["serviceKey"]["serviceName"] == GREETING)
                    greeting[field] = value
                    with self.assertRaisesRegex(ValueError, "Common Greeting contract differs"):
                        assert_snapshot("revision", stage, data, revision_fixture())

    def test_invalid_fingerprint_and_boolean_wire_identifiers_are_rejected(self) -> None:
        for field, value in (("schemaFingerprint", "not-a-fingerprint"), ("serviceId", True), ("methodIds", [False])):
            with self.subTest(field=field):
                data = revision_fixture()
                data["applications"][0]["manifests"][0]["services"][0][field] = value
                with self.assertRaises(ValueError):
                    assert_snapshot("revision", "baseline", data)

    def test_missing_mapping_and_duplicate_member_fail_closed(self) -> None:
        data = revision_fixture()
        greeting = next(item for item in data["services"] if item["serviceKey"]["serviceName"] == GREETING)
        greeting["mappedApplications"] = []
        with self.assertRaisesRegex(ValueError, "Mapping"):
            assert_snapshot("revision", "baseline", data)
        data = revision_fixture()
        data["applications"][0]["members"].append(copy.deepcopy(data["applications"][0]["members"][0]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            assert_snapshot("revision", "baseline", data)

    def test_empty_read_does_not_prove_provider_shutdown(self) -> None:
        with self.assertRaises(ValueError):
            assert_snapshot("revision", "providers-stopped", {"applicationNamespace": "public",
                            "applications": [], "services": []}, revision_fixture())

    def test_rollback_must_restore_the_original_actual_revision(self) -> None:
        prior = revision_fixture()
        data = revision_fixture()
        replacement = "sha256:" + "f" * 64
        data["applications"][0]["members"][0]["manifestRevision"] = replacement
        data["applications"][0]["manifests"][0]["revision"] = replacement
        for item in data["services"]:
            for provider in item["verifiedProviders"]:
                provider["manifestRevision"] = replacement
        with self.assertRaisesRegex(ValueError, "Rollback"):
            assert_snapshot("revision", "rolled-back", data, prior)

    def test_wrong_application_namespace_and_partial_snapshot_are_rejected(self) -> None:
        data = revision_fixture()
        data["applicationNamespace"] = "default"
        with self.assertRaisesRegex(ValueError, "namespace"):
            assert_snapshot("revision", "baseline", data)
        data = revision_fixture()
        data.pop("services")
        with self.assertRaises(ValueError):
            assert_snapshot("revision", "baseline", data)

    def test_explicit_isolated_namespace_applies_to_current_and_previous_evidence(self) -> None:
        data = revision_fixture()
        data["applicationNamespace"] = "otryx-e2e-isolated"
        assert_snapshot("revision", "baseline", data, namespace="otryx-e2e-isolated")
        with self.assertRaisesRegex(ValueError, "namespace"):
            assert_snapshot("revision", "rolled-back", data, revision_fixture(), namespace="otryx-e2e-isolated")


class LaunchLogGenerationTest(unittest.TestCase):
    """Hold child redirection until the parent can inspect the previous launch's log."""

    @staticmethod
    def shell_function(script: str, name: str) -> str:
        source = (ROOT / "scripts" / script).read_text(encoding="utf-8")
        start = source.index(name + "() {\n")
        return source[start:source.index("\n}\n", start) + 3]

    def run_launch_fixture(self, script: str, function: str, calls: str,
                           marker: str, fail_outcome: bool = False) -> subprocess.CompletedProcess:
        launch = self.shell_function(script, function).replace(
            '>"$log" 2>&1 &', '>"$(fixture_log_destination "$log")" 2>&1 &')
        waiter = self.shell_function("quickstart_e2e_common.sh", "quickstart_wait_log").replace(
            "quickstart_wait_log() {", "fixture_wait_log() {", 1)
        harness = textwrap.dedent('''\
            set -euo pipefail
            source "$2"
            QUICKSTART_WORK_DIR="$1"
            QUICKSTART_READY_SECONDS=3
            QUICKSTART_STOP_SECONDS=2
            PROVIDER_JAR=provider-fixture
            CONSUMER_JAR=consumer-fixture
            PROVIDER_APP=provider-fixture
            CONSUMER_APP=consumer-fixture
            MAX_STALENESS=3s
            fixture_marker="$3"
            fixture_fail_outcome="$4"

            fixture_cleanup() {
              local pid
              for pid in "${QUICKSTART_PIDS[@]}"; do
                kill -KILL "$pid" 2>/dev/null || true
                wait "$pid" 2>/dev/null || true
              done
            }
            trap fixture_cleanup EXIT

            fixture_log_destination() {
              local deadline=$((SECONDS + 3))
              while [[ ! -f "$QUICKSTART_WORK_DIR/release-child" ]]; do
                (( SECONDS < deadline )) || return 1
                sleep 0.01
              done
              printf '%s' "$1"
            }

            quickstart_track_pid() {
              QUICKSTART_PIDS+=("$1")
              # The child cannot have opened its log yet. This is the same boundary
              # at which the production waiter can otherwise accept an old marker.
              if [[ -f "$log" ]] && grep -Fq "$fixture_marker" "$log"; then
                printf 'stale\n'
              else
                printf 'clean\n'
              fi
              rm -f "$QUICKSTART_WORK_DIR/child-started"
              : >"$QUICKSTART_WORK_DIR/release-child"
            }

            java() {
              printf '%s\n' "$fixture_marker"
              : >"$QUICKSTART_WORK_DIR/child-started"
              exec sleep 30
            }

            quickstart_wait_log() {
              local deadline=$((SECONDS + 3))
              while [[ ! -f "$QUICKSTART_WORK_DIR/child-started" ]]; do
                (( SECONDS < deadline )) || return 1
                sleep 0.01
              done
              fixture_wait_log "$@" || return $?
              if [[ "$fixture_fail_outcome" == true ]]; then
                # Model a failed attempt that leaves a late success marker behind.
                rm -f "$QUICKSTART_WORK_DIR/release-child"
                return 1
              fi
            }
            ''')
        with tempfile.TemporaryDirectory() as work:
            fixture = Path(work) / "launch.sh"
            fixture.write_text(harness + waiter + "\n" + launch + "\n" + calls, encoding="utf-8")
            return subprocess.run(["bash", str(fixture), work,
                                   str(ROOT / "scripts" / "quickstart_e2e_common.sh"),
                                   marker, "true" if fail_outcome else "false"],
                                  capture_output=True, text=True, check=False, timeout=12)

    def test_provider_restarts_cannot_reuse_the_previous_readiness_marker(self) -> None:
        marker = "OTRYX RPC server started: bind=0.0.0.0:19090, advertised=127.0.0.1:19090"
        for script, function, arguments, pid in (
                ("run_quickstart_revision_e2e.sh", "start_revision", "baseline 19090 false", "BASELINE_PID"),
                ("run_quickstart_process_e2e.sh", "start_provider", "19090", "PROVIDER_PID")):
            with self.subTest(script=script):
                calls = (f'{function} {arguments}\nquickstart_stop_pid "${pid}"\n'
                         'rm -f "$QUICKSTART_WORK_DIR/release-child"\n'
                         f'{function} {arguments}\n')
                result = self.run_launch_fixture(script, function, calls, marker)
                self.assertEqual(result.stdout.splitlines(), ["clean", "clean"], result.stdout + result.stderr)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_each_fresh_consumer_attempt_discards_previous_outcome_markers(self) -> None:
        result = self.run_launch_fixture(
            "run_quickstart_process_e2e.sh", "probe_fresh_consumer", "probe_fresh_consumer\n",
            "RPC quickstart completed successfully: Hello, OTRYX RPC!", fail_outcome=True)
        self.assertEqual(result.stdout.splitlines(), ["clean", "clean", "clean"], result.stdout + result.stderr)
        self.assertEqual(result.returncode, 1)
        self.assertIn("A fresh Consumer could not discover the Provider", result.stderr)


class ShellScenarioTest(unittest.TestCase):
    """Run production shell scripts with explicit fake external processes, never as RPC proof."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("quickstart_e2e_common.sh", "bootstrap_quickstart_nacos.sh",
                     "observe_quickstart_nacos.sh",
                     "observe_quickstart_registry.sh", "assert_quickstart_snapshot.py",
                     "run_quickstart_revision_e2e.sh", "run_quickstart_mtls_e2e.sh",
                     "run_quickstart_process_e2e.sh", "run_three_process_deadline_e2e.sh", "run_quickstart_e2e.sh",
                     "run_quickstart_memory.sh"):
            shutil.copy2(ROOT / "scripts" / name, scripts / name)
        # This shell-only fixture records the real wrapper's environment and delegation.
        # The Python security runner has separate failure-contract tests and real E2E gates.
        (scripts / "run_quickstart_mtls_e2e.py").write_text(textwrap.dedent('''\
            import json
            import os
            from pathlib import Path
            event = {"kind": "security-scenario", "environment": {key: os.environ.get(key) for key in
                ("OTRYX_RPC_E2E_RESTART_NACOS", "OTRYX_RPC_E2E_MAX_STALENESS", "OTRYX_RPC_CLIENT_TIMEOUT")}}
            with Path(os.environ["FIXTURE_EVENTS"]).open("a", encoding="utf-8") as output:
                output.write(json.dumps(event) + "\\n")
            print("Security runner delegation fixture completed")
            '''), encoding="utf-8")
        common = scripts / "quickstart_e2e_common.sh"
        common.write_text(common.read_text(encoding="utf-8").replace("QUICKSTART_SNAPSHOT_SECONDS=120",
                          "QUICKSTART_SNAPSHOT_SECONDS=2").replace("QUICKSTART_STOP_SECONDS=35",
                          "QUICKSTART_STOP_SECONDS=2").replace("QUICKSTART_READY_SECONDS=45",
                          "QUICKSTART_READY_SECONDS=3"), encoding="utf-8")
        for role in ("provider", "consumer", "memory"):
            target = self.root / "otryx-quickstart" / f"otryx-quickstart-{role}" / "target"
            target.mkdir(parents=True)
            (target / f"{role}-exec.jar").touch()
        self.state = self.root / "state"
        self.state.mkdir()
        self.events_path = self.root / "events.jsonl"
        binaries = self.root / "bin"
        binaries.mkdir()
        java = binaries / "java"
        java.write_text("#!" + sys.executable + "\n" + textwrap.dedent('''\
            import atexit
            import json
            import os
            from pathlib import Path
            import signal
            import sys
            import time
            sys.path.insert(0, os.environ["FIXTURE_MODULE_DIR"])
            from test_quickstart_scenarios import DETAILS, GREETING, RELAY, fixture_snapshot
            args = sys.argv[1:]
            root = Path(os.environ["FIXTURE_STATE"])
            environment = {key: os.environ.get(key) for key in
                ("OTRYX_RPC_E2E_RESTART_NACOS", "OTRYX_RPC_E2E_MAX_STALENESS",
                 "OTRYX_RPC_CLIENT_TIMEOUT", "OTRYX_QUICKSTART_TLS_KEY")}
            def record(event):
                with Path(os.environ["FIXTURE_EVENTS"]).open("a", encoding="utf-8") as output:
                    output.write(json.dumps(event) + "\\n")
            if "org.springframework.boot.loader.launch.PropertiesLauncher" in args:
                position = args.index("org.springframework.boot.loader.launch.PropertiesLauncher")
                parameters = args[position + 1:]
                if "NacosV2MetadataBootstrap" in args[0]:
                    record({"kind": "bootstrap", "args": parameters, "environment": environment})
                    sys.exit(23 if os.environ.get("FIXTURE_FAIL_BOOTSTRAP") == "true" else 0)
                backend, endpoint, namespace, group, metadata_endpoint, output, *names = parameters
                record({"kind": "snapshot", "args": parameters, "environment": environment})
                states = [json.loads(path.read_text()) for path in root.glob("*.json")]
                data = fixture_snapshot(states, names)
                data["applicationNamespace"] = namespace
                if os.environ.get("FIXTURE_BAD_STAGE") and Path(output).name.startswith(
                        os.environ["FIXTURE_BAD_STAGE"] + "-"):
                    data["applications"] = []
                with Path(output).open("x", encoding="utf-8") as handle:
                    json.dump(data, handle)
                sys.exit(0)
            options = dict(arg[2:].split("=", 1) for arg in args if arg.startswith("--") and "=" in arg)
            jar_name = Path(args[args.index("-jar") + 1]).name
            memory = "memory" in jar_name
            app = "otryx-quickstart-memory" if memory else options["otryx.rpc.registry.application-name"]
            instance = "memory" if memory else options["otryx.rpc.registry.instance-id"]
            provider = "provider" in jar_name
            role = "BOTH" if memory else ("PROVIDER" if provider else "CONSUMER")
            exports = [GREETING] if memory else []
            if provider:
                if options.get("otryx.rpc.quickstart.role") == "relay":
                    role, exports = "BOTH", [RELAY]
                else:
                    exports = [GREETING]
                    if options.get("otryx.rpc.quickstart.details-enabled") == "true":
                        exports.append(DETAILS)
            state = {"app": app, "instance": instance, "role": role, "exports": exports,
                     "port": int(options.get("otryx.rpc.server.port", 0))}
            own = root / (str(os.getpid()) + ".json")
            own.write_text(json.dumps(state))
            record({"kind": "application", "pid": os.getpid(), "state": state, "args": args,
                    "environment": environment})
            def cleanup():
                own.unlink(missing_ok=True)
            atexit.register(cleanup)
            def stop(signum, frame):
                sys.exit(0)
            signal.signal(signal.SIGTERM, stop)
            if memory:
                print("Quickstart memory RPC succeeded: Hello, Quickstart!", flush=True)
                signal.pause()
            elif provider:
                print("OTRYX RPC server started: bind=0.0.0.0:" + str(state["port"]) +
                      ", advertised=127.0.0.1:" + str(state["port"]), flush=True)
                signal.pause()
            else:
                denied = options.get("otryx.rpc.quickstart.expect-authorization-denied") == "true"
                if denied and os.environ.get("FIXTURE_TLS_FAILURE") == "true":
                    print("SSLHandshakeException: fixture certificate failure", flush=True)
                    sys.exit(7)
                if denied:
                    marker = "RPC authorization denial verified."
                elif options.get("otryx.rpc.quickstart.use-details") == "true":
                    marker = "RPC details quickstart completed successfully: DETAILS_OK"
                elif options.get("otryx.rpc.quickstart.use-relay") == "true":
                    marker = "RPC deadline chain completed: CHAIN_OK remainingMillis=4000"
                else:
                    marker = "RPC quickstart completed successfully: Hello, OTRYX RPC!"
                print(marker, flush=True)
                if int(options.get("otryx.rpc.quickstart.repeat-interval-millis", 0)) > 0:
                    while True:
                        time.sleep(0.05)
                        print(marker, flush=True)
                signal.pause()
            '''), encoding="utf-8")
        java.chmod(0o755)
        # CI=true fixtures must never invoke a real Docker daemon if a process control is lost.
        docker = binaries / "docker"
        docker.write_text('#!/bin/sh\necho "Unexpected Docker invocation in isolated fixture" >&2\nexit 97\n',
                          encoding="utf-8")
        docker.chmod(0o755)
        self.environment = os.environ.copy()
        self.environment.update({"PATH": str(binaries) + os.pathsep + os.environ["PATH"],
                                 "PYTHONDONTWRITEBYTECODE": "1", "FIXTURE_MODULE_DIR": str(ROOT / "scripts"),
                                 "FIXTURE_STATE": str(self.state), "FIXTURE_EVENTS": str(self.events_path),
                                 "OTRYX_RPC_E2E_RESTART_NACOS": "false", "CI": "false", "QUICKSTART_REGISTRY": "nacos"})
        self.environment.pop("OTRYX_QUICKSTART_EVIDENCE_DIR", None)

    def tearDown(self) -> None:
        # Completed children remove their ownership records; never revisit old, reusable PIDs.
        for state in self.state.glob("*.json"):
            try:
                os.kill(int(state.stem), signal.SIGKILL)
            except ProcessLookupError:
                pass
        self.temp.cleanup()

    def events(self) -> list[dict]:
        return [json.loads(line) for line in self.events_path.read_text().splitlines()] \
            if self.events_path.exists() else []

    def run_script(self, name: str, extra: dict[str, str] | None = None) -> subprocess.CompletedProcess:
        return subprocess.run(["bash", str(self.root / "scripts" / name)], cwd=self.root,
                              env={**self.environment, **(extra or {})}, capture_output=True, text=True,
                              check=False, timeout=40)

    def test_bootstrap_failure_starts_no_application_or_observer(self) -> None:
        result = self.run_script("run_quickstart_revision_e2e.sh", {"FIXTURE_FAIL_BOOTSTRAP": "true"})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([item["kind"] for item in self.events()], ["bootstrap"])
        self.assertNotIn(": PASS", result.stdout)

    def test_each_backend_shares_explicit_scope_across_all_three_process_scenarios(self) -> None:
        for backend, endpoint in (("etcd", "http://127.0.0.1:2379"), ("consul", "http://127.0.0.1:8500"),
                                  ("eureka", "http://127.0.0.1:8761/eureka")):
            for script in ("run_quickstart_process_e2e.sh", "run_three_process_deadline_e2e.sh",
                           "run_quickstart_revision_e2e.sh"):
                with self.subTest(backend=backend, script=script):
                    self.events_path.unlink(missing_ok=True)
                    result = self.run_script(script, {"QUICKSTART_REGISTRY": backend,
                            "QUICKSTART_REGISTRY_ENDPOINT": endpoint,
                            "QUICKSTART_EUREKA_METADATA_ENDPOINT": "http://127.0.0.1:8500"})
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    events = self.events()
                    self.assertFalse(any(item["kind"] == "bootstrap" for item in events))
                    snapshots = [item for item in events if item["kind"] == "snapshot"]
                    self.assertTrue(snapshots)
                    namespace = snapshots[0]["args"][2]
                    self.assertTrue(namespace.startswith("otryx-e2e-"), namespace)
                    for item in snapshots:
                        self.assertEqual(item["args"][:4], [backend, endpoint, namespace, "-"])
                        self.assertEqual(item["args"][4], "http://127.0.0.1:8500" if backend == "eureka" else "-")
                    for item in (entry for entry in events if entry["kind"] == "application"):
                        self.assertIn("--otryx.rpc.registry.type=" + backend, item["args"])
                        self.assertIn("--otryx.rpc.registry.endpoints=" + endpoint, item["args"])
                        self.assertIn("--otryx.rpc.registry.namespace=" + namespace, item["args"])
                        if backend == "eureka":
                            self.assertIn("--otryx.rpc.registry.eureka.metadata-backend=consul", item["args"])
                            self.assertIn("--otryx.rpc.registry.eureka.metadata-endpoints=http://127.0.0.1:8500",
                                          item["args"])
                    self.assertEqual(list(self.state.glob("*.json")), [])

    def test_eureka_missing_metadata_and_unknown_backend_fail_before_application_start(self) -> None:
        for environment in ({"QUICKSTART_REGISTRY": "eureka", "QUICKSTART_EUREKA_METADATA_ENDPOINT": ""},
                            {"QUICKSTART_REGISTRY": "unknown"}):
            with self.subTest(environment=environment):
                result = self.run_script("run_quickstart_process_e2e.sh", environment)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.events(), [])

    def test_nacos_restart_cannot_operate_on_another_backend(self) -> None:
        result = self.run_script("run_quickstart_process_e2e.sh", {
            "QUICKSTART_REGISTRY": "etcd", "OTRYX_RPC_E2E_RESTART_NACOS": "true"})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.events(), [])

    def test_revision_runner_uses_real_export_switch_and_same_long_lived_consumer(self) -> None:
        result = self.run_script("run_quickstart_revision_e2e.sh")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        events = self.events()
        self.assertEqual(events[0]["kind"], "bootstrap")
        self.assertEqual(sum(event["kind"] == "bootstrap" for event in events), 1)
        consumers = [event for event in events if event["kind"] == "application"
                     and event["state"]["app"] == CONSUMER]
        self.assertEqual(len(consumers), 1)
        providers = [event for event in events if event["kind"] == "application"
                     and event["state"]["app"] == PROVIDER]
        self.assertEqual([item["state"]["instance"] for item in providers], ["baseline", "extended", "baseline"])
        self.assertEqual([set(item["state"]["exports"]) for item in providers],
                         [{GREETING}, {GREETING, DETAILS}, {GREETING}])
        endpoint, namespace, group, profile = events[0]["args"]
        self.assertEqual(profile, "TRUSTED_DEVELOPMENT")
        snapshots = [event for event in events if event["kind"] == "snapshot"]
        for event in snapshots:
            self.assertEqual(event["args"][:5], ["nacos", endpoint, namespace, group, "-"])
        paths = [item["args"][5] for item in snapshots]
        self.assertEqual(len(paths), len(set(paths)))
        self.assertTrue(any(Path(path).name.startswith("providers-stopped-") for path in paths))
        self.assertEqual(list(self.state.glob("*.json")), [])

    def test_failed_coexistence_observation_blocks_promotion_and_success(self) -> None:
        result = self.run_script("run_quickstart_revision_e2e.sh", {"FIXTURE_BAD_STAGE": "coexist"})
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("Promotion RPC verified", result.stdout)
        self.assertNotIn("Two actual application Manifest revisions and rollback: PASS", result.stdout)
        self.assertEqual(list(self.state.glob("*.json")), [])

    def test_process_membership_and_script_environment_boundaries(self) -> None:
        result = self.run_script("run_quickstart_process_e2e.sh", {
            "OTRYX_RPC_E2E_MAX_STALENESS": "7s", "OTRYX_RPC_CLIENT_TIMEOUT": "4s", "CI": "true"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        applications = [event for event in self.events() if event["kind"] == "application"]
        for event in applications:
            self.assertIsNone(event["environment"]["OTRYX_RPC_E2E_RESTART_NACOS"])
            self.assertIsNone(event["environment"]["OTRYX_RPC_E2E_MAX_STALENESS"])
            self.assertEqual(event["environment"]["OTRYX_RPC_CLIENT_TIMEOUT"], "4s")
        consumers = [event for event in applications if event["state"]["role"] == "CONSUMER"]
        self.assertEqual(len(consumers), 1)
        self.assertIn("--otryx.rpc.client.directory-max-staleness=7s", consumers[0]["args"])
        self.assertTrue(any(event["kind"] == "snapshot" and "provider-stopped-" in event["args"][5]
                            for event in self.events()))

    def assert_process_controls_are_isolated(self, script: str) -> None:
        """Exercise exported parent controls without allowing them to become Spring configuration."""
        result = self.run_script(script, {"OTRYX_RPC_E2E_RESTART_NACOS": "false",
                                         "OTRYX_RPC_E2E_MAX_STALENESS": "7s",
                                         "OTRYX_RPC_CLIENT_TIMEOUT": "4s", "CI": "true"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        events = self.events()
        self.assertTrue(events)
        for event in events:
            self.assertIsNone(event["environment"]["OTRYX_RPC_E2E_RESTART_NACOS"], event)
            self.assertIsNone(event["environment"]["OTRYX_RPC_E2E_MAX_STALENESS"], event)
            self.assertEqual(event["environment"]["OTRYX_RPC_CLIENT_TIMEOUT"], "4s", event)

    def test_memory_does_not_leak_exported_process_controls(self) -> None:
        self.assert_process_controls_are_isolated("run_quickstart_memory.sh")

    def test_chain_does_not_leak_exported_process_controls(self) -> None:
        self.assert_process_controls_are_isolated("run_three_process_deadline_e2e.sh")

    def test_revision_does_not_leak_exported_process_controls(self) -> None:
        self.assert_process_controls_are_isolated("run_quickstart_revision_e2e.sh")

    def test_mtls_does_not_leak_exported_process_controls(self) -> None:
        self.assert_process_controls_are_isolated("run_quickstart_mtls_e2e.sh")

    def test_complete_suite_isolates_controls_and_process_still_reads_them(self) -> None:
        self.assert_process_controls_are_isolated("run_quickstart_e2e.sh")
        events = self.events()
        applications = [event for event in events if event["kind"] == "application"]
        self.assertTrue(any(event["state"]["app"] == "otryx-quickstart-memory" for event in applications))
        consumers = [event for event in applications
                     if event["state"]["app"] == "otryx-quickstart-process-consumer"]
        self.assertEqual(len(consumers), 1)
        self.assertIn("--otryx.rpc.client.directory-max-staleness=7s", consumers[0]["args"])
        self.assertEqual(sum(event["kind"] == "security-scenario" for event in events), 1)

    def test_chain_observes_three_distinct_application_roles(self) -> None:
        result = self.run_script("run_three_process_deadline_e2e.sh")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        applications = [event["state"] for event in self.events() if event["kind"] == "application"]
        self.assertEqual({app["role"] for app in applications}, {"CONSUMER", "PROVIDER", "BOTH"})
        self.assertEqual(len({app["app"] for app in applications}), 3)

    def test_observer_refuses_existing_evidence_before_launch(self) -> None:
        output = self.root / "existing.json"
        output.write_text('{"old": true}')
        jar = self.root / "otryx-quickstart/otryx-quickstart-provider/target/provider-exec.jar"
        result = subprocess.run(["bash", str(self.root / "scripts/observe_quickstart_nacos.sh"),
                                 str(jar), "127.0.0.1:8848", "public", "test", "public", str(output), PROVIDER],
                                env=self.environment, capture_output=True, text=True, check=False, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.events(), [])
        self.assertEqual(output.read_text(), '{"old": true}')

    def test_suite_stops_after_first_failure(self) -> None:
        sequence = ["run_quickstart_memory.sh", "run_quickstart_process_e2e.sh",
                    "run_three_process_deadline_e2e.sh", "run_quickstart_revision_e2e.sh",
                    "run_quickstart_mtls_e2e.sh"]
        for index, name in enumerate(sequence):
            (self.root / "scripts" / name).write_text(
                '#!/bin/bash\nprintf \'%s\\n\' "' + name + '" >> "$FIXTURE_SEQUENCE"\n' +
                ("exit 9\n" if index == 2 else "exit 0\n"), encoding="utf-8")
        attempted = self.root / "sequence.txt"
        result = self.run_script("run_quickstart_e2e.sh", {"FIXTURE_SEQUENCE": str(attempted)})
        self.assertEqual(result.returncode, 9)
        self.assertEqual(attempted.read_text().splitlines(), sequence[:3])
        self.assertNotIn("Complete application-level Quickstart suite: PASS", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
