# Fory Native 安全模式与迁移（PR-B）

> 状态：**1.0.0-SNAPSHOT：Spring 显式安全 Profile 与 Provider 身份授权已接入；程序化默认行为仍在迁移中。**
> Wire v1 的 Payload 仍使用原有 Fory Native 编码、Codec ID 1、Object[] 参数形式；没有引入新的 Type ID 或变更固定协议头。

## 安全边界

| 模式 | 用途 | 类过滤 |
|---|---|---|
| TRUSTED_COMPATIBILITY | 既有可信应用平滑升级，默认值 | 保持既有可动态识别类型的语义，仅加资源限制 |
| STRICT_ALLOWLIST | 生产配置，尤其是不完全可信的对端 | Fory 原生 AllowListChecker 严格拒绝未批准的类 |

**注意：** TRUSTED_COMPATIBILITY 不是不可信数据的安全模式。TLS/mTLS 也不能替代反序列化类型约束。

## Spring Boot 接入

~~~yaml
otryx:
  rpc:
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

白名单要列出实际 RPC DTO 所在的应用包；不要使用 `*`、`java.*` 或 `jdk.*` 一类过宽规则。上例中的包名仅为占位，部署时必须改成真实契约包名。若缺少白名单或配置无效，启动失败。

程序化配置示例：

~~~java
ForyRpcCodec codec = new ForyRpcCodec(
    ForyRpcSecurityOptions.strictAllowlist(
        Set.of("com.example.contract.*", "com.example.dto.*")));
RpcCodecRegistry registry = RpcCodecRegistry.of(codec);
~~~

## 资源限制

- max-payload-bytes：在 Fory 反序列化前检查输入区间，编码后也检查输出。
- max-depth：使用 Fory 内置 `withMaxDepth` 限制反序列化嵌套层数。
- max-graph-memory-bytes：使用 Fory 的对象图内存**估算**门限，不是 JVM 堆硬上限，不涵盖所有叶子对象及序列化临时分配。
- Transport `max-frame-bytes` 仍然是独立限制；必须同步规划解码和帧的上限，建议 Payload 不高于 Frame 能容纳的范围。

## 兼容迁移顺序

1. 保持当前可信内网实例为 TRUSTED_COMPATIBILITY；先升级并校验滚动兼容。
2. 统计真实 RPC 方法涉及的 DTO 类型，显式维护允许的应用类和包。
3. 在隔离环境开启 STRICT_ALLOWLIST，运行字段结构、数组、集合、参数、返回值和滚动兼容性测试。
4. 双方完成验证后，分批启用 STRICT_ALLOWLIST，保留单独的回退开关。不要将兼容模式当作永久生产安全保障。

## U07 可达 DTO 类型模型（当前实施）

`RpcContractTypeModel.reachableTypes(RpcMethodDescriptor)` 从 RPC 方法参数和返回类型出发，发现自定义 DTO 的 record component、普通非静态/非 transient 字段、数组、泛型容器参数与继承父类类型，并为 `RpcTypeRegistry.register(descriptor)` 补充可达类的稳定身份与碰撞检测。

Fory 的严格模式在 `bind(descriptor)` 阶段校验这些**静态声明**的应用 DTO 是否全部被 `allowed-class-patterns` 覆盖，不满足则 fail-fast；实际报文中的未知多态子类仍由 Fory `AllowListChecker` 在解码时验证，不能从字段类型 `Object` 自动推断可接受的运行时子类。

**区别：** `RpcTypeIds` 与本类型图依旧只负责框架侧稳定 ID 与方法绑定校验，**不会调用 Fory 类注册接口，更不会修改 Wire v1/Codec ID 1/Object[] 的原生 Fory Payload**。

注解处理器增加真实 `JavaCompiler` 验证：继承/重载方法能够生成 Stub，而泛型方法、嵌套契约会给出诊断；Generated/JDK fallback 的 null 返回与异常终态有成对测试。当前自动处理器没有无限制支持通配符/自由类型变量或任意多态 Object，它们仍需设计明确的允许类型范围。

## 测试准入

- [ ] 兼容模式对旧 Fory Payload 读写正常
- [ ] 严格模式允许预期 DTO、阻止未知类
- [ ] 拒绝超限 Payload、异常深对象图、过大集合/对象图
- [ ] 旧新 Consumer/Provider 独立 JVM 滚动兼容
- [ ] CI 完整通过，安全模式高负载下的吞吐与尾延迟有单独证据

没有测到的性能与安全性质不得当作已验证事实。

## U05 Spring 安全意图与解码前授权

Spring Boot 现在要求显式指定 `otryx.rpc.security-profile`；未指定或值为
`UNSPECIFIED` 时启动失败。`TRUSTED_DEVELOPMENT` 为隔离开发的显式许可；
`NETWORK_MTLS` 强制 `otryx.rpc.transport.security.mode=MTLS` 和
`otryx.rpc.codec.fory.mode=STRICT_ALLOWLIST`，并要求实际应用 DTO 白名单。

Provider 由 Vert.x TLS 握手后的 `peerCertificates()` 获取已验证客户端证书主体与
SHA-256 指纹。TLS 单向模式与明文连接一律为匿名；Metadata（包括伪造身份字段）
不会提升连接身份。解码请求对象之前调用非阻塞 `RpcAuthorizer.authorize`，
其上下文提供 `RpcPeerIdentity`、`ServiceKey`、MethodId 和 Remote Address；
业务可注入 Spring `RpcAuthorizer` Bean 进行服务及方法级授权。
`NETWORK_MTLS` 的已验证身份前置检查不能被自定义 Bean 绕过。

`otryx.rpc.server.authorization.max-pending`（默认 1024）限制真实未完成授权；
`otryx.rpc.server.authorization.timeout`（默认 3s）控制逻辑超时。
拒绝返回 Wire v1 现有 `UNAVAILABLE`，容量耗尽返回 `OVERLOADED`，
不添加新 Wire 状态。超时不意味着第三方策略停止，因此槽位在实际 Stage
完成时才归还。授权代码不得在 EventLoop 上阻塞，若访问数据库/外部服务
必须使用自带容量治理的异步客户端或有界执行器。

**程序化边界**：无参 `ForyRpcCodec` 和部分既有 Core Builder 仍以可信开发
兼容行为启动；非可信网络接入须显式 `new ForyRpcCodec(strictAllowlist(...))`
和 Provider `authorizer(...)`，不能单独依赖此 Spring 安全 Profile。
