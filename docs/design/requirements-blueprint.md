# OTRYX RPC 1.0 需求蓝图

> OTRYX 1.0.0-SNAPSHOT 属于公开 Java API / GAV 的破坏性命名空间迁移；继承历史 Wire v1 并不代表新旧 Java API 保证互通。详见 [迁移指南](../migration.md)。

> 状态：**1.0.0-SNAPSHOT Migration / 继承自 Peach RPC 1.0.x 的 Wire v1 基线**  
> 目标读者：使用者、架构师、维护者、贡献者。

## 1. 背景与问题

OTRYX RPC 面向 Java 服务间通信。项目要解决的不是“再增加一个远程调用 API”，而是把以下能力放进一个边界清晰、可独立演进的 RPC 基础设施中：

- 高并发 Unary RPC；
- 服务注册、发现与本地目录；
- 超时、重试、熔断、异常实例剔除与优雅下线；
- TLS/mTLS、可观测性和可诊断性；
- 当前候选版本的 Consumer/Provider 互操作、明确边界的升级与回滚；
- 编译期 Codegen 与可插拔 Adapter；
- 可重复的性能、Chaos、兼容与发布门禁。

项目采用 MIT License，目标是让个人和团队可以无授权费用地学习、使用、修改和分发。

## 2. 项目目标

### 2.1 核心目标

| ID | 目标 |
|---|---|
| G-01 | 为 Java 服务提供稳定的 Unary RPC 调用能力 |
| G-02 | 请求热路径不访问注册中心，控制面与数据面解耦 |
| G-03 | 资源使用有界，故障行为可预测、可观测、可恢复 |
| G-04 | 允许 Registry、Transport、Codec、Proxy、Observability 通过明确边界扩展 |
| G-05 | 当前首发候选版本内部 Wire、Type ID、Schema Fingerprint 与 Public Core API 契约自洽；GA 后另制定兼容演进规则 |
| G-06 | 新使用者能够按文档从零运行官方 Provider/Consumer 示例 |
| G-07 | 发布过程具备自动化 CI、Chaos、Rolling Compatibility 与 Release Readiness 门禁 |

### 2.2 非目标

1. 1.0.x 不提供 Streaming RPC。
2. 1.0.x 不宣称跨语言 IDL/SDK。
3. 当前首发不提供 ZooKeeper Adapter；Consul、Eureka 已有独立实现，但不承诺所有发现后端具备相同 Watch 语义。
4. 1.0.x 不把 GitHub shared runner 数据包装成官方生产容量数字。
5. 1.0.x 不自动替业务判断幂等语义。

## 3. 使用者与典型场景

| 角色 | 目标 | 典型场景 |
|---|---|---|
| Java 服务开发者 | 快速完成服务调用 | Spring Boot Provider / Consumer |
| 平台工程师 | 统一通信与治理 | 注册发现、超时、重试、熔断、可观测 |
| 架构师 | 控制兼容和升级风险 | 受控候选互通、回滚、Schema 隔离 |
| SRE/运维 | 快速定位故障 | Metrics、Tracing、JFR、Dashboard、Alert |
| 框架贡献者 | 扩展底层能力 | SPI Adapter、Codegen、Benchmark、Chaos |

## 4. 系统上下文

```mermaid
flowchart LR
    AppC[Consumer Application]
    AppP[Provider Application]
    CoreC[OTRYX RPC Consumer Runtime]
    CoreP[OTRYX RPC Provider Runtime]
    Registry[(Etcd / Nacos / Consul / Eureka)]
    Obs[Metrics / Trace / JFR]
    TLS[TLS / mTLS]

    AppC --> CoreC
    CoreC -->|Unary RPC| CoreP
    CoreP --> AppP
    CoreC <-->|Subscribe / Lookup| Registry
    CoreP <-->|Register / Unregister| Registry
    CoreC --> Obs
    CoreP --> Obs
    TLS --- CoreC
    TLS --- CoreP
```

读图重点：

- Registry 只属于控制面；
- Consumer 调用使用本地 `ServiceDirectory` 快照；
- TLS/mTLS 位于 Transport 层；
- Observability Adapter 不决定业务调用成败。

## 5. 功能需求

### FR-001 服务契约

- 服务由 Java Interface 定义。
- `@OtryxRpcContract` 可触发编译期 Consumer Stub 与 Provider Dispatcher 生成。
- 缺少生成代码时必须存在可工作的 fallback 路径。

### FR-002 Provider

