# Fory Native 安全模式、能力与迁移

> 适用版本：**1.0.0-SNAPSHOT**。Wire v1 Payload 保持 Fory Native 编码、Codec ID 1 与 `Object[]` 参数形式。
> 本指南说明类型约束与资源门限；网络应用身份、有限授权和 Registry 安全链见 [安全指南](../security.md)。

## 1. 选择模式与显式运行 Profile

| Fory 模式 | 实际类型策略 | 使用边界 |
|---|---|---|
| `TRUSTED_COMPATIBILITY` | 保留可信场景的动态类型识别，并设置载荷、深度与估算内存门限 | 无参 `ForyRpcCodec` 的行为；不能满足 `NETWORK_MTLS` |
| `STRICT_ALLOWLIST` | 安装 Fory 原生 `AllowListChecker` 的 STRICT 检查器，并校验本地契约 DTO | 网络接入使用的 Fory 配置；必须提供真实应用类型规则 |

无参 Fory 构造器仍提供兼容 Codec，这不意味着 Core 会隐式以可信配置启动。
Spring 和程序化 Core Builder 都必须显式选择 `RpcSecurityProfile`；省略或 `UNSPECIFIED` 会失败。
`TRUSTED_DEVELOPMENT` 只适用于明确隔离并信任参与者的环境，TLS/mTLS 不能替代类型约束。

## 2. 配置真实应用白名单

以下是 **Codec 配置片段**，需与完整的 `NETWORK_MTLS` 身份、Registry 和 Provider 授权配置一起使用：

~~~yaml
otryx:
  rpc:
    security-profile: NETWORK_MTLS
    codec:
      fory:
        mode: STRICT_ALLOWLIST
        allowed-class-patterns:
          - com.example.contract.*
          - com.example.dto.*
        max-depth: 32
        max-graph-memory-bytes: 67108864
        max-payload-bytes: 16777216
~~~

白名单应覆盖实际 RPC DTO，而非整个应用 classpath。
上例包名是占位，必须替换为真实契约包。规则支持准确类名和应用包前缀，例如 `com.example.dto.*`。
`*`、`java.*`、`javax.*`、`jdk.*`、`sun.*` 这些宽泛规则被拒绝；严格模式的空白名单也被拒绝。
这不等于任意较窄规则都适合业务，应用仍须审核被允许类型的构造、字段和序列化行为。

程序化创建同一 Codec：

~~~java
ForyRpcCodec codec = new ForyRpcCodec(
        ForyRpcSecurityOptions.strictAllowlist(
                Set.of("com.example.contract.*", "com.example.dto.*")));
RpcCodecRegistry codecs = RpcCodecRegistry.of(codec);
~~~

该片段仅创建 Codec。Core Client/Server Builder 还必须设置明确 Profile、实际 Transport 和应用级控制面；
网络 Provider 还须显式提供 `RpcAuthorizer`。
`strictAllowlist(...)` 工厂给出的深度为 32、估算图内存为 64 MiB、Payload 为 16 MiB。
Spring 的 `max-depth` 属性缺省值是 50，上例显式设为 32；以最终实际配置为准。

实现依据：[Fory 配置值对象][fory-options]、[实际 Codec 构造][fory-codec]。

## 3. 检查所有实际 Codec 与方法绑定

安全准入使用 Core 的 `RpcCodecSecurityDescriptor`，不按 Fory 类名或 Codec ID 硬编码放行。
`NETWORK_MTLS` 对运行注册表中的**每个** Codec，以及方法绑定后实际使用的 `RpcMethodCodec`，要求：

| 能力 | 接受条件 |
|---|---|
| 类型策略 | `EXPLICIT_ALLOWLIST` 或 `REGISTERED_CONTRACT_TYPES` |
| 声明契约检查 | `validatesDeclaredContractTypes=true`，在绑定阶段校验实际方法类型 |
| 解码前载荷检查 | `validatesPayloadBeforeDecode=true`，在对象反序列化之前检查输入 |
| 资源门限 | 正的 `maxPayloadBytes`、`maxDepth`、`maxEstimatedGraphMemoryBytes` |
| Codec ID | 绑定后的 ID 与注册 ID 一致；Transport 实际启用的 ID 集合与注册表一致 |

