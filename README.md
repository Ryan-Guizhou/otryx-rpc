# OTRYX RPC

[简体中文](README.md) | [English](README.en-US.md)

![OTRYX 品牌横幅](docs/images/brand/otryx-banner.svg)

<!-- release-status:project=development -->
<!-- release-status:version=1.0.0-SNAPSHOT -->
<!-- release-status:wire=v1 -->

<!-- doc-section:overview -->
## 项目简介

**OTRYX RPC** 是面向首次正式发布的轻量、高性能、高可用 Java RPC 框架，支持可替换传输、注册中心和序列化实现。
新品牌以 Otti 科技水獭为吉祥物：**让分布式通信，简单而可靠。**

**当前源码：1.0.0-SNAPSHOT（首发开发版，尚未正式发布）**

**GitHub 仓库：** [Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc)。品牌商标、图片来源及 Maven Central Namespace 尚有外部验收条件，参见 [发布前核查](docs/publication-readiness.md)。
· **Java：21** · **Spring Boot：3.5.4** · **Wire：v1** · **License：MIT**

项目目前尚未正式发布，不对历史开发版提供 Java API、SPI、Wire、Schema 或配置兼容承诺；当前版本必须通过协议正确性、Consumer/Provider 互操作和安全验证。Spring Starter、Core Client/Server Builder 与 RegistryOptions 都必须显式选择安全 Profile：可信隔离开发使用 `TRUSTED_DEVELOPMENT`，网络部署使用 `NETWORK_MTLS`；默认 `UNSPECIFIED` 拒绝启动。网络 Profile 要求实际 mTLS 与应用绑定、安全 Codec、受保护的 Registry 通道和有限方法授权。工程规范参见[Java 编码规范](docs/engineering/java-coding-standard.md)。

<!-- doc-section:capabilities -->
## 核心能力

- **Unary RPC**：Generated Stub / Dispatcher 优先，JDK Proxy / MethodHandle fallback。
- **Transport**：Vert.x TCP 长连接、连接分片、Heartbeat、Reconnect、CANCEL、GO_AWAY、Graceful Drain。
- **Security**：显式部署 Profile、证书与完整应用身份绑定、Hostname Verification、证书 Reload、有限方法许可与实际 SPI 能力预检。
- **Registry**：Memory、Etcd、Nacos、Consul、Eureka；Consumer 热路径读取本地验证目录。后端的网络安全支持范围按实际 Adapter 区分。
- **Routing / Load Balancing**：应用级 Router SPI、本地版本化规则、应用优先级、标签/区域/发布版本与 10%/90% 灰度，先过滤再经 P2C + EWMA 负载均衡；路由标签不构成授权依据。
- **Resilience**：方法级/单次超时、父级单调 Deadline 预算传播、显式幂等 Retry、Retry Budget、Circuit Breaker、Outlier Ejection、Provider Admission。
- **Execution / Bounds**：Virtual Thread、CPU 有界资源、TLS 前握手准入及幂等释放；Provider 流式输出编码具独立字节预算，Fory Codec 与 Stable Type ID。
- **Observability**：Micrometer、OpenTelemetry、JFR、Grafana 和 Prometheus 示例配置。
- **Engineering**：JMH、10k logical-concurrency soak harness、Chaos、独立 JVM E2E 与兼容性验证。

<!-- doc-section:architecture -->
## 系统架构

![OTRYX 系统架构](docs/images/architecture/system-overview.svg)

![OTRYX 调用流程](docs/images/flows/rpc-lifecycle.svg)

**设计原则：** 控制面与数据面分离；可替换实现位于 Adapter/SPI；单次调用热路径不做注册中心 IO、SPI 扫描和动态解析。

**应用级注册（#139 已完成）：** [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) 已合入 main `783bf8115c9523e5667023678dafe659315b2b66`，并完成该合并提交的 main 门禁核验。Core、唯一 Starter 和四模块 Quickstart 使用统一 Application 身份、Provider/Consumer/BOTH 共享成员生命周期、Manifest、实例 Owner CAS Mapping、Resolver 与有界缓存。纯 Consumer 真实入册但无 RPC Endpoint；ServiceKey 表示业务契约，ServiceInstance 是携带目标应用的本地负载均衡候选。过渡 SPI、注册路径和配置已清理。[设计](docs/design/application-registry-v2.md) · [验收与计数口径](docs/reference/application-registry-acceptance.md)。

