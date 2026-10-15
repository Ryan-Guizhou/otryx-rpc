# OTRYX RPC Quickstart

> **Source baseline**: 1.0.0-SNAPSHOT, JDK 21, Spring Boot 3.5.4, Wire v1. This build is not yet on Maven Central. \`TRUSTED_DEVELOPMENT\` is **only for isolated local environments**.

This is a **runnable learning project**, not a set of disconnected snippets. Start with a real single-JVM RPC round trip, then move to two independent processes, A → B → C deadlines, pluggable registries and transport security. Use one \`otryx-spring-boot-starter\`; Nacos and other adapters are explicit dependencies.

## Module map and learning paths

| Step | Module(s) | Process model | Infrastructure | Goal |
|---|---|---|---|---|
| 1 | \`otryx-quickstart-memory\` | Provider + Consumer in **one JVM** | None | Real RPC over loopback |
| 2 | \`otryx-quickstart-api\` | Shared contract / DTO module | None | Interface, generated stub |
| 3 | \`otryx-quickstart-provider\` + \`otryx-quickstart-consumer\` | Two JVMs | Nacos | Registry, discovery, RPC |
| 4 | Consumer + Relay + Leaf | Three JVMs | Nacos | Deadline and metadata propagation |
| 5 | Optional profiles | Separate JVMs | Consul/Eureka/PKI | Registry alternatives and TLS/mTLS |

\`\`\`mermaid
flowchart LR
    API[Quickstart Contract] --> Memory[Single-JVM Memory RPC]
    API --> Provider[Provider JVM]
    API --> Consumer[Consumer JVM]
    Provider --> Nacos[(Shared Registry)]
    Consumer --> Nacos
    Consumer -->|Unary RPC / Vert.x| Provider
\`\`\`

**Memory Registry is strictly in-process.** Use Nacos, Consul, Eureka or another shared registry for independent JVMs.

## Prerequisites and build

From the **repository root**: JDK 21, Maven Wrapper (Maven 3.9.11), access to Maven Central for the first dependency download. Docker is required only for the Nacos walkthrough, not for the Memory Quickstart.

\`\`\`bash
git clone https://github.com/Ryan-Guizhou/otryx-rpc.git
cd otryx-rpc
java -version
./mvnw --version
./mvnw -B -ntp -pl otryx-quickstart -am clean package
\`\`\`

On Windows use \`mvnw.cmd\` instead of \`./mvnw\`. The shell snippets below target Linux/macOS; Windows can run the exact \`-exec.jar\` artifact paths from two terminals.

## 1. Zero-infrastructure Memory Quickstart

\`\`\`bash
bash scripts/run_quickstart_memory.sh
# Or launch and stop it manually with Ctrl+C:
java -jar otryx-quickstart/otryx-quickstart-memory/target/otryx-quickstart-memory-1.0.0-SNAPSHOT-exec.jar
\`\`\`

Expected log marker:

\`\`\`text
Quickstart memory RPC succeeded: Hello, Quickstart!
\`\`\`

\`MemoryGreetingRunner\` invokes \`@OtryxRpcReference\`, the Consumer encodes with Fory, Vert.x sends over loopback to \`MemoryGreetingServiceImpl\`, and the response is decoded. This tests a **real** RPC round trip. It does not make the development security profile suitable for an untrusted network.

Source: [Provider](otryx-quickstart-memory/src/main/java/com/peachsoft/otryx/quickstart/memory/MemoryGreetingServiceImpl.java) · [Consumer](otryx-quickstart-memory/src/main/java/com/peachsoft/otryx/quickstart/memory/MemoryGreetingRunner.java) · [Configuration](otryx-quickstart-memory/src/main/resources/application.yml).

## 2. Nacos: two independent JVMs

Start Nacos:

\`\`\`bash
docker compose -f otryx-quickstart/docker-compose.yml up -d
\`\`\`

Both applications explicitly depend on \`otryx-registry-nacos\` and configure \`registry.type=nacos\` in their respective \`application.yml\`. The single Starter deliberately does **not** transitively pull Nacos.

Provider terminal:

\`\`\`bash
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar
\`\`\`

Expected: \`OTRYX RPC server started: bind=0.0.0.0:19090, advertised=127.0.0.1:19090\`.

Consumer terminal:

\`\`\`bash
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar
\`\`\`

Expected: \`RPC quickstart completed successfully: Hello, OTRYX RPC!\`.

\`\`\`mermaid
sequenceDiagram
    participant P as Provider JVM
    participant N as Nacos
    participant C as Consumer JVM
    P->>N: Register ServiceKey + Endpoint
    C->>N: Subscribe directory
    N-->>C: Provider endpoint
    C->>P: HELLO / HELLO_ACK
    C->>P: REQUEST (Fory)
    P-->>C: RESPONSE
\`\`\`

Verify real Nacos/Provider recovery with the same Consumer process:

\`\`\`bash
OTRYX_RPC_E2E_RESTART_NACOS=false bash scripts/run_quickstart_process_e2e.sh
\`\`\`

The CI version can additionally restart the test Nacos container and verify subscription recovery and endpoint changes.

## 3. Three independent JVMs: A → B → C

With Nacos running:

\`\`\`bash
bash scripts/run_three_process_deadline_e2e.sh
\`\`\`

The script launches Consumer A, Relay Provider B (19091), and Leaf Provider C (19090). A sets a 5-second per-call deadline and metadata; B's local 10-second client timeout must not extend A's remaining budget; C validates the remaining deadline and propagated metadata.

Expected:

\`\`\`text
RPC deadline chain completed: CHAIN_OK remainingMillis=...
\`\`\`

The script validates real RPC and cleans up the child processes.

## 4. Use the single Starter in your application

The API module compiles a shared \`@OtryxRpcContract\` with an annotation processor. Generated Stub/Dispatcher is preferred; a JDK Proxy / MethodHandle fallback exists when generated code is absent:

\`\`\`java
@OtryxRpcContract
public interface GreetingService {
    @OtryxRpcIdempotent
    GreetingReply hello(GreetingRequest request);
}
\`\`\`

Provider: \`@OtryxRpcService(interfaceClass = GreetingService.class, version = "1.0.0")\`.

Consumer: \`@OtryxRpcReference(version = "1.0.0")\`.

Only declare \`@OtryxRpcIdempotent\` for truly idempotent operations, not blindly for payments or order creation.

\`\`\`xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- Only when you need Nacos: -->
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-registry-nacos</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
\`\`\`

Other opt-in adapters include Etcd, Consul, Eureka, CGLIB, ByteBuddy, Micrometer, OpenTelemetry and JFR. The Starter entrypoint does not change. See the [Starter reference](../docs/reference/starter.md).

## 5. Consul / Eureka profiles

The Provider and Consumer build files include the optional Consul/Eureka adapters required by these demonstrations. Start your own Consul agent or Eureka server, then apply the **same** Spring profile to both JVMs:

\`\`\`bash
# Provider terminal:
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar --spring.profiles.active=consul
# Consumer terminal:
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar --spring.profiles.active=consul
\`\`\`

Use \`eureka\` instead of \`consul\` for Eureka. Credentials belong in environment variables such as \`CONSUL_HTTP_TOKEN\`, \`EUREKA_USERNAME\`, and \`EUREKA_PASSWORD\`, never in source control. See [Consul](../docs/reference/registry-consul.md) and [Eureka](../docs/reference/registry-eureka.md).

## 6. TLS / mTLS safety boundaries

The optional \`application-tls.yml\` and \`application-mtls.yml\` profiles use \`OTRYX_RPC_TLS_CERT\`, \`OTRYX_RPC_TLS_KEY\`, and \`OTRYX_RPC_TLS_CA\` for PEM credentials. Enable with \`--spring.profiles.active=tls\` or \`mtls\` and provision matching CA/hostname identities.

The mTLS profile selects \`NETWORK_MTLS\` with a strict Fory DTO allowlist. **This is not sufficient for an authorized business RPC call**: Provider must also expose an \`RpcAuthorizer\` validating authenticated client identity, ServiceKey and MethodId. Without it, network-profile calls are rejected by default. Read the [security guide](../docs/security.md) before network exposure.

## 7. Observability, capacity and lifecycle

\`QuickstartCallObserver\` is a Spring \`RpcObserver\` that counts success/failure outcomes without reading sensitive payloads. Add Micrometer, OpenTelemetry or JFR adapters only when needed. Calls can be rejected under admission/backpressure; retries are limited to explicitly idempotent methods. Provider drain and Consumer closure are managed by the runtime. See [Operations](../docs/operations.md) and [Deadlines](../docs/reference/deadline-context.md).

## 8. Troubleshooting

| Symptom | Checks |
|---|---|
| \`-exec.jar\` not found | Run \`./mvnw -B -ntp -pl otryx-quickstart -am clean package\` from repo root |
| Maven cannot resolve \`1.0.0-SNAPSHOT\` | Not published: run \`./mvnw -B -ntp clean install -DskipTests\` first |
| Nacos endpoint unavailable | Compose service, 8848/9848, matching namespace/configuration |
| Memory mode cannot discover remote JVM | Memory Registry is not shared across processes |
| \`NETWORK_MTLS\` rejects requests | CA/keys/hostname, Fory allowlist and \`RpcAuthorizer\` |
| Calls timeout | Provider readiness, discovery, deadline, overload and client timeout |
| Port collision | Provider normally uses 19090; the relay scenario uses 19090/19091 |

## 9. Validation and cleanup

\`\`\`bash
python3 scripts/test_quickstart_layout.py
./mvnw -B -ntp clean verify -Pquality
bash scripts/run_quickstart_memory.sh
# These require a running local Nacos:
OTRYX_RPC_E2E_RESTART_NACOS=false bash scripts/run_quickstart_process_e2e.sh
bash scripts/run_three_process_deadline_e2e.sh
docker compose -f otryx-quickstart/docker-compose.yml down
\`\`\`

Read next: [User guide](../docs/user-guide.md) · [Starter reference](../docs/reference/starter.md) · [Architecture](../docs/architecture.md) · [Protocol compatibility](../docs/wire-compatibility.md). A short Quickstart run is **not** a production performance SLO or GA certification.
