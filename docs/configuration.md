# OTRYX RPC 生产配置与安全加固

> 本文描述当前 `1.0.0-SNAPSHOT` 源码中的配置与执行边界。容量数值需要目标环境的验证数据；功能描述不代表某次端到端门禁已经通过，验收状态见[工程断点](engineering/agent-governance-checkpoint.md)。完整 Spring 字段见 [Starter 参考](reference/starter.md)，证书与信任边界见[安全指南](security.md)。

## 1. 显式部署意图与启动顺序

所有入口必须显式选择 `RpcSecurityProfile`：Spring 使用 `otryx.rpc.security-profile`，Core Client/Server Builder 使用 `securityProfile(...)`，Registry Factory 接收带明确 Profile 的 `RegistryOptions`。默认 `UNSPECIFIED` 被拒绝；`fromCsv(...)` 或旧形状的便捷构造不会隐式选择开发模式。

| Profile | 适用范围 | 启动要求 |
|---|---|---|
| `TRUSTED_DEVELOPMENT` | 可信隔离开发与测试 | 明确选择；可使用明文、匿名 Peer 和兼容 Codec，仍执行容量与生命周期约束 |
| `NETWORK_MTLS` | 需要保护网络身份与控制面的部署 | RPC 双向 TLS、完整且有限的应用绑定、安全 Codec、全部 Registry 来源保护与显式 Provider 授权器 |
| `UNSPECIFIED` | 配置缺省值 | 在启动前失败，不降级为开发模式 |

网络 Profile 且 `server.enabled=true` 时，Starter 在创建 Registry 工厂和 RPC 运行时前要求应用提供 `RpcAuthorizer` Bean；纯 Consumer 应显式关闭 `server.enabled`。Core Server Builder 有同样的缺策略拒绝。应用负责服务与方法权限，通用 Starter 不自动注入放行策略。

配置校验之后还有实际对象预检：Core 检查最终 Codec、Method Codec、Transport Client/Server、Membership Coordinator 与 Resolver 的能力描述。Starter 同时验证已启用的两端 Builder，通过后才启动角色；BOTH 不会先注册一端再发现另一端不安全。Provider 在 `start()` 时再次复核，覆盖创建与启动之间的身份过期窗口。

默认安全声明为 `UNKNOWN` 的自定义 SPI 不能在网络 Profile 下运行。默认 Fory 需要 `STRICT_ALLOWLIST` 和实际 DTO allowlist；替换后的 Codec 按同样的契约类型、解码前校验和有限容量能力验收，不按实现类名称放行。真实 Transport 与 Codec Registry 的 Codec ID 集合必须一致。

配置决策的依据如下：

| 类型 | 依据 |
|---|---|
| 身份与安全边界 | 显式 Profile、实际安装的保护与类型化失败 |
| 容量参数 | 目标环境的负载、内存和延迟数据 |
| 业务策略 | 应用自己的方法权限、有限许可、幂等与可用性目标 |

实现入口：[Core 安全预检](../otryx-core/src/main/java/com/peachsoft/otryx/core/RpcSecurityChecks.java)、[Starter 运行时](../otryx-spring-boot/otryx-spring-boot-autoconfigure/src/main/java/com/peachsoft/otryx/spring/runtime/OtryxRpcRuntimeCoordinator.java)。

## 2. Transport 容量与目标隔离

连接池以 `RpcTarget(ApplicationKey, RpcEndpoint)` 为键，应用不同而地址相同的目标保持隔离。`connections-per-endpoint` 仍是配置键名称，实际限制每个目标的连接分片数。连接数、每连接 inflight、Frame 大小、写队列、连接与握手超时、心跳及退避应一起设置；单独扩大 Frame 或 inflight 不能推导吞吐提高。

心跳超时应覆盖目标环境实际 GC 和网络抖动，重连退避应避免故障恢复时集中建连。具体默认值和字段见 [Starter](reference/starter.md)。

### 2.1 Transport 接入预算

以下是当前默认值，其覆盖范围与释放时刻各不相同：

