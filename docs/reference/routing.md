# Router SPI：应用级路由、灰度与标签

> 当前源码契约：`1.0.0-SNAPSHOT` · JDK 21 · Wire v1。本文描述 Router 代码能力，不代表正式容量或灰度服务质量验证。

## 设计边界

OTRYX 的 Registry 主键是 ApplicationKey。`ServiceKey` 只描述业务接口与 Schema，不能作为实例注册身份。应用服务解析器必须首先通过 Mapping、Application Directory、不可变 Manifest、Schema 和安全 epoch 验证。只有具备真实 RPC Endpoint 的 Provider/BOTH 实例可进入路由候选。

```mermaid
flowchart LR
    R[ServiceKey Mapping] --> D[Application Directory]
    D --> M[Manifest and Schema verification]
    M --> L[Verified Provider candidates]
    L --> F[RpcRouter local snapshot]
    F --> B[P2C EWMA LoadBalancer]
    B --> T[RPC Transport with peer verification]
```

`RpcRouter` 在本地同步运行；禁止后端查询、配置解析、阻塞 IO 或用户自定义长时间任务。任何输出只能是输入数组中**同一对象引用**的去重子集。输入为只读副本，自定义 Router 即使修改自己的数组也不能改写可信目录。空集合直接失败，不返回其他应用；Transport 在真正发送之前仍通过原候选进行安全 epoch 复核。

## 成员标签与默认行为

Spring `application.yml` 示例：

```yaml
otryx:
  rpc:
    security-profile: TRUSTED_DEVELOPMENT # 仅本地隔离演示
    registry:
      type: memory
      member-attributes:
        zone: cn-shanghai
        release: v2
        tier: gold
        track: gray
    client:
      router: rules
```

`member-attributes` 随同实际 Application Membership 发布，保留 Provider/Consumer/BOTH 角色和真实/缺省 Endpoint 语义。Consumer-only 标签不能让其变为可调用 Provider。属性由 Registry 和 Manifest 之外的非可信来源输入，不能替代证书的 ApplicationKey 绑定或服务端授权。默认 `rules` 没有任何规则，直接使用已验证候选，没有每次调用候选数组复制。

## 规则与程序化更新

在 Spring Boot 中使用 `@Bean` 暴露同一 Router 实例，也可以在非 Spring Client Builder 调用 `router(...)`：

```java
@Bean
RuleBasedRpcRouter applicationRouter() {
    RuleBasedRpcRouter router = new RuleBasedRpcRouter();
    ServiceKey service = new ServiceKey(
            "com.example.GreetingService", "1.0.0", "default");
    ApplicationKey target = new ApplicationKey("prod", "greeting-v2");
    RpcRouteRule rule = new RpcRouteRule(
            Set.of(target), List.of(target), Map.of("tier", "gold"),
            "cn-shanghai", "v2", 10);
    router.publish(new RpcRoutingSnapshot(1, Map.of(service, rule)));
    return router;
}
```

示例需要显式导入以上 Java 类型及 `Set`、`List`、`Map`。`ServiceKey` 的版本是接口契约，`release` 是成员部署版本标签，两者互不替代。`grayPercentage` 为 `null` 表示不启用灰度；0 表示全部走 `track=stable`，100 表示全部走 `track=gray`，10 表示每次路由约 10% 进入灰度轨道。灰度使用 `ThreadLocalRandom`，属于**每次选择的概率分流**，并非调用 ID 粘性路由；后续重试可能落入不同轨道，因此不应将其作为有状态会话的粘性保证。

`allowedApplications` 是完整 `ApplicationKey` 白名单，避免应用名同名但 namespace 不同。`applicationPriority` 按顺序选择第一个有剩余候选的应用，所有优先项都不可用时可回退到**仍通过白名单、zone、release、标签、track 的候选**；若需要禁止跨应用回退，只配置白名单中的单个目标应用。

规则不会远端拉取。应用的控制面线程在认证并校验配置后调用 `publish(new RpcRoutingSnapshot(nextRevision, rules))`；只有更高 revision 能成功提交，旧版本返回 false。回滚必须调用 `rollback(previousSnapshot, largerRevision)` 生成新版本。`RpcRoutingSnapshot` 最多 512 个 ServiceKey，单条规则最多 128 个应用与 32 个标签，所有输入在发布前生成不可变副本。#144 后续将增加有权限的动态治理配置源，目前**没有远端治理规则自动订阅**。

## 验证与局限

- 路由测试覆盖 10%/90% 有限容差、应用白名单、区域、版本、优先级、规则失配、递增 revision 和回滚；Core 端到端测试覆盖伪造端点、修改输入及重复候选拒绝。
- 标签不是授权数据：未经验证的成员、错误 Schema、Consumer-only 成员和安全 epoch 失效不能由 Router 重新放行。
- 请求热路径不执行 Registry IO。启用标签过滤会产生短生命周期本地数组；其分配成本必须纳入 #146 的固定硬件分析，当前不宣称性能提升。
- 连接、TLS/Codec 内存硬限制由 #141 独立处理；路由匹配成功不能证明远端可达。

返回 [中文 README](../../README.md) · [English README](../../README.en-US.md) · [Quickstart](../../otryx-quickstart/README.md) · [SPI](../spi.md)。
