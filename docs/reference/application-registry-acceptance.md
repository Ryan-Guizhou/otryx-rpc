# 应用级注册验收与证据口径（#139）

> 当前任务 [#139](https://github.com/Ryan-Guizhou/otryx-rpc/issues/139) 使用 `1.0.0-SNAPSHOT`、JDK 21、Spring Boot 3.5.4、Wire v1。本文定义可复现门禁；实际成功、跳过或失败以[当前断点](../engineering/agent-governance-checkpoint.md)、准确 PR Head 和合并后 main 的日志为准。

## 1. 唯一运行模型

Provider、Consumer、BOTH 都通过应用身份入册。Consumer-only 无 RPC Endpoint，不能被解析成 Provider。接口数量只增加 Manifest/Mapping，不增加同一监听实例的成员数。Core Client/Server、Starter、四模块 Quickstart、benchmark 和遥测 E2E 使用相同应用控制面；不存在跨进程静态 Provider 镜像或第二种注册模式。

## 2. 共享契约与计数

`ApplicationRegistryContractTestKit` 位于 Core test-jar。Memory 与四个真实后端复用成员/角色、多个应用、多个 Manifest revision、Owner fencing、订阅健康、失败及关闭契约。测试库不进入生产依赖。

| 验证层 | 实际边界 | 证据 |
| --- | --- | --- |
| Memory | 一个内存 Registry 对象；不是网络服务 | 角色、目录、Owner、Manifest 和订阅的确定性契约 |
| 四个真实后端 | 每个后端用 10 个独立 Registry client 对象；仍在同一个测试 JVM | 30 个真实 Java 接口经当前契约解析生成 canonical Manifest，检查全部 30 个服务目录及原生成员 `0→10→0` |
| 独立进程 | 每个业务角色为独立 JVM，共享实际注册后端 | process、A→B→C chain、revision 升级/回滚，真实 Wire v1 RPC |
| 故障 | 实际服务器或受控容器操作 | Etcd leader transfer、Nacos pause/restart、Consul pause/ACL、Eureka JVM restart |

30 个接口不是用 30 个虚构名称凑出的 Metadata 记录。10 个 SDK 对象也不是 10 个业务 JVM。原生成员数通过后端只读探针独立取得，不能仅统计 Java `register()` 调用后推断服务端状态。

共享契约在订阅关闭后报告以下独立口径；计数器自身另有确定性故障测试，覆盖未完成 Future、正常及异常完成、同步拒绝、取消、订阅创建/关闭失败和关闭后回调。

| 字段 | 定义与完成边界 |
| --- | --- |
| `memberCounts=0,10,0`、`memberCountSource` | 三个收敛阶段的实际成员数；来源为远端原生只读探针或 Memory 的进程内目录 |
| `spiOperations` | 以 `member.register/unregister`、`mapping.claim/publish/revoke/lookupVerified`、`manifest.publish/lookupVerified`、`application.lookup/subscribe` 及 `subscription.close` 分项报告 `calls`、`successes`、`failures`、`pending`。只统计穿过代理的入口；默认方法内部再次调用其他方法不重复计数 |
| SPI 终态 | 异步方法只有在其原始 `CompletionStage` 真正完成时才记成功或失败；取消计为失败，同步抛错立即记失败。计数代理保留原始 stage，不把返回 Future 当成完成。最终有界等待全部 `pending=0`，并断言 `calls=successes+failures`；预期的 stale-owner publish 拒绝也必须进入失败计数 |
| `subscriptions.opened/closed/active` | `opened` 只计已成功返回的句柄；`closed` 只计每个句柄首次正常完成 `close()`，关闭抛错时仍属 active。重复关闭继续调用实际适配器，但不重复增加 closed；最终断言 `opened=closed` 且 `active=0` |
| `subscriptions.snapshotCallbacks/healthSuccessCallbacks/healthFailureCallbacks` | 分别统计代理接收的快照、同步成功和同步失败回调；健康通知不冒充成员变化。`callbacksAfterClose` 统计句柄正常关闭后开始的回调，观测器不屏蔽违规回调，正常合同断言该数为零 |
| `subscriptions.initialSnapshots/logicalChanges` | 每个订阅首个快照单列为初始观测。之后只在相邻快照的 ApplicationKey 或完整成员集合发生变化时增加 logicalChanges，忽略目录 revision 和列表顺序；成员角色、Endpoint、权重、Manifest revision 或 attributes 变化均计入。重复同内容和健康通知不增加；`A→B→A` 计两次变化。它不是全局去重后的历史内容数量，也不保证观察到合并事件前的每个后端中间态 |
| `registerConvergenceMillis/unregisterConvergenceMillis` | 从该阶段并发 SPI 操作发起到目录查询、订阅及原生成员探针全部符合预期的耗时 |

**这些 SPI 和订阅计数不是全部后端 HTTP、gRPC、keepalive、Config 轮询或 server Watch 的流量总数。** 后台租约、SDK 重试、适配器缓存和原生探针的网络请求不属于 SPI 方法计数；未测量的后端流量明确为 `backendTraffic=not-measured`。共享 runner 的短时结果不代表生产吞吐、容量或长稳。

## 3. Python 清理与失败路径门禁

```bash
python3 scripts/test_migration_readiness.py
python3 scripts/check_migration_readiness.py
python3 scripts/test_registry_generation_boundary.py
python3 scripts/test_quickstart_scenarios.py
python3 scripts/test_quickstart_layout.py
python3 scripts/check_project.py
```

清理检查扫描生产/测试 Java、SPI 描述符、配置、脚本、benchmark 和当前使用文档，拒绝过渡类型、旧注册路径、旧模式和旧 Metadata 键。它允许业务 ServiceKey、仅供本地 LB 的 ServiceInstance 和应用订阅句柄；归档发行记录及工程历史文字不作为当前用法。词法门禁不能替代行为验证。

Python 场景夹具使用明确的模拟进程，只证明真实 shell 的启动顺序、配置一致性、失败传播、清理与证据校验，不能标记为真实 RPC 或服务器通过。

## 4. 四后端独立 JVM 矩阵

先构建，工作目录为仓库根目录：

```bash
./mvnw -B -ntp -pl otryx-codegen -am install -DskipTests
./mvnw -B -ntp -pl otryx-quickstart/otryx-quickstart-provider,otryx-quickstart/otryx-quickstart-consumer \
  -am package -DskipTests
```

必须已有选定后端运行。`QUICKSTART_REGISTRY` 只接受下表四种值。默认 Nacos；服务专属 endpoint 环境变量在没有通用覆盖时生效。

| 后端 | `QUICKSTART_REGISTRY` | 默认 endpoint / 专属变量 | 元数据与隔离 |
| --- | --- | --- | --- |
| Nacos | nacos | `127.0.0.1:8848` / `NACOS_TEST_ENDPOINT` | public namespace、每场景新 group；启动前初始化分片 |
| Etcd | etcd | `http://127.0.0.1:2379` / `ETCD_TEST_ENDPOINT` | 每场景新 namespace、原生 KV |
| Consul | consul | `http://127.0.0.1:8500` / `CONSUL_TEST_ENDPOINT` | 每场景新 namespace、单 Agent 原生 KV |
| Eureka | eureka | `http://127.0.0.1:8761/eureka` / `EUREKA_TEST_ENDPOINT` | 每场景新 namespace；必填 `QUICKSTART_EUREKA_METADATA_ENDPOINT` 指向真实 Consul |

`QUICKSTART_REGISTRY_ENDPOINT` 可显式覆盖所选后端地址。端点不得内嵌凭据；这些脚本面向隔离开发/CI，受鉴权部署应使用对应配置入口，不把秘密放在 shell 命令参数或证据中。

以 Etcd 为例：

```bash
export QUICKSTART_REGISTRY=etcd
export QUICKSTART_REGISTRY_ENDPOINT=http://127.0.0.1:2379
bash scripts/run_quickstart_process_e2e.sh
bash scripts/run_three_process_deadline_e2e.sh
bash scripts/run_quickstart_revision_e2e.sh
```

切换至 Eureka + Consul：

```bash
export QUICKSTART_REGISTRY=eureka
export QUICKSTART_REGISTRY_ENDPOINT=http://127.0.0.1:8761/eureka
export QUICKSTART_EUREKA_METADATA_ENDPOINT=http://127.0.0.1:8500
bash scripts/run_quickstart_process_e2e.sh
bash scripts/run_three_process_deadline_e2e.sh
bash scripts/run_quickstart_revision_e2e.sh
```

三个脚本分别检查：原 Consumer 保持运行时 Provider 停止/恢复；Consumer-only A → BOTH B → Provider C 的 Deadline/Metadata 传播；同一 JAR 通过真实导出 `{Greeting}` 与 `{Greeting, Details}` 得到不同 Manifest Revision，验证共存、升级、回滚和 Provider 全停后 Consumer 仍在。

CI `.github/workflows/application-registry-e2e.yml` 对 Nacos、Etcd、Consul、Eureka 执行同一组三个脚本，记录各阶段快照和收敛耗时。固定环境为 Nacos 3.2.4、Etcd 3.5.21、Consul 1.20.6；Eureka 复用现有 Boot 3.5.4 / Cloud 2025.0.0 真实服务端夹具。Etcd 镜像入口依据[官方安装说明](https://etcd.io/docs/v3.5/install/)。这个矩阵不替代各后端 Chaos、ACL 或完整 Maven 质量门禁。

## 5. 只读观察 CLI

```bash
bash scripts/observe_quickstart_registry.sh \
  otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar \
  etcd http://127.0.0.1:2379 demo - - target/new-snapshot.json \
  otryx-quickstart-provider otryx-quickstart-consumer
```

参数顺序为 Provider 可执行 JAR、backend、endpoints、共享 namespace、Nacos group 或 `-`、Eureka Consul metadata endpoints 或 `-`、新输出文件、1–16 个明确应用名。Eureka 强制使用真实 Consul metadata；Etcd/Consul 拒绝无意义的 group/外置元数据参数。Nacos 的既有 `observe_quickstart_nacos.sh` 入口保留，但两个 namespace 参数必须相同，以匹配 Starter 的单一 namespace 配置。

CLI 不启动 Spring、不注册观察者成员、不枚举全注册中心，不输出任意 attributes。白名单 JSON 包含指定成员、其真实 Manifest，以及三个 Quickstart 契约的 Mapping/已验证路由。所有读取及关闭完成后才发布新文件；失败不覆盖已有证据，也不成为健康空目录。

进程总预算、成员/Manifest 数量和输出字节均有界。跨成员/元数据读取不是事务快照，脚本最多在 120 秒内重试，输出 `convergenceSeconds` 包含观察 JVM 启动、查询和重试开销；它不是服务端原子更新延迟。

## 6. 必需的补充门禁

- `ci.yml` 保留 Memory loopback、Nacos 独立进程/服务端重启、完整 chain/revision 和 mTLS 授权场景。
- `etcd-chaos.yml` / `nacos-chaos.yml` 执行应用级 Chaos profile；`consul-v2.yml` 包含真实 Agent pause/recovery 和独立 ACL default-deny；`eureka-v2.yml` 包含真实 Eureka JVM restart 与 Consul Metadata。
- `rolling-compatibility.yml` 只接受应用级 Starter 基线，五组 N/N、N+1/N、N/N+1、N+1/N+1、rollback 全部运行，任一失败阻止通过。
- RPC comparison 的 OTRYX Provider/Consumer 使用真实共享 Etcd，脚本向两端传入相同 `RPC_COMPARISON_REGISTRY_ENDPOINT` 和每轮隔离 `RPC_COMPARISON_REGISTRY_NAMESPACE`；Java 入口缺少任一值直接失败。比较结果仅是独立 JVM smoke，不能推广为不同控制面或生产规模的公平性能结论。

有条件跳过的服务端测试必须单列；仅启动服务器并通过 readiness 不能算功能、故障或回滚验证完成。准确 Head 上所有相关检查通过后才可合并，随后重新查询 main 的实际工作流清单。

rolling 对自己启动的每个 Provider/Consumer 默认等待最多 35 秒完成 TERM 停止，超时后 KILL 并回收；退出、失败和信号清理共用此边界。仅为本地夹具缩短等待时可设置 `ROLLING_COMPAT_STOP_SECONDS=1`（允许 1–35 秒），该脚本配置不会传入 Spring 子进程。强制终止不代表优雅注销，后端成员恢复另由真实 process/chain/revision 与故障门禁验证。