| 配置（相对 `otryx.rpc.transport`） | 默认值 | 实际约束 |
|---|---|---|
| `admission.max-connections` | 4096 | TLS 模式在原始 TCP NetSocket 进入时预留，先于 TLS Engine/握手；不控制 OS backlog/FD 内存 |
| `admission.max-handshakes` | 1024 | TLS 预握手和 OTRYX HELLO 共用握手额度（从 TCP 接入到 HELLO_ACK）；失败/超时幂等释放 |
| `admission.max-buffered-partial-frame-bytes` | 67108864 | Transport 实例内所有残帧逻辑字节；追加前预留，消费或关闭后归还 |
| `admission.partial-frame-timeout` | 10s | 长期没有完整帧交付的连接；少量追加字节不会无限延长驻留 |
| `max-inflight-per-connection` | 1024 | 单个 Provider 连接内尚未结束的请求；超限返回 `OVERLOADED`，不进入 Handler |
| `admission.max-buffered-response-bytes` | 67108864 | Transport Server 已编码且待写的 RESPONSE 字节；写 Future 终态或断连后释放 |

CANCEL 可以结束连接层的逻辑请求，不能证明业务或授权策略已经停止。真实 TLS 握手不自动在 Vert.x 回调前执行：先进行有限的 TCP 准入，再主动升级，拒绝明文应用数据。真正执行的资源继续受 Core 所有权约束。待写响应额度耗尽时拒绝新写入，Drain 等待尚未完成的写入；这一预算不控制 Codec 内部对象图和数组复制峰值、TLS、Netty Direct Memory 或操作系统缓冲；新增的 Provider 输出编码预算另行计算框架已保留的流式输出缓冲。

Provider 另外提供 `otryx.rpc.server.encoding.max-buffered-bytes=67108864` 和 `max-response-bytes=16777216`。编码缓冲在分配前预留全局额度，Fory 响应采用有界 OutputStream；单响应额度与 Codec 声明上限取更小值。第三方 Codec 的默认方法可能先创建完整 Payload，因此不可把当前额度视作任意自定义编码器的分配硬上限。Codec 在网络 Profile 下还必须声明正数的载荷大小、对象图深度和估算图内存上限，并在 DTO 实例化前验证载荷。这些声明不是编码分配或 JVM Heap 的硬内存租约。详见 [Transport 接入容量](reference/transport-admission.md) 与 [Codec 能力模型](../otryx-core/src/main/java/com/peachsoft/otryx/codec/RpcCodecSecurityDescriptor.java)。

## 3. Provider 授权、执行与关闭

### 3.1 有限授权结果

应用策略实现 `RpcAuthorizer.authorize(RpcAuthorizationContext)`，返回 `CompletionStage<RpcAuthorizationDecision>`。允许结果使用 `allowUntil(Instant)` 指定有限期限，拒绝使用 `deny()`。布尔结果、无限期许可或仅依据网络地址的判断不能替代完整应用与方法授权。

Context 提供真实 TLS Peer、实际本端 `ApplicationKey`、预绑定 `ServiceKey`/`methodId`、接收时刻和 `maximumValidUntil`。它不包含业务 Payload，不把请求 Metadata 当作可信身份。最大许可期限取原请求剩余 Deadline、服务端许可上限与 Peer 身份期限中的最早值；策略只能缩短它。

| 配置（相对 `otryx.rpc.server.authorization`） | 默认值 | 约束 |
|---|---|---|
| `max-pending` | 1024 | 包含排队与执行中的真实授权工作；不能因逻辑超时提前归还运行中的槽位 |
| `max-buffered-request-bytes` | 67108864 | 逻辑授权等待期间的原始 Frame 总字节；清理完成后才对外发布授权终态 |
| `timeout` | 3s | 与客户端剩余请求预算取较短值，先安装超时再调用策略 |
| `max-grant-lifetime` | 30s | 大于零且不超过 1 天；长于上下文上限的策略许可仍被收紧 |

策略体运行在独立有界工作池；即使它在返回 Stage 前阻塞，请求 EventLoop 仍可推进。逻辑超时、取消或关闭只终结等待；正在执行的策略体和原始 Stage 都实际结束后才释放工作槽位。尚未运行的 owned 队列任务可以明确放弃，不能随后继续调用策略。容量不足立即拒绝，不阻塞请求线程等待额度。

