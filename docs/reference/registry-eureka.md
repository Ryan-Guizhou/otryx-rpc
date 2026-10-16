# Eureka Registry Adapter (Legacy ServiceKey Path)

> 本文属于旧接口级注册方式，应用级替代实现见 [Eureka V2](registry-eureka-v2.md)。按照总控 #127，旧接口级实现最终由 #139 删除。

## 目标与边界

`otryx-registry-eureka` 通过 Eureka HTTP REST 接口实现 Provider 注册、续约、注销和 Consumer 健康服务发现。当前源码版本是 `1.0.0-SNAPSHOT`。由于 Eureka 没有可直接对应 OTRYX Registry SPI 的原生订阅推送或全局 revision，此适配器用**有界周期轮询**产生新的本地快照，而非伪装原生 Watch。

- 注册：`POST /eureka/apps/<app>`，JSON `instance` 包含 `instanceId`、`app`、`hostName`、`ipAddr`、`port`、`leaseInfo`、`status=UP`、业务元数据。
- 续约：`PUT /eureka/apps/<app>/<instance-id>`，404 表示租约丢失，重新注册。
- 服务发现：`GET /eureka/apps/<app>`，只路由 `UP` 且合法的 OTRYX 服务节点。
- 注销：`DELETE /eureka/apps/<app>/<instance-id>`，不存在的实例按幂等成功处理。
- 订阅：定期查询完整实例视图、去重、单调本地 revision 和有序通知；查询失败不误发布空节点视图。

## Maven 坐标

```xml
<dependency>
  <groupId>com.peachsoft.otryx</groupId>
  <artifactId>otryx-registry-eureka</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

该模块按需引入，不加入默认 Lite/Full Starter；其内部使用 `otryx-registry-http` 共享 HTTP 客户端和有界控制面。当前版本需要从源码 `mvn -B -ntp clean install -DskipTests` 安装，**未声明 Maven Central 已发布**。

## Spring Boot 配置

```yaml
otryx:
  rpc:
    registry:
      type: eureka
      endpoints: http://127.0.0.1:8761/eureka
      namespace: production
      eureka:
        username: ${EUREKA_USERNAME:}
        password: ${EUREKA_PASSWORD:}
        lease-seconds: 30
        heartbeat-seconds: 10
        poll-interval-millis: 1000
        request-timeout-millis: 3000
```

端点必须包含 Eureka Server 的实际 context path（通常为 `/eureka`），不可把 URL 用户信息当成凭证。用户名和密码要么一起配置，要么同时留空；Basic Auth 仅用于服务端支持该方案的部署。真实上线务必使用 HTTPS，避免 Basic Auth 凭证在网络中明文传输。

完整 `ServiceKey(interface, version, group)` 及 OTRYX 逻辑 namespace 会派生固定的 Eureka Application Name，避免跨版本、跨 group 混路由；`instanceId` 派生后保留业务身份元数据，`ServiceInstance.weight` 以 OTRYX 专有 Metadata 传递，**Eureka Server 本身不提供基于该字段的负载均衡**。

## 故障边界

Eureka Lease 是最终一致控制面，不等于单次 RPC 热路径心跳。Consumer 使用本地服务目录；远端订阅校对失败保留最后成功视图，
同时发送失败通知；同一 ServiceKey 的控制面查询单飞，
失败后执行最多 30 秒（或配置轮询周期）的指数退避。
一旦远端查询恢复成功，即使列表无变化也确认同步健康；快照 revision
仅由有变化的完整目录更新递增，不伪造 Eureka 全局版本。应用仍需配置 RPC Timeout、Retry Budget、Circuit Breaker 和 Provider Admission，应独立验证 Eureka Server 的自我保护（Self-Preservation）模式下过期实例清理行为。**过期服务可能短暂出现在缓存中**，不能将 Eureka 注册当作数据面实时存活证据。

`eureka.heartbeat-seconds` 必须小于 `eureka.lease-seconds`；所有网络调用在独立、2–4 线程及有界队列的控制面完成，`close()` 停止 lease renewal 并尽力注销。

## 验证

```bash
mvn -B -ntp -pl otryx-registry-eureka -am test
```

进程内 Mock REST Server 测试覆盖 POST/GET/PUT/DELETE、Basic Auth、UP-only、404 后自恢复及共享 Registry Contract。**这些并不替代真实 Eureka Server（例如 Spring Cloud Netflix Eureka Server）的版本兼容、Self-Preservation/Lease、集群节点失联或独立进程 RPC 测试**。独立 Provider/Consumer 运行方式参见 [Examples](../../otryx-quickstart/README.md)。

Eureka REST 参考：[Netflix Eureka REST Operations](https://github.com/Netflix/eureka/wiki/Eureka-REST-operations)。
