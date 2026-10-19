# Eureka 应用级注册入口

`otryx-registry-eureka` 通过 `ApplicationRegistryFactory` 的 `eureka` 扩展提供原生应用成员和 Lease。Manifest/Mapping 必须显式存于独立的 Etcd、Nacos 或 Consul 后端；缺少配置直接失败。纯 Consumer 注册无可用 RPC 端口的成员，不能被路由。

完整配置、Owner 交叉校验、容量与故障边界见 [Eureka 应用级后端](registry-eureka-v2.md) / [English](registry-eureka-v2.en-US.md)。当前真实独立进程矩阵使用 Eureka + Consul 元数据，见 [应用级注册验收](application-registry-acceptance.md)。
