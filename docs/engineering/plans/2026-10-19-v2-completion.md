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
- [ ] 当前应用级五组 rolling、跨 JVM process/chain/revision、mTLS、真实后端与 Chaos 门禁均保留；旧模式基线明确拒绝。
- [ ] 本地质量/风格/文档/集成通过后一次提交完整 PR；准确 Head 与合并后 main 分别核验。

## 2. #140：安全默认值与身份/元数据可信

依赖 #139。统一 Core Builder/Starter 显式安全意图，生产失败关闭；绑定真实 mTLS 身份与目标应用/命名空间，授权先于 DTO 反序列化。测试误配置、假冒、错误 CA、缺失 Authorizer、恶意 Manifest、Codec 不兼容、身份/权限过期及敏感日志，随后提交独立 PR。

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
