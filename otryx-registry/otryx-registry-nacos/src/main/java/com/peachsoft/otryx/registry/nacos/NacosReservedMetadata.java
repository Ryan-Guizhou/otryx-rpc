package com.peachsoft.otryx.registry.nacos;

/**
 * OTRYX RPC 在 Nacos metadata 中保留的键。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:35
 */
final class NacosReservedMetadata {

    static final String PREFIX = "otryx.rpc.";
    static final String INSTANCE_ID = PREFIX + "instance-id";
    static final String INTERFACE = PREFIX + "interface";
    static final String VERSION = PREFIX + "version";
    static final String GROUP = PREFIX + "group";
    static final String PROTOCOL = PREFIX + "protocol";
    static final String PROTOCOL_NAME = "otryx";
    static final String CLUSTER = PREFIX + "cluster";

    private NacosReservedMetadata() {
    }

    static boolean adapterOwned(String key) {
        return INSTANCE_ID.equals(key)
                || INTERFACE.equals(key)
                || VERSION.equals(key)
                || GROUP.equals(key)
                || PROTOCOL.equals(key)
                || CLUSTER.equals(key);
    }
}