许可在策略调用、恢复原请求执行器、业务执行排队、解码前和解码后调用业务前复核。异步授权后的 DIRECT 方法返回原请求执行器；CPU/虚拟线程方法保持各自模型。壁钟回退不能延长许可，单调时钟倒退同样导致失效。已经进入的业务不因授权到期而被假定强停，正常响应交付仍须通过请求与 Peer 有效性检查。

拒绝、空结果、异常或过期许可沿用 `BAD_REQUEST` 授权拒绝，不增加 Wire 消息类型。API 与实现见 [Context](../otryx-core/src/main/java/com/peachsoft/otryx/api/RpcAuthorizationContext.java)、[Decision](../otryx-core/src/main/java/com/peachsoft/otryx/api/RpcAuthorizationDecision.java) 和 [授权工作所有权](../otryx-core/src/main/java/com/peachsoft/otryx/core/RpcAuthorizationGate.java)。

### 3.2 业务执行与排空

Provider 默认使用 `BLOCKING_VIRTUAL`；CPU 方法使用有界线程池，DIRECT 默认禁止，仅用于经过验证的极短非阻塞逻辑。业务容量应同时考虑 `max-concurrent`、分层 Admission 的 `max-inflight-bytes`、每服务/方法配额、CPU parallelism 和 CPU queue capacity，见 [Provider Admission](reference/provider-admission.md)。

排空阶段默认上限为 30 秒，Spring `otryx.rpc.server.shutdown-timeout` 默认总预算为 25 秒，较早到期的总预算优先。`shutdown(RpcShutdownOptions)` 返回 `CompletionStage<RpcShutdownReport>`，报告注销、Transport 关闭、线程池终止和剩余 Admission Lease。未响应中断的业务可以超出关闭预算；Future 取消本身不能释放仍在使用的业务配额。

Spring `SmartLifecycle.stop(Runnable)` 在关闭 Stage 有结果后调用容器回调。默认 25 秒为通常 30 秒的 Spring 阶段等待留出调度余量，部署修改阶段超时应与实际排空要求一致；超时意味着报告不完整清理，不意味着任意用户代码已经停止。

## 4. Consumer 目录、重试与统计

调用 Deadline 在参数编码开始前建立，覆盖编码、连接、握手、排队、重试和完成。只有显式声明 `@OtryxRpcIdempotent` 的方法才允许按 Retry Budget 重试，默认 `max-attempts=2` 包含首次调用；业务幂等性仍由应用负责。Retry、Circuit 与 Outlier 都不能替代容量控制。

### 4.1 普通陈旧与安全失效

`otryx.rpc.client.directory-max-staleness` 默认 30 秒，必须为正且有限。普通控制面暂时失败时可在该预算内使用最近一次验证成功的目录；TLS、认证、授权和元数据完整性拒绝立即产生类型化 `RegistrySecurityException`，使 Resolver 原验证代次失效。

Directory 将同一次验证候选与端点投影原子保存。发布、读取状态、读取快照和每次 attempt 发送前都进行本地复核；发送前必须核对原来选中端点所属的候选。一个路由检测到安全拒绝后，其他路由的旧缓存不能继续发送；后续恢复产生的新验证结果也不能使旧代次缓存重新有效。该 guard 不做网络 I/O，不重新调度控制面。

### 4.2 认证失败立即终结

`RpcAuthenticationException` 和 `RegistrySecurityException` 不进入重试，也不转成普通 `UNAVAILABLE` 等待下一次 attempt。安全拒绝在当前 attempt 内终结，包括重试调度器调用 attempt 的情况；它只归还已经持有的 inflight 或熔断探测额度，不更新延迟 EWMA、不增加 Endpoint/熔断失败计数，也不清空既有健康历史。

远端方法授权拒绝继续使用已有 `BAD_REQUEST` 非重试分类。`SECURITY_CONTEXT_CHANGED` 表示真实 TLS 材料代次被替换，使用相同的非重试、中性统计规则，不能伪装成普通断连或证书自然到期。

Consumer 响应带有同一连接验证得到的 Peer；Core 在有界 completion executor 上解码前，以及解码后交付成功结果前再次检查有效期、目标应用和 namespace。仅在接收 Frame 时检查一次不足以覆盖完成队列等待期间的身份过期。

