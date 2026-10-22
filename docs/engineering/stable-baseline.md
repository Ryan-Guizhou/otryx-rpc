# Peach RPC：稳定基线与 Mainline 研发规范

> **当前生效：2026-10-09。** 本文只适用于已获用户授权的 Peach RPC Agent 工程质量治理，不等于授权发布、删除分支、修改 GitHub 权限或写入生产数据。

## 1. 固定稳定基线

| 属性 | 当前事实 |
|---|---|
| Repository | [Ryan-Guizhou/peach-rpc](https://github.com/Ryan-Guizhou/peach-rpc) |
| Snapshot branch | [`stable/agent-quality-2026-10-09`](https://github.com/Ryan-Guizhou/peach-rpc/tree/stable/agent-quality-2026-10-09) |
| Commit SHA | `8f051b78d74978c5417394ed2cd22467e077c15f` |
| Source | `main`，合并 PR #27–#39 后 |
| 该 SHA 的 CI | [主 CI SUCCESS](https://github.com/Ryan-Guizhou/peach-rpc/actions/runs/37880262195) |
| Agent Governance | [SUCCESS](https://github.com/Ryan-Guizhou/peach-rpc/actions/runs/37880262218) |
| Stability scope | Java 21/Maven CI 与当前 Agent Governance 检查通过；**不代表**固定硬件的 10k 并发或正式性能验证完成 |

稳定分支**冻结**：不得用于提交新代码、日常功能开发或强推；不因为后续 main 新增功能而移动其引用。它保存一个可检出的已验证基线，不代表分支自身能够自动强制不可变（GitHub Ruleset/Protection 未由本轮修改）。今后如需另建稳定基线，应另建新的 `stable/` 分支并记录 SHA、测试证据和创建原因。

## 2. 新的单主线交付流程

用户要求后续 Agent 工程治理代码直接进入 `main`。在现有 CI 体系下，为保留代码审查、可追溯性和必要测试，采用**短生命周期工作分支 + PR 指向 main + CI 成功后立即合并**；不继续使用相互依赖的堆叠 Draft PR。

~~~mermaid
flowchart LR
    A["main 已验证 Head"] --> B["独立治理分支"]
    B --> C["代码与专项测试"]
    C --> D["PR base=main"]
    D --> E{"Head SHA 检查通过？"}
    E -->|是| F["核对 Base 与 Head 后 squash 合并"]
    E -->|否| G["修复并重新验证"]
    G --> E
    F --> H["记录 main 新 SHA"]
    H --> I["继续下一阶段；不重复完整 CI"]
~~~

每个阶段遵循：

1. 从最新 `origin/main` 建立短分支；先检查主分支是否已有正在运行或失败的关键检查。
2. 保持提交范围可审查；不要将注释/日志格式化与有行为变化的 RPC 核心重构混在同一个 PR。
3. 按变更范围执行 PR 快速检查、Java Reactor/Style 和必要的安全及 Registry/Transport 专项；普通开发不跑发布双候选或正式容量实验。参见 [CI 分层契约](ci-gates.md)。
4. PR 必须是 `base=main`，且 `head_sha` 与被验证的提交一致；遇到失败不得以此前 SHA 的绿色记录覆盖。
5. 当前 PR 的相关必需检查全部通过且 Head/Base 未发生会改变验收结果的变化时 squash 合并。合并后核对新 main SHA 即可，不重复触发主线完整 Maven/Chaos 检查。
6. 如果 Base 有影响拟合并树的变化，先更新 PR 并重新验证；显式运行的主线检查失败时应停止后续合并并提交修复/回滚 PR，不强推重写主线或稳定分支。
7. 用户对本轮工程治理范围的授权不自动扩展到公共破坏性变更、发布、删除或管理员配置。

## 3. 如何检查与回溯

从仓库根目录执行：

~~~bash
git fetch origin main stable/agent-quality-2026-10-09
git log --oneline --first-parent origin/main -n 15
git rev-parse origin/stable/agent-quality-2026-10-09
git diff --stat origin/stable/agent-quality-2026-10-09..origin/main
~~~

稳定分支当前期望 SHA 是：

~~~text
8f051b78d74978c5417394ed2cd22467e077c15f
~~~

输出不一致时，先查远端分支修改记录并中断自动合并；不擅自删除/覆盖分支。确需复现稳定版本时，可从固定 SHA 创建**新的工作分支**，不直接修改稳定快照：

~~~bash
git switch -c investigation/stable-agent-quality 8f051b78d74978c5417394ed2cd22467e077c15f
~~~

## 4. 十分钟阻塞断点

如果单次 CI/Maven/MCP 等远程步骤超过十分钟持续无进展：记录 PR、Head SHA、Run ID/Job/最后错误和下一步，再停止等待，不无限轮询或自动取消他人的作业。可安全进行的独立工作可以继续，但不能在必需检查缺失时合并。

断点位置：[Agent 治理台账](agent-governance-checkpoint.md)。全部当前已知遗留问题需按该文档逐项更新，并与真实 GitHub 提交一致。
