# V2 剩余工作连续实施计划

> 按已批准的 Issue #127 及 #139–#147 实施契约执行。每个工作包独立提交 PR，准确 Head 检查通过后合并，再核验主线。此计划记录续写顺序，不重新定义或削减原有验收条件。2026-10-19（UTC）revision 4 提交前核验：#140 尚未完成；准确 Head 的原始执行结果与 run/check API 状态矛盾分别记录。

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
- [x] 完整 [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) 的最终 Head 为 `bbf07eace5c3409876be9da8f9bb73dbdccac721`；14 条工作流、18 项检查均通过后合并。
- [x] 合并后准确 main `783bf8115c9523e5667023678dafe659315b2b66` 的 12 条工作流、16 项检查独立核验成功；[main CI](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39242224065) 原始报告为 638 tests、0 failures/errors、18 条件 skips。
- [x] 更新 #139 为 completed，并更新 #127 当前工作包为 #140。

**当前状态：#139 已完成。** 原失败、修复和确切验收边界见 [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) 与[工程断点](../agent-governance-checkpoint.md)。依赖审查没有新增漏洞差异不代表零既有漏洞；共享 CI 短时性能数据不是 #146 正式验收。

## 2. #140：安全默认值与身份/元数据可信

依赖 #139，已经进入仓库实现和最终验证，独立分支为 `feat/v2-13-security-hardening`，基于已验证 main `783bf8115c9523e5667023678dafe659315b2b66`。Core Builder/Starter 显式安全意图、实际组件能力校验、真实 mTLS 应用/命名空间与有限身份绑定、DTO 解码前有限授权、Registry typed TLS/凭据和来源校验、安全 epoch 失效均已实现；中英文文档与 Quickstart 17 阶段已同步。

本地两组真实 Etcd 分别完成 19 项 fixture 自检及 9/2 项 Java IT。Etcd 分类修复后的 `build-34` 完整 reactor 已达 29 模块、821 tests、0 failures/errors、27 条件 skips，style 严格聚合 29 报告、400 Java、零发现；86 Markdown、54 Mermaid 和 5 SVG 已实际检查/渲染，当前图源不变。前一批真实 exec JAR 完成 17 个安全阶段，自有进程清理已复核。旧 TLS 正常连接用例的一次超时保留，两次有限诊断未复现，不放宽期限或臆造原因。

独立审查补齐 Registry 安全原因传播、namespace 原因码和 E12 重复参数的 RED/GREEN；Consumer 快照核验后的存活检查已有 17 项 mock GREEN，默认身份在 Registry 副作用前的 10 个准确 RED 已经修复，完整身份配置 38 项均通过。最终同一冻结输入在 `build-35/36` 再次通过两组真实 Etcd 19 项自检与 9/2 项 IT，在 `build-37` 独立 quality 完成 29 模块、源 Javadoc 零警告。`build-38` 使用最终 JAR 完成全部 17 阶段，62 个采样进程全部退出；样式与 Javadoc 分开验证。本地完整通过不等于 #140 completed，准确最新 Head 和合并后的 main 均须独立验证。详见[工程断点](../agent-governance-checkpoint.md)。

