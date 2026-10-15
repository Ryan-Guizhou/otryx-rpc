# OTRYX RPC 快速开始

> 基线：\`1.0.0-SNAPSHOT\` / JDK 21 / Spring Boot 3.5.4 / Wire v1。源码尚未公开发布到 Maven Central。本指南由**无需 Docker 的单 JVM Memory RPC**起步，再逐步切换为多进程服务发现。历史 Peach RPC Java API 和 OTRYX 命名空间不承诺互通。

## 1. 准备环境并克隆

必须具备 JDK 21；仓库 \`mvnw\` / \`mvnw.cmd\` 固定 Maven 3.9.11，首次安装需网络访问 Maven Central。只有后续 Nacos 跨进程实验才需要 Docker。

\`\`\`bash
git clone https://github.com/Ryan-Guizhou/otryx-rpc.git
cd otryx-rpc
java -version
./mvnw --version
./mvnw -B -ntp -pl otryx-quickstart -am clean package
\`\`\`

Windows CMD/PowerShell 使用 \`mvnw.cmd\` 替代 \`./mvnw\`。运行命令的工作目录始终为本仓库根目录。

## 2. 第一阶段：不启动注册中心

\`\`\`bash
bash scripts/run_quickstart_memory.sh
\`\`\`

或者手动启动（成功后 Ctrl+C 结束）：

\`\`\`bash
java -jar otryx-quickstart/otryx-quickstart-memory/target/otryx-quickstart-memory-1.0.0-SNAPSHOT-exec.jar
\`\`\`

应看到：

\`\`\`text
Quickstart memory RPC succeeded: Hello, Quickstart!
\`\`\`

该进程中既有 \`@OtryxRpcService\` Provider，也有 \`@OtryxRpcReference\` Consumer；RPC 会经过 Memory Registry、Fory、Vert.x loopback 和真实业务实现。**Memory Registry 不能用于两个独立 JVM 的服务发现**。这条实验只允许使用 \`TRUSTED_DEVELOPMENT\` 隔离开发配置。

源码：[Memory Provider](../otryx-quickstart/otryx-quickstart-memory/src/main/java/com/peachsoft/otryx/quickstart/memory/MemoryGreetingServiceImpl.java) · [Memory Consumer](../otryx-quickstart/otryx-quickstart-memory/src/main/java/com/peachsoft/otryx/quickstart/memory/MemoryGreetingRunner.java)。

## 3. 第二阶段：Nacos 下两个独立 JVM

启动注册中心并确认 Nacos 端口 8848 可用：

\`\`\`bash
docker compose -f otryx-quickstart/docker-compose.yml up -d
\`\`\`

如果上一步没有构建 Quickstart，先运行：

\`\`\`bash
./mvnw -B -ntp -pl otryx-quickstart -am clean package
\`\`\`

**终端 A — Provider**：

\`\`\`bash
java -jar otryx-quickstart/otryx-quickstart-provider/target/otryx-quickstart-provider-1.0.0-SNAPSHOT-exec.jar
\`\`\`

预期包含 \`OTRYX RPC server started: bind=0.0.0.0:19090, advertised=127.0.0.1:19090\`。

**终端 B — Consumer**：

\`\`\`bash
java -jar otryx-quickstart/otryx-quickstart-consumer/target/otryx-quickstart-consumer-1.0.0-SNAPSHOT-exec.jar
\`\`\`

预期：

\`\`\`text
RPC quickstart completed successfully: Hello, OTRYX RPC!
\`\`\`

\`\`\`mermaid
sequenceDiagram
    participant P as Provider JVM
    participant R as Nacos Registry
    participant C as Consumer JVM
    P->>R: register ServiceKey / Endpoint
    C->>R: subscribe / lookup
    R-->>C: endpoint snapshot
    C->>P: HELLO / HELLO_ACK
    C->>P: Unary REQUEST
    P-->>C: RESPONSE
\`\`\`

用于 CI 和本地自动化验证（先准备 Nacos）：

\`\`\`bash
OTRYX_RPC_E2E_RESTART_NACOS=false bash scripts/run_quickstart_process_e2e.sh
\`\`\`

脚本校验真实通信、Provider 重启及 Consumer 不退出时的发现恢复；CI 环境还可以重启受控 Nacos 实例。

## 4. 第三阶段：跨三个 JVM 的 Deadline

\`\`\`bash
bash scripts/run_three_process_deadline_e2e.sh
\`\`\`

该脚本运行 Consumer A → Relay Provider B（19091）→ Leaf Provider C（19090），验证 5 秒上游 Deadline、B 的 10 秒本地超时不能扩展上游预算、\`tenant\`/ \`relay\` 元数据随调用传递。预期输出 \`RPC deadline chain completed: CHAIN_OK remainingMillis=...\`。

## 5. 引入单一 Spring Boot Starter

**无需选择 Lite 或 Full**，只保留：

\`\`\`xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
\`\`\`

默认包含 Core、Memory Registry、JDK Proxy、Fory、Vert.x 及 Spring Boot 自动配置。跨 JVM 选择 Nacos 时，业务模块**另外添加**：

\`\`\`xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-registry-nacos</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
\`\`\`

Nacos、Etcd、Consul/Eureka、代理与遥测 Adapter 是可选依赖，不能仅配置 \`registry.type=nacos\` 却不添加对应实现。当前 Snapshot 尚未发布 Central，在外部业务工程使用前先从本仓库运行 \`./mvnw -B -ntp clean install -DskipTests\`。

**接口与注解**直接查看 [GreetingService](../otryx-quickstart/otryx-quickstart-api/src/main/java/com/peachsoft/otryx/quickstart/api/GreetingService.java)、[Provider](../otryx-quickstart/otryx-quickstart-provider/src/main/java/com/peachsoft/otryx/quickstart/provider/GreetingServiceImpl.java) 和 [Consumer](../otryx-quickstart/otryx-quickstart-consumer/src/main/java/com/peachsoft/otryx/quickstart/consumer/GreetingRunner.java)。\`@OtryxRpcContract\` 编译期生成 Stub/Dispatcher，缺失时回退 JDK Proxy/MethodHandle；仅显式幂等方法允许自动重试。

## 6. 可选路线与常见失败

- **Consul/Eureka**：Quickstart 包含各自 profile，外部服务准备完成后两个进程均使用 \`--spring.profiles.active=consul\` 或 \`eureka\`；
- **TLS/mTLS**：配置真实证书、CA、Hostname Verification；网络部署使用 \`NETWORK_MTLS\`、严格 Fory 白名单与 \`RpcAuthorizer\` 业务授权，未授权默认拒绝；
- **Observer**：Quickstart Consumer 的 \`QuickstartCallObserver\` 是已有可测试的 Spring 注入示例；Micrometer/OTel/JFR 按需加入；
- **\`-exec.jar\` 缺失**：先在仓库根目录构建，普通 JAR 不是 Spring Boot 可执行 JAR；
- **Memory 跨 JVM 无法发现**：改用共享 Registry，不要期待进程内注册表跨进程通信；
- **Nacos 连接失败**：检查 8848/9848、Registry type、namespace、Provider advertised address；
- **超时/拒绝**：先查 Provider Ready、Deadline、负载上限，再查服务目录与错误日志。

完整的路径、运行边界、可选 Profiles、期望结果、排障和安全警告见 [中文 Quickstart](../otryx-quickstart/README.md) 或 [English Quickstart](../otryx-quickstart/README.en-US.md)。

## 7. 校验与清理

\`\`\`bash
python3 scripts/check_project.py
python3 scripts/test_quickstart_layout.py
./mvnw -B -ntp clean verify -Pquality
bash scripts/run_quickstart_memory.sh
# Nacos 仍在运行时：
bash scripts/run_three_process_deadline_e2e.sh
docker compose -f otryx-quickstart/docker-compose.yml down
\`\`\`

继续阅读：[Starter 与配置](reference/starter.md) · [模块结构](design/project-structure.md) · [安全指南](security.md) · [运行与排障](operations.md) · [迁移](migration.md) · [FAQ](faq.md)。

