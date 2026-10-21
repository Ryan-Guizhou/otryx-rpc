#!/usr/bin/env python3
"""Failure-contract tests for the security runner; these are not RPC/TLS E2E evidence."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import run_quickstart_mtls_e2e as runner
from etcd_security_fixture import FixtureRpcFailure


class SecurityRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.work = self.root / "private"
        self.work.mkdir(mode=0o700)
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.registry = SimpleNamespace(
            endpoint="https://localhost:2379", namespace=runner.NAMESPACE,
            pki=self.root / "registry-pki", directory=self.root / "registry",
            credentials={role: (role, "private-password-" + role)
                         for role in ("admin", "provider", "consumer", "observer")},
            authenticate=Mock(return_value="private-observer-token"),
            member_key=Mock(return_value="owned-provider-member"),
            request=Mock(side_effect=FixtureRpcFailure(403, 7)))
        self.scenario = object.__new__(runner.SecurityScenario)
        self.scenario.registry = self.registry
        self.scenario.evidence = self.evidence
        self.scenario.work = self.work
        self.scenario.pki = self.work / "rpc-pki"
        self.scenario.diagnostics = None
        self.scenario.processes = []
        self.scenario.provider = Mock()
        self.scenario.provider.poll.return_value = None
        self.scenario.provider_jar = self.root / "provider-exec.jar"
        self.scenario.consumer_jar = self.root / "consumer-exec.jar"
        self.scenario.environment = {"QUICKSTART_REGISTRY_SECURITY_FILE": "/private/observer.json"}
        self.scenario.fingerprints = {name: str(index) * 64
                                      for index, name in enumerate(("provider", "consumer", "rotated", "expired"), 1)}
        self.scenario.valid_until = "2099-01-01T00:00:00Z"
        self.scenario.expired_at = "2000-01-01T00:00:00Z"
        self.scenario.outcomes = []
        self.scenario.stage = "prepare"

    def trace_scenario(self) -> list[tuple]:
        trace = []
        for name in ("certificates", "start_provider", "client", "startup_rejected", "snapshot", "record"):
            setattr(self.scenario, name, Mock(side_effect=lambda *args, _name=name, **kwargs:
                                            trace.append((_name, args, kwargs))))
        return trace

    def test_authentication_reasons_are_exact_and_cannot_mix_with_authorization(self) -> None:
        reasons = ("CERTIFICATE_REJECTED", "IDENTITY_UNBOUND", "APPLICATION_MISMATCH",
                   "NAMESPACE_MISMATCH", "IDENTITY_EXPIRED", "SECURITY_CONTEXT_CHANGED")
        for reason in reasons:
            with self.subTest(reason=reason):
                self.assertEqual(runner.expected_marker(reason, False), runner.AUTHENTICATION_MARKER + reason)
                with self.assertRaises(runner.ScenarioFailure):
                    runner.expected_marker(reason, True)
        for reason in ("TLS_ERROR", "IDENTITY_UNBOUND_EXTRA", "", "private-value"):
            with self.assertRaises(runner.ScenarioFailure) as failure:
                runner.expected_marker(reason, False)
            self.assertNotIn(reason or "private-value", str(failure.exception))
        self.assertEqual(runner.expected_marker(None, True), runner.AUTHORIZATION_MARKER)
        self.assertEqual(runner.expected_marker(None, False), runner.GREETING_MARKER)

    def test_process_controls_are_removed_without_mutating_parent_environment(self) -> None:
        environment = {name: "fixture" for name in runner.PROCESS_CONTROLS} | {"OTRYX_RPC_CLIENT_TIMEOUT": "4s"}
        isolated = runner.isolated_environment(environment)
        self.assertEqual(isolated, {"OTRYX_RPC_CLIENT_TIMEOUT": "4s"})
        self.assertTrue(all(name in environment for name in runner.PROCESS_CONTROLS))

    def test_missing_jar_fails_before_private_directory_allocation(self) -> None:
        with patch.object(runner, "executable_jar", side_effect=runner.ScenarioFailure("Missing JAR")), \
                patch.object(runner.tempfile, "mkdtemp") as allocation:
            with self.assertRaises(runner.ScenarioFailure):
                runner.SecurityScenario(self.registry, self.evidence)
            allocation.assert_not_called()

    def test_application_environment_contains_credential_paths_without_secret_values(self) -> None:
        for role in ("provider", "consumer"):
            environment = self.scenario.application_environment(role, role)
            self.assertEqual(environment["OTRYX_QUICKSTART_REGISTRY_CREDENTIALS_DIR"],
                             str(self.registry.directory / "spring-credentials" / role))
            self.assertEqual(environment["OTRYX_QUICKSTART_REGISTRY_CA"], str(self.registry.pki / "ca.crt"))
            self.assertNotEqual(environment["OTRYX_QUICKSTART_REGISTRY_CA"], environment["OTRYX_QUICKSTART_TLS_CA"])
            serialized = json.dumps(environment)
            for _, password in self.registry.credentials.values():
                self.assertNotIn(password, serialized)
            self.assertNotIn("spring-credentials/admin", serialized)

    def test_client_rejects_ambiguous_expectations_before_any_process_start(self) -> None:
        self.scenario.start = Mock()
        with self.assertRaises(runner.ScenarioFailure):
            self.scenario.client("invalid", reason="IDENTITY_UNBOUND", authorization_denied=True)
        self.scenario.start.assert_not_called()

    def assert_client_snapshot_liveness(self, exit_code: int | None, *, after_snapshot: bool = False,
                                        authorization_denied: bool = False) -> None:
        process = Mock()
        state = {"returncode": None, "snapshot_returned": False}
        marker = runner.expected_marker(None, authorization_denied)

        def poll() -> int | None:
            if after_snapshot and state["snapshot_returned"]:
                state["returncode"] = exit_code
            return state["returncode"]

        def launch(*args, **kwargs):
            kwargs["stdout"].write((marker + "\n").encode())
            return process

        def snapshot(stage: str) -> None:
            self.assertIn(process, self.scenario.processes)
            self.assertIsNone(state["returncode"])
            if not after_snapshot:
                state["returncode"] = exit_code
            state["snapshot_returned"] = True

        def cleanup(owned, *args) -> None:
            self.assertIs(owned, process)
            self.assertIn(process, self.scenario.processes)

        process.poll.side_effect = poll
        self.scenario.snapshot = Mock(side_effect=snapshot)
        self.scenario.record = Mock()
        with patch.object(runner, "OwnedProcess", side_effect=launch), \
                patch.object(runner, "stop_process_group", side_effect=cleanup) as stop:
            if exit_code is None:
                self.scenario.client("client-liveness", authorization_denied=authorization_denied)
                self.scenario.record.assert_called_once_with("client-liveness", "GREETING_ALLOWED")
            else:
                with self.assertRaises(runner.ScenarioFailure):
                    self.scenario.client("client-liveness", authorization_denied=authorization_denied)
                self.scenario.record.assert_not_called()
            self.scenario.snapshot.assert_called_once_with("client-liveness")
            stop.assert_called_once_with(process, runner.STOP_SECONDS, 5)
        self.assertNotIn(process, self.scenario.processes)
        self.assertEqual(self.scenario.outcomes, [])

    def test_client_nonzero_exit_during_snapshot_cannot_record_success(self) -> None:
        self.assert_client_snapshot_liveness(17)

    def test_client_zero_exit_after_snapshot_cannot_record_expected_denial(self) -> None:
        self.assert_client_snapshot_liveness(0, after_snapshot=True, authorization_denied=True)

    def test_live_client_records_success_and_releases_owned_process(self) -> None:
        self.assert_client_snapshot_liveness(None)

    def test_tls_error_cannot_satisfy_authorization_or_authentication_outcome(self) -> None:
        log = self.work / "tls.log"
        log.write_text("SSLHandshakeException: fixture certificate failure\n", encoding="utf-8")
        process = Mock()
        process.poll.return_value = 7
        self.scenario.start = Mock(return_value=(process, log))
        self.scenario.stop = Mock()
        self.scenario.snapshot = Mock()
        self.scenario.record = Mock()
        for expected in ({"authorization_denied": True}, {"reason": "CERTIFICATE_REJECTED"}):
            with self.subTest(expected=expected), self.assertRaises(runner.ScenarioFailure):
                self.scenario.client("rejected", **expected)
        self.scenario.snapshot.assert_not_called()
        self.scenario.record.assert_not_called()
        self.assertEqual(self.scenario.stop.call_count, 2)

    def test_success_shaped_marker_suffix_does_not_satisfy_precise_reason(self) -> None:
        log = self.work / "wrong-reason.log"
        marker = runner.expected_marker("IDENTITY_UNBOUND", False)
        log.write_text(marker + "_OTHER\n", encoding="utf-8")
        process = Mock()
        process.poll.return_value = None
        with patch.object(runner.time, "monotonic", side_effect=[0, 0, runner.READY_SECONDS + 1]), \
                patch.object(runner.time, "sleep"):
            with self.assertRaises(runner.ScenarioFailure):
                self.scenario.wait_marker(process, log, marker)
        log.write_text("2026-10-19 INFO GreetingRunner - " + marker + "\n", encoding="utf-8")
        self.scenario.wait_marker(process, log, marker)

    def test_startup_failure_requires_nonzero_exit_precise_reason_and_no_rpc_start(self) -> None:
        marker = "RPC authentication failed: IDENTITY_EXPIRED"
        process = Mock()
        log = self.work / "startup.log"
        self.scenario.start = Mock(return_value=(process, log))
        self.scenario.stop = Mock()
        self.scenario.snapshot = Mock()
        self.scenario.record = Mock()
        for code, content in ((0, marker), (1, "generic TLS failure"),
                              (1, marker + "\nOTRYX RPC server started:"),
                              (1, marker + "\n" + runner.GREETING_MARKER)):
            with self.subTest(code=code, content=content):
                process.wait.return_value = code
                log.write_text(content, encoding="utf-8")
                with self.assertRaises(runner.ScenarioFailure):
                    self.scenario.startup_rejected("negative", marker)
        self.scenario.snapshot.assert_not_called()
        self.scenario.record.assert_not_called()
        self.assertEqual(self.scenario.stop.call_count, 4)

    def test_observer_timeout_retains_process_ownership_until_cleanup(self) -> None:
        observer = Mock()
        observer.wait.side_effect = subprocess.TimeoutExpired("observer", 1)
        self.scenario.stop = Mock()
        with patch.object(runner, "OwnedProcess", return_value=observer) as launch, \
                patch.object(runner.time, "monotonic", side_effect=[0, 0, 0, runner.SNAPSHOT_SECONDS + 1]), \
                patch.object(runner.time, "sleep"):
            with self.assertRaises(runner.ScenarioFailure):
                self.scenario.snapshot("E01-allowed-initial")
        self.assertTrue(launch.call_args.kwargs["start_new_session"])
        self.assertIn(observer, self.scenario.processes)
        self.scenario.stop.assert_called_once_with(observer)

    def test_full_sequence_covers_rotation_local_remote_policy_and_registry_failures(self) -> None:
        trace = self.trace_scenario()
        self.scenario.run()
        calls = {args[0]: (name, args, kwargs) for name, args, kwargs in trace if args}
        expected = {
            "E01-allowed-initial", "E02-rotated-consumer", "E03-unbound-provider",
            "E04-local-application-mismatch", "E05-remote-application-mismatch",
            "E06-remote-namespace-mismatch", "E07-wrong-rpc-ca", "E08-expired-local-binding",
            "E09-expired-certificate", "E10-expired-policy", "E11-details-denied",
            "E12-missing-authorizer-provider", "E12-missing-authorizer-both", "E13-allowed-repeat",
            "E14-wrong-registry-ca", "E14-wrong-registry-credentials", "E15-observer-read-only"}
        self.assertEqual({stage for stage in calls if stage.startswith("E")}, expected)
        self.assertEqual(calls["E02-rotated-consumer"][1][1], "rotated")
        self.assertEqual(calls["E03-unbound-provider"][2]["reason"], "IDENTITY_UNBOUND")
        self.assertEqual(calls["E04-local-application-mismatch"][2]["overrides"],
                         {"OTRYX_QUICKSTART_CONSUMER_APPLICATION": "another-consumer"})
        self.assertEqual(calls["E05-remote-application-mismatch"][2]["reason"], "APPLICATION_MISMATCH")
        self.assertEqual(calls["E06-remote-namespace-mismatch"][2]["reason"], "NAMESPACE_MISMATCH")
        self.assertEqual(calls["E06-remote-namespace-mismatch"][2]["overrides"],
                         {"OTRYX_QUICKSTART_PROVIDER_BINDING_NAMESPACE": "another-namespace"})
        self.assertEqual(calls["E07-wrong-rpc-ca"][2]["reason"], "CERTIFICATE_REJECTED")
        self.assertEqual(calls["E09-expired-certificate"][1][1], "Invalid TLS material")
        self.assertTrue(calls["provider-expired-policy"][2]["expired_policy"])
        self.assertTrue(calls["E10-expired-policy"][2]["authorization_denied"])
        self.assertIn("--otryx.rpc.quickstart.use-details=true", calls["E11-details-denied"][2]["arguments"])
        for both in (False, True):
            arguments = calls["E12-missing-authorizer-" + ("both" if both else "provider")][2]["arguments"]
            self.assertIn("--otryx.rpc.client.enabled=" + str(both).lower(), arguments)
        self.registry.authenticate.assert_called_once_with("observer")
        self.assertEqual(self.registry.request.call_args.args[2], "private-observer-token")
        for path in self.evidence.iterdir():
            self.assertTrue(path.name == "summary.log" or path.suffix == ".json", path)
            self.assertNotIn("PRIVATE KEY", path.read_text())
            self.assertNotIn("private-password", path.read_text())

    def assert_missing_authorizer_argv(self, both: bool) -> None:
        trace = self.trace_scenario()
        self.scenario.run()
        stage = "E12-missing-authorizer-" + ("both" if both else "provider")
        name, args, kwargs = next(entry for entry in trace if entry[1] and entry[1][0] == stage)
        self.assertEqual(name, "startup_rejected")
        self.assertEqual(args[1], "NETWORK_MTLS requires an explicit RpcAuthorizer bean")
        self.assertEqual(kwargs["role"], "provider")
        self.assertEqual(kwargs["certificate"], "provider")
        supplied_arguments = kwargs["arguments"].copy()
        with patch.object(runner, "OwnedProcess") as launch:
            process, log = self.scenario.start(kwargs["role"], stage, kwargs["certificate"],
                                               kwargs["arguments"], kwargs.get("overrides"))
        command = launch.call_args.args[0]
        self.assertEqual(command[:3], ["java", "-jar", str(self.scenario.provider_jar)])
        expected = {
            "--otryx.rpc.client.enabled": str(both).lower(),
            "--otryx.rpc.registry.instance-id": "missing-authorizer",
            "--otryx.rpc.quickstart.mtls.authorization-enabled": "false",
            "--otryx.rpc.server.enabled": "true",
            "--otryx.rpc.server.port": str(runner.RPC_PORT),
            "--spring.profiles.active": "mtls",
            "--spring.application.name": runner.PROVIDER_APP,
            "--otryx.rpc.registry.application-name": runner.PROVIDER_APP,
        }
        for key, value in expected.items():
            self.assertEqual([argument for argument in command[3:] if argument.partition("=")[0] == key],
                             [key + "=" + value], key)
        option_names = [argument.partition("=")[0] for argument in command[3:]]
        self.assertEqual(len(option_names), len(set(option_names)))
        self.assertEqual(kwargs["arguments"], supplied_arguments)
        self.assertIn(process, self.scenario.processes)
        self.assertEqual(log, self.work / (stage + ".log"))
        self.assertTrue(launch.call_args.kwargs["start_new_session"])

    def test_missing_authorizer_provider_launch_has_single_effective_options(self) -> None:
        self.assert_missing_authorizer_argv(False)

    def test_missing_authorizer_both_launch_overrides_client_default_once(self) -> None:
        self.assert_missing_authorizer_argv(True)

    def test_observer_write_must_be_forbidden_by_permission_not_tls_or_authentication(self) -> None:
        for failure in (None, FixtureRpcFailure(401, 16), FixtureRpcFailure(400, 3)):
            with self.subTest(failure=failure):
                self.trace_scenario()
                self.registry.request.side_effect = failure
                wrong = self.work / "wrong-registry-credentials"
                if wrong.exists():
                    for path in wrong.iterdir():
                        path.unlink()
                    wrong.rmdir()
                with self.assertRaises(runner.ScenarioFailure):
                    self.scenario.run()
                self.assertFalse((self.evidence / "security-outcomes.json").exists())

    def test_application_logs_cannot_expose_registry_passwords_or_private_keys(self) -> None:
        log = self.work / "application.log"
        for secret in (self.registry.credentials["consumer"][1], "-----BEGIN PRIVATE KEY-----"):
            with self.subTest(secret=secret):
                log.write_text(secret, encoding="utf-8")
                with self.assertRaises(runner.ScenarioFailure) as failure:
                    self.scenario.check_logs()
                self.assertNotIn(secret, str(failure.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
