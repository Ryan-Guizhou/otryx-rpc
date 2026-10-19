#!/usr/bin/env python3
"""Self-tests for the isolated RPC comparison evidence quality gate."""

from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import unittest

from validate_rpc_comparison import markdown, validate_pair, validate_result


def sample(framework="otryx"):
    return {
        "schema": "otryx.rpc.comparison.v1",
        "recorded_at": "2026-01-01T00:00:00Z",
        "evidence_class": "smoke",
        "run_id": "test-01",
        "git_sha": "0123456789abcdef",
        "framework": framework,
        "protocol": "tcp-v1" if framework == "otryx" else "dubbo-tcp",
        "serializer": "fory-native" if framework == "otryx" else "hessian2",
        "workload": "sync-byte-array-echo-closed-loop",
        "provider_host": "127.0.0.1",
        "provider_port": 19001 if framework == "otryx" else 19002,
        "concurrency": 16,
        "payload_bytes": 256,
        "warmup_seconds": 2,
        "measurement_seconds": 3,
        "attempts": 100,
        "success": 100,
        "errors": 0,
        "error_rate": 0.0,
        "throughput_qps": 33.33333,
        "qps_per_cpu_core": None,
        "qps_per_client_cpu_core": 50.0,
        "client_process_cpu_cores": 0.66,
        "client_gc_count_delta": 1,
        "client_gc_millis_delta": 2,
        "client_heap_start_bytes": 100000,
        "client_heap_end_bytes": 110000,
        "p50_us": 50.0,
        "p99_us": 100.0,
        "p999_us": 120.0,
        "latency_samples": 100,
        "allocation_bytes_per_op": None,
        "server_cpu_cores": None,
        "server_gc_millis_delta": None,
        "connection_count": None,
        "java_version": "21",
        "os_name": "Linux",
        "os_arch": "amd64",
        "errors_by_type": {},
    }


def environment():
    return {
        "schema": "otryx.rpc.comparison.environment.v1",
        "evidence_class": "smoke",
        "run_id": "test-01",
        "git_sha": "0123456789abcdef",
        "runner_id": "ci-smoke",
        "physical_host_fingerprint": None,
        "otryx_registry_backend": "etcd",
        "otryx_registry_namespace": "comparison-fixture",
        "registry_traffic_measurement": "not-measured",
    }


class ComparisonValidatorTest(unittest.TestCase):
    def test_accepts_complete_smoke_without_invented_allocations(self):
        otryx, dubbo = sample(), sample("dubbo")
        validate_pair(otryx, dubbo, environment())
        report = markdown(otryx, dubbo, environment())
        self.assertIn("NOT OFFICIAL PERFORMANCE EVIDENCE", report)
        self.assertIn("not measured", report)

    def test_rejects_inconsistent_scenarios(self):
        otryx, dubbo = sample(), sample("dubbo")
        dubbo["concurrency"] = 32
        with self.assertRaisesRegex(ValueError, "Scenario mismatch"):
            validate_pair(otryx, dubbo, environment())

    def test_rejects_hidden_allocation_omission(self):
        data = sample()
        data.pop("allocation_bytes_per_op")
        with self.assertRaisesRegex(ValueError, "unmeasured-metric"):
            validate_result(data, "otryx")

    def test_rejects_error_breakdown_drift(self):
        data = sample()
        data["attempts"] = 101
        data["errors"] = 1
        data["error_rate"] = 1 / 101
        with self.assertRaisesRegex(ValueError, "error breakdown"):
            validate_result(data, "otryx")

    def test_rejects_unproven_controlled_claim(self):
        otryx, dubbo, env = sample(), sample("dubbo"), environment()
        otryx["evidence_class"] = dubbo["evidence_class"] = "controlled"
        env["evidence_class"] = "controlled"
        env["physical_host_fingerprint"] = "0123"
        with self.assertRaisesRegex(ValueError, "single controlled pair"):
            validate_pair(otryx, dubbo, env)

    def test_rejects_missing_or_nonapplication_registry_provenance(self):
        for field, value in (("otryx_registry_backend", None), ("otryx_registry_backend", "memory"),
                             ("otryx_registry_namespace", None), ("otryx_registry_namespace", "wrong/scope")):
            with self.subTest(field=field, value=value):
                env = environment()
                env[field] = value
                with self.assertRaisesRegex(ValueError, "OTRYX comparison requires"):
                    validate_pair(sample(), sample("dubbo"), env)

    def test_smoke_cannot_claim_backend_traffic_was_measured(self):
        for marker in (None, 0, "measured"):
            with self.subTest(marker=marker):
                env = environment()
                env["registry_traffic_measurement"] = marker
                with self.assertRaisesRegex(ValueError, "not-measured"):
                    validate_pair(sample(), sample("dubbo"), env)


