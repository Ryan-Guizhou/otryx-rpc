# OTRYX RPC 用户指南

本页提供最短可验证集成路径。运行命令来自仓库中的 Maven Reactor 与官方独立 JVM Quickstart。

## 环境与版本

- JDK **21**、Git，以及仓库 Maven Wrapper；脚本需要 Bash、Python 3、GNU `timeout`，mTLS 场景还需要 OpenSSL。
- Spring Boot **3.5.4**（Spring 集成示例）。
- 项目源码与 Maven 坐标均为 **`1.0.0-SNAPSHOT`**。当前不能从 Maven Central 直接下载安装，先本地构建。

## 先运行真实 RPC

在仓库根目录，推荐先运行无外部注册中心的 [Memory Quickstart](../otryx-quickstart/README.md)：

```bash
./mvnw -B -ntp clean install -DskipTests
./mvnw -B -ntp -pl :otryx-quickstart-api,:otryx-quickstart-provider,:otryx-quickstart-consumer,:otryx-quickstart-memory -am clean package
bash scripts/run_quickstart_memory.sh
```

跨进程服务发现需要共享 Nacos。可以使用自己部署的本地服务，或先启动仓库的 Compose 服务：

```bash
docker compose -f otryx-quickstart/docker-compose.yml up -d
```

确认 HTTP 8848 与客户端 gRPC 9848 可用。在任何应用启动前，为手工示例的同一 scope 独占初始化 Mapping 分片（Windows 用 `scripts/bootstrap_quickstart_nacos.ps1`，参数相同）：

```bash
bash scripts/bootstrap_quickstart_nacos.sh \
  otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar \
  127.0.0.1:8848 public OTRYX_RPC
```

初始化只补建缺失分片并校验、保留已有数据；同一 namespace/group 不得有并发业务写入。失败先解决再启动，普通 Starter 不自动补建。随后在两个终端分别运行：

```bash
# Provider terminal
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar
```

```bash
# Consumer terminal
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar
```

Provider 发布 Manifest、Mapping 和应用成员；Consumer 也注册真实成员，但没有 RPC Endpoint。发现路径是 ServiceKey → Mapping → Application Directory → Manifest/契约校验 → 已验证 Provider，本地路由缓存供 RPC 热路径使用。成员存在并不等于有可调用的 Provider；读取真实快照和排障见 [Quickstart](../otryx-quickstart/README.md)。

停止上述手工 JVM、保留 Nacos 后，可运行全部场景：

```bash
NACOS_TEST_ENDPOINT=127.0.0.1:8848 bash scripts/run_quickstart_e2e.sh
```

脚本依次验证 Memory、双进程恢复、BOTH 三进程链、两个真实 Manifest revision 的升级/回滚和 mTLS 授权，自动初始化各自隔离 group。四个 Nacos 场景的 JSON 快照与 `summary.log` 保存在 `target/quickstart-e2e/`；Memory 标记在控制台输出，临时 PKI 私钥不作为证据。它需要已构建 JAR 和运行中的 Nacos；可逐个运行的命令与精确预期结果见完整教程。

## 在已有 Spring Boot 项目中引用

只有一个默认轻量的 Starter；Registry 等 Adapter 需按需单独引入，详见 [Starter 细节](reference/starter.md)。

```xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

单进程 Memory 配置（不需要 Nacos/Docker；跨 JVM 必须使用共享注册中心）：

```yaml
spring:
  application:
    name: demo-service
otryx:
  rpc:
    enabled: true
    security-profile: TRUSTED_DEVELOPMENT
    registry:
      type: memory
```

业务接口声明、Provider 导出、Consumer 注入、编译期 Stub 与 Nacos/TLS 的实际示例和约束请使用 [Starter](reference/starter.md) 和 [入门教程](getting-started.md) 的源码片段，不要将伪代码替代为可运行实现。

应用名默认来自 `spring.application.name`，也可用 `otryx.rpc.registry.application-name` 覆盖，均未提供时启动失败。当前 Starter 以 `registry.namespace` 同时配置后端与 ApplicationKey；Quickstart 的 Nacos 示例明确使用 `public`，Nacos group 和 ServiceKey group 则是不同维度。

切换 Consul/Eureka 仍复用单一 Starter。Eureka 只保存应用成员，须在 Provider 和 Consumer 同时指定 `otryx.rpc.registry.eureka.metadata-backend`、`metadata-endpoints`，并显式引入所选 Etcd/Nacos/Consul 元数据 Adapter。若元数据选用 Nacos，也必须在业务启动前初始化同一 scope。完整命令见 [可选 Registry](../otryx-quickstart/README.md#7-进一步验证-registryetcd--consul--eureka)。

## 重要运行边界

- 本项目目前优先支持 **Unary RPC**，不声明 Streaming RPC 能力。
- Retry 只能用于业务明示幂等的请求；超时、Admission 和队列上限应随真实负载测试。
- PLAINTEXT 仅用于受信实验环境；网络部署选择 `NETWORK_MTLS`、严格 Fory DTO 白名单及 `RpcAuthorizer`。Quickstart 的 mTLS 演示只允许显式客户端指纹调用 Greeting 的 `hello`，受信证书不自动获得业务权限。
- Wire v1 保留不代表 Peach RPC 1.x 与 OTRYX 1.0 互通，尤其是 Java 类名变化造成的 Type ID、Method ID 和 Schema Fingerprint 变更。
- 当前使用唯一应用级控制面。#139 同时要求全仓清理、共享契约和四后端独立 JVM 验收，见[验收与计数口径](reference/application-registry-acceptance.md)；短时结果不构成生产容量或正式发布结论。

继续阅读：[配置](configuration.md) · [安全](security.md) · [兼容迁移](migration.md) · [FAQ](faq.md)。
