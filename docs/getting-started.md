# OTRYX RPC 快速开始

> OTRYX 1.0.0-SNAPSHOT 属于公开 Java API / GAV 的破坏性命名空间迁移；继承历史 Wire v1 并不代表新旧 Java API 保证互通。详见 [迁移指南](migration.md)。

> 目标：从干净环境运行一个真实的 Provider/Consumer 独立进程调用。

## 1. 前置条件

- JDK 21；
- 无需全局 Maven：仓库内 `mvnw` / `mvnw.cmd` 固定 Maven 3.9.11，首次运行需要联网下载并校验发行包；
- Python 3：用于仓库质量检查；
- Docker（用于官方 Nacos 示例）。

确认：

```bash
java -version
./mvnw -version
docker version
```

**仓库已更名：** [Ryan-Guizhou/otryx-rpc](https://github.com/Ryan-Guizhou/otryx-rpc)。建议使用当前 canonical 地址克隆；历史 peach-rpc 链接可能由 GitHub 重定向，但不再作为新文档的正式地址。

## 2. 获取源码

```bash
git clone https://github.com/Ryan-Guizhou/otryx-rpc.git
cd otryx-rpc
```

## 3. 先验证仓库

```bash
python3 scripts/check_project.py
./mvnw -B -ntp clean verify -Pquality
```

Windows 没有 `python3` 命令时可使用已安装 Python 的等价入口执行同一脚本；所有 `./mvnw` 命令在 Windows CMD/PowerShell 改用 `mvnw.cmd`。Maven Wrapper 下载地址及固定 SHA-256 见 [Maven 结构](reference/maven.md)。

## 4. 启动官方 Nacos 示例环境

在仓库根目录：

```bash
docker compose -f otryx-examples/docker-compose.yml up -d
```

该 Compose 只负责示例所需外部基础设施。

## 5. 构建 Examples

```bash
./mvnw -B -ntp -pl otryx-examples -am clean package
```

## 6. 启动 Provider

```bash
java -jar otryx-examples/otryx-example-provider/target/*-exec.jar
```

成功时会看到类似：

```text
OTRYX RPC server started: bind=0.0.0.0:19090, advertised=127.0.0.1:19090
```

## 7. 启动 Consumer

另一个终端：

```bash
java -jar otryx-examples/otryx-example-consumer/target/*-exec.jar
```

预期：

```text
RPC demo completed successfully: Hello, OTRYX RPC!
```

## 8. 你刚刚运行了什么

```mermaid
sequenceDiagram
    participant P as Provider
    participant N as Nacos
    participant C as Consumer

    P->>N: register
    C->>N: subscribe / lookup
    N-->>C: Provider endpoint
    C->>P: OTRYX RPC REQUEST
    P-->>C: OTRYX RPC RESPONSE
```

Provider 和 Consumer 是两个独立 JVM；Contract 位于共享 API 模块。

## 9. 在 Spring Boot 项目中引入

当前 1.0.0-SNAPSHOT Migration 坐标：

```xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

当前 1.0.0-SNAPSHOT 尚未发布至 Maven Central。需先在仓库根目录执行：

```bash
./mvnw -B -ntp clean install -DskipTests
```

安装完成后，本地 Maven Repository 才能解析相同的 `1.0.0-SNAPSHOT` 坐标。

## 10. Provider 最小代码

```java
@OtryxRpcService(
        interfaceClass = OrderService.class,
        version = "1.0.0")
public class OrderServiceImpl
        implements OrderService {
}
```

## 11. Consumer 最小代码

```java
@OtryxRpcReference(version = "1.0.0")
private OrderService orderService;
```

## 12. 下一步

- [Starter 与完整配置](reference/starter.md)
- [项目构造与模块](design/project-structure.md)
- [技术方案](design/technical-solution.md)
- [TLS/mTLS](security.md)
- [可观测性](observability.md)
- [升级与回滚](upgrade-rollback.md)
- [FAQ](faq.md)

相关入口：[文档中心](index.md) · [用户指南](user-guide.md) · [运行排障](operations.md)。
