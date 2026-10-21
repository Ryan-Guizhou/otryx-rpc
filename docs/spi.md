# SPI 扩展指南

OTRYX RPC 的 SPI 隔离可独立替换的实现技术和策略。当前 `1.0.0-SNAPSHOT` 只有应用级注册控制面；扩展必须与当前 Java 契约和 Wire v1 内部自洽。

## 1. 基本规则

1. 扩展接口不暴露第三方实现类型；Core 不依赖 Adapter。
2. 扩展在启动阶段解析并缓存，单次 RPC 不动态加载扩展或访问注册中心。
3. 重复扩展名直接失败，不依赖 Classpath 顺序。
4. 有状态网络资源由 Factory 创建，调用方按明确所有权和有界预算关闭。
5. SPI 只负责实现替换；可以预绑定到 Service、Method 或 Connection 的信息应尽早绑定。

## 2. 当前 SPI

| 扩展 | 职责 |
| --- | --- |
| `RpcCodec` | 方法级编解码绑定；Codec ID 属于 Wire 契约 |
| `ApplicationRegistryFactory` | 创建应用成员与 Manifest/Mapping 控制面 |
| `RpcTransportFactory` | 创建客户端和服务端 Transport |
| `LoadBalancer` | 从本地已验证候选中选择真实 Provider |
| `ProxyFactory` | Generated Stub 不存在时的代理 fallback |

## 3. 应用注册与成员生命周期

`ApplicationRegistry` 提供 `ApplicationDiscovery`、能力声明、可选 `ApplicationRegistrar` 和资源关闭。当前 Core Client/Server 与唯一 Starter 要求真实注册能力及元数据仓库；仅发现能力的实现不能用于当前运行时，不能通过空注册方法伪装支持。

```java
ApplicationRegistryFactory factory = ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
        .getExtension("etcd");
ApplicationRegistry registry = factory.create(RegistryOptions.trustedDevelopment(
        List.of("http://127.0.0.1:2379"), "demo", Map.of()));
ApplicationRegistrar registrar = registry.registrar().orElseThrow();
ApplicationMetadataBackend metadata = (ApplicationMetadataBackend) registry;
```

此片段只用于隔离开发环境的扩展查找，明确选择 `TRUSTED_DEVELOPMENT`。完整运行时必须使用 `ApplicationMembershipCoordinator` 建立固定身份，再通过 Client `applicationControlPlane`、Server `applicationMembership` 装配并负责关闭；两个 Builder 也必须显式指定同一部署安全 Profile。普通 Spring 应用使用 Starter 自动装配，参见 [Starter](reference/starter.md)。

Provider、Consumer、BOTH 都按 `ApplicationKey(namespace, applicationName) + instanceId` 入册。纯 Consumer 的 RPC Endpoint 和 Manifest Revision 为空、权重为零；它存在于成员目录，但永远不进入 RPC Provider 池。一个进程导出多个接口仍只产生一条应用成员记录。

`ServiceKey` 仅表示 interface/version/group 业务契约。Provider 通过 `ApplicationManifestRepository` 发布不可变清单，并用 `ServiceMappingRepository` 的实例 Owner 声明完整导出集合。Consumer 经 Mapping、应用目录、Manifest/Schema 校验形成 `VerifiedProviderSnapshot`，再投影为本地 LB 的 `ServiceInstance` DTO。该 DTO 没有注册或发现职责。

## 4. 能力与后端语义

Adapter 只能声明实际能力；原生 Watch、轮询订阅和本地 Revision 不可混为一谈。

| 后端 | 成员目录 | Manifest / Mapping | 订阅及 Revision |
| --- | --- | --- | --- |
| Memory | 单 Registry 对象内成员 | 内存不可变 Manifest、Owner fencing | 内存回调、本地 Revision |
| Etcd | 应用成员 Lease | KV 内容寻址与实例 CAS | Range/Watch、服务端 Revision |
| Nacos | Naming 应用成员 | Config 不可变内容、预初始化分片 CAS | Naming 事件与健康校对、本地 Revision |
| Consul | Agent Service、TTL Check、Session Claim | KV Manifest 与实例 CAS | 有界 Health 轮询、本地 Revision |
| Eureka | 原生 Application/InstanceInfo、Lease | 显式外置 Etcd/Nacos/Consul | 有界轮询、本地 Revision |

