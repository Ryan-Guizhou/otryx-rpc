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

The project has not yet reached GA and makes no backward-compatibility promise to unpublished development snapshots for Java APIs, SPI, Wire, Schema or configuration. Current-version protocol correctness, interoperability and security still require verification. Spring Starter, Core Client/Server builders and RegistryOptions all require an explicit security profile: `TRUSTED_DEVELOPMENT` for trusted isolated development or `NETWORK_MTLS` for network deployments. The default `UNSPECIFIED` profile rejects startup. Network mode requires actual mTLS and application bindings, secure codecs, protected Registry channels and finite method grants. See the [Java coding standard](docs/engineering/java-coding-standard.md).

<!-- doc-section:capabilities -->
## Core capabilities

- **Unary RPC**: Generated Stubs / Dispatchers with JDK Proxy / MethodHandle fallback.
- **Transport**: Vert.x TCP, sharded connections, heartbeat, reconnect, CANCEL, GO_AWAY and graceful drain.
- **Security**: explicit deployment profiles, certificates bound to full application identities, hostname verification, certificate reload, finite method grants and actual SPI capability checks.
- **Registry**: Memory, Etcd, Nacos, Consul, Eureka; the consumer hot path reads a locally verified directory. Network security support depends on the actual adapter.
- **Routing / load balancing**: application-aware Router SPI, versioned local rules, application priority, labels, zones, deployment releases and percentage-based canary routing, followed by P2C + EWMA. Routing labels never grant authorization.
- **Resilience**: method/per-call timeouts, inherited monotonic deadline budgets, explicitly idempotent retries, retry budget, circuit breaker, outlier ejection and provider admission.
- **Execution / bounds**: virtual threads, bounded CPU resources, pre-TLS admission with idempotent cleanup, plus a bounded Provider encoding output buffer. Fory and stable type IDs.
- **Observability**: Micrometer, OpenTelemetry, JFR and Grafana / Prometheus examples.
- **Engineering**: JMH, 10k logical-concurrency soak harness, chaos and independent-process tests.

<!-- doc-section:architecture -->
## Architecture

![OTRYX system architecture](docs/images/architecture/system-overview.svg)

![OTRYX RPC call flow](docs/images/flows/rpc-lifecycle.svg)

**Design**: control-plane/data-plane isolation; SPI and adapters for implementations; no registry I/O, SPI scan or dynamic parsing per request.

