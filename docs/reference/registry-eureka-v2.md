# Eureka V2 应用级注册

> Issue [#136](https://github.com/Ryan-Guizhou/otryx-rpc/issues/136) · `1.0.0-SNAPSHOT` · 当前应用级后端；#139 集成验收仍须以准确 PR Head、真实后端执行和 main CI 为准。

## 设计与边界

- **成员面：Eureka**。以 `ApplicationKey(namespace, applicationName)` 的确定性散列命名一个原生 Eureka Application；同一个 Provider 的所有 RPC 接口共享一个实际进程 `InstanceInfo`。Consumer-only 同样真实注册应用实例，使用本机真实 IP/Host，原生 `port=0` 且 `@enabled=false`，无 RPC Endpoint；禁止以假 RPC 端口欺骗路由。
- **元数据面：Etcd、Nacos 或 Consul**。必须显式配置至少一个拥有 CAS 的元数据后端；通过 `ApplicationMetadataBackend` 和 `ServiceMappingCandidateSource` 在后端读取 Manifest 与按实例 CAS Mapping。Eureka 不被伪装成 KV；外置后端**不注册** Eureka 的业务 Member。
- **安全路由**：Mapping 的 ServiceKey→ApplicationKey + **真实 Owner instanceId** 是未验证候选；再用同一个实例的 Eureka UP、Provider/BOTH 角色、真实端点和 InstanceInfo Meta 交集过滤，不能让其他健康实例替未发布该接口的 Owner 获得路由权，最后 Core 使用 Manifest/Schema 和服务实际绑定检查。纯 Consumer 只能被观察，不能被路由。
- **冲突保护**：Eureka Agent 没有跨进程 InstanceInfo CAS。Native Instance ID 含进程随机 Token，防止两个进程覆盖彼此的注册；若多个原生成员映射到同一逻辑 instanceId，则发现失败关闭，而不是随机选一个。实例标识应由部署编排保证唯一，真正的分布式 CAS Fencing 由元数据后端处理。
- **生命周期**：按 Eureka lease 续期；404 时重新注册；注销仅删除本进程原生 ID；轮询订阅发送明确故障信号，不能把网络/授权失败换成空目录。共享控制面拥有有界线程/队列、HTTP Timeout 与关闭预算，RPC EventLoop 不执行网络请求。

## 使用

只需在应用中添加可选 `otryx-registry-eureka`，同时**显式**引入某一个 `otryx-registry-etcd` / `otryx-registry-nacos` / `otryx-registry-consul` 依赖（默认为不引入）。对于 Consul KV：

```java
RegistryOptions options = new RegistryOptions(
    List.of("http://127.0.0.1:8761/eureka"),
    "production",
    Map.of(
        "eurekaMetadataBackend", "consul",
        "eurekaMetadataEndpoints", "http://127.0.0.1:8500",
        "eurekaMetadata.consulToken", System.getenv().getOrDefault("CONSUL_HTTP_TOKEN", ""),
        "eurekaMemberHost", "192.168.1.10",
        "eurekaLeaseSeconds", "30",
        "eurekaHeartbeatSeconds", "10",
        "eurekaPollIntervalMillis", "1000"));
EurekaApplicationRegistry registry = (EurekaApplicationRegistry)
        ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
                .getExtension("eureka").create(options);
ApplicationManifestRepository manifests = registry.manifestRepository();
ServiceMappingRepository mappings = registry.serviceMappingRepository();
```

`eurekaMetadata.*` 透传**外置后端** `RegistryOptions.providerOptions`。例如 Etcd `eurekaMetadata.leaseTtlSeconds`，Nacos `eurekaMetadata.nacosGroup`，Consul `eurekaMetadata.consulToken`；逻辑 Namespace 与 Eureka **必须一致**，不允许偷偷回退至第二个 Namespace。

Eureka HTTP Basic Auth 选项为 `eurekaUsername` / `eurekaPassword`，建议仅配 HTTPS 并从环境/密钥管理注入；不能把 Eureka Metadata 当成认证身份。Eureka 的 ACL 与外置元数据后端权限是两份独立的部署策略。

## 资源和失效模式

Eureka 只发布实际应用成员，不声明全局持久 Revision，也没有原生 CAS Mapping。成员最多 256、订阅最多 128；控制面采用 2–4 线程与 256 队列；默认租约 30s、续期 10s、查询轮询 1s、HTTP 请求 3s。订阅故障不能刷新 LKG，Consumer 新鲜度策略由 Core 控制。

`directory-max-staleness` 只约束明确同步失败之后的 LKG 使用时间。成员轮询将通过解析校验的 HTTP 200 响应，以及按空应用处理的 HTTP 404，视为成功同步；即使服务端缓存尚未反映最新成员变化，这些结果也不会自动产生同步失败。Core 无法仅凭成功响应识别这种缓存滞后，因此该预算不是 Eureka 服务端缓存内容的年龄上限。详见[目录新鲜度](registry-freshness.md)。

外置 Mapping 的 `lookupCandidateOwners` 返回带 Owner `instanceId` 的**未验证候选**，严禁直接送入负载均衡器。Etcd/Consul 的普通 `lookup` 依旧保留其自身 Member Lease 交叉检查，只有组合后端候选读取跳过这些源注册中心的 Lease，再使用 Eureka 的实际 Member Health 检查。

## 验证

`.github/workflows/eureka-v2.yml` 在 CI 运行使用 JDK 21、Spring Boot 3.5.4、Spring Cloud 2025.0.0 的独立真实 Eureka Server 测试夹具及真实 Consul KV。测试包括 Provider/Consumer/BOTH、原生 Port disabled、30 个真实 Java 接口×10 个独立 Registry client 对象（同一个测试 JVM）、多实例冲突、Manifest/Mapping CAS、Server Lease 丢失、Eureka JVM 重启及恢复。

测试服务器位于 `.github/test-fixtures/eureka-server`，不属于 RPC Maven Reactor，不会产生第二个生产 Starter。Starter/Quickstart 只使用应用控制面；独立业务 JVM 的 process/chain/revision 矩阵另由 `.github/workflows/application-registry-e2e.yml` 使用真实 Eureka + Consul 执行。原生成员数、SPI 方法入口调用数、订阅创建调用数及快照/健康通知与收敛时间分别计量，不是后端全部 HTTP、续租或服务端 Watch 流量，见[验收口径](application-registry-acceptance.md)。

隔离 Eureka Server 夹具设置 `eureka.server.use-read-only-response-cache=false`，仅停用只读缓存层；读写 Response Cache 仍通过自身的失效和过期机制更新。夹具还设置 `eureka.server.response-cache-auto-expiration-in-seconds=1`，将读写缓存的写入后过期时间设为 1 秒；缓存项过期后，由后续查询重新加载。`eureka.server.response-cache-update-interval-ms` 不能当作读写缓存的过期时间。

夹具缓存参数只界定隔离 CI 的收敛条件，不构成生产 Adapter 修复，也不保证 Eureka 生产默认配置在 25 秒内收敛。实际运行配置、执行结果和未完成门槛见[工程断点](../engineering/agent-governance-checkpoint.md)。
