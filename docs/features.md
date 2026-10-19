# OTRYX RPC 1.0 功能描述

> OTRYX 1.0.0-SNAPSHOT 属于公开 Java API / GAV 的破坏性命名空间迁移；继承历史 Wire v1 并不代表新旧 Java API 保证互通。详见 [迁移指南](migration.md)。

> 状态：**1.0.0-SNAPSHOT Migration**

## 1. 功能总览

```mermaid
mindmap
  root((OTRYX RPC 1.0))
    调用模型
      Unary RPC
      Generated Stub
      Proxy fallback
    服务治理
      Registry
      P2C + EWMA
      Retry
      Circuit Breaker
      Outlier Ejection
    高可用
      Heartbeat
      Reconnect
      Graceful Drain
      Cancel Propagation
    安全
      TLS
      mTLS
      Hostname Verification
      Certificate Reload
    可观测
      Micrometer
      OpenTelemetry
      JFR
    兼容
      Wire v1
      Stable Type ID
      Schema Fingerprint
      Rolling Upgrade
      Rollback
```

## 2. API 与 Codegen

- Java Interface 作为服务契约；
- `@OtryxRpcContract` 编译期生成 Consumer/Provider 路径；
- Generated path 缺失时使用 Proxy / MethodHandle fallback；
- CGLIB 和 Byte Buddy 为可选 Proxy Adapter。

## 3. Registry

Provider、Consumer、BOTH 都使用应用身份；纯 Consumer 无 RPC Endpoint。ServiceKey 是业务契约，Manifest 描述实际导出，实例 Owner Mapping 指向应用；ServiceInstance 只作为已验证本地 LB DTO。

| 后端 | 应用级能力与边界 |
| --- | --- |
| Memory | 单 Registry 对象内成员与元数据；用于同 JVM 测试，不跨进程 |
| Etcd | 应用 Lease、不可变 Manifest、实例 CAS、Range/Watch、Compaction/租约恢复 |
| Nacos | Naming 成员、Config Manifest/Mapping CAS、预初始化分片、健康校对与恢复 |
| Consul | 单 Agent TTL Check、Session 身份 Claim、KV CAS、Owner fencing、有界轮询 |
| Eureka | 原生应用成员/Lease，显式外置可靠 Metadata Backend，Owner 健康交叉校验 |

原生 Revision、轮询/Watch 和资源预算各不相同，详见[应用级设计](design/application-registry-v2.md)。四真实后端的共享契约使用 30 个真实接口与 10 个独立 client 对象；独立 JVM process/chain/revision 和 Chaos 是另外的证据层，不能把对象数称为业务进程数。门禁与实测边界见[应用级注册验收](reference/application-registry-acceptance.md)。

## 4. Transport

Vert.x Transport 提供：

- TCP 长连接；
- 每 endpoint 多连接分片；
- connection-local Request ID；
- handshake timeout；
- heartbeat；
- idle detection；
- reconnect backoff；
- GO_AWAY / graceful drain；
- CANCEL；
- TLS/mTLS。

## 5. Protocol

Wire v1 包含：

- 固定 Header；
- Metadata；
- Payload；
- HELLO / HELLO_ACK；
- REQUEST / RESPONSE；
- PING / PONG；
- CANCEL；
- GO_AWAY。

协议实现会拒绝：

- bad magic；
- unsupported version；
- unknown type/status；
- invalid flags/compression；
- invalid length；
- malformed metadata；
- 错误握手顺序；
- duplicate Request ID；
- 非法控制帧。

## 6. Codec

默认 Fory。

方法绑定期间执行 Stable Type ID 冲突检查。Schema Fingerprint 用于服务级兼容判断，但不改变 Fory v1 Payload 格式。

## 7. 负载均衡

默认 `p2c-ewma`：

- Power of Two Choices；
- EWMA latency；
- inflight；
- static weight；
- outlier state。

选择过程读取本地数组快照，不访问 Registry。

## 8. Retry / Circuit / Outlier

### Retry

只有 `@OtryxRpcIdempotent` 方法可自动重试。

### Circuit

方法级 CLOSED / OPEN / HALF_OPEN。

### Outlier

按 endpoint 基础设施失败做本地临时剔除。

## 9. Provider Execution

| 模式 | 用途 | 边界 |
|---|---|---|
| BLOCKING_VIRTUAL | JDBC、文件、同步 SDK | 默认 |
| CPU | CPU 密集 | 有界线程数和队列 |
| DIRECT | 极短纯内存 | 默认禁止 |

## 10. Security

- PLAINTEXT / TLS / MTLS；
- hostname verification；
- CA trust；
- certificate/private-key/trust material；
- PEM reload；
- certificate expiry warning；
- Credential/Secret 不进入运行日志。

## 11. Observability

### Micrometer

覆盖 logical call、attempt、retry、retry exhausted、timeout、inflight、Circuit state/reject、Outlier、Provider admission、Connection、Registry、TLS 等。

### OpenTelemetry

CLIENT/SERVER Span + W3C Trace Context/Baggage。

### JFR

记录慢调用、失败、reconnect、heartbeat timeout、Registry recovery、TLS/证书事件。

## 12. Spring Boot Starter

- `@OtryxRpcService`；
- `@OtryxRpcReference`；
- 按实际 Provider/Consumer 使用惰性创建 Runtime；
- Provider advertised endpoint 与 bind endpoint 分离；
- 支持 Registry、Transport、Security、Execution、Observability 配置。

## 13. Compatibility

- Wire v1；
- Stable Type ID；
- Schema Fingerprint v1；
- N Consumer -> N+1 Provider；
- N+1 Consumer -> N Provider；
- N/N+1 mixed；
- Provider rollback。

自动化矩阵见 [Wire Compatibility](wire-compatibility.md)。

## 14. 工程化

- Maven Reactor；
- Javadoc doclint；
- Repository static gate；
- Independent JVM E2E；
- JMH benchmark；
- 10k logical-concurrency soak harness；
- Etcd/Nacos Chaos；
- Rolling Compatibility；
- Release Readiness；
- RC1/GA Release Workflow。

## 15. 1.0 不包含

- Streaming RPC；
- 跨语言 SDK；
- Protobuf IDL；
- ZooKeeper Adapter（Consul / Eureka Adapter 已在源码中按需提供，应用级接入使用唯一控制面）；
- HTTP/2/gRPC Transport；
- 非 NONE 数据面压缩。

这些内容若进入后续版本，须独立设计和验证；当前未发布快照不承诺历史开发 API 兼容。
