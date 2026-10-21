#!/usr/bin/env python3
"""Check exact mTLS phase evidence without starting JVMs or a Registry service."""

from __future__ import annotations

import ast
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from assert_quickstart_snapshot import DETAILS, GREETING, SERVICES, assert_snapshot

ROOT = Path(__file__).resolve().parent.parent
NAMESPACE = "security-phase-test"
PROVIDER = "otryx-quickstart-mtls-provider"
CONSUMER = "otryx-quickstart-mtls-consumer"
PRESENT = {
    "E01-allowed-initial", "E02-rotated-consumer", "E03-unbound-provider",
    "E05-remote-application-mismatch", "E06-remote-namespace-mismatch", "E07-wrong-rpc-ca",
    "E10-expired-policy", "E11-details-denied", "E13-allowed-repeat",
}
ABSENT = {
    "E04-local-application-mismatch", "E08-expired-local-binding", "E09-expired-certificate",
    "E12-missing-authorizer-provider", "E12-missing-authorizer-both", "E14-wrong-registry-ca",
    "E14-wrong-registry-credentials", "E15-observer-read-only",
}


def evidence(consumer_present: bool) -> dict:
    """Build synthetic shape evidence only; this fixture makes no network claims."""
    revision = "sha256:" + "a" * 64
    contracts = [GREETING, DETAILS]
    provider = {"instanceId": "provider-main", "role": "PROVIDER",
                "rpcEndpoint": {"host": "127.0.0.1", "port": 19143},
                "weight": 100, "manifestRevision": revision}
    consumer = {"instanceId": "consumer-main", "role": "CONSUMER", "rpcEndpoint": None,
                "weight": 0, "manifestRevision": None}

    def key(name: str) -> dict:
        return {"serviceName": name, "version": "1.0.0", "group": "default"}

    exports = [{"serviceKey": key(name), "serviceId": index, "methodIds": [index],
                "schemaFingerprint": "b" * 64} for index, name in enumerate(contracts, 1)]
    route = {"applicationName": PROVIDER, **{name: provider[name] for name in
             ("instanceId", "role", "rpcEndpoint", "manifestRevision")}}
    return {"applicationNamespace": NAMESPACE, "applications": [
        {"applicationName": PROVIDER, "members": [provider],
         "manifests": [{"revision": revision, "services": exports}]},
        {"applicationName": CONSUMER, "members": [consumer] if consumer_present else [], "manifests": []},
    ], "services": [{"serviceKey": key(name), "mappedApplications": [PROVIDER] if name in contracts else [],
                     "verifiedProviders": [copy.deepcopy(route)] if name in contracts else []}
                    for name in sorted(SERVICES)]}


def literal_stages(expression: ast.expr) -> set[str]:
    """Expand only the runner's literal stage strings and its two E12 branches."""
    if isinstance(expression, ast.Constant) and isinstance(expression.value, str):
        return {expression.value}
    if isinstance(expression, ast.IfExp):
        return literal_stages(expression.body) | literal_stages(expression.orelse)
    if isinstance(expression, ast.BinOp) and isinstance(expression.op, ast.Add):
        return {left + right for left in literal_stages(expression.left)
                for right in literal_stages(expression.right)}
    raise AssertionError("Security runner changed its explicit stage construction")


def actual_runner_phases() -> tuple[set[str], set[str]]:
    """Read phase call sites without importing or executing the service launcher."""
    tree = ast.parse((ROOT / "scripts/run_quickstart_mtls_e2e.py").read_text(encoding="utf-8"))
    scenario = next(node for node in tree.body if isinstance(node, ast.ClassDef)
                    and node.name == "SecurityScenario")
    run = next(node for node in scenario.body if isinstance(node, ast.FunctionDef) and node.name == "run")
    present, absent = set(), set()
    for node in ast.walk(run):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                and isinstance(node.func.value, ast.Name) and node.func.value.id == "self":
            if node.func.attr == "client":
                present.update(literal_stages(node.args[0]))
            elif node.func.attr == "startup_rejected":
                absent.update(literal_stages(node.args[0]))
        elif isinstance(node, ast.Assign) and any(isinstance(target, ast.Attribute)
                                                 and isinstance(target.value, ast.Name)
                                                 and target.value.id == "self" and target.attr == "stage"
                                                 for target in node.targets):
            absent.update(literal_stages(node.value))
    return present, absent


