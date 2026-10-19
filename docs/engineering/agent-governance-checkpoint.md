# OTRYX RPC 工程断点：#139 应用级注册最终迁移

> 更新时间：2026-10-19（UTC）。这是当前工作树的恢复记录，不是发行或 CI 成功声明。恢复时先查询 GitHub 最新 main、PR Head 与各自准确 SHA 的工作流；不得沿用前一提交的绿色结果。

## 1. 当前任务与已确认边界

仓库：[Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc)。当前只实施 [#139](https://github.com/Ryan-Guizhou/otryx-rpc/issues/139)，完整范围见[应用级设计](../design/application-registry-v2.md)与[验收口径](../reference/application-registry-acceptance.md)。后续顺序由[连续实施计划](plans/2026-10-19-v2-completion.md)记录，不能把后续 Issue 的能力提前算入本次完成。

用户既定授权是一个 Issue 的完整 PR：本地验证 → 准确 PR Head 的相关门禁通过 → 合并 main → 核验合并后的 main CI。不重复申请已批准的实现与常规验证；发行、删除远端资源、修改权限、生产配置或数据库写入不在范围内。

固定 `1.0.0-SNAPSHOT`、JDK 21、Spring Boot 3.5.4、Wire v1。Provider、Consumer、BOTH 都使用唯一应用级注册，纯 Consumer 没有 RPC Endpoint。ServiceKey 保留为业务契约身份，ServiceInstance 仅为已验证 Provider 的本地负载均衡 DTO。没有旧 SPI 兼容模式、双读双写或跨进程假 Provider 镜像。Core 与 Starter 的目录陈旧预算默认为 30 秒，并且必须严格为正、可转换为有限纳秒值。

## 2. 恢复基线与分支

| 项目 | 编辑时记录 |
| --- | --- |
| 最后已合并工作包 | #138，经 [PR #159](https://github.com/Ryan-Guizhou/otryx-rpc/pull/159) 合并 |
| 本轮起点 / 当前未提交 HEAD | `793e942287f16677d56091f0ce45c61ac461c137` |
| 工作分支 | `feat/v2-12-application-registry-contract` |
| 本轮提交 / PR | 尚未写入本记录；提交后补准确 SHA 与 PR 链接 |
| 前一工作包检查 | 根代理恢复核验记录：PR Head 7/7、合并后 main 4/4；仅适用于前一工作包 |
| #139 准确 Head CI | 待新提交与完整相关工作流实际执行 |
| #139 合并后 main CI | 尚未发生，不能预填通过 |

本轮继续保留已验证稳定分支，不移动 `stable/agent-quality-2026-10-09`（`8f051b78d74978c5417394ed2cd22467e077c15f`）或 `stable/peach-rpc-1.0.1-pre-otryx-2026-10-10`。历史治理证据见[稳定基线](stable-baseline.md)，不把 2026-10-09 的 PR 状态当作当前待办。

## 3. 当前工作树已实施内容

- Core、Starter、测试、benchmark 与遥测 E2E 统一使用应用控制面；删除接口级注册 SPI、工厂、描述符及旧兼容 Metadata。重要异常、关闭、线程与资源契约迁至应用级测试。
- Core test-jar 提供共享应用契约。Memory 保留一个 Registry 对象的无网络语义；四真实后端分别使用 10 个独立 Registry client 对象和 30 个真实 Java 接口生成的 canonical Manifest。它们仍在同一个测试 JVM，不能称为 10 个独立业务进程。
- 原生成员 `0→10→0`、每个 SPI 的调用/成功/失败/未完成数、订阅创建/关闭/活动句柄及快照/健康通知与目录收敛分别记录。相邻不同成员内容计为观察到的逻辑变化，初始快照、仅 revision 变化和相同成员重排单列。成功报告在句柄关闭、所有操作结清后输出。后端总 HTTP/gRPC、keepalive、Config 轮询及服务端 Watch 流量未测量，不得由这些数值推算。
- Python 清理门禁覆盖生产/测试、描述符、配置、脚本、benchmark 和当前使用文档；允许的负向契约文字严格限定为两个指定测试文件的精确字符串，不能豁免 imports、调用或其他旧用法。
- rolling 只接受应用级 Starter 基线，所有 N/N、N+1/N、N/N+1、N+1/N+1、rollback 五组仍执行；旧模式必须在构建/启动前拒绝，混合组或回滚失败不能发布整组成功。脚本拥有的每个进程使用 35 秒 TERM 停止预算，超时后 KILL 并回收；正常停止及 EXIT/INT/TERM 清理复用同一实现。
- 通用只读观察 CLI 支持 Nacos/Etcd/Consul/Eureka，Eureka 明确使用真实 Consul metadata；process/chain/revision 共用同一后端与隔离 namespace。新增四后端独立 JVM CI 矩阵，既有 Nacos、mTLS、Chaos、ACL 门禁保留。
- 最终 CI 审查补齐准确 PR Head 检出、六类真实后端/故障/rolling/comparison 的 main 触发与原始报告归档。rolling 的 main 基线使用 push.before，并记录实际两个提交；CI benchmark、preflight、soak 的来源 SHA 统一为实际检出 Head。依赖审查有意保留拟合并树与基线的差异扫描，不混称运行时 Head 测试。
- RPC comparison 两个 OTRYX JVM 使用真实共享 Etcd，必须获得相同 endpoint 与本轮 namespace。脚本先验证 Etcd 健康，再构建/启动；CI 启动固定 Etcd 3.5.21。
- 真实 comparison 暴露 Jetcd 0.8.7 生成 Stub 的 Vert.x 5 类型与本仓 Vert.x 4 冲突。新增真实 Etcd 事件循环首次注册回归已复现 `ContextInternal` 加载失败；修复将 Jetcd 对齐 0.8.5、Vert.x gRPC 对齐 4.5.34，同时保留既有 gRPC/Guava/Commons Compress 传递依赖版本。比较模块导入主项目依赖管理，具体绿色结果与漏洞差异门禁仍按下表实测记录。
- 根 README、Quickstart 中英文文档与架构、SPI、迁移、运行/后端指南同步；旧历史计划中的失效 Metadata 源码链接改为当前 Manifest codec。

## 4. 验证记录与不能合并的剩余项

本地原始脚本日志保存于 `/tmp/otryx-139-script-evidence/`；此路径是当前会话证据，不是永久 CI artifact。`target/` 会被 Maven clean 清理，不能依赖其中先前 RED 日志继续存在。提交 PR 时需上传或引用实际 CI artifact，并把准确 SHA、Run/Job 链接补在本节。

| 检查 | 当前证据状态 |
| --- | --- |
| Core 完整测试 | 最新 clean reactor 中 231 tests，0 failures/errors/skips；见本地 `reactor-quality-complete.log`。旧公开入口/有限预算 6 项、旧 revision 复活 LKG 1 项和启动回滚 close 抛错导致 Future 悬挂 1 项均有先失败后通过证据；另增加 3 个共享证据计数器回归 |
| 清理门禁、rolling 与 Quickstart Python 夹具 | 9 / 21 / 29 个单元测试实际通过；原始日志为 `cleanup-tests.log`、`rolling-tests.log`、`quickstart-tests.log`。Quickstart layout 与独立 migration readiness 检查也通过 |
| 共享 Etcd preflight / 比较结果校验 / 进程预算 | 最新 18 个 Python 测试通过，见 `comparison-process-green.log`；旧脚本忽略 TERM 的进程卡住有独立 RED。HTTP 与受控进程夹具只验证脚本失败传播和限时回收，不是 Etcd 或 RPC 功能实测 |
| `check_project.py` | 本轮实际通过；最终复跑日志为 `repository-check.log` |
| 文档与脚本静态检查 | 86 个 Markdown 的结构/链接、两组中英文 README、22 个 shell 通过，见 `document-workflow-check.log`；最后 CI 专项检查 20 个无重复键 YAML、146 个 run 块、17 个 PR checkout、6 组 main/PR 触发镜像与实际归档路径均通过，见 `ci-gate-static-review.json`。生产 Java 魔法字面量检查 239 文件 / 0 findings，见 `magic-literal-check.log` |
| Mermaid / SVG 实际预览 | 本地未找到仓库固定的 Mermaid CLI；结构/语法检查不能替代渲染。准确 Head 的 Documentation Previews 工作流仍须实际通过 |
| Java 观察 CLI / 全 reactor quality | 最终 `clean install -Pquality` 全量构建成功，601 tests、0 failures/errors、4 skips；见 `reactor-quality-complete.log`，92 份原始 XML 与汇总另存 `quality-complete-evidence/`。这次从根 reactor 一次执行至末尾，包含最新计数器和 Etcd EventLoop 回归 |
| 全 reactor style 与报告完整性 | 隔离源码快照 `clean install -Pstyle-audit -DskipTests` 成功，聚合 `--enforce-zero` 确认 29 个模块报告、333 个 reactor Java 文件、0 findings；见 `style-snapshot-final.log`、`checkstyle-final.json` / `.md`。工作区同步产生的 `.rsync-tmp` 曾两次破坏 JAR 打包，隔离快照避免该环境干扰；Java/POM/Checkstyle 输入逐文件 SHA-256 与工作树核对一致 |
| 四后端共享合同 / 原生 `0→10→0` / Chaos | Etcd、Nacos、Consul、Eureka 真实共享契约均通过，分别 10 个不同客户端、30 个真实契约、原生成员 `0→10→0`。Memory 为 1 个共享 Registry。Etcd 外部端点 restart、Consul ACL/Chaos、Eureka Chaos 共 4 个条件跳过；Etcd/Nacos 独立 Chaos profile 未在本地执行，必须由准确 Head CI 补齐 |
| 四后端 process / Nacos chain/revision/mTLS | 本地 8 场景全部实际通过：Memory；Etcd、Consul、Eureka、Nacos 的独立 JVM process；Nacos chain、revision 与 mTLS。日志为 `local-e2e-summary.log` 和逐场景日志。Nacos restart 本地显式关闭，其真实容器故障门禁及其他三后端 chain/revision 仍由 CI 执行 |
| 应用级五组 rolling | 失败传播夹具与实际两版本 RPC 分开；真实矩阵结果待实跑 |
| RPC comparison 独立 JVM | 完整 OTRYX/Dubbo smoke 已实际通过，两框架错误数均为 0，校验器通过，拥有的进程均退出；见 `comparison-smoke-final.log` 和 `comparison-smoke-evidence/`。OTRYX 两 JVM 使用同一真实 Etcd 与隔离 namespace；本地未受控环境的短测量不构成性能排名或 #146 正式验收 |
| 已迁移 benchmark / 10k smoke 工具链 | 5 份 JMH smoke JSON 及 10k 逻辑调用者、请求 3 秒的 soak 已实际运行，结构校验通过；见 `benchmark-smoke-139.log`、`soak-smoke-139.log` 与 `v2d2-smoke-report-139/`。soak 错误均为过载拒绝，错误率 99.8185%，仅得 `REPORT_ONLY`，不是 SLO 通过。既有环境 Markdown 脚本的反引号替换错误再次复现，保留为 #146 工具整改项；原始 properties/JSON 独立存在 |
| Jetcd / Vert.x 依赖兼容 | 真实 Etcd EventLoop 注册用例在旧依赖下 1 test / 1 error，修复后 1 test / 0 errors，并在上述完整 quality 构建再次通过；日志及 XML 为 `etcd-context-red` / `etcd-context-green`。比较模块依赖树确认既有 gRPC、Guava、Commons Compress 等版本保留；准确 Head 的 OSV 漏洞差异门禁仍待执行 |
| 准确 PR Head 及合并后 main | 待提交后查询，不能借用旧结果 |

本地已准备固定官方服务版本：Etcd 3.5.21、Consul 1.20.6、Nacos 3.2.4，Eureka 使用仓库 Boot 3.5.4 / Cloud 2025.0.0 服务端夹具。Nacos readiness 路径为 `/nacos/v3/admin/core/state/readiness`；旧路径的 404 不代表可继续测试。服务端进程必须与实际测试处于可控生命周期内，不假设不同执行会话之间后台进程持续存活。readiness 成功仅表示可以开始验证。

Etcd 外部 endpoint 的测试不能控制外部服务重启时，条件性跳过必须单列；CI 仍使用可控容器执行必要故障门禁。测试基础设施限制与产品缺陷分别记录，不能为了绿色删掉验收项。

rolling 有界停止先在旧脚本复现失败：忽略 TERM 的实际进程夹具触发 20 秒外层超时，见 `rolling-owned-stop-red.log`。修复后同一夹具通过，八个故意忽略 TERM 的业务夹具进程均被 KILL 并回收，五组仍全部执行。测试将 `ROLLING_COMPAT_STOP_SECONDS` 设为 1 秒；生产默认 35 秒，配置仅允许 1–35 秒并在启动子进程前移除。该测试只证明 shell 生命周期，不是实际 RPC 或优雅注销证据。

## 5. 下一步与恢复命令

先核对 Git 状态、准确 Head 和本节日志，再执行尚未完成的项。局部修复后只扩大与实际风险相关的验证；Maven 质量与风格、真实后端、跨 JVM、rolling 和 mTLS 都是合并门槛。

```bash
git status --short
git rev-parse HEAD
python3 scripts/test_migration_readiness.py
python3 scripts/check_migration_readiness.py
python3 scripts/test_registry_generation_boundary.py
python3 scripts/test_quickstart_scenarios.py
python3 scripts/test_quickstart_layout.py
python3 scripts/test_rpc_comparison_validator.py
python3 scripts/check_project.py
mvn -B -ntp clean verify -Pquality
mvn -B -ntp -Pstyle-audit -DskipTests install
python3 scripts/summarize_checkstyle_audit.py \
  --json target/checkstyle-audit.json \
  --markdown target/checkstyle-audit.md --enforce-zero
```

四后端环境与独立 JVM 命令以[验收指南](../reference/application-registry-acceptance.md)为准。不要通过选择旧基线、静态成员或 Memory 替身绕过失败。提交前将已通过、失败、跳过和未运行项分开；创建一个包含完整 #139 纵向迁移的 PR，准确 Head 的相关门禁全部通过后才合并，随后核查 main 实际工作流清单。

## 6. 中断与回滚

遵循 [AGENTS.md](../../AGENTS.md)：单个 Maven、CI 或远程 MCP 步骤持续无进展超过十分钟，停止无意义等待并记录最后输出、错误、Run/Job、下一条命令；不擅自取消他人远端任务，不把工具不可用写成通过。

当前为未提交工作树，修复时保留各代理并行修改，不使用硬重置覆盖他人内容。若 PR 合并后 main 失败，停止后续 Issue 合并，按实际故障提交隔离修复或 revert PR。回滚目标是最后已验证主线；稳定引用、生产服务、发行版本与权限均不作自动修改。
