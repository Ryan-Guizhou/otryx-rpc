# OTRYX RPC Quickstart

> **当前源码**：1.0.0-SNAPSHOT、JDK 21、Spring Boot 3.5.4、Wire v1。尚未发布到 Maven Central，使用前先从本仓库编译。以下安全配置中的 TRUSTED_DEVELOPMENT **仅限本地或隔离环境**。

本目录是 OTRYX RPC 的**可运行入门工程**：不只展示代码片段，而是以真实的接口、Provider、Consumer、序列化与 Vert.x TCP 调用帮助你完成从首次运行到跨服务通信的学习。**只需依赖一个 \`otryx-spring-boot-starter\`**；Nacos/Consul/Eureka 等 Adapter 由具体应用显式选用。

## 学习路径与模块

| 顺序 | 模块 | 运行方式 | 必备基础设施 | 学习目标 |
|---|---|---|---|---|
| 1 | \`otryx-quickstart-memory\` | **一个 JVM** 内运行 Provider 和 Consumer | 无 | 最快跑通完整 RPC 调用 |
| 2 | \`otryx-quickstart-api\` | 共享 Contract / DTO 编译模块 | 无 | 接口契约、Generated Stub |
| 3 | \`otryx-quickstart-provider\` + \`otryx-quickstart-consumer\` | 两个 JVM | Nacos | 服务注册、发现、代理调用 |
| 4 | 同上 + Relay Provider | **三个 JVM** | Nacos | A → B → C Deadline 和 Metadata |
| 5 | 可选 Profiles | 多 JVM | Consul/Eureka、真实 CA | 替换 Registry、TLS/mTLS 安全边界 |

\`\`\`mermaid
flowchart LR
    A[Quickstart API] --> M[Memory: Provider + Consumer]
    A --> P[Provider JVM]
    A --> C[Consumer JVM]
    P --> N[(Nacos / Consul / Eureka)]
    C --> N
    C -->|Unary RPC / Vert.x TCP| P
    M -->|Memory Registry / loopback| M
\`\`\`

**注意**：Memory Registry 仅在单个 JVM 内共享，不提供跨进程发现。两个独立 JVM 的学习路径必须先运行 Nacos 或其他共享注册中心。

## 0. 准备工作

在仓库根目录运行所有命令：

- JDK **21**，确认 \`java -version\`；
- 仓库自带 Maven Wrapper（固定 Maven 3.9.11），Linux/macOS 使用 \`./mvnw\`，Windows 使用 \`mvnw.cmd\`；
- 首次下载 Maven/依赖需要 Maven Central 网络访问；
- 仅进行 Nacos、Consul 或 Eureka 双进程路径时才需要对应外部服务；Memory 路径不需要 Docker。

\`\`\`bash
git clone https://github.com/Ryan-Guizhou/otryx-rpc.git
cd otryx-rpc
java -version
./mvnw --version
./mvnw -B -ntp -pl otryx-quickstart -am clean package
\`\`\`

**Windows**：将上述 \`./mvnw\` 改为 \`mvnw.cmd\`；下面以 Linux/macOS bash 命令为主，Windows 可用两个 CMD/PowerShell 终端启动对应确切 JAR 文件名。

## 1. 第一条调用：无 Nacos 的 Memory Quickstart

\`\`\`bash
# 可直接使用验证脚本（负责启动、校验返回值、关闭子进程）
bash scripts/run_quickstart_memory.sh
\`\`\`

或自行启动 JAR（从仓库根目录运行；之后 Ctrl+C 退出）：

\`\`\`bash
java -jar otryx-quickstart/otryx-quickstart-memory/target/otryx-quickstart-memory-1.0.0-SNAPSHOT-exec.jar
\`\`\`

输出中应出现：

\`\`\`text
Quickstart memory RPC succeeded: Hello, Quickstart!
\`\`\`

该应用的完整调用路径是：\`MemoryGreetingRunner\` 中的 \`@OtryxRpcReference\` → Consumer（JDK Proxy，优先 Generated Stub）→ Fory 编码 → Vert.x loopback → \`MemoryGreetingServiceImpl\` → Response。只运行在单进程、回环地址、动态端口，**不代表网络环境安全配置已经完成**。

主要源码：

- [Memory Provider](otryx-quickstart-memory/src/main/java/com/peachsoft/otryx/quickstart/memory/MemoryGreetingServiceImpl.java)
- [Memory Consumer](otryx-quickstart-memory/src/main/java/com/peachsoft/otryx/quickstart/memory/MemoryGreetingRunner.java)
- [Memory 配置](otryx-quickstart-memory/src/main/resources/application.yml)
- [公共 GreetingService](otryx-quickstart-api/src/main/java/com/peachsoft/otryx/quickstart/api/GreetingService.java)

## 2. 第二条调用：Nacos 双独立 JVM

### 2.1 启动注册中心

\`\`\`bash
docker compose -f otryx-quickstart/docker-compose.yml up -d
\`\`\`

确认 Nacos 本地 TCP \`127.0.0.1:8848\` 可达。Quickstart Provider 和 Consumer 的 \`application.yml\` 均配置了 \`registry.type=nacos\`，并在各自 Maven POM 中**显式引入** \`otryx-registry-nacos\`。单一 Starter 不会偷偷引入未使用的注册中心。

### 2.2 启动 Provider（终端 A）

\`\`\`bash
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar
\`\`\`

预期日志包含类似：

\`\`\`text
OTRYX RPC server started: bind=0.0.0.0:19090, advertised=127.0.0.1:19090
\`\`\`

### 2.3 启动 Consumer（终端 B）

\`\`\`bash
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar
\`\`\`

预期：

\`\`\`text
RPC quickstart completed successfully: Hello, OTRYX RPC!
\`\`\`

Consumer 首次启动时会短暂重试尚未传播到本地目录的注册记录，但不会无条件吞掉业务或协议错误。Provider/Consumer 是**两个独立 JVM**；共享的只是 \`otryx-quickstart-api\` 里的接口、DTO 和 Contract 元数据。

\`\`\`mermaid
sequenceDiagram
    participant P as Provider JVM
    participant N as Nacos
    participant C as Consumer JVM
    P->>N: 注册 ServiceKey 和 Endpoint
    C->>N: 订阅服务目录
    N-->>C: Endpoint 快照
    C->>P: HELLO / HELLO_ACK
    C->>P: REQUEST (Fory)
    P-->>C: RESPONSE
\`\`\`

**自动化复验**（先保证 Nacos 启动）：

\`\`\`bash
OTRYX_RPC_E2E_RESTART_NACOS=false bash scripts/run_quickstart_process_e2e.sh
\`\`\`

这还会在单个 Consumer JVM 存活期间验证 Provider 停止与恢复。CI 在具备可重启的测试 Nacos 容器时可额外验证 Nacos 控制面恢复、注册恢复与端点更换。

## 3. 第三条调用：三个 JVM 的 Deadline 和 Metadata

继续保持 Nacos 运行，并从仓库根目录执行：

\`\`\`bash
bash scripts/run_three_process_deadline_e2e.sh
\`\`\`

脚本分别启动 Consumer A、Relay Provider B（19091）、Leaf Provider C（19090），并断言：

- Consumer 显式设置 5 秒本次调用 Deadline；
- Relay Provider 的本地 Client 默认 10 秒，但**不能覆盖**上游更短的剩余预算；
- \`tenant\` 和 \`relay\` 等 Metadata 随 A → B → C 调用透传；
- Leaf 校验剩余预算与来源 Metadata，Consumer 应输出以下 marker：

\`\`\`text
RPC deadline chain completed: CHAIN_OK remainingMillis=...
\`\`\`

失败时脚本会输出三个进程日志并清理子进程。这是真实通信验收，不是仅打印一段伪日志。

## 4. 理解需要复制到自己项目里的最小代码

共享 Contract 接口使用 \`@OtryxRpcContract\`，可通过 Maven Annotation Processor 生成 Stub/Dispatcher（当前 API 模块已经配置处理器）。没有生成代码时，会回退至 JDK Proxy / MethodHandle，不影响接口协议：

\`\`\`java
@OtryxRpcContract
public interface GreetingService {
    @OtryxRpcIdempotent
    GreetingReply hello(GreetingRequest request);
}
\`\`\`

Provider 声明 \`@OtryxRpcService(interfaceClass = GreetingService.class, version = "1.0.0")\`；Consumer 注入 \`@OtryxRpcReference(version = "1.0.0")\`。只有业务真正具备幂等语义时才应添加 \`@OtryxRpcIdempotent\`，尤其不要仅为了重试而将扣款、下单标记为幂等。

你的应用应只引入单一 Starter；需要 Nacos 再明确添加 Adapter：

\`\`\`xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-registry-nacos</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
\`\`\`

其他可按需选择 \`otryx-registry-etcd\`、\`otryx-registry-consul\`、\`otryx-registry-eureka\`、\`otryx-proxy-cglib\`、\`otryx-proxy-bytebuddy\` 或 Observability Adapter；无需重新换 Starter。完整属性与 SPI 覆盖见 [Starter 参考](../docs/reference/starter.md)。

## 5. 进一步验证 Registry：Consul / Eureka

Provider、Consumer 当前的 POM 已声明可选演示所需的 Consul/Eureka Adapter。先自己部署对应服务后，**两个进程使用同一 Profile**，不要尝试与 Memory Registry 跨 JVM 连接：

\`\`\`bash
# Provider 终端
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar --spring.profiles.active=consul
# Consumer 终端
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar --spring.profiles.active=consul
\`\`\`

把 \`consul\` 换成 \`eureka\` 即可选择 Eureka。认证通过 \`CONSUL_HTTP_TOKEN\` 或 \`EUREKA_USERNAME\`/\`EUREKA_PASSWORD\` 等环境变量传递，**不要提交真实密钥**。详见 [Consul](../docs/reference/registry-consul.md) 和 [Eureka](../docs/reference/registry-eureka.md)。

## 6. 安全运行：TLS / mTLS

Provider、Consumer 的 \`application-tls.yml\`、\`application-mtls.yml\` 都读取环境变量：

| 环境变量 | 用途 |
|---|---|
| \`OTRYX_RPC_TLS_CERT\` | 当前进程的 PEM 证书 |
| \`OTRYX_RPC_TLS_KEY\` | 对应私钥 |
| \`OTRYX_RPC_TLS_CA\` | 信任 CA 证书 |

使用 \`--spring.profiles.active=tls\` 或 \`--spring.profiles.active=mtls\` 启动。Consumer 默认执行 Hostname Verification，证书身份必须与目标地址匹配。mTLS Profile 会启用 \`NETWORK_MTLS\`、严格 Fory DTO allowlist；**还必须提供基于验证过的客户端身份、ServiceKey、MethodId 的 \`RpcAuthorizer\` Bean**，否则网络 Profile 下 Provider 会默认拒绝业务调用。这里的 TLS/mTLS 文件是安全配置起点，不应在未配置真实证书与授权时误认为端到端生产接入已完成。

另见 [安全与授权](../docs/security.md)。

## 7. 观测、背压和生命周期

- \`QuickstartCallObserver\` 演示自定义 \`RpcObserver\` 的 Spring Bean 接入，按终态累计成功/失败次数，不读取 Payload；
- Micrometer、OpenTelemetry、JFR 是**按需添加**的 Adapter，不会作为 Starter 的强制依赖；
- 重试仅适用于幂等方法；达到容量上限时会快速拒绝而非阻塞 EventLoop；
- Provider/Consumer 的关闭、Drain、Deadline 均由真实运行时负责，见 [运行与排障](../docs/operations.md)、[Deadline](../docs/reference/deadline-context.md)。

## 8. 常见问题和排查顺序

| 现象 | 检查项 |
|---|---|
| \`exec.jar\` 找不到 | 先在**仓库根目录**运行 \`./mvnw -B -ntp -pl otryx-quickstart -am clean package\`；区分普通 JAR 与 \`-exec.jar\` |
| Maven 解析不了 OTRYX \`1.0.0-SNAPSHOT\` | 尚未发布 Central；先本地运行 \`./mvnw -B -ntp clean install -DskipTests\` |
| 无法连接 Nacos | 检查 \`docker compose ... up -d\`、8848/9848、Registry type、namespace 与 Provider/Consumer 配置是否一致 |
| Memory 模式双进程互相看不到 | Memory Registry **不跨 JVM**；使用 Nacos / Consul / Eureka 等共享注册中心 |
| \`NETWORK_MTLS\` 拒绝调用 | 检查 CA/证书/私钥、Hostname、严格 Fory 白名单和 \`RpcAuthorizer\` |
| RPC 偶发超时 | 检查 Provider 是否启动、服务目录是否完成同步、请求 Deadline、连接与背压指标 |
| 端口被占用 | Provider 默认 19090；多跳场景脚本使用 19090/19091；先停止先前的 Provider 进程 |

## 9. 完整验收与清理

\`\`\`bash
python3 scripts/test_quickstart_layout.py
./mvnw -B -ntp clean verify -Pquality
bash scripts/run_quickstart_memory.sh
# 下面两条命令需要本地 Nacos
OTRYX_RPC_E2E_RESTART_NACOS=false bash scripts/run_quickstart_process_e2e.sh
bash scripts/run_three_process_deadline_e2e.sh
docker compose -f otryx-quickstart/docker-compose.yml down
\`\`\`

初次验收完成后可阅读：[用户指南](../docs/user-guide.md) · [Starter 参考](../docs/reference/starter.md) · [架构设计](../docs/architecture.md) · [协议兼容](../docs/wire-compatibility.md)。固定硬件性能结论仍需要受控证据，短时 Quickstart 成功**不是**正式生产性能或 GA 发布认证。
