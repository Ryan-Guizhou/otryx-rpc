# OTRYX RPC Wire Compatibility

> 状态：**1.0.0-SNAPSHOT 契约候选 / Wire v1 固定头格式**  
> 本文定义 1.0.x 的稳定标识、Schema Fingerprint 与滚动升级语义。

> OTRYX 1.0 保留 Wire v1，但 Java 类型全限定名和 Maven 坐标已迁移；跨 Peach RPC 1.0.x 的 Type ID/Method ID/Schema Fingerprint **不自动兼容**。详见 [迁移指南](migration.md)。

## 1. 兼容模型

OTRYX RPC 将以下概念明确分离：

~~~text
Protocol Version
Codec ID
Type ID
Schema Fingerprint
Feature Capability
ServiceKey Version
~~~

其中：

- Protocol Version 决定固定线协议格式；
- Codec ID 决定 Payload 编解码方案；
- Type ID 提供与 Classpath/注册顺序无关的稳定类型身份；
- Schema Fingerprint 表示完整 RPC Service 契约；
- Feature Capability 通过 HELLO/ACK 协商；
- ServiceKey Version 表示业务契约版本边界。

## 2. Stable Type ID

RpcTypeIds 使用规范化 Java Type 名称计算稳定 ID。

| 区间 | 用途 |
|---|---|
| 1..1023 | Framework reserved |
| 1024..2147483646 | User contract types |

RpcTypeRegistry 在方法绑定阶段注册参数和返回类型。不同 Type 若碰撞到同一个 ID，会 fail-fast。

当前 Fory Adapter 在 bind(RpcMethodDescriptor) 阶段执行该检查。

> Stable Type ID 当前用于稳定身份与冲突检测；Fory Native Payload 仍保持现有格式，不把 Type ID 强行插入 v1 Payload，因此不会破坏既有 Wire 格式。

## 3. Schema Fingerprint

RpcSchemaFingerprint 当前规范版本为 1，使用 SHA-256。

Fingerprint 输入包括：

- ServiceKey；
- 排序后的 RPC 方法；
- 参数泛型类型；
- 返回泛型类型；
- 自定义参数化 DTO 的实际类型变量与字段结构；
- Enum 常量集合；
- 仅 public abstract 实例方法作为 RPC 方法，default/static/Object 不属于远程方法；
- Record Component；
- 普通 DTO 的非 static、非 transient 字段；
- 父类 DTO 字段。

反射字段顺序和方法枚举顺序不会影响结果。

### 保守兼容原则

当前模型是严格 Fingerprint：

- DTO 增加字段：Fingerprint 改变；
- DTO 删除字段：Fingerprint 改变；
- 字段类型变化：Fingerprint 改变；
- 方法参数/返回值变化：Fingerprint 改变；
- 方法集合变化：Fingerprint 改变。

如果变更需要与旧契约同时在线，推荐发布新的 ServiceKey.version，而不是覆盖原版本。

### U07 Type Model 与 Fory Wire 的分离

`RpcContractTypeModel` 在方法级绑定时递归发现可达 DTO，用于 `RpcTypeRegistry` 的稳定身份碰撞检测及严格 Fory 白名单的**静态声明类校验**。它不能将 `RpcTypeIds` 自动映射为 Fory Native Payload 中的类注册编号；当前 Codec ID 1 的真实线上编码不变。应用运行期的多态/Object 参数仍须由 Fory 的独立类检查器守护。

## 4. 应用 Manifest 的兼容校验

Provider 发布标准二进制 ApplicationManifest，其中包含 Manifest/Wire/Schema 版本、ApplicationKey 和实际导出服务的 ServiceKey、Service ID、Method ID、Fingerprint；ApplicationInstance 只引用其内容 Revision。不同真实导出集合会得到不同 Revision，可以在同一个应用目录共存。

Consumer 后台使用 ServiceMapping、应用健康成员和实例自己的 Manifest 校验。缺失内容、内容身份不符、版本/指纹不匹配或纯 Consumer 均不能进入该服务的 Provider 池；单次 RPC 不重新访问远端或计算清单。

## 5. Rolling Compatibility Matrix

两个 checkout 都必须使用应用级 Starter。历史接口模式、未知或混合装配在开始前直接失败，不能缩减成较少配对。

| Consumer | Provider | 必须检查的行为 |
| --- | --- | --- |
| N | N | 基线自身真实 RPC |
| N+1 | N | 新 Consumer 对应用级基线 Provider |
| N | N+1 | 基线 Consumer 对新 Provider |
| N+1 | N+1 | 当前组合真实 RPC |
| N+1 | 回滚后的 N | Provider 回滚后真实 RPC |

以上每组都要求相同业务契约在各实例的 Manifest 中通过验证；相同 Wire v1 不会放宽 Schema 校验。任一组失败，整个矩阵失败。`run_quickstart_revision_e2e.sh` 另外验证同一应用中 `{Greeting}` 与 `{Greeting, Details}` 两个实际导出集合、共存、升级和回滚；共同 Greeting 契约保持不变。

## 6. Rollback

回滚保留可能仍被在线实例或回滚版本引用的 Manifest Revision。实例 Owner 的 Generation fencing 防止迟到发布/撤销破坏新所有者；注销仅撤销自己的成员和导出映射，不删除共享清单或 Generation 墓碑。

业务 DTO、Method ID 或 Fingerprint 改变时，须通过不同 ServiceKey.version 等明确业务版本边界隔离。历史开发注册模型的成对迁移见[迁移指南](migration.md)，不能将其当作支持的 rolling baseline。

## 7. HELLO 与应用 Manifest 的边界

Schema 校验在应用控制面完成；HELLO 负责 Protocol Version、Codec、Compression、Feature 与 Max Frame Bytes。清单不加入每个连接的 HELLO Payload，一个 Provider 进程可以导出多个服务，Consumer 在选择连接前排除不匹配实例。

完整当前契约与证据层次见[应用级注册验收](reference/application-registry-acceptance.md)。

## 8. 当前限制

- 当前不是跨语言 IDL；
- Fory Native 仍是 Java Codec；
- 1.0.x 不提供字段级宽松兼容；
- 不允许根据 Classpath 顺序分配 Type ID；
- 缺失 Fingerprint、协议版本或 Schema 版本的节点均会拒绝；这些安全拒绝不能当作 Registry 故障进行无限重试。

跨语言 Protobuf/IDL 属于 1.1.x+ 战略生态，不阻塞 1.0 GA。
