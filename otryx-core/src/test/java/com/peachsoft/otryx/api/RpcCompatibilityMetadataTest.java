package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 Registry 兼容元数据的判断规则与不匹配处理。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/30 11:16
 */
class RpcCompatibilityMetadataTest {

    @Test
    void shouldRejectProviderWithoutSchemaMetadata() {
        ServiceInstance instance = instance(Map.of());

        assertEquals(
                RpcCompatibilityMetadata.Compatibility.INCOMPATIBLE,
                RpcCompatibilityMetadata.compatibility(
                        instance,
                        "expected"));
    }

    @Test
    void shouldRejectExplicitSchemaMismatch() {
        ServiceInstance instance = instance(validMetadata("remote"));

        assertEquals(
                RpcCompatibilityMetadata.Compatibility.INCOMPATIBLE,
                RpcCompatibilityMetadata.compatibility(
                        instance,
                        "expected"));
    }

    @Test
    void shouldAcceptMatchingSchema() {
        ServiceInstance instance = instance(validMetadata("expected"));

        assertEquals(
                RpcCompatibilityMetadata.Compatibility.COMPATIBLE,
                RpcCompatibilityMetadata.compatibility(
                        instance,
                        "expected"));
    }

    @Test
    void protocolVersionMismatchMustRejectProvider() {
        java.util.Map<String, String> metadata =
                new java.util.HashMap<>(validMetadata("expected"));
        metadata.put(RpcCompatibilityMetadata.PROTOCOL_VERSION, "999");
        assertEquals(
                RpcCompatibilityMetadata.Compatibility.INCOMPATIBLE,
                RpcCompatibilityMetadata.compatibility(
                        instance(metadata), "expected"));
    }

    @Test
    void schemaVersionMismatchMustRejectProvider() {
        java.util.Map<String, String> metadata =
                new java.util.HashMap<>(validMetadata("expected"));
        metadata.put(RpcCompatibilityMetadata.SCHEMA_VERSION, "999");
        assertEquals(
                RpcCompatibilityMetadata.Compatibility.INCOMPATIBLE,
                RpcCompatibilityMetadata.compatibility(
                        instance(metadata), "expected"));
    }

    private static Map<String, String> validMetadata(String fingerprint) {
        return Map.of(
                RpcCompatibilityMetadata.PROTOCOL_VERSION, "1",
                RpcCompatibilityMetadata.SCHEMA_VERSION,
                Integer.toString(RpcSchemaFingerprint.SCHEMA_VERSION),
                RpcCompatibilityMetadata.SCHEMA_FINGERPRINT, fingerprint);
    }

    private static ServiceInstance instance(
            Map<String, String> metadata) {
        return new ServiceInstance(
                "node-1",
                new ServiceKey(
                        "demo.Service",
                        "1.0.0",
                        "default"),
                new RpcEndpoint("127.0.0.1", 19090),
                100,
                metadata);
    }
}
