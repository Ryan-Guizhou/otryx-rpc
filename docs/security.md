# OTRYX RPC 网络身份、TLS 与授权

> 适用版本：**1.0.0-SNAPSHOT / JDK 21**。本文说明当前实现的安全契约。
> Wire v1 固定协议头与 Fory Native Codec ID 1 不变；安全配置和 Java SPI 必须按本版本迁移。
> 类型白名单与解码预算另见 [Fory 安全指南](reference/fory-security.md)，完整属性见 [配置参考](configuration.md)。

## 1. 必须显式选择安全 Profile

Spring Boot 与程序化 Core Builder 都要求设置 `RpcSecurityProfile`。
省略配置或使用 `UNSPECIFIED` 会在运行资源激活前失败，不再隐式选择可信开发模式。

| Profile | 适用范围 | 启动条件 |
|---|---|---|
| `TRUSTED_DEVELOPMENT` | 明确隔离且信任参与者的开发环境 | 显式选择；允许匿名 Transport 和兼容 Codec，不能据此声明网络身份可信 |
| `NETWORK_MTLS` | 需要验证应用身份和方法权限的网络调用 | 实际 mTLS、有限应用证书绑定、安全 Codec 能力、受保护的 Registry 来源；启用 Provider 时还必须显式提供授权器 |

Transport 仍提供 `PLAINTEXT`、`TLS`、`MTLS` 三种模式。单向 TLS 验证 Provider 的证书链并加密通道，
mTLS 还验证 Consumer 证书链；只有 mTLS 加上本地应用绑定才能产生已认证的 `RpcPeerIdentity`。
明文和单向 TLS 的应用身份为匿名，均不能满足 `NETWORK_MTLS`。

`NETWORK_MTLS` 检查最终被调用对象的能力。Core 读取实际 Transport、整个 `RpcCodecRegistry`、
绑定后的 `RpcMethodCodec` 以及所有 Registry 通道来源，拒绝未知或不完整的描述。
配置中写了 `MTLS`、类名包含 “secure”、只替换默认 Codec，均不能替代这些检查。
自定义扩展属于进程内可信代码，必须真实实施它报告的能力；descriptor 不是第三方代码的隔离机制。

实现依据：[Core 安全预检][core-checks]、[Transport 能力契约][transport-descriptor]。

## 2. 应用身份来自完整叶证书绑定

部署方提供 `RpcCertificateBinding(certificateSha256, application, validUntil)`：

- `certificateSha256` 是**实际叶证书完整 DER 编码的 SHA-256**，格式为 64 位十六进制；
- `application` 是完整的 `ApplicationKey(namespace, applicationName)`；
- `validUntil` 是有限的失效时刻，等于该时刻即失效，不能使用无限期限哨兵。

证书 Subject、CN、仅公钥的哈希和请求 Metadata 都不是应用身份来源。
指纹绑定也不取代 TLS 信任链、证书有效期或客户端 hostname 校验。
Consumer 必须验证对端绑定与预期目标应用完全一致；Provider 先验证 Consumer 属于自己的 namespace，
再交给方法授权策略决定该 Consumer 应用是否有权调用。

绑定表必须包含实际本机叶证书，以及允许建立连接的对端叶证书。
本机应用身份来自实际的应用成员配置，不能另外配置一个不同的 Transport 本机应用来绕过校验。
同一指纹不能同时绑定不同应用或不同期限；Spring 配置列表最多 1024 项。

可从受控的叶证书文件计算指纹：

~~~bash
openssl x509 -in /etc/otryx/tls/tls.crt -outform DER | openssl dgst -sha256
~~~

以下仅展示 Transport 与应用绑定片段。`${...}` 必须由部署环境提供；
完整网络启动还需要安全 Codec、Registry 配置，以及 Provider 的显式 `RpcAuthorizer`。

~~~yaml
otryx:
  rpc:
    security-profile: NETWORK_MTLS
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
        identity:
          certificate-bindings:
            - certificate-sha256: "${OTRYX_LOCAL_CERT_SHA256}"
              application:
                namespace: "${OTRYX_RPC_NAMESPACE}"
                application-name: "${OTRYX_RPC_APPLICATION}"
              valid-until: "${OTRYX_LOCAL_BINDING_VALID_UNTIL}"
            - certificate-sha256: "${OTRYX_PEER_CERT_SHA256}"
              application:
                namespace: "${OTRYX_RPC_NAMESPACE}"
                application-name: "${OTRYX_PEER_APPLICATION}"
              valid-until: "${OTRYX_PEER_BINDING_VALID_UNTIL}"
