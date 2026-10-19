# OTRYX RPC 1.0 详细设计

> OTRYX 1.0.0-SNAPSHOT 属于公开 Java API / GAV 的破坏性命名空间迁移；继承历史 Wire v1 并不代表新旧 Java API 保证互通。详见 [迁移指南](../migration.md)。

> 状态：**Current / 1.0.0-SNAPSHOT Migration**

![单次 RPC 调用链](../images/flows/rpc-lifecycle.svg)

## 1. Consumer 调用链

```mermaid
sequenceDiagram
    participant A as Application
    participant C as OtryxRpcClient
    participant D as ServiceDirectory
    participant L as P2C/EWMA
    participant T as Vertx Transport
    participant P as Provider

    A->>C: invoke(method,args)
    C->>C: create logical deadline / trace context
    C->>D: read immutable snapshot
    D-->>C: compatible instances[]
    C->>L: choose(instances, endpoint stats)
    L-->>C: endpoint
    C->>C: circuit/outlier/retry decision
    C->>T: encode + request(endpoint)
    T->>P: REQUEST
    P-->>T: RESPONSE / ERROR
    T-->>C: complete attempt
    C-->>A: result / exception
```

关键约束：

- `ServiceDirectory` 读取不触发 Registry I/O；
- 单次 logical call 可以包含多个 Attempt；
- Timeout 结束后不允许 Retry 越过 Deadline；
- CANCEL 可传播到 Provider。

## 2. Provider 请求处理

```mermaid
flowchart TD
    Frame[Inbound Frame] --> Validate[Protocol Validation]
    Validate --> Track[Connection-local Inflight]
    Track --> Admission[Provider Admission]
    Admission --> Bind[Method Binding]
    Bind --> Decode[Payload Decode]
    Decode --> Policy{Execution Mode}
    Policy -->|BLOCKING_VIRTUAL| VT[Virtual Thread]
    Policy -->|CPU| CPU[Bounded CPU Pool]
    Policy -->|DIRECT allowed| EL[Event Loop]
    VT --> Invoke[Generated Dispatcher / Fallback]
    CPU --> Invoke
    EL --> Invoke
    Invoke --> Async{CompletionStage?}
    Async -->|No| Encode[Response Encode]
    Async -->|Yes| Await[Register Completion Callback]
    Await --> ReturnExec[Dispatch Back To Provider Executor]
    ReturnExec --> Encode
    Encode --> Write[Transport Write]
```

### Admission

Provider 通过全局、服务、方法三级并发额度及已准入 Frame 字节预算保护业务资源，CPU 模式还有独立的有界队列；拒绝时返回 `OVERLOADED`。详见 [Provider 分层 Admission 设计](../reference/provider-admission.md)。这些额度不代表 JVM Heap 的硬上限。

准入租约只有在两个条件**同时满足**后归还：

1. 响应 Future 已成功、异常或取消而进入终态；
2. 实际业务执行已经结束：同步方法执行线程退出，或业务返回的异步 `CompletionStage` 已真实进入终态且必要的响应完成处理已结束。

`CompletableFuture.cancel(true)` 不能证明业务代码或外部 IO 已停止，因此不会仅凭逻辑请求取消提前释放 Provider 并发额度。取消发生在 CPU 队列中、业务尚未获得执行所有权时，框架会通过原子执行归属判定直接回收租约。已经开始的同步任务尝试中断，但必须等其真正退出后才释放。对业务自建异步 Stage 不进行强制 `cancel`，以免把 Future 的逻辑取消误判为底层执行已结束。

异步 `CompletionStage` 不占用 CPU/虚拟线程等待 `join()`。异步结果完成后，Provider 将结果序列化派发至受管理的执行器；队列饱和时返回 `OVERLOADED`。响应 Future 对外完成与最终业务执行账本归还之间可能存在极短的异步时序差，因此运维/并发测试应依据 Lease 指标判断资源释放，而不是只依赖响应 Future 终态。

### Cancellation

Transport 根据 Request ID 取消 connection-local 请求，Provider 随后尝试取消排队任务或中断正在执行的 Worker。**取消只保证逻辑请求终止，不保证用户业务立即停止**。忽略中断、永不结束的业务 Stage 会继续占用配置配额以防止超过实际执行上限；业务必须具备协作式取消或自身超时治理。

## 3. Registry 与本地目录

```mermaid
sequenceDiagram
    participant P as Provider
    participant R as Registry Adapter
    participant B as Etcd/Nacos
    participant C as Consumer Adapter
    participant D as ServiceDirectory

    P->>R: register(instance)
    R->>B: register
    C->>B: subscribe(service)
    B-->>C: snapshot/change
    C->>C: normalize + revision
    C->>D: VerifiedProviderSnapshot
    D->>D: compatibility filter
    D->>D: replace immutable array snapshot
    P->>R: unregister(instance)
    R->>B: unregister
    B-->>C: empty/updated snapshot
    C->>D: converge directory
```

### Etcd

- Lease 作为 Provider 生命周期；
- Watch 出错后重新 Range；
- compaction 后从有效 revision 恢复；
- keepalive + TTL watchdog。

### Nacos

