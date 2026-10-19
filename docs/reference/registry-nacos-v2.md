# Nacos V2 应用级注册与元数据（Issue #134）

> Nacos Client / Server：3.2.4。本文描述当前唯一应用级 Naming/Config 控制面。Starter 与四模块 Quickstart 使用同一套应用成员与元数据；#139 的共享契约、全仓清理、独立 JVM 与 Chaos 验收必须绑定准确 Head 和合并后 main CI，见[应用级验收](application-registry-acceptance.md)。

## 1. 一份应用成员，不是每接口一份注册

使用 `ApplicationKey(namespace, applicationName) + instanceId` 作为**逻辑身份**。Nacos Naming 的 serviceName 为 `otryx.v2.application.{applicationName}`，Registry group/cluster 与业务接口 version/group 完全分离。

| 角色 | Naming Instance IP/Port | Core RPC Endpoint | 映射 |
| --- | --- | --- | --- |
| PROVIDER | 真实 RPC 对外 IP 和 Port | 真实 Endpoint | 可发布非空导出接口 |
| CONSUMER | 真实本机 IP，Port **0** | **null** | 不发布 Provider 映射 |
| BOTH | 真实 RPC 对外 IP 和 Port | 真实 Endpoint | 可发布非空导出接口 |

Nacos 3.2.4 的 `Instance.validate()` 允许端口 0；这里的 0 仅是其 Naming 身份字段，**不是**可访问服务端口。适配器从未创建 `RpcEndpoint(host, 0)`，Consumer-only 绝不进入 RPC 候选池。

Nacos Naming 的实际记录主键包含 IP/Port/Cluster。为了允许同一宿主机有两个 `port=0` 的纯 Consumer，物理 Cluster 为 `{logicalCluster}-v2-{SHA256(instanceId+incarnationToken)前24位}`；业务逻辑 Cluster 原样记录在受保护的 `otryx.v2.cluster` Metadata。查询全部物理 Cluster 后按逻辑 Cluster 过滤。这样同主机成员不会相互覆盖，也无需虚构可连接端口。真实跨进程集成测试验证此语义。

SDK 同一个 Naming Client、同一应用 Service 只应持有一个 Member；不同进程/实例必须用自己的 `ApplicationRegistryFactory.create` 创建独立 Client。角色升级先注销旧物理成员，再注册新角色；若中途失败，必须重新入册，不能保留虚假的 Provider Ready。

## 2. Metadata 与真实边界

Nacos Naming 仅表示应用成员状态：`otryx.v2.namespace`、`application`、`instance`、`role`、`manifest`、`cluster`、`token`、`attribute.*`。应用扩展属性有界，成员 Metadata 最多 8 KiB。所有真实身份、Role、Endpoint、ManifestRevision 必须解码验证；遇到恶意或跨 Namespace 的 V2 成员时 fail closed，不能偷偷将它投射为 RPC Provider。

Naming 的健康与订阅由 SDK 心跳支持；本适配器还每 5 秒执行低频 reconcile，保留同步失败事件。新鲜度及 LKG 由 Core Resolver 控制。任何 Naming Event 仅触发**有界控制线程**刷新；热路径绝不调用 SDK。

## 3. Nacos Config Manifest

Manifest 存入 `otryx.v2.manifest.{sha256(appName)}.{revisionHex}`，内容为 Canonical Binary 的 `otryx-v2-manifest:` + Base64 文本。读取后校验原始字节、App Namespace、ApplicationName 和完整 SHA256 Revision。配置已存在时，只允许写入**完全相同**内容。首次发布允许使用内容寻址键的创建接口，但必须读取并严格比对完整 canonical bytes；它不承担 Mapping 的 CAS 语义。不同 Revision 可并存，不自动 GC，以保护滚动升级与回滚。

Nacos 的发布 ACK 与查询缓存可见性不同步。适配器只发布一次，然后在既有控制面线程中使用 **3 秒总确认预算**；只有缺失结果会按最多 25ms 的间隔继续查询，每次 SDK 查询受剩余预算约束。确认值缺少权威 MD5、内容不一致、SDK 直接抛出的异常或线程中断立即失败，截止后到达的值也不能算成功。SDK 自身若将网络故障转换为无 Snapshot 的缺失结果，适配器只能在该预算内继续确认；不能由缺失结果推断后端健康。这个预算从发布 ACK 返回后开始，不包括此前的读取和发布耗时。确认超时不表示远端写入已回滚；调用方可以用完全相同的 Manifest 幂等重试，已存在的相同内容无需重新发布。

