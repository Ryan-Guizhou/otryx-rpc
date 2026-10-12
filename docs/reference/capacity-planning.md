# OTRYX RPC 容量规划指南

> 状态：**GA Methodology / Environment-specific Numbers Required**

## 1. 目标

容量规划回答：

- 单实例在目标业务下能承受多少 QPS；
- p99/p99.9 是否满足业务目标；
- 需要多少 CPU/Memory；
- 每 endpoint 多少 connection shard 合适；
- Provider admission / CPU queue 如何设置；
- Retry/TLS 对容量影响多大。

## 2. 不能直接复制的数字

不要直接复制：

- 开发机结果；
- GitHub shared runner；
- 其他 CPU 型号；
- 不同 payload；
- 不同业务方法；
- 不同 TLS/Registry 组合。

## 3. 基本输入

| 输入 | 说明 |
|---|---|
| Payload | 64B / 256B / 1KiB / 16KiB / 1MiB 等 |
| Concurrency | 目标并发 |
| QPS | 业务流量 |
| Service time | Provider 业务耗时 |
| CPU | 核数与限额 |
| Memory | Heap/Native |
| TLS | PLAINTEXT/TLS/mTLS |
| Retry | 失败时放大系数 |
| Connections | 每 endpoint shard |

## 4. 建议测量流程

```mermaid
flowchart LR
    Workload[业务工作负载] --> Matrix[Benchmark Matrix]
    Matrix --> Soak[Long Soak]
    Soak --> Fault[Fault / Overload]
    Fault --> Repeat[Repeatability]
    Repeat --> Capacity[Capacity Profile]
```

## 5. QPS/Core

只在 CPU 限额稳定且 workload 明确时计算：

```text
QPS/Core = successful requests per second / available CPU cores
```

必须同时记录错误率和 tail latency，否则单独 QPS 没有意义。

## 6. Provider

需要验证：

- `max-concurrent`；
- CPU parallelism；
- CPU queue capacity；
- drain timeout；
- Virtual Thread 业务下游容量。

Admission 应保护 Provider，而不是把过载隐藏为无限排队。

PR-D（Draft）新增 [Provider 分层 Admission 与 Frame 字节预算](provider-admission.md)。多服务下静态配额按服务数量等分，保证某个服务无法抢占其他服务的保留额度；代价是空闲服务的额度不会被借用。需要验证每服务并发/字节预算、方法级上限、取消资源释放以及真实 p99/GC 影响，不可把全局请求 Frame 配额当作 JVM Heap 上限。

## 7. Consumer

需要验证：

- connections-per-endpoint；
- max-inflight-per-connection；
- timeout；
- Retry Budget；
- Circuit；
- Outlier。

Retry 放大会直接改变下游真实 QPS。

## 8. Memory

记录：

- Heap；
- GC；
- Native Memory；
- Direct Buffer；
- inflight；
- connection count。

小 payload 高 allocation 时优先看 Future/Pending/timer；大 payload 线性增长时优先看 frame copy/buffer ownership。

## 9. TLS

TLS/mTLS 必须单独测量：

- handshake；
- steady-state throughput；
- CPU；
- connection reuse；
- certificate reload。

## 10. 推荐值晋级

```text
Controlled Evidence
  -> Repeatability PASS
  -> Baseline Candidate
  -> Engineering Review
  -> Regression Closure
  -> Environment-specific Profile
```

没有走完该流程，不把数字写成官方生产推荐。

## 11. Evidence

见 [Performance Evidence](performance-evidence.md)。


## U04：已编码 RESPONSE 字节预算的边界

Provider 资源分为 Transport 连接/HELLO、残帧字节、每连接在途数、Core 分层请求 Frame/真实业务 Lease，以及每个 Transport Server 新增的 **已编码 RESPONSE 待写额度**。不同所有权范围不能合计得出 JVM/Native 的内存硬上限。

`otryx.rpc.transport.admission.max-buffered-response-bytes` 默认 67108864（64 MiB；未经过受控容量矩阵确定）。Vert.x 在调用 `socket.write()` 前获取 `ResponseByteBudget.Lease`，对应写 Future 完成/失败或 Socket 断连时归还；Drain 同时等待仍存在的写入 Lease。额度耗尽时用 OVERLOADED/GO_AWAY 及时拒绝，不阻塞 EventLoop。

**无法约束的内存：** Fory 返回值编码期间的峰值、业务对象图、Direct/TLS 缓冲、Socket 写队列和操作系统内核缓存。这些指标需要结合 GC、堆/Native、Socket backpressure、吞吐和 p99/p99.9 的固定硬件矩阵才能设置安全生产上限。CI 慢读 Socket 回归只能验证框架响应 Lease 有界，不能证明整个网络栈、JVM 或远端消费有界。
