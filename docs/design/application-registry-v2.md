# OTRYX V2：应用级注册身份与 SPI 契约

> **当前状态：V2-01 新 API 阶段；应用级运行时尚未实现。**
>
> 总控 Issue [#127](https://github.com/Ryan-Guizhou/otryx-rpc/issues/127)，当前工作包 [#128](https://github.com/Ryan-Guizhou/otryx-rpc/issues/128)。
> 版本 JDK 21、Spring Boot 3.5.4、1.0.0-SNAPSHOT、Wire v1 均保持不变。

## 1. 核心决策

Provider 和 Consumer **都必须使用 Application 为注册身份**。一个进程实例的实际 RPC 监听端点只对应一条应用成员记录，接口数量不增加注册记录数。纯 Consumer 必须存在于应用成员目录，但没有可供 RPC 调用的 Endpoint，不能进入 Provider 负载均衡池。BOTH 同时表达消费和提供角色。

ServiceKey 继续描述 interface/version/group，维持原有 Method ID、Schema Fingerprint、协议和业务调用模型，但不能作为新的 Registry 实例主键。

目标调用链：ServiceKey → ServiceMapping → Application Directory → 实例 Manifest → Contract/Schema/Health 校验 → Router/LoadBalancer → Unary Transport。调用热路径不得进行远端 Registry IO。

**不保留长期接口级注册或发现、兼容模式、双读双写以及旧 Registry Key/Path。**

## 2. V2-01 新模型及限制

| Java 类型 | 意义 |
| --- | --- |
| ApplicationKey | namespace + applicationName，最长 128，限定可安全编码字符 |
| ApplicationRole | PROVIDER / CONSUMER / BOTH |
| ApplicationInstance | instanceId + ApplicationKey + Role + 可选真实 Endpoint + Weight + ManifestRevision + 有界 Attributes |
| ApplicationDirectorySnapshot | 同一应用、唯一 instanceId、不可变实例列表和单调 revision |
| ApplicationRegistryListener | 独立的目录更新、同步成功、同步失败事件 |
| ApplicationDiscovery | 按 ApplicationKey 查询或订阅完整成员快照 |
| ApplicationRegistrar | 注册/更新/注销应用实例，要求幂等和实例级隔离 |
| ApplicationRegistry | 能力声明、可选 Registrar、关闭与资源归还 |

应用身份 namespace / name 去首尾空白后均非空，仅允许英文数字及点、短横线、下划线，最长 128；instanceId 不允许控制字符，最长 256。

Provider/BOTH 必须包含实际 RPC Endpoint、正整数权重及 Manifest Revision。Consumer-only 的 Endpoint 和 ManifestRevision 必须是 null，权重为 0。实例扩展属性最多 64 项，key 最多 128，value 最多 1024 字符；构造后不可被外部修改。这些 Attributes 仅是路由标签，**不构成认证或授权证据**。

目录快照不接受跨 ApplicationKey 的成员、重复 instanceId、负 revision；成功健康通知不得作为清空实例的命令。接口契约与 Manifest 的校验将在后续 Issue 完成。

## 3. 分阶段开发约束

当前旧 Registry / ServiceRegistrar / ServiceDiscovery(ServiceKey) 同时被 Core Client/Server、ServiceDirectory 和所有 Registry Adapter 使用。由于 #129–#138 尚未实现，直接在 #128 删除所有旧类型会导致多模块 Maven 编译失败，违反每个独立 Issue 必须可验证地合并的规则。

**该现存代码只是分阶段开发期间的临时待替换实现，绝不是最终兼容或双模式支持。** 后续严格按以下顺序完成：

1. **#128**：新身份、强类型 SPI、快照、校验及测试（本 PR 目标）。
2. **#129–#130**：真实 Manifest Repository 和 ServiceMapping。
3. **#131–#132**：Memory Registry、Provider 和 Consumer 全面切换，验证 Consumer-only 入册。
4. **#133–#136**：Etcd、Nacos、Consul、Eureka 原生应用注册和元数据适配。
5. **#137–#138**：唯一 Starter、Quickstart 与双角色启动生命周期。
6. **#139**：仓库级强制清除**所有**旧接口级 Registry SPI、Key/Path、配置、测试和文案。未通过这一门禁不得宣布 V2 应用级注册完成。

不允许任何新代码调用旧接口级 SPI；不得增加运行时双模式开关。对于底层 Registry 不具备 Metadata KV 的能力，后续使用独立仓库并 fail fast，不能回退。

## 4. 质量验证

- 核验三种角色模型、Provider Endpoint/Manifest 约束、Consumer-only 不可路由；
- 核验命名空间、应用名、实例 ID、Attribute 等无效输入和最大边界；
- 核验 Snapshot 不可变、应用隔离、重复成员 ID、Revision；
- 核验 ApplicationRegistry REGISTRATION capability 与实际实现不一致时拒绝；
- 不改变 Unary Wire v1，也不增加 Core 对外部 Adapter 的依赖；
- 按 AGENTS.md 运行相关 Maven/JUnit、Java 格式/文档门禁。

后续任何 Agent 应当根据当前源码和 Issue 的真实状态判断完成度，不得把已提交的接口定义误报为 Provider/Consumer 已经使用应用级注册。
