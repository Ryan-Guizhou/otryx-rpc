# OTRYX RPC Java 工程编码规范

**状态：V1.1 首发前治理规范（PR-02 建立执行机制，PR-03 至 PR-07 完成全仓治理）。**
**事实基线：** Java 21、Maven、Spring Boot 3.5.4、OTRYX 1.0.0-SNAPSHOT，以当前 POM、代码和测试为准。
**参考：** Peach Cloud 的中文 Javadoc、类型元数据、英文日志和可读性要求；保留 RPC 热路径及异步边界的特殊约束。

## 1. 目标与治理边界

优先级：正确性 > 安全 > 性能与可靠性 > 可理解性 > 可维护性 > 风格。

OTRYX 尚未 GA，不对历史开发版本承担 Java API、SPI、配置、Wire、Schema 或 Maven 坐标兼容义务。**这不表示允许引入协议错误、安全漏洞或 Consumer/Provider 不互通。** 任何对当前运行行为、资源生命周期或公开契约的修改均应与纯规范修改分开提交，并附测试及回滚方案。

- 采用能力族 Maven 聚合：registry、serialization、transport、proxy、observability、spring-boot；具体实现保持独立。
- Core 只依赖中立 API、协议、运行时和 SPI，不反向依赖 Vert.x、Nacos、Etcd、Fory 或 Spring 的具体实现。
- 优先显式依赖、构造器注入和清楚的资源所有权，不为目录对称而增加空模块或无意义抽象。
- 不机械重构大类、Stream、Optional、record、var 或 reflection；先证明职责问题和实际收益。

## 2. 命名、格式和代码组织

| 对象 | 要求 |
| --- | --- |
| Package | 小写英文，按领域及职责分层；不新增 misc、temp 等杂物包 |
| 类型 | UpperCamelCase，有业务/框架语义的名词或能力 |
| 方法、参数、字段 | lowerCamelCase；方法优先动词表达语义 |
| 常量 | UPPER_SNAKE_CASE，必要时体现单位和范围 |
| 布尔方法 | is/has/can/supports 等正向语义，遵守 Bean 约定 |
| 数值及超时 | 明示单位：Millis/Nanos/Bytes、maxInflightBytes 等 |
| 错误类型 | 以具体失败原因命名，拒绝模糊 SystemException |
| SPI 实现 | 技术名 + 契约名，如 NacosApplicationRegistry、VertxRpcTransportFactory |

### 协议字面量与魔法值

对外部协议（Consul、Eureka 等）的 **JSON 字段名、HTTP 方法、API 路径、健康状态、固定 Header、配置键与有语义的默认值**，应使用具名 `static final` 常量，遵循 `UPPER_SNAKE_CASE`。按归属就近放在 Adapter 包内的协议常量类；跨 Adapter 共用的 HTTP 方法常量放在共享 HTTP 控制面。不将厂商协议字段塞入 Core，也不创建缺乏职责的全局常量库。

例如：Consul 的 `"DeregisterCriticalServiceAfter"` 与 Eureka 的 `"overriddenstatus"` 属于需要精确匹配的协议值，不应散落在注册、发现、健康过滤等业务方法内。测试中用于验证**第三方协议的预期报文字段和值**可直接使用标准协议字面量作为独立断言，避免测试引用生产常量导致「实现与预期一起写错」的假阳性。

普通异常提示、一次性局部业务文本，以及无额外业务含义的 `""`、路径连接符等不必机械抽取。仅整理常量时保持协议字节值和行为一致，不能顺带修改健康判断、重试、订阅或资源生命周期。

**全仓范围**：上述约束适用于 Core/Wire、Registry、Transport、Serialization、Proxy、Spring Boot 配置、Observability、Codegen、Examples 与 Benchmarks，而不仅限于某个注册中心。协议路径、环境变量、SPI Extension、Metrics 名称和 Tags 是对外接口，应集中在各自最小所有者中；配置装配层必须复用 Provider/Adapter 已公开的配置键常量（可适度新增独立、不依赖厂商 SDK 的契约常量）。

**自动审计（Issue #76）**：

```bash
python3 scripts/test_java_magic_literals.py
python3 scripts/check_java_magic_literals.py --audit --report target/magic-literal-audit.json
python3 scripts/check_java_magic_literals.py --changed --base <base-commit-sha>
# 强制执行全仓高置信度检测（当前 CI 使用此模式）：
python3 scripts/check_java_magic_literals.py --enforce-all --report target/magic-literal-audit.json
```

