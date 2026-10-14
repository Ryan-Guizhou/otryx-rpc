# OTRYX RPC 可观测性

> 状态：**1.0.0-SNAPSHOT Migration**  
> Core 不直接依赖 Micrometer、OpenTelemetry 或 JFR。

## 1. 架构边界

```mermaid
flowchart LR
    Core[otryx-core]
    Observer[RpcObserver]
    Trace[RpcTracingBridge]
    Meta[RpcMetadataPropagator]
    Micro[Micrometer Adapter]
    OTel[OpenTelemetry Adapter]
    Jfr[JFR Adapter]

    Core --> Observer
    Core --> Trace
    Core --> Meta
    Observer --> Micro
    Trace --> OTel
    Observer --> Jfr
```

未安装 Adapter 时使用 NOOP 路径。

## 2. Micrometer

依赖：

```xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-observability-micrometer</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Spring Context 中存在 `MeterRegistry` 时可自动装配 RPC Observer。

### 标准指标

- `otryx.rpc.client.calls`；
- `otryx.rpc.client.attempts`；
- `otryx.rpc.client.retries`；
- `otryx.rpc.client.retry.exhausted`；
- `otryx.rpc.client.inflight`；
- `otryx.rpc.client.timeouts`；
- `otryx.rpc.client.circuit.rejected`；
- `otryx.rpc.client.circuit.state`；
- `otryx.rpc.client.outlier.ejected`；
- `otryx.rpc.server.invocations`；
- `otryx.rpc.server.inflight`；
- `otryx.rpc.server.admission.rejected`；
- `otryx.rpc.server.overloaded`；
- `otryx.rpc.connection.active`；
- `otryx.rpc.connection.established`；
- `otryx.rpc.connection.reconnects`；
- `otryx.rpc.connection.heartbeat.timeouts`；
- `otryx.rpc.connection.closed`；
- `otryx.rpc.registry.operations`；
- `otryx.rpc.registry.failures`；
- `otryx.rpc.registry.recoveries`；
- `otryx.rpc.tls.handshake`；
- `otryx.rpc.tls.certificate.reload`；
- `otryx.rpc.tls.certificate.expiry.warnings`。

默认不把 endpoint、instanceId、service、method、exception message、traceId 放进 Meter Tag，避免高基数。

## 3. Call 与 Attempt

一次业务调用可能产生多个网络 Attempt：

```text
client.calls = 1
client.attempts = 1..N
client.retries = attempts - 1
```

业务 QPS、成功率、Latency SLO 以 logical call 为主；Attempt 用于诊断 Retry Amplification。

## 4. OpenTelemetry

依赖：

```xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-observability-opentelemetry</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

真实 RPC 路径传播 W3C Trace Context/Baggage。

```mermaid
sequenceDiagram
    participant A as Application
    participant C as Consumer
    participant W as Wire Metadata
    participant P as Provider

    A->>C: invoke
    C->>C: CLIENT span
    C->>W: inject context
    W->>P: REQUEST
    P->>P: extract + SERVER span
    P-->>C: RESPONSE
    P->>P: end SERVER span
    C->>C: end CLIENT span
```

## 5. JFR

依赖：

```xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-observability-jfr</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

启用示例：

```yaml
otryx:
  rpc:
    observability:
      jfr:
        enabled: true
        slow-threshold: 100ms
```

JFR 用于低频高价值诊断：慢/失败调用、Retry、reconnect、heartbeat timeout、Registry recovery、TLS/证书事件。

## 6. Metadata 安全边界

- key/value 不允许换行；
- key 不允许 `=`；
- key/value 有长度限制；
- `RpcCallOptions` 传播元数据最多 64 项、总 UTF-8 字节预算 8KiB；
- Wire 帧上限另行校验，不能替代应用 Metadata 校验；
- Deadline/Timeout Budget 为保留键；
- 未启用 Trace Adapter 时不创建额外 Trace Metadata。

## 7. 故障隔离

> telemetry failure must not become RPC failure.

Observer/Propagator Adapter 回调异常必须被隔离，不得改变 RPC 业务成功与否。

Micrometer 的 Circuit 状态缓存默认最多跟踪 4096 个服务/方法组合，可通过
`new MicrometerRpcObserver(registry, maxTrackedCircuits)` 显式调整容量。
新增组合超过上限时累加低基数计数器 `otryx.rpc.client.circuit.tracking.dropped`，
不会无限增加状态缓存；此时 Circuit Gauge 只表示已被跟踪的子集。
未知 Registry 类型统一为 `custom`，未知 Admission 原因统一为 `other`，
避免把自定义 SPI 名称或未经限制的字符串扩张为动态时序。

对于不受控制的自定义 Observer，可显式包装
`new BoundedAsyncRpcObserver(delegate, queueCapacity)`。
它采用单 daemon 线程和有界队列；普通事件过载会丢弃并由 `droppedEvents()`
计数，失败回调由 `failedCallbacks()` 计数。Client/Server inflight 和
Provider 已准入字节 Delta 独立聚合，队列满不丢失净值，但短时间峰值可能被合并。
异步出口不会自动继承调用方 ThreadLocal，不适合强一致审计或可靠业务消息。
`close()` 不等待忽略中断的第三方回调，所有者应正确安排生命周期。

## 8. 生产入口

- [生产可观测与 SLO](reference/production-observability.md)
- [Grafana Dashboard](../deploy/observability/grafana/otryx-dashboard.json)
- [Prometheus Alert Example](../deploy/observability/prometheus/otryx-alerts.example.yml)
