# OTRYX RPC Quickstart

[简体中文](README.md) | [English](README.en-US.md)

> **当前源码**：1.0.0-SNAPSHOT、JDK 21、Spring Boot 3.5.4、Wire v1。尚未发布到 Maven Central，使用前先从本仓库编译。以下安全配置中的 TRUSTED_DEVELOPMENT **仅限本地或隔离环境**。

本目录是 OTRYX RPC 的**可运行入门工程**：不只展示代码片段，而是以真实的接口、Provider、Consumer、序列化与 Vert.x TCP 调用帮助你完成从首次运行到跨服务通信的学习。**只需依赖一个 `otryx-spring-boot-starter`**；Nacos/Consul/Eureka 等 Adapter 由具体应用显式选用。

<!-- doc-section:learning-path -->
## 学习路径与模块

| 顺序 | 模块 | 运行方式 | 必备基础设施 | 学习目标 |
|---|---|---|---|---|
| 1 | `otryx-quickstart-memory` | **一个 JVM** 内运行 Provider 和 Consumer | 无 | 最快跑通完整 RPC 调用 |
| 2 | `otryx-quickstart-api` | 共享 Contract / DTO 编译模块 | 无 | 接口契约、Generated Stub |
| 3 | `otryx-quickstart-provider` + `otryx-quickstart-consumer` | 两个 JVM | Nacos | 应用成员、Manifest、Mapping 与已验证路由 |
| 4 | 同上 + Relay Provider | **三个 JVM** | Nacos | Consumer / BOTH / Provider，A → B → C Deadline |
| 5 | 同一 Provider JAR 的两个导出集合 | 多 JVM | Nacos | 两个真实 Manifest revision 共存、升级、回滚 |
| 6 | 现有 Provider / Consumer 的 `mtls` Profile | 多 JVM | Nacos、OpenSSL | 证书认证、客户端指纹与方法授权 |

```mermaid
flowchart LR
    A[Quickstart API] --> M["Memory: Provider + Consumer"]
    A --> P[Provider JVM]
    A --> C[Consumer JVM]
    P --> N[(Nacos / Consul / Eureka)]
    C --> N
    C -->|Unary RPC / Vert.x TCP| P
    M -->|Memory Registry / loopback| M
```

**注意**：Memory Registry 仅在单个 JVM 内共享，不提供跨进程发现。两个独立 JVM 的学习路径必须先运行 Nacos 或其他共享注册中心。

<!-- doc-section:prerequisites -->
## 0. 准备工作

在仓库根目录运行所有命令：

- JDK **21**，确认 `java -version`；
- 仓库自带 Maven Wrapper（固定 Maven 3.9.11），Linux/macOS 使用 `./mvnw`，Windows 使用 `mvnw.cmd`；
- 首次下载 Maven/依赖需要 Maven Central 网络访问；
- 脚本需要 Bash、Python 3 和 GNU coreutils 的 `timeout`；mTLS 场景还需要 OpenSSL。macOS 需把 GNU `timeout` 配置到 `PATH`；
- 跨进程路径需要已运行的共享 Nacos；可使用下文 Docker Compose，也可使用自行部署的本地 Nacos。Memory 路径不需要 Docker。

```bash
git clone https://github.com/Ryan-Guizhou/otryx-rpc.git
cd otryx-rpc
java -version
./mvnw --version
./mvnw -B -ntp -pl otryx-quickstart -am clean package
```

**Windows**：将上述 `./mvnw` 改为 `mvnw.cmd`；下面以 Linux/macOS bash 命令为主，Windows 可用两个 CMD/PowerShell 终端启动对应确切 JAR 文件名。

### 已构建 JAR 后，一条命令运行全部场景

先按第 2.1 节准备 Nacos，再执行：

```bash
NACOS_TEST_ENDPOINT=127.0.0.1:8848 bash scripts/run_quickstart_e2e.sh
```

