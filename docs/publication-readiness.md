# OTRYX RPC 公开发布与品牌权利核查

> 更新日期：2026-10-14。本文记录可验证的工程事实与尚未满足的外部条件，**不是商标法律意见、域名所有权证明或 Central 账号授权结果**。

## 一、工程与仓库状态

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| GitHub 当前仓库 | 已更名为 `Ryan-Guizhou/otryx-rpc` | https://github.com/Ryan-Guizhou/otryx-rpc |
| 旧版稳定快照 | 保留，禁止覆写 | `stable/peach-rpc-1.0.1-pre-otryx-2026-10-10`，SHA `a4175635aef8702cab613b9c672ca7edbaae6a94` |
| OTRYX API/命名空间迁移 | 已合并 PR #53 | `2f9706b02d179c30e611536e8d901e62c76c352d` |
| Maven 坐标 | `com.peachsoft.otryx:otryx-*` | 根 POM 与模块 POM |
| 当前源码版本 | `1.0.0-SNAPSHOT`，不是 GA | `docs/release-status.properties` |
| 正式发布 | **未授权，暂不发布** | 见下方外部阻塞项 |

`otryx-rpc` 是目前真实的仓库 slug，不要继续将尚不存在的 `Ryan-Guizhou/otryx` 填入 SCM、clone 或使用指南。如果维护者以后主动改仓库名，须同步更新 POM、文档及仓库身份校验。

## 二、Maven Central Namespace（阻塞）

当前 Maven `groupId` 是 `com.peachsoft.otryx`。Sonatype 使用反向 DNS 进行身份验证：

- 如维护者控制 **peachsoft.com**，可以申请/验证 `com.peachsoft`，其授权通常覆盖 `com.peachsoft.otryx` 子命名空间。
- 也可以申请精确 namespace `com.peachsoft.otryx`，对应 DNS 为 **otryx.peachsoft.com**，必须证明对该 DNS 名称有配置 TXT 记录的权限。
- `peach.io` / `io.peach` **不能授权** `com.peachsoft.otryx`。此前文档里的这类对应关系已经纠正。
- 如果无法证明上述 DNS 的所有权，应由项目维护者明确批准**修改 Maven groupId 与 Java 包名**，或使用基于 GitHub 账号验证的命名空间（例如 `io.github.<GitHub 用户名>`，但不满足当前固定的 `com.peachsoft.otryx` 需求）。
- 本仓库不掌握 Central Portal 的组织、Namespace Verification Key、DNS 控制面或 GitHub Secrets，**目前不能证明发布权限已验证**。

**维护者需执行：** 登录 https://central.sonatype.com → View Namespaces → 添加并验证目标 Namespace → 按 Portal 生成的 Verification Key 配置指定域名 TXT → 等待状态变为 Verified，并确认当前发布账号拥有该 Namespace 的权限。不要把 Verification Key 或 Portal Token 提交到 Git 仓库。

官方说明：
- https://central.sonatype.org/register/namespace/
- https://central.sonatype.org/faq/namespaces-vs-groupids/
- https://central.sonatype.org/publish/publish-portal-maven/

## 三、OTRYX 名称与商标（阻塞）

**检索发现：**

1. Otryx Systems 在软件及数字解决方案相关业务中使用 OTRYX 名称：https://otryx.framer.website/
2. 第三方商标数据库显示，印度第 42 类存在 OTRYX（label/device）注册记录，申请号 **5533429**，登记权利人为 **Otryx Systems Private**。第三方记录不能替代官方实时注册簿：https://www.quickcompany.in/trademarks/5533429-otryx-label

**不能据此推断：** OTRYX 在所有国家均被他人独占，也不能推断本项目已经取得合法使用权。该记录为图形/标签商标，不应错误描述为已经核准的全球文字商标。

**维护者需执行：** 根据计划实际发行/推广国家、商品/服务类别（至少软件相关第 9、42 类），经官方商标数据库或合格知识产权专业人士核验同名/近似名称、权利状态、商品/服务重合及混淆风险；记录审查意见及采用/避让决定。适用的查询入口包括印度 Trade Marks Public Search、WIPO Global Brand Database，以及拟发行地区的商标局。未经确认不使用“已注册商标”“全球唯一”等描述。

## 四、吉祥物和图片素材（阻塞）

| 素材 | 来源已知情况 | 公共发布处理 |
| --- | --- | --- |
| 用户上传的原始科技水獭参考图 | 用户提供；原始创作者、第三方素材及授权链**尚未证实** | 不将原图作为正式商标素材直接入库或授权给第三方 |
| 对话中生成的水獭概念图/横幅 PNG | 基于用户指定视觉参考进行 AI 辅助创作 | 保留设计档和生成记录，审查与参考图及第三方品牌的近似度 |
| `docs/images/mascot/otti-main.svg` | OTRYX 项目矢量吉祥物稿 | 可作为开发期文档形象；正式标志需审核参考图权属 |
| `docs/images/brand/otryx-banner.svg` | 项目原创布局与标题设计 | 公开宣传前仍需审核名称及整体品牌 |
| `docs/images/architecture/` 与 `docs/images/flows/` | 根据当前架构手工制成可维护 SVG | 确认每个模块、箭头和技术说明与代码一致 |