Nacos Config 的单条限制小于 Core 256 KiB Manifest：本适配器对每条 Config 设置 **90 KiB** 严格编码后上限（含 Base64 和前缀），容量不足即失败，不拆分后产生部分可见对象，也不静默截断。

## 4. Nacos Config ServiceMapping：MD5-CAS、Fencing、分片

**部署初始化是强制步骤。** Nacos 3.2.4 服务端在 `casMd5=null` 时执行普通 insert/update，不是可靠的 create-if-absent CAS。因此发布任何 Mapping 之前，必须在无业务写入的独占维护窗口调用：

```java
RegistryOptions options = RegistryOptions.trustedDevelopment(
        List.of("127.0.0.1:8848"), "public", Map.of("nacosGroup", "OTRYX_RPC"));
NacosV2MetadataBootstrap.initialize(options);
```

它仅初始化缺失的空分片、核验现有内容且不清空已有有效数据。**禁止与运行期写入并发执行。** 运行期任何缺失分片都直接 fail closed，绝不使用 `publishConfigCas(..., null)` 或 `publishConfig` 回退写入。部署管道应在全部 Provider/Consumer 启动之前完成此步骤。

Quickstart 的隔离 Nacos 环境可使用独立命令入口；先编译可执行 Provider JAR，确认 `java` 和 GNU coreutils 的 `timeout` 在 `PATH` 中，再执行：

```bash
bash scripts/bootstrap_quickstart_nacos.sh \
  otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar \
  127.0.0.1:8848 public OTRYX_RPC
```

脚本通过 Spring Boot `PropertiesLauncher` 在独立 JVM 调用 `NacosV2MetadataBootstrap.main`，设置 120 秒执行预算；超时先发出终止信号，5 秒宽限后仍未退出则强制结束。**Java CLI 必须恰好提供四个参数：endpoint、namespace、group、`TRUSTED_DEVELOPMENT`。** 上面的 Bash 包装器仍接收 JAR 与三个目标参数，由脚本固定追加第四个安全意图；不是由 Factory 推断开发模式。旧的三参 Java CLI 会拒绝，第四参为 `UNSPECIFIED`、`NETWORK_MTLS` 或其他值也会在创建 SDK 前以 `UNSUPPORTED_PROTECTION` 拒绝。

脚本中的 namespace/group 必须与应用配置一致。该 CLI 不接收秘密参数；需要 SDK 用户密码的开发初始化应使用下文 typed `RegistryOptions` 调用 `initialize`，仍必须显式选择 `TRUSTED_DEVELOPMENT`。双进程、三进程和滚动验证脚本为每轮生成独立 group，并在任何应用启动前初始化；普通应用启动不执行初始化。

Config 中固定 8 个分片 `otryx.v2.mapping.s0` ~ `s7`（同一 Nacos namespace 与 Registry group）。一个 Owner 依据 SHA256(应用名、实例 ID) 分配至唯一分片。分片包含按身份排序的 Owner、持久化单调 Generation、随机进程 Token、此 Owner 的**完整 ServiceKey 集合**。

- `claim`：读取权威 Config 内容及真实 MD5，MD5-CAS 更新 Owner generation+1，原子清空旧导出集合；
- `publish`：只有当前本进程持有的 generation/token 才能全量替换服务集合，旧代必须拒绝；
- `revoke`：只把当前 Owner 的发布集合变为空，保留 generation 墓碑，不删除同应用其他实例的接口；
- `lookup`：仅遍历有界 8 分片，组合候选 Application 集合。Nacos **没有多 Config 全局 MVCC**：映射快照不是健康 Provider 列表。Core 必须进一步验证 Naming Directory、各实例 Manifest、Schema Fingerprint、真实可路由 Endpoint 和本地新鲜度预算。

