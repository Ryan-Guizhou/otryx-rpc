# Consul V2：应用级注册与 KV 元数据

> [Issue #135](https://github.com/Ryan-Guizhou/otryx-rpc/issues/135) · OTRYX RPC 1.0.0-SNAPSHOT · **开发中，尚未通过最终 CI/E2E 验收**。

## 一、定位与行为

Consul V2 通过 **Consul Session TTL + KV Acquire** 原子声明实例身份，避免同一 `instanceId` 被多个进程同时占用。成员 Session 失效后由 Consul 自动删除临时 Claim 键；Mapping Generation 墓碑保持不变。

Consul V2 在独立的 `ConsulApplicationRegistry` 中实现 `ApplicationRegistry + ApplicationRegistrar`。
Provider、Consumer 和 BOTH 都用 `ApplicationKey(namespace, applicationName)` 注册一个逻辑 Consul Service，每个真实进程实例拥有独立 ID。暴露 30 个接口不会额外创建 30 个成员，接口属于 Manifest/Mapping，不是 ServiceName。

- Provider / BOTH：Agent Service 注册真实、可路由的 RPC Address/Port，并在 TTL Check passing 后进入应用目录；后续 Provider Pool 仍需 Manifest、Schema、Wire 校验。
- Consumer-only：Agent Service **不配置** RPC Address/Port；端口由 Consul 原生缺省为 0。保留实际应用成员和 TTL Check，永远不得变成 Provider。
- 接口映射：完整 ServiceKey → 一组 ApplicationKey；KV 以实例 Owner 保存完整导出集合，CAS + generation + 进程 token 拒绝旧代写入。
- Manifest：`<namespace>/<application>/<sha256:revision>` 内容寻址；原始 Canonical Bytes 用 `?cas=0` 仅创建。一个 revision 与一个内容严格对应，不能覆盖。
- 分离控制/数据面：Agent/Health/KV 仅由有界控制面线程访问；调用热路径仅读本地验证后的 Provider 快照。

## 二、依赖及选项

通过 SPI `ApplicationRegistryFactory` 选择 `consul`。必须显式引入 `otryx-registry-consul`（Starter 默认不引入），配置如下：

```java
RegistryOptions options = new RegistryOptions(
    List.of("http://127.0.0.1:8500"),
    "production",
    Map.of(
        "consulToken", System.getenv().getOrDefault("CONSUL_HTTP_TOKEN", ""),
        "consulTtlSeconds", "30",
        "consulHeartbeatSeconds", "10",
        "consulPollIntervalMillis", "1000",
        "consulRequestTimeoutMillis", "3000"));
ConsulApplicationRegistry registry = (ConsulApplicationRegistry)
    ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
        .getExtension("consul").create(options);
ApplicationManifestRepository manifests = registry.manifestRepository();
ServiceMappingRepository mappings = registry.serviceMappingRepository();
```

`providerOptions` 支持 `consulEnterpriseNamespace`（Enterprise 可选）；逻辑 namespace 仍由 `RegistryOptions.namespace` 提供。**Consul Agent 必须恰好一个 endpoint**，本地 Agent TTL Check 不能向其他 Agent 漫游续期。ACL Token 放在 `X-Consul-Token`，需最小 `service:write`、`service:read`、`key:read`、`key:write`、`session:write`，认证和授权不能由应用 Metadata 替代。

## 三、一致性、容量与故障

- Consul KV 默认单 value 最大约 512 KiB，适配器采取更保守的 **256 KiB** 上限。原生 Service Meta 的字段名和值采用 **UTF-8 字节数** 限制（最多 128/512 字节），不以 Java 字符长度代替。Manifest、Owner 超限直接拒绝，不截断。
- Owner Scan：最多 512 个 KV、累计 8 MiB；每 Owner 最多 512 个 ServiceKey；每次 Mapping 最多 128 个候选应用。
- 进程成员与订阅最多 256 / 128；控制线程 2–4、队列 256；请求默认超时 3 秒。
- 成员 Check 和实例 Session 默认 TTL 30 秒，每 10 秒续约；丢失 Agent Check 时重新注册，异常按健康失败上报而非悄悄发布空目录。
- Session 结束时 Consul 自动删除会话拥有的临时成员 Claim；Owner `revoke` 清空导出，**保留 Generation 墓碑**。不自动删除 Manifest 或墓碑，以免滚动/回滚与迟到请求导致 ABA；生产清理必须具备权威引用验证、保护窗口和原子 Fencing。本限制意味着 KV 容量需要定期审计。
- Consul Health/Service Meta 是控制面发现信息，**不等于服务身份认证**。ACL 与 mTLS、方法授权必须单独启用。没有证明的强一致、跨数据中心事务或全局 Revision 不应被声明为已实现。
- `close()` 停止调度与最多三秒的实例注销；故障时由服务 TTL 最终使成员失效，不能删除其他进程的成员和共享 Mapping。

## 四、测试

```bash
docker run -d --name otryx-consul-v2 -p 8500:8500 \
  hashicorp/consul:1.20 agent -dev -client=0.0.0.0
mvn -B -ntp -pl otryx-registry/otryx-registry-consul -am \
  -Dconsul.test.endpoint=http://127.0.0.1:8500 test
```

GitHub Actions `.github/workflows/consul-v2.yml` 的 `consul-v2` 任务使用真实 Consul，`acl-enforcement` 任务单独以 ACL default-deny 启动、仅在隔离容器内生成临时管理 Token，分别验证授权请求与未授权请求失败关闭；Token 不写入代码或 Maven 启动参数。真实后端任务执行角色注册、并发 KV CAS、Manifest 不可变、TTL Check 和 Session 失效重注册、跨进程成员互斥、30 接口×10 实例矩阵。原有 Mock Agent 测试仅验证旧接口级路径，**不能用来替代真实 V2 E2E**。

需要额外验证并量化：Agent pause/restart、ACL 拒绝、Catalog 收敛、超额 Metadata、跨进程 Owner 竞态、回滚与安全 GC。#137–#139 才负责 Starter/Quickstart 应用级生产接线与旧 SPI 最终移除，不能凭本 Adapter 的存在宣称全局迁移完成。
