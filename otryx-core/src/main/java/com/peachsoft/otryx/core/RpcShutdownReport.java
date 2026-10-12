package com.peachsoft.otryx.core;

/**
 * Provider 关闭过程中实际观察到的资源状态快照。
 *
 * <p>此报告不能证明应用任务已被强制停止。特别是 Future 取消、
 * shutdownNow 和线程中断不保证用户业务或外部 IO 已退出。
 * 当总预算耗尽时，本记录可能早于后台清理工作完成。
 *
 * @param elapsedMillis 首次开始关闭至报告生成的耗时
 * @param deadlineExceeded 是否超出本轮统一关闭预算
 * @param registrationsRemoved 服务注销是否全部完成且成功
 * @param drained Transport 排空是否成功
 * @param transportClosed 是否已调用且成功返回 Transport.close
 * @param executorsTerminated 执行器是否全部确认终止
 * @param remainingAdmissionLeases 报告时仍被实际工作持有的准入租约数
 * @param cleanupFailures 清理期间已检测到的失败阶段数量
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 15:32
 */
public record RpcShutdownReport(
        long elapsedMillis,
        boolean deadlineExceeded,
        boolean registrationsRemoved,
        boolean drained,
        boolean transportClosed,
        boolean executorsTerminated,
        int remainingAdmissionLeases,
        int cleanupFailures) {
}
