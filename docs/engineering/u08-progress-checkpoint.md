# U08 验证断点（2026-10-13）

> 本文件记录开发分支客观状态。完成与否以精确 Head 的 GitHub CI 和实测 E2E 为准。

- 基线 main：`8efd4dc9c2f064d58bb67876d4272e57ff84da0e`。
- 开发分支：`feat/u08-monotonic-call-context-20261013`。
- 保持 `1.0.0-SNAPSHOT`、JDK 21、Wire v1、稳定分支和发行状态。

## 代码已提交

- Core 增加 `RpcCallOptions`、`RpcCallContext`、`RpcCallScope`。
- 客户端按单次 > 方法 > Consumer 全局默认选取超时，受父调用剩余期限截断；重试共用同一截止时间。
- Provider 在授权/准入/排队/执行阶段应用单调预算，超时响应与业务真实退出分别结算；执行线程和异步 Stage 完成线程恢复上下文。
- Spring Client Properties / Coordinator 注入方法级配置。
- 请求 Metadata 验证业务上下文数量、总字节数及认证保留键；Wire v1 的毫秒预算采用向下取整，不主动扩张。
- 添加 Core 的方法级、重试、上下文、Provider 超时、伪造身份回归；同步双语 README、协议和 Starter 文档。

## 未完成的验收

- [ ] 精确 Head `mvn -B -ntp clean verify -Pquality` 与 CI/Agent/Style/Docs/Rolling/Examples 检查。
- [ ] 独立 **3 JVM** A→B→C 链式 E2E（包括时钟偏差、执行排队、重试）。
- [ ] Spring Binder 复杂 Method 签名键、Generated/JDK fallback 路径行为等价。
- [ ] 基于准确 Head 审查竞态、授权失败、业务忽略中断与资源释放回归。
- [ ] 全部验收绿灯方可合并 main；main Push CI 通过后关闭 Issue #103。

## 环境与恢复

本次执行容器有 JDK 21，但未安装 Maven；访问 github.com 的 Git DNS 失败，故本地 Maven **NOT EXECUTED**。不得把静态审查代替 CI。开发应以 GitHub PR Actions 的实际报告为准，失败则回到功能分支修复，重新核对 Head SHA。不得强推 main、修改稳定分支或发布。

回滚仅影响本功能分支或对应 U08 PR；主分支基线暂未改动。
