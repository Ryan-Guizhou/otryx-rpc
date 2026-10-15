package com.peachsoft.otryx.api;

/**
 * 应用实例在 RPC 控制面中的角色。
 *
 * <p>Consumer 也是需要登记的应用成员，但不会因为具有应用注册记录而变为可路由的 Provider。
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
public enum ApplicationRole {

    /** 仅对外提供 RPC 服务。 */
    PROVIDER(true, false),

    /** 仅消费 RPC 服务，没有对外提供的 RPC 端点。 */
    CONSUMER(false, true),

    /** 同时提供与消费 RPC 服务。 */
    BOTH(true, true);

    private final boolean provider;
    private final boolean consumer;

    ApplicationRole(boolean provider, boolean consumer) {
        this.provider = provider;
        this.consumer = consumer;
    }

    /**
     * 判断是否具有可对外提供 RPC 服务的角色。
     *
     * @return Provider 或 BOTH 返回 true
     */
    public boolean providesRpc() {
        return provider;
    }

    /**
     * 判断是否具有 RPC 消费端角色。
     *
     * @return Consumer 或 BOTH 返回 true
     */
    public boolean consumesRpc() {
        return consumer;
    }
}