该工具只检查 Java 生产源码的八类高置信度字面量位置（`@Extension`、`providerOption`、`Map.entry`/`Map.of`、`JsonNode.path`、`Metric.tag`、JVM/ENV 与 HTTP Header），不假装分析所有 Java 字符串或数值表达式。Issue #76 完成后的主线 CI 使用 --enforce-all 作为阻断门禁，并保留 JSON 审计证据；--audit 可用于开发阶段审计。指标名称、容量/时序魔法数、状态映射等未能由词法规则识别的内容仍需人工复查并记录依据，零发现不表示所有语义字面量已获证明。不得把业务已知缺陷混入纯常量化 PR。

Java 源文件使用 UTF-8 无 BOM、LF、4 空格、行宽 120，禁止通配符 import、行尾空白和无意义空行。建议按 java/javax/jakarta、第三方、项目内包分组；导入顺序全面自动强制前应先评估全仓差异，不造成与行为变更混合的巨大格式化 PR。遵守现有 Checkstyle 零违规门禁。

## 3. 中文 Javadoc

### 3.1 类型级元数据（已确认的 Peach Cloud 规范）

所有**手写 Java 顶层** Class、Interface、Enum、Annotation、Record 应具有中文类型 Javadoc 和以下三个标签，各出现一次：

~~~java
/**
 * 定义服务发现的公开行为边界。
 *
 * <p>端点列表返回当前缓存快照，不保证远端注册中心即时同步。</p>
 *
 * @Author Mr Shu
 * @Version 1.0.0
 * @CreateTime 2026/10/10 09:30
 */
~~~

以上值仅用于演示格式；**不得直接复制作者、日期或版本到所有历史源文件**。

- @Author：使用有证据支持的作者/维护者署名；项目管理员身份不自动证明他撰写了历史代码。
- @Version：记录可证实的类型版本或维护版本。新类型以当前开发版本为准，不随意写旧版本。
- @CreateTime：使用已有可靠记录；否则可从 Git 首次引入该类型的提交时间取得可追溯的起点，但须按“版本控制首次出现时间”解释，不声称它等于本地首次编写时间。
- 格式严格为 yyyy/M/d HH:mm，采用一致的项目时区口径；不可填 TODO、unknown、占位符或虚假数据。
- 标准 @since 与 @Version 不相同；只有存在明确的引入版本证据时填写 @since。

根 Maven Javadoc Plugin 注册上述大小写严格一致的自定义标签（仅允许类型位置），原有 doclint=all 和 failOnWarnings=true 不得降低。Javadoc 的存在性、内容真实性与元数据格式是不同的检查维度。

### 3.2 方法级与字段级契约

公开 API/SPI、重要配置、关键生命周期、复杂异步/线程/背压/资源方法必须准确说明：

- 参数单位、可空性、校验、枚举取值和所有权
- 返回值顺序、快照/可变性、空结果、异步完成语义
- 具体异常、错误状态、可能的副作用
- 线程归属、阻塞、超时、取消、中断、关闭和释放责任
- 需要保证的幂等条件、失败后行为及当前版本协议约束

使用 @param、@return、@throws、{@code}、{@link}，继承契约无变化时可用 {@inheritDoc}。不要仅重复方法名、逐行解释 if、伪造线程安全保证或为了注释覆盖率增加无意义内容。

### 3.3 全仓迁移门禁

新增工具：

~~~bash
python3 scripts/test_java_doc_metadata.py
python3 scripts/check_java_doc_metadata.py --audit --report target/java-javadoc-metadata-audit.json
python3 scripts/check_java_doc_metadata.py --changed --base <base-commit-sha>
python3 scripts/check_java_doc_metadata.py --enforce-all
~~~

- **PR-02：** 全量 --audit 生成带文件/行号/类型的债务台账，不将历史缺口假装为合规；变更过的 Java 源文件由 --changed 严格检查。
- **PR-03 至 PR-06：** 按模块核实和整改存量手写类型，复杂行为问题另建 PR。
- **PR-07：** 全量缺口归零后启用 --enforce-all CI；对确实不能核实的历史数据采用有审批、责任人、依据的例外台账，不通过伪造字段来“清零”。

工具是轻量源文本检查，并非完整 Java AST 或元数据真实性鉴定；Checkstyle/Javadoc/Code Review 继续独立运行。测试和独立 Benchmark 的手写 Java 也纳入审计，编译器临时生成文件与 target 不纳入。

