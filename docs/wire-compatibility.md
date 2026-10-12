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

## 4. Registry Metadata

新 Provider 注册实例时发布：

~~~text
peach.rpc.protocol.version
peach.rpc.schema.version
peach.rpc.schema.fingerprint
~~~

Consumer 在 Registry Snapshot 更新时完成兼容过滤，单次 RPC 热路径不额外计算 Fingerprint，也不额外创建 Schema Metadata。

## 5. Rolling Compatibility Matrix

| Consumer | Provider | 行为 |
|---|---|---|
| N | N | 原有 v1 行为 |
| N | N+1 | 旧 Consumer 忽略新增 Registry Metadata |
| N+1 | N（缺少完整 Schema Metadata） | INCOMPATIBLE，从本地目录剔除 |
| N+1 | N（Metadata 完整且 Fingerprint 相同） | COMPATIBLE，可以滚动互操作 |
| N+1 | N+1，相同 Schema | COMPATIBLE，正常路由 |
| N+1 | N+1，不同 Schema | INCOMPATIBLE，从本地目录剔除 |

因此同一 Wire v1、相同指纹规范且完整 Meta 的业务契约可以滚动升级；**不再**为尚未发行的历史快照保留缺失 Fingerprint 的宽松路径。

## 6. Rollback

N+1 回滚到 N 时：

1. N Consumer 的行为取决于 N 版本自身的兼容策略；
2. N+1 Consumer **拒绝**缺少 Wire/Schema Version/Fingerprint 的 N Provider；
3. N Provider 只有发布了完整且一致的元数据才会进入 N+1 本地目录；
4. Registry revision 继续保证旧 Snapshot 不覆盖新 Snapshot。

如果 N+1 引入了不兼容业务契约，则必须通过新的 ServiceKey.version 隔离，不能依赖 Rollback 自动兼容 Schema。

## 7. HELLO 与 Registry Metadata 的边界

Schema Compatibility 当前放在 Registry 控制面，而不是每连接 HELLO Payload。

原因：

- 不改变现有 v1 HELLO Payload；
- 不增加每个连接的服务级 Schema 列表；
- 一个 Provider 进程可以暴露多个 Service；
- Consumer 能在选连接前过滤明确不兼容实例。

HELLO 继续负责 Protocol Version、Codec、Compression、Feature 与 Max Frame Bytes。

## 8. 当前限制

- 当前不是跨语言 IDL；
- Fory Native 仍是 Java Codec；
- 1.0.x 不提供字段级宽松兼容；
- 不允许根据 Classpath 顺序分配 Type ID；
- 缺失 Fingerprint、协议版本或 Schema 版本的节点均会拒绝；这些安全拒绝不能当作 Registry 故障进行无限重试。

跨语言 Protobuf/IDL 属于 1.1.x+ 战略生态，不阻塞 1.0 GA。