入口依次运行 Memory、双进程恢复、三进程 Deadline、双 revision、mTLS 授权，任一阶段失败即停止。它使用现有四个模块的已构建 JAR，不代替 Maven 构建或启动 Nacos。每个跨进程场景创建独立 Nacos group，先初始化分片，再把同一 scope 传给所有参与 JVM。

四个 Nacos 场景的证据保存在 `target/quickstart-e2e/<scenario>.<随机后缀>/`，包含受限字段的 JSON 快照和 `summary.log`；Memory 成功标记保留在控制台输出。可用 `OTRYX_QUICKSTART_EVIDENCE_DIR` 改变证据根目录。JSON 的中间尝试可能尚未收敛，以 `summary.log` 中 `Registry snapshot verified` 指向的文件作为该阶段断言通过的证据。mTLS 临时 PKI 在证据目录之外生成并清理，私钥不进入证据。详细步骤和每个阶段应满足的行为见下文；列出命令不表示当前工作树已经通过该次执行。

<!-- doc-section:memory -->
## 1. 第一条调用：无 Nacos 的 Memory Quickstart

```bash
# 可直接使用验证脚本（负责启动、校验返回值、关闭子进程）
bash scripts/run_quickstart_memory.sh
```

或自行启动 JAR（从仓库根目录运行；之后 Ctrl+C 退出）：

```bash
java -jar otryx-quickstart/otryx-quickstart-memory/target/otryx-quickstart-memory-1.0.0-SNAPSHOT-exec.jar
```

脚本成功时向标准输出打印 `Memory Quickstart: PASS (real RPC, no external registry)`。手工运行 JAR 时，应用日志中应出现以下标记；脚本则在临时应用日志中核验同一标记：

```text
Quickstart memory RPC succeeded: Hello, Quickstart!
```

该应用的完整调用路径是：`MemoryGreetingRunner` 中的 `@OtryxRpcReference` → Consumer（JDK Proxy，优先 Generated Stub）→ Fory 编码 → Vert.x loopback → `MemoryGreetingServiceImpl` → Response。只运行在单进程、回环地址、动态端口，**不代表网络环境安全配置已经完成**。

应用成员入册后，Provider 路由仍需异步刷新。Memory 演示对已声明幂等的 `hello` 在最多 10 秒的发现预算内重试明确的不可用结果；已有父调用 Deadline 时使用更短预算，每次 RPC 也受剩余时间约束。业务、协议和响应校验失败会立即终止，线程中断会保留中断标记并退出等待。

主要源码：

- [Memory Provider](otryx-quickstart-memory/src/main/java/com/peachsoft/otryx/quickstart/memory/MemoryGreetingServiceImpl.java)
- [Memory Consumer](otryx-quickstart-memory/src/main/java/com/peachsoft/otryx/quickstart/memory/MemoryGreetingRunner.java)
- [Memory 配置](otryx-quickstart-memory/src/main/resources/application.yml)
- [公共 GreetingService](otryx-quickstart-api/src/main/java/com/peachsoft/otryx/quickstart/api/GreetingService.java)

<!-- doc-section:nacos -->
## 2. 第二条调用：Nacos 双独立 JVM

### 2.1 启动注册中心

```bash
docker compose -f otryx-quickstart/docker-compose.yml up -d
```

确认 Nacos 本地 TCP `127.0.0.1:8848` 可达。Quickstart Provider 和 Consumer 的 `application.yml` 均配置了 `registry.type=nacos`，并在各自 Maven POM 中**显式引入** `otryx-registry-nacos`。单一 Starter 不会偷偷引入未使用的注册中心。

**在启动任何 Provider/Consumer 前初始化 Nacos V2 Mapping 分片。** 等待 Nacos 的 HTTP `8848` 和客户端 gRPC `9848` 端口就绪，然后在该 namespace/group 没有业务进程写入的独占维护窗口执行：

```bash
bash scripts/bootstrap_quickstart_nacos.sh \
  otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar \
  127.0.0.1:8848 public OTRYX_RPC
```