~~~

`RpcPeerIdentity.authenticated()` 只说明身份结构已经认证，不保证此刻仍有效。
有效期取绑定 `validUntil` 与叶证书 `notAfter` 的较早者，并同时检查证书 `notBefore`、
原始验证时刻的壁钟和单调时间。连接复用、会话恢复或壁钟回退都不能重新发放已消耗的有效期。

实现依据：[绑定结构][certificate-binding]、[有限连接身份][peer-identity]。

## 3. 准备阶段与实际 TLS 握手

Vert.x Transport 构造时读取并冻结 PEM 材料，每份文件最多 1 MiB；校验证书可解析、当前有效、
私钥与叶证书匹配，并准备实际使用的 KeyManager、TrustManager 和不可变安全 descriptor。
读取 descriptor 不再读取文件，也不创建 Vert.x、监听端口、连接或后台重载任务。
实际激活使用这一份已验证材料，避免预检之后重新读取另一份文件。

在 `NETWORK_MTLS` 下，Spring 的 `BOTH` 角色先准备并验证两侧，随后才允许任一侧激活。
启用 Provider 却缺少显式授权器会启动失败；纯 Consumer 不要求创建业务授权器。
准备或启动失败会回收未使用的 Transport，并使该 Coordinator 的后续激活失败，
不会用已经关闭的 prepared Transport 隐式重试注册。

真实连接上的 TLS/mTLS 握手先于 OTRYX HELLO 和业务流量。
当前实现通过实际 JDK SSLEngine 使用的 KeyManager/TrustManager，把私有证明放入握手 session：
证明记录参与验证的材料代次、实际叶证书指纹，以及原始壁钟和单调时间锚点。
握手完成后，Transport 从**承载该请求或响应的同一条连接**检查证明和实际证书，再产生应用身份。
缺少证明、证书不符或代次失效均关闭连接，不使用当前全局代次替旧握手背书。

当前支持并检查实际 **SunJSSE / JDK TLS** 路径；未验证的 native TLS 或其他 provider 不会获得同等能力声明。
每个实际创建的 SSLContext 均将客户端和服务端 session cache 设置为 **1024 项、300 秒**并读回确认，
初始构造、复制及重载均遵守该限制。该缓存容量不代表握手前连接数或整个进程的内存上限。

实现依据：[TLS 材料及握手证明][tls-support]、[有限 JDK session cache][jdk-cache]。

## 4. 连接复用、到期与响应交付

Transport 客户端接口使用完整目标及携带连接身份的响应：

~~~java
CompletionStage<RpcTransportResponse> request(
        RpcTarget target, byte[] frame, Duration timeout);
// RpcTarget(ApplicationKey application, RpcEndpoint endpoint)
// RpcTransportResponse(byte[] frame, RpcPeerIdentity peer)
~~~

连接池和重连状态按完整 `RpcTarget` 区分。
同一 host/port 上的不同 namespace 或应用不能借用另一目标已经认证的连接。
返回的 `peer` 来自实际响应连接，不能用路由表 Metadata 或上一次请求的身份补齐。

| 边界 | 必须重新检查的事实 |
|---|---|
| 连接建立及发送 HELLO 前 | 真实 TLS 证明、当前材料代次、本机身份、对端绑定及目标应用 |
| 池内连接复用、发送请求 | 原连接身份仍有效，完整目标仍匹配 |
| 收帧及 Transport 完成响应 | 连接代次和证书/绑定期限仍有效 |
| Consumer Core 解码响应前 | 实际响应 peer 仍有效且匹配本次目标 |
| Consumer Core 解码完成、交付结果前 | 再次检查同一 peer；排队或解码期间到期不能交付成功 |
| Provider 业务结果写回前 | 原连接身份与本机材料仍有效，过期连接不能写回成功 |
| 空闲连接 | 独立到期巡检，默认间隔 250 ms，不依赖对端协商或发送 Heartbeat |

