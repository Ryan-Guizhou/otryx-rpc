# OTRYX RPC Roadmap

> 当前主线是 **OTRYX 1.0.0-SNAPSHOT** 品牌/API 迁移。以下条目仅为技术候选路线，不构成日期、已发布版本或性能承诺。

## OTRYX 1.0.x：首发质量收敛与稳定化

- 全仓从 io.peach.rpc / peach-rpc-* 迁移为 com.peachsoft.otryx / otryx-*。
- 准确说明跨包名 Stable Type ID、Method ID、Schema Fingerprint 兼容边界。
- 验证独立进程示例、Memory/Etcd/Nacos/Consul/Eureka、TLS/mTLS、Chaos、CI 与编译期 Codegen。
- 完善双语 README、文档架构图、吉祥物和可复现基准测试。
- 依照[首发质量改造实施契约](docs/engineering/plans/2026-10-12-open-source-quality-upgrade.md)先完成 EventLoop 隔离、关闭预算、分层背压、契约安全和可复现验证。
- Maven Central namespace 与品牌商标风险核查完成后，再独立评审公开发布；本轮保持 `1.0.0-SNAPSHOT`。

## Peach RPC 1.0.x（历史兼容分支）

定位：保留历史稳定基线；不将其解释为当前 OTRYX 公开 GA 的兼容保证。

- Bug fix；
- Security fix；
- 文档/示例改进；
- 不破坏兼容的 Observability 和运维增强；
- 不改变 Wire v1 / Stable Type ID / Schema Fingerprint v1。

## 1.1.x 候选

### 跨语言与 IDL

- Protobuf/IDL；
- 多语言 SDK 的契约生成；
- 字段级演进策略。

### Registry 生态

在不污染 Core 的前提下评估：

- Kubernetes EndpointSlice；
- ZooKeeper；

### 数据面

- Buffer ownership 优化；
- 更低 allocation 的 Pending/Completion 路径；
- Compression 策略；
- 只有 Benchmark Evidence 证明收益后才进入默认路径。

### Streaming

Streaming RPC 是独立设计主题，需要先完成：

- 流控；
- 背压；
- 取消；
- half-close；
- 长连接资源配额；
- 与 Unary 的兼容边界。

## 不做的事情

- 为“功能数量”破坏模块边界；
- 为基准数字牺牲正确性、可维护性和故障边界；
- 未经兼容设计直接复用 Wire/Codec/Type ID；
- 把未经固定环境验证的数据写成生产性能承诺。