默认未知 descriptor、兼容 Codec、额外注册但未满足要求的 Codec、
根 Codec 安全但方法绑定返回未知或较弱能力，都会使网络准入失败。
自定义 Codec 可以实现同一能力契约；它必须真实执行声明的限制，并提供相应回归证据。
只构造一份安全 descriptor 不会自动安装类型检查器。

Fory 的 descriptor 在创建时根据**实际安装**的严格检查器和资源设置冻结。
根 Codec 与它返回的方法 Codec 使用同一份不可变描述；读取描述不会重建 Fory 或执行 IO。
兼容模式实际报告 `TRUSTED_COMPATIBILITY`，不能通过包装配置把它提升为严格模式。

实现依据：[Codec 能力结构][codec-descriptor]、[Core 准入检查][core-checks]。

## 4. 静态契约与运行时类型分别验证

`RpcContractTypeModel.reachableTypes(RpcMethodDescriptor)` 从方法参数和返回值开始，
分析 record component、普通非静态/非 transient 字段、数组、泛型容器参数和继承类型。
`RpcTypeRegistry.register(descriptor)` 使用可达类型进行框架侧稳定身份与冲突检查。

Fory 严格模式在 `bind(descriptor)` 时验证可达的应用 DTO 是否被白名单覆盖，缺失则立即失败。
对于 `Object` 字段或多态基类，静态类型图不能推导全部运行时子类；
实际 Payload 中的应用类型仍由 Fory `AllowListChecker` 检查。
请求参数和响应返回值均经过这一实际 Codec，因此双方都要维护自己的可信类型范围。

这些框架侧 Type ID 与方法绑定校验不会把 Wire 改成 Fory 类注册编码，
也不会把原来的 `Object[]` 参数表示改成逐参数的新 Payload。
当前 API 可达类型、继承或泛型设计应以 [契约类型模型][contract-types] 为准，
不要从一次静态绑定成功推断任意多态图都可安全解码。

## 5. 资源限制的实际时点

| 设置 | 执行时点 | 不能据此宣称的保证 |
|---|---|---|
| `max-payload-bytes` | 解码前检查长度与 slice 区间；序列化完成后检查输出长度 | 编码前分配不会超过该值 |
| `max-depth` | Fory `withMaxDepth` 约束反序列化嵌套深度 | 对所有对象分配给出统一内存上限 |
| `max-graph-memory-bytes` | Fory `withMaxGraphMemoryBytes` 使用对象图内存估算门限 | JVM heap、DirectMemory、全部叶子对象和临时分配的硬上限 |
| Transport `max-frame-bytes` | 独立检查 Wire Frame 大小 | 一个合法小 Frame 的解码图必然很小 |

Payload 上限应与完整 Frame 的 header、Metadata 和 Transport 限制一起规划。
Frame 字节数、授权等待字节数、已准入业务字节数和 Fory 图内存估算是不同的预算，不能互相替代。
尤其是编码后拒绝超长结果时，Fory 已经执行了序列化分配。

**#141** 才负责后续覆盖实际分配的硬预算与握手前资源准入。
本次 `maxEstimatedGraphMemoryBytes` 的名字和能力描述明确保留“估算”边界，
不能用它声明已经解决所有恶意对象图、序列化暂存或握手前内存耗尽问题。

## 6. 身份与授权先于业务 DTO 解码

Provider 使用实际 mTLS 连接叶证书的完整 DER SHA-256，在受控绑定表中匹配
`ApplicationKey(namespace, applicationName)` 和有限期限。
Subject、仅公钥指纹、远端地址和请求 Metadata 不授予应用身份。
绑定、证书和原始单调时间共同约束身份，重用或恢复 TLS session 不重置有效期。

