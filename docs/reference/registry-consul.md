# Consul Registry Adapter

## 目标与边界

`otryx-registry-consul` 基于 Consul HTTP Agent API 实现 OTRYX `Registry`、`ServiceRegistrar` 与异步 `ServiceDiscovery`。当前源码版本为 `1.0.0-SNAPSHOT`。**Registry 只运行在控制面**：RPC 热路径不会访问 Consul。

- Provider 注册：`PUT /v1/agent/service/register`，包含 `ServiceInstance` IP/Port、全量 Metadata 与 TTL Check。
- 健康续期：`PUT /v1/agent/check/pass/service:<instance-id>`；Consul Agent 遗失 Check 后返回 404，下一次续期重新注册。
- 发现：`GET /v1/health/service/<service>?passing=true`，仅信任 passing Health Checks；响应异常时不覆盖最后一个已知视图。
- 订阅：本地计划任务定期执行完整视图查询和去重，变更时按顺序发布快照。**没有声明原生 Watch 或全局 Revision**。
- 注销：`PUT /v1/agent/service/deregister/<instance-id>`。应用关闭时停止续约并尝试注销。

### 依赖坐标

在项目根目录先执行 `mvn -B -ntp clean install -DskipTests`（当前开发版尚未发布 Maven Central），然后在独立 Spring Boot 应用中添加：

```xml
<dependency>
  <groupId>com.peachsoft.otryx</groupId>
  <artifactId>otryx-registry-consul</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

该依赖会传递引入控制面 `otryx-registry-http`；框架的轻量 Starter 不默认包含 Consul。**业务 Provider 和 Consumer 必须显式安装相同的 Registry Adapter**。

## Spring Boot 配置

```yaml
otryx:
  rpc:
    registry:
      type: consul
      endpoints: http://127.0.0.1:8500
      namespace: production
      consul:
        token: ${CONSUL_HTTP_TOKEN:}
        datacenter: ""
        enterprise-namespace: ""
        ttl-seconds: 30
        heartbeat-seconds: 10
        poll-interval-millis: 1000
        request-timeout-millis: 3000
```

`namespace` 是 OTRYX 自身的逻辑命名空间：与服务接口名、版本、RPC group 组合后散列为稳定 Consul Service Name。它**不等于** Consul Enterprise Namespace；后者单独通过 `enterprise-namespace` 配置，开源 Consul 部署通常留空。`endpoints` 为 HTTP(S) URL，无账户口令或 query；本实现要求**恰好一个本地 Consul Agent endpoint**；Agent service/register 与 TTL check/pass 都是同一 Agent 的本地状态，不能跨多个 Agent 随机轮换续约。故障时应由进程或编排系统重启并重新绑定目标 Agent，而不是对 Agent 注册请求做跨节点重试。

Provider 仍需配置面向 Consumer 的 `server.advertised-host` 与 `server.advertised-port`，不能发布 `0.0.0.0`。若使用 Consul ACL Token，应通过环境变量或安全配置注入，不要在 Git 记录。Agent 注册属于**具体 Consul Agent**；切换到其他 Agent 时要重新注册，而非假设 Provider 租约已迁移。

## 资源、失败与恢复

控制面使用专属 2–4 线程、有界 256 队列以及一个调度线程；HTTP 请求有超时。
订阅失败会发出明确的不健康信号，但**不会**把 LKG 替换为健康空视图；
对同一 ServiceKey 的控制面查询单飞，失败后使用不超过 30 秒（或配置轮询周期）的有界指数退避。
恢复后即使实例集合没有变化，也会发送同步成功信号以恢复健康状态；
确有变化时才重新发布带进程内 revision 的完整视图。TLS/mTLS 指的是 **RPC Transport** 的安全配置；Consul HTTP API 的鉴权和 HTTPS 是另一条独立连接，不能相互替代。

`consul.ttl-seconds` 必须大于 `consul.heartbeat-seconds`。Consul 健康探测延迟、Agent 失联与 TTL 的短暂不一致仍应通过业务超时、重试和熔断处理。自动续期只针对由当前 Registry 对象注册的实例，`close()` 幂等。

## 验证

```bash
mvn -B -ntp -pl otryx-registry-consul -am test
```

测试使用进程内 Mock HTTP Agent 验证注册、TTL Passing、健康视图、404 重注册、注销、ACL Header 与共享 Registry Contract。**Mock 测试不是正式 Consul Server 实例的集成/Chaos 验证**；生产部署前还应使用实际目标 Consul 版本单独验证 Agent 故障转移、ACL、TLS、Session/TTL、跨机可路由地址及独立进程 RPC。可通过 [Examples](../../otryx-examples/README.md) 启动独立 Provider/Consumer。

Consul API 官方文档：[Agent Service](https://developer.hashicorp.com/consul/api-docs/agent/service) · [Health](https://developer.hashicorp.com/consul/api-docs/health)。