## 4. 英文日志（原生 SLF4J）

保留 LoggerFactory，不为统一日志而引入 Lombok。统一使用稳定、简短、英文参数化消息：

~~~java
private static final Logger LOGGER = LoggerFactory.getLogger(Example.class);

LOGGER.warn("RPC request rejected, serviceId={}, methodId={}, reason={}",
        serviceId, methodId, reason);
~~~

| 级别 | 适用场景 |
| --- | --- |
| ERROR | 组件无法继续提供必要能力、需立即关注的不可恢复失败 |
| WARN | 非正常但可恢复的降级、异常拒绝、重试耗尽或连接风险 |
| INFO | 初始化成功、服务上线/下线、关键配置与生命周期变化 |
| DEBUG | 经排障需要开启的详细但非敏感过程信息 |
| TRACE | 专项调试，评估开销和数据敏感性后有界使用 |

- 每次正常 RPC 调用、编解码、心跳等热路径**不记录 INFO**。
- 禁止运行时动态拼接日志字符串、String.format 预格式化、重复打印同一异常。
- 只记录排障所需的非敏感白名单字段，例如 requestId、serviceId、methodId、errorType、reason、elapsedMillis；字段名保持一致。
- 不直接记录 Token、密码、私钥、签名 URL、完整请求/响应、业务参数或未经筛选的 Metadata。
- Throwable 末尾参数并非绝对安全。用户业务异常/外部 SDK 异常的 message 与堆栈可能包含凭据，应优先记录脱敏 errorType 与请求关联 ID；若必须输出堆栈须完成来源和权限审查。
- OpenTelemetry Span、Observer、JFR 和其他遥测出口执行相同的脱敏边界；Metrics 标签不得使用请求 ID 或任意业务输入等高基数字段。
- 高频故障须考虑限频/采样/状态转换记录，不能造成日志风暴或不必要的字符串分配。

## 5. 异常、并发与资源生命周期

- 在异步隔离边界不要机械地把 catch(Throwable) 改为 catch(Exception)；需要验证 Future/CompletionStage 是否结束以及 Lease、Permit、ByteBudget 是否归还。
- 禁止静默吞异常；InterruptedException 必须按线程和取消契约处理。
- Vert.x EventLoop 不执行阻塞 I/O、同步等待 Future、Thread.sleep 或用户任意阻塞回调。
- 对 Retry、Circuit Breaker、Admission、连接池、队列、缓冲区等明确上限与退避/拒绝策略，不创建无界线程池或无限缓冲。
- 资源取消、超时、拒绝、异常、关闭所有路径要可被测试验证；TLS 验证失败不得静默回退至不安全连接。
- 热路径性能改变需要对照真实基线与 JMH/JFR、allocation、端到端 p99 测试，Shared CI Smoke 不等于生产性能承诺。

## 6. 自动化检查和例外

既有 scripts/check_java_conventions.py 检查高置信度受限 API（如 System.exit、ProcessBuilder、System.out、动态拼接日志、EventLoop sleep）及建议性风险（catch Throwable、敏感字段）。Checkstyle 严格门禁覆盖配置中真实启用的类型/方法/字段命名、public Javadoc、import、行宽等规则。

静态扫描无法证明所有日志都安全，也无法证明 Javadoc 与真实线程/资源契约相符；这些必须由代码审查、故障注入和回归测试验证。历史审计数字只代表当时的规则和提交，不可作为当前全仓合规证明。

### 建议执行

~~~bash
python3 scripts/check_project.py
python3 scripts/test_java_conventions.py
python3 scripts/test_java_doc_metadata.py
python3 scripts/check_java_conventions.py --audit --report target/java-conventions-audit.json
python3 scripts/check_java_doc_metadata.py --audit --report target/java-javadoc-metadata-audit.json
python3 scripts/test_checkstyle_audit.py
mvn -B -ntp -Pstyle-audit -DskipTests install
python3 scripts/summarize_checkstyle_audit.py --json target/checkstyle-audit.json --markdown target/checkstyle-audit.md --enforce-zero
mvn -B -ntp clean verify -Pquality
~~~

源码行为变更还须受影响测试、Examples 及合适的性能验证。变更的每个 PR 以精确 Head SHA 的 CI 为依据；仅在全部相关检查通过后合并，再核对 main CI。

完整规则由本文件、AGENTS.md 和共享 Java Engineering Skill 协同维护，不另复制多个独立规范版本。
