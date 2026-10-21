package com.peachsoft.otryx.interceptor;

import com.peachsoft.otryx.api.RpcStatus;

/**
 * 有序、不可修改业务参数与身份的调用生命周期扩展点。
 *
 * <p>所有实现运行于框架专属有界 Worker，不在 Vert.x EventLoop 执行。
 * before/beforeAttempt 可以抛异常拒绝发送；终态回调异常只记录类型，
 * 不覆盖已经确定的业务结果。禁止阻塞等待其它 EventLoop 任务或泄漏上下文。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 16:00
 */
public interface RpcInterceptor {

    /**
     * 启动时排序，较小值先执行 before；相同优先级保持注册顺序。
     *
     * @return 顺序值
     */
    default int order() {
        return 0;
    }

    /**
     * 调用已满足基础准入/授权要求，尚未进入业务或 Consumer 首次尝试。
     *
     * @param call 不含 Payload、秘密或可伪造 Peer 身份的只读上下文
     */
    default void before(RpcInvocation call) {
    }

    /**
     * Consumer 在一次逻辑调用内开始新的网络重试尝试；本地目录重新选择不计为新 Attempt。
     *
     * @param call 只读 Consumer 上下文
     * @param attempt 从 1 开始的尝试次数
     */
    default void beforeAttempt(RpcInvocation call, int attempt) {
    }

    /**
     * Consumer 一次 Attempt 完成。某些未发送、被取消的尝试由逻辑终态归并。
     *
     * @param call 只读 Consumer 上下文
     * @param attempt 从 1 开始的尝试次数
     * @param status 框架分类后的结果，不含底层异常消息
     */
    default void afterAttempt(RpcInvocation call, int attempt, RpcStatus status) {
    }

    /**
     * 调用成功后逆序执行；只用于观察，不允许变更结果。
     *
     * @param call 只读调用上下文
     */
    default void after(RpcInvocation call) {
    }

    /**
     * 失败后逆序执行；包含超时，不能重写实际授权或业务失败原因。
     *
     * @param call 只读调用上下文
     * @param status 框架分类后的失败状态
     */
    default void onFailure(RpcInvocation call, RpcStatus status) {
    }

    /**
     * 显式取消后逆序执行；不把 Future 的逻辑取消当作业务实际停止。
     *
     * @param call 只读调用上下文
     */
    default void onCancel(RpcInvocation call) {
    }
}
