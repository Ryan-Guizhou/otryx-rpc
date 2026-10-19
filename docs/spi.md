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
ApplicationRegistry registry = factory.create(new RegistryOptions(
        List.of("http://127.0.0.1:2379"), "demo", Map.of()));
ApplicationRegistrar registrar = registry.registrar().orElseThrow();
ApplicationMetadataBackend metadata = (ApplicationMetadataBackend) registry;
```

此片段说明扩展查找；完整运行时必须使用 `ApplicationMembershipCoordinator` 建立固定身份，再通过 Client `applicationControlPlane`、Server `applicationMembership` 装配并负责关闭。普通 Spring 应用使用 Starter 自动装配，参见 [Starter](reference/starter.md)。

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

`RegistryOptions` 包含 endpoints、namespace、providerOptions 和可选 Observer。endpoints/namespace 为公共字段；SDK 专属设置放入 `providerOptions`，Core 不引入厂商 SDK。

后端默认值、超时、ACL、容量和失败边界分别见 [Etcd](reference/registry-etcd.md)、[Nacos](reference/registry-nacos-v2.md)、[Consul](reference/registry-consul-v2.md)、[Eureka](reference/registry-eureka-v2.md)。Nacos 每个新 namespace/group 必须在业务启动前显式初始化 Mapping 分片；Eureka 缺少可靠外置元数据配置直接失败。

应用目录的新鲜度预算必须为严格正的有限值，默认 30 秒；远端读取、订阅、重试、取消与关闭均在控制面处理。RPC 热路径只读取不可变本地快照。快照读取成功与内容改变是不同事件，详见 [新鲜度与恢复](reference/registry-freshness.md)。

## 6. Codec 与代理

`RpcCodec.bind(RpcMethodDescriptor)` 在方法粒度预绑定 encoder/decoder，单次请求不重新解析反射 Method 或 Schema。Codec ID 不按 SPI 加载顺序动态分配。

标注 `@OtryxRpcContract` 并启用 `otryx-codegen` 后，编译期生成 Consumer Stub。运行时优先发现生成类，缺失才回退 `ProxyFactory`。JDK Proxy 为默认 fallback，CGLIB 与 Byte Buddy 按需添加。

## 7. 验证扩展

新增后端须执行相同的应用成员、角色、Manifest、Mapping Owner、订阅健康、失败和关闭契约，不能仅有工厂加载测试。受控计数应分别报告原生成员、SPI 方法入口调用数、订阅创建调用数及快照/健康通知和收敛时间，不能将它们表述为后端全部 HTTP、keepalive 或 server Watch 流量。

运行入口和真实证据边界见 [应用级注册验收](reference/application-registry-acceptance.md)。
