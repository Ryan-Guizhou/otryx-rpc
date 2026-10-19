# OTRYX RPC

[简体中文](README.md) | [English](README.en-US.md)

![OTRYX brand banner](docs/images/brand/otryx-banner.svg)

<!-- release-status:project=development -->
<!-- release-status:version=1.0.0-SNAPSHOT -->
<!-- release-status:wire=v1 -->

<!-- doc-section:overview -->
## Overview

**OTRYX RPC** is a lightweight, high-performance, highly available Java RPC framework preparing for its first stable release, with pluggable transport, registry and serialization.
Meet Otti, the engineering otter: **Simple to Call. Built to Scale.**

**Source: 1.0.0-SNAPSHOT (first-release development, not yet released)**

**GitHub:** [Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc). Trademark use, original artwork provenance and Maven Central namespace ownership still need external clearance; see [Publication readiness](docs/publication-readiness.md).
· **Java 21** · **Spring Boot 3.5.4** · **Wire v1** · **MIT**

The project has not yet reached GA and makes no backward-compatibility promise to unpublished development snapshots for Java APIs, SPI, Wire, Schema or configuration. Current-version protocol correctness, interoperability and security still require verification. Spring Starter requires an explicit security profile: `TRUSTED_DEVELOPMENT` for isolated demos or `NETWORK_MTLS` (mTLS, strict Fory allowlist and per-method authorization) for network deployments. Directly constructed Core runtimes still require an explicit security boundary. See the [Java coding standard](docs/engineering/java-coding-standard.md).

<!-- doc-section:capabilities -->
## Core capabilities

- **Unary RPC**: Generated Stubs / Dispatchers with JDK Proxy / MethodHandle fallback.
- **Transport**: Vert.x TCP, sharded connections, heartbeat, reconnect, CANCEL, GO_AWAY and graceful drain.
- **Security**: PLAINTEXT / TLS / mTLS, hostname verification and certificate reload.
- **Registry**: Memory, Etcd, Nacos, Consul, Eureka; the consumer hot path reads a local service directory.
- **Load balancing**: P2C + EWMA, inflight count and static weights.
- **Resilience**: method/per-call timeouts, inherited monotonic deadline budgets, explicitly idempotent retries, retry budget, circuit breaker, outlier ejection and provider admission.
- **Execution**: virtual threads, bounded CPU resources, Fory and stable type IDs.
- **Observability**: Micrometer, OpenTelemetry, JFR and Grafana / Prometheus examples.
- **Engineering**: JMH, 10k logical-concurrency soak harness, chaos and independent-process tests.

<!-- doc-section:architecture -->
## Architecture

![OTRYX system architecture](docs/images/architecture/system-overview.svg)

![OTRYX RPC call flow](docs/images/flows/rpc-lifecycle.svg)

**Design**: control-plane/data-plane isolation; SPI and adapters for implementations; no registry I/O, SPI scan or dynamic parsing per request.

