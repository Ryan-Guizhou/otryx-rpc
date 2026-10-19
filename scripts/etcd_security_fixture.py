#!/usr/bin/env python3
"""Own an isolated Etcd 3.5.21 TLS/RBAC fixture and never put credentials in argv.

Only test data is written. The listener binds loopback, the server leaf contains
DNS:localhost only, and all application roles are restricted to their actual V2
key ranges. Registry PKI is independent of RPC certificates.

API reference: https://etcd.io/docs/v3.5/dev-guide/api_grpc_gateway/
RBAC reference: https://etcd.io/docs/v3.5/op-guide/authentication/rbac/
"""
from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from owned_process_group import OwnedProcess, OwnedProcessFailure, stop_owned as stop_process_group

VERSION = "3.5.21"
REQUEST_TIMEOUT = 3
START_TIMEOUT = 25
STOP_TIMEOUT = 10
MAX_RESPONSE_BYTES = 1024 * 1024
ROLE_NAMES = ("admin", "provider", "consumer", "observer")
VALID_NAME = re.compile(r"[A-Za-z0-9._-]{1,128}\Z")


class FixtureFailure(RuntimeError):
    """A bounded diagnostic that never includes a request, token or response body."""


class FixtureRpcFailure(FixtureFailure):
    def __init__(self, status: int, code: int):
        super().__init__(f"Etcd fixture request rejected: http={status} grpc={code}")
        self.status = status
        self.code = code


