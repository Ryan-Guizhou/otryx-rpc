# OTRYX RPC 工程断点：#140 安全默认值与身份可信

> 更新时间：2026-10-19（UTC，revision 4 提交前核验）。[#139](https://github.com/Ryan-Guizhou/otryx-rpc/issues/139) 已由 [PR #160](https://github.com/Ryan-Guizhou/otryx-rpc/pull/160) 合并并完成准确 main 验证。当前 [#140](https://github.com/Ryan-Guizhou/otryx-rpc/issues/140) 的 [PR #161](https://github.com/Ryan-Guizhou/otryx-rpc/pull/161) 尚未合并。第三轮准确 Head `e1ba9b1dc8d00667b849cd7632165cdfbc3d0814` 已实际执行：原版 comparison smoke 和多项真实后端通过，普通 CI 与 Nacos Chaos 前置 Core 构建在同一异步 admission 测试失败。run/check API 仍有 queued/in_progress 非终态，与准确 Head 的原始日志和产物不一致，不能记成未执行或全部成功。revision 4 仅为两个相关测试补上真实业务入口屏障；冻结 Core 319 项全部通过。最新 Head 和合并后 main 仍待验收，#140 保持未完成。

## 1. 任务与授权边界

仓库：[Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc)。按[连续实施计划](plans/2026-10-19-v2-completion.md)完成 #140–#147，每个 Issue 独立提交完整 PR：实现、测试和中英文文档一起验证，准确 PR Head 的全部相关门禁通过后合并，再独立核验准确 main 的全部相关门禁并更新 Issue 与 [#127](https://github.com/Ryan-Guizhou/otryx-rpc/issues/127)。

用户已授权连续实施和正常 PR/合并流程，无须每个工作包重复申请。保持 `1.0.0-SNAPSHOT`、JDK 21、Spring Boot 3.5.4、Wire v1、唯一 Starter 和四模块 Quickstart；不发行、不创建 Tag、不删除远端资源、不修改权限、生产配置或数据库。已有稳定分支保持不变，参见[稳定基线](stable-baseline.md)。

Provider、Consumer、BOTH 均使用真实应用成员身份；纯 Consumer 没有 RPC Endpoint。ServiceKey 仅标识业务契约，ServiceInstance 是已验证 Provider 的本地负载均衡 DTO。旧接口级 Registry SPI、双读双写和兼容开关不能恢复。

## 2. 已完成基线与当前分支

| 项目 | 已核验状态 |
| --- | --- |
| 最后完成工作包 | #139，经 PR #160 完成 |
| PR #160 最终 Head | `bbf07eace5c3409876be9da8f9bb73dbdccac721` |
| 该 Head 门禁 | 14 条工作流、18 项检查均成功 |
| 合并后 main / #140 基线 | `783bf8115c9523e5667023678dafe659315b2b66` |
| 该 main 门禁 | 独立核验 12 条工作流、16 项检查均成功；[普通 CI](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39242224065) |
| #140 工作分支 | `feat/v2-13-security-hardening` |
| #140 PR | [#161](https://github.com/Ryan-Guizhou/otryx-rpc/pull/161)，尚未合并 |
| #140 首次 Head | `4a147880983a53cab587980230276878155c84b9`；14 条工作流结束，10 成功、4 失败，不能合并 |
| #140 第二轮 Head | `3949104b32662d754732098cfa618e4aa55ab230`；tree `b7fd007c92c433bf83947fe885f8c5c6df2866e5`，14 条工作流结束，12 成功、2 失败，不能合并 |
| 第二轮失败 | 普通 CI 的 NacosRoundTripTest bootstrap 遗漏显式 profile；comparison smoke 超出原 5% 错误率门槛，原始失败原因尚未唯一归因 |
| 第三轮准确 Head | `e1ba9b1dc8d00667b849cd7632165cdfbc3d0814`；tree `c37cd606e8e4ea83c14f9a5e1497c0d17684960c`，原始执行结果见第 7 节；API 状态仍不一致 |
| 第三轮失败 | 普通 CI 与 Nacos Chaos 前置构建的 `asyncStageShouldRetainAdmissionUntilCompletion`：预期 OVERLOADED、实际 OK，测试没有等待慢业务真正取得 admission |
| revision 4 / 合并后 main | 两处业务入口屏障的 `build-55` 完整 Core 319 项通过；新 Head、tree 及其独立完整门禁 PENDING，合并后 main 尚未形成 |

#139 最终 main 原始报告为 638 tests、0 failures/errors、18 条件 skips；style 严格聚合 29 模块且无发现，文档实际渲染 52 个 Mermaid 与 5 个 SVG。真实 Registry 合同、四后端独立 JVM 矩阵、常规快照和五组 rolling 均已核验。依赖审查没有新增漏洞差异，但既有依赖告警仍存在；共享 CI 短时 benchmark 不能作为 #146 正式性能证据。

#139 历次失败、修复与临时证据保留在 PR #160 及[原断点文档](https://github.com/Ryan-Guizhou/otryx-rpc/blob/bbf07eace5c3409876be9da8f9bb73dbdccac721/docs/engineering/agent-governance-checkpoint.md)，不再作为当前待办。

## 3. #140 已实现、仍待整体验收的范围

- Core Builder 与 Starter 要求显式安全 profile；未指定意图失败关闭。NETWORK 校验实际 Transport、Codec 和 Registry 能力，UNKNOWN 不能由配置声明代替。实际受限自定义 Codec 不受未使用的默认 Fory 配置误拒绝。
- mTLS 证书 DER 指纹绑定真实 ApplicationKey 和有限有效期；验证目标应用、命名空间、双向 TLS、实际连接与 Session、证书及绑定时间。类型化安全拒绝不进入普通重试，也不污染 Endpoint 健康统计。
- Provider 在 DTO 反序列化前执行有限授权。公共结果完成前归还私有决策资源；调用者取消只取消私有决策，授权器真实工作体和原始 Stage 都结束后才归还工作容量。
- Registry 使用 typed TLS/凭据、实际来源能力和固定脱敏错误。Eureka 成员与 Metadata 来源分别校验；Manifest、Mapping、namespace 和安全 epoch 贯穿目录快照，安全拒绝不能继续沿用旧成功快照。
- Etcd 真实客户端支持受保护通道、凭据和有限操作预算；Nacos 当前 SDK 无法证明的 NETWORK/TLS 组合在创建 SDK 前拒绝。各后端仅接收自己的非安全配置键。
- Fory STRICT 使用真实类型约束与契约解码证明。现有对象图估算不是 #141 的硬内存上限，不能提前算作完成。
- Quickstart 增加分离的 RPC/Registry PKI、受限 Etcd 角色、有限身份/授权和 17 个安全阶段。独立只读 Observer 验证真实成员与路由，不登记虚假成员；配置和密钥文件有明确大小、格式和权限限制。
- Python 验收入口通过可验证归属的进程组管理自有 Etcd、Java 和 Observer，等待真实进程退出后再回收私有材料；Linux `/proc` 与 PID 视图一致性是该入口的运行前提。
- 根 README、Quickstart 中英文、配置、Starter、SPI、安全和 Registry 参考文档已同步。未执行或未通过的验证范围继续明确保留。

接口及限制见[安全指南](../security.md)、[Starter](../reference/starter.md)、[SPI](../spi.md)、[Fory 安全](../reference/fory-security.md)和 [Quickstart](../../otryx-quickstart/README.md)。

## 4. 本地证据与准确边界

隔离工作区和证据位于 `/tmp/otryx-issue140-2d1e5f300fc7/`。每次运行使用独立冻结源码目录和输入 SHA-256；临时日志不是永久 CI artifact，不能替代准确 Git Head 的 CI 证明。不同阶段测试数不能相加后称为一次全量通过。

| 验证 | 已知结果及限制 |
| --- | --- |
| 准确 main 的确定性 RED | 17 个安全边界回归在 main `783bf8115c9523e5667023678dafe659315b2b66` 上真实失败；旧测试配置或环境错误不计产品 RED |
| 授权资源和统计追加 RED | #140 修复前版本复现结果发布早于归还 64 字节、包装的认证拒绝修改健康统计；原始失败 XML 保留 |
| 默认 Codec 追加 RED | 实际严格自定义 Codec 被未使用的默认 Fory 配置拒绝；修复前 2 tests / 1 failure，修复后两项通过 |
| `build-11` | Core 314、Fory 12、Transport 88、Starter 106，合计 520 tests，0 failures/errors/skips；686 个输入前后不变 |
| `build-14-etcd-security` | 实际 TLS + RBAC Etcd：19 项独立 fixture 自检与 9 项 Java IT 全部通过，无跳过 |
| `build-14-etcd-client-certificate` | 强制客户端证书：另一组 19 项 fixture 自检与 2 项 Java IT 全部通过；区分缺失客户端证书和错误后端密码，沿用相同冻结源码 |
| Etcd 清理与证据 | 两次运行均核验原始 XML、精确计数、输入完整性和自有进程退出，清理无错误 |
| `build-15` 已执行部分 | Core 314、Codegen 3、Etcd 普通测试 8、Nacos 59、HTTP 9、Consul 25、Eureka 19、Fory 12 均无失败；Nacos/Consul/Eureka 分别有 8/11/7 个外部条件 skips |
| `build-15` 未通过部分 | Transport 88 tests 中 `tlsShouldCompleteRpcWithTrustedServer` 有 1 个错误：外层 2 秒请求期限耗尽；SessionProof 7 和 Identity 11 均通过。两次有限单方法诊断通过但未复现原失败，无法唯一定位原失败在 TLS、HELLO 或业务阶段；原失败保留，预算未放宽 |
| Starter 原因码回归 | `build-16` 两个负例复现 membershipReady 清理后丢失 Registry 安全原因；修复后 `build-17` Ownership 5 项及 Quickstart 128 项通过，真实 E14 同时验证 TLS_REJECTED 与 AUTHENTICATION_REJECTED |
| namespace 原因码回归 | `build-21` 两个负例期望 NAMESPACE_MISMATCH、实际为 APPLICATION_MISMATCH；按 namespace 优先比较后，`build-24` 两项及真实 E06 均通过；不把此修复解释为旧 TLS 超时的原因 |
| `build-23` Javadoc | 独立 `-Pquality -DskipTests verify` 的 29 模块全部成功，源 Javadoc 无警告；测试明确跳过。插件另有 3 条空 Starter API 的 ignored link 提示，不宣称全日志零警告 |
| `build-24` 完整 reactor | 29 模块全部成功；原始 XML 为 803 tests、0 failures/errors、27 条件 skips，共 117 suites；其中 Transport 90、Starter 108、Quickstart Consumer 135 与 Memory 9 项 |
| style 证据边界 | `build-18` 独立 style 为 29 报告、400 Java、零发现。`build-24` 混用 quality/style 虽 Maven 成功，但 Javadoc fork 的生成源覆盖了 Checkstyle XML，严格聚合拒绝该报告；最终两项必须分开执行，不能用 Maven 退出码替代完整样式报告 |
| `build-25` 真实 Quickstart | 同批真实 exec JAR 的 17 个安全阶段全部通过，17 份成员快照与 fixture/outcomes 已核验；独立采样记录的 62 个自有进程均已退出，清理无错误。短命进程可能未采样，真实清理由 runner 的所有权机制负责 |
| E12 参数回归 | Spring 对重复命令行选项拼接值；两个真实入口的 mock RED 后，场景参数按键覆盖，每键只出现一次。Provider 与 BOTH 缺授权器均在真实 E12 拒绝启动 |
| Consumer 存活回归 | marker 后快照等待可能遮蔽退出；3 个 mock 回归中两个负例真实 RED，修复后完整 runner 17 项通过。记录 outcome 前再次检查进程状态；没有声称旧 E2E 实际发生该退出 |
| 默认身份前置检查 | `build-26` 新增 4 个负例和 `build-27` 非法应用名、namespace、显式实例 ID 的 6 个负例均准确 RED，证明 Registry supplier/factory 被提前调用。复用 Core 真实构造校验后，`build-28` 完整身份配置 38 项全部通过，保留名称回退、自定义 lazy 身份及 UUID 创建时机 |
| `build-28` reactor/style | Etcd 分类追加修复前的 684 个冻结输入前后不变，29 模块全部成功；117 份原始 XML 共 816 tests、0 failures/errors、27 条件 skips。独立 style 严格聚合 29 报告、400 Java、零发现；两份真实 exec JAR 已校验 Launcher、ZIP 完整性及 SHA-256，自有 Maven 进程已退出 |
| `build-29` quality | 同一修复前冻结目录独立 `-Pquality -DskipTests verify`，29 模块成功、源 Javadoc 零警告；3 条空 Starter API 的 ignored link 提示保留。quality 后两份 exec JAR 各有 8 个条目的时间戳变化，全部 247/235 个条目的内容逐字节相同；真实 E2E 使用并核验 quality 后的新 JAR 哈希 |
| `build-30` E2E 原始失败 | 15 个阶段通过，第 16 个错误 Registry 凭据场景得到成员确认 TimeoutException，没有观察到要求的 AUTHENTICATION_REJECTED；第 17 个只读权限场景未执行。685 输入和两份 JAR 不变，56 个观察到的自有进程全部退出；不接受超时冒充认证原因 |
| `build-31` 有限诊断 | 相同 JAR、原有预算、真实 fixture/场景方法/Observer 的单次 E14 复现通过，日志有 AUTHENTICATION_REJECTED、无 TimeoutException；13 个观察进程已退出。未复现 build-30 超时，首次 gRPC 路径日志较早不等于已证明根因；未改生产、runner、预算或断言 |
| `build-32` 完整收尾 | 相同冻结输入与 quality 后 JAR 的 17 个阶段全部通过，322.372 秒；17 份成员快照、fixture 与 outcomes 已核验。E04/E12 BOTH 的 Observer 分别在第二次读取收敛，未重跑应用启动。采样的 68 个进程均已退出，短命进程采样限制保留 |
| Etcd 分类追加 RED | `build-33` 原始 XML 为 8 tests、4 个准确 failures、0 errors/skips；SDK 合成 UNAUTH 包装内的 deadline、不可达、准备失败、深链/环均被旧实现误判安全拒绝。4 个正例保留；684 输入不变，自有进程退出。历史最终 AUTH marker 本身不能证明服务端实际返回了凭据拒绝 |
| Etcd 分类最小修复 | 只沿已知状态载体与 Completion/Execution 包装解析合成认证失败，保留第一个实际非 UNAUTH 状态；未知准备原因、深度耗尽或环返回普通不可用，TLS 类型优先。`build-34` 同一测试文件的 8 项全部 GREEN，独立审查无阻断 |
| `build-34` 最终 reactor/style | 684 个冻结输入前后不变，29 模块成功；117 份原始 XML 为 821 tests、0 failures/errors、27 条件 skips。严格 style 为 29 报告、400 Java、零发现，身份配置 38 项和 TLS 11 项通过；两份 exec JAR 完整性及哈希已保存，201.247 秒、自有进程已退出 |
| `build-35/36` 最终真实 Etcd | 同一冻结源码两次分别通过 19 项 fixture 自检及 9/2 项安全 IT，零 failures/errors/skips；各自原始 XML、完整用例名与输入哈希已核验。9/10 个采样进程全部退出，清理无错误，普通 RBAC 与强制客户端证书实例独立 |
| `build-37` 最终 quality | 同一冻结目录独立 quality 的 29 模块成功，96.793 秒、输入不变、源 Javadoc 零警告。27 条插件 warning 行与 3 条空 Starter API ignored link 提示单列。两份 exec JAR 全部 247/235 条目内容与已测试产物逐字节一致，各 8 个条目仅时间戳变化；最终 E2E 使用重新核验的真实 JAR |
| `build-38` 最终完整 mTLS | 最终 quality 后的真实 JAR 完成全部 17 个阶段，253.54 秒；685 输入与两份 JAR 哈希不变。17 份成员快照逐项与已核验状态一致，fixture 及 outcomes 齐全；62 个采样进程全部退出、清理无错误。包括修复后精确 Registry 认证拒绝与只读 Observer，未放宽期限或断言 |
| Python 及文档 | 进程组归属 10、安全快照 11、场景入口 29 项已通过；文档实际检查 86 Markdown、渲染 54 Mermaid 与 5 SVG，中文图已用 CJK 字体复核。当前图源哈希与已渲染输入相同，最终 Head 仍由 CI 再核验 |
| 静态检查 | 项目布局、差异空白、生产语义字面量 274 文件、改变/新增 Java 186 类型的约定和元数据检查均通过；26 个框架边界 catch-Throwable 提示不冒充零告警。后续修改重新核验对应输入 |
| `build-39/40` bootstrap 回归 | 原始参数测试准确复现 21 tests / 1 error：Java 入口已接收四项，旧断言仍解包三项。测试显式断言第四项为 TRUSTED_DEVELOPMENT 后，21 项全部通过；原 namespace/group 和应用 scope 断言保留 |
| `build-41` Python 门禁 | CI YAML 中全部 16 组 Python 自测命令顺序执行成功，108.052 秒，684 输入不变。该快照早于两处 Java 测试修复；相同脚本仍由最新 Head CI 再验证 |
| `build-42/43` 取消同步验证 | 正常实现的两项取消测试均通过；隔离副本仅移除实际 transport 取消动作后，两项均在取消事件断言准确失败，0 errors/skips。测试监听器提前注册，等待真实取消事件，保留原异常与取消断言；生产实现不变 |
| `build-44` 真实 Consul 追加失败 | 显式 profile 使九项 IT 实际运行，7 通过、2 个恢复断言失败，0 errors/skips。实际 Consul 日志反复报告 Session 或 Agent 成员不存在；HTTP 客户端在保留 404 前解析文本正文并拒绝。69.39 秒，684 输入不变，自有 Maven/Consul 均已退出 |
| `build-45/46` HTTP 404 初次回归 | 原测试确实复现非 JSON 404 被误判 METADATA_INTEGRITY_REJECTED，修复后通过；独立审查发现请求路径命中了 JDK 默认 404，预设的 Consul 文本 handler 未执行。这两次不作为指定 Consul 正文的验证证据，夹具修正后的结果见下行 |
| `build-48/49` HTTP 正确夹具 RED/GREEN | 使用实际注册路径，handler 发送两类 Consul 文本并逐次断言确实被调用。旧 HTTP 实现为 9 tests / 1 准确 failure，0 errors/skips；相同测试配合最小 404 reader 修复后，完整 HTTP 安全/字节 10 项通过。404 声明/分块超限、401/403、成功响应畸形 JSON、重定向、期限和取消边界保留，684 输入不变、清理无错误 |
| `build-47` 真实 Consul GREEN | 相同九项 IT 在最小 HTTP 修复后全部通过，0 failures/errors/skips，原有两个 20 秒恢复预算不变；总计 21.696 秒，测试套件 7.283 秒。前后均确认自有 Consul 1.20.6 节点身份与 leader，684 输入不变，两组进程均已退出；不代替远端 Docker Chaos |
| `build-50` 有限 smoke 诊断 | 独立诊断快照沿用并发 16、预热 2 秒、测量 3 秒、256 字节、Client 15 秒与 5% 门槛。官方 smoke 命令 exit 0；OTRYX measurement 7207 成功 / 0 错误，Dubbo 7144 成功 / 0 错误。warmup 捕获选点为空的 `attemptChecked:661` 来源，但未复现原 CI 高错误率，不能唯一归因 |
| `build-50` helper 失败边界 | 外层 helper exit 1，仅因把新生成的 OTRYX/Dubbo 两份 `dependency-reduced-pom.xml` 计入 sourceChanges；684 个预先冻结输入前后完全不变，64 个采样进程全部退出，清理无错误。完整 helper 仍记 FAIL，官方命令另记 PASS；诊断 overlay 不是准确干净 Head 的验收证据 |
| `build-51` Directory 确定性 RED | 真实 Resolver 完成同内容/空集/恢复解析后，ManualScheduler 暂扣 Directory publication。原生产实现的完整类为 20 tests / 3 failures / 0 errors / 0 skips，三项准确失败；另外 2 个新增安全对照和原有 15 项通过。20.312 秒，684 输入不变，清理无错误；证明本地交接漏洞，不归因全部旧 CI 错误 |
| `build-52` Directory 完整 Core GREEN | 最小补投影修复后 62 份 XML、319 tests、0 failures/errors/skips，其中 ServiceDirectoryTest 20 项全部通过。47.922 秒，684 输入不变，cleanup_error 为 null。旧 Selection/安全 epoch 拒绝、合法空集、原 LKG 截止点和单次无 IO 补投影均保留；不代替真实后端或准确最新 Head 门禁 |
| `build-53` 真实 Nacos 回归 | 使用独有数据目录和动态端口启动 Nacos 3.2.4，核验自有 HTTP/gRPC listener 与 readiness；原 NacosRoundTripTest 的真实 RPC 和停服断言为 1 test / 0 failures/errors/skips，套件 13.367 秒、全过程 76.105 秒。只修 bootstrap 的显式开发 profile，保留 CSV 端点、namespace/group 和 15 秒停服断言；684 输入不变，Nacos/Maven 进程清理后均已退出 |
| `build-54` 原版 smoke GREEN | 未修改 Harness 或官方脚本，保持 16 并发、2 秒预热、3 秒测量、256 字节、15 秒 RPC 期限与 5% 错误率门槛。OTRYX 18104 成功 / 0 错误，Dubbo 21244 成功 / 0 错误；官方命令及 helper 均 exit 0，外层 71.689 秒。684 原输入不变，两份 Shade POM 明确归类为生成物，58 个采样进程及外层 helper 已退出。原始 JSON 的 git_sha 为基准提交，另以候选 tree 和完整 manifest 绑定实际修复输入；仅为本地候选 smoke，不替代准确新 Head 或正式性能证据 |
| `build-55` 异步 admission 同步 GREEN | 两个相关用例在发出第二次调用前等待真实慢业务入口，保留既有结果、1 秒响应等待和 lease-release 断言。62 份 XML、319 tests / 0 failures/errors/skips；28.114 秒，684 冻结输入不变，自有 Maven PID 已退出、清理无错误 |
| 尚待验收 | 第三轮准确 Head 的真实执行与 API 状态矛盾见第 7 节；revision 4 准确最新 Head 和合并后 main 的完整相关门禁均 PENDING。每份历史失败与其独立修复证据继续保留 |

本地没有 Docker。`build-12` 在 Etcd Testcontainers 初始化时因没有有效 Docker 环境中止，环境错误不作为产品 RED。后续本地全量命令只排除 `EtcdApplicationRegistryIntegrationTest`，保留 Surefire 默认匹配模式；受保护 Etcd 用独立真实 native fixture 补验。容器故障、集群、各后端集成及 ACL 的原有 CI 门禁保持不变，条件跳过不能算通过。

## 5. PR #161 首轮 CI 与修复边界

准确首次 Head 为 `4a147880983a53cab587980230276878155c84b9`。全部 14 条工作流已经结束，10 条成功、4 条失败；以下原始失败不能被后续成功覆盖。

- [普通 CI](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39268700328) 和 [Rolling Compatibility](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39268700355) 均在共用 bootstrap 参数测试解包三项时失败；之后的 reactor、17 阶段安全验收与五组 rolling 尚未执行。真实入口需要第四项显式安全 profile，因此只修测试契约，保留入口行为。
- [Consul](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39268700440) 九项 IT 均在夹具构造的 UNSPECIFIED 配置上拒绝，实际合同断言未执行，后续 Docker pause/recovery 也未执行。两处夹具改用显式 trustedDevelopment；独立 ACL 项实际通过，不能代替九项 IT 或 Chaos。
- [Nacos Chaos](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39268700381) 在上游 Core 关闭测试失败：调用线程已退出，但原始 transport Future 的取消仍在异步传播。Nacos 模块实际跳过；以取消事件建立测试同步点，不能把调用 Future 终态当作副作用全部完成的屏障。
- [四后端独立 JVM 矩阵](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39268700248) 实际通过，逐份核验 44 个快照与 12 份 summary 的真实 RPC、角色、无 Endpoint Consumer、成员/Manifest revision 及 promotion/rollback。Eureka 六项 IT 与真实重启、Etcd leader transfer 均实际通过；这些结果不替代未执行的专项。

测试夹具修复后，真实 Consul 进一步暴露通用 JSON HTTP reader 提前解析 404 文本正文的问题。最小修复仅保留缺失状态，沿用原始字节/期限检查、401/403 分类和成功响应严格解析；Consul 与 Eureka 已有的缺失资源处理继续执行，注册失败不会伪装成功。中英文 Consul 故障说明已同步。

修复后必须在最新 Head 重新检查全部工作流、job 和关键原始 artifact。只读核对确认 Memory、四后端 Quickstart、benchmark 和 Observer 已显式选择开发 profile，mTLS YAML 明确选择 NETWORK_MTLS；未因此放宽生产默认值或安全能力校验。

## 6. PR #161 第二轮 CI 与 revision 3 修复

第二轮准确 Head 为 `3949104b32662d754732098cfa618e4aa55ab230`，各 run 均为 attempt 1。全部 14 条工作流结束，12 成功、2 失败；原始 artifact、checkout 与测试范围分别核对，不能只看 badge，也不能把其他 run 的通过补记到失败 run 的未执行步骤。

| 工作流 / 原始证据 | 实际结果及验收边界 |
| --- | --- |
| [普通 CI 39273378455](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378455)，job `118131245309` | 原始 117 份 XML：825 tests / 0 failures / 1 error / 18 skips。唯一错误是 `NacosRoundTripTest.providerLifecycleShouldPropagateThroughNacos` 的 metadata bootstrap 未显式选择开发 profile，得到 `RegistrySecurityException: UNSUPPORTED_PROTECTION`；不能放宽 UNSPECIFIED 的生产拒绝来修测试 |
| 普通 CI 后续范围 | Reactor 在 Quickstart Consumer 失败，Memory Quickstart、benchmark/soak、独立 JVM Quickstart、A-B-C、revision/rollback、受保护 Etcd 9 项 IT、客户端证书 2 项 IT、17 阶段 mTLS 及实际安全计数门禁均未执行。上传步骤成功不证明这些场景执行；更早本地 9/2/17 的成功不能替代本次 |
| [Consul 39273378417](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378417)、[Eureka 39273378429](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378429)、[Etcd Chaos 39273378582](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378582)、[Nacos Chaos 39273378584](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378584) | 专项目标合计 20 tests / 0 failures / 0 errors / 0 skips：Consul 9 IT + 1 真实 Docker Chaos + 1 ACL，Eureka 6 IT + 1 真实重启，Etcd/Nacos 各 1 Chaos。普通 reactor 内的条件 skips 与这些独立 run 分开计数 |
| [四后端独立 JVM Matrix 39273378414](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378414) | 四后端共 12 个 process/chain/revision 场景与 44 份新快照实际通过。真实 RPC、Consumer 无 Endpoint、Provider/BOTH 角色、Manifest revision、promotion/rollback 和原 Consumer 保持均已核验；不与重复 reactor XML 相加。Registry 共 9 份 artifact 的 GitHub digest 与 ZIP CRC 一致 |
| [Rolling Compatibility 39273378482](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378482)，job `118131244847` | 基线 `783bf8115c9523e5667023678dafe659315b2b66` 与本 Head 的五组 N/N+1/rollback RPC 实际通过，至少五次逻辑 hello。artifact 的 commits.properties/matrix.log、digest/CRC 已核验；未归档实际 exec JAR 字节/哈希、成员快照或逐 PID 退出快照，不把它写成 mTLS 或负载验收 |
| [RPC Comparison Smoke 39273378437](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39273378437)，job `118131244627` | 原参数门槛准确失败。OTRYX 800459 次测量调用中 4643 成功、795816 错误，错误率 99.4199578%，记录类型均为 RpcUnavailableException；Dubbo 26794 成功、0 错误。原始 artifact 没有首个失败的源位置/状态迁移，原因尚未唯一归因，不能用后续成功覆盖 |

### revision 3 修复与证据边界

Directory 的受控 RED 证明：Resolver 已换入完整验证的新 candidate，Directory 的成功发布尚在本地队列时，旧对象退役会造成普通 NOT_READY。最小修复只在普通陈旧路径读取本地 Resolver 的实际新对象、一次补投影及 CAS；保留旧 Selection 拒绝、原安全 epoch、全部原 LKG/成功计时，不执行远端 IO 或循环等待。`build-51/52` 只证明这项确定缺口及其 Core 修复，不能声称旧 smoke 的 795816 个错误全部来自该机制。

`build-50` 为有限诊断：官方 smoke PASS 与 helper FAIL 同时保留。新 Shade 文件是生成物，诊断快照的 684 个既有输入没有变化；该运行带有诊断 overlay，即使结果 JSON 含原 Head，也不能作为准确干净 Head 的成功证明。64 个观察到的自有进程均已退出，短命进程的采样限制继续保留。

当前证据目录为 `/tmp/otryx-issue140-2d1e5f300fc7/ci-head-3949104/`，各专项保留原始 ZIP/XML/summary 与无凭据摘录；不保存完整 Nacos 认证日志或签名下载 URL。Directory 原始 RED XML SHA-256 为 `a54d6685dc6b4b7727f8d2b1324acd0dba53c4d12ce4a814a247b2359fc04e6a`，GREEN XML 为 `015d1201e89258643b7519ff78c7c0dda1004059b456d684227e8a8615ee19a5`。本地临时证据仍需准确新 Head 的远端 artifact 验收。

| 后续门禁 | 当前状态 |
| --- | --- |
| NacosRoundTrip 条件测试的显式 bootstrap profile 修复及真实 Nacos 回归 | `build-53` 实际通过 1/0/0/0，原始 XML 与自有进程清理已核验 |
| 不带诊断 overlay、原参数/原 5% 门槛的 comparison smoke | `build-54` 原始结果 OTRYX 18104/0 error、Dubbo 21244/0 error；官方命令和 helper 均通过，输入及进程清理已核验 |
| revision 3 准确 Head 的完整 reactor、独立 style/quality 与必要真实后端 | 第三轮已获得原始执行证据，实际通过、失败与未执行范围见第 7 节 |
| 同一 PR 的第三次提交及准确最新 Head 全部相关 CI | 已发布 `e1ba9b1dc8d00667b849cd7632165cdfbc3d0814`；两项前置 Core 失败，不能合并 |
| 合并后准确 main 的独立核验、#140/#127 状态更新 | PENDING；#140 尚未完成，#141 仍未实施 |

## 7. PR #161 第三轮原始执行与 revision 4

准确 Head 为 `e1ba9b1dc8d00667b849cd7632165cdfbc3d0814`，tree 为 `c37cd606e8e4ea83c14f9a5e1497c0d17684960c`，各 run 均为 attempt 1。普通 run/jobs/check API 初次持续返回 queued、空 steps 和空 runner，辅助 run-attempt 后续还返回 in_progress，但 artifact API 与原始 job logs 已证明本轮实际执行。下面依据准确 checkout、run/job、digest/CRC 及原始内容分别记录；API 状态矛盾保留，未据此宣称完整门禁成功或合并。

| 准确运行 / 证据 | 实际结果与边界 |
| --- | --- |
| [普通 CI 39279078819](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39279078819)，job `118149908395` | 62 份 XML：319 tests / 1 failure / 0 errors / 0 skips。唯一失败为 `OtryxRpcServerAsyncExecutionTest.asyncStageShouldRetainAdmissionUntilCompletion`，预期 OVERLOADED、实际 OK。29 模块中根模块 SUCCESS、Core FAILURE、其余 27 SKIPPED；后续受保护 Etcd 9+2 项、17 阶段 mTLS 与 Quickstart/benchmark 验收均未执行 |
| [Nacos Chaos 39279078670](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39279078670) | 前置 Core 同一用例失败，artifact 实际只有 62 份 Core XML、319/1/0/0；没有 NacosApplicationRegistryChaosTest 报告，该目标本轮未执行 |
| Registry 其余专项 | Consul 9 IT + 1 Chaos + 1 ACL、Eureka 6 IT + 1 restart、Etcd 1 Chaos，共 19 个目标实际通过。逐 run 原始 artifact 和清理另行核验，不能用它们替代 Nacos 未执行目标 |
| [四后端独立 JVM Matrix 39279078710](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39279078710) | 12 个场景、44 份真实成员/路由快照全部通过；Consumer 无 Endpoint、Manifest 与角色、promotion/rollback 均按原断言检查 |
| [Rolling 39279078728](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39279078728)，job `118149908562` | 准确基线 `783bf8115c9523e5667023678dafe659315b2b66` 与本 Head，四个实际 repackage 构建成功、五组合 RPC 全通过。artifact `12199472588` 的两个文件及 Docker 清理已核；没有实际 JAR 字节/哈希或完整进程末态账本，不扩称安全或负载验证 |
| [原版 Smoke 39279078618](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39279078618)，job `118149907839` | 原 16 并发、2 秒预热、3 秒测量、256 字节及 5% 门槛下，OTRYX 27437 成功 / 0 错误，Dubbo 18315 成功 / 0 错误。准确 Head 的 environment/result/report 三方一致，6 个 artifact 成员、digest/CRC、原 validator 和 Docker 清理已核；是共享 CI smoke，不是 #146 正式性能验收 |
| Java Style / Java Agent Quality | Style 的 29 个模块 XML 完整覆盖 400 个 Java 源文件，0 findings。Agent Quality 扫描 406 文件：0 errors、26 R001 warnings；元数据 406 类型、0 findings。不得把非零 warning 说成全日志零告警 |
| Documentation / Agent Governance | 86 Markdown，54 个 Mermaid 和 5 个 SVG 实际渲染成功；治理脚本实际执行成功。图源和文档门禁未降级 |
| [Release Readiness 39279078714](https://github.com/Ryan-Guizhou/otryx-rpc/actions/runs/39279078714)，job `118149908085` | 独立 `clean verify -Pquality` 实际 BUILD SUCCESS，839 tests / 0 failures/errors / 27 条件 skips；只计该 job 的结果，不能覆盖普通 CI 的失败。SNAPSHOT 发行专用步骤未运行，不是实际发布 |
| Fory Allocation Comparison | 四份原始 JMH 文件、16 行、一个 baseline/candidate pair，实际 REPORT_ONLY。共享 CI 短测不能升级成固定硬件验收 |

普通 CI 的原始 reactor ZIP `12197939097` SHA-256 为 `3c248c412e0fa5e983e114ba3500c3a1ce9d7e41bbc4325853745c0dc24f65f9`；Smoke ZIP `12198039951` 为 `d8bf77d06bd5d801f5632afd39f58e4ec1537f6885ca6afc4edba265c3a6c898`。证据位于 `/tmp/otryx-issue140-2d1e5f300fc7/ci-head-e1ba9b1/`；不保存临时签名 URL，不将旧运行报告覆盖为本轮结果。

### 两处测试屏障与本地 GREEN

授权使用独立有限 worker，`handle()` 返回未完成的 Future 不能证明该请求已经取得业务 admission。原测试立即发出第二次请求，两次授权完成的次序没有保证；后发快调用可能合法先占槽并返回 OK。`AsyncServiceImpl.slow()` 的既有 `slowInvoked` latch 处于真实业务体内，必在 admission 成功后触发，且测试持有的 Stage 此时尚未完成。

revision 4 在 `asyncStageShouldRetainAdmissionUntilCompletion` 和相邻 `asyncStageShouldNotBlockCpuWorker` 中等待该业务入口，再发出快调用。前者继续验证在途 Stage 占槽、完成后释放和后续恢复；后者保证确实在慢业务已进入时验证 CPU worker 可用。两处均沿用现有 3 秒入口等待尺度；所有既有响应结果、1 秒 get 和释放断言保持原值。

`build-55` 一次冻结 Core 验证实际得到 62 份 XML、319 tests / 0 failures/errors/skips，两个目标方法各运行一次。28.114 秒、684 输入前后哈希一致、sourceChanges 为空、cleanup_error 为 null，自有 PID 257204 已退出。目标 XML SHA-256 为 `8ee47684523f4869f59f874feb11e8330534740ec8d1cf1fa1b1cb0127187602`。当前修复测试文件 SHA-256 为 `90af987ba64b325ff10bc732470db778e90cd2e41be8d3a34056c474d6594f04`；其后只更新工程证据文档。该本地 GREEN 不代替新准确 Head 的全部门禁。

## 8. 恢复与后续动作

1. 核对分支、远端 main 和自有进程；一次仅一个 Maven/真实后端验证窗口，不改动运行中的冻结输入，不混用不同阶段 JAR。
2. 保留已核验的身份、存活和 Etcd 分类 RED/GREEN；无法唯一归因的旧 TLS 与成员等待超时仍记录证据不足。
3. 保留全部历史失败及 `build-34` reactor/style、`build-35/36` 两组真实 Etcd、`build-37` quality、`build-38` 的 17 阶段、`build-45/46` 夹具错误与 `build-48/49` 纠正证据。第二轮准确 Head、`build-50` 诊断和 `build-51/52` Directory RED/GREEN 分开记录，不能把任一旧全量或诊断报告改称最新输入。
4. 将已通过冻结 Core 验证的两处测试屏障作为 revision 4 追加到现有 PR #161，再核验准确新 Head 的完整相关门禁。先读取真实日志/产物解决 API 状态与执行证据的矛盾；每条 workflow/job/关键 artifact 确实通过后，以预期 Head 合并，再独立核验准确 main。
5. 更新 #140 与 #127，再从新 main 继续 #141–#145 的独立工作包。main 若失败，先修复主线，暂停后续合并。
6. #146 工具和报告可继续准备，但正式验收必须有固定物理主机、测前确定的 SLO/退化阈值、AB BA AB 六次运行、每次至少 60 分钟 soak 和完整原始指标。条件缺失保持 BLOCKED；#147 只执行已授权发行 dry-run，依赖未完成不能提前关闭。

任何步骤连续 10 分钟无进展，记录准确命令、SHA、run/status、错误和下一动作，停止可安全中断的本地自有操作；不取消其他人的远端 workflow。回滚采用可审查 revert PR，不移动稳定分支，不强推覆盖他人提交。
