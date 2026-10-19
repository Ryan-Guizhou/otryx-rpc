# Eureka V2 Application Membership

> Issue [#136](https://github.com/Ryan-Guizhou/otryx-rpc/issues/136) · `1.0.0-SNAPSHOT`. Current application backend; integrated #139 acceptance still requires exact-head backend execution and post-merge main CI.

## Separation of responsibilities

Eureka is the **only authority for application member leases**: Provider, Consumer-only, and BOTH all register native Eureka InstanceInfo under one deterministic application-scoped name. All interfaces exported by a process share one Eureka member. A pure Consumer uses a **real host identity** with a **native disabled RPC port** (`port=0`, `@enabled=false`) and does not advertise any OTRYX RPC endpoint.

Eureka is **not a transactional metadata database**. Configuration must explicitly provide one external `etcd`, `nacos`, or `consul` adapter with immutable Manifest CAS and instance-owned Mapping. The external backend is not used to register the application member. `ApplicationMetadataBackend` exposes the repository pair and `ServiceMappingCandidateSource` reads candidate mappings independently of another Registry's lease. The Eureka mapping view then verifies the **same publishing instanceId** is an actual UP Provider member, never substituting an unrelated healthy Provider from the same application; Core still verifies Manifest, schema and bound RPC services.

Eureka lacks native compare-and-set for InstanceInfo ownership. Registration uses a random per-process suffix in its native Instance ID; two processes with the same logical instanceId are **not allowed to overwrite one another**, and duplicate logical identities in discovery are rejected instead of arbitrarily routed. Deployments must ensure logical instance IDs are unique. Persisted Mapping Generation CAS still fences old owner writes.

## Configuration

Explicitly depend on `otryx-registry-eureka` **and** one backend adapter (`otryx-registry-etcd`, `otryx-registry-nacos`, or `otryx-registry-consul`). The default Starter does not include them. Example:

```java
RegistryOptions options = new RegistryOptions(
    List.of("http://127.0.0.1:8761/eureka"),
    "production",
    Map.of(
        "eurekaMetadataBackend", "consul",
        "eurekaMetadataEndpoints", "http://127.0.0.1:8500",
        "eurekaMetadata.consulToken", System.getenv().getOrDefault("CONSUL_HTTP_TOKEN", ""),
        "eurekaMemberHost", "192.168.1.10",
        "eurekaLeaseSeconds", "30",
        "eurekaHeartbeatSeconds", "10",
        "eurekaPollIntervalMillis", "1000"));
EurekaApplicationRegistry registry = (EurekaApplicationRegistry)
        ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
                .getExtension("eureka").create(options);
```

All `eurekaMetadata.*` options are passed to the selected backend, with the same logical namespace. Backend omission, invalid adapter, or missing candidate lookup support fail fast. `eurekaUsername`/`eurekaPassword` enable Eureka Basic Auth; use HTTPS and a secure credential source. External Metadata ACL and Eureka credentials are different trust boundaries.

## Consistency and bounds

Member count <=256; subscriptions <=128; 2–4 bounded control workers and a queue of 256; default Eureka lease/heartbeat/poll/request timeout 30s/10s/1s/3s. A 404 lease renew triggers re-registration; genuine HTTP, authorization and schema failures propagate and cannot silently replace LKG with an empty result. Mapping candidates are **not routable** until checked against Eureka members, Manifest, schema, endpoint and local service bindings.

`directory-max-staleness` limits LKG use only after an explicit synchronization failure. Membership polling treats a parsed and validated HTTP 200 response, or an HTTP 404 handled as an empty application, as a successful synchronization. These results do not automatically signal failure when the server cache has not yet incorporated a membership change. Core cannot infer that cache lag from a successful response, so this budget does not bound the age of Eureka server cache contents. See [directory freshness](registry-freshness.md).

The Starter and Quickstart use the same application control plane. No automatic GC of durable Generation tombstones or Manifest revisions is claimed: these protect rollbacks and fencing.

## Real service E2E

`.github/workflows/eureka-v2.yml` launches a real Spring Boot 3.5.4 / Spring Cloud Netflix Eureka 2025.0.0 server alongside real Consul KV. CI checks Provider/Consumer/BOTH native Info, disabled Consumer port, real Meta CAS + no cross-registry member duplication, 30 real Java interfaces × ten independent Registry client objects in one test JVM, lost lease recovery and a genuine Eureka JVM restart. The server is isolated under `.github/test-fixtures/eureka-server`, not an extra runtime Starter or Maven reactor module.

The isolated Eureka fixture sets `eureka.server.use-read-only-response-cache=false`, disabling only the read-only layer. The read-write response cache still uses its own invalidation and expiration mechanisms. The fixture also sets `eureka.server.response-cache-auto-expiration-in-seconds=1` so that read-write cache entries expire one second after they are written; subsequent queries reload expired entries. `eureka.server.response-cache-update-interval-ms` is not the read-write cache expiration time.

Fixture cache settings define convergence conditions for isolated CI. They are not a production adapter fix and do not guarantee convergence within 25 seconds under Eureka's production defaults. See the [engineering checkpoint](../engineering/agent-governance-checkpoint.md) for actual run configuration, results and outstanding gates.

Independent business JVM process/chain/revision scenarios run separately in `.github/workflows/application-registry-e2e.yml` against real Eureka and Consul. Native members, SPI method-entry calls, subscription creation calls and snapshot/health notifications and convergence time are reported separately; they are not total backend HTTP, lease renewal or server Watch traffic. See [acceptance boundaries](application-registry-acceptance.md).