严格要求 Nacos 3.2.4 `getConfigWithResult` 返回权威 MD5。没有 MD5 的历史 Snapshot、连接故障、权限错误或未初始化分片不能作为有效 CAS 基础，也不能伪装成健康空映射。`publishConfigCas` 返回 false 只会被认为 CAS 冲突或拒绝，最多 12 次重读重试；成功 CAS 还会执行最多 32 次、每次间隔 125ms 的权威 Config 可见性复核：如果仍看到更旧代数，可以有界等待；若发现更高代数或相同代数不同 token，立即拒绝；超时必须失败，不能报告虚假成功。不能以无条件 `publishConfig` 降级写入。

由于 Nacos 对 Config 大小及跨分片原子性有限制，这个实现限定每分片最多 128 Owner、每 Owner 最多 512 个接口、每个 Config 内容最多 90 KiB、每次映射最多 128 应用，适合受控规模部署。高规模索引、分片扩展、跨分片事务和存储 GC 必须另行设计；不能简单修改常量宣布已支持无限扩展。

## 5. 配置和编程式接入

当前 Nacos Adapter 只支持显式 `TRUSTED_DEVELOPMENT`，凭据为 NONE 或 USER_PASSWORD。Naming、Config 和登录 HTTP/gRPC 的完整 TLS 保护尚不受当前实现支持，因此 **NETWORK_MTLS、任意 TLS 材料、ACL_TOKEN 与 CLIENT_CERTIFICATE 均在 SDK 创建前拒绝**，`initialize` 也遵守同一边界。不能只把 endpoint 改为 HTTPS 或填写 SDK 字符串开关来宣称保护生效。旧三参/四参 `RegistryOptions` 及 `fromCsv` 默认 `UNSPECIFIED`，不会自动进入开发模式。

无凭据的隔离开发配置如下；endpoint 使用实际 `host:port`，不是含用户名、密码或路径的 URL：

```java
RegistryOptions options = RegistryOptions.trustedDevelopment(
        List.of("127.0.0.1:8848"), "public",
        Map.of(
                "nacosGroup", "OTRYX_RPC",
                "nacosCluster", "DEFAULT",
                "nacosMemberHost", "192.168.1.20"));
try (NacosApplicationRegistry registry = (NacosApplicationRegistry)
        new NacosApplicationRegistryFactory().create(options)) {
    ApplicationKey app = new ApplicationKey("public", "orders");
    ApplicationMembershipCoordinator membership =
            new ApplicationMembershipCoordinator(
                    app, "orders-1",
                    registry.registrar().orElseThrow(),
                    registry.manifestRepository(),
                    registry.serviceMappingRepository(),
                    Duration.ofSeconds(15));
    // 纯 Consumer：membership.startConsumer()
    // Provider：bind services → prepareProvider(...) → Transport Ready → providerReady(...)
    // 进程关闭时先等待 membership.shutdown()，再关闭 Registry。
}
```

有用户密码的开发环境通过 typed `security` 配置，`nacosUsername` 为调用方持有的用户名，`nacosPassword` 为凭据来源提供的 `char[]`：

```java
RegistryOptions authenticatedDevelopment = new RegistryOptions(
        List.of("127.0.0.1:8848"), "public", Map.of("nacosGroup", "OTRYX_RPC"),
        RpcObserver.noop(), RpcSecurityProfile.TRUSTED_DEVELOPMENT,
        new RegistrySecurityOptions(
                Optional.empty(), RegistryCredentials.userPassword(nacosUsername, nacosPassword)),
        Optional.empty());
```

`providerOptions` 仅接受 `nacosGroup / nacosCluster / nacosMemberHost`，历史 Map 用户密码、安全开关及未知键会被拒绝。Nacos 不接受 `metadataSecurity`。安全 DTO 防御复制密码并隐藏 `toString()`；提供密码不改变该客户端的开发保护等级。

使用 SDK 的真实 NamespaceId，默认 `public`。Naming/Config 权限必须按实际 namespace/group 约束；SHA256 和 metadata token **并非安全认证凭据**。需要当前受支持的受认证 TLS Registry 链时应选择满足条件的后端，参见[安全边界](../security.md)。Consumer 的 `nacosMemberHost` 必须是真实本机 IP，未配置时使用 `InetAddress.getLocalHost().getHostAddress()`，不可填全网通配监听地址。

