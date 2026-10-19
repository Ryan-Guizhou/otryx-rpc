# OTRYX RPC 工程断点：#139 应用级注册最终迁移

> 更新时间：2026-10-19（UTC）。本批补丁提交前核验的 [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) Head 为 `3133c8fb1a39bb53b4618e0eb94c419a39f15775`，尚未合并，#139 尚未完成远端验收。该 Head 的 14 条工作流已结束，12 条成功，普通 CI 与 Eureka 专项失败。本轮四后端全量 quality、Nacos process 与完整 revision 切换/回滚已通过；Eureka 追加夹具缓存期限和重启断言修复后，6 项真实 integration 与 1 项真实 JVM restart 均已通过。下一步提交完整补丁，重新核验准确 Head 的全部相关门禁，再合并并独立核验 main。恢复时查询最新 SHA，不沿用旧绿色结果。

## 1. 当前任务与授权边界

仓库：[Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc)。当前工作包为 [#139](https://github.com/Ryan-Guizhou/otryx-rpc/issues/139)，范围见[应用级设计](../design/application-registry-v2.md)和[验收口径](../reference/application-registry-acceptance.md)。后续 #140–#147 按[连续实施计划](plans/2026-10-19-v2-completion.md)推进；后续安全、资源和正式性能能力不能提前算入 #139 完成。

用户已经授权一个 Issue 的完整 PR：本地验证 → 准确 PR Head 相关门禁全部通过 → 合并 main → 独立核验合并后的 main CI。不重复申请已批准的实现和常规验证；不发行、不创建 Tag、不删除远端资源、不修改权限、生产配置或数据库。

固定 `1.0.0-SNAPSHOT`、JDK 21、Spring Boot 3.5.4、Wire v1、唯一 Starter 和四模块 Quickstart。Provider、Consumer、BOTH 均使用应用级成员身份；纯 Consumer 没有 RPC Endpoint。ServiceKey 仅标识业务契约，ServiceInstance 仅是已验证 Provider 的本地负载均衡 DTO。旧 Registry SPI、双读双写和兼容开关已删除。目录默认陈旧预算 30 秒，必须严格为正且可转换为有限纳秒值。

## 2. 本批补丁提交前的准确基线与状态

| 项目 | 当前已核验状态 |
| --- | --- |
| 最后合并工作包 | #138，经 [PR #159](https://github.com/Ryan-Guizhou/otryx-rpc/pull/159) 完成 |
| 当前 main / PR base | `793e942287f16677d56091f0ce45c61ac461c137` |
| 工作分支 | `feat/v2-12-application-registry-contract` |
| 本批补丁提交前核验的 PR Head | `3133c8fb1a39bb53b4618e0eb94c419a39f15775` |
| 当前远端树 | `fbe99441da58c579ecbcafcb3d590bbe8e637699` |
| 当前 Head CI | 14 条已结束，12 success、2 failure；不是可合并状态 |
| 本轮工作树 | Consumer 重复 Greeting 超时恢复、Eureka self-peer/缓存期限/重启断言、Nacos 有界可见性确认和启动日志竞态均已修复，并有回归 |
| 本轮最终全量验证 | 隔离 quality 基线 638 tests、0 failures/errors、4 条件 skips、93 XML；可执行 JAR 完整性、真实 Nacos process 与独立 revision 全流程通过。随后追加的四个 Eureka 运行输入单独冻结：6 integration + 1 实际 restart 全部通过，493 输入前后不变，后端已清理 |
| 下一提交及其 CI | 尚未形成，不预填 SHA 或通过状态 |
| #139 合并后 main CI | 尚未发生 |

稳定分支保持不动：`stable/agent-quality-2026-10-09` 固定在 `8f051b78d74978c5417394ed2cd22467e077c15f`；`stable/peach-rpc-1.0.1-pre-otryx-2026-10-10` 保留。参见[稳定基线](stable-baseline.md)。

## 3. #139 已实现的完整范围

- Core、Starter、测试、benchmark 和遥测 E2E 都使用应用控制面；删除旧接口级注册 SPI、工厂、描述符和 Metadata 桥接，保留迁移后的异常、取消、关闭和资源断言。
- Core test-jar 共享合同覆盖 Memory 和四个真实后端。四后端各使用 10 个独立 Registry client 对象、30 个真实 Java 接口生成的 canonical Manifest；它们在一个测试 JVM 中，不等同于 10 个业务 JVM。
- 报告原生成员 `0→10→0`、SPI 调用/成功/失败/未完成、订阅活动/关闭、成员逻辑变化和目录收敛时间。报告在所有拥有的句柄和操作结清后输出。这些计数不代表服务端总 HTTP/gRPC、keepalive 或 Watch 流量。
- 清理门禁覆盖生产/测试、SPI 描述符、配置、脚本、benchmark 和当前文档；负向测试中的旧 API 字符串仅按精确位置放行，不能用来豁免真实旧用法。
- 独立 JVM process/chain/revision 统一后端与 namespace，四后端矩阵保留；Eureka 使用真实 Consul metadata。Rolling 实际执行 N/N、N+1/N、N/N+1、N+1/N+1、rollback 五组，旧接口模式在构建/启动前拒绝。
- comparison 的 OTRYX Provider/Client 使用真实共享 Etcd。Jetcd 与 Vert.x gRPC 对齐到当前 Vert.x 4；comparison 保留 Netty `4.1.138.Final`、Jackson `2.21.6` 的既有安全版本约束。
- 普通 CI 的 benchmark 来源变量仅作用于三个 benchmark step；Resolver 先原子提交状态并归还容量，再在锁外完成 Future；依赖门禁对 base/拟合并树的 main、comparison、Eureka fixture 三个 Maven 根执行真实 SBOM/OSV 覆盖和失败关闭审查。
- 根 README、Quickstart 中英文文档及应用注册设计、SPI、运行、迁移和后端指南已经同步。当前新增行为的文档与测试随本次追加修复一并验证。

## 4. 本批补丁提交前的远端证据

以下全部限定于 `3133c8fb1a39bb53b4618e0eb94c419a39f15775`。首个 Head `585b569175c048144b603863cfa1ef9401410157` 的详细历史见 [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) 和[该提交的断点文档](https://github.com/Ryan-Guizhou/otryx-rpc/blob/3133c8fb1a39bb53b4618e0eb94c419a39f15775/docs/engineering/agent-governance-checkpoint.md)，不再将其旧状态写成当前待办。

| 工作流 | 实际结果与剩余门槛 |
| --- | --- |
| [普通 CI · 39228392049](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39228392049) | Reactor quality 已成功：605 tests、0 failures/errors、18 条件 skips。随后 Nacos 独立进程场景停止 Provider 时，重复 Consumer 因本地 `RpcTimeoutException` 退出，导致后续恢复与后续步骤未完成。Reactor 成功不能代替整个 CI 成功 |
| [Eureka · 39228392137](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39228392137) | 共享 integration 6 tests、1 failure。Standalone fixture 将自身误当 peer，`replication=true` 重注册已删除成员；专属 restart 未执行。必须修复 fixture 并实际完成共享合同及 restart |
| [Dependency Review · 39228392127](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39228392127) | 完整三根双边审查成功，34 个 POM/侧。main 外部包 145→148、comparison 66→89、Eureka 119→119，无新增漏洞差异；既有漏洞仍存在，不能表述为零漏洞 |
| [四后端 JVM · 39228391983](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39228391983) | 四后端 process/chain/revision 矩阵成功；不能覆盖普通 CI 的独立故障路径 |
| [Rolling · 39228392108](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39228392108) / [Comparison · 39228391990](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39228391990) | 当前 Head 的五组 rolling 和短时 comparison smoke 成功；后者不是 #146 正式性能验收 |
| 其余门禁 | Agent Governance、Java Agent Quality、Etcd Chaos、Nacos Chaos、Consul V2/Chaos/ACL、Java Style Audit、Documentation Previews、Release Readiness 均 success；新 Head 仍需重新核验相关门禁 |

首轮空依赖扫描被误标绿色的问题已由当前 Head 的真实三根审查闭环；不能继续把它写成当前失败。当前真正未通过的是普通 CI 与 Eureka 两项，以及后续修复提交尚未进行的准确 SHA 验证。

## 5. 本轮恢复修复与本地证据

证据位于当前会话 `/workspace/scratch/2d1e5f300fc7/evidence/` 和 `/tmp/otryx-pr160-2d1e5f300fc7/`；这些不是永久 CI artifact。运行过的源码以 base Head、工作树差异及输入 SHA-256 区分，不能把未提交补丁验证冒充准确 Git 提交的验证。

| 项目 | 已知事实与后续验证 |
| --- | --- |
| Consumer 故障恢复 | 仅显式 repeat、普通幂等 Greeting 的新增分支容忍本地 `RpcTimeoutException`；one-shot、relay、details、授权拒绝验证保留原有超时语义。已有 55 项定向测试证据和独立审查。其他错误继续按原有类型处理：现有 `RpcUnavailableException` 仍可重试，不能宣称所有 TLS 握手失败都会立即终止；类型化安全拒绝属于 #140 |
| Eureka fixture | 显式 bind/hostname=`127.0.0.1`、defaultZone 使用实际端口以排除 self-peer。新增 Bash 真实入口的 Python 回归覆盖默认 8761 和自定义 18762；2 项通过，旧实现 RED，硬编码端口的变异也被拒绝。新测试已接入 Eureka workflow |
| Nacos 发布确认 | 真实 Nacos 3.2.4 接受并落盘后，异步配置视图可能暂时返回空。仅发布一次，在 ACK 后启动 3 秒单调时钟预算；只对空结果按剩余预算读取，最多间隔 25ms，读取和 canonical 校验完成后再次检查期限。内容冲突、缺失权威 MD5、SDK 传播出的异常和中断立即失败，迟到值不接受。新增 11 项定向测试通过，其中两个核心用例有 RED 证据；真实 Nacos integration 也已在隔离 reactor 通过 |
| Nacos SDK 边界 | SDK 可能将部分非 403 异常转换为本地 snapshot 回退；没有 snapshot 时仍可能返回空。本实现不把空值当作后端健康证据，也不声称能识别 SDK 隐藏的每个错误，最终受同一确认预算约束。不可变 Manifest 不改成 CAS，Mapping 原有 MD5 CAS 不变 |
| 启动日志竞态 | process 的 Provider 重启、fresh Consumer probe 和 revision baseline 回滚三个启动点均先由父进程清空本轮日志，再创建后台进程。新增两项测试用受控重定向屏障覆盖三个边界，旧实现出现三个预期失败；完整 31 项脚本回归通过。随后修正测试夹具传播 waiter 失败状态的单行代码，两个新回归及 TLS 拒绝分类共 3 项再次通过；两组结果分别记录 |
| 本轮首次真实全量 | `pr160-local/quality.log`：Core 235 tests 成功，后续 Nacos integration 9 tests/1 error；整个 reactor exit 1。原始 XML 已复制到 `pr160-local/failed-quality-tests/`，全部四个自有后端已停止。它不是最终全量成功证据 |
| 构建环境修复 | `pr160-local-final/quality.log` 在 Etcd 测试发现阶段扫描到 `.rsync-tmp` 类路径而失败。后续构建移至 `/tmp/otryx-pr160-2d1e5f300fc7/source`，612 个源码文件逐一校验 SHA-256；隔离 Maven 缓存的 580 个 JAR 均通过 ZIP CRC 校验。不得将失败运行中可能受同步影响的 XML 总数作为验收总数 |
| 验收 driver 修复 | 隔离运行 `validation/quality.log` 的四后端模块均成功，随后 Spring 因未知配置 `otryx.rpc.e2e.restart.nacos` 失败。仅将本地 driver 的 `OTRYX_RPC_E2E_RESTART_NACOS=false` 限定到 process 脚本；保留产品严格配置校验。修正后的运行使用独立 `validation-scoped/` 证据目录 |
| 静态检查 | 最终 `check_project.py`、`git diff --check`、Java 元数据 340 类型/0 findings、239 个生产 Java 文件语义字面量检查/0 findings 已通过；Eureka workflow 无重复 YAML key，PR/push 路径对称，9 个 Bash 块语法通过 |
| 全 reactor style | 先前 `clean install -Pstyle-audit -DskipTests` 成功。追加 Chaos 测试后，最终再次清除自有 Checkstyle 缓存并执行全 reactor `-Pstyle-audit -DskipTests validate`，严格聚合 29 reports、334 Java files、0 findings，确认包含更新后的 Chaos 文件。29 原始 XML 与 376 个输入哈希保存于 `validation-eureka-final/style-final-reports/`；其中仅 Chaos 源码相对前次 style 变化。Eureka driver 中增量缓存生成的 0-file style 报告不计通过，`validation-review.json` 明确记录替代证据；截断或缺项报告同样不接受 |
| 最终全量 quality | `validation-scoped/quality.log` 的 `clean install -Pquality` exit 0，耗时 279.764s；638 tests、0 failures/errors、4 条件 skips、93 XML。四后端均实测 30 契约、10 个独立 Registry client、原生成员 `0→10→0`。Provider/Consumer 本轮新生成 exec.jar 均通过 ZIP CRC 和 JarLauncher 入口检查 |
| Nacos process | `validation-scoped/nacos-process.log` exit 0，44.628s；Provider 停止及重启后目录收敛，长驻 Consumer 未重启便恢复调用 |
| revision 首次补验 | `validation-scoped/nacos-revision.log` exit 1，53.257s；首个 baseline Provider 未在脚本原有 45 秒就绪预算内输出 ready，尚未进入 revision 切换，后续 Eureka restart 因 driver 顺序未执行。保留原日志，复用同一批 JAR 单独运行剩余场景，不将未执行的场景记为通过 |
| Nacos revision 完整补验 | `validation-remaining/nacos-revision.log` exit 0，97.373s；baseline、两 revision 共存、额外契约真实调用、晋级、回滚共存、回滚完成及全部 Provider 停止均成功。原 Consumer 进程持续运行，45 秒单次就绪预算未放宽；输入及三个可执行 JAR 的前后哈希一致 |
| Eureka 初始快照失败 | `validation-remaining/eureka-restart-tests/` 原始 XML 为 1 test、1 failure、0 errors/skips，失败位于 `initial.await(25s)`。两成员已在服务端登记 UP，但测试内 stop/start、失联通知与恢复断言均未执行；driver 的夹具启动/清理不能替代 restart 验收。一次同配置短探针正常得到完整快照，未复现该失败；不能据此确认缓存竞态或归因资源争抢 |
| Eureka 缓存机制与夹具修复 | `eureka-cache-probe/` 使用 fixture 内实际 Eureka 2.0.4 `ResponseCacheImpl`、Guava 33.0.0 和真实 JSON codec，仅 mock registry 数据。四个受控 load/invalidate 时序确认空值或单成员旧值可跨失效写回：180s 配置在 1.2s 后仍旧，1s 配置重新读取双成员。没有执行真实 HTTP 注册，也没有等待满 180s；此证据不能唯一归因此前失败。夹具显式设读写缓存过期 1s；两项真实 Bash 参数回归先 RED、再 GREEN，生产 Adapter 和 25/65s 预算未改 |
| Eureka 重启断言修复 | `INITIAL→OUTAGE→RECOVERY` 单向阶段隔离初始错误与实际故障；重启后由健康同步确认恢复，允许成员内容相同。最后主动 lookup 必须包含 Provider/Consumer，Provider 池精确相等，空集不能通过。失败记录有界计数、最后快照匹配及最多四层异常类型；离线 JDK21 编译和 7 组回调/32 条断言通过 |
| Eureka 最终真实补验 | `validation-eureka-final/` 的两个新 XML：integration 6 tests、restart 1 test，均 0 failures/errors/skips；Maven 分别 22.339s、18.247s，restart 测试本身 12.50s。真实 Server PID 从 150478 变为 151115，新 Server 日志记录两成员 UP，最终主动成员/路由断言通过；自有服务已停止、8500/8761 端口关闭 |
| 固定输入完整性 | `validation-scoped/source-input-review.json` 核验原始 493 个运行输入均未变化。driver 的重新枚举集合包含 Shade 新生成的 `otryx-benchmarks/dependency-reduced-pom.xml`，因此原 summary 的集合相等字段为 false；构建日志明确记录该生成物，不把它误判成 Java/配置/脚本在测试中被修改 |
| 剩余补验的清理 | `validation-remaining/summary.json` 单独记录 Nacos revision 成功及 Eureka 失败；其整体 `passed=false` 保留。所有自有 Eureka、Consul、Nacos 均已停止，原始 493 个输入及三个 JAR 的前后哈希一致 |
| 最后补丁边界 | 相对完整 quality 基线，只追加 Eureka control、launcher 回归、workflow、Chaos 测试四个运行输入及文档，其余 489 个运行输入不变，生产 Java 未再修改。`eureka-final-source-change.json` 记录四文件 SHA；真实 Eureka 最终运行前后 493 个输入全部一致 |
| 尚待完成 | 准确新 Head 的全部相关 CI、合并及准确 main CI |

本地没有 Docker，因此真实验证使用固定版本的原生 Etcd、Consul、Nacos 和独立 Eureka fixture，并通过现有测试端点配置进入真实 integration。外部端点模式条件跳过的容器故障、ACL 和集群场景仍由各自真实 CI 补齐，不删除或弱化 gate。

## 6. 下一步与恢复命令

1. 整批补丁的确定性 RED/GREEN、脚本回归、静态检查和独立审查已完成；完整 quality、Nacos process/revision、Eureka integration/restart 的实际范围见上表，没有未解决的 Critical/Important 审查项。
2. 三阶段入口分别为 `verify_pr160.py --repo /tmp/otryx-pr160-2d1e5f300fc7/source --mvn /tmp/otryx-runtime-2d1e5f300fc7/mvn --evidence <new-directory>`、`verify_pr160_remaining.py --evidence <new-directory>` 和 `verify_eureka_final.py <new-directory>`，均位于 `/tmp/otryx-pr160-2d1e5f300fc7/`。前两阶段绑定旧的冻结输入；最后四个 Eureka 输入已变化，不可盲目复用旧 driver 的哈希前置条件。仅为未解决风险或门禁需要补验，使用全新证据目录并保留先前失败记录。
3. 构建期间不在同步工作区执行 Maven，不修改冻结源码；最终核对源码哈希、JAR、原始 XML 和进程清理。已有完整 style 与静态检查仅在相应输入保持一致时复用，不用缓存的缺项报告替代全量门禁。
4. 更新本记录的实际测试数、跳过项和原始证据。一次性提交到当前 PR；提交前核对远端仍是预期 Head，禁止覆盖他人修改。
5. 按新 Head SHA 查询所有相关 workflow、job、step 和关键 artifact，全部通过才以预期 Head 合并；然后独立查询准确 main SHA 的全部相关检查。不能从 PR 绿色推断 main 绿色。
6. 更新 #139 与 #127 的完成状态及证据；依赖满足后从新 main 开始 #140 的完整独立 PR，继续后续工作包。

## 7. 中断保护与回滚

任何步骤连续 10 分钟无进展，记录准确命令、SHA、run/status、错误和下一可执行动作，停止可安全中断的本地操作；不取消其他人的远端 workflow。会话中断后先检查最新 GitHub 状态及现存进程/端口，不盲目复用旧后台服务或 `target/`。

本次变更尚未合并，只在 #160 短期分支推进。需要回滚时采用可审查的 revert PR，不移动稳定分支、不强推覆盖他人提交。#146 缺少已验证的专用物理环境和预先确定的 SLO 时保持未验收；#147 只进行获准的发行 dry-run，不创建实际发布。
