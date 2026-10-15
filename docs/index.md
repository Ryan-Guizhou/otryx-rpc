# OTRYX RPC 文档中心

> **版本状态**：源码为 `1.0.0-SNAPSHOT`；JDK 21、Spring Boot 3.5.4，**尚未公开发布到 Maven Central**。Peach RPC 1.x 到 OTRYX 1.0 属于破坏性 Java API / Type ID / Method ID / Schema Fingerprint 迁移，Wire v1 不代表跨版本可直接互通。

![OTRYX RPC 架构](images/architecture/system-overview.svg)

## 从这里开始

| 你的目标 | 建议阅读 |
|---|---|
| 下载源码、编译、实际跑通 Provider / Consumer | [快速开始](getting-started.md) |
| 第一次集成 Starter、写业务接口 | [用户指南](user-guide.md) |
| Registry / 超时 / 背压 / TLS 等参数 | [配置参考](configuration.md) 和 [Starter](reference/starter.md) |
| 排查运行失败、资源和监控问题 | [运行与排障](operations.md) 和 [FAQ](faq.md) |
| 理解设计或开发扩展 | [架构](architecture.md)、[核心能力](features.md)、[SPI](spi.md) |
| 跨版本迁移或生产发布 | [兼容协议](wire-compatibility.md)、[迁移](migration.md)、[升级回滚](upgrade-rollback.md) |

## 架构与技术细节

[协议](protocol.md) · [安全](security.md) · [可观测性](observability.md) · [性能与限制](performance.md) · [Nacos 注册中心](reference/registry-nacos.md) · [Consul](reference/registry-consul.md) · [Eureka](reference/registry-eureka.md)

资源所有权与关闭/重试决策：[运行时资源所有权](reference/runtime-ownership.md)。

设计依据：[需求蓝图](design/requirements-blueprint.md) · [技术方案](design/technical-solution.md) · [详细设计](design/detailed-design.md) · [模块结构](design/project-structure.md) · [首发质量改造实施契约](engineering/plans/2026-10-12-open-source-quality-upgrade.md)。

性能及工程证据：[性能证据](reference/performance-evidence.md) · [容量规划](reference/capacity-planning.md) · [Soak 验收与 AB/BA 三轮证据](reference/soak-acceptance.md) · [分配剖析](reference/transport-allocation-profiling.md)。

## 发布与项目管理

[首发 RC/GA 验收与供应链](reference/first-release-acceptance.md) · [品牌规范与 Otti](brand-guidelines.md) · [公开发布验收与授权风险](publication-readiness.md) · [历史发布说明](archive/releases/release-notes-1.0.1.md)。

Agent/MCP 与 Java 规范属于贡献者资料，不是使用 OTRYX RPC 的必要步骤，详见 [工程规范](../AGENTS.md)。

> 本目录仅对当前代码中存在的能力给出使用入口。固定硬件吞吐、容量与延迟结论需依赖受控证据，不可将共享 CI 冒烟测试当成生产 SLO。
