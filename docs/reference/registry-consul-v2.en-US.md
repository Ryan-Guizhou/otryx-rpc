# Consul V2: Application Membership and KV Metadata

[中文](registry-consul-v2.md)

> [Issue #135](https://github.com/Ryan-Guizhou/otryx-rpc/issues/135) · OTRYX RPC 1.0.0-SNAPSHOT · Current application backend. This page describes the implementation; execution evidence must identify the exact source SHA. See [acceptance boundaries](application-registry-acceptance.md).

## Scope and behavior

**Consul Session TTL plus KV Acquire** atomically claims an application instance identity across processes. Session expiration deletes temporary claims while persistent Mapping generation tombstones remain.

`ConsulApplicationRegistry` implements `ApplicationRegistry + ApplicationRegistrar`. Provider, Consumer and BOTH use an application-scoped Service derived from `ApplicationKey(namespace, applicationName)`, with one Service ID per process. Exporting 30 interfaces does not create 30 members.

- Provider / BOTH registers its real RPC address and port and enters the directory after the TTL Check passes. Core still verifies Manifest, Schema, Wire compatibility and service bindings before routing.
- Consumer-only registers a real member and TTL Check **without an RPC address or port**. The native port defaults to zero; it cannot become a Provider.
- Mapping stores complete exports per instance Owner. CAS ModifyIndex, increasing generation and a process token fence stale publication and revocation.
- Manifest uses application/revision content addressing. Complete canonical bytes are created with `?cas=0`; different content cannot overwrite the same revision.
- Bounded control workers access Agent/Health/KV. The RPC call path reads only locally verified Provider snapshots.

## Explicit security configuration

Include `otryx-registry-consul` explicitly and select the `consul` `ApplicationRegistryFactory` SPI. The default Starter does not include it. The old three/four-argument `RegistryOptions` constructors and `fromCsv` retain their call shape, but use `UNSPECIFIED`; the Factory rejects startup.

For this Registry chain, `NETWORK_MTLS` requires **HTTPS, an explicit CA and ACL_TOKEN**. In the example, `httpsAgentEndpoint` is one actual HTTPS Agent URL, `caFile` is a controlled CA-file `Path`, and `aclToken` is a `char[]` obtained from a credential source. `RpcSecurityProfile` belongs to `com.peachsoft.otryx.api`, `RpcObserver` to `com.peachsoft.otryx.observability`, and the security DTOs to `com.peachsoft.otryx.registry`.

```java
RegistrySecurityOptions security = new RegistrySecurityOptions(
    Optional.of(new RegistryTlsOptions(caFile, Optional.empty())),
    RegistryCredentials.aclToken(aclToken));

RegistryOptions options = new RegistryOptions(
    List.of(httpsAgentEndpoint),
    "production",
    Map.of(
        "consulTtlSeconds", "30",
        "consulHeartbeatSeconds", "10",
        "consulPollIntervalMillis", "1000",
        "consulRequestTimeoutMillis", "3000"),
    RpcObserver.noop(),
    RpcSecurityProfile.NETWORK_MTLS,
    security,
    Optional.empty());

ConsulApplicationRegistry registry = (ConsulApplicationRegistry)
    ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
        .getExtension("consul").create(options);
ApplicationManifestRepository manifests = registry.manifestRepository();
ServiceMappingRepository mappings = registry.serviceMappingRepository();
```

`MEMBERSHIP / MANIFEST / MAPPING` share the same actual HTTP client and `security`. Consul rejects `metadataSecurity`. The ACL token is installed as `X-Consul-Token`; TLS verifies the CA and server hostname, and redirects are disabled. The Factory creates its protection descriptor after installing TLS/credentials; Core checks the complete set of sources. Selecting a profile alone does not configure RPC transport mTLS or method authorization. See [security boundaries](../security.md).

`RegistryTlsOptions.clientIdentity` may add `RegistryClientKeyMaterial(certificateChain, privateKey, privateKeyPassword)`, but it cannot replace the ACL token. The current Factory rejects a standalone `CLIENT_CERTIFICATE` credential mode. Password, token and private-key-password arrays are copied on input and access; security DTO `toString()` methods hide material values. This does not promise to erase strings retained by third-party clients.

For an explicitly isolated plaintext development environment:

```java
RegistryOptions development = RegistryOptions.trustedDevelopment(
    List.of("http://127.0.0.1:8500"), "development", Map.of());
```

`trustedDevelopment` supplies neither TLS nor credentials. Development ACL use still requires the seven-argument constructor, explicit `TRUSTED_DEVELOPMENT` and typed `security`. Do not put credentials/TLS in `providerOptions`; the historical `consulToken` key and other unknown keys are rejected.

## Nonsecurity options and actual paths

| `providerOptions` | Default | Range / purpose |
| --- | --- | --- |
| `consulTtlSeconds` | 30 | 10–3600 seconds |
| `consulHeartbeatSeconds` | 10 | At least 1 second, strictly below TTL |
| `consulPollIntervalMillis` | 1000 | 200–60000 milliseconds |
| `consulRequestTimeoutMillis` | 3000 | 100–60000 milliseconds |
| `consulEnterpriseNamespace` | empty | Optional Consul Enterprise Namespace |

**Exactly one local Agent endpoint is required**: a TTL Check cannot roam between Agents. The OTRYX logical namespace comes from `RegistryOptions.namespace` and is separate from `consulEnterpriseNamespace`.

ACL grants must follow the Service, Session and KV operations actually used by the client. Let `b64(x)` mean URL-safe Base64 of UTF-8 without padding, and `R = otryx/v2/ns/{b64(namespace)}/`. R has no leading slash:

| Object | Actual KV key |
| --- | --- |
| Manifest | `R + apps/{b64(application)}/manifests/{b64(revision)}` |
| Mapping Owner | `R + owners/{b64(application)}/{b64(instanceId)}` |
| Temporary member Claim | `R + claims/{b64(application)}/{b64(instanceId)}` |

The native ServiceName is `otryx-app-` plus the first 12 SHA256 bytes, in hexadecimal, of namespace, a NUL separator and application. The Service ID appends the first 12 SHA256 bytes of instanceId in hexadecimal. Owner lookup scans `R + owners/`. Policies must cover actual read ranges rather than guessing KV or Service names from logical application names. Read-only observation needs no publication grants; a process registering Consumer membership still writes its own member, Claim and Session. Metadata role/token fields are not backend authorization credentials.

## Bounds and failures

| Boundary | Limit |
| --- | --- |
| Single KV value | 256 KiB; no truncation |
| Native Service Meta | At most 64 entries; key/value limits of 128/512 UTF-8 bytes |
| Owner scan | At most 512 KV entries and 8 MiB total |
| ServiceKeys per Owner / candidate applications per lookup | 512 / 128 |
| Process members / subscriptions | 256 / 128 |
| Control workers / queue | 2–4 / 256 |
| Raw HTTP response / HTTP requests in flight | 12 MiB / 64 |

The total HTTP budget includes connection, response body and JSON parsing. Raw bytes are counted before aggregation; error statuses and chunked responses have the same bounds. Strict UTF-8, JSON depth/scalar limits and duplicate-field checks run before metadata is trusted. Writes are not automatically replayed at another endpoint.

Genuine non-passing or unroutable members may be filtered by health. Foreign ownership, damaged V2 roles/fields, malformed content, TLS, authentication, authorization and response-limit failures produce a stable `RegistrySecurityException.reason()` without raw causes or server bodies; they cannot become healthy empty snapshots. Ordinary network failures retain Core's bounded LKG behavior. Security failures invalidate the Resolver's old security epoch immediately, so another route cannot keep using an old snapshot merely because its timer has not run.

HTTP 404 preserves the missing-resource status. After the existing byte and deadline checks, the client discards Consul's text error body instead of parsing it as metadata JSON. A missing Agent member or expired Session can then enter the existing ownership verification, claim and registration recovery path. HTTP 401/403, oversized bodies and malformed metadata in successful responses still fail under the original security rules; they cannot become missing resources.

Default heartbeats renew TTL Checks and Sessions every 10 seconds and reregister missing Agent Checks. `close()` stops scheduling and attempts unregister for at most three seconds. Unreachable registrations expire by TTL; other processes' members are not deleted. Session expiration removes temporary Claims, Owner revoke preserves generation tombstones, and old Manifests are not automatically garbage-collected. Persistent cleanup needs authoritative references, a protection window and atomic fencing. No global revision, cross-datacenter transaction or unconditional strong consistency is claimed.

## Verification and evidence scope

This is a **plaintext development fixture**. It can verify Registry contracts, but does not prove HTTPS or narrow deployment grants:

```bash
docker run -d --name otryx-consul-v2 -p 8500:8500 \
  hashicorp/consul:1.20 agent -dev -client=0.0.0.0
mvn -B -ntp -pl otryx-registry/otryx-registry-consul -am \
  -Dconsul.test.endpoint=http://127.0.0.1:8500 test
```

The [Consul workflow](../../.github/workflows/consul-v2.yml) `consul-v2` job uses real Consul for roles, immutable Manifest, concurrent CAS/fencing, TTL/Session recovery and instance exclusion, with a separate Agent pause/recovery step. Its `acl-enforcement` job starts an isolated default-deny server and generates a temporary management token to verify authorized success and anonymous write rejection. It does not prove per-role/per-instance least privilege or Registry TLS. Credentials are not written into source or Maven arguments.

The shared contract uses 30 real Java interfaces and ten independent Registry client objects in one test JVM. Native membership `0→10→0`, SPI method entries, subscription creation, snapshot/health notifications and convergence times are measured separately; they are not total HTTP/keepalive traffic. Record execution, failure and skips for conditional real-service tests individually. Independent business-JVM process/chain/revision scenarios run in the [application-registry-e2e workflow](../../.github/workflows/application-registry-e2e.yml); see [acceptance boundaries](application-registry-acceptance.md).

Implementation references: [Factory](../../otryx-registry/otryx-registry-consul/src/main/java/com/peachsoft/otryx/registry/consul/ConsulApplicationRegistryFactory.java), [identity and keyspace](../../otryx-registry/otryx-registry-consul/src/main/java/com/peachsoft/otryx/registry/consul/ConsulV2Identity.java), [KV client](../../otryx-registry/otryx-registry-consul/src/main/java/com/peachsoft/otryx/registry/consul/ConsulV2Client.java), [HTTP boundary](../../otryx-registry/otryx-registry-http/src/main/java/com/peachsoft/otryx/registry/http/HttpRegistryClient.java), [Resolver](../../otryx-core/src/main/java/com/peachsoft/otryx/registry/ApplicationServiceResolver.java).
