# Consul V2：应用级成员、KV Manifest/Mapping 与 Session GC（#135）

> 本页描述 Consul 1.22.7 服务端验证的 V2 编程式 Adapter。正式 Starter/Quickstart 接线归 #137/#138，旧 ConsulRegistryBackend / HttpRegistryIdentity(ServiceKey) 的彻底删除归 #139。不能把本适配器独立存在误称为全链路 V2 已上线。

## 1. 一份应用成员，多份业务接口

| OTRYX | Consul 原生对象 | 要求 |
| --- | --- | --- |
| ApplicationKey(namespace, app) | otryx-v2- + sha256(namespace, app) 的 Agent Service Name | 不使用 ServiceKey |
| application + instanceId + incarnation | 一个 Agent Service ID 和 TTL Check | 一进程一个成员，可独立恢复 |
| PROVIDER / BOTH | 真实 advertised host 和 RPC Port | Meta.role、ManifestRevision、Address/Port 均需校验 |
| CONSUMER-only | **Address=""，Port=0** | 原生 Consul 可承载的无 RPC Endpoint 成员，绝不路由 |
| ApplicationManifest | KV manifests/sha256(app)/revision | 内容不可变、多版本并存 |
| ServiceMapping | KV owners/sN/ownerDigest | 单 Owner 完整 ServiceKey 集合及持久 Generation CAS |
| 活性标记 | Session 持有的 KV live/sN/ownerDigest/generation | Session 过期自动 GC，不能删除 Fencing 墓碑 |

所有 Consul 原生对象均不再以 ServiceKey 注册。Service Name/ID 使用 SHA256 摘要隔离逻辑 Namespace，原始 Namespace/Application/instanceId/Role/Manifest 等在 Meta 双向验证。**SHA256 不是 ACL 身份认证。** 同宿主机的多个 Consumer 通过独立的 instanceId+进程 incarnation 生成不同 Service ID，不占用 RPC 端口。

## 2. TTL 成员、轮询订阅和故障

Service 注册使用 PUT /v1/agent/service/register，新 Check 初始 critical，随后 PUT /v1/agent/check/pass/service:<serviceId> 标记 passing。默认服务 TTL 30s，续约间隔 10s，使用单一 Agent endpoint；服务检查丢失返回 404 时重新注册。Health GET /v1/health/service/<app>?passing=true 只读取 passing 成员；任何异常不得伪装成健康空目录。

每个订阅使用有界后台控制线程轮询（默认 1s），只在视图变化时投递完整快照，恢复后即使视图未变也更新同步健康。不声明全局 Revision。close 停止续约、注销本实例并释放连接。Consumer-only 必须保持空 Service Address 和 0 端口，从不创建 RpcEndpoint。

## 3. Consul CAS + Session Fencing

一个实例的 Owner Key 保存永久的 Generation、随机 Token 和完整的导出 ServiceKey 集合：

1. claim：无 Key 时使用原生 cas=0 原子创建；有 Key 时读取 ModifyIndex，通过 CAS 递增 generation 并清空旧集合。
2. 随后创建 Behavior=delete 的 TTL Session，使用 acquire 将 live Key 绑定本代 generation/token。默认 Session TTL 为 30s。
3. publish：在正确本地 claim、远端 Owner Generation/Token 和 Session live Key 都匹配时用 ModifyIndex CAS 整体替换接口集合。
4. revoke：仅清空当前 generation 的接口，保留持久 KV 墓碑，并 destroy 该代 Session。永不删除其他 Provider 的映射。
5. lookup：有界枚举 8 个 Owner 分片和 Session 活性键分片，同时满足当前代数、Token、有效 Session Marker、passing Provider 身份才进入候选应用；Consumer-only 与已失效 TTL / Session 不可被路由。
6. Session 续约失败时先有界重试；Session 被 Consul GC 后本代 Owner **不得复活**，只能重新 claim → publish 开新一代。Manifest 不自动 GC，防止破坏灰度和回滚引用。

Consul KV 原生 cas=0 支持跨进程安全首次创建，不需要 Nacos 3.2.4 的独占离线初始化。每份 Mapping Owner 文档最多 32 KiB；8 个分片各最多 64 Owner，共 512 Owner；单次扇出最多 128 应用。每个 Manifest KV JSON 文档最多 400 KiB（Canonical 原始最多 256 KiB）。超出上限必须拒绝而非截断。Owner 扫描属于**冷控制面**，不进入 RPC 热路径；更大规模需要独立倒排索引和真实容量证据。

