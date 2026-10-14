# OTRYX RPC 贡献与编码规范

OTRYX RPC 目前为 **1.0.0-SNAPSHOT 首发前工程**。我们以简单易用、高性能、高并发、高可用及可维护性为目标。首发前不要求兼容历史未发布版本，但仍严格要求当下的协议正确性、安全、资源生命周期和 Consumer/Provider 互操作。

## 单一规范入口

- 仓库 Agent、PR 授权和 CI 边界： [AGENTS.md](AGENTS.md)
- 正式 Java 编码、Peach Cloud 类型 Javadoc 与英文日志规范： [docs/engineering/java-coding-standard.md](docs/engineering/java-coding-standard.md)
- 结构设计与能力族： [docs/design/project-structure.md](docs/design/project-structure.md)
- MCP 使用与安全边界： [docs/engineering/agent-mcp.md](docs/engineering/agent-mcp.md)

## Java 变更基本规则

- Java 21；UTF-8 无 BOM、LF、4 空格、最长 120 字符；禁止通配符 import。
- 所有新增/本次修改的手写 Java 类型须提供中文 Javadoc 与真实的 @Author、@Version、@CreateTime（yyyy/M/d HH:mm），不得编造历史。
- 重要 API/SPI 方法说明参数、返回值、异常、线程归属、阻塞/取消、资源所有权和关闭责任。
- 保留原生 LoggerFactory；英文 SLF4J 参数化日志，正确分级、不泄露 Token、秘密凭据、Metadata 和用户异常原文；不在正常 RPC 热路径逐请求记录 INFO。
- Core 不直接依赖第三方 Adapter；Executor、队列和连接/缓存须有明确有界策略；不得阻塞 EventLoop。
- 纯格式/Javadoc/日志文案和运行行为重构必须分 PR。任何运行行为、公共契约或协议更改需独立设计与测试，不因未发版而忽略故障场景。

## 提交前验证

~~~bash
python3 scripts/check_project.py
python3 scripts/test_java_conventions.py
python3 scripts/test_java_doc_metadata.py
python3 scripts/check_java_doc_metadata.py --audit --report target/java-javadoc-metadata-audit.json
./mvnw -B -ntp clean verify -Pquality
~~~

如果修改了 Java 类型，PR 工作流还会对变化文件执行 --changed 元数据门禁；完整 Checkstyle 规则由 Java Style Audit 执行。PR-02 先建立存量审计，PR-03 至 PR-06 分模块整改，PR-07 统一开启全量元数据零违规门禁。

测试结果必须来自本次提交；无法执行的测试说明原因，不使用历史绿色结果替代。涉及性能路径需提供适当的 JMH/JFR/端到端对比。文档若涉及用户可见功能，同时同步中英文 README。

## 分支与协作

小 PR 从最新 main 建立分支，明确 Goal、Scope、Non-goals、Risk、Verification。相关 GitHub Actions 全部通过方可依据授权合并；合并后核对 main CI。稳定快照不追加提交、不强推。Release、删除远端资源、仓库权限调整及数据库写操作需要单独授权。

## 社区和安全

请遵守 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)；安全漏洞按 [SECURITY.md](SECURITY.md) 中的私密途径报告，不在公开 Issue 中发布敏感细节。


## 依赖与首发候选贡献规范（U14）

- OSV Scanner 新增依赖漏洞告警先评估可达性与修复方案；例外须由维护者记录风险、责任人、期限，不通过关闭检查绕过。
- Maven/GitHub Actions 依赖使用 Dependabot 月度分组，以减少无意义的 CI；不要在同一个 PR 混合无关升级。
- `first-release-dry-run.yml` 只提供无凭据 RC/GA 构建证据，**不包括发布、签名或 Tag**。代码合并不授权实际发行。
- 所有提交须通过准确 Head 和合并后 main CI；Fixed Runner 受控性能证据与共享 Runner Smoke 严格区分。