**安全契约与验收（#140）：** 本文描述显式安全模式、真实应用身份和受保护控制面的契约与使用入口。完整验证范围以 [#140](https://github.com/Ryan-Guizhou/otryx-rpc/issues/140) 及工程断点中记录的准确 PR Head、合并后 main 和原始证据为准。

[应用路由 SPI 与规则文档](docs/reference/routing.md)：Provider/Consumer 统一应用身份，默认无过滤，使用本地原子路由规则而非热路径查询 Registry；任何自定义 Router 的输出均须是已验证 Provider 候选的原始对象子集。

<!-- doc-section:compatibility -->
## 兼容性与迁移

Wire Protocol v1 的 32 字节 Header、消息和 Codec 标识保持不变；这不能证明不同注册模型或 Java 命名空间直接互通。
Java 包名参与类型规范名、Method ID 和 Schema Fingerprint 计算，故本次新命名空间属于**破坏性 API 升级**。

- 老项目仍可使用原稳定分支构建，不覆盖旧 Maven GAV。
- rolling 两端必须使用应用级 Starter；N/N、N+1/N、N/N+1、N+1/N+1、rollback 五组全部执行，任一失败阻止通过。旧模式、混合或未知装配在业务进程启动前明确拒绝。
- 历史注册模型采用隔离注册域成对迁移；Wire v1 或相同 Maven 版本号不能替代完整业务链的互通验证。

[详细兼容协议](docs/wire-compatibility.md) · [迁移与回滚指南](docs/migration.md)

<!-- doc-section:quick-start -->
## 快速开始

推荐 **JDK 21、Spring Boot 3.5.4**。仓库内 Maven Wrapper 固定 Maven 3.9.11；首次运行需可连接 Maven Central。

~~~bash
./mvnw -B -ntp clean install -DskipTests
./mvnw -B -ntp clean verify -Pquality
~~~

Windows CMD/PowerShell 使用 `mvnw.cmd` 替代 `./mvnw`，无需预装全局 Maven。

推荐从**无 Docker 的单 JVM Memory Quickstart**开始，再学习共享 Registry 下的应用成员、已验证路由、A→B→C 的 BOTH 角色与 Deadline，以及同一应用两个真实导出 revision 的升级/回滚。独立 mTLS 路线使用受保护的 Etcd，演示证书到完整 ApplicationKey 的绑定、证书轮换和有限方法授权。完整命令、JSON 字段和预期结果见 [Quickstart 教程](otryx-quickstart/README.md)；简版路线见 [快速入门](docs/getting-started.md)。

构建完成后，在 **Linux** 上为默认 process/chain/revision 场景准备本地 Nacos，并准备 Bash、Python 3、GNU `timeout`、OpenSSL 和 Etcd **3.5.21** 可执行文件，再顺序运行全部场景。Linux 是 mTLS 安全自动验收及此聚合入口的前置条件：

~~~bash
ETCD_BINARY=/absolute/path/to/etcd \
  NACOS_TEST_ENDPOINT=127.0.0.1:8848 bash scripts/run_quickstart_e2e.sh
~~~

默认的 process、chain、revision 各自初始化独立 Nacos group；它们也支持通过 `QUICKSTART_REGISTRY` 与 `QUICKSTART_REGISTRY_ENDPOINT` 选择真实 Etcd、Consul、Eureka，Eureka 另需 `QUICKSTART_EUREKA_METADATA_ENDPOINT` 指向真实 Consul。mTLS 脚本自行启动并清理 loopback Etcd TLS/RBAC 实例，使用独立的 RPC/Registry CA 与分角色凭据，无需 Nacos。受限 JSON 和 `summary.log` 保存在 `target/quickstart-e2e/`，不包含临时私钥或凭据。执行记录只证明对应提交的实际覆盖范围；完整结果需逐一核对真实场景和准确提交的 CI。参数见[应用级验收](docs/reference/application-registry-acceptance.md)，装配见 [Starter](docs/reference/starter.md)。

<!-- doc-section:dependency -->
## Maven 坐标

目前开发版本：

~~~xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
~~~

单一 Starter 默认仅引入 AutoConfiguration、Core/Memory Registry/JDK Proxy/Fory/Vert.x；Nacos、Etcd、Consul/Eureka、CGLIB/ByteBuddy 与遥测 Adapter 按需显式添加。**1.0.0-SNAPSHOT 尚未发布到 Maven Central。**