`NETWORK_MTLS` Provider 缺少显式授权器时启动失败。
授权器返回 `CompletionStage<RpcAuthorizationDecision>`，使用 `deny()` 或 `allowUntil(Instant)`；
许可由原请求接收时刻、请求 deadline、对端身份期限及配置的最大许可期共同限制。
Core 在受控的有界 worker 调用第三方策略方法体，不在 EventLoop 上直接执行该回调。

授权拒绝、异常、null 或许可在排队中到期，都不进入 Fory DTO 解码。
跨请求队列后和实际解码前重新检查；解码完成后、业务方法调用前也再次检查许可。
Consumer 对响应则在解码前及解码完成后交付前验证同一响应连接的 peer，
避免有效响应排队到过期后仍交付成功。

逻辑超时或取消不能证明第三方策略停止。
已运行授权工作必须等到方法体退出且原始 Stage 实际终止才释放并发槽位，
未运行的自有任务可以明确放弃；Frame 字节预算按逻辑授权终态回收。
这类工作所有权与 Fory 解码预算是独立控制，详细默认值和关闭行为见 [安全指南](../security.md)。

认证异常是固定、无原始 cause 的 `RpcAuthenticationException`，Core 不重试且中性结算端点统计。
方法授权拒绝使用 Wire v1 的 `BAD_REQUEST`，容量不足为 `OVERLOADED`，原请求超时为 `DEADLINE_EXCEEDED`。
这些结果不要求先解码不可信业务 DTO。

## 7. 迁移与验证

1. 为双方声明明确的安全 Profile。隔离开发可显式选择 `TRUSTED_DEVELOPMENT`；网络接入选择 `NETWORK_MTLS`。
2. 清点请求、响应和动态子类的实际 DTO，配置有限范围的白名单；先解决绑定阶段缺失类型。
3. 为本机和对端准备完整叶证书应用绑定、正常 CA/hostname 验证、有限授权策略和独立 Registry 安全链。
4. 核对实际注册的所有 Codec 与方法 Codec 能力，避免额外 SPI 扩展留下未知解码路径。
5. 在隔离环境验证严格模式、超限拒绝、业务权限、身份过期、队列边界和 Consumer/Provider 互操作。

迁移失败应修正 DTO 规则、实际能力或信任配置；`NETWORK_MTLS` 不提供回退到兼容 Codec 的开关。
既有可信开发模式可以单独验证，但不能用它替代网络安全验收。

[Fory 单元回归][fory-tests] 覆盖严格类型允许/拒绝、载荷/深度限制、方法绑定和根/方法 descriptor 一致性。
它不能单独证明真实 mTLS、跨 JVM 滚动互操作、Registry 权限、高负载吞吐或尾延迟。
这些门禁必须有对应准确提交的独立报告；整体状态见 [工程检查点](../engineering/agent-governance-checkpoint.md)，
本指南不把局部回归写成完整端到端验收 GREEN。

[fory-options]: ../../otryx-serialization/otryx-serialization-fory/src/main/java/com/peachsoft/otryx/codec/fory/ForyRpcSecurityOptions.java
[fory-codec]: ../../otryx-serialization/otryx-serialization-fory/src/main/java/com/peachsoft/otryx/codec/fory/ForyRpcCodec.java
[codec-descriptor]: ../../otryx-core/src/main/java/com/peachsoft/otryx/codec/RpcCodecSecurityDescriptor.java
[core-checks]: ../../otryx-core/src/main/java/com/peachsoft/otryx/core/RpcSecurityChecks.java
[contract-types]: ../../otryx-core/src/main/java/com/peachsoft/otryx/api/RpcContractTypeModel.java
[fory-tests]: ../../otryx-serialization/otryx-serialization-fory/src/test/java/com/peachsoft/otryx/codec/fory/ForyRpcCodecTest.java
