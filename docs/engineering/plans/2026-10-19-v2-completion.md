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

**文件边界：** Core Client/Server/Directory、Registry SPI/Factory/实现、共享合同及故障测试、benchmark、Starter、Quickstart CLI、scripts、CI 和中英文文档。

**接口：** 保留 `applicationControlPlane(resolver, membership, namespace)` 和 `applicationMembership(coordinator)`；删除旧 Builder 入口。应用 SPI 与 Wire v1 保持当前版本内自洽。`ServiceInstance` 仅是已验证 Provider 的本地负载均衡载体。

- [x] 删除旧公开注册入口，将有价值的异常、取消、并发和关闭断言迁至应用级链路。
- [x] Core test-jar 共享合同覆盖 Memory 与四个真实后端；各真实后端 10 个独立 Registry clients、30 个真实接口，分别报告原生成员、SPI 完成、订阅和收敛。
- [x] 四后端 process/chain/revision、五组 rolling、comparison smoke 以及真实后端/Chaos/ACL 门禁保留并实际执行；旧接口模式基线明确拒绝。
- [x] 完整 [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) 已提交；本批收尾补丁提交前核验 Head `3133c8fb1a39bb53b4618e0eb94c419a39f15775`，base `793e942287f16677d56091f0ce45c61ac461c137`。
- [x] 当前 Head 的 benchmark 环境变量作用域、Resolver Future/状态完成竞态和真实三根依赖审查已闭环；Dependency Review 的 34 个 POM/侧扫描有效，无新增漏洞差异，不代表零既有漏洞。
- [x] 完成 Consumer 重复 Greeting 故障恢复、Eureka standalone self-peer/缓存期限/重启断言、Nacos 发布可见性确认和重启日志竞态的整批修复及本地真实后端验证。
- [ ] 新准确 Head 所有相关门禁通过后合并，核验准确 main CI，再更新 #139/#127。

**当前状态：#139 尚未完成。** 本批补丁提交前核验的 Head 有 14 条 workflow 已结束，12 success、2 failure。普通 [CI 39228392049](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39228392049) 的 reactor 已通过 605 tests/0 failures/errors/18 条件 skips，但后续 Nacos Provider 停止时 Consumer 因本地超时退出；[Eureka 39228392137](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39228392137) 的 fixture self-replication 导致成员删除无法收敛。其他成功专项不能覆盖这两个失败路径。

本轮修复均已完成定向回归与独立审查：Nacos 仅发布一次，在 ACK 后用 3 秒总预算确认可见性；三个进程启动点先由父进程清空本轮日志，避免旧 ready 被误用。真实 quality 基线在隔离源码、依赖目录通过：638 tests/0 failures/errors/4 条件 skips、93 XML；最后追加测试变更后再次清缓存执行全量 style，严格聚合 29 reports/334 Java files/0 findings，元数据 340 类型和生产语义字面量 239 文件均无发现。Nacos process 及独立 revision 完整升级/回滚通过，Consumer 未重启，原预算未放宽。Eureka 初始失败后追加四个运行输入：fixture 读写缓存过期 1s、参数回归、workflow 和分阶段重启断言；受控真实缓存机制四案通过，最后 6 integration + 1 真实 JVM restart 均通过、进程确实更换、成员恢复且服务已清理。其余 489 个运行输入不变。原初始失败没有足够诊断来唯一归因缓存竞态，原始失败记录保留；fixture 参数不是生产默认缓存的收敛保证。准确新 Head/main CI 尚未发生。详细日志、历史失败、已执行/跳过边界及下一命令见[工程断点](../agent-governance-checkpoint.md)；不同工作树阶段的测试数不能相加或互相替代。

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
