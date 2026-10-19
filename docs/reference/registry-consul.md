# Consul 应用级注册入口

`otryx-registry-consul` 通过 `ApplicationRegistryFactory` 的 `consul` 扩展提供 Agent 应用成员、TTL 健康检查、Session 身份 Claim 与 KV 元数据。一个进程实例只注册一个应用成员，导出接口存于 Manifest/Mapping。

完整参数、单 Agent 约束、ACL、CAS、容量与故障边界见 [Consul 应用级后端](registry-consul-v2.md) / [English](registry-consul-v2.en-US.md)。独立 Provider/Consumer JVM 场景见 [Quickstart](../../otryx-quickstart/README.md)，证据要求见 [应用级注册验收](application-registry-acceptance.md)。
