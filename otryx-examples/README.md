# OTRYX RPC Examples

本目录展示推荐的真实部署结构：**契约共享，Provider 与 Consumer 分进程运行**。

## 模块

- `otryx-example-api`：仅包含 RPC Contract、DTO 和编译期生成代码；
- `otryx-example-provider`：只发布 `@OtryxRpcService`；
- `otryx-example-consumer`：只注入 `@OtryxRpcReference` 并发起调用。

Provider 与 Consumer 都同时产出：

- 普通 JAR：供 Reactor 内测试和其他模块作为依赖使用；
- `*-exec.jar`：Spring Boot 可执行 JAR，供独立进程运行。

## 运行

在仓库根目录执行：

```bash
docker compose -f otryx-examples/docker-compose.yml up -d
mvn -B -ntp -pl otryx-examples -am clean package
java -jar otryx-examples/otryx-example-provider/target/*-exec.jar
```

Provider 启动后，在另一个终端执行：

```bash
java -jar otryx-examples/otryx-example-consumer/target/*-exec.jar
```

Provider 应输出类似：

```text
OTRYX RPC server started: bind=0.0.0.0:19090, advertised=127.0.0.1:19090
```

Consumer 应输出：

```text
RPC demo completed successfully: Hello, OTRYX RPC!
```

Consumer 在启动阶段允许等待短暂的注册中心传播时间，但真实业务调用仍遵循 OTRYX RPC 的超时、重试、熔断和端点剔除规则。


## A→B→C 三独立 JVM Deadline E2E（U08）

在本地启动官方示例 Nacos 后，先构建全部示例，再从仓库根目录运行：

```bash
mvn -B -ntp -pl otryx-examples -am clean package
bash scripts/run_three_process_deadline_e2e.sh
```

脚本启动三个独立 Java 进程：Consumer A、Relay Provider B（端口 19091）和 Leaf Provider C（端口 19090）。
A 将 5 秒单次覆盖超时及 `tenant` Metadata 发给 B；B 延迟 250ms，
带着 `relay=B` 元数据继续调用 C。B 的 Client 默认超时为 10 秒，
但不能覆盖 A 已剩余的更短预算。C 在实际业务线程中验证到达的 Metadata
以及剩余 Deadline 小于 5 秒，Consumer 输出：

```text
RPC deadline chain completed: CHAIN_OK remainingMillis=...
```

这个场景使用真实 Nacos 注册中心和 Vert.x TCP；`scripts/run_three_process_deadline_e2e.sh`
已列入 CI 门禁。额外的绝对 Epoch 时钟偏移与剩余预算校验在 Core
`OtryxRpcServerAsyncExecutionTest` 中执行。E2E 通过状态应以 Actions 的执行结果为准。

## TLS / mTLS 可选 Profile

默认示例仍使用 PLAINTEXT，保证本地和 CI 的 Nacos 恢复 E2E 不需要证书准备。

Provider/Consumer 额外提供：

- `application-tls.yml`
- `application-mtls.yml`

这些 Profile 只引用环境变量，不在仓库保存证书或私钥。

### TLS

Provider 需要：

```text
OTRYX_RPC_TLS_CERT=<provider certificate PEM path>
OTRYX_RPC_TLS_KEY=<provider private key PEM path>
```

Consumer 需要：

```text
OTRYX_RPC_TLS_CA=<trusted CA PEM path>
```

分别使用 Spring Profile `tls` 启动 Provider 与 Consumer。Consumer 默认启用 hostname verification，因此调用地址必须与 Provider 证书身份匹配。

### mTLS

Provider 和 Consumer 都需要配置：

```text
OTRYX_RPC_TLS_CERT=<local certificate PEM path>
OTRYX_RPC_TLS_KEY=<local private key PEM path>
OTRYX_RPC_TLS_CA=<trusted CA PEM path>
```

分别使用 Spring Profile `mtls` 启动。Provider 会要求合法客户端证书，握手失败不会降级到 plaintext。

证书在线 Reload、有效期校验和故障语义见 [TLS / mTLS 安全指南](../docs/security.md)。

## 可选 Observability Adapter

Examples 默认不强制引入观测框架。需要验证生产观测集成时，可按需给 Provider/Consumer 增加：

- `otryx-observability-micrometer`
- `otryx-observability-opentelemetry`
- `otryx-observability-jfr`

其中 Micrometer/OpenTelemetry 在 Spring Context 存在对应 Bean 时自动装配；JFR 通过 `otryx.rpc.observability.jfr.enabled=true` 启用。完整指标、Trace Context 与 JFR 事件见 [可观测性指南](../docs/observability.md)。


## N/N+1 Rolling Compatibility E2E

仓库提供 `scripts/run_rolling_compatibility_e2e.sh` 与独立 `Rolling Compatibility` Workflow。

PR 场景会把 PR base commit 作为 N、当前 HEAD 作为 N+1，分别构建两套 Provider/Consumer executable JAR，然后验证：

~~~text
N Consumer   -> N Provider
N+1 Consumer -> N Provider
N Consumer   -> N+1 Provider
N+1 Consumer -> N+1 Provider
N+1 Consumer -> N Provider rollback
~~~

该 E2E 验证 Wire v1 与 Registry Schema Metadata 的跨 Git 版本兼容。它不替代生产多副本滚动发布演练；真实多实例分批发布、容量和流量切换仍属于 RC 环境门禁。



## Consul / Eureka 可选注册中心

示例 Provider、Consumer 的 POM 均按需引入新增 Adapter，默认仍为 Nacos。先从项目根目录执行：

```bash
mvn -B -ntp -pl otryx-examples -am clean package
```

确保已启动同一 Consul Agent（默认本地 `http://127.0.0.1:8500`）或 Eureka Server（默认本地 `http://127.0.0.1:8761/eureka`）。两个进程使用**相同**的 Spring Profile、Registry endpoint 和逻辑 namespace 启动：

```bash
java -jar otryx-examples/otryx-example-provider/target/*-exec.jar --spring.profiles.active=consul
java -jar otryx-examples/otryx-example-consumer/target/*-exec.jar --spring.profiles.active=consul
```

将上面的 `consul` 改为 `eureka`，可测试 Eureka；Provider 和 Consumer 分别在独立终端启动，不要同时占用 shell。Consul Token 用环境变量 `CONSUL_HTTP_TOKEN`，Eureka Basic Auth 用 `EUREKA_USERNAME`、`EUREKA_PASSWORD`；切勿把密钥写进配置仓库。以上是**外部服务启动后**的独立进程示例，普通 CI 的 Mock HTTP 单元测试不等于已通过真实 Consul/Eureka Server 集成测试。详细参见 [Consul](../docs/reference/registry-consul.md) 和 [Eureka](../docs/reference/registry-eureka.md)。

## 安全运行方式（U05）

默认示例的 `application.yml` 显式使用 `TRUSTED_DEVELOPMENT`，
**只能运行在隔离、可信的测试网络**。双向 TLS 示例配置
`application-mtls.yml` 覆盖为 `NETWORK_MTLS`，并提供严格 Fory DTO
白名单（`com.peachsoft.otryx.examples.api.*`）。部署真实网络时必须配置
真实 CA/客户端证书和业务的 `RpcAuthorizer`；运行示例不代表已授予
应用级权限，详见 [安全指南](../docs/security.md)。
