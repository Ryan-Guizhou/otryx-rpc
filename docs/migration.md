# 从 Peach RPC 1.0.x 迁移到 OTRYX RPC 1.0

> **迁移状态：OTRYX `1.0.0-SNAPSHOT`；尚未形成新旧版本直接互通保证。**
>
> Peach RPC 1.x 与 OTRYX RPC 1.0 属于不同的 Maven/Java 命名空间。即使两者均使用 `1.0.x` 版本编号，也**不表示**可以直接混部、滚动升级或回滚。

## Maven 与 Java API

| 类型 | Peach RPC 1.0.x | OTRYX 1.0 |
|---|---|---|
| Maven groupId | io.peach.rpc | com.peachsoft.otryx |
| Maven artifactId | peach-rpc-* | otryx-* |
| Java namespace | io.peach.rpc.* | com.peachsoft.otryx.* |
| Public type / annotation | PeachRpc* | OtryxRpc* |
| Spring Boot config | peach.rpc.* | otryx.rpc.* |
| Protocol header | Wire v1 | Wire v1 |

## 为什么这次升级是 Breaking Change

旧版的 RpcTypeIds 从 Java 全限定类名计算稳定类型标识，RpcIds 从方法名及参数/返回值类型计算方法标识，RpcSchemaFingerprint 将这些信息组合进 SHA-256。因此，改动包名可能改变 **Type ID、Method ID、Schema Fingerprint**，尽管报文的 Wire v1 Header、Magic 和消息类型没有改变。

**旧接口级注册路径仍使用的历史 Registry Metadata Key：**

- peach.rpc.protocol.version
- peach.rpc.schema.version
- peach.rpc.schema.fingerprint

这些是历史开发实现的兼容字段，不属于新项目的 Spring 配置前缀，也不是 V2 的应用成员身份。旧接口级 SPI、Key/Path 与相关配置的最终删除由 #139 统一执行，不承诺将这些字段作为永久兼容模式保留。

## 迁移流程

1. 固定旧版 Provider/Consumer 与业务 DTO 的源码、构建产物和服务注册设置；不要覆盖旧 GAV 或 Tag。
2. 将 pom.xml 中的 io.peach.rpc:peach-rpc-* 修改成 com.peachsoft.otryx:otryx-*。
3. 更新 io.peach.rpc.* import、PeachRpc* 公开类名及注解，清理旧版生成的 Stub。
4. 配置项改为 otryx.rpc.*，对 Nacos/Etcd 的 namespace、group 与 ServiceKey 做单独审查，避免将新旧无法互通的业务契约登记到同一服务集合。
5. 同时构建与部署 OTRYX Provider/Consumer，先做独立进程的正确性、TLS、Chaos 和失败路径测试。
6. 在隔离流量环境测试真实新旧混合版本；未提供通过证据前采用蓝绿或业务级分流，不采用自动滚动混合升级。
7. 回滚时保留旧版注册组、旧依赖和完整配置，防止新消费者错误连接到旧提供者。

## OTRYX 接口级注册迁移到应用级注册（#137）

这次控制面迁移独立于上述 Java 包名改动。即使两个 OTRYX 快照具有相同的 Maven 版本、Java 包名和 Wire v1 Header，旧 Starter 的 `RegistryFactory` / `serviceDiscovery` / `serviceRegistrar` 与新 Starter 的 `ApplicationRegistryFactory` / `applicationControlPlane` / `applicationMembership` 仍使用不同的注册与发现模型，不能据此直接混部。

当前 #137 分支的 Starter 只装配应用级路径：同一进程实例通过 `ApplicationKey + instanceId` 共享一条 Provider/Consumer/BOTH 成员记录；纯 Consumer 必须真实入册，但没有 RPC Endpoint。旧 SPI 仍留在部分分阶段源码中，**不是 fallback、双读双写或运行时模式开关**。#138 负责完整 Quickstart 改写，#139 负责最终旧路径清除；两者仍须独立完成。[设计和分阶段状态](design/application-registry-v2.md)

### 配置与扩展变更

| 项目 | 应用级 Starter 要求 |
| --- | --- |
| 应用名称 | 配置 `spring.application.name`，或用 `otryx.rpc.registry.application-name` 覆盖；均为空时启动失败 |
| Namespace / 实例 | Namespace 默认 `default`；`registry.instance-id` 默认每次启动生成一次 UUID，显式值须在同一应用内唯一 |
| 共享控制面预算 | 新增 `otryx.rpc.registry.control-plane-timeout`，默认 `3s`，必须大于零且不超过 `30s`；独立于 `server.control-plane-timeout`，纯 Consumer 同样适用 |
| 发现陈旧窗口 | `client.directory-max-staleness` 默认 `30s`；`client.enabled=true` 时拒绝 `0s` 无限 LKG，即使尚无实际引用 |
| 自定义 Registry | 使用 `ApplicationRegistry` 并提供 `ApplicationRegistrar` 与 `ApplicationMetadataBackend` 仓库；旧 `Registry` / `ServiceRegistrar` / `ServiceDiscovery` Bean 定义直接被拒绝 |
| 配置键 | Starter 拥有的未知键和旧接口级选项直接失败；`observability.*` 与 `quickstart.*` 子树由各自模块校验 |
| 默认 Consul 工厂 | 移除非空 `otryx.rpc.registry.consul.datacenter`；用 `registry.endpoints` 选择单个本地 Agent |
| 默认 Eureka 工厂 | `registry.eureka.metadata-backend` 显式选择 `etcd`、`nacos` 或 `consul`，配置非空 `registry.eureka.metadata-endpoints` 并加入所选元数据 Adapter |

