#!/usr/bin/env python3
"""Verify generated Registry PKI and bounded readiness failure diagnostics."""
from __future__ import annotations

import json
from contextlib import redirect_stderr
import io
from pathlib import Path
import ssl
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import URLError

from etcd_security_fixture import EtcdSecurityFixture, FixtureFailure, FixtureRpcFailure
import etcd_security_fixture


class FixturePkiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="otryx-fixture-pki-test-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.fixture = EtcdSecurityFixture("unused", require_client_cert=True)
        cls.fixture.directory = Path(cls.temporary.name)
        cls.fixture.pki = cls.fixture.directory / "pki"
        cls.fixture.pki.mkdir(mode=0o700)
        cls.fixture._create_pki()

    def verify(self, certificate, purpose, *, host="localhost", ca="ca"):
        return subprocess.run(
            ["openssl", "verify", "-x509_strict", "-CAfile", str(self.fixture.pki / (ca + ".crt")),
             "-verify_hostname", host, "-purpose", purpose, str(self.fixture.pki / (certificate + ".crt"))],
            capture_output=True, text=True, timeout=5)

    def test_generated_certificates_pass_strict_chain_and_purpose_checks(self):
        for certificate, purpose in (("server", "sslserver"), ("server", "sslclient"),
                                     ("client", "sslclient"), ("gateway-client", "sslclient")):
            with self.subTest(certificate=certificate, purpose=purpose):
                result = self.verify(certificate, purpose)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_strict_verification_still_rejects_wrong_host_and_untrusted_ca(self):
        for options in ({"host": "127.0.0.1"}, {"ca": "wrong-ca"}):
            with self.subTest(options=options):
                result = self.verify("server", "sslserver", **options)
                self.assertNotEqual(result.returncode, 0)


class FixtureReadinessTest(unittest.TestCase):
    def test_last_failure_is_bounded_and_does_not_include_exception_text(self):
        certificate_error = ssl.SSLCertVerificationError(1, "password=must-not-escape")
        certificate_error.verify_code = 92
        failures = (
            (URLError(certificate_error), {"kind": "tls-certificate", "verifyCode": 92}),
            (FixtureRpcFailure(503, 14), {"kind": "rpc", "http": 503, "grpc": 14}),
            (URLError(OSError(111, "token=must-not-escape")), {"kind": "os", "errno": 111}),
        )
        for failure, expected in failures:
            with self.subTest(kind=expected["kind"]):
                fixture = EtcdSecurityFixture("unused")
                fixture.process = Mock()
                fixture.process.poll.return_value = None
                fixture.request = Mock(side_effect=failure)
                with patch("etcd_security_fixture.time.monotonic", side_effect=(0, 1, 26)), \
                        patch("etcd_security_fixture.time.sleep"), \
                        self.assertRaises(FixtureFailure) as raised:
                    fixture._await_ready()
                self.assertIn(json.dumps(expected, sort_keys=True), str(raised.exception))
                self.assertNotIn("must-not-escape", str(raised.exception))
                self.assertLess(len(str(raised.exception)), 256)

    def test_cli_keeps_failure_evidence_without_raw_exception_text(self):
        fixture = Mock()
        fixture.readiness = {"status": "failed", "attempts": 1,
                             "lastProbe": {"kind": "tls-certificate", "verifyCode": 92}}
        fixture.__enter__ = Mock(side_effect=FixtureFailure("bounded fixture failure"))
        fixture.__exit__ = Mock(return_value=False)
        with tempfile.TemporaryDirectory(prefix="otryx-fixture-evidence-test-") as temporary:
            evidence = Path(temporary) / "failure.json"
            arguments = ["etcd_security_fixture.py", "--etcd-binary", "unused", "--self-test",
                         "--evidence", str(evidence)]
            with patch("sys.argv", arguments), patch("etcd_security_fixture.EtcdSecurityFixture",
                                                     return_value=fixture), redirect_stderr(io.StringIO()):
                self.assertEqual(etcd_security_fixture.main(), 1)
            self.assertTrue(evidence.is_file(), "A failed fixture must leave bounded diagnostic evidence")
            result = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(result["status"], "failed")
            self.assertEqual(result["readiness"], fixture.readiness)
            self.assertNotIn("passed", result)
            self.assertEqual(evidence.stat().st_mode & 0o777, 0o600)


if __name__ == "__main__":
    unittest.main()