巡检定时器不提供硬实时调度保证；主动收发和复用边界仍各自检查到期。
Core 的完成队列测试可以使用受控假 Transport 验证排队期限；它不能替代 Adapter 对真实 TLS 连接来源的证明。

实现依据：[Vert.x Client][vertx-client]、[Vert.x Server][vertx-server]、[Core Client][core-client]。

## 5. 证书与信任材料重载

重载周期检查证书、私钥和信任集合的 SHA-256、mtime、size。
有限后台工作器读取并验证候选材料，EventLoop 不读取文件或同步等待重载。
单次候选材料对应一个新的私有代次，只有 SDK 成功安装后才提交为活动代次。

~~~mermaid
flowchart TD
    A["读取并验证候选材料"] --> B{"材料有效？"}
    B -->|否| C["保留活动材料并记录脱敏失败"]
    B -->|是| D["异步安装 SDK TLS 配置"]
    D -->|失败| C
    D -->|成功| E["原子提交新代次"]
    E --> F["关闭旧代次连接"]
    E --> G["新连接使用新材料"]
    F --> H["旧握手或旧证明不能进入业务流量"]
~~~

只修改信任集合也构成代次变化。
新代次生效时，旧代次连接及已分配但尚未完成的旧握手不能借用新代次继续工作；
本地终止原因是 `SECURITY_CONTEXT_CHANGED`，不能冒充证书已经过期或被 CA 拒绝。
因此重载可能终止在途调用，部署时应结合调用幂等性与轮换顺序规划。
无效候选材料或 SDK 更新失败保留之前的活动材料，但不延长它原有的有效期。

会话恢复保留原证明和验证时间锚点；只有实际重新验证的完整握手才能产生新的证明。
使用私有 session 数据后，SunJSSE 的恢复路径可能使用有状态缓存。
真实 TLS 1.2 session-ID 恢复回归在隔离子 JVM 中关闭客户端 ticket 扩展，TLS 1.3 回归使用默认路径；
这些测试设置不修改生产 JVM 全局属性，也不保证每次生产重连都发生会话恢复。

绑定表在 Transport 准备时冻结。文件重载用于更新 PEM 材料，不是动态改写绑定表的 API；
轮换的新叶证书须已在当前受控绑定表内，否则重新部署包含正确绑定的配置。

## 6. 有限方法授权与资源所有权

`NETWORK_MTLS` Provider 必须显式装配 `RpcAuthorizer`，返回
`CompletionStage<RpcAuthorizationDecision>`。结果只有 `deny()` 或有限的 `allowUntil(Instant)`，
没有布尔值的永久授权。异常、null Stage、null 决定及已失效的许可均拒绝。

授权上下文包括实际连接 `peer`、本机 `localApplication`、已绑定的 `serviceKey`/`methodId`、
原始接收时间 `receivedAt` 与最大期限 `maximumValidUntil`。
`remote` 只用于审计；上下文不包含业务 Payload，也不把请求自述身份当作可信证据。
策略应按对端应用及服务/方法权限决定结果。

最终许可期限以**原请求接收时刻**为起点，取以下约束的较早者：

- 原请求的剩余时间预算；
- 对端证书与应用绑定的有限期限；
- `server.authorization.max-grant-lifetime`；
- 授权器自身返回的 `allowUntil`。

Core 先设置逻辑超时并取得有界工作所有权，再在自有授权 worker 调用第三方策略方法体。
排队中的策略在调用前重新检查期限和身份；策略返回后、跨请求调度队列后、业务 DTO 解码前，
以及解码完成而尚未调用业务方法时，都重新检查同一份有限许可。
拒绝或许可在排队中到期时，不进入 DTO 解码；若许可在解码期间到期，则不调用业务方法。

| 配置前缀 `otryx.rpc.server.authorization.` | 默认值 | 约束对象 |
|---|---:|---|
| `max-pending` | 1024 | 尚未真实结束的授权工作，包括排队、方法体和原始 Stage |
| `max-buffered-request-bytes` | 67108864 | 逻辑授权等待阶段持有的请求 Frame 字节 |
| `timeout` | 3s | 等待策略的逻辑期限，仍受更短的原请求 deadline 限制 |
| `max-grant-lifetime` | 30s | 从原始接收时刻计算的最大许可有效期，正数且不超过一天 |

