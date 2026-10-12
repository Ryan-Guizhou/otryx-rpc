# Security Policy

## 支持版本

OTRYX 当前仅有 `1.0.0-SNAPSHOT` 开发源码，尚未发布 GA；以下是公开支持状态，不代表历史稳定分支已被修改。

| Version | Supported |
|---|---|
| OTRYX `1.0.0-SNAPSHOT` | Development; no published GA support claim |
| OTRYX `1.0.x` GA | Not yet published |
| OTRYX `0.x` | Not supported |

## 报告安全漏洞

请不要通过公开 Issue 披露未修复的安全漏洞。

优先使用 GitHub 仓库的 **Private vulnerability reporting / Security Advisories** 提交：

- 受影响版本；
- 复现条件；
- 影响范围；
- PoC 或最小复现（如果安全）；
- 可能的修复建议。

维护者会先确认问题，再协调修复与披露时间。

## 安全范围

OTRYX RPC 安全边界包括：

- Wire Protocol 输入校验；
- TLS/mTLS；
- Hostname Verification；
- Certificate/Private Key/CA material；
- Registry Credential；
- Metadata 长度与保留键；
- Retry/Replay 风险；
- 依赖漏洞；
- Release Artifact 完整性。

当前代码中，Fory 可信兼容模式与可选 PLAINTEXT 均存在，不能仅因支持 TLS/mTLS 就推断框架默认安全。严格反序列化策略、可信身份与应用授权链属于首发质量改造工作包，详见[实施契约](docs/engineering/plans/2026-10-12-open-source-quality-upgrade.md)。

## Secret 规则

- 仓库不得提交真实密码、Token、私钥、生产证书。
- Runtime 日志不得输出 Registry Password、Private Key 或敏感业务参数。
- TLS 失败不得自动降级到 PLAINTEXT。

## Release 修复

安全修复遵循最小兼容改动原则。若 Wire/API 必须发生不兼容变化，将使用新的兼容边界并在 Release Notes 中明确说明。