**Application registry (#139 completed):** [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) was merged into main at `783bf8115c9523e5667023678dafe659315b2b66`, and that merge commit passed its main gates. Core, the single Starter and the four Quickstart modules use Application identity, shared Provider/Consumer/BOTH membership, immutable Manifests, instance-owner CAS Mapping, a Resolver and bounded caches. Consumer-only members register without RPC endpoints; ServiceKey identifies business contracts, and ServiceInstance is a local load-balancing candidate carrying the target application. Transitional SPI, registry paths and configuration have been removed. [Design](docs/design/application-registry-v2.md) · [Acceptance and measurement boundaries](docs/reference/application-registry-acceptance.md).

**Security contracts and acceptance (#140):** this document describes explicit security profiles, verified application identity and protected control-plane entrypoints. Verify the complete acceptance scope against the exact PR Head, post-merge main and raw evidence recorded in [#140](https://github.com/Ryan-Guizhou/otryx-rpc/issues/140) and the engineering checkpoint.

[Application routing SPI and rules](docs/reference/routing.md): the default router performs no filtering; versioned snapshots are local, and custom routers may return only members of the already verified Provider candidate set. No registry I/O occurs on the call path.

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

Start with the **no-Docker single-JVM Memory Quickstart**, then explore shared Registry membership and verified routes, the BOTH role and deadlines in A→B→C, and promotion/rollback between two real export revisions of one application. The separate mTLS route uses protected Etcd to demonstrate certificates bound to a full ApplicationKey, certificate rotation and finite method grants. See the [Quickstart tutorial](otryx-quickstart/README.en-US.md) for commands, JSON fields and expected outcomes, or [Getting started](docs/getting-started.md) for a shorter path.

After building, use **Linux** and prepare local Nacos for the default process/chain/revision scenarios, plus Bash, Python 3, GNU `timeout`, OpenSSL and an Etcd **3.5.21** executable, then run all scenarios in order. Linux is required by the mTLS security runner and this aggregate entrypoint:

~~~bash
ETCD_BINARY=/absolute/path/to/etcd \
  NACOS_TEST_ENDPOINT=127.0.0.1:8848 bash scripts/run_quickstart_e2e.sh
~~~

The default process, chain and revision scenarios each bootstrap a separate Nacos group. They also support real Etcd, Consul and Eureka through `QUICKSTART_REGISTRY` and `QUICKSTART_REGISTRY_ENDPOINT`; Eureka additionally requires `QUICKSTART_EUREKA_METADATA_ENDPOINT` for real Consul metadata. The mTLS script starts and cleans up its own loopback Etcd TLS/RBAC instance, with separate RPC/Registry CAs and credentials for each role; it does not need Nacos. Allowlisted JSON and `summary.log` are saved under `target/quickstart-e2e/`, without temporary private keys or credentials. Execution records establish only the actual coverage of the tested commit; complete acceptance requires checking each real scenario and CI for that exact commit. See [application acceptance](docs/reference/application-registry-acceptance.md) and the [Starter guide](docs/reference/starter.md) for parameters and assembly.

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

`NETWORK_MTLS` binds the actual TLS leaf certificate SHA-256 to a complete `ApplicationKey(namespace, applicationName)` and finite expiry. Application policy then checks the source application, ServiceKey and MethodId and returns `RpcAuthorizationDecision.allowUntil(Instant)` or `deny()`. A grant cannot outlive the certificate, deployment binding, request budget or server grant limit. CN/SAN, request Metadata and Registry attributes cannot substitute for an application binding.

With network mode and `server.enabled=true`, a missing explicit `RpcAuthorizer` bean rejects startup before the Registry factory or RPC runtime is created; a pure Consumer should set `server.enabled=false`. Core Server builders enforce the same missing-policy rejection. Starter also validates the final actual capabilities of both enabled Client/Server builders before either role starts. Default Fory uses `STRICT_ALLOWLIST`; custom codecs are checked for contract types, validation before decoding and finite capacity limits. See [Starter security configuration](docs/reference/starter.md) and the [security guide](docs/security.md).

Implemented application-level backends are documented in [Etcd](docs/reference/registry-etcd.md), [Nacos V2](docs/reference/registry-nacos-v2.md), [Consul V2](docs/reference/registry-consul-v2.en-US.md) and [Eureka V2](docs/reference/registry-eureka-v2.en-US.md). In network mode, Etcd uses an explicit CA and username/password, Consul uses HTTPS and an ACL token with one local Agent, and Eureka configures its membership and external metadata connections separately. Spring carries typed TLS/credentials in `registry.security` and independent `registry.metadata-security`; a protected source cannot mask a weak source in the same channel. The current Nacos adapter rejects NETWORK/TLS intent before creating its SDK and remains available for trusted development; Eureka therefore cannot use Nacos metadata in network mode. Starter retains one application-level registry path.

Initialize Nacos V2 Mapping shards before starting any application, in an exclusive maintenance window for the same namespace/group. Quickstart provides a standalone initialization command; its independent-process verification scripts perform this step for a separate group per run. See the [Nacos startup steps](otryx-quickstart/README.en-US.md#2-nacos-two-independent-jvms).

Nacos Manifest/Mapping reads accept only remote query results and their authoritative MD5. SDK local snapshots and failover files cannot become fresh successful snapshots or CAS inputs. Ordinary remote failures remain unavailable, subject to Core's existing finite LKG budget; actual authentication, authorization or integrity rejections still invalidate old security snapshots immediately. See the [Nacos metadata boundaries](docs/reference/registry-nacos-v2.md).

See [Starter guide](docs/reference/starter.md) and [Production configuration](docs/configuration.md) for validated options, TLS and registry setup. The [Deadline and call-context reference](docs/reference/deadline-context.md) covers precedence, explicit scope propagation and A→B→C budget semantics (Chinese).

<!-- doc-section:operations -->
## Performance and operations

Validate deadline, admission, backpressure, retries, TLS and observability against your actual workloads. Shared CI-runner smoke results are not production throughput, p99 or capacity claims.

[Performance evidence](docs/reference/performance-evidence.md) · [Capacity planning](docs/reference/capacity-planning.md) · [Observability](docs/observability.md)

Production `NETWORK_MTLS` Providers require `RpcBoundedResultMethodCodec` during method binding, rejecting the default full-array result adapter. Fory writes to a bounded OutputStream; final Wire Frame construction also reserves a temporary frame budget. Trusted development mode retains the legacy adapter. This is not a global JVM heap/direct-memory bound; see [Transport resource ownership](docs/reference/transport-admission.md).

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

Core and the Spring Starter both default to `otryx.rpc.client.directory-max-staleness=30s` and require a strictly positive finite budget. Ordinary Registry synchronization failures may use the last valid directory within that budget; a quiet Watch alone is not unhealthy. Authentication, authorization, TLS or metadata-integrity rejection immediately invalidates the previous verification epoch. Before each send, a local guard checks the same candidate that supplied the selected endpoint. Security failures are not retried, counted as endpoint failures or used to update latency EWMA. See [directory freshness](docs/reference/registry-freshness.md) and [current security boundaries](docs/reference/starter.md).

A successful refresh retires the previous candidate even when its public contents are unchanged. If the Resolver has committed a new healthy candidate while the directory publication callback is still queued, an initialized directory can adopt that exact candidate's endpoint projection through one bounded local operation. The earlier selection remains retired, and a newly verified empty snapshot removes the previous providers. This operation preserves the original synchronization time, failure state and LKG deadline; the actual successful publication callback updates synchronization state. Repeated local reads cannot extend that deadline or turn a Resolver LKG candidate into a new successful synchronization. Initial readiness, expiry, closure and security rejection retain their existing checks.


## Optional Consumer / Provider Interceptor SPI

Configure an ordered `RpcInterceptor` chain. Consumer logical calls are distinct from individual retry attempts; Provider hooks run only after actual peer checks, service/method authorization and business admission. With no configured hooks, no dedicated worker is created and the existing hot path remains unchanged. Enabled hooks use bounded workers, per-call admission and serialized lifecycle events. Timeout or cancellation never treats still-running hook work as completed.

[Interceptor lifecycle and safety contract](docs/reference/interceptors.md) · [Quickstart configuration](otryx-quickstart/README.en-US.md).


## Signed dynamic governance (V2-17)

Optional `RpcGovernanceRuntime` consumes complete signed governance snapshots on a bounded control executor and atomically switches timeout, tag routing, application weighting, circuit and concurrency limits. Each Consumer logical call pins one revision; the Provider's original admission and mTLS/auth/Codec security always take precedence. With no configured repository/verifier, there is no dynamic-governance overhead. Once enabled, calls fail closed until the first valid signed snapshot arrives. See [Governance and security contract](docs/reference/governance.md).