首发 RC/GA 仅提供 [无发布 Dry-run](docs/reference/first-release-acceptance.md)：核验 Main、Sources、Javadoc、SBOM 与 SHA256 清单，**不签名、不创建 Tag、不上传 Central**。首次发行仍须核实品牌与 Maven Namespace 授权。

<!-- doc-section:configuration -->
## 配置

~~~yaml
spring:
  application:
    name: order-service
otryx:
  rpc:
    enabled: true
    security-profile: TRUSTED_DEVELOPMENT # 仅隔离开发演示
    registry:
      type: memory
      control-plane-timeout: 3s
~~~

`spring.application.name` 是默认应用身份；可用 `otryx.rpc.registry.application-name` 覆盖，均未配置时启动失败。`registry.control-plane-timeout` 独立于 `server.control-plane-timeout`，默认 `3s`，必须大于零且不超过 `30s`，约束共享成员操作和 Consumer 入册等待。未创建实际 Consumer/Provider 时不会产生虚构成员。

`NETWORK_MTLS` 把真实 TLS 叶证书的 SHA-256 绑定到完整 `ApplicationKey(namespace, applicationName)` 与有限期限，业务授权器再按来源应用、ServiceKey 和 MethodId 返回 `RpcAuthorizationDecision.allowUntil(Instant)` 或 `deny()`。有效许可不能超过证书、部署绑定、请求预算和服务端最大许可期限；CN/SAN、请求 Metadata 与 Registry attributes 不能代替应用绑定。

网络 Profile 且 `server.enabled=true` 时，缺少显式 `RpcAuthorizer` Bean 会在创建 Registry 工厂和 RPC 运行时前拒绝启动；纯 Consumer 应设置 `server.enabled=false`。Core Server Builder 执行相同的缺策略拒绝。Starter 还会在任一角色启动前验证已启用 Client/Server 的最终实际能力；默认 Fory 使用 `STRICT_ALLOWLIST`，自定义 Codec 则按契约类型检查、解码前校验及有限容量能力验收。详见 [Starter 安全配置](docs/reference/starter.md) 与[安全指南](docs/security.md)。

已实现的应用级后端见 [Etcd](docs/reference/registry-etcd.md)、[Nacos V2](docs/reference/registry-nacos-v2.md)、[Consul V2](docs/reference/registry-consul-v2.md) 和 [Eureka V2](docs/reference/registry-eureka-v2.md)。网络 Profile 的 Etcd 使用明确 CA 与用户名密码，Consul 使用单个本地 Agent 的 HTTPS 与 ACL Token；Eureka 的成员链和外置元数据链分别配置。Spring 以 `registry.security` 和独立 `registry.metadata-security` 承载有类型 TLS/凭据，不能让已保护来源覆盖同通道中的弱来源。当前 Nacos Adapter 在创建 SDK 前拒绝 NETWORK/TLS 意图，可信开发仍可使用；Eureka 在网络 Profile 下也不能选择 Nacos 元数据链。Starter 保持唯一应用级路径。

