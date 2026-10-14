# OTRYX RPC 生产可观测与 SLO 模板

> 状态：**1.0.0-SNAPSHOT Migration / SLO Values Environment-specific**  
> 本文提供指标语义、Dashboard、告警模板和 SLO 建模方式。项目不预设 Production SLO 数字。

## 1. Golden Signals

OTRYX RPC 生产观测按四类信号组织：

| 信号 | 主要指标 |
|---|---|
| Traffic | logical client calls、server invocations |
| Latency | logical client call / server invocation Timer |
| Errors | final status、failure category |
| Saturation | provider admission reject、retry、circuit、outlier、active connections |

## 2. 为什么区分 Call 与 Attempt

一个业务 RPC 可能因为 Retry 产生多个网络 Attempt。

因此：

- otryx.rpc.client.calls：逻辑调用，一次用户调用只计一次；
- otryx.rpc.client.attempts：网络 Attempt，可被 Retry 放大；
- otryx.rpc.client.retries：Retry 调度次数。

业务 QPS、业务成功率和业务延迟 SLO 应以 logical call 为主，Attempt 用于诊断 Retry Amplification。

## 3. 标准 Micrometer 指标

核心指标包括：

- otryx.rpc.client.calls
- otryx.rpc.client.attempts
- otryx.rpc.client.retries
- otryx.rpc.client.retry.exhausted
- otryx.rpc.client.inflight
- otryx.rpc.client.timeouts
- otryx.rpc.client.circuit.rejected
- otryx.rpc.client.circuit.state
- otryx.rpc.client.outlier.ejected
- otryx.rpc.server.invocations
- otryx.rpc.server.inflight
- otryx.rpc.server.inflight.bytes（PR-D：Core 已准入请求的 Frame 字节数，不含 Transport 队列与对象图）
- otryx.rpc.server.admission.rejected
- otryx.rpc.server.overloaded
- otryx.rpc.connection.active
- otryx.rpc.connection.reconnects
- otryx.rpc.connection.heartbeat.timeouts
- otryx.rpc.registry.operations
- otryx.rpc.registry.failures
- otryx.rpc.registry.recoveries
- otryx.rpc.tls.handshake
- otryx.rpc.tls.handshake.failures
- otryx.rpc.tls.certificate.reload
- otryx.rpc.tls.certificate.expiry.warnings

Client Call/Attempt 和 Server Invocation 使用低基数 status/category 标签。

默认不使用：

- endpoint；
- instanceId；
- service name；
- method ID；
- exception message；
- trace ID

作为 Meter Tag。

这些高基数维度应通过 Trace、日志或受控的自定义 Observer 查询。

## 4. Failure Category

统一低基数分类：

- NONE
- CLIENT
- PROVIDER
- TRANSPORT
- REGISTRY
- SECURITY
- PROTOCOL
- RESILIENCE
- UNKNOWN

RpcFailureClassifier 将 RpcStatus 和 Framework Exception 映射到上述分类。

## 5. Prometheus Histogram

Grafana p50/p99/p99.9 面板要求 MeterRegistry 发布 Timer Histogram。

Spring Boot 场景可显式启用：

~~~yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        otryx.rpc.client.calls: true
        otryx.rpc.server.invocations: true
~~~

是否启用更多 Timer Histogram 需要评估 Prometheus Series 数量。

## 6. Dashboard

仓库提供：

[Grafana Dashboard](../../deploy/observability/grafana/otryx-dashboard.json)

主要面板：

- Logical RPC QPS；
- RPC Error Rate；
- Active Connections；
- Retry Rate；
- p50/p99/p99.9；
- Client/Server inflight；
- Timeout rate；
- Circuit state / reject、Outlier、Admission；
- Registry / TLS / Heartbeat failure signals。

Dashboard 使用 Prometheus 常见 Micrometer 命名规则。

## 7. Alert Rules

仓库提供：

[Prometheus Alert Example](../../deploy/observability/prometheus/otryx-alerts.example.yml)

当前示例只对明确的运维异常信号给出基础告警：

- TLS handshake failure；
- Registry operation failure；
- Heartbeat timeout；
- Provider admission rejection。

该文件是 Example，不是 Production SLO Policy。实际环境需要调整 duration、severity、routing 和静默策略。

## 8. SLO 模板

项目不硬编码统一 SLO 数值。每个生产环境至少需要定义：

~~~text
Availability target: <environment target>
Latency p99 target: <environment target>
Latency p99.9 target: <environment target>
Error-budget window: <environment window>
Provider saturation policy: <environment policy>
~~~

### Availability

建议基于 logical client calls：

~~~text
successful logical calls
------------------------
all logical calls
~~~

需要明确 BUSINESS_ERROR 是否计入基础设施可用性。不要在查询阶段临时改变口径。

### Latency

延迟目标使用 logical client call Timer，避免 Retry Attempt 独立计数造成分母扭曲。

### Retry Amplification

单独观察：

~~~text
attempt rate / logical call rate
retry rate / logical call rate
~~~

Retry Amplification 持续升高通常比最终 Error Rate 更早暴露故障。

## 9. Capacity 与 SLO 的关系

SLO 不应该反向拍脑袋生成线程数。

正确流程：

~~~text
SLO / Traffic forecast
        |
Fixed-hardware baseline
        |
QPS/Core + latency + allocation
        |
Capacity Planning
        |
maxConcurrent / connections / replicas
        |
Load & chaos validation
~~~

具体容量数字仍由目标环境的受控 Performance Evidence 决定。

## 10. 故障排查顺序

推荐按以下顺序排查：

1. logical calls error/latency；
2. retry amplification；
3. circuit/outlier；
4. provider admission；
5. connection/heartbeat/reconnect；
6. registry failures/recovery；
7. TLS；
8. Trace；
9. JFR；
10. 固定 Evidence 与容量模型对比。

Telemetry Adapter 的异常不得传播回 RPC 数据面。

## 11. Circuit 状态缓存与标签基数（U10-A）

Micrometer 内部对不同服务/方法 Circuit 状态的跟踪数量默认限制为 4096，
可以通过 `MicrometerRpcObserver(registry, maxTrackedCircuits)` 显式调整。
新键超过容量时累计 `otryx.rpc.client.circuit.tracking.dropped`；
超限时 Circuit Gauge 仅代表被跟踪子集，不能宣称等于全部实际 Circuit。
Registry Type 仅使用内置值及统一 `custom`，Admission Reason 仅使用
框架既定值及统一 `other`；从任何第三方 SPI 获得的任意字符串都不能直接成为标签。

这只解决标签基数和缓存容量，不解决同步自定义 Observer 可能阻塞 EventLoop 的问题；
外部慢速遥测须在下一步使用明确的有界异步出口。