[PR #161](https://github.com/Ryan-Guizhou/otryx-rpc/pull/161) 首次 Head `4a147880983a53cab587980230276878155c84b9` 的 14 条工作流已结束，10 成功、4 失败。原因分别为共用 bootstrap 测试仍解包三项、Consul 两处 IT 夹具未显式选择安全 profile、Core 关闭测试未等待异步取消事件；在同一 PR 修复对应测试。21 项参数回归及 CI 全部 16 组 Python 自测已通过，两项取消测试正常实现通过，移除实际取消动作的隔离副本则两项准确失败。真实 Consul 随后实际运行九项 IT，暴露两项恢复失败：404 文本正文被提前当作 Metadata JSON 解析。新增回归准确 RED，最小修复后的 HTTP 安全/字节测试 10 项全部 GREEN，原字节、期限与安全拒绝边界保留。四后端矩阵实际完成 44 份快照和 12 份 summary；该首轮普通 CI、rolling、Consul Chaos 与 Nacos Chaos 当时未执行的范围单独保留；第二轮实际执行结果见下表，不放宽生产默认值或将跳过计为通过。

指定 Consul 文本回归的 `build-45/46` 初次夹具误命中 JDK 默认 404，未执行预设 Consul 文本 handler，这两次不能作为指定正文的证明；独立审查已纠正，`build-48/49` 用实际 handler 和到达次数断言重新完成准确 RED/GREEN。最小 HTTP 修复后的 `build-47` 真实 Consul 九项全部通过，两个恢复期限未放宽，输入与进程清理已核验；第二轮远端 Docker 故障门禁的实际执行另行记于下表。

历史 `build-30` E2E 运行在 15 个阶段通过后，错误 Registry 凭据负例出现成员确认超时，未观察到认证原因码，最后只读场景未运行。一次原预算、同批 JAR 的有限真实复现已观察到 AUTHENTICATION_REJECTED，未复现超时；随后 `build-32` 相同输入的完整 17 阶段通过，68 个采样到的进程均已退出。原失败保留，不修改预算或降低断言。追加 SDK 审查确认 AuthCredential 会将任意 Authenticate Future 失败包装成 UNAUTHENTICATED；`build-33` 四个准确 RED 后最小修复，`build-34` 全部八项分类测试 GREEN。历史最终 AUTH marker 本身不能反推服务端真实状态；修复后真实后端与完整 `build-38` 均已通过。

### 第二轮准确 Head 与 revision 3 当前状态

[PR #161](https://github.com/Ryan-Guizhou/otryx-rpc/pull/161) 第二轮准确 Head 为 `3949104b32662d754732098cfa618e4aa55ab230`。14 条工作流已经结束，12 成功、2 失败；首轮全部失败及本地 `build-45/46` 夹具错误继续保留，不能被后续成功覆盖。

| 原始证据 | 实际结果 |
| --- | --- |
| [普通 CI 39273378455](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378455) | 117 份 XML、825 tests / 0 failures / 1 error / 18 skips。唯一错误为 NacosRoundTripTest 的 bootstrap 遗漏显式开发 profile，触发 UNSUPPORTED_PROTECTION。该 run 后续受保护 Etcd 9 项、客户端证书 2 项、17 阶段 mTLS，以及 Memory/独立 JVM/A-B-C/revision/benchmark/soak 均未执行；不把历史本地成功补记为本次执行 |
| Registry 专项 | Consul 9 IT + 1 Docker Chaos + 1 ACL、Eureka 6 IT + 1 restart、Etcd/Nacos 各 1 Chaos，共 20 个目标测试实际通过，0 failures/errors/skips；9 份 artifact 的 digest/CRC 与原始报告已核验 |
| [四后端 JVM Matrix 39273378414](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378414) | 12 个 process/chain/revision 场景、44 份成员/路由快照实际通过；保留 Consumer 无 Endpoint、角色、Manifest revision、promotion/rollback 的真实断言 |
| [Rolling 39273378482](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378482) | 五组 N/N+1/rollback 的 RPC 实际通过；只认该普通开发 profile 的互操作证据，不扩称 mTLS、负载或未归档的逐 PID/JAR 完整证据 |
| [Comparison Smoke 39273378437](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378437) | OTRYX 4643 成功、795816 错误，错误率 99.4199578%，超过原 5% 门槛；Dubbo 26794 成功、0 错误。原异常仅记录 RpcUnavailableException 类型，首个状态/来源未记录，原因尚未唯一归因 |
| `build-50` 有限诊断 | 同原并发 16、2 秒预热、3 秒测量、256 字节及预算，官方 smoke exit 0，measurement OTRYX 7207/0 error、Dubbo 7144/0 error；warmup 捕获选点为空来源，未复现旧 CI 高错误率。helper exit 1 仅因两份 Shade dependency-reduced-pom.xml 新生成物，684 个冻结输入不变、64 个采样进程全退出、清理无错。diagnostic overlay 不能替代准确 Head，官方 PASS 与 helper FAIL 分开保留 |
| `build-51` 确定性 RED | ServiceDirectoryTest 20 tests / 3 failures / 0 errors / 0 skips，三项准确证明本地 publication 排队时的目录交接缺口；两个新增安全对照和原有 15 项通过。684 输入不变，清理无错误 |
| `build-52` 完整 Core GREEN | 最小修复后 62 份 XML、319 tests / 0 failures / 0 errors / 0 skips，Directory 20 项全通过；47.922 秒，684 输入不变、清理无错误。只补投影实际新 candidate，旧对象/epoch 仍拒绝、合法空集不沿用旧 Provider、LKG 原计时不延长，无远端 IO 或循环 |

`build-53` 使用全新自有 Nacos 3.2.4 实例、数据目录和动态端口，实际通过原 NacosRoundTripTest 的真实 RPC 及 Provider 停服断言：1 test / 0 failures/errors/skips，套件 13.367 秒、全过程 76.105 秒。仅 bootstrap 显式选择开发 profile，CSV 端点、namespace/group 和原 15 秒停服断言保留；684 冻结输入不变，Maven/Nacos 进程清理后已退出。

`build-54` 使用原版 Harness 与官方脚本，保持 16 并发、2 秒预热、3 秒测量、256 字节、15 秒 RPC 期限及 5% 门槛，实际得到 OTRYX 18104 成功 / 0 错误、Dubbo 21244 成功 / 0 错误。官方命令与 helper 均 exit 0，外层 71.689 秒；684 原输入不变，两份 Shade POM 明确归类为生成物，58 个采样进程及 helper 已退出。原始 JSON 仍携带基准 git_sha，实际修复输入由候选 tree 和完整 manifest 另行绑定；这是本地候选 smoke，不能改称准确新 Head 或固定硬件正式性能证据。

当前不能把这项确定的 Directory 交接漏洞写成旧 CI 全部 795816 错误的已确认根因。第三次提交已经发布，准确 Head 为 `e1ba9b1dc8d00667b849cd7632165cdfbc3d0814`，tree 为 `c37cd606e8e4ea83c14f9a5e1497c0d17684960c`。原始日志与 artifact 已证明实际执行；run/check API 仍有 queued/in_progress 非终态，与实际产物矛盾，不能据此记为未执行或全绿。

### 第三轮失败与 revision 4

[普通 CI 39279078819](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39279078819) 的 62 份 XML 为 319 tests / 1 failure / 0 errors / 0 skips。唯一失败是异步 Stage 占槽测试预期 OVERLOADED、实际 OK；Nacos Chaos 的上游 Core 构建也在同一测试失败，目标 Chaos 没有执行。普通 CI 后续模块及受保护 Etcd 9+2 项、17 阶段 mTLS 等验收也未执行，不由其他 job 的成功补记。

第三轮其余原始证据中，19 个 Registry 专项目标、四后端 12 场景/44 快照、五组 rolling 都通过。[原版 Smoke 39279078618](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39279078618) 在原参数与 5% 门槛下得到 OTRYX 27437/0 error、Dubbo 18315/0 error。Style 为 29 模块/400 Java/零发现；Agent Quality 为 406 文件/0 errors/26 warnings，元数据 406 类型/0 findings。文档实际渲染 54 Mermaid、5 SVG，并检查 86 Markdown。独立 Release Readiness 的 `clean verify -Pquality` 为 839 tests / 0 failures/errors / 27 条件 skips；Fory 一个 JMH pair 保持 REPORT_ONLY。每项 scope 独立记账，共享 CI 数据不是正式性能验收。

最小修复在两个相关测试中等待既有 `slowInvoked` 业务入口，再发送第二次调用。异步授权完成前，未结束的响应不能证明 admission 已取得；等待真实业务入口建立先后关系，仍能检测提前释放和 CPU worker 被异步 Stage 阻塞。保留全部结果、1 秒响应等待和 lease-release 断言，沿用现有 3 秒入口等待尺度。

`build-55` 的 62 份原始 XML 实际通过 Core 319 项，0 failures/errors/skips，两个目标方法均运行。28.114 秒、684 冻结输入哈希不变、自有 Maven PID 已退出、清理无错误。下一步在同一 PR 发布 revision 4，准确新 Head 的全部相关 CI 与合并后 main 的独立验证均为 **PENDING**。#140 继续保持未完成；#141 仅有只读准备。精确 run/job、原始 ZIP/XML 哈希和恢复动作见[工程断点](../agent-governance-checkpoint.md)。

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
