#!/usr/bin/env python3
"""Verify fifteen RPC security scenarios with owned JVMs and a real scoped Etcd.

Evidence consists only of validated application snapshots and fixed scenario
outcomes. RPC/Registry PKI and secret configuration trees remain in private
working directories and are removed after owned processes have stopped.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time

from assert_quickstart_snapshot import assert_snapshot
from etcd_security_fixture import EtcdSecurityFixture, FixtureFailure, FixtureRpcFailure, encoded, private_write
from owned_process_group import OwnedProcess, OwnedProcessFailure, stop_owned as stop_process_group

ROOT = Path(__file__).resolve().parent.parent
PROVIDER_APP = "otryx-quickstart-mtls-provider"
CONSUMER_APP = "otryx-quickstart-mtls-consumer"
NAMESPACE = "otryx-quickstart-security"
RPC_PORT = 19143
READY_SECONDS = 45
STOP_SECONDS = 35
SNAPSHOT_SECONDS = 120
PROCESS_CONTROLS = ("OTRYX_RPC_E2E_RESTART_NACOS", "OTRYX_RPC_E2E_MAX_STALENESS")
GREETING_MARKER = "RPC quickstart completed successfully: Hello, OTRYX RPC!"
AUTHORIZATION_MARKER = "RPC authorization denial verified."
AUTHENTICATION_MARKER = "RPC authentication failure verified: reason="


class ScenarioFailure(RuntimeError):
    """Fixed scenario failure; never contains raw application output or secrets."""


def isolated_environment(environment: dict[str, str]) -> dict[str, str]:
    result = dict(environment)
    for name in PROCESS_CONTROLS:
        result.pop(name, None)
    return result


def expected_marker(reason: str | None, authorization_denied: bool) -> str:
    if reason is not None and authorization_denied:
        raise ScenarioFailure("Authentication and authorization outcomes are mutually exclusive")
    if reason is not None:
        if reason not in {"CERTIFICATE_REJECTED", "IDENTITY_UNBOUND", "APPLICATION_MISMATCH",
                          "NAMESPACE_MISMATCH", "IDENTITY_EXPIRED", "SECURITY_CONTEXT_CHANGED"}:
            raise ScenarioFailure("Unsupported expected authentication reason")
        return AUTHENTICATION_MARKER + reason
    return AUTHORIZATION_MARKER if authorization_denied else GREETING_MARKER


def utc_value(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def executable_jar(role: str) -> Path:
    candidates = list((ROOT / "otryx-quickstart" / ("otryx-quickstart-" + role) / "target").glob("*-exec.jar"))
    if len(candidates) != 1:
        raise ScenarioFailure("Exactly one executable JAR per Quickstart application is required")
    return candidates[0]


class SecurityScenario:
    def __init__(self, registry: EtcdSecurityFixture, evidence: Path, diagnostics: Path | None = None):
        self.registry = registry
        self.evidence = evidence
        self.diagnostics = diagnostics
        self.provider_jar = executable_jar("provider")
        self.consumer_jar = executable_jar("consumer")
        self.work = Path(tempfile.mkdtemp(prefix="otryx-quickstart-security-"))
        self.work.chmod(0o700)
        self.pki = self.work / "rpc-pki"
        self.pki.mkdir(mode=0o700)
        self.processes = []
        self.provider = None
        self.valid_until = utc_value(datetime.now(timezone.utc) + timedelta(hours=1))
        self.expired_at = utc_value(datetime.now(timezone.utc) - timedelta(minutes=1))
        self.outcomes = []
        self.environment = isolated_environment(os.environ) | registry.environment()
        self.fingerprints = {}
        self.stage = "prepare"

    def close(self, failed: bool) -> None:
        cleanup_failed = False
        for process in reversed(self.processes.copy()):
            try:
                self.stop(process)
            except (OwnedProcessFailure, OSError, subprocess.SubprocessError):
                cleanup_failed = True
        if cleanup_failed:
            raise OwnedProcessFailure("Owned application cleanup failed; private working files were retained") from None
        if failed and self.diagnostics is not None:
            self.diagnostics.mkdir(parents=True, mode=0o700, exist_ok=True)
            self.diagnostics.chmod(0o700)
            for path in self.work.glob("*.log"):
                destination = self.diagnostics / path.name
                shutil.copyfile(path, destination)
                destination.chmod(0o600)
        shutil.rmtree(self.work)

    def command(self, arguments: list[str], log: Path) -> None:
        with log.open("ab") as output:
            result = subprocess.run(arguments, stdout=output, stderr=subprocess.STDOUT, timeout=30)
        if result.returncode:
            raise ScenarioFailure("Isolated RPC certificate generation failed")

    def certificates(self) -> None:
        log = self.work / "certificate-generation.log"
        for ca in ("ca", "wrong-ca"):
            self.command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes", "-days", "2",
                          "-subj", "/CN=OTRYX RPC Test " + ca, "-keyout", str(self.pki / (ca + ".key")),
                          "-out", str(self.pki / (ca + ".crt"))], log)
        for serial, (name, usage) in enumerate((("provider", "serverAuth"), ("consumer", "clientAuth"),
                                               ("rotated", "clientAuth"), ("expired", "clientAuth")), 1):
            self.command(["openssl", "req", "-new", "-newkey", "rsa:2048", "-sha256", "-nodes",
                          "-subj", "/CN=otryx-quickstart-" + name, "-keyout", str(self.pki / (name + ".key")),
                          "-out", str(self.pki / (name + ".csr"))], log)
            extension = self.pki / (name + ".ext")
            private_write(extension, "basicConstraints=critical,CA:FALSE\n"
                          "keyUsage=critical,digitalSignature,keyEncipherment\n"
                          f"extendedKeyUsage={usage}\nsubjectAltName=IP:127.0.0.1,DNS:localhost\n")
            if name == "expired":
                private_write(self.pki / "index.txt", "")
                private_write(self.pki / "serial", "1000\n")
                issued = self.pki / "issued"
                issued.mkdir(mode=0o700)
                config = self.pki / "expired.cnf"
                private_write(config, "[ca]\ndefault_ca=signer\n[signer]\n"
                              f"database={self.pki / 'index.txt'}\nserial={self.pki / 'serial'}\n"
                              f"new_certs_dir={issued}\ndefault_md=sha256\ndefault_days=2\n"
                              "policy=subject_policy\n[subject_policy]\ncommonName=supplied\n")
                self.command(["openssl", "ca", "-batch", "-notext", "-config", str(config),
                              "-cert", str(self.pki / "ca.crt"), "-keyfile", str(self.pki / "ca.key"),
                              "-in", str(self.pki / (name + ".csr")), "-extfile", str(extension),
                              "-startdate", "20250101000000Z", "-enddate", "20260101000000Z",
                              "-out", str(self.pki / (name + ".crt"))], log)
            else:
                self.command(["openssl", "x509", "-req", "-sha256", "-days", "2",
                              "-in", str(self.pki / (name + ".csr")), "-CA", str(self.pki / "ca.crt"),
                              "-CAkey", str(self.pki / "ca.key"), "-set_serial", str(serial),
                              "-extfile", str(extension), "-out", str(self.pki / (name + ".crt"))], log)
            result = subprocess.run(["openssl", "x509", "-in", str(self.pki / (name + ".crt")),
                                     "-outform", "DER"], capture_output=True, timeout=10, check=True)
            self.fingerprints[name] = hashlib.sha256(result.stdout).hexdigest()

    def application_environment(self, role: str, certificate: str, overrides: dict | None = None) -> dict:
        environment = self.environment | {
            "OTRYX_QUICKSTART_TLS_CERT": str(self.pki / (certificate + ".crt")),
            "OTRYX_QUICKSTART_TLS_KEY": str(self.pki / (certificate + ".key")),
            "OTRYX_QUICKSTART_TLS_CA": str(self.pki / "ca.crt"),
            "OTRYX_QUICKSTART_REGISTRY_ENDPOINT": self.registry.endpoint,
            "OTRYX_QUICKSTART_REGISTRY_CA": str(self.registry.pki / "ca.crt"),
            "OTRYX_QUICKSTART_REGISTRY_CREDENTIALS_DIR": str(self.registry.directory / "spring-credentials" / role),
            "OTRYX_QUICKSTART_NAMESPACE": self.registry.namespace,
            "OTRYX_QUICKSTART_PROVIDER_APPLICATION": PROVIDER_APP,
            "OTRYX_QUICKSTART_CONSUMER_APPLICATION": CONSUMER_APP,
            "OTRYX_QUICKSTART_PROVIDER_SHA256": self.fingerprints["provider"],
            "OTRYX_QUICKSTART_CONSUMER_SHA256": self.fingerprints["consumer"],
            "OTRYX_QUICKSTART_CONSUMER_ROTATED_SHA256": self.fingerprints["rotated"],
            "OTRYX_QUICKSTART_BINDING_VALID_UNTIL": self.valid_until,
            "OTRYX_QUICKSTART_POLICY_VALID_UNTIL": self.valid_until,
        }
        return environment | (overrides or {})

    def start(self, role: str, stage: str, certificate: str, arguments: list[str] | None = None,
              overrides: dict | None = None) -> tuple[OwnedProcess, Path]:
        self.stage = stage
        app = PROVIDER_APP if role == "provider" else CONSUMER_APP
        jar = self.provider_jar if role == "provider" else self.consumer_jar
        command = ["java", "-jar", str(jar), "--spring.profiles.active=mtls",
                   "--spring.application.name=" + app, "--otryx.rpc.registry.application-name=" + app,
                   "--otryx.rpc.registry.instance-id=" + role + "-main"]
        if role == "provider":
            command += ["--otryx.rpc.client.enabled=false", "--otryx.rpc.server.enabled=true",
                        "--otryx.rpc.server.host=127.0.0.1", "--otryx.rpc.server.port=" + str(RPC_PORT),
                        "--otryx.rpc.server.advertised-port=" + str(RPC_PORT),
                        "--otryx.rpc.quickstart.role=leaf", "--otryx.rpc.quickstart.details-enabled=true"]
        else:
            command += ["--otryx.rpc.server.enabled=false", "--otryx.rpc.quickstart.use-relay=false",
                        "--otryx.rpc.quickstart.repeat-interval-millis=0"]
        # Spring joins repeated property values; resolve scenario overrides before launching.
        options = {argument.partition("=")[0]: argument for argument in command[3:] + (arguments or [])}
        command = command[:3] + list(options.values())
        log = self.work / (stage + ".log")
        with log.open("wb") as output:
            process = OwnedProcess(command, env=self.application_environment(role, certificate, overrides),
                                   stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        self.processes.append(process)
        return process, log

    def stop(self, process: OwnedProcess) -> None:
        stop_process_group(process, STOP_SECONDS, 5)
        if process in self.processes:
            self.processes.remove(process)

    def wait_marker(self, process: OwnedProcess, log: Path, marker: str, *, exact: bool = True) -> None:
        deadline = time.monotonic() + READY_SECONDS
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise ScenarioFailure("Application exited before its precise expected outcome")
            content = log.read_text(encoding="utf-8", errors="replace")
            matched = any(line.endswith(marker) for line in content.splitlines()) if exact else marker in content
            if matched:
                return
            time.sleep(0.05)
        raise ScenarioFailure("Application exceeded its readiness or RPC outcome budget")

    def snapshot(self, stage: str) -> None:
        started = time.monotonic()
        deadline = started + SNAPSHOT_SECONDS
        attempt = 0
        while time.monotonic() < deadline:
            attempt += 1
            output = self.evidence / f"{stage}-attempt-{attempt}.json"
            command = ["bash", str(ROOT / "scripts/observe_quickstart_registry.sh"), str(self.provider_jar),
                       "etcd", self.registry.endpoint, self.registry.namespace, "-", "-", str(output),
                       PROVIDER_APP, CONSUMER_APP]
            with (self.work / "snapshot.log").open("wb") as log:
                observer = OwnedProcess(command, env=self.environment, stdout=log,
                                        stderr=subprocess.STDOUT, start_new_session=True)
                self.processes.append(observer)
                try:
                    code = observer.wait(timeout=min(35, max(1, deadline - time.monotonic())))
                except subprocess.TimeoutExpired:
                    code = None
                finally:
                    self.stop(observer)
            if code == 0:
                try:
                    assert_snapshot("mtls", stage, json.loads(output.read_text()), namespace=self.registry.namespace)
                    elapsed_millis = int((time.monotonic() - started) * 1000)
                    message = (f"Registry snapshot verified: backend=etcd scenario=mtls stage={stage} "
                               f"convergenceMillis={elapsed_millis} file={output.name}")
                    with (self.evidence / "summary.log").open("a", encoding="utf-8") as summary:
                        summary.write(message + "\n")
                    print(message, flush=True)
                    return
                except (ValueError, TypeError, KeyError):
                    pass
            time.sleep(0.1)
        raise ScenarioFailure("The read-only observer did not confirm the expected application state")

    def record(self, stage: str, outcome: str) -> None:
        self.outcomes.append({"stage": stage, "outcome": outcome})
        with (self.evidence / "summary.log").open("a", encoding="utf-8") as output:
            output.write(f"Quickstart security PASS: stage={stage} outcome={outcome}\n")
        print(f"Quickstart security PASS: stage={stage} outcome={outcome}", flush=True)

    def start_provider(self, stage: str, expired_policy: bool = False) -> None:
        if self.provider is not None:
            self.stop(self.provider)
        self.provider, log = self.start("provider", stage, "provider", overrides={
            "OTRYX_QUICKSTART_POLICY_VALID_UNTIL": self.expired_at if expired_policy else self.valid_until})
        self.wait_marker(self.provider, log, "OTRYX RPC server started:", exact=False)
        if f"advertised=127.0.0.1:{RPC_PORT}" not in log.read_text(encoding="utf-8", errors="replace"):
            raise ScenarioFailure("Provider advertised an unexpected RPC endpoint")

    def client(self, stage: str, certificate: str = "consumer", *, reason: str | None = None,
               authorization_denied: bool = False, arguments: list[str] | None = None,
               overrides: dict | None = None) -> None:
        marker = expected_marker(reason, authorization_denied)
        args = ["--otryx.rpc.quickstart.expect-authorization-denied=" + str(authorization_denied).lower()]
        if reason:
            args.append("--otryx.rpc.quickstart.expect-authentication-failure=" + reason)
        process, log = self.start("consumer", stage, certificate, args + (arguments or []), overrides)
        try:
            self.wait_marker(process, log, marker)
            self.snapshot(stage)
            if process.poll() is not None:
                raise ScenarioFailure("Consumer exited before its verified outcome was recorded")
            self.record(stage, reason or ("AUTHORIZATION_DENIED" if authorization_denied else "GREETING_ALLOWED"))
        finally:
            self.stop(process)

    def startup_rejected(self, stage: str, marker: str, *, role: str = "consumer", certificate: str = "consumer",
                         arguments: list[str] | None = None, overrides: dict | None = None) -> None:
        process, log = self.start(role, stage, certificate, arguments, overrides)
        try:
            try:
                code = process.wait(timeout=READY_SECONDS)
            except subprocess.TimeoutExpired:
                raise ScenarioFailure("Invalid security configuration kept its application running") from None
            content = log.read_text(encoding="utf-8", errors="replace")
            if code == 0 or marker not in content or "OTRYX RPC server started:" in content:
                raise ScenarioFailure("Startup did not reject the exact expected security failure")
            if GREETING_MARKER in content or AUTHORIZATION_MARKER in content or AUTHENTICATION_MARKER in content:
                raise ScenarioFailure("A rejected startup emitted an RPC success marker")
            self.snapshot(stage)
            self.record(stage, "STARTUP_REJECTED")
        finally:
            self.stop(process)

    def run(self) -> None:
        self.certificates()
        self.start_provider("provider-initial")
        self.client("E01-allowed-initial")
        self.client("E02-rotated-consumer", "rotated")
        self.client("E03-unbound-provider", reason="IDENTITY_UNBOUND", overrides={
            "OTRYX_QUICKSTART_PROVIDER_SHA256": "f" * 64})
        self.startup_rejected("E04-local-application-mismatch", "RPC authentication failed: APPLICATION_MISMATCH",
                              overrides={"OTRYX_QUICKSTART_CONSUMER_APPLICATION": "another-consumer"})
        self.client("E05-remote-application-mismatch", reason="APPLICATION_MISMATCH", overrides={
            "OTRYX_QUICKSTART_PROVIDER_APPLICATION": "another-provider"})
        self.client("E06-remote-namespace-mismatch", reason="NAMESPACE_MISMATCH", overrides={
            "OTRYX_QUICKSTART_PROVIDER_BINDING_NAMESPACE": "another-namespace"})
        self.client("E07-wrong-rpc-ca", reason="CERTIFICATE_REJECTED", overrides={
            "OTRYX_QUICKSTART_TLS_CA": str(self.pki / "wrong-ca.crt")})
        self.startup_rejected("E08-expired-local-binding", "RPC authentication failed: IDENTITY_EXPIRED", overrides={
            "OTRYX_QUICKSTART_BINDING_VALID_UNTIL": self.expired_at})
        self.startup_rejected("E09-expired-certificate", "Invalid TLS material", certificate="expired", overrides={
            "OTRYX_QUICKSTART_CONSUMER_SHA256": self.fingerprints["expired"]})
        self.start_provider("provider-expired-policy", expired_policy=True)
        self.client("E10-expired-policy", authorization_denied=True)
        self.start_provider("provider-restored-policy")
        self.client("E11-details-denied", authorization_denied=True,
                    arguments=["--otryx.rpc.quickstart.use-details=true"])
        for both in (False, True):
            self.startup_rejected("E12-missing-authorizer-" + ("both" if both else "provider"),
                                  "NETWORK_MTLS requires an explicit RpcAuthorizer bean", role="provider",
                                  certificate="provider", arguments=[
                                      "--otryx.rpc.quickstart.mtls.authorization-enabled=false",
                                      "--otryx.rpc.client.enabled=" + str(both).lower(),
                                      "--otryx.rpc.registry.instance-id=missing-authorizer"])
        self.client("E13-allowed-repeat")
        self.startup_rejected("E14-wrong-registry-ca", "Registry security rejected: TLS_REJECTED", overrides={
            "OTRYX_QUICKSTART_REGISTRY_CA": str(self.registry.pki / "wrong-ca.crt")})
        wrong = self.work / "wrong-registry-credentials"
        wrong.mkdir(mode=0o700)
        prefix = "otryx.rpc.registry.security.credentials."
        private_write(wrong / (prefix + "username"), self.registry.credentials["consumer"][0])
        private_write(wrong / (prefix + "password"), "incorrect-fixture-password")
        self.startup_rejected("E14-wrong-registry-credentials", "Registry security rejected: AUTHENTICATION_REJECTED",
                              overrides={"OTRYX_QUICKSTART_REGISTRY_CREDENTIALS_DIR": str(wrong)})
        self.stage = "E15-observer-read-only"
        token = self.registry.authenticate("observer")
        key = self.registry.member_key(PROVIDER_APP, "provider-main")
        try:
            self.registry.request("/v3/kv/put", {"key": encoded(key), "value": encoded("forbidden")}, token)
        except FixtureRpcFailure as error:
            if error.code != 7:
                raise ScenarioFailure("Observer write failed for an unexpected reason") from None
        else:
            raise ScenarioFailure("Read-only observer was permitted to modify provider membership")
        self.snapshot(self.stage)
        self.record(self.stage, "READ_ALLOWED_WRITE_FORBIDDEN")
        if self.provider.poll() is not None:
            raise ScenarioFailure("The Provider did not survive the complete negative-case sequence")
        self.check_logs()
        private_write(self.evidence / "security-outcomes.json", json.dumps({
            "scenario": "quickstart-network-mtls", "registry": "etcd-tls-user-password",
            "namespace": self.registry.namespace, "outcomes": self.outcomes}, indent=2) + "\n")

    def check_logs(self) -> None:
        secrets = [password for _, password in self.registry.credentials.values()]
        for log in self.work.glob("*.log"):
            content = log.read_text(encoding="utf-8", errors="replace")
            if any(secret in content for secret in secrets) or "-----BEGIN PRIVATE KEY-----" in content:
                raise ScenarioFailure("Application diagnostics exposed private security material")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--etcd-binary", default=os.environ.get("ETCD_BINARY", shutil.which("etcd")))
    parser.add_argument("--diagnostics", type=Path)
    args = parser.parse_args()
    if not args.etcd_binary or any(shutil.which(name) is None for name in ("java", "openssl", "bash", "timeout")):
        parser.error("Etcd 3.5.21, Java, OpenSSL, Bash and timeout are required")
    try:
        executable_jar("provider")
        executable_jar("consumer")
    except ScenarioFailure as error:
        print(str(error), file=sys.stderr)
        return 1
    root = Path(os.environ.get("OTRYX_QUICKSTART_EVIDENCE_DIR", ROOT / "target/quickstart-e2e"))
    root.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix="mtls.", dir=root))
    mask = os.umask(0o077)
    scenario = None
    failed = True
    try:
        with EtcdSecurityFixture(args.etcd_binary, NAMESPACE, PROVIDER_APP, CONSUMER_APP) as registry:
            private_write(evidence / "registry-fixture.json", json.dumps(registry.self_test(), indent=2) + "\n")
            scenario = SecurityScenario(registry, evidence, args.diagnostics)
            try:
                scenario.run()
                failed = False
            finally:
                scenario.close(failed)
        print("Quickstart NETWORK_MTLS security scenarios: PASS", flush=True)
        return 0
    except (ScenarioFailure, FixtureFailure, OwnedProcessFailure, OSError, subprocess.SubprocessError) as error:
        stage = scenario.stage if scenario is not None else "registry-fixture"
        print(f"Quickstart security scenario failed: stage={stage} type={type(error).__name__}", file=sys.stderr)
        return 1
    finally:
        os.umask(mask)


if __name__ == "__main__":
    def interrupted(_number, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
