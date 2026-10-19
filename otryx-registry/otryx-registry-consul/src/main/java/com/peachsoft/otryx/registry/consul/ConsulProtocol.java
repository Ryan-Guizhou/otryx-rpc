package com.peachsoft.otryx.registry.consul;

/**
 * Consul 应用成员 Health API 的协议字段与固定健康状态。
 *
 * <p>仅用于 Consul Adapter 内部，集中声明区分大小写的对外协议契约。
 *
 * @Author OTRYX RPC Contributors
 * @Version 1.0.0
 * @CreateTime 2026/10/10 18:13
 */
final class ConsulProtocol {

    /** Service 健康查询响应的 JSON 字段。 */
    static final String FIELD_ID = "ID";
    static final String FIELD_ADDRESS = "Address";
    static final String FIELD_PORT = "Port";
    static final String FIELD_META = "Meta";
    static final String FIELD_SERVICE = "Service";
    static final String FIELD_NODE = "Node";
    static final String FIELD_CHECKS = "Checks";

    /** Check 的 JSON 字段和可发现的健康状态。 */
    static final String FIELD_STATUS = "Status";
    static final String STATUS_PASSING = "passing";

    private ConsulProtocol() {
    }
}
