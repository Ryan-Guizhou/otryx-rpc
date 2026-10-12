package com.peachsoft.otryx.core;

/**
 * Consumer 异步关闭阶段的真实结果快照。
 *
 * <p>总预算耗尽时报告可能早于外部 Transport/Registry 资源真正终止；
 * 不能将 Future.cancel 或调用逻辑终态等同于远端业务停止。
 *
 * @param elapsedMillis 首次关闭至本报告生成的耗时
 * @param deadlineExceeded 统一关闭预算是否已耗尽
 * @param directoriesClosed 所有本地目录关闭操作是否成功结束
 * @param transportClosed Transport 关闭调用是否成功返回
 * @param executorsTerminated Consumer 自有执行器是否已终止
 * @param remainingLogicalCalls 报告时仍未完成的逻辑调用数
 * @param cleanupFailures 已观察到的清理失败数量
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 15:32
 */
public record RpcClientShutdownReport(
        long elapsedMillis,
        boolean deadlineExceeded,
        boolean directoriesClosed,
        boolean transportClosed,
        boolean executorsTerminated,
        int remainingLogicalCalls,
        int cleanupFailures) {
}
