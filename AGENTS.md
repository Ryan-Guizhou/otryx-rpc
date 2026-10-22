# OTRYX RPC — Repository Agent Contract

本文件为 Cursor、Codex 和其他 Agent 的统一工作入口。唯一事实源：当前分支源码、测试、POM、构建结果 > 当前依赖版本官方文档 > 仓库技术文档和 CONTRIBUTING > 共享 Skills > 运行时适配 > 历史对话。不得根据旧资料声称某功能已实现。


## OTRYX 1.0 首发前治理契约（2026-10-10）

- 本仓库尚未正式发布，当前根 Maven 坐标为 com.peachsoft.otryx:otryx-rpc:1.0.0-SNAPSHOT。
- 用户已批准 V1.1 治理：允许调整未发布版本的 Java API、SPI、模块、配置、序列化及协议设计，**不对历史开发版本承担兼容义务**；任何行为性变化仍须独立设计、验证、分 PR 实施。
- 工程规范与 Javadoc/日志治理沿用既定 PR 阶段；Issue #76 由独立 PR 完成全仓语义字面量审计、整改与八类高置信度位置的 --enforce-all CI 门禁。未覆盖的容量数值、状态映射等仍需人工审查，不将自动零发现当作全语义证明。
- 所有模块以现有 Maven Reactor、实际源代码和测试为准，不为尚未实现的 Registry/Serialization Provider 创建空模块。
- 协议健壮性、线程归属、资源回收、TLS/鉴权、Consumer/Provider 互操作和高并发安全**始终是正确性要求**，即使对旧版不承担兼容责任也不得降低。
- 已验证的稳定分支保持不动：stable/peach-rpc-1.0.1-pre-otryx-2026-10-10 和 stable/agent-quality-2026-10-09。
- 本次治理不包含发行、删除远端资源、修改仓库权限、生产配置或数据库写入。

## 工作引擎与授权

Task Intake → Source/Impact Analysis → Complexity Gate → Implementation Contract（复杂任务）→ 用户确认 → Implement → Verify → Mainline PR → 精确 Head 的相关门禁通过后合并 main（默认不再次构建 main）。

- 简单、确定且不涉及公共契约的局部修复可直接实施。
- 公共 API、Wire/Codec/Schema、SPI、配置、线程/资源、鉴权、安全、模块架构、兼容性重构属于复杂任务。实施前交付 Goal、Scope、Non-goals、Compatibility、Constraints、Migration、Verification、Risks，获用户确认方可执行。对已授权的当前任务不重复确认。
- **本轮明确授权（2026-10-09，V1.1 于 2026-10-10 进一步确认）**：OTRYX RPC 既定 Agent 代码治理工作可以从 `main` 创建短生命周期分支，提交 PR，经该 Head SHA 对应的相关质量门禁全部通过后合并到 `main`，不再堆叠 Draft PR。此授权**不适用于**发布、删除远端资源、修改仓库权限、数据库写入或超出本轮实施契约的改动。
- `stable/agent-quality-2026-10-09` 固定在 SHA `8f051b78d74978c5417394ed2cd22467e077c15f`，作为已验证的合并前基线。禁止在该分支追加提交、强制推送或将其用作日常开发分支，详见 [稳定基线与主线工作流](docs/engineering/stable-baseline.md)。
- 分阶段小 PR，**只以 `main` 为 Base**。每次核对当前 PR Head SHA、Base SHA、变更影响、必需检查与可合并性，确认后 squash。合并后只记录新 main SHA，不自动重新运行同一套 Maven/Chaos/RC-GA 工作流。如验证后 Base 有实质变化，须先更新 PR 并重新验证，不能沿用历史绿色结果。

## 中断保护与断点续写

- 单个 Maven、CI、远程 MCP 或其他待完成步骤持续无进展超过 **10 分钟**时，停止等待并中断可安全中断的本地操作。对于 GitHub 远端工作流，只记录当前 run/status，不为缩短等待擅自取消其他人的 Job。
- 中断时记录：任务目标、最后一个成功步骤、PR/分支/Head SHA、未通过的检查、日志/错误、下一条可执行命令、回滚方式和影响边界。
- 断点记录存放于 `docs/engineering/agent-governance-checkpoint.md` 或当前 PR 描述；恢复时必须重新查询最新 SHA 与 CI，不依据旧的对话状态盲目重复提交。
- 对确定性的代码缺陷先修复；遇到网络/工具访问失败最多做有限重试，仍不可用时如实报告，不循环等待、不伪造测试通过记录。
- 如当前步骤阻塞，但有无依赖的工作可开展，允许先处理独立任务并标记阻塞项；**不能将待完成工作描述为已完成**。

## Java 工程规范与首发前正确性红线

