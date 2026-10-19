# Nacos 应用级注册入口

`otryx-registry-nacos` 通过 `ApplicationRegistryFactory` 的 `nacos` 扩展提供应用成员、Manifest 与 ServiceMapping。Provider、Consumer、BOTH 都按应用身份入册；Consumer-only 无 RPC Endpoint。

完整参数、Naming/Config 结构、启动前分片初始化、容量与故障约束见 [Nacos 应用级后端](registry-nacos-v2.md)。可运行教程见 [Quickstart](../../otryx-quickstart/README.md)，真实进程矩阵与计数边界见 [应用级注册验收](application-registry-acceptance.md)。
