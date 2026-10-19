# 迁移到 OTRYX RPC 应用级注册

> 当前源码为 `1.0.0-SNAPSHOT` / JDK 21 / Spring Boot 3.5.4 / Wire v1，尚未正式发布。#139 的源码、真实后端和 CI 验收状态以[断点记录](engineering/agent-governance-checkpoint.md)为准。

OTRYX 的唯一注册模型是应用成员：Provider、Consumer、BOTH 都以 `ApplicationKey + instanceId` 入册。接口数量不增加实例记录，纯 Consumer 没有 RPC Endpoint。业务契约仍使用 `ServiceKey(interface, version, group)`；它不再承担注册实例身份。当前版本没有接口注册兼容模式、双读双写或自动回退。

## 1. Maven 与 Java 命名空间

| 类型 | 历史 Peach RPC | 当前 OTRYX |
| --- | --- | --- |
| Maven groupId | io.peach.rpc | com.peachsoft.otryx |
| Maven artifactId | peach-rpc-* | otryx-* |
| Java namespace | io.peach.rpc.* | com.peachsoft.otryx.* |
| Public type / annotation | PeachRpc* | OtryxRpc* |
| Spring Boot config | peach.rpc.* | otryx.rpc.* |
| Protocol header | Wire v1 | Wire v1 |

`RpcTypeIds`、`RpcIds` 与 `RpcSchemaFingerprint` 包含 Java 类型身份和结构。包名或 DTO/方法结构改变可能改变 Type ID、Method ID 与 Fingerprint；相同 Wire Header 或 Maven 快照版本号不证明业务互通。旧生成 Stub 必须清理后重建。

## 2. 当前控制面装配

| 项目 | 必需契约 |
| --- | --- |
| 应用身份 | 使用 `spring.application.name` 或 `otryx.rpc.registry.application-name`；均为空则启动失败 |
| Namespace / 实例 | `registry.namespace` 默认 `default`；instance ID 每次启动生成一次 UUID，显式值须在同一应用内唯一 |
| Core Client | `applicationControlPlane(resolver, membership, namespace)`；创建时启动 Consumer 入册，以 `membershipReady()` 确认；未就绪则调用失败关闭 |
| Core Server | `applicationMembership(membership)`；绑定真实端点和导出 Manifest 后发布 Provider/BOTH 成员 |
| 自定义后端 | `ApplicationRegistry`、真实 `ApplicationRegistrar` 及 `ApplicationMetadataBackend` 仓库 |
| 成员预算 | `registry.control-plane-timeout` 默认 `3s`，大于零且不超过 `30s`；独立于 Server 控制面预算 |
| 目录陈旧预算 | Core 与 Starter 默认 `30s`，仅接受严格正的有限时长；零时长不能表示永久保留 LKG |
| 旧配置 | Starter 所拥有的未知键与已移除选项直接失败；Observability 与 Quickstart 子树由相应模块校验 |
| Consul | 配置一个本地 Agent endpoint；TTL Check 不能跨 Agent 续期 |
| Eureka | 必须显式选择可靠外置 Metadata Backend 和 endpoints；当前独立 JVM 矩阵使用真实 Consul |

Starter 创建 Consumer 时会在共享成员预算内等待上述入册确认，失败触发已分配资源回收。

表内简写配置键位于 `otryx.rpc.*`。当前所有权与覆盖规则见 [Starter](reference/starter.md)，Manifest 与 Mapping 的一致性见 [应用注册设计](design/application-registry-v2.md)。

`ServiceInstance` 只作为本地负载均衡候选 DTO 保留。候选来源必须通过真实 Mapping、Application Directory、实例 Manifest 和契约校验；不允许直接将硬编码 Provider 候选或本地镜像当作跨进程发现证据。

## 3. Rolling 的基线要求

[基线检查器](../scripts/check_registry_generation_boundary.py) 同时识别两个 checkout 的实际 Starter 工厂、Consumer 发现和 Provider 成员装配。两端必须全部为应用级接线；历史接口模式、未知、混合或不一致装配在启动任何业务进程前失败。

[独立 JVM rolling 脚本](../scripts/run_rolling_compatibility_e2e.sh) 必须执行全部五组，箭头表示 Consumer → Provider：

| 顺序 | 组合 | 验证目的 |
| --- | --- | --- |
| 1 | N → N | 基线自身可用 |
| 2 | N+1 → N | 先升级 Consumer |
| 3 | N → N+1 | 先升级 Provider |
| 4 | N+1 → N+1 | 当前组合可用 |
| 5 | N+1 → 回滚后的 N | Provider 回滚可用 |

任一组失败使整个门禁失败。不能通过识别到历史模式而缩减矩阵；需要迁移历史开发部署时采用隔离注册域和完整业务链切换。当前五组只对实际提交、相同业务契约与应用级基线提供证据，不承诺旧 Java API、旧注册域或任意 DTO 变更互通。

## 4. 部署迁移顺序

1. 固定现有 Provider/Consumer 配套源码、构建产物、业务契约、注册域与安全配置，保留可恢复的旧部署。
2. 同步更新 Maven 坐标、Java import、注解和生成 Stub；将应用身份与实例身份显式纳入部署配置。
3. 在新的隔离注册域配置唯一 Starter 和所需 Adapter。Nacos 在业务启动前初始化 Mapping 分片；Eureka 显式配置元数据后端。不要将两个不同注册模型放入同一实例集合。
4. 在 JDK 21 下构建并运行质量、真实后端契约、独立 JVM process/chain/revision、mTLS 与 rolling 门禁，再按完整业务链切换流量。
5. 回滚时恢复配套 Provider、Consumer、业务契约和注册配置。保存仍可能被在线实例或回滚使用的 Manifest Revision；成员注销不会自动删除内容清单或 Generation 墓碑。

```bash
./mvnw -B -ntp clean verify -Pquality
python3 scripts/check_migration_readiness.py
python3 scripts/test_registry_generation_boundary.py
```

这些命令是验证入口，具体成功结论必须绑定准确提交。真实后端运行方式和计数口径见 [应用级注册验收](reference/application-registry-acceptance.md)。

## 5. TLS/mTLS 示例环境变量

证书路径仍绑定到 `otryx.rpc.transport.security.*`。更新启动脚本时同步移除旧环境变量，避免 Spring 把旧变量解释为未知配置。

| 旧 Quickstart 环境变量 | 当前环境变量 |
| --- | --- |
| `OTRYX_RPC_TLS_CERT` | `OTRYX_QUICKSTART_TLS_CERT` |
| `OTRYX_RPC_TLS_KEY` | `OTRYX_QUICKSTART_TLS_KEY` |
| `OTRYX_RPC_TLS_CA` | `OTRYX_QUICKSTART_TLS_CA` |

实际网络部署必须配置 `NETWORK_MTLS`、可信 CA、严格 DTO 白名单和基于已验证身份的服务/方法授权；注册属性不提供认证证据。

## 6. 仓库与发布边界

当前仓库为 [Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc)。重构前稳定分支 `stable/peach-rpc-1.0.1-pre-otryx-2026-10-10` 固定于 `a4175635aef8702cab613b9c672ca7edbaae6a94`，其历史状态为 Release Prep，不代表正式发行。

当前质量门禁、短时共享 runner 数值、源码清理或四后端测试都不等同于正式发布、生产容量或任意环境下的安全部署保证。发布条件仍见 [发布准备](publication-readiness.md)。
