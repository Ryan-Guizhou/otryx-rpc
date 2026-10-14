# Changelog

## 1.0.0-SNAPSHOT（OTRYX 品牌/API 迁移，尚未正式发布）

- 项目品牌更名为 OTRYX RPC，Java 包名统一 com.peachsoft.otryx，Maven groupId 统一 com.peachsoft.otryx，artifactId 改为 otryx-*。
- 公开 PeachRpc* API/注解迁移为 OtryxRpc*；Spring 配置改用 otryx.rpc，新增完整中英文迁移指南。
- 继续使用 Wire v1 及历史 Registry Metadata 字段；**Java Type/Method IDs 与 Schema Fingerprint 跨项目/命名空间不保证兼容**。
- 保留旧版稳定快照 stable/peach-rpc-1.0.1-pre-otryx-2026-10-10；新坐标目前处于开发测试，尚未在 Maven Central 发布。
- 增加 Otti 吉祥物、品牌 Banner、架构和调用链插图，完善 Agent/质量与迁移门禁。

### 2026-10 首发工程准备（未发布）

- U10：低基数遥测、有界 Observer、JFR Registry 标签脱敏和基准工具。
- U11：Maven Wrapper、Lite Starter 自定义 Bean/SPI 接入测试和独立进程示例。
- U12：重试决策及关闭总预算提取为可独立测试的内部职责；保持 Wire v1。
- U14：RC1/GA 无发布 Dry-run、CycloneDX SBOM、SHA256 清单、Dependency Review 与 Dependabot；**尚无发行 Tag、GPG 签名或 Central 上传**。

## Peach RPC 1.0.x 历史变更记录

Peach RPC 使用语义化版本。1.0.x 保持 Wire v1、Stable Type ID、Schema Fingerprint v1 和 Public Core API 的兼容边界。

## Unreleased

暂无。

## 1.0.1

### Fixed

- Provider 对业务 `CompletionStage` 使用 continuation 完成响应，不再通过 `join()` 占用 Provider worker；异步阶段完成前继续持有 admission permit。
- 异步业务 Future 在外部线程或 EventLoop 完成时，响应序列化重新进入 Provider 管理的执行资源并恢复 Trace/Metadata Scope，避免大型响应编码阻塞 Future completion thread。
- Provider 在对外完成响应 Future 之前先释放 admission，消除“响应已完成但并发许可尚未归还”的瞬时过载竞态。
- CPU completion queue 饱和时异步响应 fail-fast 为 `OVERLOADED`，不会退回外部 completion thread 执行编码。

### Performance

- Retry Budget 达到额度上限时避免无意义 CAS 写回。
- Circuit Breaker 在健康 CLOSED 成功路径避免重复原子写。
- `EndpointStats.available()` 在健康节点不再无条件读取 `System.nanoTime()`；过期 ejection 使用 CAS 清零。
- Endpoint 成功记账在连续失败数已经为 0 时避免重复原子写，并新增对应 JMH 路径。

### Release Engineering

- 增加 Maven Central Publisher Portal 发布链路、GPG signing 与不可变 1.0.x Patch Release Workflow。
- 增加 Central publication preflight，校验公开模块、POM metadata、main/source/javadoc artifact shape 与非公开 examples/benchmarks exclusion。
- 纯依赖 Spring Boot Starter 使用 placeholder `sources` / `javadoc` classifier JAR，避免为了 Central 发布引入虚假的 public API。
- Release Readiness 只验证当前 Patch 源码，不再用 post-GA 最新源码重新构建历史 `1.0.0-RC1` / `1.0.0` 产物。

### Compatibility

- Wire Protocol 仍为 v1。
- Public Core API、Stable Type ID、Schema Fingerprint v1 与 Registry compatibility metadata 保持 1.0.x 兼容边界。
- 1.0.1 与 1.0.0 按既有 N/N+1 与 rollback 机制保持滚动兼容。

## 1.0.0

### Added

- 正式冻结 Wire v1 与 1.0.x 兼容策略。
- 完整 RC1/GA Release Workflow、Release Readiness 与 SHA256 Release Bundle。
- 面向开源使用者的需求蓝图、技术方案、功能描述、详设、快速开始、FAQ 和项目结构文档。
- GitHub 开源治理入口：Security Policy、Code of Conduct、PR/Issue 模板。

### Changed

- Maven 默认版本提升为 `1.0.0`。
- 对外文档从 V2 阶段研发日志收敛为 1.0 稳定文档体系。
- 固定硬件性能 Evidence 改为“发布官方性能/容量数字”的必需门槛，而不是开源 GA 本身的阻塞项。

### Fixed

- Nacos Consumer subscription 增加低频完整视图 reconcile，确保远端最后一个 Provider 注销后即使 NamingEvent 延迟或缺失，本地目录也能最终收敛为空。

### Verified

- CI。
- Release Readiness。
- Etcd Chaos。
- Nacos Chaos。
- N/N+1 Rolling Compatibility。
- Independent JVM Examples E2E。

## 1.0.0-RC1

### Added

- Stable Type ID 与冲突检测。
- Schema Fingerprint v1。
- Registry compatibility metadata 与 pre-routing incompatible provider isolation。
- N/N+1 mixed version 与 rollback 自动化。
- 协议 malformed/property/race 测试。
- Etcd leader-transfer Chaos。
- Nacos pause/unpause Chaos。
- logical-call Metrics、Retry exhausted、Circuit state、Provider admission 等生产观测信号。
- Upgrade/Rollback、Production Configuration、Dashboard、Alert 与 SLO 模板。

### Freeze

RC1 起冻结：

- Wire Protocol v1；
- Public Core API；
- Stable Type ID 规则；
- Schema Fingerprint v1；
- 已分配 Codec/Message Type；
- Registry compatibility metadata key。

## 0.1.x

预览阶段。用于建立 Core/Adapter 边界、Unary RPC、Spring Boot Starter、Registry/Transport/Codec、Resilience、Security、Observability 与性能工具链。
