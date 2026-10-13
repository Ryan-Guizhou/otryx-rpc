# OTRYX RPC TLS / mTLS 安全指南

> Fory Native 反序列化安全模式、类型白名单与资源上限参见 [Fory 安全模式迁移指南](reference/fory-security.md)。TLS/mTLS 不替代对象类型约束。

> 适用版本：**OTRYX RPC 1.0.x**。  
> TLS/mTLS 位于 Transport 层，不改变 OTRYX RPC v1 固定协议头，也不替代业务授权。

## 1. 定位

OTRYX RPC 的安全链路为：

~~~text
OtryxRpcClient / OtryxRpcServer
        |
OTRYX RPC Protocol
        |
TLS / mTLS
        |
Vert.x TCP
        |
Network
~~~

三种模式：

| 模式 | 通道加密 | Consumer 校验 Provider | Provider 校验 Consumer |
|---|---:|---:|---:|
| PLAINTEXT | 否 | 否 | 否 |
| TLS | 是 | 是 | 否 |
| MTLS | 是 | 是 | 是 |

默认仍为 `PLAINTEXT`，保证现有应用升级后行为不变。

## 2. Spring Boot 配置

~~~yaml
otryx:
  rpc:
    transport:
      security:
        mode: MTLS
        certificate-path: /etc/otryx/tls/tls.crt
        private-key-path: /etc/otryx/tls/tls.key
        trust-certificate-path: /etc/otryx/tls/ca.crt
        hostname-verification: true
        handshake-timeout: 3s
        reload-interval: 30s
        expiry-warning-threshold: 7d
~~~

### TLS Provider

Provider 必须配置：

- `certificate-path`
- `private-key-path`

### TLS Consumer

Consumer 必须配置：

- `trust-certificate-path`

默认启用 Hostname Verification。

### mTLS

Provider 与 Consumer 都必须配置：

- certificate
- private key
- trust certificate

Provider 使用 `ClientAuth.REQUIRED`，因此缺少合法客户端证书时握手失败。

## 3. 连接生命周期

TLS/mTLS 在 OTRYX RPC HELLO 之前完成：

~~~mermaid
sequenceDiagram
    participant C as Consumer
    participant P as Provider

    C->>P: TCP connect
    C->>P: TLS ClientHello
    P->>C: TLS certificate
    opt mTLS
        C->>P: Client certificate
    end
    Note over C,P: certificate verification
    C->>P: Peach HELLO
    P->>C: HELLO_ACK
    Note over C,P: ACTIVE
~~~

TLS 失败不会 fallback 到 plaintext。

## 4. 证书校验

启动阶段会：

1. 校验证书/私钥/CA 文件可读；
2. 解析 X.509 certificate；
3. 执行有效期校验；
4. 对临近过期证书产生 Observer warning；
5. 构造 Vert.x SSL options。

无效或过期证书会 fail-fast。

## 5. Hostname Verification

默认开启：

~~~yaml
otryx.rpc.transport.security.hostname-verification: true
~~~

Consumer 通过目标 RPC host 与证书身份执行校验。

生产环境不建议关闭。测试已覆盖证书主机名与目标 host 不匹配时握手失败。

## 6. 在线证书 Reload

OTRYX RPC 周期检查以下 material 的 SHA-256 内容指纹、mtime 与 size：

- server certificate
- private key
- trust certificate

文件状态发生变化后：

~~~text
file changed
   -> parse and validate new material
   -> updateSSLOptions
   -> new connection uses new certificate
   -> existing connection remains alive
~~~

新 material 无效时：

~~~text
reload validation failed
   -> report FAILURE observation
   -> keep previous valid SSL material
   -> existing/new connection continues with previous material
~~~

不会因为换证主动批量断开所有长连接。

## 7. 与连接高可用的协同

TLS 已通过以下组合测试：

- TLS + Peach HELLO/ACK；
- TLS + Heartbeat；
- TLS 长时间空闲后继续 RPC；
- Provider restart；
- 同一个 TLS Consumer 不重启恢复调用；
- reconnect/backoff 与 TLS 重建共同工作。

因此 TLS 不是旁路实现，而是现有 Connection HA 生命周期的一部分。

## 8. 可观测事件

Core `RpcObserver` 提供：

- `onTlsHandshakeCompleted`
- `onCertificateReloadCompleted`
- `onCertificateExpiryWarning`

Micrometer/JFR Adapter 可以消费这些事件。

TLS handshake failure 会归一化到 Observer，不需要解析日志文本。

## 9. 安全边界

mTLS 证明的是服务 Transport 身份，不等于业务授权。

~~~text
TLS/mTLS
    -> transport encryption / service identity
    -> Internal Token / application identity
    -> method/resource authorization
~~~

因此 mTLS 不替代 Peach Cloud 等业务系统自己的 Internal Token、Permission 或审计策略。

## 10. 自动化验证

当前真实网络测试覆盖：

- trusted TLS success；
- wrong CA fail；
- hostname mismatch fail；
- mTLS success；
- missing client certificate fail；
- expired certificate fail-fast；
- valid certificate reload；
- invalid reload keeps old material；
- TLS + Heartbeat + reconnect；
- plaintext Transport regression 由原有 Transport suite 持续覆盖。

## U05：网络身份与授权接入（1.0.0-SNAPSHOT）

- **Spring 显式配置**：`otryx.rpc.security-profile` 不允许省略。
  `TRUSTED_DEVELOPMENT` 仅在受隔离的开发/可信环境使用，
  `NETWORK_MTLS` 强制 Transport mTLS 与 Fory STRICT_ALLOWLIST。
- **可信身份来源**：Vert.x 只在 mTLS 客户端证书经 TLS 信任链验证后提取
  Subject 和证书 SHA-256 指纹；明文/单向 TLS 均视为匿名，
  即使 Metadata 声称用户名或管理员权限也不可信。
- **解码前授权**：Core `RpcAuthorizer` 异步接受
  `RpcAuthorizationContext(peer, serviceKey, methodId, remote)`；
  除 mTLS 身份之外，需要按业务边界注入 ServiceKey/MethodId 权限策略。
  拒绝不会反序列化请求 DTO，也不会运行业务方法。
- **容量与关闭**：`server.authorization.max-pending` 默认 1024，
  `server.authorization.timeout` 默认 3s；逻辑超时不会中止正在
  执行的第三方授权 Stage，实际完成前仍占槽位。不要阻塞 EventLoop。
- **程序化 API 尚有过渡入口**：无参 Fory 和未显式加策略的 Core Builder
  保留可信兼容行为；新程序化生产接入须显式传入 STRICT_ALLOWLIST、
  mTLS 和 `RpcAuthorizer`，不能将其理解为已经默认 fail-closed。

详细白名单规则、Wire 限制见 [Fory 安全说明](reference/fory-security.md)。

授权等待阶段新增 `server.authorization.max-buffered-request-bytes`（默认
67108864），对尚未通过授权的完整请求 Frame 独立做 CAS 字节准入。
超过上限直接 OVERLOADED；逻辑超时/取消最终释放该阶段字节额度，
第三方授权真正完成之前仍持有独立的并发槽位。授权等待结束再次
检查发送方提供的剩余时间预算（优先于可能跨机器偏差的 Epoch Deadline）。
此预算不是 JVM 堆上限，也不替代业务执行和 Transport 已有预算。
