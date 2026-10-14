# Registry 健康状态与 Last-Known-Good 陈旧预算（U09-A）

## Consumer 配置

Consumer 的请求热路径始终读取本地服务目录；不会同步请求远端 Registry。

```yaml
otryx:
  rpc:
    client:
      directory-max-staleness: 10s
```

程序化调用可使用 `OtryxRpcClient.builder().directoryFreshnessOptions(
new RpcDirectoryFreshnessOptions(Duration.ofSeconds(10)))`。

默认 `Duration.ZERO` 为不限制，保持当前 LKG 行为。正值只在 Registry Adapter
显式调用 `RegistryListener.onSynchronizationFailed(error)` 后开始消耗本地单调时钟预算。
长时间没有 Watch 变更事件**不等于失联**，也不会让健康目录自行过期。
`onSynchronizationSucceeded()` 必须代表真实同步/健康确认，不能凭空宣称已恢复。

## 状态与语义

| 状态 | 意义 | Consumer 处理 |
| --- | --- | --- |
| NOT_READY | 尚未有首个有效目录快照 | 不路由 |
| HEALTHY | 最近有效快照或真实健康同步 | 按快照路由；空快照代表真实无实例 |
| LAST_KNOWN_GOOD | 已同步过，后续显式失联但未过陈旧窗口 | 保留最近有效实例 |
| STALE | 显式失联且启用的陈旧窗口已耗尽 | 不发起网络请求，明确 UNAVAILABLE 原因 |
| CLOSED | 本地订阅已经关闭 | 拒绝进一步使用，丢弃迟到更新 |

初始空目录、健康空目录和错误导致的陈旧目录不可混淆。
过期判定使用本机 `System.nanoTime()`，不把本地计时器或 Adapter 合成 revision
宣称为跨机器精确时间或外部远端真实版本。旧 revision 快照不能覆盖新快照。

## 范围和待完成项

U09-A 提供 Core 的策略、诊断和兼容性监听接口，不声称所有 Adapter
已经能报告同步失败。U09-B 将 Etcd 的实际 Watch / Range 健康信号接入，
针对 compaction/断开提供有界退避、单飞重连、请求快照合并与关闭时失效保护。
对应代码和 Etcd 集成测试在 U09-B PR 验收；不得将这些能力归因于 U09-A。

Nacos 与 HTTP/Consul/Eureka 的故障同步通知及重建仍由后续 U09-C/D 完成，
随后进行 U09-E 的多进程故障注入。

回归：`ServiceDirectoryTest`、`OtryxRpcClientResilienceTest`。
全量 Maven 与 Examples 以关联 PR 的准确 Head CI 为准。