Nacos V2 的 Mapping 分片必须在所有应用启动前、同一 namespace/group 的独占维护窗口内初始化。Quickstart 提供独立初始化命令，跨进程验证脚本会为本轮独立 group 完成该步骤；参见 [Nacos 启动步骤](otryx-quickstart/README.md#2-第二条调用nacos-双独立-jvm)。

Nacos 的 Manifest/Mapping 读取只接受远端查询结果及其权威 MD5，SDK 本地 Snapshot/Failover 不能成为新的成功快照或 CAS 基础。普通远端故障保留为不可用，Core 仅按既有有限 LKG 预算处理；真实认证、权限或完整性拒绝仍立即使旧安全快照失效。详见 [Nacos 元数据边界](docs/reference/registry-nacos-v2.md)。

完整配置约束和 TLS/Nacos/Etcd 示例见 [Starter 文档](docs/reference/starter.md)、[生产配置](docs/configuration.md)。调用超时覆盖、作用域传播及 A→B→C 预算规则参见 [Deadline 与调用上下文](docs/reference/deadline-context.md)。

<!-- doc-section:operations -->
## 生产与性能

先通过项目现有准入、背压、超时、重试、TLS 与可观测性规范验证目标环境；不可将 GitHub shared runner 的 smoke 结果解释为生产吞吐、p99 或容量 SLO。

[性能证据](docs/reference/performance-evidence.md) · [容量规划](docs/reference/capacity-planning.md) · [可观测性](docs/observability.md)

生产级 `NETWORK_MTLS` 在 Provider 绑定方法时要求 `RpcBoundedResultMethodCodec`，禁止仅依赖先完整分配 Payload 的默认 Codec；Fory 已实现有界 OutputStream，最终 Wire Frame 创建前另预留瞬时内存配额。可信开发模式仍可使用兼容适配；该防护不等于全 JVM Heap/DirectMemory 的硬上限，详见 [Transport 资源说明](docs/reference/transport-admission.md)。

<!-- doc-section:documentation -->
## 文档

[文档总览与阅读导航](docs/index.md) · [快速开始](docs/getting-started.md) · [用户指南](docs/user-guide.md) · [运行与排障](docs/operations.md) · [设计与协议](docs/architecture.md) · [SPI](docs/spi.md) · [安全](docs/security.md) · [品牌规范](docs/brand-guidelines.md) · [迁移指南](docs/migration.md) · [发布核查](docs/publication-readiness.md)

<!-- doc-section:development -->
## 开发与贡献

[贡献指南](CONTRIBUTING.md) · [安全策略](SECURITY.md) · [AGENTS 工程契约](AGENTS.md) · [首发质量改造实施契约](docs/engineering/plans/2026-10-12-open-source-quality-upgrade.md)。

项目坚持**中文标准 Javadoc、英文结构化日志、可验证的兼容性与性能证据**。

<!-- doc-section:license -->
## License

[MIT License](LICENSE)

## Registry Freshness / 服务发现可靠性

Core 与 Spring Starter 的 `otryx.rpc.client.directory-max-staleness` 默认均为 `30s`，只接受严格正的有限预算。普通 Registry 同步失败可在预算内使用最后有效目录，安静的 Watch 不代表失联；认证、授权、TLS 或元数据完整性拒绝则立即使旧验证代次失效。每次发送前本地复核选中端点所属的同一候选，安全失败不重试、不累计 Endpoint 故障或更新延迟 EWMA。状态语义见[目录新鲜度](docs/reference/registry-freshness.md)与[当前安全边界](docs/reference/starter.md)。


## 可选的 Consumer / Provider Interceptor SPI

通过 `RpcInterceptor` 配置有序 Hook 链：Consumer 的一次逻辑调用与每次 Retry Attempt 分开回调；Provider 只在真实 Peer 校验、服务/方法授权及业务 Admission 通过后运行。默认无 Hook 时不创建专属 Worker，也不改变现有调用快路径。配置 Hook 后采用固定 Worker、整条会话的容量配额及串行事件队列，超时/取消不会提前归还仍在运行的 Hook 槽位。

[Interceptor 的生命周期、异常及线程边界](docs/reference/interceptors.md) · [Quickstart 配置](otryx-quickstart/README.md)。


## 签名动态治理（V2-17）

可选 `RpcGovernanceRuntime` 使用独立有界控制线程订阅完整签名治理快照，对应用/方法级 Timeout、标签路由、应用权重、熔断和并发额度执行**原子版本切换**。Consumer 同一逻辑调用捕获同一 revision；Provider 原业务 Admission 和 mTLS/授权/Codec 安全规则始终优先。未配置 Repository/Verifier 时无动态治理开销；配置后在首次正确签名到达前 Fail Closed。详见 [治理配置与安全契约](docs/reference/governance.md)。
<!-- doc-section:runtime-diagnostics -->
## 运行时只读诊断

通过 `OtryxRpcClient.diagnose(ServiceKey)` 查看已经引用的服务的可信本地目录状态、固定失败原因与候选数量；不访问远端 Registry、不执行 Router 或新建订阅。Micrometer `otryx.rpc.client.directory.failures` 和 JFR 只使用固定枚举原因。Provider `responseEncodingDiagnostics()` 返回编码临时缓冲预留量和峰值，不等于进程 Heap/DirectMemory。详见[诊断指南](docs/reference/runtime-diagnostics.md)。

> V2-19 实证边界：固定物理机控制面原生计数、AB BA AB 六次完整 JMH 与每次至少 60 分钟 Soak **尚未采集**，不得以 Shared Runner CI Smoke 推断正式性能或容量。工具与证据入口参见 [注册规模量测](docs/reference/v2-19-performance-closure.md)。

V2-19 六轮真实固定机性能实验现可使用 `python3 scripts/run_v2_19_controlled_campaign.py` 一次组织执行；没有有效物理机和预声明 SLO 时会拒绝运行，不提供官方吞吐/容量承诺。详见[真实性能验收](docs/reference/v2-19-performance-closure.md)。