def encoded(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def component(value: str) -> str:
    if not VALID_NAME.fullmatch(value):
        raise FixtureFailure("Invalid fixture application or namespace")
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=")


def prefix_end(value: str) -> str:
    data = bytearray(value.encode("utf-8"))
    for index in range(len(data) - 1, -1, -1):
        if data[index] < 255:
            data[index] += 1
            return bytes(data[:index + 1]).decode("utf-8")
    raise FixtureFailure("Fixture prefix has no finite upper bound")


def private_write(path: Path, value: str) -> None:
    with path.open("x", encoding="utf-8") as stream:
        stream.write(value)
    path.chmod(0o600)


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def stop_owned(process: OwnedProcess | None) -> None:
    stop_process_group(process, STOP_TIMEOUT, STOP_TIMEOUT)


class EtcdSecurityFixture:
    """Own one local server, private PKI, four users and their finite process lifetime."""

    def __init__(self, binary: str, namespace: str = "otryx-security-test",
                 provider: str = "security-provider", consumer: str = "security-consumer",
                 require_client_cert: bool = False):
        component(namespace)
        component(provider)
        component(consumer)
        self.binary = binary
        self.namespace = namespace
        self.provider = provider
        self.consumer = consumer
        self.require_client_cert = require_client_cert
        self.directory = None
        self.process = None
        self.log = None
        self.endpoint = ""
        self.context = None
        self.credentials = {}
        self.root = f"/otryx/v2/ns/{component(namespace)}/"

    def __enter__(self):
        try:
            version = subprocess.run([self.binary, "--version"], check=True, capture_output=True,
                                     text=True, timeout=REQUEST_TIMEOUT).stdout
            if not re.search(r"^etcd Version: " + re.escape(VERSION) + r"$", version, re.MULTILINE):
                raise FixtureFailure("The security fixture requires Etcd 3.5.21")
            # No automatic finalizer may remove credentials if an owned process
            # could not be stopped. Normal context exit removes the directory.
            self.directory = Path(tempfile.mkdtemp(prefix="otryx-registry-security-"))
            self.directory.chmod(0o700)
            self.pki = self.directory / "pki"
            self.pki.mkdir(mode=0o700)
            self.credential_dir = self.directory / "credentials"
            self.credential_dir.mkdir(mode=0o700)
            self._create_pki()
            client_port, peer_port = free_port(), free_port()
            while peer_port == client_port:
                peer_port = free_port()
            self.endpoint = f"https://localhost:{client_port}"
            self.wrong_host_endpoint = f"https://127.0.0.1:{client_port}"
            peer = f"http://127.0.0.1:{peer_port}"
            command = [self.binary, "--name=otryx-security-fixture", "--logger=zap", "--log-level=error",
                       "--data-dir=" + str(self.directory / "data"),
                       "--listen-client-urls=" + self.wrong_host_endpoint,
                       "--advertise-client-urls=" + self.endpoint,
                       "--listen-peer-urls=" + peer, "--initial-advertise-peer-urls=" + peer,
                       "--initial-cluster=otryx-security-fixture=" + peer,
                       "--cert-file=" + str(self.pki / "server.crt"),
                       "--key-file=" + str(self.pki / "server.key")]
            if self.require_client_cert:
                command += ["--client-cert-auth=true", "--trusted-ca-file=" + str(self.pki / "ca.crt")]
            self.context = ssl.create_default_context(cafile=self.pki / "ca.crt")
            if self.require_client_cert:
                # Etcd's HTTP gateway rejects a nonempty client CN when authentication is enabled.
                # SDK tests retain client.crt with its unmapped CN and use the native gRPC API.
                self.context.load_cert_chain(self.pki / "gateway-client.crt", self.pki / "gateway-client.key")
            self.log = (self.directory / "etcd.log").open("w")
            self.process = OwnedProcess(command, stdout=self.log, stderr=subprocess.STDOUT,
                                        start_new_session=True)
            self._await_ready()
            self._configure_roles()
            self._write_observer_config()
            self._write_spring_credentials()
            return self
        except BaseException as error:
            self.__exit__(type(error), error, error.__traceback__)
            raise

    def __exit__(self, _error_type, error, _traceback):
        try:
            stop_owned(self.process)
        finally:
            if self.log is not None:
                self.log.close()
        # A nested command/scenario can still own active processes. Keep its
        # Registry credentials as well as its own files until cleanup succeeds.
        if not isinstance(error, OwnedProcessFailure) and self.directory is not None and self.directory.exists():
            shutil.rmtree(self.directory)

    def _openssl(self, arguments: list[str]) -> None:
        with (self.directory / "pki.log").open("a") as log:
            result = subprocess.run(["openssl"] + arguments, stdout=log, stderr=subprocess.STDOUT, timeout=30)
        if result.returncode:
            raise FixtureFailure("Could not generate isolated Registry test certificates")

    def _create_pki(self) -> None:
        for name in ("ca", "wrong-ca"):
            self._openssl(["req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes", "-days", "2",
                           "-subj", "/CN=OTRYX Registry Fixture " + name,
                           "-keyout", str(self.pki / (name + ".key")), "-out", str(self.pki / (name + ".crt"))])
        certificates = [("server", "serverAuth,clientAuth"), ("client", "clientAuth")]
        if self.require_client_cert:
            certificates.append(("gateway-client", "clientAuth"))
        for number, (name, usage) in enumerate(certificates, 1):
            # The gateway's own gRPC connection reuses the server key pair. Keep both
            # gateway certificates free of CN-based backend identity; native SDK tests
            # still use the separate client leaf whose CN has no matching RBAC user.
            subject = "/O=OTRYX Registry Fixture" if name != "client" else "/CN=otryx-registry-test-client"
            self._openssl(["req", "-new", "-newkey", "rsa:2048", "-sha256", "-nodes", "-subj", subject,
                           "-keyout", str(self.pki / (name + ".key")), "-out", str(self.pki / (name + ".csr"))])
            private_write(self.pki / (name + ".ext"), "basicConstraints=critical,CA:FALSE\n"
                          "keyUsage=critical,digitalSignature,keyEncipherment\n"
                          f"extendedKeyUsage={usage}\nsubjectAltName=DNS:localhost\n")
            self._openssl(["x509", "-req", "-sha256", "-days", "2", "-in", str(self.pki / (name + ".csr")),
                           "-CA", str(self.pki / "ca.crt"), "-CAkey", str(self.pki / "ca.key"),
                           "-set_serial", str(number), "-extfile", str(self.pki / (name + ".ext")),
                           "-out", str(self.pki / (name + ".crt"))])
        for path in self.pki.iterdir():
            path.chmod(0o600)

    def _await_ready(self) -> None:
        deadline = time.monotonic() + START_TIMEOUT
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise FixtureFailure("Etcd TLS fixture exited before readiness")
            try:
                self.request("/v3/kv/range", {"key": encoded(self.root)})
                return
            except (URLError, OSError, FixtureRpcFailure):
                time.sleep(0.05)
        raise FixtureFailure("Etcd TLS fixture readiness budget expired")

    def request(self, path: str, payload: dict, token: str | None = None, *,
                endpoint: str | None = None, context: ssl.SSLContext | None = None) -> dict:
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = token
        request = Request((endpoint or self.endpoint) + path, data=json.dumps(payload).encode(), headers=headers)
        try:
            with urlopen(request, context=context or self.context, timeout=REQUEST_TIMEOUT) as response:
                body = response.read(MAX_RESPONSE_BYTES + 1)
                if len(body) > MAX_RESPONSE_BYTES:
                    raise FixtureFailure("Etcd fixture response exceeded its byte budget")
                return json.loads(body)
        except HTTPError as error:
            body = error.read(MAX_RESPONSE_BYTES + 1)
            try:
                code = int(json.loads(body).get("code", -1)) if len(body) <= MAX_RESPONSE_BYTES else -1
            except (ValueError, TypeError):
                code = -1
            raise FixtureRpcFailure(error.code, code) from None

    def authenticate(self, role: str, *, password: str | None = None) -> str:
        username, configured = self.credentials[role]
        result = self.request("/v3/auth/authenticate", {"name": username,
                              "password": configured if password is None else password})
        token = result.get("token")
        if not isinstance(token, str) or not token:
            raise FixtureFailure("Etcd fixture did not return an authentication token")
        return token

    def member_key(self, application: str, instance: str) -> str:
        return self.root + f"apps/{component(application)}/members/{component(instance)}"

    def owner_key(self, application: str, instance: str) -> str:
        return self.root + f"owners/{component(application)}/{component(instance)}"

    def manifest_prefix(self, application: str) -> str:
        return self.root + f"apps/{component(application)}/manifests/"

    def _grant(self, role: str, key: str, permission: str, prefix: bool = False) -> None:
        grant = {"permType": permission, "key": encoded(key)}
        if prefix:
            grant["range_end"] = encoded(prefix_end(key))
        self.request("/v3/auth/role/grant", {"name": role, "perm": grant})

    def _configure_roles(self) -> None:
        for role in ROLE_NAMES:
            username = "root" if role == "admin" else "otryx-" + role
            password = secrets.token_urlsafe(36)
            self.credentials[role] = username, password
            private_write(self.credential_dir / (role + ".username"), username + "\n")
            private_write(self.credential_dir / (role + ".password"), password + "\n")
            self.request("/v3/auth/user/add", {"name": username, "password": password})
            self.request("/v3/auth/role/add", {"name": username})
            if role != "admin":
                for prefix in (self.root + "apps/", self.root + "owners/"):
                    self._grant(username, prefix, "READ", True)
                if role == "provider":
                    self._grant(username, self.member_key(self.provider, "provider-main"), "WRITE")
                    self._grant(username, self.owner_key(self.provider, "provider-main"), "WRITE")
                    self._grant(username, self.manifest_prefix(self.provider), "WRITE", True)
                elif role == "consumer":
                    self._grant(username, self.member_key(self.consumer, "consumer-main"), "WRITE")
            self.request("/v3/auth/user/grant", {"user": username, "role": username})
        self.request("/v3/auth/enable", {})
        if not self.request("/v3/auth/status", {}, self.authenticate("admin")).get("enabled"):
            raise FixtureFailure("Etcd fixture authentication was not enabled")

    def _write_observer_config(self) -> None:
        security = {"trustCertificateCollection": str(self.pki / "ca.crt"),
                    "credentialKind": "USER_PASSWORD",
                    "usernameFile": str(self.credential_dir / "observer.username"),
                    "passwordFile": str(self.credential_dir / "observer.password")}
        if self.require_client_cert:
            security.update(clientCertificateChain=str(self.pki / "client.crt"),
                            clientPrivateKey=str(self.pki / "client.key"))
        private_write(self.directory / "observer.json", json.dumps({"security": security}) + "\n")

    def environment(self) -> dict[str, str]:
        return {"ETCD_TLS_TEST_ENDPOINT": self.endpoint,
                "ETCD_TLS_TEST_WRONG_HOST_ENDPOINT": self.wrong_host_endpoint,
                "ETCD_TLS_TEST_NAMESPACE": self.namespace,
                "ETCD_TLS_TEST_CA_FILE": str(self.pki / "ca.crt"),
                "ETCD_TLS_TEST_WRONG_CA_FILE": str(self.pki / "wrong-ca.crt"),
                "ETCD_TLS_TEST_CLIENT_CERT_FILE": str(self.pki / "client.crt"),
                "ETCD_TLS_TEST_CLIENT_KEY_FILE": str(self.pki / "client.key"),
                "ETCD_TLS_TEST_CREDENTIALS_DIR": str(self.credential_dir),
                "ETCD_TLS_TEST_SPRING_CREDENTIALS_DIR": str(self.directory / "spring-credentials"),
                "QUICKSTART_REGISTRY_SECURITY_FILE": str(self.directory / "observer.json")}

    def _write_spring_credentials(self) -> None:
        directory = self.directory / "spring-credentials"
        directory.mkdir(mode=0o700)
        for role in ("provider", "consumer"):
            target = directory / role
            target.mkdir(mode=0o700)
            username, password = self.credentials[role]
            prefix = "otryx.rpc.registry.security.credentials."
            private_write(target / (prefix + "username"), username)
            private_write(target / (prefix + "password"), password)

    def self_test(self) -> dict:
        """Exercise real allow/deny and revocation; leave a clean application key space."""
        outcomes = []

        def denied(name, operation, allowed_codes=(7,)):
            try:
                operation()
            except FixtureRpcFailure as error:
                if error.code not in allowed_codes:
                    raise FixtureFailure(f"Etcd fixture negative {name} returned unexpected http={error.status} "
                                         f"grpc={error.code}") from None
            else:
                raise FixtureFailure("Etcd fixture unexpectedly permitted a forbidden operation")
            outcomes.append(name)

        provider_key = self.member_key(self.provider, "provider-main")
        consumer_key = self.member_key(self.consumer, "consumer-main")
        owner_key = self.owner_key(self.provider, "provider-main")
        manifest_key = self.manifest_prefix(self.provider) + component("probe")
        tokens = {role: self.authenticate(role) for role in ROLE_NAMES}
        put = lambda key: {"key": encoded(key), "value": encoded("fixture-only")}
        # With a verified client certificate Etcd reports this missing backend identity as
        # permission denied; the TLS-only listener reports an empty user (INVALID_ARGUMENT).
        missing_credentials_code = 7 if self.require_client_cert else 3
        denied("missing-credentials", lambda: self.request("/v3/kv/range", {"key": encoded(provider_key)}),
               (missing_credentials_code,))
        denied("wrong-credentials", lambda: self.authenticate("observer", password="incorrect-fixture-password"), (3,))
        for name, context, endpoint in (
                ("wrong-ca", ssl.create_default_context(cafile=self.pki / "wrong-ca.crt"), self.endpoint),
                ("wrong-san", self.context, self.wrong_host_endpoint)):
            try:
                self.request("/v3/kv/range", {"key": encoded(provider_key)}, tokens["observer"],
                             context=context, endpoint=endpoint)
            except URLError as error:
                if not isinstance(error.reason, ssl.SSLCertVerificationError):
                    raise FixtureFailure("Etcd fixture TLS negative did not fail certificate verification") from None
            else:
                raise FixtureFailure("Etcd fixture unexpectedly accepted invalid server identity")
            outcomes.append(name)
        for role, key in (("provider", provider_key), ("consumer", consumer_key)):
            self.request("/v3/kv/put", put(key), tokens[role])
            if self.request("/v3/kv/range", {"key": encoded(key)}, tokens["observer"]).get("count") != "1":
                raise FixtureFailure("Observer did not read the expected authorized member")
            outcomes.append(role + "-write-observer-read")
        denied("observer-write", lambda: self.request("/v3/kv/put", put(provider_key), tokens["observer"]))
        denied("consumer-provider-write", lambda: self.request("/v3/kv/put", put(provider_key), tokens["consumer"]))
        for name, key in (("mapping-owner", owner_key), ("manifest", manifest_key)):
            self.request("/v3/kv/put", put(key), tokens["provider"])
            if self.request("/v3/kv/range", {"key": encoded(key)}, tokens["observer"]).get("count") != "1":
                raise FixtureFailure("Observer could not read authorized metadata")
            outcomes.append("provider-" + name + "-write-observer-read")
            denied("consumer-" + name + "-write", lambda: self.request("/v3/kv/put", put(key), tokens["consumer"]))
            denied("observer-" + name + "-write", lambda: self.request("/v3/kv/put", put(key), tokens["observer"]))
        denied("provider-other-instance-write", lambda: self.request("/v3/kv/put",
                put(self.member_key(self.provider, "other-instance")), tokens["provider"]))
        denied("consumer-other-instance-write", lambda: self.request("/v3/kv/put",
                put(self.member_key(self.consumer, "other-instance")), tokens["consumer"]))
        denied("cross-namespace-read", lambda: self.request("/v3/kv/range",
                {"key": encoded("/otryx/v2/ns/another/apps/value")}, tokens["observer"]))
        denied("cross-namespace-write", lambda: self.request("/v3/kv/put",
                put("/otryx/v2/ns/another/apps/value"), tokens["provider"]))
        username = self.credentials["observer"][0]
        self.request("/v3/auth/user/revoke", {"name": username, "role": username}, self.authenticate("admin"))
        denied("revoked-existing-token", lambda: self.request("/v3/kv/range",
                {"key": encoded(provider_key)}, tokens["observer"]), (7, 16))
        self.request("/v3/auth/user/grant", {"user": username, "role": username}, self.authenticate("admin"))
        for key in (provider_key, consumer_key, owner_key, manifest_key):
            self.request("/v3/kv/deleterange", {"key": encoded(key)}, self.authenticate("admin"))
        return {"fixture": "etcd-tls-rbac", "etcdVersion": VERSION,
                "clientCertificateRequired": self.require_client_cert, "passed": outcomes}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--etcd-binary", default=os.environ.get("ETCD_BINARY", shutil.which("etcd")))
    parser.add_argument("--namespace", default="otryx-security-test")
    parser.add_argument("--provider-application", default="security-provider")
    parser.add_argument("--consumer-application", default="security-consumer")
    parser.add_argument("--require-client-cert", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--command-timeout", type=int, default=600)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not args.etcd_binary or not shutil.which("openssl") or not 0 < args.command_timeout <= 1800:
        parser.error("Etcd 3.5.21, OpenSSL and a positive command timeout up to 1800 seconds are required")
    if not args.self_test and not command:
        parser.error("Specify --self-test or a command after --")
    if args.evidence and args.evidence.exists():
        parser.error("Refusing to overwrite existing fixture evidence")
    previous_umask = os.umask(0o077)
    child = None
    try:
        with EtcdSecurityFixture(args.etcd_binary, args.namespace, args.provider_application,
                                 args.consumer_application, args.require_client_cert) as fixture:
            if args.self_test:
                evidence = fixture.self_test()
                if args.evidence:
                    private_write(args.evidence, json.dumps(evidence, indent=2) + "\n")
                print("Etcd TLS/RBAC fixture checks passed: " + str(len(evidence["passed"])), flush=True)
            if command:
                environment = os.environ | fixture.environment()
                for key in ("OTRYX_RPC_E2E_RESTART_NACOS", "OTRYX_RPC_E2E_MAX_STALENESS"):
                    environment.pop(key, None)
                child = OwnedProcess(command, env=environment, start_new_session=True)
                try:
                    return child.wait(timeout=args.command_timeout)
                except subprocess.TimeoutExpired:
                    raise FixtureFailure("Etcd security test command exceeded its total budget") from None
                finally:
                    stop_owned(child)
        return 0
    except (FixtureFailure, OwnedProcessFailure, OSError, subprocess.SubprocessError) as error:
        reason = ": " + str(error) if isinstance(error, FixtureFailure) else ""
        print("Etcd security fixture failed: type=" + type(error).__name__ + reason, file=sys.stderr)
        return 1
    finally:
        os.umask(previous_umask)


if __name__ == "__main__":
    def interrupted(_number, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