实现入口：[ServiceDirectory](../otryx-core/src/main/java/com/peachsoft/otryx/core/ServiceDirectory.java)、[Resolver 安全代次](../otryx-core/src/main/java/com/peachsoft/otryx/registry/ApplicationServiceResolver.java)、[目标响应](../otryx-core/src/main/java/com/peachsoft/otryx/transport/RpcTransportResponse.java)。

## 5. RPC 双向 TLS 与证书绑定

`NETWORK_MTLS` 要求 RPC 双向 TLS，并保持 Hostname Verification。绑定表把真实叶证书 SHA-256 映射到完整 `ApplicationKey(namespace, applicationName)` 和有限 `validUntil`，本端身份来自实际 Membership。CN/SAN 继续属于 TLS 名称校验范围，不能自动推导应用身份；Registry attributes 和远端声明同样不能补充可信绑定。

有效身份同时受证书有效期与绑定期限约束。新连接、响应接收、解码与交付边界都检查这份连接证明；旧 TLS 会话不能被标记成重载后的新材料代次。TLS 失败不会回退明文，过期或绑定不匹配使用固定、无原始敏感 cause 的类型化认证异常。

私钥权限、CA 信任集合、叶证书和绑定表轮换需要各自管理。RPC CA 与 Registry CA 是独立输入，不能通过只保护 RPC 链路来推断控制面已经可信。证书生成、轮换和失败场景见 [安全指南](security.md) 与 [Quickstart](../otryx-quickstart/README.md)。这些教程的阶段断言须以实际执行记录验收，不能仅凭启动参数宣称安全场景通过。

## 6. Registry 的实际来源保护

Spring 用 `otryx.rpc.registry.security` 配置成员访问链的 TLS 与有类型凭据；Eureka 使用结构相同的独立 `otryx.rpc.registry.metadata-security` 配置外置元数据链。凭据字段通过 `credentials.kind` 区分，用户名密码、Token 与客户端材料不能混用为互相矛盾的字段。秘密使用受控配置源或 Spring `configtree:` 注入，不放入普通选项 Map。

当前 Factory 支持范围：

| 后端 | 网络 Profile 的有效配置 | 运行与装配约束 |
|---|---|---|
| Etcd | HTTPS/gRPC TLS、明确 CA、`USER_PASSWORD`，可另装客户端证书 | 评估 endpoint、namespace、Lease TTL 与恢复告警；三个通道使用实际安装保护的客户端 |
| Consul | HTTPS、明确 CA、`ACL_TOKEN`，可另装客户端证书 | 只使用一个本地 Agent；拒绝非空 `datacenter`，Enterprise namespace 按当前 Adapter 支持配置 |
| Eureka | 成员 HTTPS、明确 CA、`USER_PASSWORD`；元数据链独立配置 | 必须有外置 Etcd/Nacos/Consul 后端；网络 Profile 的外置链也必须是当前可保护的实现，因此不能选 Nacos |
| Nacos | 当前不支持 `NETWORK_MTLS` | `TRUSTED_DEVELOPMENT` 可用 `NONE` 或 `USER_PASSWORD`；TLS/网络意图在创建 SDK 前拒绝 |
| Memory | 框架真实进程内来源 | 使用本地进程保护声明；Core 只接受经实际来源边界识别的 Memory 实现 |

