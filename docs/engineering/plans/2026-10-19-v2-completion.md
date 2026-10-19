# V2 剩余工作连续实施计划

> 按已批准的 Issue #127 及 #139–#147 实施契约执行。每个工作包独立提交 PR，准确 Head 检查通过后合并，再核验主线。此计划记录续写顺序，不重新定义或削减原有验收条件。

**目标：** 完成应用级注册唯一入口及其生产化能力，保持可运行、可验证、可追溯的主线。

**架构：** Provider/Consumer/BOTH 共享应用成员身份，业务接口由 Mapping、Directory 和不可变 Manifest 解析。数据面读取原子本地快照，控制面负责有界刷新和故障恢复。

**技术栈：** JDK 21、Spring Boot 3.5.4、Maven、Wire v1，版本固定为 `1.0.0-SNAPSHOT`。

**规格：** [应用注册设计](../../design/application-registry-v2.md)、[总控 #127](https://github.com/Ryan-Guizhou/otryx-rpc/issues/127) 及各子 Issue 的验收条款。

## 全局约束与检查重点

- 不保留接口级 Registry SPI、双模式配置、旧路径或虚构 Consumer RPC Endpoint。
- 单一 Starter、四模块 Quickstart；中文 Javadoc、英文日志、具名常量；Core 不反向依赖 Adapter。
- 不改版本、不发布、不创建 Tag、不改权限；已验证稳定分支不变。
- 验证启动中关闭、迟到成功、失联后旧 revision、慢取消和异步资源释放；不以取消 Future 代表业务已退出。
- 验证每个配置入口的真实行为；测试脚本自身失败必须传播，未运行/被跳过的外部测试单列。
- 共享环境 smoke 不作为固定硬件的吞吐或容量证据；缺少 #146 条件时保留阻塞状态。

## 1. #139：应用级迁移清理与统一真实契约

**文件边界：** Core `OtryxRpcClient`、`OtryxRpcServer`、`ServiceDirectory`、`RpcDirectoryFreshnessOptions`；Registry 旧 SPI/Factory/实现；所有受影响测试、benchmark、Starter、Quickstart CLI、scripts、CI 和中英文文档。

**接口：** 保留 `applicationControlPlane(resolver, membership, namespace)` 和 `applicationMembership(coordinator)`；删除旧 Builder 入口。应用 SPI 与 Wire v1 不变。`ServiceInstance` 仅是已验证 Provider 的本地 LB 载体。

- [x] 先运行公开入口删除及有限目录预算的失败测试，并核验未修改主线的基线。
- [x] 删除旧生产体系，将所有有价值的异常/并发测试迁至应用级链路。
- [x] 使用 Core test-jar 共享合同：Memory 单 Registry；四个真实后端各 10 个独立客户端、30 个真实接口，报告原生成员数、SPI 操作、订阅与收敛时间。
- [x] 保留应用级五组 rolling、跨 JVM process/chain/revision、mTLS、真实后端与 Chaos 门禁；旧模式基线明确拒绝。
- [x] 创建完整 [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160)，首个 Head 为 `585b569175c048144b603863cfa1ef9401410157`。
- [x] 首个 Head 的四后端 process/chain/revision 矩阵、实际五组 rolling、独立 JVM comparison smoke、Java Style Audit 与 Documentation Previews 已成功，并读取实际日志/artifact。
- [x] 首轮普通 CI 的 benchmark 环境变量泄漏、Eureka 共享契约的 Resolver Future/状态竞态，以及 Dependency Review 因 SNAPSHOT 提取失败产生空结果却显示绿色的问题，已完成各自范围的本地修复验证；新准确 Head 仍待远端闭环。
- [x] Resolver success/failure 完成顺序修复后的 fresh quality 返回 exit 0：604 tests、0 failures/errors、4 个条件 skips、92 份 XML，包含四个真实后端；此条不包含随后新增的 cancel 回调重入测试。
- [x] 最终 cancel 回调重入补充已有独立 RED/GREEN：Resolver 8、Mapping 4、Manifest 4、Kit 4，共 20 tests，0 failures/errors/skips；不与 604-test 全量结果相加。
- [x] 最终 Java 源树隔离 style 已成功：29 reports / 333 files / 0 findings，376 个输入 SHA-256 当时与工作树一致；随后 comparison POM 修补，不把此前 hash 核对扩大为当前 376 项仍匹配。Java / main reactor 输入未变，check_project 和 Java 元数据 339 / 0 也通过。
- [x] 依赖门禁 15 个 Python 测试、两个修改 workflow 的严格 YAML / 31 个 shell 块静态复核通过；真实三根 SBOM/OSV 检出 comparison 回退后，修补工作树的 comparison 单根固定 reporter exit 0、新增漏洞差异为空，真实 Etcd smoke 也成功。
- [ ] 形成修复提交；在新准确 Head 重新核验完整三根依赖审查及其他必需门禁，全部通过后才合并，再核验合并后 main。

**当前状态：PR 已创建，尚未合并，#139 尚未完成验收。** 首个 Head 的 14 条 workflow 已结束，普通 CI 与 Eureka 专项失败；其余 12 条显示 success，其中 Dependency Vulnerability Review 的空结果不构成漏洞验收。本轮三项修复已完成各自范围的本地闭环。根代理的 Resolver success/failure 完成顺序修复后完整 quality 已成功，固化证据为 `/tmp/otryx-build/quality-resolver-fix-evidence/summary.json`；随后新增的 cancel 回调重入测试不在这次 604-test 构建中，其最终版本的独立 20-test GREEN 见 `/tmp/otryx-build/resolver-cancellation-green.log`，对应 RED 为 `resolver-cancellation-red.log`。最终 style 39.978 秒 BUILD SUCCESS，聚合证据为 `/tmp/otryx-build/checkstyle-139-fix.json`，输入 hash 匹配只限 comparison POM 后续修补前。上述结果不等同最终代码已重新完成 605 项全量测试；下一 Head 的远端门禁尚未触发，不能预填通过。

依赖门禁的三根双边实测覆盖 34 个 POM，来源为原 base / 首 Head 的拟合并树 `c9fc354f27412d8c1d56bdd216dd922e2de94e4e`，固定 reporter 实际检出新增 43 个 unique advisory ID / 44 个 occurrence / 8 个 package。comparison POM 随后优先恢复 Vert.x 4.5.34 原有的 Netty 4.1.138.Final / Jackson 2.21.6；修补后的 working-tree 单根 comparison reporter exit 0、新增差异为空。外部包数 66 → 89，两侧仍各有 24 个 vulnerable-package occurrence，不能写成零漏洞。证据为 `/tmp/otryx-build/comparison-bom-fix-evidence/verified-comparison.json`、`vulnerability-diff.json` 与 `source-provenance.json`；它们不代表补丁后的三根或新 Head 已通过。

同一工作树重新安装 Core、clean package 后，真实共享 Etcd 的 OTRYX / Dubbo 独立 JVM smoke 分别为 47,361 / 28,036 次成功、均 0 errors，exit 0 且拥有的进程全部回收，见该目录 `smoke-process-status.json` / `smoke/`。fresh、installed、comparison shaded runtime 中的三份 Resolver.class SHA-256 一致。smoke JSON 的 git_sha 仍记录首 Head，实际补丁来源以 source-provenance 为准；不得据此宣称首 Head 或下一 Head 精确验证。15 个依赖脚本测试与 31 个 workflow shell 块的静态复核见 `dependency-gate-green.log`、`workflow-review-139-fix.json`。

| 首轮证据 | 实际结果 |
| --- | --- |
| [普通 CI · 39221767557](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767557) | job 级 `OTRYX_RPC_BENCHMARK_COMMIT` 被 Starter 严格校验识别为未知配置，reactor 失败；本地已限制为 preflight/matrix/soak 三个 step 的准确 Head 输入，不放宽 Binder，待新 Head 实测 |
| [Eureka 专项 · 39221767693](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767693) | 共享契约要求 HEALTHY 时观察到 NOT_READY；Resolver 本地已修复为先发布状态，再对外完成 Future，最终取消回调有独立 GREEN。首轮专属 restart 未执行，仍需新 Head 补验，不能由其他成功场景覆盖 |
| [Dependency Review · 39221767605](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767605) | 未发布的 `1.0.0-SNAPSHOT` 依赖无法完成提取，results 为空；工作流显示 success 也不能算验收。实际提取 / 覆盖 / 失败关闭门禁本地已修复，并通过真实 reporter RED 定位 comparison 依赖回退；后续单根 working-tree GREEN 的范围见上文 |
| [四后端 JVM · 39221767646](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767646) | 四后端 × 三场景成功；44 份快照与 12 份 summary，原 Consumer 进程恢复/晋级/回滚、真实额外方法和三 JVM 链均有日志 |
| [Rolling · 39221767575](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767575) | 五组组合及 rollback 全部 PASS；归档实际 N=`793e942287f16677d56091f0ce45c61ac461c137`、N+1=`585b569175c048144b603863cfa1ef9401410157` |
| [Comparison · 39221767757](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767757) | 真实共享 Etcd、两框架独立 JVM、0 errors、validator 成功；只有 2 秒预热/3 秒测量的 smoke，不属于 #146 正式性能验收 |
| [Style · 39221767391](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767391) / [Docs · 39221767470](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39221767470) | 首个准确 Head 实际成功，后续 Head 仍需重新核验相关检查 |

完整失败原因、Job/Artifact、已通过/跳过/未执行边界与恢复命令见[工程断点](../agent-governance-checkpoint.md)。本地旧绿色结果与首轮专项成功都不能替代修复后提交的验收。

## 2. #140：安全默认值与身份/元数据可信

依赖 #139。当前仅完成 Core、Registry、Starter/Quickstart 的实施准备草案，未开始仓库实现。统一 Core Builder/Starter 显式安全意图，生产失败关闭；绑定真实 mTLS 身份与目标应用/命名空间，授权先于 DTO 反序列化。测试误配置、假冒、错误 CA、缺失 Authorizer、恶意 Manifest、Codec 不兼容、身份/权限过期及敏感日志；待 #139 合并且 main CI 核验完成后实施独立 PR。

## 3. #141：连接、编码与取消资源上界

依赖 #140。检查 TLS 预握手配额、Frame/Codec 字节峰值、慢消费和异步取消；每项新增边界先有可复现失败用例，再验证拒绝与实际释放。记录内存、Future、Lease、线程及连接，禁止以无证据零拷贝变更替代预算控制。

## 4. #142：Router SPI

依赖 #139。在 Manifest/Schema/健康过滤之后、LB 之前实现应用/tag/zone/gray/weight 本地路由快照。验证 10%/90% 容差、tag 隔离、空候选错误、热更与回滚，Consumer 成员绝不进入候选集。

## 5. #143：Interceptor SPI

依赖 #139。实现有序 Consumer/Provider 扩展链，区分逻辑调用与 retry attempt；失败、Deadline、取消只发布一次终态，阻塞扩展不运行于 EventLoop。验证上下文恢复及不可绕过 mTLS/授权，并进行低开销 profiling。

## 6. #144：动态治理快照

依赖 #142、#143。治理仓库发布具版本、预编译的本地快照；原子更新/回滚、有界 LKG、应用/方法级规则；安全硬限制优先。验证并发更新无部分态、无远端热路径 IO、无效或不可信配置不生效及配置故障恢复。

## 7. #145：观测与诊断

依赖 #139–#141。为应用成员、Mapping/Manifest、陈旧目录、过滤/路由、鉴权与资源高水位提供受限指标及只读诊断。验证失败可复现、脱敏、固定标签上限、NOOP 和 Quickstart 排障步骤。

## 8. #146：受控性能验收

依赖 #139–#141。复核既有 evidence 工具并补齐应用注册缩减数据、复现入口和拒绝伪证据测试。正式完成必须具备固定物理主机、测前 SLO/退化阈值、AB BA AB 六次运行、每次至少 60 分钟 soak 及完整原始指标；条件缺失时明确 BLOCKED，不能关闭为 completed。

## 9. #147 与 #127：最终收口

按各 Issue 的依赖逐项核验 Maven Reactor、Java 风格、Javadoc、JaCoCo、漏洞/SBOM、真实 Registry、文档图链和发行 dry-run。依赖未完成时保留受影响验收项。同步准确 PR/Head/main/CI 证据及下一可执行命令；交付以实际源码与工作流结果为准。
