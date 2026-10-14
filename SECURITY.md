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

Spring Boot 接入要求明确设置 `otryx.rpc.security-profile`：`TRUSTED_DEVELOPMENT` 仅用于可信隔离环境；`NETWORK_MTLS` 强制双向 TLS 与 Fory 严格 DTO 白名单，并在业务参数解码前校验客户端证书身份。自定义 `RpcAuthorizer` 可进一步按 ServiceKey/MethodId 授权。

**残余边界**：纯程序化 `new ForyRpcCodec()` / `RpcCodecRegistry.fromSpi()` 仍可使用历史可信兼容模式；调用方需要显式配置严格 Codec 和安全 Transport，不能把该入口视为生产安全默认。TLS 不能替代服务和方法权限，证书签发方信任范围应按租户收敛。详见[实施契约](docs/engineering/plans/2026-10-12-open-source-quality-upgrade.md)。

## Secret 规则

- 仓库不得提交真实密码、Token、私钥、生产证书。
- Runtime 日志不得输出 Registry Password、Private Key 或敏感业务参数。
- TLS 失败不得自动降级到 PLAINTEXT。

## Release 修复

安全修复遵循最小兼容改动原则。若 Wire/API 必须发生不兼容变化，将使用新的兼容边界并在 Release Notes 中明确说明。



`NETWORK_MTLS` 模式校验最终生效的 `RpcCodecRegistry`、`RpcTransportOptions` Bean，确保不绕过严格 Fory 白名单和 mTLS。授权拒绝返回 Wire v1 `BAD_REQUEST`；客户端 Deadline 到期会结束等待，但不保证第三方授权任务已物理停止。


## 供应链与依赖漏洞治理（U14）

- Dependabot 按 Maven / GitHub Actions 每月分组更新，避免每个补丁分别触发 CI。
- OSV Scanner 在 PR 中比较目标分支与提交分支，阻断新增的已知依赖漏洞（不仅限 Critical）；不依赖 GitHub Dependency Graph；High 和其他风险仍须结合可达性、部署和修复版本审查。
- 无法立即修复时，维护者记录组件及版本、可达范围、缓解措施、责任人和复核日期，不允许永久例外。
- RC/GA 候选应附准确源码 SHA、CycloneDX SBOM 和各发布模块的 SHA256。Unsigned dry-run 不能等同于 GPG 签名或 Central 上架。
- 不因检查失败而关闭安全扫描；实际发行、权限修改或部署需独立授权。