**不得**将含有“10 万+ QPS”之类未经基准证据核实的 AI 概念海报纳入正式 README、官网或发行包。Logo、图像风格查重和版权检索结果不能仅凭一次图片搜索宣称“全球无雷同”。

维护者需要提供原始水獭参考图的创作或授权信息（原创证明、购买授权、允许改编及公开商用的协议，或相应替代稿），再进行最终品牌图形定稿。

## 五、发布安全与操作

- **代码质量**：所有需要的 PR/main CI 必须对准确 SHA 成功；独立进程 E2E、Chaos、性能证据和 Wire v1 兼容检查不能因品牌改名而绕过。
- **语义化版本**：`1.0.0-SNAPSHOT` 为开发版；升级 Java 包名、GAV 和部分公开 API 属于 Breaking Change；Wire v1 冻结键保持不变。
- **Central**：发布前确认已取得 `com.peachsoft.otryx` 命名空间使用权，Maven Central 坐标未被占用，生成主 JAR + Sources + Javadoc + GPG 签名，验证 POM/SCM/许可证/依赖元数据。
- **凭据**：使用 GitHub Repository Secrets 配置 `CENTRAL_USERNAME`、`CENTRAL_PASSWORD`、`MAVEN_GPG_PRIVATE_KEY`、`MAVEN_GPG_PASSPHRASE`；禁止回显或提交。
- **发布工作流**：历史 `.github/workflows/release.yml` 仅面向历史 1.0.x Patch；开发源码增加明确拒绝，不可用来发布 OTRYX 首发。独立 `.github/workflows/first-release-dry-run.yml` 提供 RC1/GA 候选构建、CycloneDX SBOM 和校验清单，**不签名、不创建 Tag、不上传 Central**。正式发布须另行授权和演练。
- **第一次发布**：建议 Central `autoPublish=false`，由维护者在 Portal 验证 Bundle 后人工确认发布。未经以上授权不要触发 Central/GitHub Release 或创建 GA Tag。
- **回滚**：保留旧版稳定分支。跨 Peach RPC 1.x 与 OTRYX 1.0 的 Type ID、Method ID、Schema Fingerprint 变化不等同于 Wire 协议级兼容保证。

## 六、发布前验收登记

- [x] GitHub 仓库更名已被核实，开发代码和 `main` CI 已验证。
- [x] POM / 入门文档的 canonical 地址已统一；自动检查防止文档回退。
- [x] 错误的 `io.peach` → `com.peachsoft.otryx` Namespace 推导已修正。
- [x] 商标风险、素材来源和对外技术宣传的边界已有文字记录。
- [ ] 维护者证实对 `peachsoft.com` / `otryx.peachsoft.com` 的 DNS 控制权限。
- [ ] Central Portal 的 `com.peachsoft` 或 `com.peachsoft.otryx` 状态为 **Verified**，且发布用户拥有对应权限。
- [ ] 完成 OTRYX 名称在目标地区的商标风险评估并确定使用方案。
- [ ] 核实水獭原始参考图/最终 Logo 的改编和公开使用权。
- [ ] 实施并验证 OTRYX 1.0 RC/GA 发布流水线、签名、上传和回滚演练。
- [ ] 由维护者授权实际公开发布。

**只有以上阻塞项完成，才能将“工程可构建”推进为“具备正式公开发布条件”。**


## 七、首发 Dry-run 边界（2026-10-14）

- `first_release_dry_run.py` 仅允许临时 Maven 版本 `1.0.0-RC1` 或 `1.0.0`；根 POM 和 `release-status.properties` 保持 `1.0.0-SNAPSHOT`。
- `test_first_release_dry_run.py` 覆盖非法版本、缺失 Sources、SBOM 版本漂移、无效 SHA 和开发状态变更时的拒绝行为。
- 签名验证、Maven Central Namespace Verified、品牌素材权属和实际发行授权不由 unsigned dry-run 代替。
- 每次候选运行应记录确切 Commit SHA、Workflow、完整 CI、SBOM 和 Checksum；**PR 合并不代表手动 RC/GA Dry-run 已执行**。

详见 [首发验收与供应链](reference/first-release-acceptance.md)。

**安全扫描实施说明：** GitHub Dependency Graph 尚未启用，因此改为 `dependency-review.yml` 中固定完整 Commit SHA 的 OSV Scanner PR Base/Head 差异扫描。新增已知漏洞阻断，原有风险仍需维护者登记；扫描失败时不能假定没有漏洞。
