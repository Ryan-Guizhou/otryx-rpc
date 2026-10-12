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

The project has not yet reached GA and makes no backward-compatibility promise to unpublished development snapshots for Java APIs, SPI, Wire, Schema or configuration. Current-version protocol correctness, consumer/provider interoperability and security still require verification. The transport can default to PLAINTEXT, and directly constructed Fory codecs and the Starter currently use trusted compatibility mode by default; configure appropriate TLS/mTLS and deserialization restrictions before use on untrusted networks. See the [Java coding standard](docs/engineering/java-coding-standard.md).

<!-- doc-section:capabilities -->
## Core capabilities

- **Unary RPC**: Generated Stubs / Dispatchers with JDK Proxy / MethodHandle fallback.
- **Transport**: Vert.x TCP, sharded connections, heartbeat, reconnect, CANCEL, GO_AWAY and graceful drain.
- **Security**: PLAINTEXT / TLS / mTLS, hostname verification and certificate reload.
- **Registry**: Memory, Etcd, Nacos, Consul, Eureka; the consumer hot path reads a local service directory.
- **Load balancing**: P2C + EWMA, inflight count and static weights.
- **Resilience**: end-to-end deadline, explicitly idempotent retries, retry budget, circuit breaker, outlier ejection and provider admission.
- **Execution**: virtual threads, bounded CPU resources, Fory and stable type IDs.
- **Observability**: Micrometer, OpenTelemetry, JFR and Grafana / Prometheus examples.
- **Engineering**: JMH, 10k logical-concurrency soak harness, chaos and independent-process tests.

<!-- doc-section:architecture -->
## Architecture

![OTRYX system architecture](docs/images/architecture/system-overview.svg)

![OTRYX RPC call flow](docs/images/flows/rpc-lifecycle.svg)

**Design**: control-plane/data-plane isolation; SPI and adapters for implementations; no registry I/O, SPI scan or dynamic parsing per request.

<!-- doc-section:compatibility -->
## Compatibility and migration

Wire v1 keeps its 32-byte header, message/codec IDs and historical registry compatibility metadata.
However, full Java class names affect type and method identifiers and schema fingerprints. the current OTRYX namespace change is therefore a **breaking API migration**.

- The original Peach RPC baseline remains available and is not republished under a new GAV.
- Isolate deployments or use blue/green upgrades until cross-version service contract compatibility is independently verified.
- Frozen registry keys remain peach.rpc.protocol.version / peach.rpc.schema.version / peach.rpc.schema.fingerprint.

[Wire compatibility](docs/wire-compatibility.md) · [Migration/rollback](docs/migration.md)

<!-- doc-section:quick-start -->
## Quick start

Requires **JDK 21, Maven and Spring Boot 3.5.4**. Build the development version from source:

~~~bash
mvn -B -ntp clean install -DskipTests
mvn -B -ntp clean verify -Pquality
~~~

Run the independent Provider / Consumer example from [otryx-examples](otryx-examples/README.md); see [Getting started](docs/getting-started.md) for complete setup.

<!-- doc-section:dependency -->
## Maven coordinates

Development version:

~~~xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter-lite</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
~~~

The full starter is otryx-spring-boot-starter. **No Maven Central release is claimed for this snapshot.**

<!-- doc-section:configuration -->
## Configuration

~~~yaml
otryx:
  rpc:
    enabled: true
    registry:
      type: memory
~~~

Consul and Eureka are opt-in adapters with polling-based subscriptions. See [Consul](docs/reference/registry-consul.md) and [Eureka](docs/reference/registry-eureka.md).

See [Starter guide](docs/reference/starter.md) and [Production configuration](docs/configuration.md) for validated options, TLS and registry setup.

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