## 6. 集成验收

```bash
NACOS_TEST_ENDPOINT=127.0.0.1:8848 ./mvnw -B -ntp -pl otryx-registry/otryx-registry-nacos -am test
./mvnw -B -ntp clean verify -Pquality
```

- `NacosV2ModelTest`：端点、Token/Cluster 隔离、编码、畸形数据和容量边界。
- `NacosApplicationManifestRepositoryTest`：发布后的短暂不可见性、冲突、缺失 MD5、SDK 失败、确认总预算、幂等重试、并发同 Revision 及关闭中断。
- `NacosApplicationRegistryIntegrationTest`：真实 Nacos 上多个 Consumer 共用本机 IP、Provider 30 接口对应一份应用成员、角色升级/降级、两个实例共享服务映射、旧 Generation 防写、多 Revision 发布以及订阅转为空。
- `NacosApplicationRegistryChaosTest`：独立 Provider/Consumer 客户端在 Nacos 容器 pause/unpause 后恢复并完成 Config 映射校验（`-Pnacos-chaos`）。
- 变更后的 PR Head 需通过 Maven、Checkstyle、Java/Javadoc 门禁、Doc Previews、Chaos 和 CI；合并后核对 main CI。

## English overview

Both Factory creation and bootstrap currently require explicit `TRUSTED_DEVELOPMENT` with NONE or typed USER_PASSWORD credentials. `UNSPECIFIED`, `NETWORK_MTLS`, any TLS material and unsupported credentials fail before SDK creation; the full Naming/Config/login protection chain is not supported. The old `RegistryOptions` constructors no longer imply development mode, and security keys in `providerOptions` are rejected. Use `RegistryOptions.trustedDevelopment` for isolated credential-free fixtures, or the seven-argument typed configuration for development username/password authentication.

Initialize all eight Config mapping shards offline using `NacosV2MetadataBootstrap.initialize` before starting any application writer. Nacos 3.2.4 does not provide compare-and-swap when `casMd5=null`, so runtime writes refuse uninitialized mapping shards. Nacos V2 publishes one **application-level ephemeral Naming member** for each Provider/Consumer/BOTH instance. A consumer-only member uses the real host IP and a **zero Naming port**, never an RPC endpoint; a token-derived physical cluster distinguishes multiple local consumers. Immutable manifests use content-addressed Config keys and strict canonical-byte verification; instance-owned, generation-fenced service mappings use server MD5 compare-and-swap. Strict limits apply to each Config record (90 KiB) and the eight mapping shards. Nacos has no cross-document MVCC snapshot, so Core must always verify Naming liveness, Manifest and Schema before routing.

A Manifest publish ACK can precede query-cache visibility. The adapter publishes once and confirms within a **three-second total budget after the ACK**, polling only missing results at intervals of up to 25ms on its existing control executor. Each SDK query receives the remaining budget. Missing authoritative MD5, conflicting content, exceptions propagated by the SDK and interruption fail immediately; a response arriving after the deadline cannot succeed. If the SDK itself converts a network failure with no local snapshot into a missing result, confirmation remains limited to this budget; missing data does not establish backend health. The confirmation budget excludes the preceding read and publish. A confirmation timeout does not roll back the remote write: retrying the identical Manifest is idempotent, and an already visible identical value needs no further publish. Direct regression tests cover these boundaries, concurrent publishers and close interruption.

The single Starter, four-module Quickstart, Core and benchmark runtimes use application registration exclusively. The Java bootstrap CLI requires exactly four arguments: endpoint, namespace, group and `TRUSTED_DEVELOPMENT`. The Bash wrapper takes the JAR plus the three target values and appends that explicit profile. Missing or non-development profiles are rejected before SDK creation. It runs before any application writer; each process test uses its own group. The shared contract uses 30 real Java interfaces and ten independent Registry client objects in one test JVM. The separate four-backend process/chain/revision matrix exercises independent JVMs. Current #139 execution evidence is tracked in the [acceptance guide](application-registry-acceptance.md); no unexecuted server gate is reported as passed.
