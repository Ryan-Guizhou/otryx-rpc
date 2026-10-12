package com.peachsoft.otryx.api;

import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry 中的 RPC Wire/Schema 兼容元数据。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/30 11:16
 */
public final class RpcCompatibilityMetadata {

    /** Registry Metadata：Wire Protocol Version。 */
    public static final String PROTOCOL_VERSION =
            "peach.rpc.protocol.version";
    /** Registry Metadata：Schema Fingerprint 规范版本。 */
    public static final String SCHEMA_VERSION =
            "peach.rpc.schema.version";
    /** Registry Metadata：服务 Schema Fingerprint。 */
    public static final String SCHEMA_FINGERPRINT =
            "peach.rpc.schema.fingerprint";

    private RpcCompatibilityMetadata() {
    }

    /**
     * 创建 Provider 发布的兼容元数据。
     *
     * @param key 服务键
     * @param api 服务接口
     * @return Registry Metadata
     */
    public static Map<String, String> providerMetadata(
            ServiceKey key,
            Class<?> api) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(
                PROTOCOL_VERSION,
                Integer.toString(
                        Byte.toUnsignedInt(
                                RpcProtocolCodec.VERSION)));
        values.put(
                SCHEMA_VERSION,
                Integer.toString(
                        RpcSchemaFingerprint.SCHEMA_VERSION));
        values.put(
                SCHEMA_FINGERPRINT,
                RpcSchemaFingerprint.serviceFingerprint(
                        key,
                        api));
        return Map.copyOf(values);
    }

    /**
     * 判定 Provider 与本地服务契约的兼容状态。
     *
     * <p>当前尚未首发版本不保留无 Schema 元数据的隐式兼容路径。
     * 任一缺失、格式不匹配或版本不匹配都视为 INCOMPATIBLE。
     *
     * @param instance Registry 服务实例
     * @param expectedFingerprint 本地期望 Fingerprint
     * @return 兼容状态
     */
    public static Compatibility compatibility(
            ServiceInstance instance,
            String expectedFingerprint) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(
                expectedFingerprint,
                "expectedFingerprint");
        Map<String, String> metadata = instance.metadata();
        String protocol = metadata.get(PROTOCOL_VERSION);
        String schemaVersion = metadata.get(SCHEMA_VERSION);
        String remote = metadata.get(SCHEMA_FINGERPRINT);
        if (!Integer.toString(Byte.toUnsignedInt(RpcProtocolCodec.VERSION))
                        .equals(protocol)
                || !Integer.toString(RpcSchemaFingerprint.SCHEMA_VERSION)
                        .equals(schemaVersion)
                || remote == null
                || remote.isBlank()) {
            return Compatibility.INCOMPATIBLE;
        }
        return expectedFingerprint.equals(remote)
                ? Compatibility.COMPATIBLE
                : Compatibility.INCOMPATIBLE;
    }

    /** 服务实例兼容状态。 */
    public enum Compatibility {
        /** 新节点 Fingerprint 与 Consumer 一致。 */
        COMPATIBLE,
        /** 新节点 Fingerprint 与 Consumer 不一致，必须隔离。 */
        INCOMPATIBLE
    }
}
