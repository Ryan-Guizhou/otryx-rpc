package com.peachsoft.otryx.registry;

/**
 * Registry 同步结果监听器。
 *
 * <p>快照是唯一可以替换本地实例列表的消息。仅返回健康信号不得清空目录，
 * 同步失败亦不得伪装成合法空快照；未实现健康信号的旧 Adapter
 * 不会隐式触发 Last-Known-Good 过期。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@FunctionalInterface
public interface RegistryListener {

    /**
     * 发布新的有效服务目录快照。
     *
     * @param snapshot 服务实例快照
     */
    void onSnapshot(RegistrySnapshot snapshot);

    /**
     * 同步或 Watch 连接健康确认，即使订阅数据没有变化。
     */
    default void onSynchronizationSucceeded() {
    }

    /**
     * 同步或 Watch 失效通知，不代表合法空目录。
     *
     * @param error 技术故障原因；不得包含凭据或敏感业务信息
     */
    default void onSynchronizationFailed(Throwable error) {
    }
}
