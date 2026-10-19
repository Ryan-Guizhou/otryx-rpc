# OTRYX RPC 工程断点：#139 应用级注册最终迁移

> 更新时间：2026-10-19（UTC）。[PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) 已创建，尚未合并，#139 尚未完成验收。首个 Head 的普通 CI、Eureka 专项及依赖扫描证据存在三项关闭门槛缺口；本轮环境变量、Resolver 和依赖门禁修复已完成各自范围的本地验证，修补后的 comparison 工作树单根依赖差异也已通过。新 Head 尚待提交并触发完整远端门禁。恢复时先查询 GitHub 最新 main、PR Head 与各自准确 SHA 的工作流；不得沿用前一提交的绿色结果。

## 1. 当前任务与已确认边界

仓库：[Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc)。当前只实施 [#139](https://github.com/Ryan-Guizhou/otryx-rpc/issues/139)，完整范围见[应用级设计](../design/application-registry-v2.md)与[验收口径](../reference/application-registry-acceptance.md)。后续顺序由[连续实施计划](plans/2026-10-19-v2-completion.md)记录，不能把后续 Issue 的能力提前算入本次完成。

用户既定授权是一个 Issue 的完整 PR：本地验证 → 准确 PR Head 的相关门禁通过 → 合并 main → 核验合并后的 main CI。不重复申请已批准的实现与常规验证；发行、删除远端资源、修改权限、生产配置或数据库写入不在范围内。

固定 `1.0.0-SNAPSHOT`、JDK 21、Spring Boot 3.5.4、Wire v1。Provider、Consumer、BOTH 都使用唯一应用级注册，纯 Consumer 没有 RPC Endpoint。ServiceKey 保留为业务契约身份，ServiceInstance 仅为已验证 Provider 的本地负载均衡 DTO。没有旧 SPI 兼容模式、双读双写或跨进程假 Provider 镜像。Core 与 Starter 的目录陈旧预算默认为 30 秒，并且必须严格为正、可转换为有限纳秒值。

## 2. 恢复基线与分支

| 项目 | 编辑时记录 |
| --- | --- |
| 最后已合并工作包 | #138，经 [PR #159](https://github.com/Ryan-Guizhou/otryx-rpc/pull/159) 合并 |
| 本轮起点 / 首轮 rolling 的实际 N | `793e942287f16677d56091f0ce45c61ac461c137` |
| 工作分支 | `feat/v2-12-application-registry-contract` |
| 首个 PR Head / 首轮 rolling 的实际 N+1 | `585b569175c048144b603863cfa1ef9401410157`，对应 [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) |
| 当前修复工作树 | 基于首个 Head 修复 CI 环境变量作用域、Resolver 完成顺序、依赖扫描门禁及 comparison 的 Netty/Jackson 回退；修复提交 SHA 尚未写入本记录 |
| 前一工作包检查 | 根代理恢复核验记录：PR Head 7/7、合并后 main 4/4；仅适用于前一工作包 |
| #139 首个 Head CI | 14 条 workflow 已结束：普通 CI 与 Eureka 专项失败；其余 12 条显示 success，但 Dependency Vulnerability Review 的空扫描结果不能作为验收证据 |
| 当前本地 quality 重跑 | 根代理已返回 exit 0：604 tests、0 failures/errors、4 个条件 skips、92 份 XML，包含四个真实后端及 Resolver success/failure 完成顺序修复；不包含随后新增的 cancel 回调重入测试 |
| 最终取消回调补充 | 独立定向 GREEN：Resolver 8、Mapping 4、Manifest 4、Kit 4，共 20 tests，0 failures/errors/skips；与前一行完整 quality 分开，不合成新的全量测试总数 |
| 依赖门禁本地闭环 | 原 base / 首 Head 拟合并树的三根 SBOM/OSV 真实覆盖 34 个 POM，并检出 comparison 新增漏洞；修补后的 comparison 单根 working-tree reporter exit 0，仍须下一 Head 完整三根审查 |
| 下一 Head 的远端门禁 | 尚未触发；修复提交的准确 SHA 与结果待补，不能借用首个 Head 的专项成功 |
| #139 合并后 main CI | 尚未发生，不能预填通过 |

本轮继续保留已验证稳定分支，不移动 `stable/agent-quality-2026-10-09`（`8f051b78d74978c5417394ed2cd22467e077c15f`）或 `stable/peach-rpc-1.0.1-pre-otryx-2026-10-10`。历史治理证据见[稳定基线](stable-baseline.md)，不把 2026-10-09 的 PR 状态当作当前待办。

## 3. 当前工作树已实施内容

- Core、Starter、测试、benchmark 与遥测 E2E 统一使用应用控制面；删除接口级注册 SPI、工厂、描述符及旧兼容 Metadata。重要异常、关闭、线程与资源契约迁至应用级测试。
- Core test-jar 提供共享应用契约。Memory 保留一个 Registry 对象的无网络语义；四真实后端分别使用 10 个独立 Registry client 对象和 30 个真实 Java 接口生成的 canonical Manifest。它们仍在同一个测试 JVM，不能称为 10 个独立业务进程。
- 原生成员 `0→10→0`、每个 SPI 的调用/成功/失败/未完成数、订阅创建/关闭/活动句柄及快照/健康通知与目录收敛分别记录。相邻不同成员内容计为观察到的逻辑变化，初始快照、仅 revision 变化和相同成员重排单列。成功报告在句柄关闭、所有操作结清后输出。后端总 HTTP/gRPC、keepalive、Config 轮询及服务端 Watch 流量未测量，不得由这些数值推算。
- Python 清理门禁覆盖生产/测试、描述符、配置、脚本、benchmark 和当前使用文档；允许的负向契约文字严格限定为两个指定测试文件的精确字符串，不能豁免 imports、调用或其他旧用法。
- rolling 只接受应用级 Starter 基线，所有 N/N、N+1/N、N/N+1、N+1/N+1、rollback 五组仍执行；旧模式必须在构建/启动前拒绝，混合组或回滚失败不能发布整组成功。脚本拥有的每个进程使用 35 秒 TERM 停止预算，超时后 KILL 并回收；正常停止及 EXIT/INT/TERM 清理复用同一实现。
- 通用只读观察 CLI 支持 Nacos/Etcd/Consul/Eureka，Eureka 明确使用真实 Consul metadata；process/chain/revision 共用同一后端与隔离 namespace。新增四后端独立 JVM CI 矩阵，既有 Nacos、mTLS、Chaos、ACL 门禁保留。
- CI 审查补齐准确 PR Head 检出、六类真实后端/故障/rolling/comparison 的 main 触发与原始报告归档。rolling 的 main 基线使用 push.before，并记录实际两个提交。首轮 CI 发现 benchmark 来源 SHA 的 job 级环境变量污染 Starter，本地已将准确 Head 只限定到 preflight、matrix、soak 三个 step；不放宽 Starter 严格配置。依赖审查改为基线与拟合并树分别构建 main、comparison、Eureka fixture 三根 SBOM，严格验证提取与扫描覆盖，并让固定 reporter 读取规范路径副本；保留原始输入/结果，提取失败不再被空 JSON 掩盖。本地真实 RED/GREEN 的代码范围见 4.3，首轮空扫描的绿色状态仍不构成验收。
- RPC comparison 两个 OTRYX JVM 使用真实共享 Etcd，必须获得相同 endpoint 与本轮 namespace。脚本先验证 Etcd 健康，再构建/启动；CI 启动固定 Etcd 3.5.21。
- 真实 comparison 暴露 Jetcd 0.8.7 生成 Stub 的 Vert.x 5 类型与本仓 Vert.x 4 冲突。新增真实 Etcd 事件循环首次注册回归已复现 `ContextInternal` 加载失败；修复将 Jetcd 对齐 0.8.5、Vert.x gRPC 对齐 4.5.34，同时保留既有 gRPC/Guava/Commons Compress 传递依赖版本。完整依赖提取又发现 comparison 导入主 BOM 后使原有 Netty/Jackson 回退；当前 POM 优先恢复 Vert.x 4.5.34 原有的 Netty 4.1.138.Final / Jackson 2.21.6，再保留主 BOM 的其余约束。修补后真实 comparison 和单根漏洞差异已有本地证据，不能扩大为新 Head 的全量通过。
- 根 README、Quickstart 中英文文档与架构、SPI、迁移、运行/后端指南同步；旧历史计划中的失效 Metadata 源码链接改为当前 Manifest codec。

## 4. 验证记录与不能合并的剩余项

本地原始脚本日志保存于 `/tmp/otryx-139-script-evidence/`；此路径是当前会话证据，不是永久 CI artifact。`target/` 会被 Maven clean 清理，不能依赖其中先前 RED 日志继续存在。首个 Head 提交前、Resolver 完成顺序修复后及随后 cancel 回调重入补充是不同代码阶段，以下记录分别标明范围，不能相互替代。

### 4.1 首个 PR Head 的远端结果与三项修复

本节所有远端结果都属于 `585b569175c048144b603863cfa1ef9401410157`、attempt 1。14 条 workflow 已全部结束，当前不能合并或宣布 #139 验收完成。

| 工作流 / 证据 | 首轮结果与后续动作 |
| --- | --- |
| [普通 CI · 39221767557](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767557) | [verify job 117958479833](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767557/job/117958479833) 在 reactor 失败。job 级 `OTRYX_RPC_BENCHMARK_COMMIT` 被严格配置检查识别成未知键 `otryx.rpc.benchmark.commit`，污染 Spring 测试。本地已将该变量限定到三个 benchmark step，待新 Head 验证；首轮普通 CI 后续 Memory、benchmark、独立 JVM 与 mTLS 步骤被跳过，不能写成通过 |
| [Eureka 专项 · 39221767693](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767693) | [real-eureka job 117958480438](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767693/job/117958480438) 的共享契约出现 `expected HEALTHY but was NOT_READY`。Integration XML 为 6 tests / 1 failure；Resolver 对外 Future 完成早于本地状态发布的竞态已在本地修复，最终取消回调也有独立 GREEN。首轮终态 Kit 计数未输出，专属 restart 步骤未执行；仍须新 Head 远端闭环，不能被 Eureka 独立 JVM 矩阵的成功覆盖 |
| [Dependency Vulnerability Review · 39221767605](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767605) | [dependency-review job 117958479879](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767605/job/117958479879) 显示 success，但未发布的 reactor `1.0.0-SNAPSHOT` 依赖提取失败，产生空 results；原门禁只检查文件存在和 JSON 语法。实际提取/覆盖/失败关闭门禁已在本地补齐，并真实检出随后修补的 comparison 依赖回退，见 4.3；首轮这个绿色结果不能证明无新漏洞 |
| [四后端独立 JVM 矩阵 · 39221767646](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767646) | Nacos、Etcd、Consul、Eureka 四个 job 均成功，process / chain / revision 共 12 场景实际执行。四份 artifact 合计 44 份快照、12 份 summary；有原 Consumer PID 恢复、三 JVM 链、真实 `DETAILS_OK`、两个 Manifest revision、晋级/回滚与清退证据。Eureka 使用真实 Consul metadata |
| [Rolling Compatibility · 39221767575](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767575) | 五条组合 PASS 齐全，含 rollback。artifact 中实际 N=`793e942287f16677d56091f0ce45c61ac461c137`、N+1=`585b569175c048144b603863cfa1ef9401410157`；两者均为应用级基线，没有用同一 SHA 替代混合版本 |
| [RPC Comparison Smoke · 39221767757](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767757) | 两框架各自独立 Provider / Client JVM、真实共享 Etcd 与结果 validator 成功。OTRYX 54,437 次成功、Dubbo 21,513 次成功，均 0 errors；2 秒预热、3 秒测量，明确仅为 smoke。两份结果、环境、Provider 日志和报告已归档，不构成 #146 正式性能证据 |
| [Java Style Audit · 39221767391](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767391) 与 [Documentation Previews · 39221767470](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767470) | 首个准确 Head 的风格与文档预览均实际成功；修复形成新 Head 后重新核验相关门禁 |

其余六条已结束且显示 success 的工作流为 Agent Governance、Java Agent Quality、Etcd Chaos、Nacos Chaos、Consul V2 Real Backend and Chaos、Release Readiness。Etcd/Nacos 的独立 Chaos XML 各为 1 test / 0 failures / 0 skips；Consul 主集成 9 tests、Chaos 1 test 均成功，ACL 在单独 job 的 1 test 成功，不能把主 job 的 ACL skip 算作执行。上述结果均限定于首个 Head。

独立 JVM / rolling / comparison 六份 artifact 已下载并逐份核对 SHA-256，索引为 `/tmp/otryx-build/pr160-jvm-ci-audit.md`；五份后端 artifact 也已解包核对原始 XML 与 digest，索引为 `/tmp/otryx-build/pr160-registry-ci/artifact-content-audit.json`。Eureka 失败 XML 保留原样。根代理的 Resolver success/failure 完成顺序修复后完整 quality 已返回 exit 0：604 tests、0 failures/errors、4 个条件 skips、92 份 XML，包含四个真实后端；固化摘要为 `/tmp/otryx-build/quality-resolver-fix-evidence/summary.json`。随后新增的 cancel 回调重入测试未编入这次构建，其独立定向运行已 GREEN：20 tests、0 failures/errors/skips，证据为 `/tmp/otryx-build/resolver-cancellation-red.log` 与 `resolver-cancellation-green.log`。两次结果分别记账，不能据此宣称最终代码已跑过新的完整 605-test 构建。本轮修复已完成各自范围的本地闭环；三项修复仍须在新准确 Head 的实际门禁中验证，下一 Head 的远端门禁尚未触发。

### 4.2 本地验证记录与各代码阶段的适用边界

| 检查 | 当前证据状态 |
| --- | --- |
| Core 完整测试 | 首个 Head 提交前为 231 tests；Resolver success/failure 完成顺序修复后的完整 reactor 中为 234 tests，0 failures/errors/skips，见 `quality-resolver-fix-evidence/summary.json`。随后新增的 cancel 回调重入测试不在该次结果内。公开入口/有限预算、旧 revision 复活 LKG、启动回滚及共享证据计数器的原有回归继续保留 |
| 最终 Resolver 取消回调重入 | RED 为 Resolver 8 tests / 1 error；最终 GREEN 为 Resolver 8、Mapping 4、Manifest 4、Kit 4，共 20 tests / 0 failures/errors/skips。原始日志为 `/tmp/otryx-build/resolver-cancellation-red.log`、`/tmp/otryx-build/resolver-cancellation-green.log`；这是独立定向验证，不加到 604 的全量计数中 |
| 清理门禁、rolling 与 Quickstart Python 夹具 | 9 / 21 / 29 个单元测试实际通过；原始日志为 `cleanup-tests.log`、`rolling-tests.log`、`quickstart-tests.log`。Quickstart layout 与独立 migration readiness 检查也通过 |
| 共享 Etcd preflight / 比较结果校验 / 进程预算 | 最新 18 个 Python 测试通过，见 `comparison-process-green.log`；旧脚本忽略 TERM 的进程卡住有独立 RED。HTTP 与受控进程夹具只验证脚本失败传播和限时回收，不是 Etcd 或 RPC 功能实测 |
| `check_project.py` | 本轮实际通过，根代理最终复核也通过；最近 Java 元数据检查为 339 文件 / 0 findings。此前日志为 `repository-check.log` |
| 文档与脚本静态检查 | 86 个 Markdown 的结构/链接、两组中英文 README、22 个 shell 通过，见 `document-workflow-check.log`；最后 CI 专项检查 20 个无重复键 YAML、146 个 run 块、17 个 PR checkout、6 组 main/PR 触发镜像与实际归档路径均通过，见 `ci-gate-static-review.json`。生产 Java 魔法字面量检查 239 文件 / 0 findings，见 `magic-literal-check.log` |
| 本轮依赖脚本 / workflow 修复 | 根代理独立复核 15 个依赖门禁 Python 测试全部 GREEN，见 `dependency-gate-green.log`；两个修改 workflow 严格 YAML 无重复键、31 个 shell run 块通过 bash -n，准确 benchmark 来源仅在三个目标 step，见 `/tmp/otryx-build/workflow-review-139-fix.json` |
| Mermaid / SVG 实际预览 | 本地未找到仓库固定的 Mermaid CLI；结构/语法检查不能替代渲染。首个 Head 的 Documentation Previews 已实际成功，见 4.1；后续提交须重新核验 |
| Java 观察 CLI / 全 reactor quality | 首个 Head 提交前为 601 tests；Resolver success/failure 完成顺序修复后的 fresh `clean install -Pquality` 已 exit 0，604 tests、0 failures/errors、4 个条件 skips、92 份 XML，包含四个真实后端，见 `/tmp/otryx-build/quality-resolver-fix-evidence/summary.json`。该次不包含随后新增的 cancel 回调重入测试；定向新增用例与下一 Head 远端门禁单独记账 |
| 全 reactor style 与报告完整性 | 最终取消修复后的隔离源码 `clean install -Pstyle-audit -DskipTests` 已 BUILD SUCCESS，39.978 秒；`--enforce-zero` 确认 29 个模块报告、333 个 reactor Java 文件、0 findings，见 `/tmp/otryx-build/checkstyle-139-fix.json`。376 个 Java/POM/Checkstyle 输入 SHA-256 是当时与工作树一致；随后独立 comparison POM 已修补，因此不能称当前 376 项全部匹配。Java 及 main reactor 输入未改变，comparison 构建另见 4.3。此前 `style-snapshot-final.log`、`checkstyle-final.json` / `.md` 属于更早阶段 |
| 四后端共享合同 / 原生 `0→10→0` / Chaos | 首个 Head CI 已补齐 Etcd/Nacos Chaos 与 Consul ACL/Chaos，但 Eureka 共享契约暴露竞态。Resolver success/failure 完成顺序修复后的 604-test 本地构建已再次覆盖四个真实后端，保留 4 个条件 skips；该结果不包含随后 cancel 回调重入补充，也不能替代下一 Head 的 Eureka/restart 等远端门禁。共享契约仍为各后端 10 个不同客户端、30 个真实契约，Memory 为 1 个共享 Registry |
| 四后端 process / Nacos chain/revision/mTLS | 本地 8 场景实际通过，见 `local-e2e-summary.log`。首个 Head 的四后端矩阵又实际执行全部 process / chain / revision，见 4.1。普通 CI 因 reactor 失败，后续 Nacos restart 与 mTLS 等步骤未执行；本地结果不能替代这些远端剩余门禁 |
| 应用级五组 rolling | shell 失败传播夹具与真实两版本 RPC 均有证据；首个 Head 的实际五组矩阵已成功，具体 SHA、run 和 artifact 见 4.1 |
| RPC comparison 独立 JVM | comparison 最小 POM 修补后重新安装当前 Core、clean package，并实际通过真实 Etcd 的独立 JVM smoke：OTRYX 47,361 / Dubbo 28,036 次成功，均 0 errors，exit 0、拥有的进程全部回收；见 `/tmp/otryx-build/comparison-bom-fix-evidence/smoke-process-status.json` 与 `smoke/`。这是 working-tree 验证，即使结果 JSON 的 git_sha 仍为首 Head，也不能当成该提交或新 Head 的精确产物；source-provenance.json 记录补丁来源。本地短测量不构成性能排名或 #146 正式验收 |
| 已迁移 benchmark / 10k smoke 工具链 | 5 份 JMH smoke JSON 及 10k 逻辑调用者、请求 3 秒的 soak 已实际运行，结构校验通过；见 `benchmark-smoke-139.log`、`soak-smoke-139.log` 与 `v2d2-smoke-report-139/`。soak 错误均为过载拒绝，错误率 99.8185%，仅得 `REPORT_ONLY`，不是 SLO 通过。既有环境 Markdown 脚本的反引号替换错误再次复现，保留为 #146 工具整改项；原始 properties/JSON 独立存在 |
| Jetcd / Vert.x 依赖兼容 | 真实 Etcd EventLoop 注册用例在旧依赖下 1 test / 1 error，修复后 1 test / 0 errors，并在提交前完整 quality 构建再次通过；日志及 XML 为 `etcd-context-red` / `etcd-context-green`。后续 comparison 依赖回退已通过真实 reporter RED 定位并修补；工作树单根 reporter GREEN 与原三根覆盖范围分别见 4.3，不能称所有依赖无漏洞 |
| 准确 PR Head 及合并后 main | PR #160 已创建，首个 Head 有三项关闭门槛缺口；修复中的新提交与 main 合并均未在本记录中发生 |

### 4.3 依赖门禁真实 RED/GREEN 与源码范围

`/tmp/otryx-build/dependency-review-evidence-1/manifest.json` 记录基线 `793e942287f16677d56091f0ce45c61ac461c137`、首 Head `585b569175c048144b603863cfa1ef9401410157`、当时拟合并树 `c9fc354f27412d8c1d56bdd216dd922e2de94e4e`。main 29、comparison 4、Eureka fixture 1，共 34 个 POM 的双边 SBOM 构建与 OSV 提取实际完成。固定 reporter 真正返回 RED：新增 43 个 unique advisory ID、44 个 occurrence、8 个 package，定位到 comparison 导入主 BOM 后的 Netty/Jackson 回退。这证明修复后的提取门禁能暴露真实问题，不能把该三根结果写成通过。

随后 comparison POM 修补的本地实测位于 `/tmp/otryx-build/comparison-bom-fix-evidence/`。`verified-comparison.json` 与 `vulnerability-diff.json` 证明固定 reporter exit 0、新增漏洞差异为空。双边 scan exit 1 都是已有漏洞结果：外部包数 baseline 66 → candidate 89，两侧各有 24 个 vulnerable-package occurrence，绝不是零漏洞扫描。报告仅规范化来源路径，原始 SBOM / OSV 结果、各自 SHA-256 与扫描状态独立保留。

这个 GREEN **仅针对修补后的 comparison 工作树单根**，不是新提交，也不是补丁后完整三根门禁。`source-provenance.json` 明确记录此范围；fresh Core、已安装 Core 与 comparison shaded runtime 中的 Resolver.class SHA-256 三份完全相同，见 `verified-comparison.json`。真实 Etcd smoke 与拥有进程回收同时成功，见 `smoke-process-status.json`。新准确 Head 仍须重新构建完整三根依赖输入、执行真实差异审查及其他相关 CI；不把原拟合并树覆盖和后来工作树单根 GREEN 拼成未执行的新 Head 全绿。

本地已准备固定官方服务版本：Etcd 3.5.21、Consul 1.20.6、Nacos 3.2.4，Eureka 使用仓库 Boot 3.5.4 / Cloud 2025.0.0 服务端夹具。Nacos readiness 路径为 `/nacos/v3/admin/core/state/readiness`；旧路径的 404 不代表可继续测试。服务端进程必须与实际测试处于可控生命周期内，不假设不同执行会话之间后台进程持续存活。readiness 成功仅表示可以开始验证。

Etcd 外部 endpoint 的测试不能控制外部服务重启时，条件性跳过必须单列；CI 仍使用可控容器执行必要故障门禁。测试基础设施限制与产品缺陷分别记录，不能为了绿色删掉验收项。

rolling 有界停止先在旧脚本复现失败：忽略 TERM 的实际进程夹具触发 20 秒外层超时，见 `rolling-owned-stop-red.log`。修复后同一夹具通过，八个故意忽略 TERM 的业务夹具进程均被 KILL 并回收，五组仍全部执行。测试将 `ROLLING_COMPAT_STOP_SECONDS` 设为 1 秒；生产默认 35 秒，配置仅允许 1–35 秒并在启动子进程前移除。该测试只证明 shell 生命周期，不是实际 RPC 或优雅注销证据。

## 5. 下一步与恢复命令

先核对 Git 状态、准确 Head 和本节日志，再执行尚未完成的项。604-test 完整 quality、随后最终取消版本的独立 20-test GREEN、Java style、15 个依赖门禁 Python 测试、31 个 workflow shell 块以及 comparison 工作树的真实 smoke / 单根依赖差异均已有结果，保持各自代码范围；避免并行启动第二个 Maven。下一步由根代理记录修复提交 SHA、推送 PR #160，并核验新准确 Head 的完整三根依赖审查与所有相关工作流；下一 Head 的远端门禁尚未触发。Maven 质量与风格、真实后端、跨 JVM、rolling 和 mTLS 都是合并门槛。

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

四后端环境与独立 JVM 命令以[验收指南](../reference/application-registry-acceptance.md)为准。不要通过选择旧基线、静态成员或 Memory 替身绕过失败。继续在同一完整 #139 PR #160 中修复，将通过、失败、跳过和未运行项分开；新准确 Head 的相关门禁全部通过后才合并，随后核查 main 实际工作流清单。#140 目前只有实施准备草案，未开始仓库实现。

## 6. 中断与回滚

遵循 [AGENTS.md](../../AGENTS.md)：单个 Maven、CI 或远程 MCP 步骤持续无进展超过十分钟，停止无意义等待并记录最后输出、错误、Run/Job、下一条命令；不擅自取消他人远端任务，不把工具不可用写成通过。

当前在 PR #160 首个提交之上存在并行修复，修复提交 SHA 尚未写入本记录；保留各代理修改，不使用硬重置覆盖他人内容。PR 尚未合并；若将来合并后 main 失败，停止后续 Issue 合并，按实际故障提交隔离修复或 revert PR。回滚目标是最后已验证主线；稳定引用、生产服务、发行版本与权限均不作自动修改。