可用能力枚举由 `RegistryCapability` 定义。Eureka 的外置元数据后端不重复注册业务成员；其未验证 Mapping 候选必须与 Eureka 实际 Owner 和健康成员交叉校验。`otryx-registry-http` 提供仍被应用级 Eureka 使用的 HTTP 工具，不是可选择的注册模式。

## 5. RegistryOptions 与配置

`RegistryOptions` 包含 endpoints、namespace、非安全 providerOptions、Observer、显式 securityProfile、当前链的 security，以及 Eureka 外置元数据链的可选 metadataSecurity。三/四参数构造与 `fromCsv(...)` 保留 `UNSPECIFIED`，不能用于 Factory 启动；`trustedDevelopment(...)` 只表达明确的隔离开发意图。

TLS 信任、可选客户端证书和后端凭据通过 `RegistrySecurityOptions`、`RegistryTlsOptions`、`RegistryCredentials` 建模。凭据种类为 `NONE`、`USER_PASSWORD`、`ACL_TOKEN` 或 `CLIENT_CERTIFICATE`；具体 Adapter 必须拒绝自己不支持的组合。非安全 SDK 设置才进入 `providerOptions`，未知键或凭据键不能静默透传。Eureka membership 与外置 Metadata 分别提供安全配置和实际来源，不能把一条安全链的凭据或声明自动继承给另一条。Core 不引入厂商 SDK。

后端默认值、超时、ACL、容量和失败边界分别见 [Etcd](reference/registry-etcd.md)、[Nacos](reference/registry-nacos-v2.md)、[Consul](reference/registry-consul-v2.md)、[Eureka](reference/registry-eureka-v2.md)。Nacos 每个新 namespace/group 必须在业务启动前显式初始化 Mapping 分片；Eureka 缺少可靠外置元数据配置直接失败。

应用目录的新鲜度预算必须为严格正的有限值，默认 30 秒；远端读取、订阅、重试、取消与关闭均在控制面处理。RPC 热路径只读取不可变本地快照。安全失败使旧候选的安全代次立即失效，不能借普通失联的 LKG 规则继续路由；恢复后必须重新完整验证。快照读取成功与内容改变是不同事件，详见 [新鲜度与恢复](reference/registry-freshness.md) 和 [安全指南](security.md)。

## 6. Codec 与代理

`RpcCodec.bind(RpcMethodDescriptor)` 在方法粒度预绑定 encoder/decoder，单次请求不重新解析反射 Method 或 Schema。Codec ID 不按 SPI 加载顺序动态分配。

`NETWORK_MTLS` 检查实际 `RpcCodecRegistry` 的全部 Codec，以及每个最终 `RpcMethodCodec` 的 `RpcCodecSecurityDescriptor`：类型策略必须受限，契约声明与 Payload 解码边界必须明确，资源限额必须有限。默认 `UNKNOWN` 不能通过；`TRUSTED_COMPATIBILITY` 不能作为网络部署能力。安全的自定义 Codec 可以替换 Fory，未使用的默认 Fory 模式不会代替实际能力校验。Fory 当前的图内存估算不等同于编码前峰值分配硬上限，后者仍由 #141 单独验收。

标注 `@OtryxRpcContract` 并启用 `otryx-codegen` 后，编译期生成 Consumer Stub。运行时优先发现生成类，缺失才回退 `ProxyFactory`。JDK Proxy 为默认 fallback，CGLIB 与 Byte Buddy 按需添加。

## 7. Transport、身份和有限授权契约

`RpcTransportClient.request(RpcTarget, byte[], Duration)` 接受预期 `ApplicationKey` 与 Endpoint，并返回 `CompletionStage<RpcTransportResponse>`。响应必须包含该请求实际使用连接的 `RpcPeerIdentity`，不能借用其他连接或配置文件里的静态身份。双向 TLS 身份绑定完整叶证书 DER SHA-256、ApplicationKey 和有限期限，并受证书有效期、原始墙钟/单调时间锚及当前安全代次限制。

Client/Server 的 `securityDescriptor()` 只能说明本对象已安装的真实能力，读取时不能启动线程、连接或监听器。未实现的默认描述是 `UNKNOWN`；NETWORK Builder 对它失败关闭。Client 在发送、响应解码和交付前复核目标身份；`RpcAuthenticationException` 的固定原因不能被转换成可重试的普通连接失败，也不能更新端点失败、EWMA 或熔断状态。

