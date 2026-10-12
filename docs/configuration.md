# OTRYX RPC 生产配置与安全加固

> 状态：**1.0.0-SNAPSHOT Migration / Numeric Capacity Values Require Environment Evidence**

## 1. 配置原则

生产配置分为：

| 类型 | 来源 |
|---|---|
| 协议/安全默认值 | 框架固定 |
| 容量参数 | 目标环境 Evidence |
| 业务策略 | 应用自己的延迟、吞吐、幂等和可用性目标 |

## 2. Transport

必须评审：

- connections-per-endpoint；
- max-inflight-per-connection；
- max-frame-bytes；
- write queue；
- connect/handshake timeout；
- heartbeat interval/timeout；
- reconnect backoff。

危险做法：

- 无依据扩大 frame/inflight；
- heartbeat timeout 小于正常 GC/网络抖动窗口；
- reconnect backoff 过小形成恢复风暴。

### Transport 接入准入预算（U03-A）

`otryx.rpc.transport.admission.max-connections`（默认 4096）约束 Vert.x 已接入连接；`max-handshakes`（默认 1024）约束同时进行的 OTRYX HELLO 协议握手，失败即时关闭 Socket、关闭后归还幂等 Lease。注意二者均**不能**直接覆盖 TLS 预握手阶段、TCP backlog 或操作系统 Socket 内存。

`max-buffered-partial-frame-bytes` 和 `partial-frame-timeout` 目前仅为已绑定配置，服务端全局部分帧与超时治理将由 U03-B 实现；入站单连接 Inflight 约束由 U03-C 实现。详见 [Transport 接入容量](reference/transport-admission.md)。

## 3. Provider

默认：

- BLOCKING_VIRTUAL；
- CPU 有界线程池；
- DIRECT 默认禁止。

DIRECT 只能用于经过证明的极短非阻塞逻辑。

容量参数：

- max-concurrent；
- 分层 Admission 的 max-inflight-bytes / 每服务与方法限制（见 [Provider Admission 指南](reference/provider-admission.md)，当前已实现；生产配额仍需受控负载数据验证）；
- CPU parallelism；
- CPU queue capacity；
- drain timeout（排空阶段的上限，默认 30 秒）；
- `otryx.rpc.server.shutdown-timeout`（Spring Provider 共享总关闭预算，默认 25 秒；先于排空单项预算到期时以总预算为准）。

关闭并不等于强停用户代码：`shutdown(RpcShutdownOptions)` 返回 `CompletionStage<RpcShutdownReport>`，报告注销、Transport 关闭、线程池终止和剩余 Admission Lease。未响应中断的业务任务可以超出框架总关闭预算；不得因 Future 取消就提前归还仍占用的业务配额。Spring `SmartLifecycle.stop(Runnable)` 在关闭 Stage 有结果后才调用容器回调；默认 25 秒为通常 30 秒的 Spring 阶段等待留出调度余量，如调整阶段超时需与实际排空要求一致。

## 4. Consumer Resilience

生产评审：

- timeout；
- max attempts；
- Retry Budget；
- retry backoff；
- Circuit threshold/open duration；
- Outlier threshold/ejection duration。

Retry/Circuit/Outlier 是故障控制，不是容量替代。

## 5. TLS/mTLS

生产优先 TLS/mTLS：

- hostname verification 保持开启；
- 私钥只授予运行用户；
- Trust material 独立管理；
- 监控证书过期；
- 验证在线 Reload；
- TLS 失败禁止回退 PLAINTEXT。

详见 [安全指南](security.md)。

## 6. Registry

### Etcd

评审 endpoint 集群、namespace、Lease TTL、凭据/TLS、Watch/Lease 恢复告警。

### Nacos

评审 endpoint、namespace、group、cluster、username/password 和 SDK 恢复行为。

### Consul

使用 Consul Agent API，配置 `otryx.rpc.registry.type=consul`，endpoint URL、逻辑 namespace、可选 datacenter/Enterprise namespace 与 ACL token；Provider 使用 TTL Check 并主动 Pass 续约，Consumer 通过 `/v1/health/service` 只读取 passing 节点。订阅使用有界控制线程定期刷新，不承诺原生 Watch。详见 [Consul 接入](reference/registry-consul.md)。

### Eureka

使用 Eureka REST API，配置 `otryx.rpc.registry.type=eureka`，包含完整 `/eureka` context-path 的 endpoint URL、可选 Basic Auth、Lease 和查询校对周期。Provider 续约失败 404 时重新注册；Consumer 仅接收 UP 节点，订阅通过有界定时轮询实现。详见 [Eureka 接入](reference/registry-eureka.md)。

Registry 不进入单次 RPC 热路径。

## 7. JVM

至少记录：

- Heap；
- GC；
- CPU/Container limit；
- Native Memory；
- Direct Buffer；
- JFR 策略。

Benchmark JVM 参数不能未经验证直接复制到生产。

## 8. Observability

至少部署：

- logical call QPS/latency/error；
- retry / retry exhausted；
- circuit state/reject；
- outlier ejection；
- Provider admission；
- connection/heartbeat；
- Registry failure/recovery；
- TLS handshake/reload；
- Trace 或等价链路诊断。

见 [生产可观测与 SLO](reference/production-observability.md)；10k 逻辑并发、实际峰值在途量和错误率的差异请参阅 [Soak 质量验收](reference/soak-acceptance.md)。

## 9. 容量数字

任何 QPS、线程数、连接数、Heap、p99/p99.9 推荐值都必须来自目标环境 Evidence。见 [容量规划](reference/capacity-planning.md)。
