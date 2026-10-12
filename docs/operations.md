# OTRYX RPC 运行与排障

本页描述现有工程的基础运行检查，不能代替目标环境的容量评估或发布审批。

## 1. 先验证工程

仓库根目录运行：

```bash
python3 scripts/check_project.py
mvn -B -ntp clean verify -Pquality
bash scripts/run_example_process_e2e.sh
```

第三条命令运行独立 JVM 示例；其外部服务、端口和前置条件以 [Examples](../otryx-examples/README.md) 和脚本为准。CI 中还包含 Nacos Integration、Compatibility、Chaos、Benchmark Smoke 等独立工作流；不代表每次本地运行均执行了这些测试。

## 2. 常见失败的定位次序

| 现象 | 首先确认 | 详细资料 |
|---|---|---|
| Registry 未发现实例 | Nacos/Etcd 的服务地址、命名空间、订阅与 Provider 注册是否一致 | [Nacos](reference/registry-nacos.md) |
| RPC 超时 | 整体 Deadline、连接状态、Provider Inflight 和线程/CPU 资源 | [配置](configuration.md) |
| TLS/mTLS 握手失败 | 信任链、hostname verification、证书文件、客户端证书模式 | [安全](security.md) |
| 过载和请求拒绝 | Admission、backpressure、并发、Retry Budget，拒绝原因 | [Provider Admission](reference/provider-admission.md) |
| Trace/指标缺失 | Adapter 依赖、采集/exporter 配置和采样 | [可观测性](observability.md) |
| 滚动升级失败 | 类型、方法和 Schema Fingerprint、业务契约、Registry 隔离 | [升级回滚](upgrade-rollback.md) |

## 3. 容量与长期稳定性

固定 Runner 环境下重复进行矩阵实验、记录分位延迟、GC、allocation 和错误率。10k 逻辑并发是 soak harness 的**测试负载设置**，不等于已证明生产可长期稳定支撑 10k 并发。

原始证据与脚本详见 [性能证据](reference/performance-evidence.md)、[容量规划](reference/capacity-planning.md) 和 [Soak 验收](reference/soak-acceptance.md)。

## 4. 关闭、排空与剩余业务

Provider 使用单一单调时钟总预算：先停止接收新请求，批量注销 Registry，再等待 Transport 排空，最后尽力清理 Transport 与执行器。它不会为每个注销操作重新获得完整超时。直接调用 Java `close()` 是非阻塞地发起默认预算关闭；若需要确定结果，应调用 `shutdown(new RpcShutdownOptions(totalTimeout))` 并等待返回的 Stage。

`RpcShutdownReport` 分别报告是否超时、是否全部注销、是否排空、Transport 是否关闭、Executor 是否真正终止、还有多少 Admission Lease 仍被占用及已检测的清理失败数。若外部 SDK 或用户业务不响应中断，框架只能给出有界终态报告，不能承诺强制停止底层工作。Spring 模式的 `otryx.rpc.server.shutdown-timeout` 默认为 25 秒，`SmartLifecycle.stop(Runnable)` 会在报告完成后调用回调；应与 `spring.lifecycle.timeout-per-shutdown-phase` 联合校准。

Consumer 调用 `close()` 会立即拒绝新的引用/调用，并发起非阻塞关闭；若需确定清理结果，可等待 `shutdown(RpcShutdownOptions)` 返回的 `CompletionStage<RpcClientShutdownReport>`。框架后台结算 Pending 逻辑调用、取消 Deadline/延迟重试，将目录关闭与 Transport 关闭分开隔离，避免单个不响应中断的 SPI 阻断其他清理。`RpcClientShutdownReport` 中的 `transportClosed`、`directoriesClosed`、`executorsTerminated` 与 `remainingLogicalCalls` 必须分别解释，不得在超时报告中声称资源已全部释放。自行实现的 Transport/Registry 仍需遵守资源所有权和可重入关闭契约；不要以调用 `Future.cancel` 证明远端业务实际停止。

## 5. 兼容、回滚与风险边界

- Peach RPC 1.x 到 OTRYX 1.0 的公开 API 命名空间变更属于 Breaking Change；**不要直接将两代服务混部视为受支持**。
- 保留旧版稳定分支，先使用分区部署或蓝绿过渡，并以真实业务契约验证互通边界。
- `1.0.0-SNAPSHOT` 非 GA；商标、参考素材和 Maven Central 发布权限仍有独立验收前置条件。

参见 [迁移指南](migration.md)、[Wire 兼容性](wire-compatibility.md) 与 [公开发布核查](publication-readiness.md)。