class ComparisonScriptFixture:
    """Supply an isolated shell checkout and HTTP health fixture without Maven or a JVM."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.runner = scripts / "run_rpc_comparison_smoke.sh"
        shutil.copyfile(Path(__file__).with_name(self.runner.name), self.runner)
        binaries = self.root / "bin"
        binaries.mkdir()
        git = binaries / "git"
        git.write_text("#!/bin/sh\nprintf '0123456789abcdef0123456789abcdef01234567\\n'\n",
                       encoding="utf-8")
        git.chmod(0o755)
        maven = binaries / "mvn"
        maven.write_text("#!" + sys.executable + "\n"
                         "import json, os, pathlib, sys\n"
                         "pathlib.Path(os.environ['BUILD_RECORD']).write_text(json.dumps({\n"
                         " 'args': sys.argv[1:],\n"
                         " 'endpoint': os.environ['RPC_COMPARISON_REGISTRY_ENDPOINT'],\n"
                         " 'namespace': os.environ['RPC_COMPARISON_REGISTRY_NAMESPACE']}))\n"
                         "sys.exit(37)\n", encoding="utf-8")
        maven.chmod(0o755)
        self.record = self.root / "build.json"
        self.environment = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                                BUILD_RECORD=str(self.record),
                                RPC_COMPARISON_OUTPUT_DIR=str(self.root / "output"),
                                RPC_COMPARISON_REGISTRY_NAMESPACE="comparison-fixture")
        self.requests = []
        self.health_body = b'{"health":"true"}'
        self.health_status = 200
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                fixture.requests.append(self.path)
                self.send_response(fixture.health_status)
                self.end_headers()
                self.wfile.write(fixture.health_body)

            def log_message(self, *_arguments):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(lambda: worker.join(timeout=2))
        self.addCleanup(self.server.shutdown)
        self.endpoint = f"http://127.0.0.1:{self.server.server_port}"
        self.environment["RPC_COMPARISON_REGISTRY_ENDPOINT"] = self.endpoint

    def run_script(self, **overrides):
        return subprocess.run(["bash", str(self.runner)],
                              env=dict(self.environment, **overrides), cwd=self.root,
                              text=True, capture_output=True, timeout=10, check=False)


class ComparisonEtcdPreflightTest(ComparisonScriptFixture, unittest.TestCase):
    """Exercise the real shell preflight with a local HTTP fixture, never RPC evidence."""

    def test_healthy_shared_etcd_is_checked_before_build_and_passed_to_children(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertEqual(self.requests, ["/health"])
        record = json.loads(self.record.read_text(encoding="utf-8"))
        self.assertEqual(record["endpoint"], self.endpoint)
        self.assertEqual(record["namespace"], "comparison-fixture")
        self.assertIn("otryx-registry/otryx-registry-etcd", " ".join(record["args"]))

    def test_invalid_endpoint_or_namespace_never_reaches_server_or_build(self):
        for override in (
                {"RPC_COMPARISON_REGISTRY_ENDPOINT": "memory://local"},
                {"RPC_COMPARISON_REGISTRY_ENDPOINT": self.endpoint + "/v3"},
                {"RPC_COMPARISON_REGISTRY_ENDPOINT": "http://name:secret@127.0.0.1:2379"},
                {"RPC_COMPARISON_REGISTRY_NAMESPACE": "wrong/namespace"},
                {"RPC_COMPARISON_REGISTRY_NAMESPACE": "n" * 129}):
            with self.subTest(override=tuple(override)):
                result = self.run_script(**override)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.record.exists())
                self.assertEqual(self.requests, [])
                self.assertNotIn("name:secret", result.stdout + result.stderr)

    def test_unhealthy_or_invalid_health_response_never_starts_build(self):
        for status, body in ((200, b'{"health":"false"}'), (200, b"not-json"),
                             (503, b'{"health":"true"}')):
            with self.subTest(status=status, body=body):
                self.health_status, self.health_body = status, body
                result = self.run_script()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Shared Etcd readiness failed:", result.stderr)
                self.assertFalse(self.record.exists())

    def test_unreachable_etcd_never_starts_build(self):
        self.server.shutdown()
        self.server.server_close()
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Shared Etcd readiness failed:", result.stderr)
        self.assertFalse(self.record.exists())


class ComparisonProcessBoundaryTest(ComparisonScriptFixture, unittest.TestCase):
    """Run the actual shell with owned signal-handling processes and the real validator."""

    def setUp(self):
        super().setUp()
        binaries = self.root / "bin"
        (binaries / "mvn").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        for framework in ("otryx", "dubbo"):
            target = self.root / "benchmarks" / "rpc-comparison" / framework / "target"
            target.mkdir(parents=True)
            (target / f"{framework}-comparison.jar").write_bytes(b"explicit process fixture")
        shutil.copyfile(Path(__file__).with_name("validate_rpc_comparison.py"),
                        self.root / "scripts" / "validate_rpc_comparison.py")
        samples = self.root / "samples.json"
        samples.write_text(json.dumps({framework: sample(framework) for framework in ("otryx", "dubbo")}),
                           encoding="utf-8")
        self.events_path = self.root / "processes.jsonl"
        self.addCleanup(self.kill_fixture_children)
        self.environment.update({
            "FIXTURE_EVENTS": str(self.events_path), "FIXTURE_SAMPLES": str(samples),
            "RPC_COMPARISON_WARMUP_SECONDS": "1", "RPC_COMPARISON_DURATION_SECONDS": "1",
            "RPC_COMPARISON_STOP_SECONDS": "1", "RPC_COMPARISON_CLIENT_OVERHEAD_SECONDS": "1",
        })
        java = binaries / "java"
        java.write_text("#!" + sys.executable + "\n" + textwrap.dedent('''\
            import json
            import os
            from pathlib import Path
            import signal
            import sys
            import time

            args = sys.argv[1:]
            if args == ["-version"]:
                print("Explicit fixture Java 21; no JVM is executed")
                sys.exit(0)
            framework = Path(args[1]).parents[1].name
            role = args[2]
            with Path(os.environ["FIXTURE_EVENTS"]).open("a", encoding="utf-8") as events:
                events.write(json.dumps({"framework": framework, "role": role, "pid": os.getpid(),
                                         "started": time.monotonic(),
                                         "stopSetting": os.getenv("RPC_COMPARISON_STOP_SECONDS"),
                                         "overheadSetting": os.getenv("RPC_COMPARISON_CLIENT_OVERHEAD_SECONDS")})
                             + "\\n")
            if role == "provider":
                if os.getenv("FIXTURE_PROVIDER_IGNORE_TERM") == framework:
                    signal.signal(signal.SIGTERM, signal.SIG_IGN)
                print("READY framework=" + framework + " explicit-process-fixture", flush=True)
                signal.pause()
                sys.exit(0)
            mode = os.getenv("FIXTURE_CLIENT_MODE", "success") if framework == "otryx" else "success"
            if mode == "ignore-term":
                signal.signal(signal.SIGTERM, signal.SIG_IGN)
            if mode in ("wait", "ignore-term"):
                signal.pause()
                sys.exit(0)
            if mode == "failure":
                sys.exit(42)
            data = json.loads(Path(os.environ["FIXTURE_SAMPLES"]).read_text(encoding="utf-8"))[framework]
            data.update({"git_sha": os.environ["RPC_COMPARISON_GIT_SHA"],
                         "run_id": os.environ["RPC_COMPARISON_RUN_ID"],
                         "provider_host": args[3], "provider_port": int(args[4]),
                         "concurrency": int(args[5]), "warmup_seconds": int(args[6]),
                         "measurement_seconds": int(args[7]), "payload_bytes": int(args[8])})
            data["throughput_qps"] = data["success"] / data["measurement_seconds"]
            if framework == "dubbo" and os.getenv("FIXTURE_INVALID_EVIDENCE") == "true":
                data["concurrency"] += 1
            Path(args[9]).write_text(json.dumps(data), encoding="utf-8")
            '''), encoding="utf-8")
        java.chmod(0o755)

    def events(self):
        if not self.events_path.exists():
            return []
        return [json.loads(line) for line in self.events_path.read_text(encoding="utf-8").splitlines()]

    def kill_fixture_children(self):
        for event in self.events():
            try:
                os.kill(event["pid"], signal.SIGKILL)
            except ProcessLookupError:
                pass

    def start_script(self, **overrides):
        return subprocess.Popen(["bash", str(self.runner)],
                                env=dict(self.environment, **overrides), cwd=self.root,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                start_new_session=True)

    def complete_script(self, process):
        try:
            stdout, stderr = process.communicate(timeout=12)
        except subprocess.TimeoutExpired:
            # Releasing the intentionally stuck child lets the old shell reap it.
            self.kill_fixture_children()
            try:
                stdout, stderr = process.communicate(timeout=4)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                stdout, stderr = process.communicate(timeout=4)
            self.fail("Comparison shell exceeded its process budget; owned child was still waiting.\n"
                      + stdout + stderr)
        return subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)

    def run_script(self, **overrides):
        return self.complete_script(self.start_script(**overrides))

    def assert_reaped(self):
        for event in self.events():
            with self.assertRaises(ProcessLookupError, msg=f"Owned process was not reaped: {event}"):
                os.kill(event["pid"], 0)

    def assert_no_comparison_success(self, result):
        self.assertFalse((self.root / "output" / "report.md").exists())
        self.assertNotIn("Completed independent-JVM smoke comparison", result.stdout)

    def test_term_ignoring_provider_is_killed_and_both_frameworks_are_validated(self):
        result = self.run_script(FIXTURE_PROVIDER_IGNORE_TERM="otryx")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("forcing termination", result.stderr)
        self.assertEqual([(event["framework"], event["role"]) for event in self.events()],
                         [("otryx", "provider"), ("otryx", "client"),
                          ("dubbo", "provider"), ("dubbo", "client")])
        self.assertTrue((self.root / "output" / "report.md").is_file())
        self.assertIn("Completed independent-JVM smoke comparison", result.stdout)
        for event in self.events():
            self.assertIsNone(event["stopSetting"])
            self.assertIsNone(event["overheadSetting"])
        self.assert_reaped()

    def test_client_timeout_terminates_a_cooperative_process_and_preserves_timeout(self):
        result = self.run_script(FIXTURE_CLIENT_MODE="wait")
        self.assertEqual(result.returncode, 124, result.stdout + result.stderr)
        self.assertIn("client exceeded its overall budget", result.stderr)
        self.assertEqual([event["framework"] for event in self.events()], ["otryx", "otryx"])
        self.assert_no_comparison_success(result)
        self.assert_reaped()

    def test_client_timeout_kills_term_ignoring_processes_and_reaps_every_owned_pid(self):
        result = self.run_script(FIXTURE_CLIENT_MODE="ignore-term", FIXTURE_PROVIDER_IGNORE_TERM="otryx")
        self.assertEqual(result.returncode, 124, result.stdout + result.stderr)
        self.assertIn("client exceeded its overall budget", result.stderr)
        self.assertIn("forcing termination", result.stderr)
        client = next(event for event in self.events() if event["role"] == "client")
        self.assertGreaterEqual(time.monotonic() - client["started"], 2.5,
                                "Budget must include warmup, measurement and startup/close overhead")
        self.assert_no_comparison_success(result)
        self.assert_reaped()

    def test_business_failure_survives_provider_forced_cleanup(self):
        result = self.run_script(FIXTURE_CLIENT_MODE="failure", FIXTURE_PROVIDER_IGNORE_TERM="otryx")
        self.assertEqual(result.returncode, 42, result.stdout + result.stderr)
        self.assertEqual([event["framework"] for event in self.events()], ["otryx", "otryx"])
        self.assert_no_comparison_success(result)
        self.assert_reaped()

    def test_validator_rejection_remains_failure_after_both_frameworks_finish(self):
        result = self.run_script(FIXTURE_INVALID_EVIDENCE="true")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("Scenario mismatch", result.stderr)
        self.assertEqual(len(self.events()), 4)
        self.assert_no_comparison_success(result)
        self.assert_reaped()

    def test_invalid_process_budgets_fail_before_health_build_or_processes(self):
        for name, values in (("RPC_COMPARISON_STOP_SECONDS", ("0", "36", "-1", "bad")),
                             ("RPC_COMPARISON_CLIENT_OVERHEAD_SECONDS", ("0", "121", "-1", "bad"))):
            for value in values:
                with self.subTest(name=name, value=value):
                    result = self.run_script(**{name: value})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(name, result.stderr)
                    self.assertEqual(self.requests, [])
                    self.assertEqual(self.events(), [])

    def test_term_signal_preserves_exit_status_and_cleans_both_active_processes(self):
        process = self.start_script(FIXTURE_CLIENT_MODE="ignore-term", FIXTURE_PROVIDER_IGNORE_TERM="otryx")
        try:
            deadline = time.monotonic() + 5
            while not any(event["role"] == "client" for event in self.events()):
                if time.monotonic() >= deadline or process.poll() is not None:
                    self.fail("Client fixture did not start before signal test")
                time.sleep(0.05)
            process.send_signal(signal.SIGTERM)
            result = self.complete_script(process)
            self.assertEqual(result.returncode, 143, result.stdout + result.stderr)
            self.assert_no_comparison_success(result)
            self.assert_reaped()
        finally:
            self.kill_fixture_children()
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=4)


if __name__ == "__main__":
    unittest.main()
