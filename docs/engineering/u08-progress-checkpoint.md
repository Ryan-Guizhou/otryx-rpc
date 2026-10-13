# U08 执行断点（2026-10-13）

> 当前状态：**IN PROGRESS / NOT VERIFIED**。本文不代表完成、通过测试或准许合并。

## 仓库与基线

- 基线 main：`8efd4dc9c2f064d58bb67876d4272e57ff84da0e`，U05 PR #102 已合并，其 post-merge main CI 已通过。
- 开发分支：`feat/u08-monotonic-call-context-20261013`。
- Issue：#103。保留 JDK 21、Wire v1 和 `1.0.0-SNAPSHOT`，稳定分支不动。

## 已写入开发分支（未验收）

1. Core 新增 `RpcCallOptions`、`RpcCallContext`、`RpcCallScope`，定义显式 ThreadLocal scope、父预算上限、Metadata 数量/字节/保留键检查和 Runnable 捕获包装。
2. Client 根据单次选项、方法超时默认值、客户端超时选取预算，并尝试受父上下文的单调截止时间约束；新增 `Builder.methodTimeouts`。
3. Spring Client Properties / Coordinator 新增方法超时映射。
4. 新增 `RpcCallContextTest` 的 scope / deadline / explicit continuation / Metadata 回归。

## 尚未完成（必须继续）

- [ ] 在 Provider 入口创建本机单调截止时间并在业务线程、异步 Stage 回调中正确传播 `RpcCallContext`。
- [ ] 授权、排队、执行、响应四阶段使用统一预算；定时器超时/cancel/响应竞争只有一个逻辑终态，Lease 等实际业务终态后才释放。
- [ ] Client retry、异步 completion 内部上下文捕获恢复；不得假定用户 executor 隐式传递。
- [ ] Wire 发送余量的舍入行为、跨时钟时限测试、Metadata 边界检查、身份注入拒绝、非幂等不重试回归。
- [ ] Generated 与 JDK fallback 等价；独立 JVM A→B→C E2E、重试/排队/时钟差/业务忽略中断。
- [ ] Spring 全链路验证与中文文档、双语 README、Examples。
- [ ] 同一 Head 定向 Maven 测试、`mvn -B -ntp clean verify -Pquality`、完整远程相关 CI；通过才合并 PR，main CI 通过后关闭 Issue #103。

## 当前验证限制

执行环境仅有 Java 与 Git，没有 `mvn`；尝试以 `git clone --depth 1` 获取开发分支时 DNS 失败：`Could not resolve host: github.com`。因此尚未执行任何 Maven/编译/测试命令，所有验证结果为 **NOT EXECUTED**，不能写 PASS。

## 恢复入口

1. 重新检查 `main` 与当前分支 Head/差异，避免覆盖并发改动。
2. 优先评估新类 Javadoc、行长、Checkstyle、Spring 绑定测试以及超时边界正确性，按 RED→GREEN 完成服务端状态机。
3. 在具备 Maven/JDK21 的运行器执行定向和全量质量门禁。
4. **整批功能完成后**再建一个 PR；若 CI 不通过，修复后以新准确 Head 重新核验。不要因本断点已写代码而提前合并。

回滚边界：仅撤销此功能分支/后续 U08 PR；未更改 main、稳定分支、版本或任何发布产物。
