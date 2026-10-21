package com.peachsoft.otryx.api;

import java.util.Objects;

/**
 * 同时绑定应用身份与连接地址的不可变路由目标。
 *
 * @param application 预期对端的完整应用身份
 * @param endpoint 可连接的远端地址，不接受监听占位地址
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public record RpcTarget(ApplicationKey application, RpcEndpoint endpoint) {

    /** 校验实际连接目标，不执行 DNS 或其他 IO。 */
    public RpcTarget {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(endpoint, "endpoint");
        String host = endpoint.host();
        if (endpoint.port() == RpcEndpoint.MIN_PORT
                || RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host)
                || "0:0:0:0:0:0:0:0".equals(host)
                || "[0:0:0:0:0:0:0:0]".equals(host)
                || "*".equals(host)) {
            throw new IllegalArgumentException("RPC target must have a connectable endpoint");
        }
    }
}
