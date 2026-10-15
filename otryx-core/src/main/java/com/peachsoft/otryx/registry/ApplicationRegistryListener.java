package com.peachsoft.otryx.registry;

/**
 * 应用目录的变更与健康通知监听器。
 *
 * <p>只有 onSnapshot 可以更新实例列表；健康事件不可冒充新的空快照。
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
@FunctionalInterface
public interface ApplicationRegistryListener {

    /**
     * 接收经过适配器校验的应用目录完整快照。
     *
     * @param snapshot 应用目录快照
     */
    void onSnapshot(ApplicationDirectorySnapshot snapshot);

    /** 注册中心同步正常，当前快照未必有变更。 */
    default void onSynchronizationSucceeded() {
    }

    /**
     * 注册中心同步失败，现存快照的继续使用取决于调用侧新鲜度策略。
     *
     * @param error 同步故障，不能包含凭据
     */
    default void onSynchronizationFailed(Throwable error) {
    }
}
