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

**Application registry (#139 acceptance in progress):** Core, the single Starter and the four Quickstart modules use Application identity, shared Provider/Consumer/BOTH membership, immutable Manifests, instance-owner CAS Mapping, a Resolver and bounded caches. Consumer-only members register without RPC endpoints; ServiceKey identifies business contracts and ServiceInstance is only a local load-balancing DTO. Cleanup gates reject retired SPI, paths and configuration. Shared real-backend contracts, the four-backend process/chain/revision matrix and all five rolling pairs require actual evidence for the exact head; source or shell fixtures alone do not establish acceptance. [Design](docs/design/application-registry-v2.md) · [Acceptance and measurement boundaries](docs/reference/application-registry-acceptance.md).

<!-- doc-section:compatibility -->
## Compatibility and migration

Wire v1 keeps its 32-byte header and message/codec IDs; this does not establish interoperability between different registry models or Java namespaces.
Full Java class names affect type and method identifiers and schema fingerprints, so the current OTRYX namespace change is a **breaking API migration**.

- The original Peach RPC baseline remains available and is not republished under a new GAV.
- Both rolling checkouts must use application-level Starter wiring. All five N/N, N+1/N, N/N+1, N+1/N+1 and rollback pairs run, and any failure blocks acceptance. Retired modes, mixed wiring and unknown layouts are rejected before application processes start.
- Migrate historical registry models as matching pairs in isolated scopes. Wire v1 or an identical Maven version does not replace interoperability checks across the complete business chain.

[Wire compatibility](docs/wire-compatibility.md) · [Migration/rollback](docs/migration.md)

<!-- doc-section:quick-start -->
## Quick start

Requires **JDK 21 and Spring Boot 3.5.4**. The checked-in Maven Wrapper pins Maven 3.9.11 and needs Maven Central access on its first run.

~~~bash
./mvnw -B -ntp clean install -DskipTests
./mvnw -B -ntp clean verify -Pquality
~~~

Use `mvnw.cmd` on Windows CMD/PowerShell; a globally installed Maven is optional.

Start with the **no-Docker single-JVM Memory Quickstart**, then explore Nacos application members and verified routes, the BOTH role and deadlines in A→B→C, promotion/rollback between two real export revisions of one application, and mTLS client-fingerprint and method authorization. See the [Quickstart tutorial](otryx-quickstart/README.en-US.md) for commands, JSON fields and expected outcomes, or [Getting started](docs/getting-started.md) for a shorter path.

After building, with local Nacos, Bash, Python 3, GNU `timeout` and OpenSSL available, run every scenario in order:

~~~bash
NACOS_TEST_ENDPOINT=127.0.0.1:8848 bash scripts/run_quickstart_e2e.sh
~~~

Nacos process scenarios bootstrap separate groups. The process, chain and revision scripts also support real Etcd, Consul and Eureka through `QUICKSTART_REGISTRY` and `QUICKSTART_REGISTRY_ENDPOINT`; Eureka additionally requires `QUICKSTART_EUREKA_METADATA_ENDPOINT` for real Consul metadata. Allowlisted JSON and `summary.log` are saved under `target/quickstart-e2e/`, without temporary private keys. A command proves only its actual coverage; complete #139 acceptance also requires shared contracts, Chaos, exact-head CI and post-merge main verification. See [application acceptance](docs/reference/application-registry-acceptance.md) and the [Starter guide](docs/reference/starter.md) for parameters, telemetry and assembly.

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

Real network deployments must explicitly choose `NETWORK_MTLS` with mutual TLS, a strict Fory DTO allowlist and an `RpcAuthorizer` that checks the verified peer against ServiceKey/MethodId. Provider denies calls by default without an authorizer. Missing security intent fails Spring startup; `TRUSTED_DEVELOPMENT` is for isolated demos only. See the [security guide](docs/security.md).

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

Core and the Spring Starter both default to `otryx.rpc.client.directory-max-staleness=30s` and require a strictly positive finite budget. Expired last-known-good routes are rejected **after explicit registry synchronization failure**; a quiet Watch alone is not unhealthy. Etcd, Nacos and Consul/Eureka expose health events and bounded recovery. See [directory freshness](docs/reference/registry-freshness.md) for state semantics, real Nacos restart checks and the four-backend independent-process evidence boundaries.