逻辑超时、取消或关闭不证明第三方代码已经停止。
已经运行的策略，只有**策略方法体退出且原始 Stage 实际终止**才归还并发槽位；
尚未执行的自有排队任务可以明确放弃并释放。逻辑授权终态回收该阶段 Frame 字节预算，
这与真实策略工作槽位是两个不同的所有权边界。
关闭会发出中断并清理未运行任务，但不能强制终止忽略中断的第三方代码。
该代码会继续占用有限工作容量，容量耗尽时拒绝新工作。

策略方法体被隔离于 EventLoop，但外部数据库、HTTP 客户端及策略自身创建的任务仍需要有限超时与容量。
方法授权许可控制业务进入；业务结果完成前还会检查请求 deadline 和连接身份，
已经执行的业务副作用不会因身份随后失效而自动回滚。

实现依据：[授权 SPI][authorizer]、[有限授权决定][authorization-decision]、
[授权工作所有权][authorization-gate]、[Provider 执行边界][core-server]。

## 7. 安全失败、重试和可观测性

本地认证失败使用固定、无原始 cause 的 `RpcAuthenticationException`：

| Reason | 含义 |
|---|---|
| `CERTIFICATE_REJECTED` | 实际本地 TLS 证书验证失败或缺失可信握手证明 |
| `IDENTITY_UNBOUND` | 已验证叶证书没有受控应用绑定 |
| `APPLICATION_MISMATCH` | 实际绑定与预期应用不一致 |
| `NAMESPACE_MISMATCH` | 实际绑定跨越允许的 namespace |
| `IDENTITY_EXPIRED` | 证书、绑定或原始单调有效期已耗尽 |
| `SECURITY_CONTEXT_CHANGED` | 材料代次变更导致本地旧连接终止 |

Core 对这些异常不重试，并以中性结果结算端点统计，不把它们计作端点可用性故障。
Adapter 只归类本地有证据的 TLS 错误；对端普通断开仍是连接不可用，不能推测成远端拒绝证书的原因。
没有 TLS 失败后降级明文的路径。

方法授权拒绝使用 Wire v1 已有的 `BAD_REQUEST`，客户端不重试，也不因此惩罚端点健康。
容量不足使用 `OVERLOADED`；原请求 deadline 先到则使用 `DEADLINE_EXCEEDED`。
这是当前 Wire v1 的状态表达，不增加新的授权状态码。

`RpcObserver` 提供 `onTlsHandshakeCompleted`、`onCertificateReloadCompleted` 和
`onCertificateExpiryWarning`。失败日志与异常只输出固定原因或受控类型，
不附带私钥、凭据、完整 PEM、请求 Metadata 或第三方原始异常文本。

## 8. Registry 的安全是独立访问链

RPC mTLS 不自动保护 Registry。`NETWORK_MTLS` 还逐一检查
`MEMBERSHIP`、`MANIFEST`、`MAPPING` 的**所有实际来源**。
每个远程来源必须真实安装 TLS、hostname 验证、受支持的有类型凭据，并报告匹配的 namespace；
缺失通道、未知来源或同一通道内混入不安全来源均拒绝。
进程内特例仅授予 Core 审核过的三个 final Memory 实现，任意扩展自报 `LOCAL_PROCESS` 不获接受。

| Adapter | 当前网络模式要求 | 边界 |
|---|---|---|
| Etcd | 显式 CA 与 `USER_PASSWORD`，可附加客户端证书/私钥 | 实际 gRPC 客户端安装 TLS 与凭据；不接受仅 `CLIENT_CERTIFICATE` 认证 |
| Consul | 显式 CA 与 `ACL_TOKEN`，可附加客户端证书/私钥 | 成员、Manifest、Mapping 使用同一受保护客户端；仍要求单个本地 Agent |
| Eureka | Eureka 自己的 CA 与 `USER_PASSWORD`；外部 Metadata Backend 另配安全材料 | Eureka 成员链与 Metadata 链独立验证，后者沿用该 Backend 的要求 |
| Nacos | 当前不支持 `NETWORK_MTLS` | NETWORK 或任何 TLS 意图在创建 Naming/Config SDK 前拒绝；显式开发模式仅支持 `NONE` / `USER_PASSWORD` |

