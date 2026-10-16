# Consul V2: Application Membership and KV Metadata

> [Issue #135](https://github.com/Ryan-Guizhou/otryx-rpc/issues/135) · OTRYX RPC 1.0.0-SNAPSHOT · **Under development; final CI and E2E acceptance are still pending.**

## Scope

A **Consul Session TTL plus KV Acquire** atomically fences application instance identity across processes. Expired sessions delete temporary membership claims; persistent Mapping generation tombstones remain.

`ConsulApplicationRegistry` implements `ApplicationRegistry + ApplicationRegistrar` with native Consul Agent Service/TTL and KV metadata. Provider, Consumer, and BOTH register under one application-scoped service name. A single process exports multiple RPC interfaces through an immutable Manifest and per-instance ServiceMapping owner rather than interface-specific Consul services.

Provider/BOTH registers a real RPC address and port. Consumer-only registers a real Consul service and TTL check **without an RPC address or port**. Its native port defaults to zero and it can never enter the Provider routing pool. Healthy Provider members still require Manifest, Schema, and Wire compatibility verification before calling.

Manifest revisions use canonical bytes and create-only Consul KV CAS (`cas=0`). Mapping owners are persisted as one KV value per application instance. CAS ModifyIndex, a monotonically increasing generation, and a process token fence stale writes, including delayed revocation. Discovery performs Consul Health checks; no network lookup runs on the RPC data path.

## Configuration

Explicitly include `otryx-registry-consul` and select the `consul` `ApplicationRegistryFactory` SPI. The default Starter does not include Consul. Use one local Agent endpoint, e.g. `http://127.0.0.1:8500`, and configure:

| RegistryOptions.providerOptions | Default |
| --- | --- |
| `consulToken` | empty; inject via environment/secret manager |
| `consulTtlSeconds` | 30 |
| `consulHeartbeatSeconds` | 10 |
| `consulPollIntervalMillis` | 1000 |
| `consulRequestTimeoutMillis` | 3000 |
| `consulEnterpriseNamespace` | empty |

The OTRYX logical namespace is `RegistryOptions.namespace`; Consul Enterprise Namespace is distinct. Restrict ACL policies to required `service:read/write`, `key:read/write`, and `session:write`. Consul HTTP API ACL and RPC mTLS/authorizer are independent security controls. Metadata is never an authorization source.

## Limits and failure behavior

A KV value is limited to **256 KiB** by the adapter (below Consul's usual 512 KiB default). Owner scans are capped at 512 keys / 8 MiB, per-owner exports at 512, candidate applications at 128, local members at 256, and subscriptions at 128. Control-plane work uses 2–4 threads and a bounded 256-task queue. HTTP requests have a finite timeout. Malformed metadata, foreign namespaces, exhausted capacities, failed CAS, and ACL/network errors fail closed; they must never become valid empty results.

Heartbeats renew both local Agent TTL checks and Consul Session leases and reregister missing checks. Polling subscriptions distinguish valid empty member directories from failures. `close()` has a three-second best-effort cleanup budget; unreachable Agent registrations expire through TTL.

Consul Session invalidation cleans up temporary membership claims. Revoke preserves a generation tombstone; old Manifest revisions are intentionally retained. Arbitrary deletion can break rollout, rollback, and fencing, so safe cross-instance GC remains dependent on authoritative membership checks and retention rules. Catalog convergence and HA limits require deployment-specific validation. This adapter does not claim a native global revision or distributed transaction.

## Real backend verification

```bash
docker run -d --name otryx-consul-v2 -p 8500:8500 \
  hashicorp/consul:1.20 agent -dev -client=0.0.0.0
mvn -B -ntp -pl otryx-registry/otryx-registry-consul -am \
  -Dconsul.test.endpoint=http://127.0.0.1:8500 test
```

`.github/workflows/consul-v2.yml` runs against a real Consul server, covering three roles, immutable Manifest, distributed Mapping fencing, heartbeat and Session recovery, cross-process identity exclusion, and a 30-contract by 10-instance density case. The legacy fake Agent tests are not evidence for V2. Additional pause/restart, ACL, cross-process contention, and GC tests are needed before closing #135. Full Starter/Quickstart migration and legacy Registry SPI deletion belong to #137–#139.
