package com.peachsoft.otryx.api;

import java.util.Objects;

/**
 * RPC 网络端点。
 *
 * @param host 主机名或 IP 地址
 * @param port TCP 端口
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public record RpcEndpoint(String host, int port) {

    /** TCP 端口范围的下限。 */
    public static final int MIN_PORT = 0;

    /** TCP 端口范围的上限。 */
    public static final int MAX_PORT = 65_535;

    /** 全网 IPv4 监听地址，不可作为远端访问地址。 */
    public static final String UNSPECIFIED_IPV4_HOST = "0.0.0.0";

    /** 全网 IPv6 监听地址，不可作为远端访问地址。 */
    public static final String UNSPECIFIED_IPV6_HOST = "::";

    /** 带方括号的 IPv6 全网监听地址。 */
    public static final String UNSPECIFIED_IPV6_BRACKETED_HOST = "[::]";

    /** 校验端点。 */
    public RpcEndpoint {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
    }

    /**
     * 返回 host:port 形式的端点名称。
     *
     * @return 端点名称
     */
    public String authority() {
        return host + ":" + port;
    }
}