**V2 application-level registry migration (in progress):** Core has a shared Provider/Consumer application identity, Manifest, per-instance CAS Mapping, service Resolver and bounded control-plane caches; application-scoped Memory, Etcd, Nacos, Consul (#135) and Eureka with external metadata (#136) adapters are implemented. The current #137 branch connects the single Spring Boot Starter to that control plane: each process instance shares one member record, Consumer creation waits for membership acknowledgement, and shutdown waits within a budget for Client/Provider cleanup before shared membership teardown. Acceptance of #137 still depends on the exact PR head and post-merge main CI. The complete Quickstart rewrite and legacy interface-level SPI deletion remain #138 and #139. **The project-wide V2 migration is not yet complete.** [Design and status](docs/design/application-registry-v2.md).

<!-- doc-section:compatibility -->
## Compatibility and migration

Wire v1 keeps its 32-byte header and message/codec IDs; this does not establish interoperability between different registry models or Java namespaces.
Full Java class names affect type and method identifiers and schema fingerprints, so the current OTRYX namespace change is a **breaking API migration**.

- The original Peach RPC baseline remains available and is not republished under a new GAV.
- Rolling checks select their matrix from the Starter's active registry generation: matching generations retain the complete N/N+1 and rollback combinations; interface/application migration requires only N/N and N+1/N+1 independent-JVM pairs and does not establish mixed-generation interoperability.
- Use isolated registry scopes across generations, and migrate or roll back matching Provider/Consumer pairs. Legacy interface-level metadata keys belong only to the historical implementation retained until #139.

[Wire compatibility](docs/wire-compatibility.md) · [Migration/rollback](docs/migration.md)

<!-- doc-section:quick-start -->
## Quick start

Requires **JDK 21 and Spring Boot 3.5.4**. The checked-in Maven Wrapper pins Maven 3.9.11 and needs Maven Central access on its first run.

~~~bash
./mvnw -B -ntp clean install -DskipTests
./mvnw -B -ntp clean verify -Pquality
~~~

Use `mvnw.cmd` on Windows CMD/PowerShell; a globally installed Maven is optional.

Start with the **no-Docker single-JVM Memory Quickstart**, then Nacos Provider/Consumer and A→B→C Deadline walkthroughs in [otryx-quickstart](otryx-quickstart/README.md); see [Getting started](docs/getting-started.md) for complete setup. The consumer's `QuickstartCallObserver` demonstrates a safe, Spring-managed custom observability SPI.

These existing examples do not establish completion of the full V2 Quickstart rewrite and acceptance in #138. See the [Starter guide](docs/reference/starter.md) for #137 behavior and source-test commands.

<!-- doc-section:dependency -->
## Maven coordinates

Development version:

~~~xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
~~~

The **only Starter** defaults to AutoConfiguration, Core/Memory Registry/JDK Proxy/Fory/Vert.x. Add Nacos, Etcd, Consul/Eureka, CGLIB/ByteBuddy or observability adapters explicitly when needed. **No Maven Central release is claimed for this snapshot.**

First-release RC/GA candidates use an [unsigned non-publishing dry-run](docs/reference/first-release-acceptance.md) to verify main JARs, sources, Javadocs, SBOM and SHA256 checksums. It **does not sign, tag or upload to Central**. Trademark and Maven namespace clearance remain external release gates.

<!-- doc-section:configuration -->
## Configuration

~~~yaml
spring:
  application:
    name: order-service
otryx:
  rpc:
    enabled: true
    security-profile: TRUSTED_DEVELOPMENT # isolated development only
    registry:
      type: memory
      control-plane-timeout: 3s
~~~

`spring.application.name` supplies the default application identity; override it with `otryx.rpc.registry.application-name`. Startup fails if both are absent. `registry.control-plane-timeout` is independent of `server.control-plane-timeout`, defaults to `3s`, and must be positive and no greater than `30s`; it bounds shared membership operations and the Consumer membership wait. No member is created before an actual Consumer or Provider runtime is needed.

Real network deployments must explicitly choose `NETWORK_MTLS` with mutual TLS and a strict Fory DTO allowlist. An optional `RpcAuthorizer` bean checks the verified peer against ServiceKey/MethodId. Missing security intent fails Spring startup; `TRUSTED_DEVELOPMENT` is for isolated demos only. See the [security guide](docs/security.md).

Implemented application-level backends are documented in [Etcd](docs/reference/registry-etcd.md), [Nacos V2](docs/reference/registry-nacos-v2.md), [Consul V2](docs/reference/registry-consul-v2.en-US.md) and [Eureka V2](docs/reference/registry-eureka-v2.en-US.md). Consul uses a single local Agent. Eureka owns application members and requires a separately configured Etcd/Nacos/Consul metadata backend and endpoints. The Starter provides neither fallback to legacy interface-level adapters nor dual reads/writes.

Initialize Nacos V2 Mapping shards before starting any application, in an exclusive maintenance window for the same namespace/group. Quickstart provides a standalone initialization command; its independent-process verification scripts perform this step for a separate group per run. See the [Nacos startup steps](otryx-quickstart/README.en-US.md#2-nacos-two-independent-jvms).

See [Starter guide](docs/reference/starter.md) and [Production configuration](docs/configuration.md) for validated options, TLS and registry setup. The [Deadline and call-context reference](docs/reference/deadline-context.md) covers precedence, explicit scope propagation and A→B→C budget semantics (Chinese).

<!-- doc-section:operations -->
## Performance and operations

Validate deadline, admission, backpressure, retries, TLS and observability against your actual workloads. Shared CI-runner smoke results are not production throughput, p99 or capacity claims.

[Performance evidence](docs/reference/performance-evidence.md) · [Capacity planning](docs/reference/capacity-planning.md) · [Observability](docs/observability.md)

<!-- doc-section:documentation -->
## Documentation

[Documentation index](docs/index.md) · [Getting started](docs/getting-started.md) · [User guide](docs/user-guide.md) · [Operations & troubleshooting](docs/operations.md) · [Architecture](docs/architecture.md) · [SPI](docs/spi.md) · [Security](docs/security.md) · [Brand](docs/brand-guidelines.md) · [Migration](docs/migration.md) · [Publication readiness](docs/publication-readiness.md)

<!-- doc-section:development -->
## Development and contributing

[Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Agent contract](AGENTS.md) · [First-release quality implementation plan](docs/engineering/plans/2026-10-12-open-source-quality-upgrade.md).

The repository requires **Chinese standard Javadoc, English structured logs and reproducible compatibility/performance evidence**.

<!-- doc-section:license -->
## License

[MIT License](LICENSE)

## Registry freshness

Spring Starter V2 defaults to `otryx.rpc.client.directory-max-staleness=30s` and rejects expired last-known-good routes
**after explicit registry synchronization failure**; a quiet Watch alone is not considered unhealthy.
When `client.enabled=true`, an explicit `0s` unbounded setting is rejected even before any reference is created.
Etcd, Nacos, and Consul/Eureka report health and use bounded recovery. See the
[registry recovery and independent-JVM test guide](docs/reference/registry-freshness.md) for the real Nacos restart checks and their limits.