- Provider 使用临时实例；
- SDK 阻塞调用在 Adapter 控制面执行器；
- 本地主动 unregister 后同步收敛当前进程 subscription；
- 远端 Provider 生命周期仍以 NamingEvent 为主通道。

## 4. 连接状态机

```mermaid
stateDiagram-v2
    [*] --> Connecting
    Connecting --> TLSHandshake: secure mode
    Connecting --> Hello: plaintext
    TLSHandshake --> Hello: verified
    TLSHandshake --> Closed: failure
    Hello --> Active: HELLO_ACK
    Hello --> Closed: timeout / reject
    Active --> HeartbeatWait: idle
    HeartbeatWait --> Active: PONG / inbound
    HeartbeatWait --> Closed: timeout
    Active --> Draining: GO_AWAY
    Draining --> Closed: inflight drained
    Active --> Closed: fatal error
```

## 5. Retry 时序

```mermaid
flowchart TD
    Failure[Attempt Failure] --> Retryable{Idempotent and Retryable?}
    Retryable -->|No| Final[Complete Failure]
    Retryable -->|Yes| Attempts{Max Attempts?}
    Attempts -->|Exceeded| Max[MAX_ATTEMPTS]
    Attempts -->|Available| Budget{Retry Budget?}
    Budget -->|No| BUDGET[BUDGET]
    Budget -->|Yes| Deadline{Deadline allows backoff?}
    Deadline -->|No| DEADLINE[DEADLINE]
    Deadline -->|Yes| Backoff[Exponential Backoff + Jitter]
    Backoff --> Next[Next Attempt]
    Max --> Final
    BUDGET --> Final
    DEADLINE --> Final
```

## 6. Circuit Breaker

方法级状态：

```mermaid
stateDiagram-v2
    CLOSED --> OPEN: consecutive failures
    OPEN --> HALF_OPEN: open duration elapsed
    HALF_OPEN --> CLOSED: probe success
    HALF_OPEN --> OPEN: probe failure
```

HALF_OPEN 同时只放行一个探测调用。

## 7. Graceful Shutdown

```mermaid
sequenceDiagram
    participant O as Operator
    participant P as Provider
    participant R as Registry
    participant C as Connected Consumers

    O->>P: stop
    P->>R: unregister
    P->>C: GO_AWAY(UNAVAILABLE)
    P->>P: stop accepting new requests
    P->>P: wait inflight
    alt inflight drained
        P->>P: close
    else drain timeout
        P->>P: cancel / close
    end
```

## 8. Compatibility Filter

Provider 发布不可变 ApplicationManifest；成员仅引用实际 Manifest Revision。Consumer 控制面依次验证应用成员角色、真实 Endpoint、Mapping Owner、Manifest 内容身份、Wire/Schema 版本和导出业务契约。

缺少 Manifest、内容身份不符或契约不匹配的实例不得进入本地 Provider 池。纯 Consumer 可存在于应用目录，但无 RPC Endpoint。校验后的候选才投影为 ServiceInstance 供负载均衡使用；请求热路径不访问注册中心。

## 9. Observability 事件模型

一次逻辑调用：

```text
client.calls = 1
client.attempts = 1..N
client.retries = attempts - 1
client.retry.exhausted = optional final stop reason
```

这样 QPS/SLO 不会被 Retry 放大，而 Attempt 指标仍能反映网络实际负载。

## 10. 关键并发回归

自动化覆盖：

- response vs timeout；
- response vs cancel；
- cancel vs disconnect；
- drain vs new request；
- close vs heartbeat；
- HALF_OPEN concurrent probe；
- duplicate Request ID；
- random fragmentation/coalescing。

## 11. 资源所有权

| 资源 | Owner | 生命周期 |
|---|---|---|
| ServiceDirectory | Consumer Runtime | refer -> client close |
| EndpointStats | Consumer Runtime | endpoint active -> directory removal |
| Connection Group | Transport Client | endpoint use -> client close |
| Registry Subscription | Registry Adapter | subscribe -> close |
| Provider Execution Pool | Server Runtime | server start -> close |
| Observer Adapter | Spring/Core composition | runtime lifecycle |

## 12. 错误与恢复原则

- 协议错误优先 fail-fast；
- Telemetry 错误隔离；
- Registry 短时错误保留 last-known-good；
- Retry 不能替代容量；
- Circuit/Outlier 不能隐藏长期故障；
- TLS 错误不允许自动降级到明文。


## 13. U12 内部职责隔离

本轮仅分离两个能保持现有行为、并独立编写回归测试的职责：

- `ClientRetryController`：按幂等/错误类型 → Attempt 上限 → 额度扣减 → Deadline/Backoff 的**原有顺序**计算决策，不负责安排重试或归还额度；非幂等失败不生成 retry-exhausted 信号。
- `RpcShutdownBudget`：由 Client/Server shutdown 入口创建一次单调时钟预算，后续 Controller/Transport/Executor 等阶段共享。Stage 到期不等于外部用户业务已退出。

`ClientCall` 和 `ProviderInvocation` 仍保持原有作用域，避免在没有额外所有权/延迟证据时跨对象搬移响应完成与 Admission Lease。本轮不改变 Java 公共 API、Wire v1、Retry 策略、配置默认值或行为终态。

具体入口、所有权表和故障回归见 [运行时资源所有权](../reference/runtime-ownership.md)。