表中简写的配置键都位于 `otryx.rpc.*` 下。自定义应用级 Registry Bean 会替代默认工厂，未使用的 Consul/Eureka 后端专属设置不参与默认工厂校验；它仍须满足注册、元数据和有效安全契约。完整配置、生命周期和诊断说明见 [Starter](reference/starter.md)。

Quickstart TLS/mTLS Profile 的证书占位符变量同步改名；更新启动脚本后还须移除旧环境变量，避免旧名被 Spring 解释为未知的 `otryx.rpc.tls.*` 配置。证书路径仍绑定到现有 `otryx.rpc.transport.security.*` 属性，TLS 校验与授权要求不变。

| 旧 Quickstart 环境变量 | 新环境变量 |
| --- | --- |
| `OTRYX_RPC_TLS_CERT` | `OTRYX_QUICKSTART_TLS_CERT` |
| `OTRYX_RPC_TLS_KEY` | `OTRYX_QUICKSTART_TLS_KEY` |
| `OTRYX_RPC_TLS_CA` | `OTRYX_QUICKSTART_TLS_CA` |

Consumer 首次创建在有限预算内等待自身 Registry 入册确认；失败会中止创建并触发已分配资源回收。该确认不代表目标 Provider 的异步目录刷新已经完成。未实例化的 lazy 引用不会产生成员，关闭会封闭懒创建入口，并有界等待 Client/Provider 后再清理共享 Membership。

### 滚动检查所能证明的范围

[注册代际检查器](../scripts/check_registry_generation_boundary.py) 同时检查两个源码目录中的实际 Starter 工厂、Consumer 发现和 Provider 注册装配；仅存在 V2 Core 类型不等于 Starter 已启用 V2。未知、混合或相互矛盾的装配直接失败。

[独立 JVM 运行脚本](../scripts/run_rolling_compatibility_e2e.sh) 按检查结果要求以下组合，表中的箭头均表示 Consumer → Provider：

| 两个快照的实际注册代际 | 必须执行的组合 | 证据边界 |
| --- | --- | --- |
| 同代：接口级/接口级，或应用级/应用级 | N→N、N+1→N、N→N+1、N+1→N+1、N+1→回滚后的 N | 保留完整五组合；兼容结论仍取决于该次真实检查结果 |
| 跨代：接口级/应用级 | N→N、N+1→N+1 | 只检查两代各自的独立 Provider/Consumer JVM；不执行、也不声称混合代际或混合代际回滚互通 |

选择跨代矩阵不等于已经通过检查，也不取代当前版本的协议、TLS/鉴权、故障恢复及 Maven Reactor 门禁。#137 的结果必须绑定准确 PR Head，合并后另行核验 main CI。

### 成对迁移与回滚

1. 固定现有 Provider/Consumer 的匹配快照、业务契约、构建产物、注册域与完整配置，保留可恢复的旧部署。
2. 在隔离的注册域准备应用级 Provider 和 Consumer，同时更新唯一 Starter、可选 Adapter、应用身份及必需元数据配置，不向旧实例集合引入应用级 Consumer。
3. 从仓库根目录在 JDK 21 下执行 Starter 相关回归，再执行完整质量门禁；这只是可复现的验证入口，不是已通过声明：

   ```bash
   ./mvnw -B -ntp -pl otryx-spring-boot/otryx-spring-boot-autoconfigure -am test
   ./mvnw -B -ntp clean verify -Pquality
   ```

4. 使用该部署实际采用的注册中心，在独立 JVM 验证新 Provider/Consumer 配对、真实成员角色、发现就绪、RPC、关闭与故障路径，再按完整业务链切换流量。
5. 回滚时成对恢复匹配代际的 Provider、Consumer、Adapter 与注册配置；不要仅回滚 Provider 并假设应用级 Consumer 能发现接口级旧 Provider。保留仍可能用于部署或回滚的 Manifest Revision，不将本次迁移当作元数据 GC。

## 当前 GitHub 仓库

源码已迁至 [Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc)，所有新文档、POM SCM 和克隆示例以该地址为准。

## 稳定快照与兼容证据

重构前稳定分支为 stable/peach-rpc-1.0.1-pre-otryx-2026-10-10，基准 SHA 为 a4175635aef8702cab613b9c672ca7edbaae6a94。其发布状态为 1.0.1 Release Prep，不应表述为已经正式发布的 1.0.1 GA。

新版本的互通、性能与安全声明以实际 CI 和受控环境 Evidence 为准。

## 发布前条件

- 验证 com.peachsoft.otryx 的 Maven Central namespace 所有权。
- 审核 OTRYX 名称在软件相关类别的既有使用/商标风险。
- GitHub 仓库正式改名后校验所有 SCM、Workflow、Badge 和文档链接。
