# Nacos Registry Adapter

## 1. 定位

`otryx-registry-nacos` 将 Nacos NamingService 适配为 OTRYX RPC 的 `Registry` / `ServiceRegistrar`。它只属于控制面：Consumer 单次 RPC 热路径读取 Core 的本地 `ServiceDirectory`，不会访问 Nacos。

当前基线使用 Nacos Client 3.2.4。

## 2. 坐标映射

| OTRYX RPC | Nacos | 说明 |
|---|---|---|
| `RegistryOptions.namespace` | namespaceId | 空值时使用 `public` |
| `registry.nacos.group` | groupName | 默认 `OTRYX_RPC` |
| `ServiceKey.canonicalName()` | serviceName | 接口名、版本和 RPC group 组成稳定身份 |
| `registry.nacos.cluster` | clusterName | 默认 `DEFAULT` |
| `RpcEndpoint` | Instance ip/port | 必须是 Consumer 可路由地址 |
| `ServiceInstance.weight` | Instance weight | Core 100 对应 Nacos 1.0 |
| `ServiceInstance.metadata` | Instance metadata | 用户键与框架保留键共同发布 |

Nacos group 与 RPC `ServiceKey.group` 不可混用：前者是 Registry 管理分组，后者属于 RPC 服务身份。

## 3. 保留元数据

`otryx.rpc.*` 是框架命名空间，分为两类：

**Nacos Adapter 生成的身份键**：

- `otryx.rpc.instance-id`
- `otryx.rpc.interface`
- `otryx.rpc.version`
- `otryx.rpc.group`
- `otryx.rpc.protocol`
- `otryx.rpc.cluster`

这些键由 Adapter 根据 `ServiceInstance` 写入，输入 metadata 覆盖它们会在注册前 fail-fast。

**Core 兼容元数据**：

- `peach.rpc.protocol.version`
- `peach.rpc.schema.version`
- `peach.rpc.schema.fingerprint`

这些键由 N+1 Provider 的 Core 生成，Registry Adapter 必须原样透传，旧 Consumer 会忽略它们，新 Consumer 用于 Schema Compatibility 过滤。

## 4. 查询与订阅

`lookup(ServiceKey)` 只查询指定服务、group 和 cluster，不扫描全量服务。

订阅流程：

1. 使用固定 EventListener 注册订阅；
2. 拉取当前完整实例视图作为 initial snapshot；
3. 在 initial snapshot 期间到达的 NamingEvent 先缓存；
4. 后续事件进入单订阅串行队列；
5. healthy=false、enabled=false、权重非正或端点非法的实例被过滤；
6. 实例按 endpoint 与 instanceId 稳定排序并去重；
7. 相同视图不重复刷新 Core；
8. 变化视图使用 Adapter 进程内 AtomicLong 生成单调 revision；
9. 同一 Registry Client 主动注销本地 Provider 后，会立即从该 Client 的 subscription 快照中移除对应 endpoint，避免最后一个实例注销时目录悬挂；
10. 远端 Provider 生命周期以 Nacos NamingEvent 为主通道；
11. Registry 每 5 秒执行一次低频完整视图 reconcile 作为最终一致性兜底；只有事件队列空闲时才执行，避免旧查询结果覆盖正在排队的新事件；
12. 独立 Provider/Consumer Client 集成测试验证“远端最后一个 Provider 注销 -> empty snapshot”最终收敛；
13. 关闭时取消 reconcile task，并使用原 EventListener 实例 unsubscribe；
14. 初始订阅失败不会被伪造为健康空视图，也不会直接销毁该订阅；5 秒 reconcile 周期负责单飞重试；
15. SDK 查询失败时发送明确的同步失败信号，保留最近有效实例快照；远端查询恢复后，即使内容没有变化也确认健康；
16. NamingEvent 为完整实例视图，Adapter 只暂存最新事件，避免慢 Consumer 带来无界事件队列；
17. 远端查询期间收到的新 NamingEvent 优先，旧查询结果不得倒灌覆盖；
18. Nacos 对 Ephemeral Provider 的心跳与重注册仍由其官方客户端负责，本适配层没有额外的无界注册重试任务。

## 5. 线程与资源

Nacos Java SDK 的注册、注销、查询和订阅初始化可能阻塞。Adapter 使用名称以 `otryx-nacos-control-` 开头的私有有界 ThreadPoolExecutor：

- core threads：2；
- max threads：4；
- queue：256；
- 拒绝时快速失败；
- 不使用 ForkJoinPool.commonPool；
- 不占用 Vert.x Event Loop；
- 单线程 daemon scheduler 只负责触发低频 reconcile，真实 Nacos 查询仍提交到上述有界控制面执行器。

Registry 关闭先取消订阅，再关闭 NamingService，最后关闭控制面执行器；重复关闭幂等。
注册服务的 SDK 自动恢复应以独立 Nacos 故障注入的 Provider 停机/恢复测试验收，
不要误认为应用层已经实现主动重建全部 Provider 注册。

## 6. 配置

```yaml
otryx:
  rpc:
    registry:
      type: nacos
      endpoints: 127.0.0.1:8848
      namespace: public
      nacos:
        group: OTRYX_RPC
        cluster: DEFAULT
        username: ${NACOS_USERNAME:}
        password: ${NACOS_PASSWORD:}
```

用户名和密码不会进入 Core 类型。错误日志不得输出 password。

## 7. Provider 发布地址

监听地址与注册中心发布地址是不同概念：

```yaml
otryx:
  rpc:
    server:
      host: 0.0.0.0
      port: 19090
      advertised-host: 10.0.0.15
      advertised-port: 19090
```

当 host 为 `0.0.0.0`、`::` 或 `[::]` 且没有 advertised-host 时，Provider 启动失败，避免把不可路由地址写入 Nacos。advertised-port=0 时使用 Transport 实际监听端口。

## 8. 测试

CI 启动固定版本 Nacos 3.2.4，并设置 `NACOS_TEST_ENDPOINT`。当前自动化覆盖 SPI 加载、服务名映射、权重与元数据映射、健康实例过滤、共享 Registry Contract、独立 Provider/Consumer Client 的远端注册/注销订阅收敛、真实注册/查询/订阅/注销，以及拆分 Provider/Consumer 的 RPC round-trip。独立 Nacos Chaos 使用 Consumer、Provider A、Provider B 三个 Registry Client 模拟容器 pause/unpause 下的注册与订阅恢复。

本地未提供 `NACOS_TEST_ENDPOINT` 时，真实 Nacos 集成测试会跳过；普通单元测试仍正常执行。