## 4. ACL、单 Agent 与程序化配置

生产 Consul Token 应具有本应用的 service:read/write、对应前缀 key:read/write 和 Session 创建/续约需要的 session:write ACL，并根据部署启用 HTTPS/TLS。不能用开发模式的匿名权限作为生产默认。逻辑 Namespace 与 Consul Enterprise Namespace、Datacenter 相互独立。

~~~java
RegistryOptions options = new RegistryOptions(
        List.of("https://consul-agent.local:8501"), "production",
        Map.of(
                "consulToken", System.getenv("CONSUL_HTTP_TOKEN"),
                "consulDatacenter", "dc1",
                "consulEnterpriseNamespace", "",
                "consulTtlSeconds", "30",
                "consulHeartbeatSeconds", "10",
                "consulSessionTtlSeconds", "30",
                "consulPollIntervalMillis", "1000",
                "consulRequestTimeoutMillis", "3000"));
try (ConsulApplicationRegistry registry =
        (ConsulApplicationRegistry) new ConsulApplicationRegistryFactory().create(options)) {
    // ApplicationMembershipCoordinator 执行
    // Manifest publish → Mapping claim/publish → Transport Ready → Application register
    // shutdown 时先撤应用成员，再 revoke Mapping，最后 registry.close()
}
~~~

真实部署需配置 Secret 注入、最小权限和单个本地 Agent；跨 Agent 轮换注册会让 TTL Check 丢失。身份 Token 与任意 Metadata 都不得输出到日志/Trace。Session 丢失后由上层应用生命周期重新建立 Owner，不能直接对旧 generation 重试发布。

## 5. 真实 Consul 容器验证

CI Workflow: .github/workflows/consul-v2.yml，固定使用 hashicorp/consul:1.22.7。

~~~bash
CONSUL_TEST_ENDPOINT=http://127.0.0.1:8500 \
  mvn -B -ntp -pl otryx-registry/otryx-registry-consul -am test

CONSUL_TEST_ENDPOINT=http://127.0.0.1:8500 \
CONSUL_CHAOS_CONTAINER=otryx-consul-v2-ci \
  mvn -B -ntp -pl otryx-registry/otryx-registry-consul -am test -Pconsul-chaos
~~~

- ConsulV2ModelTest：SPI、Consumer Port0、Role/Endpoint 防伪、数据长度和实例元数据边界。
- ConsulApplicationRegistryIntegrationTest：真实不同进程模拟的 Provider/Consumer/BOTH，一实例 30 接口单注册、并发 Owner、旧代抢占、双 Manifest Revision、远端订阅和注销。
- ConsulApplicationRegistryChaosTest：容器 pause/unpause、服务 TTL 停跳不再 Passing、Session 超时自动删除短寿命活性 KV。
- 旧 ConsulRegistryTest 的 Fake Agent 只证明迁移前接口路径的历史契约，不可代替 V2 E2E。

PR 需要精确 Head 全量 CI、独立 Consul E2E/Chaos、Java Style、Docs 和 Release/Rolling 全绿后才可合并 main，随后核验 main CI。JDK21、1.0.0-SNAPSHOT、Wire v1 不变，禁止发布或改权限。

## English summary

Consul V2 publishes one native Agent Service + TTL Check **per application instance**. Consumer-only membership uses Address="" and Port=0 without an RPC endpoint. Application manifests are immutable CAS=0 KV entries. Mapping relies on durable per-owner ModifyIndex CAS and monotonically increasing Generation fencing; ephemeral Session-held KV markers (Behavior=delete) expire automatically on lost liveness. Discovery intersects current generation, valid Session marker, and passing Provider membership. Cold control-plane scans are explicitly capped. Session loss requires a new claim and republish; it must never revive stale generations. Legacy ServiceKey-based registration stays isolated until #139; starter/quickstart V2 wiring is tracked in #137/#138.

Official references: [Agent](https://developer.hashicorp.com/consul/api-docs/agent/service), [KV](https://developer.hashicorp.com/consul/api-docs/kv), [Session](https://developer.hashicorp.com/consul/api-docs/session).
