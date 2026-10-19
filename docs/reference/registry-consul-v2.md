# Consul V2：应用级注册与 KV 元数据

[English](registry-consul-v2.en-US.md)

> [Issue #135](https://github.com/Ryan-Guizhou/otryx-rpc/issues/135) · OTRYX RPC 1.0.0-SNAPSHOT · 当前应用级后端。本文描述当前实现；执行证据须绑定准确源码 SHA，见[验收边界](application-registry-acceptance.md)。

## 一、定位与行为

Consul V2 通过 **Consul Session TTL + KV Acquire** 原子声明实例身份，避免同一 `instanceId` 被多个进程同时占用。成员 Session 失效后，临时 Claim 键由 Consul 删除，持久 Mapping generation 墓碑保留。

`ConsulApplicationRegistry` 实现 `ApplicationRegistry + ApplicationRegistrar`。Provider、Consumer 和 BOTH 都按 `ApplicationKey(namespace, applicationName)` 注册应用级 Service，每个进程拥有独立 Service ID；导出 30 个接口不会产生 30 份成员。

- Provider / BOTH：注册真实 RPC Address/Port，TTL Check passing 后进入应用目录；进入调用池还需 Core 的 Manifest、Schema、Wire 和服务绑定校验。
- Consumer-only：注册真实应用成员和 TTL Check，**不配置 RPC Address/Port**，原生端口缺省为 0，不能成为 Provider。
- Mapping：按实例 Owner 保存完整导出集合；CAS ModifyIndex、递增 generation 和进程 token 拒绝旧代发布或撤销。
- Manifest：按应用及 SHA256 revision 寻址；完整 canonical bytes 通过 `?cas=0` 仅创建，同一 revision 不能被不同内容覆盖。
- Agent/Health/KV 由有界控制线程访问，RPC 调用热路径只读取本地验证后的 Provider 快照。

## 二、显式安全配置

显式引入 `otryx-registry-consul`，通过 `ApplicationRegistryFactory` SPI 选择 `consul`；默认 Starter 不包含该依赖。旧的三参/四参 `RegistryOptions` 构造及 `fromCsv` 保留调用形状，但其 profile 为 `UNSPECIFIED`，Factory 会拒绝启动。

`NETWORK_MTLS` 在本 Registry 链上要求 **HTTPS、显式 CA 与 ACL_TOKEN**。以下 `httpsAgentEndpoint` 是一个实际 HTTPS Agent URL，`caFile` 为受控 CA 文件的 `Path`，`aclToken` 为从凭据来源取得的 `char[]`。`RpcSecurityProfile` 位于 `com.peachsoft.otryx.api`，`RpcObserver` 位于 `com.peachsoft.otryx.observability`，其余安全 DTO 位于 `com.peachsoft.otryx.registry`。

```java
RegistrySecurityOptions security = new RegistrySecurityOptions(
    Optional.of(new RegistryTlsOptions(caFile, Optional.empty())),
    RegistryCredentials.aclToken(aclToken));

RegistryOptions options = new RegistryOptions(
    List.of(httpsAgentEndpoint),
    "production",
    Map.of(
        "consulTtlSeconds", "30",
        "consulHeartbeatSeconds", "10",
        "consulPollIntervalMillis", "1000",
        "consulRequestTimeoutMillis", "3000"),
    RpcObserver.noop(),
    RpcSecurityProfile.NETWORK_MTLS,
    security,
    Optional.empty());

ConsulApplicationRegistry registry = (ConsulApplicationRegistry)
    ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
        .getExtension("consul").create(options);
ApplicationManifestRepository manifests = registry.manifestRepository();
ServiceMappingRepository mappings = registry.serviceMappingRepository();
```

三个 Registry 通道 `MEMBERSHIP / MANIFEST / MAPPING` 使用同一实际 HTTP 客户端和 `security`，Consul 不接受 `metadataSecurity`。ACL Token 安装为 `X-Consul-Token`；TLS 客户端验证 CA 和服务端主机名，不跟随重定向。Factory 在安装实际 TLS/凭据后建立保护描述，Core 再核对完整来源；单独设置 profile 不代表 RPC 传输的 mTLS 和方法授权已经完成，见[安全边界](../security.md)。

`RegistryTlsOptions.clientIdentity` 可额外提供 `RegistryClientKeyMaterial(certificateChain, privateKey, privateKeyPassword)`，但不能替代 ACL Token；当前 Factory 不接受仅 `CLIENT_CERTIFICATE` 的凭据模式。密码、Token 和私钥密码数组在输入/读取时复制，安全 DTO 的 `toString()` 隐藏材料值；这不承诺擦除第三方客户端已持有的字符串。

对明确隔离的明文开发环境，使用：

```java
RegistryOptions development = RegistryOptions.trustedDevelopment(
    List.of("http://127.0.0.1:8500"), "development", Map.of());
```

`trustedDevelopment` 不配置 TLS 或凭据。需要开发环境 ACL 时仍用七参构造、显式 `TRUSTED_DEVELOPMENT` 和 typed `security`。凭据/TLS 不放入 `providerOptions`；历史 `consulToken` 及其他未知键会被拒绝。

## 三、非安全选项与实际路径

| `providerOptions` | 默认值 | 范围/用途 |
| --- | --- | --- |
| `consulTtlSeconds` | 30 | 10–3600 秒 |
| `consulHeartbeatSeconds` | 10 | 至少 1 秒，严格小于 TTL |
| `consulPollIntervalMillis` | 1000 | 200–60000 毫秒 |
| `consulRequestTimeoutMillis` | 3000 | 100–60000 毫秒 |
| `consulEnterpriseNamespace` | 空 | 可选 Consul Enterprise Namespace |

**必须恰好一个本地 Agent endpoint**；TTL Check 不能向其他 Agent 漫游续期。OTRYX 逻辑命名空间来自 `RegistryOptions.namespace`，与 `consulEnterpriseNamespace` 分开。

ACL 应按客户端实际调用的 Service、Session、KV 路径授权。设 `b64(x)` 为 UTF-8 的 URL-safe Base64、无填充，`R = otryx/v2/ns/{b64(namespace)}/`，注意 R 没有前导斜线：

| 对象 | 实际 KV 键 |
| --- | --- |
| Manifest | `R + apps/{b64(application)}/manifests/{b64(revision)}` |
| Mapping Owner | `R + owners/{b64(application)}/{b64(instanceId)}` |
| 临时成员 Claim | `R + claims/{b64(application)}/{b64(instanceId)}` |

原生 ServiceName 为 `otryx-app-` 加 namespace、NUL 分隔符、application 的 SHA256 前 12 字节十六进制；Service ID 再追加 instanceId 的 SHA256 前 12 字节十六进制。Owner 查询扫描 `R + owners/`；部署策略需要覆盖实际读取范围，不能只按文档中的逻辑应用名猜测 KV 或 Service 名。只读观察不需要发布权限；参与 Consumer 成员注册的进程仍会写自身成员、Claim 和 Session。元数据中的 role/token 不是后端授权凭据。

## 四、容量与故障

| 边界 | 限制 |
| --- | --- |
| 单个 KV value | 256 KiB，不截断 |
| 原生 Service Meta | 最多 64 项；key/value 按 UTF-8 字节限制为 128/512 |
| Owner 扫描 | 最多 512 个 KV，累计 8 MiB |
| 每 Owner 的 ServiceKey / 每次候选应用 | 512 / 128 |
| 进程成员 / 订阅 | 256 / 128 |
| 控制线程 / 队列 | 2–4 / 256 |
| 单次原始 HTTP 响应 / HTTP 在途请求 | 12 MiB / 64 |

HTTP 总预算包括连接、响应体和 JSON 解析；原始字节在聚合前计数，错误状态与 chunked 响应同样受限。严格 UTF-8、JSON 深度/标量限制以及重复字段检查在信任元数据前执行；写请求不会向另一个 endpoint 自动重放。

合法的 non-passing 或不可路由成员可被健康过滤。声称为 V2 的错误归属、损坏角色/字段、畸形内容，以及 TLS、认证、授权或响应超限会形成稳定 `RegistrySecurityException.reason()`，不附原始 cause 或服务端正文，也不能成为健康空快照。普通网络失败沿用 Core 的有界 LKG；安全失败立即使当前 Resolver 的旧安全 epoch 失效，另一条路由不会因定时器尚未运行而继续使用旧快照。

默认每 10 秒续约 TTL Check 与 Session，丢失 Agent Check 时重新注册。`close()` 停止调度并以最多三秒尝试注销；不可达时由 TTL 最终使成员失效，不删除其他进程成员。Session 结束删除临时 Claim，Owner revoke 保留 generation 墓碑，旧 Manifest 不自动 GC。持久数据清理必须另有权威引用、保护窗口和原子 fencing；不声明全局 revision、跨数据中心事务或无条件强一致。

## 五、验证与证据范围

以下为**明文开发夹具**，可验证 Registry 合同，不证明 HTTPS 或窄权限部署：

```bash
docker run -d --name otryx-consul-v2 -p 8500:8500 \
  hashicorp/consul:1.20 agent -dev -client=0.0.0.0
mvn -B -ntp -pl otryx-registry/otryx-registry-consul -am \
  -Dconsul.test.endpoint=http://127.0.0.1:8500 test
```

[Consul workflow](../../.github/workflows/consul-v2.yml) 的 `consul-v2` job 使用真实 Consul，覆盖角色、不可变 Manifest、并发 CAS/fencing、TTL/Session 恢复与实例互斥；独立步骤执行 Agent pause/recovery。`acl-enforcement` job 以 ACL default-deny 启动隔离服务并生成临时管理 Token，验证授权成功与匿名写入拒绝；它不证明每角色、每实例的最小权限策略，也不证明 Registry TLS。凭据不写入源码或 Maven 参数。

共享合同包含 30 个真实 Java 接口 × 10 个独立 Registry client 对象，位于同一测试 JVM。原生成员 `0→10→0`、SPI 方法入口、订阅创建、快照/健康通知及收敛时间分别计量，不等于全部 HTTP/keepalive 流量。条件式真实服务测试的执行、失败和跳过必须逐项记录；四后端独立业务 JVM 的 process/chain/revision 由 [application-registry-e2e workflow](../../.github/workflows/application-registry-e2e.yml) 执行，见[验收边界](application-registry-acceptance.md)。

实现依据：[Factory](../../otryx-registry/otryx-registry-consul/src/main/java/com/peachsoft/otryx/registry/consul/ConsulApplicationRegistryFactory.java)、[身份与键空间](../../otryx-registry/otryx-registry-consul/src/main/java/com/peachsoft/otryx/registry/consul/ConsulV2Identity.java)、[KV 客户端](../../otryx-registry/otryx-registry-consul/src/main/java/com/peachsoft/otryx/registry/consul/ConsulV2Client.java)、[HTTP 边界](../../otryx-registry/otryx-registry-http/src/main/java/com/peachsoft/otryx/registry/http/HttpRegistryClient.java)、[Resolver](../../otryx-core/src/main/java/com/peachsoft/otryx/registry/ApplicationServiceResolver.java)。
