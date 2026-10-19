# Etcd V2 应用级 Registry（#133）

> 范围：`otryx-registry-etcd` 的原生应用成员、内容寻址 Manifest、Owner-fenced ServiceMapping。
> 当前为唯一应用级控制面。#139 的真实后端共享契约、全仓清理、独立 JVM 与 Chaos 验收状态见[应用级验收](application-registry-acceptance.md)和[断点](../engineering/agent-governance-checkpoint.md)。

## 设计与身份

Provider、Consumer、BOTH 均按 `ApplicationKey(namespace, applicationName) + instanceId` 注册**真实的进程成员**。同一实例导出 30 个业务接口仍只存在一个 Etcd 成员键。

- PROVIDER/BOTH：必须包含真实 RPC Endpoint、正权重和 Manifest revision。
- CONSUMER：注册真实且可续租的成员记录，`rpcEndpoint == null`、`manifestRevision == null`、`weight == 0`，不能进入负载均衡池。
- `ServiceKey` 仅用于 `ServiceMappingKey` 和实际业务契约；不再作为 V2 成员的 Registry Key。
- 业务热路径只读取 Core 的本地 `VerifiedProviderSnapshot`；Etcd 的 Range/Watch/Mapping、Manifest 读取位于控制面。

## V2 键空间

用户字段全部按 UTF-8 → URL-safe Base64（无填充）编码，`/` 不会出现在编码段中：

| 分类 | Etcd Key（简化） | 更新策略 |
| --- | --- | --- |
| 应用成员 | `/otryx/v2/ns/{ns}/apps/{app}/members/{instanceId}` | 带 Lease 的 CAS，进程 Token 隔离 |
| Manifest | `/otryx/v2/ns/{ns}/apps/{app}/manifests/{sha256Revision}` | compare version=0，只创建一次，不自动删除 |
| Owner | `/otryx/v2/ns/{ns}/owners/{app}/{instanceId}` | compare modRevision、单调 generation、原子全量导出 |

应用成员与版本化元数据使用独立 V2 键空间，没有双写或兼容开关。所有 Adapter 必须配置 Etcd ACL，限制同一可信命名空间下访问与写入；SHA256 只校验字节一致性，**不构成发布者身份认证**。

## 生命周期

1. Provider 绑定全部接口，发布不可变 `ApplicationManifest`；同修订版不同内容不能覆盖。
2. `ServiceMappingRepository.claim` 使用 Etcd Txn/CAS 颁发实例级 fencing generation；`publish` 原子替换此 Owner 的完整业务接口集合。
3. RPC Transport 真实监听成功后，以 ApplicationKey+instanceId 发布可路由 Provider/BOTH Member；Listener 未就绪不得先注册。
4. Consumer-only 启动时也要调用 `register`，但成员没有可路由 RPC Endpoint。
5. Client 控制面执行 ServiceKey → Mapping → Directory → 各实例 Manifest → Schema/Wire 校验；数据面不读取 Etcd。
6. 关闭先撤除可路由成员再撤销实例映射；`revoke` 保留 generation 墓碑，避免旧进程或延迟操作删除新代数据。

**重要：** Mapping 采取每 Owner 单键全量导出，以避免跨多接口的分批事务产生部分发布。查询时以同一 Etcd MVCC 修订版检查真实 Provider Member 的存在性。Lease 到期后即使 Owner 墓碑/旧集合尚在，也不会将其错误解释成活跃应用。此实现采用**受限 Owner 范围扫描**（单命名空间最多 512 条 Owner、8 MiB 原始映射数据、单接口最多 128 应用），适合受控规模集群；在更大规模下应演进索引设计并提供受控性能证据，不得直接调大限制宣称线性扩展。

## Java 编程式接入