Eureka 的 `metadataSecurity` 不继承 Eureka 的 CA 或凭据。
把 Eureka 改为 HTTPS，却让外部 Metadata Backend 保持明文，不满足网络模式；
选择 Nacos 作为 Metadata Backend 也不能绕过当前 Nacos NETWORK 限制。

安全材料通过 `RegistrySecurityOptions`、`RegistryTlsOptions` 和有类型 `RegistryCredentials` 传递，
不能放进旧的字符串 Map、URL userinfo、未知开关或公共 Metadata。
Factory 在真实安装客户端安全配置后才报告 descriptor；它说明客户端保护事实，
不证明后端当前健康，也不证明部署方已经授予恰当的后端 ACL。
后端仍须独立限制对应 namespace/key 范围的读写权限。

Registry 的安全原因必须由实际类型和状态支持。Etcd SDK 合成的外层 `UNAUTHENTICATED`
不能单独证明密码错误；其内部超时或连接故障保持普通不可用，不能误触发安全 epoch 失效。
有界解析拒绝依赖异常描述猜测原因，返回结果也不携带后端原始 cause。

实现依据：[Registry 来源聚合][registry-descriptor]、[Registry 配置边界][registry-support]。

## 9. 验证范围与部署边界

本仓库的定向回归分别覆盖实际 TLS 信任链、hostname、mTLS 应用绑定、有限身份、空闲到期、
旧 engine 跨 trust-only reload、TLS 1.2/1.3 会话证明、缺失证明和有限 session cache。
Core 另以受控 Transport、时钟和执行队列验证解码前/交付前拒绝、授权工作所有权与启动预检。
两类测试证明的层次不同，应结合原始测试报告判断。

准确提交的构建、跨 JVM RPC、真实 Registry 安全链与完整 CI 验收状态由
[工程检查点](engineering/agent-governance-checkpoint.md) 和对应 PR 记录。
定向 TLS 测试通过不等于这些端到端门禁已经完成；本文不声明整体验收 GREEN。

当前资源约束包括文件读取上限、session cache、授权容量与帧预算。
Fory 的对象图门限仍是估算，编码后检查也不能证明编码前没有分配大对象。
握手前 FD/内存准入、覆盖解码和编码分配的硬预算属于 **#141** 的后续范围，
不能从本次身份与授权检查推导出这些资源安全保证。

[core-checks]: ../otryx-core/src/main/java/com/peachsoft/otryx/core/RpcSecurityChecks.java
[transport-descriptor]: ../otryx-core/src/main/java/com/peachsoft/otryx/transport/RpcTransportSecurityDescriptor.java
[certificate-binding]: ../otryx-core/src/main/java/com/peachsoft/otryx/api/RpcCertificateBinding.java
[peer-identity]: ../otryx-core/src/main/java/com/peachsoft/otryx/api/RpcPeerIdentity.java
[tls-support]: ../otryx-transport/otryx-transport-vertx/src/main/java/com/peachsoft/otryx/transport/vertx/VertxTlsSupport.java
[jdk-cache]: ../otryx-transport/otryx-transport-vertx/src/main/java/com/peachsoft/otryx/transport/vertx/BoundedJdkSslEngineOptions.java
[vertx-client]: ../otryx-transport/otryx-transport-vertx/src/main/java/com/peachsoft/otryx/transport/vertx/VertxRpcTransportClient.java
[vertx-server]: ../otryx-transport/otryx-transport-vertx/src/main/java/com/peachsoft/otryx/transport/vertx/VertxRpcTransportServer.java
[core-client]: ../otryx-core/src/main/java/com/peachsoft/otryx/core/OtryxRpcClient.java
[core-server]: ../otryx-core/src/main/java/com/peachsoft/otryx/core/OtryxRpcServer.java
[authorizer]: ../otryx-core/src/main/java/com/peachsoft/otryx/core/RpcAuthorizer.java
[authorization-decision]: ../otryx-core/src/main/java/com/peachsoft/otryx/api/RpcAuthorizationDecision.java
[authorization-gate]: ../otryx-core/src/main/java/com/peachsoft/otryx/core/RpcAuthorizationGate.java
[registry-descriptor]: ../otryx-core/src/main/java/com/peachsoft/otryx/registry/RegistrySecurityDescriptor.java
[registry-support]: ../otryx-core/src/main/java/com/peachsoft/otryx/registry/RegistrySecuritySupport.java
