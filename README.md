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

项目目前尚未正式发布，不对历史开发版提供 Java API、SPI、Wire、Schema 或配置兼容承诺；当前版本必须通过协议正确性、Consumer/Provider 互操作和安全验证。Spring Starter 必须显式选择安全部署意图：隔离开发选择 `TRUSTED_DEVELOPMENT`，网络环境选择 `NETWORK_MTLS`（mTLS、严格 Fory 白名单与方法授权）。Core 程序化入口仍须由使用者显式建立相应安全边界。工程规范参见[Java 编码规范](docs/engineering/java-coding-standard.md)。

<!-- doc-section:capabilities -->
## 核心能力

- **Unary RPC**：Generated Stub / Dispatcher 优先，JDK Proxy / MethodHandle fallback。
- **Transport**：Vert.x TCP 长连接、连接分片、Heartbeat、Reconnect、CANCEL、GO_AWAY、Graceful Drain。
- **Security**：PLAINTEXT / TLS / mTLS、证书校验、Hostname Verification 与证书 Reload。
- **Registry**：Memory、Etcd、Nacos、Consul、Eureka；Consumer 热路径读取本地服务目录。
- **Load Balancing**：P2C + EWMA、inflight 与静态权重。
- **Resilience**：方法级/单次超时、父级单调 Deadline 预算传播、显式幂等 Retry、Retry Budget、Circuit Breaker、Outlier Ejection、Provider Admission。
- **Execution**：Virtual Thread、CPU 有界资源；Fory Codec 与 Stable Type ID。
- **Observability**：Micrometer、OpenTelemetry、JFR、Grafana 和 Prometheus 示例配置。
- **Engineering**：JMH、10k logical-concurrency soak harness、Chaos、独立 JVM E2E 与兼容性验证。

<!-- doc-section:architecture -->
## 系统架构

![OTRYX 系统架构](docs/images/architecture/system-overview.svg)

![OTRYX 调用流程](docs/images/flows/rpc-lifecycle.svg)

**设计原则：** 控制面与数据面分离；可替换实现位于 Adapter/SPI；单次调用热路径不做注册中心 IO、SPI 扫描和动态解析。

**V2 应用级注册改造（进行中）：** Core 已具备统一 Application 身份、共享 Provider/Consumer 成员生命周期、Manifest、按实例 CAS Mapping、服务发现 Resolver 及有界缓存；Memory、Etcd、Nacos、Consul（#135）、Eureka + 外置元数据（#136）的应用级 Adapter 已实现。当前 #137 分支将单一 Spring Starter 接入同一套应用控制面：一个进程实例共享一条成员记录，Consumer 入册成功后才完成运行时创建，关闭时有界等待 Client/Provider 后清理共享成员。#137 的验收仍以准确 PR Head 与合并后 main CI 为准；Quickstart 完整改写和旧接口级 SPI 删除仍由 #138、#139 承担，**全仓尚不可称已正式完成 V2 迁移**。[设计与进度](docs/design/application-registry-v2.md)。

<!-- doc-section:compatibility -->
## 兼容性与迁移

Wire Protocol v1 的 32 字节 Header、消息和 Codec 标识保持不变；这不能证明不同注册模型或 Java 命名空间直接互通。
Java 包名参与类型规范名、Method ID 和 Schema Fingerprint 计算，故本次新命名空间属于**破坏性 API 升级**。

- 老项目仍可使用原稳定分支构建，不覆盖旧 Maven GAV。
- 滚动检查按 Starter 实际启用的注册代际选择矩阵：同代保留完整 N/N+1 与回滚组合；跨接口级/应用级迁移只要求 N/N 和 N+1/N+1 独立 JVM 配对，不能据此声称混合代际互通。
- 跨注册代际使用隔离注册域，Provider/Consumer 成对迁移和回滚；旧接口级 Metadata Key 仅属于 #139 前的历史实现范围。

[详细兼容协议](docs/wire-compatibility.md) · [迁移与回滚指南](docs/migration.md)

<!-- doc-section:quick-start -->
## 快速开始

推荐 **JDK 21、Spring Boot 3.5.4**。仓库内 Maven Wrapper 固定 Maven 3.9.11；首次运行需可连接 Maven Central。

~~~bash
./mvnw -B -ntp clean install -DskipTests
./mvnw -B -ntp clean verify -Pquality
~~~

Windows CMD/PowerShell 使用 `mvnw.cmd` 替代 `./mvnw`，无需预装全局 Maven。

推荐先运行**无 Docker 的单 JVM Memory Quickstart**，再运行 Nacos 双独立进程与 A→B→C Deadline，见 [otryx-quickstart](otryx-quickstart/README.md)；详细启动方式见 [快速入门](docs/getting-started.md)。Consumer 示例内的 `QuickstartCallObserver` 演示可验证的自定义遥测 SPI 注入。

这些现有演示不代表 #138 的 V2 Quickstart 完整改写和验收已完成；#137 的 Starter 行为与源码测试入口见 [Starter 文档](docs/reference/starter.md)。

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

实际网络部署必须显式选择 `NETWORK_MTLS`，同时配置双向 TLS、Fory 严格 DTO 白名单；可注入 `RpcAuthorizer` 对已验证证书身份按 ServiceKey/MethodId 授权。未指定安全意图会在 Spring 启动阶段失败；开发示例的 `TRUSTED_DEVELOPMENT` 不适用于不可信网络，详见[安全指南](docs/security.md)。

已实现的应用级后端见 [Etcd](docs/reference/registry-etcd.md)、[Nacos V2](docs/reference/registry-nacos-v2.md)、[Consul V2](docs/reference/registry-consul-v2.md) 和 [Eureka V2](docs/reference/registry-eureka-v2.md)。Consul 使用单个本地 Agent；Eureka 负责应用成员，必须另外配置 Etcd/Nacos/Consul 元数据后端与地址。Starter 不回退到旧接口级 Adapter，也不提供双读双写模式。

Nacos V2 的 Mapping 分片必须在所有应用启动前、同一 namespace/group 的独占维护窗口内初始化。Quickstart 提供独立初始化命令，跨进程验证脚本会为本轮独立 group 完成该步骤；参见 [Nacos 启动步骤](otryx-quickstart/README.md#2-第二条调用nacos-双独立-jvm)。

完整配置约束和 TLS/Nacos/Etcd 示例见 [Starter 文档](docs/reference/starter.md)、[生产配置](docs/configuration.md)。调用超时覆盖、作用域传播及 A→B→C 预算规则参见 [Deadline 与调用上下文](docs/reference/deadline-context.md)。

<!-- doc-section:operations -->
## 生产与性能

先通过项目现有准入、背压、超时、重试、TLS 与可观测性规范验证目标环境；不可将 GitHub shared runner 的 smoke 结果解释为生产吞吐、p99 或容量 SLO。

[性能证据](docs/reference/performance-evidence.md) · [容量规划](docs/reference/capacity-planning.md) · [可观测性](docs/observability.md)

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

Spring Starter V2 默认 `otryx.rpc.client.directory-max-staleness=30s`，在 Registry **显式同步失败**且 LKG 超时后拒绝过期路由；安静的 Watch 本身不代表失联。`client.enabled=true` 时禁止显式 `0s` 无限 LKG，即使尚无实际引用。Etcd、Nacos、Consul/Eureka 已实现健康通知与有界恢复。真实独立 JVM Nacos 重启验收和边界见 [Registry 健康与故障恢复](docs/reference/registry-freshness.md)。