```java
RegistryOptions options = new RegistryOptions(
        List.of("http://127.0.0.1:2379"), "production",
        Map.of("leaseTtlSeconds", "30"));
EtcdApplicationRegistryFactory factory = new EtcdApplicationRegistryFactory();
EtcdApplicationRegistry registry = (EtcdApplicationRegistry) factory.create(options);
try {
    ApplicationKey app = new ApplicationKey("production", "order-service");
    ApplicationMembershipCoordinator membership =
            new ApplicationMembershipCoordinator(
                    app, "order-1", registry,
                    registry.manifestRepository(),
                    registry.serviceMappingRepository(),
                    Duration.ofSeconds(15));
    // membership.startConsumer()：纯 Consumer 也注册真实成员
    // Provider 完成业务绑定后：prepareProvider(...) → Transport Ready → providerReady(...)
    // 注入 Core Client/Server 的 applicationControlPlane/applicationMembership。
    // 请在 finally 或应用关闭生命周期调用 membership.shutdown() 并确认终态。
} finally {
    registry.close();
}
```

这是 **Core 编程式接入**，不是 Spring Boot Starter 自动装配说明；单 Starter 与独立 JVM Quickstart 的 V2 自动化在 #137–#138 实施。

## 一致性与故障语义

- register/update：读取同一实例已有 Token 与 modRevision 后 CAS 更新；同实例不同进程 Token 必须拒绝抢占。原进程 Lease 释放后，新进程才能占用该成员键。
- unregister：仅与本地记录完整相同且 Token 匹配时 CAS 删除，迟到请求不得删除新身份/新角色。
- Lease keep-alive/TTL 探测失效后重新 Grant，再对仍归本进程持有的成员执行 CAS 恢复；失败显式终止或退避重试，不能伪造成功。
- Watch：初始 Range + `header.revision + 1` Watch。Compaction/断线显式 `onSynchronizationFailed`，Range 恢复后发布不可变快照并 `onSynchronizationSucceeded`；健康空目录不同于 IO 错误。
- Mapping owner：每次 claim 在 Etcd 单键递增 generation，publish/revoke 只允许本次 claim；空集合写墓碑，过期 token 不可复活旧集合。要严格配置写入 ACL，防止绕过 SPI 直接改写 Etcd 键。
- Manifest：单次最多 256 KiB，编解码必须拒绝未知版本、恶意超限、错误应用及错误 revision；多 revision 同时保留以支持滚动升级和回滚，不实施危险 GC。

## 容量红线与配置

| 项目 | 上限/默认 |
| --- | --- |
| 单应用成员目录 | 256 个 Member |
| 单 Registry 进程成员 | 512 |
| 订阅数量 | 128 |
| 单次成员目录累计字节 | 4 MiB |
| Owner 范围扫描 | 512 个 Owner / 8 MiB |
| 同步映射最多应用 | 128 |
| Mapping claim/CAS 重试 | 12 次 |
| 单个 Manifest | 256 KiB |
| Lease TTL | 默认 30 秒，可设置 `leaseTtlSeconds` |

超出预算、畸形 KV、权限错误、IO 故障均 fail-closed；不得返回空目录冒充正常状态。配置字段 `endpoints`、`namespace`、`providerOptions.leaseTtlSeconds` 使用已有 `RegistryOptions`。TLS、客户端证书与鉴权须按环境可信配置，不能对不可信生产 Etcd 暴露匿名写入。

## 验证

```bash
./mvnw -B -ntp -pl otryx-registry/otryx-registry-etcd -am test
./mvnw -B -ntp clean verify -Pquality
python3 scripts/check_project.py
```

真实 Etcd JUnit：`EtcdApplicationRegistryIntegrationTest` 覆盖成员角色、30 个真实 Java 接口×10 个独立 Registry client 对象的原生成员数量、双 Provider 映射保留、旧代 fencing、滚动 Manifest、真实 Lease 恢复、Watch Compaction 和 Namespace 边界。`EtcdV2CodecTest` 覆盖受限编解码与非法值。独立三节点 Leader transfer/网络隔离的应用级 Chaos 与四后端 process/chain/revision 矩阵须分别实际执行；不要将通过 JUnit 或共享 Runner 的小规模检查视为 10k 并发生产性能证据。

## English quick reference

Etcd V2 registers **application membership** for Providers and Consumers. Consumer-only members have no RPC endpoint. Immutable manifests are content-addressed; instance-scoped service mappings use Etcd CAS with monotonically increasing owner generations. All control-plane reads are asynchronous and must be bounded by the caller's deadlines. Application membership is the only runtime model; #139 acceptance also requires shared backend contracts and independent-JVM evidence. See the main English README for the current project maturity; do not claim production readiness without real Etcd E2E and CI evidence.