- JDK 21、Spring Boot 3.5.4、Maven 多模块；Java 4 空格缩进、UTF-8 无 BOM、LF、120 字符上限、禁止通配符 import，按 .editorconfig 和 Checkstyle 执行。
- 类型级中文 Javadoc 采用 Peach Cloud 元数据：@Author、@Version、@CreateTime（yyyy/M/d HH:mm）。元数据必须可证实，**不可批量填假作者、假版本或将治理时间伪装为创建时间**；无法核实的历史类型列入审计台账。
- 公共 API/SPI、复杂并发/资源和配置方法使用真实中文契约注释，描述参数、null、异常、线程/阻塞、取消、所有权、关闭及副作用。@since 只能用有历史证据的真实版本。
- 继续使用 SLF4J LoggerFactory；英文参数化日志、稳定白名单字段、合理级别，不为统一日志引入 Lombok。热点逐请求正常操作不记录 INFO，敏感异常/Metadata/Token/完整输入不得直接写入 Log、Trace 或 Metrics。
- Core 不得反向依赖 Vert.x/Nacos/Etcd/Fory/Spring 等 Adapter；参见 ArchUnit 架构门禁。
- EventLoop 禁止阻塞 IO、Thread.sleep、同步等待 Future 或可任意阻塞的业务回调；线程/取消/超时边界必须有测试。
- 新 Executor、队列、连接、Pending Map、inflight bytes 和缓存必须有容量/有界策略、拒绝、取消、超时、关闭和释放契约。
- 全仓 Java 生产代码遵守协议、配置、Header、SPI、Metrics、状态和容量/时序魔法字面量治理，优先局部具名常量或已有 enum/API；不得以全局万能 Constants 类、误报忽略或机械抽取普通文案绕过治理。见 docs/engineering/java-coding-standard.md。
- 不吞异常、不忽略 InterruptedException，不默认重试非幂等操作。纯规范 PR 禁止偷偷改变公开/私有运行语义；若需调整需另立行为 PR。
- 未正式发布不要求与 Peach RPC/旧 OTRYX 开发版本兼容；但所有修改后的协议、Codec、Schema/Type ID、Registry Metadata 和 Starter 装配必须当前版本内部自洽且可被测试证明。

## MCP 与权限

MCP 为证据补全工具，不是无限授权。
- CodeGraph 用于 symbol/caller/impact；Context7 仅用于依赖版本不确定的 API；GitHub 用于已授权 PR、Issue、CI 和远端读写；AgentMemory 不得覆盖仓库事实。
- MySQL 默认**不连接**：只有使用数据库真正授权的独立只读账号，并经用户主动启用后才允许访问；prompt 或 MCP 配置本身不能强制只读。不得使用有生产写权限的用户。
- GitHub 合并：仅当前已授权的 OTRYX RPC Agent 工程治理范围，允许在相关检查全部通过后合并到 `main`；所有其他合并仍须明确授权。删除分支、Release、数据库 DML/DDL、权限变更和生产配置写入始终必须另行授权。
- 不允许 Agent 自动批准未知第三方 MCP 或将凭据提交到源码。详见 docs/engineering/agent-mcp.md（本治理阶段加入）。

## Skills 与适配

唯一共享目录 `.agents/skills/<name>/SKILL.md`，不复制到 .cursor 或 .codex。只加载与任务相关的 Skill：
- `using-otryx-java-engineering`：命名、Javadoc、日志、基础代码结构。
- `using-otryx-compatibility`：当前 Wire、Schema、SPI、配置契约的正确性及变更影响分析（不要求兼容未发布旧版本）。
- `using-otryx-performance`：EventLoop、Admission、背压、JMH/JFR。
- `review-otryx-changes`：变更审查、违规分类、证据复核。
- 文档变化需同步 README.md 与 README.en-US.md 的相关结构和事实。

`.cursor/rules` 仅放薄入口，`.codex/config.toml` 仅放平台配置。不得出现多个互相漂移的规范事实源。

- Java 命名/公共类型与方法 Javadoc/通配符 Import/行长的 AST 检查见 [Checkstyle 严格门禁](docs/engineering/java-style-audit.md)。先运行 `mvn -B -ntp -Pstyle-audit -DskipTests install`，再执行 `python3 scripts/summarize_checkstyle_audit.py --json target/checkstyle-audit.json --markdown target/checkstyle-audit.md --enforce-zero`。该门禁同时验证主 Maven Reactor 所有模块报告完整性；不得为使 CI 通过而禁用规则或掩盖存量问题。

## Verification

- Java 元数据：python3 scripts/test_java_doc_metadata.py；python3 scripts/check_java_doc_metadata.py --audit --report target/java-javadoc-metadata-audit.json；变更的 Java 文件在 PR 中执行 --changed --base <base-sha>。
- Javadoc 存量处理：PR-02 记录全部遗留缺口，后续按模块逐项核实，PR-07 才启用 --enforce-all；Checkstyle 原有严格门禁不得关闭。
- 通用门禁：`python3 scripts/check_project.py`；`mvn -B -ntp clean verify -Pquality`。
- Agent/MCP：`python3 scripts/sync_agent_mcp.py --check` 与 `python3 scripts/test_agent_mcp.py`。
- Wire/Registry/Transport：相关单元与集成测试、Rolling Compatibility；性能改动另做 JMH/JFR 和可复现 Evidence。shared runner 的数值不等于生产容量结论。
- 人工检查不可自动静态证明的规则，记录例外原因；不为保持 CI 绿色关闭必要检查。
- 最终只陈述实际改动、PR、测试通过/失败与剩余风险。合并前核验 PR Head、Base、必需 Check 与可合并性；合并后记录 main 新 SHA，不再次执行同一套重型 CI。受控性能及正式发布仍需独立门禁；显式主线验证失败必须暂停合并并隔离修复。见 [CI 分层契约](docs/engineering/ci-gates.md)。

## V2 应用级注册强制约束（#127）

- Provider/Consumer/BOTH 都必须按应用级身份注册，纯 Consumer 没有 RPC Endpoint，也不得以假端口加入 RPC Provider 池。
- [应用级注册设计与当前迁移状态](docs/design/application-registry-v2.md) 是 V2 控制面目标和过渡范围的明确约束；新功能不得新增旧接口级 Registry 注册路径或兼容开关。
- #139 是彻底删除旧 ServiceRegistrar / ServiceDiscovery(ServiceKey) / Registry Key 与历史文案的最终强制门禁；未完成不得宣布 V2 已正式支持应用级注册。