- `@OtryxRpcService` 暴露服务。
- Provider 启动后向 Registry 注册实际 advertised endpoint。
- 关闭时先注销、进入 GO_AWAY/Drain，再释放资源。

### FR-003 Consumer

- `@OtryxRpcReference` 注入远程服务。
- Consumer 使用本地服务目录和负载均衡器选择 endpoint。
- 单次逻辑调用必须受整体 Deadline 约束。

### FR-004 Registry

- 已有 Memory、Etcd、Nacos、Consul、Eureka；HTTP 类型 Adapter 使用轮询式订阅。
- 按各 Adapter 声明的真实能力提供 register、unregister、lookup、subscribe。
- Registry 故障不能让一次控制面异常立即清空 last-known-good 数据面目录。

### FR-005 Transport 与协议

- 当前默认 Transport 为 Vert.x TCP。
- Wire v1 使用固定 Header + Metadata + Payload。
- 支持 HELLO/ACK、PING/PONG、CANCEL、GO_AWAY。
- 支持 TLS/mTLS。

### FR-006 Resilience

- 自动 Retry 只允许显式 `@OtryxRpcIdempotent` 方法。
- Retry 受最大 Attempt、Budget、Deadline、Backoff/Jitter 约束。
- 支持方法级 Circuit Breaker。
- 支持 endpoint Outlier Ejection。
- Provider 支持 admission 与 CPU 有界队列。

### FR-007 可观测性

- Core 提供低依赖 Observer/Tracing/Metadata 契约。
- Micrometer、OpenTelemetry、JFR 使用独立 Adapter。
- Telemetry 异常不能反向导致 RPC 失败。

### FR-008 兼容升级

- 当前候选版本的 Wire v1、Type ID 与 Schema Fingerprint 必须经 Consumer/Provider 独立进程验证。
- 尚未 GA，不承担对过去开发快照的跨版本兼容义务；Rolling Compatibility 仅证明实际测试的基线组合。
- 正式发行后另行规定兼容和演进契约，不通过历史未发布快照决定当前协议。
- 不兼容业务契约通过新的 `ServiceKey.version` 隔离。

## 6. 非功能需求

### 性能

- 热路径不得访问 Registry。
- 不应在热路径执行 SPI 扫描和配置解析。
- 性能优化必须由 Benchmark/Soak Evidence 驱动。
- 没有固定硬件证据时不得发布官方 QPS/SLO 数字。

### 可靠性

- 连接支持 heartbeat、idle detection、reconnect backoff。
- Provider 支持 graceful drain。
- Etcd/Nacos 已有真实集成与 Chaos 测试；Consul/Eureka 仍需依据各自控制面契约和恢复场景验证。
- 关键并发 race 必须有自动化回归。

### 安全

- 支持 TLS/mTLS。
- Hostname Verification 默认开启。
- 密钥和 Registry Credential 不进入日志。
- 协议输入进行边界与状态机校验。

### 可维护性

- Core 不依赖 Vert.x、Etcd、Nacos、Spring、Micrometer、OTel、JFR。
- 公共/受保护框架 API 使用中文 Javadoc。
- Runtime 日志使用英文。
- 中英文 README 同步。

## 7. 故障与边界场景

必须覆盖：

- Registry 暂停、重启、leader transfer、watch 恢复；
- silent/half-open connection；
- handshake timeout；
- request timeout / cancel / response race；
- duplicate Request ID；
- malformed header / metadata / control frame；
- Provider overload；
- Retry exhausted；
- Circuit OPEN/HALF_OPEN；
- TLS/mTLS 握手或证书错误；
- N/N+1 mixed deployment 和 rollback。

## 8. 1.0 验收标准

1. `python3 scripts/check_project.py` 通过。
2. `mvn -B -ntp clean verify -Pquality` 通过。
3. Release Readiness 通过。
4. Etcd Chaos 通过。
5. Nacos Chaos 通过。
6. Rolling Compatibility 通过。
7. 独立 JVM Examples E2E 通过。
8. README、快速开始、配置、兼容、升级、运维和安全文档完整。
9. RC/GA 历史 Release Notes 与实际候选产物状态清楚区分；本轮不生成正式版本或宣称发行已完成。
10. MIT License、Security、Contribution、Code of Conduct 对外可见。

## 9. 与后续版本的边界

正式 GA 后再定义 Java API、SPI、Wire、Schema 的演进和兼容策略，禁止未经评审静默破坏已公开契约。后续方向见根目录 [ROADMAP](../../ROADMAP.md)。