Provider 使用显式 `RpcAuthorizer`，返回 `CompletionStage<RpcAuthorizationDecision>`。允许结果必须包含有限 `validUntil`，不能超出接收时间、请求 Deadline、Peer 身份期限与配置的最大 grant lifetime；解码和方法执行前均复核。同步策略体在有界工作线程执行，逻辑超时或取消会释放等待帧预算，但工作槽位须等策略体和其原始 Stage 都真正结束才释放。缺少授权器的 NETWORK Provider/BOTH 在启动与注册副作用之前拒绝，应用 Metadata 不能代替认证身份或授权器。

这些契约由 Core 最终校验，SPI 替换不能降低它们。具体原因与迁移示例见 [安全指南](security.md) 和 [Starter](reference/starter.md)。

## 8. 验证扩展

新增后端须执行相同的应用成员、角色、Manifest、Mapping Owner、订阅健康、失败和关闭契约，不能仅有工厂加载测试。NETWORK 扩展还须验证三类实际 Registry 来源的保护、错误信任/凭据、内容篡改与安全失效；Transport/Codec 替换须有未知能力拒绝及真实安全正例。受控计数应分别报告原生成员、SPI 方法入口调用数、订阅创建调用数及快照/健康通知和收敛时间，不能将它们表述为后端全部 HTTP、keepalive 或 server Watch 流量。

运行入口和真实证据边界见 [应用级注册验收](reference/application-registry-acceptance.md)。


## 9. Router SPI：应用级候选过滤

Consumer 的 `RpcRouter` 位于 `com.peachsoft.otryx.routing`，默认 `rules` 实现为 `RuleBasedRpcRouter`。调用链为 Mapping → Directory → Manifest/Schema/Health → **Router** → LoadBalancer → Transport。应用成员的 `attributes` 投影到本地 `ServiceInstance.metadata`；标签不可用于冒充身份或授权。

自定义 Router 必须只返回已验证数组中的原始 Provider 对象，不得修改输入、增加端点或执行 Registry IO。Core 会校验输出的引用身份并在发送前再次复核目录安全代次；空路由明确失败且不能偷偷回退到其他应用。规则按 `ServiceKey` 加载有界原子快照，更新和回滚均使用递增 revision。详细规则、灰度、优先级与配置示例见 [Router 参考](reference/routing.md)。


## 10. Interceptor SPI：独立调用生命周期

`com.peachsoft.otryx.interceptor.RpcInterceptor` 是有序 Java 扩展点，由唯一 Starter 按 Spring `@Order` 注入或通过 Core Builder 显式配置。调用方无需在热路径进行 ServiceLoader 扫描；空列表保持无 Hook 的既有同步调度路径。Context 只包含枚举角色、业务 ServiceKey、Method ID 与实际应用身份，不接收可伪造的 Metadata 身份、Payload 或证书字节。

Consumer `before/after/onFailure/onCancel` 对应一个逻辑调用，`beforeAttempt/afterAttempt` 只对应网络 Retry 序号，本地目录重新选点不增加 Attempt。Provider Hook 必须在真实身份、授权和 Provider Admission 后运行，且执行业务前再次验证有限许可。Hook 在专属有界 Worker 上运行，事件按单个调用顺序串行化；失败前置 Hook 会阻止业务执行，终态 Hook 异常不会覆盖已经产生的调用结果。详细安全、线程、容量与调用图见 [Interceptor 参考](reference/interceptors.md)。


## 11. RpcGovernanceRepository：有签名的完整快照控制面

`com.peachsoft.otryx.governance.RpcGovernanceRepository` 必须实现异步初始 `load()` 与可关闭 `watch(...)`，交付完整 `RpcGovernanceSignedUpdate`，不能只推字段差异。框架的唯一写入路径先由 `RpcGovernanceVerifier` 验证发行人与 HMAC 覆盖的全部字段，再在单线程有界工作池编译并原子发布；失败时保持静态安全基线和有期限的治理 LKG。当前 Starter **不隐式选择远端配置后端**，必须提供受保护 Adapter + 验证器两个 Bean。不可在 RPC 热路径发起 Nacos/Etcd/Consul IO。完整说明、回滚、配额所有权与故障处理见 [治理参考](reference/governance.md)。