凭据模型虽然包含 `CLIENT_CERTIFICATE`，当前 Etcd/Consul/Eureka 工厂未接受它作为单独的凭据模式。配置可选客户端证书时，仍需对应工厂支持的用户名密码或 ACL Token。具体字段见 [Starter 的独立 Registry 保护](reference/starter.md#62-有类型的-registry-tls-与凭据)。

Factory 先有限读取并验证明确的 CA、可选客户端证书与私钥，再把同一份解析材料和凭据安装到实际客户端，最后提供 `RegistrySecurityDescriptor`。HTTP 客户端保持 hostname 校验并禁止重定向；端点的 HTTP/HTTPS scheme 必须与明确的 TLS 配置一致，不接受 URL 内嵌凭据或隐式信任。

Core 针对 `MEMBERSHIP`、`MANIFEST`、`MAPPING` 的每个实际来源检查认证 TLS、凭据与 scope。一个通道有多个来源时全部保留；Eureka Mapping 依赖外置元数据和成员健康两条链，不能以任意一条已保护链覆盖另一条弱来源。自定义 SPI 自报 `LOCAL_PROCESS` 会被来源边界降为未知，不能冒充框架 Memory。

Consul Provider 通过 TTL Check 续约，应用目录只投影健康可路由 Provider；Eureka 通过应用成员 REST API 续约和有界轮询，只有 `UP` 的有效成员可路由，纯 Consumer 保留无 RPC Endpoint 的真实成员。Manifest/Mapping 后端不额外注册相同应用实例。详见 [Consul V2](reference/registry-consul-v2.md)、[Eureka V2](reference/registry-eureka-v2.md) 和 [Nacos V2](reference/registry-nacos-v2.md)。

Registry 网络请求始终在控制面执行，单次 RPC 发送前只做本地验证。只读成员观察使用独立凭据且不注册或发布，见 [Quickstart 观察 CLI](../otryx-quickstart/README.md)。

## 7. JVM 证据

记录目标 JVM 的 Heap、GC、CPU/容器限制、Native Memory、Direct Buffer 和 JFR 策略，并和流量、Frame 大小、并发量及队列饱和情况关联。Benchmark JVM 参数不能未经目标环境验证直接复制到生产。

## 8. Observability

调用观测应覆盖逻辑 QPS/延迟/错误、重试耗尽、Circuit 状态、Outlier、Provider Admission、连接与心跳、Registry 安全拒绝与普通恢复、TLS 握手和材料重载。安全拒绝虽然不计入 Endpoint 基础设施失败，逻辑调用仍应作为失败可见。

敏感诊断只记录固定原因、有限计数和白名单字段；不把 Metadata、Payload、凭据、证书内容或任意底层异常放进日志、Trace 或指标标签。可能阻塞的遥测出口使用有界异步适配，不能阻塞 RPC EventLoop。

见 [生产可观测与 SLO](reference/production-observability.md)；10k 逻辑并发、实际峰值在途量和错误率的差异见 [Soak 质量验收](reference/soak-acceptance.md)。

## 9. 容量数字

QPS、线程数、连接数、Heap 和 p99/p99.9 的建议值都需要目标环境数据。配置默认值说明当前约束，不代表部署容量承诺。验证方法见[容量规划](reference/capacity-planning.md)。


## 10. Consumer 本地路由策略（V2-15）

`otryx.rpc.client.router` 默认为 `rules`；可通过自定义 `RpcRouter` Bean 替换。 `otryx.rpc.registry.member-attributes` 为 Provider/Consumer/BOTH 成员附加非可信标签，最多 64 项且有单键和值长度上限；不得在其中放入 Token、密码、证书或身份声明。

`RuleBasedRpcRouter` 使用带递增 revision 的本地 `RpcRoutingSnapshot`。未配置规则时不产生候选数组拷贝；启用规则后先按 ApplicationKey、标签、zone、release 与 track 过滤，再依据优先应用选择候选，最后才执行 P2C EWMA；灰度 0/100 分别强制稳定/灰度轨道。路由空集会拒绝，不会跨应用降级；安全身份验证仍由实际 mTLS 与 Provider 授权决定。参见 [Router SPI](reference/routing.md)。


## 11. 有界结果编码的生产 Codec 契约（V2-14）

`NETWORK_MTLS` Provider 的每一个方法级 Codec 在注册前必须是 `RpcBoundedResultMethodCodec`，不接受使用兼容 `encodeResult()` 完整数组的默认方法级适配。Fory 直接向有界 OutputStream 写入；最终 Wire Frame 根据有效底层数组区间构造，创建前同时对输出缓冲与完整 Frame 的瞬时内存预留额度，避免中间 Payload 拷贝。该额度不包含第三方 Codec 内部堆分配、返回后异步持有的 Frame、Netty/SSL/操作系统缓冲，需结合 [Transport 配额](reference/transport-admission.md) 和 #146 固定物理性能证据验收。