该命令通过独立 JVM 调用已有 `NacosV2MetadataBootstrap`，与后续示例使用相同的 endpoint、namespace 和 Nacos group。它只创建缺失分片并校验、保留已有内容；初始化失败时先解决错误，再启动应用。普通 Starter 启动不会代替这一步，也不能与业务 Mapping 写入并发执行。参数和 CAS 约束见 [Nacos V2 文档](../docs/reference/registry-nacos-v2.md#4-nacos-config-servicemappingmd5-casfencing分片)。

手工初始化命令有 120 秒执行预算和 5 秒强制退出宽限。自动化场景使用各自随机 group，已经包含这一步；不要为了运行自动脚本而初始化正在被其他应用写入的共享 group。

### 2.2 启动 Provider（终端 A）

```bash
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar
```

预期日志包含类似：

```text
OTRYX RPC server started: bind=0.0.0.0:19090, advertised=127.0.0.1:19090
```

### 2.3 启动 Consumer（终端 B）

```bash
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar
```

预期：

```text
RPC quickstart completed successfully: Hello, OTRYX RPC!
```

Consumer 首次启动时会短暂重试尚未传播到本地目录的注册记录，但不会无条件吞掉业务或协议错误。Provider/Consumer 是**两个独立 JVM**；共享的只是 `otryx-quickstart-api` 里的接口、DTO 和 Contract 元数据。

```mermaid
sequenceDiagram
    participant P as Provider JVM
    participant N as Nacos
    participant C as Consumer JVM
    P->>N: 写入 Manifest、Mapping 和 Provider 成员
    C->>N: 注册 Consumer 成员（无 RPC Endpoint）
    C->>N: 读取 Mapping、应用成员和 Manifest
    N-->>C: 候选数据
    C->>C: 验证角色、契约及 Endpoint
    C->>P: HELLO / HELLO_ACK
    C->>P: REQUEST (Fory)
    P-->>C: RESPONSE
```

### 2.4 观察真实成员、Manifest 与 Mapping

保持两个 JVM 运行，在第三个终端创建一个新的输出文件路径：

```bash
mkdir -p target/quickstart-e2e
snapshot_dir=$(mktemp -d target/quickstart-e2e/manual.XXXXXXXX)
bash scripts/observe_quickstart_nacos.sh \
  otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar \
  127.0.0.1:8848 public OTRYX_RPC public "$snapshot_dir/members.json" \
  otryx-quickstart-provider otryx-quickstart-consumer
```

参数顺序为 **Provider 可执行 JAR、endpoint、Nacos namespace、Nacos group、application namespace、新 JSON 文件、1–16 个 applicationName**。当前 Starter 将 `otryx.rpc.registry.namespace` 同时用于后端配置和 `ApplicationKey.namespace`；本教程明确设置 `public`，所以两处均传 `public`，不存在独立的 Spring application namespace 配置。

不要混淆以下身份：

| 维度 | 手工示例值 | 作用 |
|---|---|---|
| `ApplicationKey` | `public` + `otryx-quickstart-provider` 或 `otryx-quickstart-consumer` | 应用成员归属 |
| Nacos group | `OTRYX_RPC` | 隔离本轮 Naming / Config 控制面数据 |
| `ServiceKey` | 接口全名 + `1.0.0` + `default` | 业务契约与路由目标；不是注册实例主键 |
| `instanceId` | 每次启动生成的实例 ID | 同一应用内区分进程 |
| Manifest revision | 由实际导出契约内容计算 | 标识不可变导出集合；不是手写发布版本号 |

JSON 中应重点检查：

| 字段 | 应看到什么 |
|---|---|
| `applications[].members` | Provider 为 `PROVIDER`，Consumer 为 `CONSUMER`；各自是真实成员 |
| Consumer 的 `rpcEndpoint` / `manifestRevision` / `weight` | `null` / `null` / `0`，不捏造监听端口 |
| `applications[].manifests[].services` | 导出的 ServiceKey、serviceId、methodIds、schemaFingerprint |
| `services[].mappedApplications` | ServiceMapping 返回的候选应用 |
| `services[].verifiedProviders` | 经过成员角色、Endpoint、Manifest 与契约校验的 Provider；纯 Consumer 永远不在此列表 |

观察器的成员与 Manifest 视图按明确指定的应用读取；服务视图通过真实 Mapping/Resolver 查询三个固定契约（Greeting、RelayGreeting、GreetingDetails），可能包含同一 scope 中其他导出这些契约的应用。它不枚举整个注册中心，不注册自己的成员，不导出任意实例 attributes。所有读取成功后才写新文件，并拒绝覆盖已有文件；每次观察都应换新文件名。Naming 与 Config 的读取**不是跨存储事务快照**，变更期间需等待收敛；读取失败不能解释为空目录。源码见 [QuickstartRegistrySnapshot](otryx-quickstart-provider/src/main/java/com/peachsoft/otryx/quickstart/provider/QuickstartRegistrySnapshot.java)。

### 2.5 自动复验停止与恢复

先停止上面手工启动的 JVM、释放端口，再执行：

```bash
OTRYX_RPC_E2E_RESTART_NACOS=false bash scripts/run_quickstart_process_e2e.sh
```

脚本的应用名是 `otryx-quickstart-process-provider` 和 `otryx-quickstart-process-consumer`，与手工演示使用不同的隔离 scope。它在单个 Consumer JVM 存活期间观察 `initial`、`provider-stopped` 和 `provider-restarted`。停止 Provider 后，Consumer 成员仍存在，但 Greeting 的已验证 Provider 应为空；恢复后同一 Consumer 应重新完成 RPC。

在拥有且允许重启的本地测试容器环境，可设置 `OTRYX_RPC_E2E_RESTART_NACOS=true`，再验证 `registry-recovered`、新 Consumer 的发现和 `endpoint-changed`（19090 → 19091）。此扩展要求唯一一个 `nacos/nacos-server:v3.2.4` 容器与 `127.0.0.1:8848` endpoint。该开关默认跟随 `CI`；使用共享或非容器 Nacos 时明确设为 `false`，统一入口也会继承这个选择。

双进程、三进程和滚动验证脚本分别使用本轮独立 Nacos group，在首个应用启动前完成分片初始化，并把同一 scope 传给所有启动、重启和新 Consumer 探测进程。

<!-- doc-section:chain -->
## 3. 第三条调用：三个 JVM 的 Deadline 和 Metadata

继续保持 Nacos 运行，并从仓库根目录执行：

```bash
bash scripts/run_three_process_deadline_e2e.sh
```

脚本分别启动 Consumer A、Relay Provider B（19091）、Leaf Provider C（19090），并断言：

- Consumer 显式设置 5 秒本次调用 Deadline；
- Relay Provider 的本地 Client 默认 10 秒，但**不能覆盖**上游更短的剩余预算；
- `tenant` 和 `relay` 等 Metadata 随 A → B → C 调用透传；
- 成员快照中 A 为无 Endpoint 的 `CONSUMER`，B 为一个带真实 Endpoint 的 `BOTH`，C 为 `PROVIDER`；B 不会因同时消费与导出而出现两条成员；
- Leaf 校验剩余预算与来源 Metadata，Consumer 应输出以下 marker：

```text
RPC deadline chain completed: CHAIN_OK remainingMillis=...
```

失败时脚本会输出三个进程日志并清理子进程。这是真实通信验收，不是仅打印一段伪日志。

应用名依次为 `otryx-quickstart-chain-consumer`、`otryx-quickstart-chain-relay`、`otryx-quickstart-chain-leaf`，阶段为 `complete`。

<!-- doc-section:revisions -->
## 4. 同一应用的两个真实 Manifest revision

```bash
bash scripts/run_quickstart_revision_e2e.sh
```

脚本复用**同一个新构建的 Provider JAR、相同 ApplicationName、相同 ServiceKey version/group**，只改变真实导出集合：基线导出 `GreetingService`；设置 `otryx.rpc.quickstart.details-enabled=true` 后额外装配 [GreetingDetailsServiceImpl](otryx-quickstart-provider/src/main/java/com/peachsoft/otryx/quickstart/provider/GreetingDetailsServiceImpl.java)，导出 `GreetingDetailsService`。新增契约的幂等方法 `details(GreetingRequest)` 返回 `GreetingReply("DETAILS_OK")`。

Provider 应用名为 `otryx-quickstart-revision-provider`，实例 `baseline` / `extended` 使用 19090 / 19091；长期 Consumer 为 `otryx-quickstart-revision-consumer` / `consumer-main`。导出集合变化产生不同 Manifest revision，无需伪造 revision 或更改接口版本。共存阶段还比较两个 Manifest 中 Greeting 的完整契约条目：ServiceKey、serviceId、schemaFingerprint 与 MethodId 集合必须相同，额外导出的 Details 才是本例 revision 变化的来源。观察与断言按以下顺序进行：

| 阶段 | 同一 Provider 应用的成员与导出 | 必须成立的行为 |
|---|---|---|
| `baseline` | 一个实例，`{Greeting}` | Greeting RPC 成功 |
| `coexist` | 两个实例，分别为 `{Greeting}`、`{Greeting, Details}` | 两个真实 revision 共存；Details 只路由到实际导出它的实例 |
| `details-probed` | 两个 Provider 共存，另有 Details Consumer 探针 | 探针真实 RPC 返回 `DETAILS_OK`，并作为无 Endpoint 的 Consumer 被观察 |
| `promoted` | 仅 `{Greeting, Details}` 实例 | 同一个长期 Consumer 的 Greeting RPC 继续成功；Details 路由指向该实例 |
| `rollback-coexist` | 基线实例重新加入，两个 revision 再次共存 | 回滚期间 Greeting 保持可路由 |
| `rolled-back` | 仅 `{Greeting}` 实例 | Greeting 成功；Details 没有已验证 Provider |
| `providers-stopped` | Provider 全部停止，Consumer JVM 保持运行 | Consumer 成员仍在；Greeting 和 Details 的已验证 Provider 都为空 |

Details 的实际调用成功标记为：

```text
RPC details quickstart completed successfully: DETAILS_OK
```

Details 探针应用名为 `otryx-quickstart-revision-details-probe`，完成调用及观察后退出。全部阶段通过后脚本输出 `Two actual application Manifest revisions and rollback: PASS`。实现见 [revision 脚本](../scripts/run_quickstart_revision_e2e.sh) 与 [快照断言](../scripts/assert_quickstart_snapshot.py)。

本场景验证当前应用级控制面内的导出变化、成员共存与收敛。它不证明历史接口级 Registry 与应用级 Registry 混合部署互通；跨注册代际规则仍见 [迁移指南](../docs/migration.md)。

<!-- doc-section:security -->
## 5. mTLS：已认证证书仍须通过方法授权

```bash
bash scripts/run_quickstart_mtls_e2e.sh
```

脚本生成临时 CA、带正确服务端身份的证书，以及由**同一 CA 签发的两张不同客户端证书**。应用名为 `otryx-quickstart-mtls-provider`（19143）和 `otryx-quickstart-mtls-consumer`。Provider 只允许其中一张客户端叶子证书的 SHA-256 指纹调用 Greeting 的 `hello`，按以下顺序验收：

| 阶段 | 客户端与目标 | 预期结果 |
|---|---|---|
| `allowed-initial` | 允许证书调用 Greeting hello | 真实 RPC 成功 |
| `identity-denied` | 同 CA 的另一张证书调用 Greeting hello | 精确远程授权拒绝 |
| `allowed-repeat` | 切回允许证书调用 Greeting hello | 再次成功；Provider 仍为同一 JVM |
| `service-denied` | 允许证书调用已导出的 Details | 精确远程授权拒绝，证明证书受信不等于任意方法获准 |

每阶段单独启动并结束 Consumer，Provider 保持运行。全部通过后脚本输出 `mTLS verified identity and service authorization: PASS`。

```mermaid
sequenceDiagram
    participant C as Consumer
    participant T as TLS Transport
    participant P as Core Provider
    participant A as RpcAuthorizer
    participant S as Greeting Service
    C->>T: mTLS 握手与证书验证
    T-->>C: 已验证连接
    C->>T: RPC serviceId / methodId
    T->>P: 已验证 Peer 与请求 Header
    P->>A: 叶子指纹、预绑定 ServiceKey / MethodId
    alt 指纹与方法均允许
        A-->>P: Allow
        P->>S: 解码并调用 hello
        S-->>C: GreetingReply
    else 身份或方法未授权
        A-->>P: Deny
        P-->>C: BAD_REQUEST / RPC authorization denied
    end
```

这里的拒绝必须是精确的 `BAD_REQUEST` / `RPC authorization denied`。CA、Hostname 或握手失败属于**认证失败**，不会被当成授权拒绝通过。Consumer 的 `otryx.rpc.quickstart.expect-authorization-denied=true` 只接受这一精确结果，成功标记是：

```text
RPC authorization denial verified.
```

### 手工使用 TLS / mTLS Profile

证书环境变量由对应 Profile 按角色读取，路径均指向本地 PEM 文件：

| Profile / 进程 | 必需环境变量 | 用途 |
|---|---|---|
| `tls` / Provider | `OTRYX_QUICKSTART_TLS_CERT`、`OTRYX_QUICKSTART_TLS_KEY` | 服务端证书和私钥 |
| `tls` / Consumer | `OTRYX_QUICKSTART_TLS_CA` | 校验服务端证书的信任 CA |
| `mtls` / Provider、Consumer | `OTRYX_QUICKSTART_TLS_CERT`、`OTRYX_QUICKSTART_TLS_KEY`、`OTRYX_QUICKSTART_TLS_CA` | 各进程自己的证书、私钥及校验对端的信任 CA |

旧 `OTRYX_RPC_TLS_CERT`、`OTRYX_RPC_TLS_KEY`、`OTRYX_RPC_TLS_CA` 必须改名并从环境中移除，否则 Starter 严格校验会拒绝未知配置。启动参数为 `--spring.profiles.active=tls` 或 `--spring.profiles.active=mtls`；Consumer 默认验证目标主机身份。

`mtls` Profile 启用 `NETWORK_MTLS`、严格 Fory DTO allowlist，并加载 Quickstart 专用 `RpcAuthorizer`。**Provider 必须显式设置** `otryx.rpc.quickstart.mtls.allowed-client-sha256`，值必须恰好是 64 个小写十六进制字符，无冒号、无首尾空白。可从允许的客户端叶子证书 DER 计算：

```bash
openssl x509 -in client.crt -outform DER | openssl dgst -sha256
```

取输出末尾的 64 位摘要，通过 Provider 参数 `--otryx.rpc.quickstart.mtls.allowed-client-sha256=<摘要>` 传入；Consumer 不需要该属性。缺失或格式错误时 Provider 启动失败。授权依据是 Transport 已验证的叶子证书 DER SHA-256，**不是 CN/SAN 文本**；目标须同时匹配 `GreetingService` / `1.0.0` / `default` 与 `hello` 的 MethodId。即使证书受信且指纹允许，Details 也不获授权。

`otryx.rpc.quickstart.use-details=true` 选择 Details 调用，与 `use-relay=true` 互斥；拒绝预期模式不能与重复调用模式一起使用。[演示授权器](otryx-quickstart-provider/src/main/java/com/peachsoft/otryx/quickstart/provider/QuickstartMtlsAuthorizationConfiguration.java) 属于 Quickstart Provider，通用 Starter 不会自动生成业务授权策略。`NETWORK_MTLS` Provider 未提供授权器时仍默认拒绝。完整证书部署、Reload 和授权边界见 [安全与授权](../docs/security.md)。

<!-- doc-section:integration -->
## 6. 理解需要复制到自己项目里的最小代码

共享 Contract 接口使用 `@OtryxRpcContract`，可通过 Maven Annotation Processor 生成 Stub/Dispatcher（当前 API 模块已经配置处理器）。没有生成代码时，会回退至 JDK Proxy / MethodHandle，不影响接口协议：

```java
@OtryxRpcContract
public interface GreetingService {
    @OtryxRpcIdempotent
    GreetingReply hello(GreetingRequest request);
}
```

Provider 声明 `@OtryxRpcService(interfaceClass = GreetingService.class, version = "1.0.0")`；Consumer 注入 `@OtryxRpcReference(version = "1.0.0")`。只有业务真正具备幂等语义时才应添加 `@OtryxRpcIdempotent`，尤其不要仅为了重试而将扣款、下单标记为幂等。

你的应用应只引入单一 Starter；需要 Nacos 再明确添加 Adapter：

```xml
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
```

其他可按需选择 `otryx-registry-etcd`、`otryx-registry-consul`、`otryx-registry-eureka`、`otryx-proxy-cglib`、`otryx-proxy-bytebuddy` 或 Observability Adapter；无需重新换 Starter。完整属性与 SPI 覆盖见 [Starter 参考](../docs/reference/starter.md)。

<!-- doc-section:other-registries -->
## 7. 进一步验证 Registry：Consul / Eureka

Provider、Consumer 当前的 POM 已声明可选演示所需的 Consul/Eureka Adapter。先自己部署对应服务后，**两个进程使用同一 Profile**，不要尝试与 Memory Registry 跨 JVM 连接：

```bash
# Provider 终端
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar --spring.profiles.active=consul
# Consumer 终端
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar --spring.profiles.active=consul
```

Eureka 只保存应用成员，必须另外配置提供 Manifest/Mapping 的元数据后端。下面示例要求同时运行 Eureka 和 Consul；Quickstart 已显式引入这两个 Adapter，两个进程均指定同一个 Consul KV 后端：

```bash
# Provider 终端
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar \
  --spring.profiles.active=eureka \
  --otryx.rpc.registry.eureka.metadata-backend=consul \
  --otryx.rpc.registry.eureka.metadata-endpoints=http://127.0.0.1:8500
# Consumer 终端
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar \
  --spring.profiles.active=eureka \
  --otryx.rpc.registry.eureka.metadata-backend=consul \
  --otryx.rpc.registry.eureka.metadata-endpoints=http://127.0.0.1:8500
```

Consul Profile 的认证通过 `CONSUL_HTTP_TOKEN` 传递，Eureka 成员面的认证通过 `EUREKA_USERNAME`/`EUREKA_PASSWORD` 传递。Eureka 的外置元数据凭据须另外通过 `metadata-options` 配置，见 [Starter 后端装配](../docs/reference/starter.md)。不要提交真实密钥。后端约束见 [Consul V2](../docs/reference/registry-consul-v2.md) 和 [Eureka V2](../docs/reference/registry-eureka-v2.md)。

<!-- doc-section:operations -->
## 8. 观测、背压和生命周期

- `QuickstartCallObserver` 演示自定义 `RpcObserver` 的 Spring Bean 接入，按终态累计成功/失败次数，不读取 Payload；
- Micrometer、OpenTelemetry、JFR 是**按需添加**的 Adapter，不会作为 Starter 的强制依赖；
- 重试仅适用于幂等方法；达到容量上限时会快速拒绝而非阻塞 EventLoop；
- Provider/Consumer 的关闭、Drain、Deadline 均由真实运行时负责，见 [运行与排障](../docs/operations.md)、[Deadline](../docs/reference/deadline-context.md)。

### 常见问题和排查顺序

| 现象 | 检查项 |
|---|---|
| `exec.jar` 找不到 | 先在**仓库根目录**运行 `./mvnw -B -ntp -pl otryx-quickstart -am clean package`；区分普通 JAR 与 `-exec.jar` |
| Maven 解析不了 OTRYX `1.0.0-SNAPSHOT` | 尚未发布 Central；先本地运行 `./mvnw -B -ntp clean install -DskipTests` |
| 无法连接 Nacos | 检查 `docker compose ... up -d`、8848/9848、Registry type、namespace 与 Provider/Consumer 配置是否一致 |
| 缺少 Mapping 分片 | 停止该 scope 的所有业务写入，再独占运行 bootstrap；自动脚本已在应用启动前执行 |
| Memory 模式双进程互相看不到 | Memory Registry **不跨 JVM**；使用 Nacos / Consul / Eureka 等共享注册中心 |
| Consumer 成员存在却无法调用 | 成员入册不等于 Provider 路由就绪；检查 Mapping、该实例 Manifest、角色与 `verifiedProviders` |
| 观察器拒绝写文件 | 输出必须是新文件；每轮观察改用新文件名，检查 namespace/group/applicationName 是否一致 |
| `NETWORK_MTLS` 拒绝调用 | 分清握手失败和 `RPC authorization denied`；再查指纹、ServiceKey、MethodId 与严格 Fory 白名单 |
| RPC 偶发超时 | 检查 Provider 是否启动、服务目录是否完成同步、请求 Deadline、连接与背压指标 |
| 端口被占用 | Provider 默认 19090；多跳和双 revision 使用 19090/19091，mTLS 使用 19143；先停止手工进程，顺序运行脚本 |

<!-- doc-section:validation -->
## 9. 完整验收与清理

```bash
python3 scripts/test_quickstart_layout.py
./mvnw -B -ntp clean verify -Pquality
# 已构建全部 JAR，且本地 Nacos 正在运行
bash scripts/run_quickstart_e2e.sh
# 仅当本教程的 Compose 由你启动且已不再使用时执行
docker compose -f otryx-quickstart/docker-compose.yml down
```

初次验收完成后可阅读：[用户指南](../docs/user-guide.md) · [Starter 参考](../docs/reference/starter.md) · [架构设计](../docs/architecture.md) · [协议兼容](../docs/wire-compatibility.md)。固定硬件性能结论仍需要受控证据，短时 Quickstart 成功**不是**正式生产性能或 GA 发布认证。

统一入口仅在五个脚本全部成功后输出 `Complete application-level Quickstart suite: PASS`。原始 JVM 日志在临时目录中，失败时向控制台输出诊断末尾，清理后不作为持久证据；脚本只管理自己启动的应用进程，不停止用户提供的 Nacos。

<!-- doc-section:migration-status -->
## V2 应用级注册迁移状态

Core 编程式 `ApplicationMembershipCoordinator`、`ApplicationServiceResolver` 与
`OtryxRpcClient.applicationControlPlane(...)` / `OtryxRpcServer.applicationMembership(...)`
已经建立独立的应用级 RPC 路径。可运行的 **Core 单 JVM Wire v1 E2E**
在 `ApplicationRuntimeEndToEndTest` 中验证三接口、独立 Consumer 与同进程 BOTH。
#137 已将唯一 Spring Starter 接入同一套应用级控制面并合并，
本目录的 Spring Quickstart 通过该 Starter 注册 Provider、Consumer 或 BOTH 成员；
纯 Consumer 不注册 RPC Endpoint。Nacos 独立进程脚本在启动应用前初始化本轮独立 scope。
#138 的范围是本页的成员诊断、两个真实 Manifest revision 滚动和 mTLS 授权教程，
复用原有四模块和单一 Starter；验收须核对该 PR 准确 Head 与合并后 main CI 的实际结果。
旧接口级 SPI 的全量删除仍归 #139，尚未完成。Quickstart 成功不能作为全仓 V2 迁移完成或
生产就绪的证明。各阶段范围与剩余工作见 [V2 设计与状态](../docs/design/application-registry-v2.md)。
