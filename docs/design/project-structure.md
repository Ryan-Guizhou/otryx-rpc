# OTRYX RPC 项目构造思路与模块结构

> 状态：**1.0.0-SNAPSHOT Migration**

## 1. 构造原则

OTRYX RPC 的模块不是按“功能名越细越好”拆分，而是按**第三方依赖隔离、生命周期边界和可替换性**拆分。

原则：

1. Core 只定义稳定契约和运行时；
2. 第三方技术进入独立 Adapter；
3. 编译期能力与运行时能力分离；
4. Quickstart/Benchmarks 不成为业务依赖；
5. Starter 只负责组装，不重新发明 Core 语义；
6. 文档、测试和发布门禁与代码同步演进。

## 2. Maven Reactor

当前根项目为 `com.peachsoft.otryx:otryx-rpc:1.0.0-SNAPSHOT`。采用能力族聚合 POM，具体 Adapter 为独立可发布 Maven 模块。

| 聚合能力族 | 子模块 |
| --- | --- |
| otryx-registry | otryx-registry-etcd、otryx-registry-nacos、otryx-registry-http、otryx-registry-consul、otryx-registry-eureka |
| otryx-serialization | otryx-serialization-fory |
| otryx-transport | otryx-transport-vertx |
| otryx-proxy | otryx-proxy-cglib、otryx-proxy-bytebuddy |
| otryx-observability | otryx-observability-micrometer、otryx-observability-opentelemetry、otryx-observability-jfr |
| otryx-spring-boot | otryx-spring-boot-autoconfigure、otryx-spring-boot-starter |

另保留独立模块 otryx-core、otryx-codegen、otryx-quickstart、otryx-benchmarks。能力族只负责聚合，不引入额外运行时依赖。

## 3. 依赖方向

```mermaid
flowchart TB
    Core[otryx-core]
    Codegen[otryx-codegen]
    Fory[codec-fory]
    Vertx[transport-vertx]
    Etcd[registry-etcd]
    Nacos[registry-nacos]
    Http[registry-http]
    Consul[registry-consul]
    Eureka[registry-eureka]
    Cglib[proxy-cglib]
    ByteBuddy[proxy-bytebuddy]
    Micro[observability-micrometer]
    OTel[observability-opentelemetry]
    Jfr[observability-jfr]
    Auto[Spring Boot autoconfigure]
    Starter[Spring Boot Starter]
    Quickstart[quickstart]
    Bench[benchmarks]

    Codegen --> Core
    Fory --> Core
    Vertx --> Core
    Etcd --> Core
    Nacos --> Core
    Http --> Core
    Consul --> Http
    Eureka --> Http
    Cglib --> Core
    ByteBuddy --> Core
    Micro --> Core
    OTel --> Core
    Jfr --> Core
    Auto --> Core
    Auto --> Fory
    Auto --> Vertx
    Starter --> Auto
    Quickstart --> Starter
    Quickstart --> Nacos
    Quickstart --> Consul
    Quickstart --> Eureka
    Bench --> Core
```

## 4. 为什么 Core 不依赖第三方实现

Core 禁止直接 import：

- Vert.x；
- Etcd；
- Nacos；
- Spring；
- Fory；
- CGLIB；
- Micrometer；
- OpenTelemetry；
- JFR。

这样做使：

- Wire/Registry/Transport 契约可独立测试；
- Adapter 可以替换；
- 业务引入的第三方依赖可控；
- 未来扩展不会迫使 Core API 携带第三方类型。

## 5. 编译期与运行时

```text
Java Interface
   |
@OtryxRpcContract
   |
Annotation Processor
   +--> Generated Consumer Factory
   +--> Generated Server Factory

Runtime
   +--> generated path preferred
   +--> Proxy / MethodHandle fallback
```

把能够在编译期确定的信息提前生成，减少运行时反射和动态装配。

## 6. 单一 Starter 的位置

默认推荐唯一的 `otryx-spring-boot-starter`，只带入 AutoConfiguration / Core / Memory Registry / JDK Proxy / Fory / Vert.x；Etcd、Nacos、Consul/Eureka、CGLIB、ByteBuddy 和可观测性实现通过可选 Adapter 添加：

```xml
<dependency>
    <groupId>com.peachsoft.otryx</groupId>
    <artifactId>otryx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Quickstart 的 Memory 单进程模块只使用 Starter；Nacos 双 JVM 模块另加 `otryx-registry-nacos`。该分层避免 SDK 依赖膨胀，同时保持 Provider/Consumer 注解、Core API、Wire v1 和安全策略一致。非 Spring 用法仍可直接使用 Core 与对应 Adapter。

## 7. 测试结构

- Core Unit / Property / Race；
- Registry Adapter 真实集成；
- Registry Contract TestKit；
- TLS/mTLS；
- OpenTelemetry real RPC trace；
- Independent JVM Quickstart E2E；
- Etcd Chaos；
- Nacos Chaos；
- Rolling Compatibility；
- Benchmark / 10k soak；
- Release Readiness。

## 8. 新贡献者阅读顺序

1. [需求蓝图](requirements-blueprint.md)
2. [技术方案](technical-solution.md)
3. [架构设计](../architecture.md)
4. [调用链详设](detailed-design.md)
5. [SPI](../spi.md)
6. 对应 Adapter 源码与测试
7. [开发与贡献](../engineering/development.md)

## 9. 新增 Adapter 的边界

新增 Registry/Codec/Transport/Proxy/Observability 实现时：

- 优先新模块；
- 不把第三方类型泄漏到 Core；
- 复用现有 Contract Test；
- 定义 close/lifecycle；
- 明确 timeout/线程/阻塞边界；
- 补故障和恢复测试；
- 更新文档与 README 能力矩阵。
