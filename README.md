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

<!-- doc-section:compatibility -->
## 兼容性与迁移

Wire Protocol v1 的 32 字节 Header、消息和 Codec 标识、历史 Registry 兼容字段保持不变。
但 Java 包名参与类型规范名、Method ID 和 Schema Fingerprint 计算，故本次新命名空间属于**破坏性 API 升级**。

- 老项目仍可使用原稳定分支构建，不覆盖旧 Maven GAV。
- 新旧服务先做版本隔离或蓝绿部署；互通能力必须按业务契约实际验证。
- 旧版 Registry 兼容键保持为 peach.rpc.protocol.version / peach.rpc.schema.version / peach.rpc.schema.fingerprint。

[详细兼容协议](docs/wire-compatibility.md) · [迁移与回滚指南](docs/migration.md)

<!-- doc-section:quick-start -->
## 快速开始

推荐 **JDK 21、Spring Boot 3.5.4**。仓库内 Maven Wrapper 固定 Maven 3.9.11；首次运行需可连接 Maven Central。

~~~bash
./mvnw -B -ntp clean install -DskipTests
./mvnw -B -ntp clean verify -Pquality
~~~

Windows CMD/PowerShell 使用 `mvnw.cmd` 替代 `./mvnw`，无需预装全局 Maven。

真实的 Provider / Consumer 独立进程示例见 [otryx-examples](otryx-examples/README.md)；详细启动方式见 [快速入门](docs/getting-started.md)。Consumer 示例内的 `ExampleCallObserver` 演示可验证的自定义遥测 SPI 注入。

<!-- doc-section:dependency -->
## Maven 坐标

目前开发版本：

~~~xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter-lite</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
~~~

完整 Starter：otryx-spring-boot-starter。**1.0.0-SNAPSHOT 并未声明已发布到 Maven Central。**

首发 RC/GA 仅提供 [无发布 Dry-run](docs/reference/first-release-acceptance.md)：核验 Main、Sources、Javadoc、SBOM 与 SHA256 清单，**不签名、不创建 Tag、不上传 Central**。首次发行仍须核实品牌与 Maven Namespace 授权。

<!-- doc-section:configuration -->
## 配置

~~~yaml
otryx:
  rpc:
    enabled: true
    security-profile: TRUSTED_DEVELOPMENT # 仅隔离开发演示
    registry:
      type: memory
~~~

实际网络部署必须显式选择 `NETWORK_MTLS`，同时配置双向 TLS、Fory 严格 DTO 白名单；可注入 `RpcAuthorizer` 对已验证证书身份按 ServiceKey/MethodId 授权。未指定安全意图会在 Spring 启动阶段失败；开发示例的 `TRUSTED_DEVELOPMENT` 不适用于不可信网络，详见[安全指南](docs/security.md)。

Consul / Eureka 采用按需依赖与轮询式订阅，见 [Consul](docs/reference/registry-consul.md) 与 [Eureka](docs/reference/registry-eureka.md) 适配指南。

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

Consumer 可选设置 `otryx.rpc.client.directory-max-staleness`，在 Registry **显式同步失败**且 LKG 超时后拒绝过期路由；默认不限制。Etcd、Nacos、Consul/Eureka 已实现健康通知与有界恢复。真实独立 JVM Nacos 重启验收和边界见 [Registry 健康与故障恢复](docs/reference/registry-freshness.md)。