class SecuritySnapshotTest(unittest.TestCase):
    def check(self, stage: str, data: dict) -> None:
        assert_snapshot("mtls", stage, data, namespace=NAMESPACE)

    def test_phase_contract_matches_the_actual_runner_call_sites(self) -> None:
        self.assertEqual((PRESENT, ABSENT), actual_runner_phases())
        self.assertEqual(17, len(PRESENT | ABSENT))
        self.assertFalse(PRESENT & ABSENT)

    def test_every_real_phase_accepts_its_exact_membership(self) -> None:
        for stage in sorted(PRESENT | ABSENT):
            with self.subTest(stage=stage):
                self.check(stage, evidence(stage in PRESENT))

    def test_rpc_outcomes_require_the_still_running_consumer(self) -> None:
        for stage in sorted(PRESENT):
            with self.subTest(stage=stage), self.assertRaises(ValueError):
                self.check(stage, evidence(False))

    def test_startup_rejections_and_final_observer_reject_a_stale_consumer(self) -> None:
        for stage in sorted(ABSENT):
            with self.subTest(stage=stage), self.assertRaises(ValueError):
                self.check(stage, evidence(True))

    def test_unknown_and_retired_phase_labels_cannot_certify_new_evidence(self) -> None:
        for stage in ("allowed-initial", "identity-denied", "allowed-repeat", "service-denied",
                      "E00-allowed-initial", "E12-missing-authorizer", "E14-wrong-registry"):
            with self.subTest(stage=stage), self.assertRaises(ValueError):
                self.check(stage, evidence(True))

    def test_missing_authorizer_keeps_only_the_original_live_provider(self) -> None:
        for stage in ("E12-missing-authorizer-provider", "E12-missing-authorizer-both"):
            data = evidence(False)
            self.check(stage, data)
            duplicate = copy.deepcopy(data["applications"][0]["members"][0])
            duplicate["instanceId"] = "missing-authorizer"
            data["applications"][0]["members"].append(duplicate)
            with self.subTest(stage=stage), self.assertRaises(ValueError):
                self.check(stage, data)

    def test_provider_and_both_exported_routes_must_survive_all_phases(self) -> None:
        for stage in sorted(PRESENT | ABSENT):
            for mutation in ("member", GREETING, DETAILS):
                with self.subTest(stage=stage, mutation=mutation):
                    data = evidence(stage in PRESENT)
                    if mutation == "member":
                        data["applications"][0]["members"] = []
                        data["applications"][0]["manifests"] = []
                        for service in data["services"]:
                            service["mappedApplications"] = []
                            service["verifiedProviders"] = []
                    else:
                        service = next(item for item in data["services"]
                                       if item["serviceKey"]["serviceName"] == mutation)
                        service["mappedApplications"] = []
                        service["verifiedProviders"] = []
                    with self.assertRaises(ValueError):
                        self.check(stage, data)

    def test_consumer_identity_role_and_nonroutable_shape_are_exact(self) -> None:
        for field, value in (("instanceId", "other-consumer"), ("role", "BOTH"), ("weight", 1),
                             ("rpcEndpoint", {"host": "127.0.0.1", "port": 19144})):
            data = evidence(True)
            data["applications"][1]["members"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.check("E01-allowed-initial", data)
        data = evidence(True)
        data["services"][0]["verifiedProviders"].append({"applicationName": CONSUMER,
                                                         **data["applications"][1]["members"][0]})
        with self.assertRaises(ValueError):
            self.check("E01-allowed-initial", data)

    def test_observer_cannot_register_itself_or_leave_an_unexpected_application(self) -> None:
        data = evidence(False)
        self.check("E15-observer-read-only", data)
        data["applications"].append({"applicationName": "observer", "members": [], "manifests": []})
        with self.assertRaises(ValueError):
            self.check("E15-observer-read-only", data)

    def test_namespace_and_application_views_cannot_be_omitted(self) -> None:
        data = evidence(False)
        with self.assertRaises(ValueError):
            assert_snapshot("mtls", "E15-observer-read-only", data)
        data["applications"].pop()
        with self.assertRaises(ValueError):
            self.check("E15-observer-read-only", data)

    def test_cli_rejects_stale_consumer_after_startup_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "snapshot.json"
            command = [sys.executable, str(ROOT / "scripts/assert_quickstart_snapshot.py"), "mtls",
                       "E14-wrong-registry-credentials", str(path), "--namespace", NAMESPACE]
            path.write_text(json.dumps(evidence(True)), encoding="utf-8")
            failure = subprocess.run(command, text=True, capture_output=True, check=False, timeout=10)
            self.assertEqual(1, failure.returncode)
            self.assertNotIn("snapshot PASS", failure.stdout)
            path.write_text(json.dumps(evidence(False)), encoding="utf-8")
            success = subprocess.run(command, text=True, capture_output=True, check=False, timeout=10)
            self.assertEqual(0, success.returncode, success.stderr)
            self.assertIn("snapshot PASS", success.stdout)


if __name__ == "__main__":
    unittest.main()
