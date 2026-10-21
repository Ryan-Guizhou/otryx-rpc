#!/usr/bin/env python3
"""Assert Quickstart application membership and routing from a real read-only snapshot."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SERVICE_PREFIX = "com.peachsoft.otryx.quickstart.api."
GREETING = SERVICE_PREFIX + "GreetingService"
RELAY = SERVICE_PREFIX + "RelayGreetingService"
DETAILS = SERVICE_PREFIX + "GreetingDetailsService"
SERVICES = {GREETING, RELAY, DETAILS}
REVISION = re.compile(r"sha256:[0-9a-f]{64}")
FINGERPRINT = re.compile(r"[0-9a-f]{64}")

# SecurityScenario.client snapshots before stopping its live Consumer. Startup
# rejections snapshot after exit, and the final observer runs after all clients stop.
MTLS_CONSUMER_PRESENT = frozenset({
    "E01-allowed-initial", "E02-rotated-consumer", "E03-unbound-provider",
    "E05-remote-application-mismatch", "E06-remote-namespace-mismatch", "E07-wrong-rpc-ca",
    "E10-expired-policy", "E11-details-denied", "E13-allowed-repeat",
})
MTLS_CONSUMER_ABSENT = frozenset({
    "E04-local-application-mismatch", "E08-expired-local-binding", "E09-expired-certificate",
    "E12-missing-authorizer-provider", "E12-missing-authorizer-both", "E14-wrong-registry-ca",
    "E14-wrong-registry-credentials", "E15-observer-read-only",
})


def require(condition: bool, message: str) -> None:
    """Reject incomplete, contradictory or unexpected evidence with a stable message."""
    if not condition:
        raise ValueError(message)


def keyed(items: list[dict], name: str) -> dict:
    """Index a list without silently overwriting duplicate evidence."""
    require(isinstance(items, list), "Expected an evidence list")
    result = {}
    for item in items:
        require(isinstance(item, dict) and name in item, "Incomplete evidence entry")
        key = item[name]
        require(isinstance(key, str) and bool(key), "Invalid evidence identifier")
        require(key not in result, "Duplicate evidence identifier")
        result[key] = item
    return result


def contract(key: dict) -> str:
    """Require the actual Quickstart ServiceKey, including version and group."""
    require(isinstance(key, dict), "Invalid service contract")
    require(key.get("version") == "1.0.0" and key.get("group") == "default",
            "Unexpected service version or group")
    name = key.get("serviceName")
    require(name in SERVICES, "Unexpected Quickstart contract")
    return name


def endpoint(value: dict, port: int | None = None) -> None:
    """Require a real loopback RPC endpoint; zero and fake consumer ports are invalid."""
    require(isinstance(value, dict), "Missing Provider endpoint")
    require(value.get("host") == "127.0.0.1", "Unexpected Provider host")
    actual = value.get("port")
    require(isinstance(actual, int) and not isinstance(actual, bool) and 0 < actual <= 65535,
            "Invalid Provider port")
    if port is not None:
        require(actual == port, "Unexpected Provider port")


class Snapshot:
    """Validate internal consistency before any scenario-specific assertion."""

    def __init__(self, data: dict, namespace: str = "public"):
        require(isinstance(namespace, str) and re.fullmatch(r"[A-Za-z0-9._-]{1,128}", namespace) is not None,
                "Invalid expected application namespace")
        require(isinstance(data, dict) and data.get("applicationNamespace") == namespace,
                "Unexpected application namespace")
        self.applications = keyed(data.get("applications"), "applicationName")
        self.members = {}
        self.exports = {}
        for app_name, app in self.applications.items():
            manifests = keyed(app.get("manifests"), "revision")
            members = keyed(app.get("members"), "instanceId")
            referenced = set()
            for revision, manifest in manifests.items():
                require(REVISION.fullmatch(revision) is not None, "Invalid Manifest revision")
                exports = {}
                require(isinstance(manifest.get("services"), list), "Missing Manifest exports")
                for service in manifest["services"]:
                    name = contract(service.get("serviceKey"))
                    require(name not in exports, "Duplicate Manifest contract")
                    require(isinstance(service.get("serviceId"), int)
                            and not isinstance(service["serviceId"], bool), "Missing service identifier")
                    methods = service.get("methodIds")
                    require(isinstance(methods, list) and len(methods) > 0
                            and all(isinstance(value, int) and not isinstance(value, bool) for value in methods)
                            and len(set(methods)) == len(methods), "Invalid method identifiers")
                    require(isinstance(service.get("schemaFingerprint"), str)
                            and FINGERPRINT.fullmatch(service["schemaFingerprint"]) is not None,
                            "Invalid schema fingerprint")
                    exports[name] = service
                self.exports[app_name, revision] = exports
            for instance_id, member in members.items():
                role = member.get("role")
                require(role in {"PROVIDER", "CONSUMER", "BOTH"}, "Invalid application role")
                if role == "CONSUMER":
                    require(member.get("rpcEndpoint") is None and member.get("manifestRevision") is None
                            and type(member.get("weight")) is int and member["weight"] == 0,
                            "Consumer-only member became routable")
                else:
                    endpoint(member.get("rpcEndpoint"))
                    require(type(member.get("weight")) is int and member["weight"] > 0,
                            "Invalid Provider weight")
                    revision = member.get("manifestRevision")
                    require(revision in manifests, "Provider Manifest was not read")
                    referenced.add(revision)
                self.members[app_name, instance_id] = member
            require(set(manifests) == referenced, "Snapshot contains an unreferenced Manifest")
        require(isinstance(data.get("services"), list), "Missing service observations")
        self.services = {}
        for service in data["services"]:
            name = contract(service.get("serviceKey"))
            require(name not in self.services, "Duplicate service observation")
            applications = service.get("mappedApplications")
            require(isinstance(applications, list) and all(isinstance(value, str) for value in applications)
                    and len(set(applications)) == len(applications), "Invalid Mapping observations")
            providers = service.get("verifiedProviders")
            require(isinstance(providers, list), "Missing verified Provider observations")
            seen = set()
            for provider in providers:
                identity = (provider.get("applicationName"), provider.get("instanceId"))
                require(identity in self.members and identity not in seen, "Unknown or duplicate route")
                seen.add(identity)
                member = self.members[identity]
                require(member["role"] in {"PROVIDER", "BOTH"}, "Consumer-only member entered routes")
                require(all(provider.get(field) == member[field]
                            for field in ("role", "rpcEndpoint", "manifestRevision")),
                        "Route does not match its actual member")
                require(identity[0] in applications, "Route application is absent from Mapping")
                require(name in self.exports[identity[0], member["manifestRevision"]],
                        "Route is absent from its instance Manifest")
            self.services[name] = service
        require(set(self.services) == SERVICES, "Incomplete fixed-contract observations")

    def expect_members(self, expected: dict[tuple[str, str], tuple[str, int | None, set[str]]]) -> None:
        """Check all requested members, exact roles, endpoints and actual export sets."""
        require(set(self.members) == set(expected), "Unexpected or missing application member")
        for identity, (role, port, exports) in expected.items():
            member = self.members[identity]
            require(member["role"] == role, "Application has the wrong role")
            if port is not None:
                endpoint(member["rpcEndpoint"], port)
                require(set(self.exports[identity[0], member["manifestRevision"]]) == exports,
                        "Manifest does not contain the required actual export set")

    def expect_routes(self, expected: dict[str, set[tuple[str, str]]]) -> None:
        """Check exact Core-verified candidates and Mapping for every observed contract."""
        for name in SERVICES:
            service = self.services[name]
            routes = expected.get(name, set())
            actual = {(entry["applicationName"], entry["instanceId"])
                      for entry in service["verifiedProviders"]}
            require(actual == routes, "Unexpected or missing Core-verified Provider")
            require(set(service["mappedApplications"]) == {identity[0] for identity in routes},
                    "Mapping has not converged to live contract owners")


def assert_snapshot(scenario: str, stage: str, data: dict, previous: dict | None = None,
                    namespace: str = "public") -> None:
    """Assert named real deployment phases; no synthetic revision labels are accepted."""
    snapshot = Snapshot(data, namespace)
    prior = Snapshot(previous, namespace) if previous is not None else None
    expected = {}
    routes = {}
    if scenario == "process":
        provider = ("otryx-quickstart-process-provider", "provider-main")
        consumer = ("otryx-quickstart-process-consumer", "consumer-main")
        require(stage in {"initial", "provider-stopped", "provider-restarted", "registry-recovered", "fresh-consumer",
                          "endpoint-changed"}, "Unknown process stage")
        expected[consumer] = ("CONSUMER", None, set())
        if stage != "provider-stopped":
            expected[provider] = ("PROVIDER", 19091 if stage == "endpoint-changed" else 19090, {GREETING})
            routes[GREETING] = {provider}
        names = {provider[0], consumer[0]}
        if stage == "fresh-consumer":
            fresh = ("otryx-quickstart-process-fresh-consumer", "fresh-consumer")
            expected[fresh] = ("CONSUMER", None, set())
            names.add(fresh[0])
    elif scenario == "chain":
        require(stage == "complete", "Unknown deadline-chain stage")
        leaf = ("otryx-quickstart-chain-leaf", "leaf")
        relay = ("otryx-quickstart-chain-relay", "relay")
        consumer = ("otryx-quickstart-chain-consumer", "consumer")
        expected = {leaf: ("PROVIDER", 19090, {GREETING}), relay: ("BOTH", 19091, {RELAY}),
                    consumer: ("CONSUMER", None, set())}
        routes = {GREETING: {leaf}, RELAY: {relay}}
        names = {leaf[0], relay[0], consumer[0]}
    elif scenario == "revision":
        provider_name = "otryx-quickstart-revision-provider"
        baseline = (provider_name, "baseline")
        extended = (provider_name, "extended")
        consumer = ("otryx-quickstart-revision-consumer", "consumer-main")
        require(stage in {"baseline", "coexist", "details-probed", "promoted", "rollback-coexist", "rolled-back",
                          "providers-stopped"}, "Unknown revision stage")
        expected[consumer] = ("CONSUMER", None, set())
        if stage in {"baseline", "coexist", "details-probed", "rollback-coexist", "rolled-back"}:
            expected[baseline] = ("PROVIDER", 19090, {GREETING})
            routes[GREETING] = {baseline}
        if stage in {"coexist", "details-probed", "promoted", "rollback-coexist"}:
            expected[extended] = ("PROVIDER", 19091, {GREETING, DETAILS})
            routes.setdefault(GREETING, set()).add(extended)
            routes[DETAILS] = {extended}
        if stage in {"coexist", "details-probed", "rollback-coexist"}:
            require(baseline in snapshot.members and extended in snapshot.members,
                    "Both real revision instances must coexist")
            require(snapshot.members[baseline]["manifestRevision"] !=
                    snapshot.members[extended]["manifestRevision"], "Actual Manifest revisions are identical")
            first_exports = snapshot.exports[provider_name, snapshot.members[baseline]["manifestRevision"]]
            second_exports = snapshot.exports[provider_name, snapshot.members[extended]["manifestRevision"]]
            require(GREETING in first_exports and GREETING in second_exports, "Common Greeting contract is missing")
            first = first_exports[GREETING]
            second = second_exports[GREETING]
            require(first["serviceKey"] == second["serviceKey"]
                    and first["serviceId"] == second["serviceId"]
                    and first["schemaFingerprint"] == second["schemaFingerprint"]
                    and set(first["methodIds"]) == set(second["methodIds"]),
                    "Common Greeting contract differs between actual revisions")
        if stage != "baseline":
            require(prior is not None and baseline in prior.members, "Original revision evidence is required")
            require(consumer in prior.members, "Original Consumer evidence is required")
            if baseline in snapshot.members:
                require(snapshot.members[baseline]["manifestRevision"] ==
                        prior.members[baseline]["manifestRevision"], "Rollback did not restore the actual revision")
        names = {provider_name, consumer[0]}
        if stage == "details-probed":
            probe = ("otryx-quickstart-revision-details-probe", "details-probe")
            expected[probe] = ("CONSUMER", None, set())
            names.add(probe[0])
    elif scenario == "mtls":
        provider = ("otryx-quickstart-mtls-provider", "provider-main")
        consumer = ("otryx-quickstart-mtls-consumer", "consumer-main")
        require(stage in MTLS_CONSUMER_PRESENT | MTLS_CONSUMER_ABSENT, "Unknown mTLS stage")
        # The original Provider stays alive while E12 rejects a second startup.
        expected = {provider: ("PROVIDER", 19143, {GREETING, DETAILS})}
        if stage in MTLS_CONSUMER_PRESENT:
            expected[consumer] = ("CONSUMER", None, set())
        routes = {GREETING: {provider}, DETAILS: {provider}}
        names = {provider[0], consumer[0]}
    else:
        raise ValueError("Unknown Quickstart scenario")
    require(set(snapshot.applications) == names, "Unexpected application observation set")
    snapshot.expect_members(expected)
    snapshot.expect_routes(routes)


def main() -> int:
    """Read complete JSON evidence and return a failure status for every unmet invariant."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=("process", "chain", "revision", "mtls"))
    parser.add_argument("stage")
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--namespace", default="public")
    args = parser.parse_args()
    try:
        data = json.loads(args.snapshot.read_text(encoding="utf-8"))
        previous = json.loads(args.previous.read_text(encoding="utf-8")) if args.previous else None
        assert_snapshot(args.scenario, args.stage, data, previous, args.namespace)
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"Quickstart snapshot rejected: {type(error).__name__}: {error}", file=sys.stderr)
        return 1
    print(f"Quickstart snapshot PASS: scenario={args.scenario} stage={args.stage}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
