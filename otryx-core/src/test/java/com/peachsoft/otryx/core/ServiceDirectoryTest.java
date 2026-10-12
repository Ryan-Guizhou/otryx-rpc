package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.peachsoft.otryx.api.RpcCompatibilityMetadata;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceDiscovery;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证服务目录注册、查询及实例选择逻辑。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
class ServiceDirectoryTest {

    @Test
    void shouldIgnoreOlderSnapshots() {
        ServiceKey key = new ServiceKey("example.Service", "1.0.0", "default");
        CapturingRegistry registry = new CapturingRegistry();

        try (ServiceDirectory directory = new ServiceDirectory(registry, key)) {
            ServiceInstance newer = instance(key, "newer", 19091);
            ServiceInstance older = instance(key, "older", 19092);

            registry.publish(new RegistrySnapshot(List.of(newer), 20));
            registry.publish(new RegistrySnapshot(List.of(older), 19));

            assertArrayEquals(new ServiceInstance[] {newer}, directory.snapshot());
        }
    }

    @Test
    void shouldFilterMissingAndMismatchedSchemaMetadata() {
        ServiceKey key = new ServiceKey(
                "example.Service",
                "1.0.0",
                "default");
        CapturingRegistry registry = new CapturingRegistry();

        try (ServiceDirectory directory =
                     new ServiceDirectory(
                             registry,
                             key,
                             "expected")) {
            ServiceInstance matching = new ServiceInstance(
                    "matching",
                    key,
                    new RpcEndpoint("127.0.0.1", 19091),
                    100,
                    Map.of(
                            RpcCompatibilityMetadata.PROTOCOL_VERSION, "1",
                            RpcCompatibilityMetadata.SCHEMA_VERSION,
                            Integer.toString(
                                    com.peachsoft.otryx.api.RpcSchemaFingerprint.SCHEMA_VERSION),
                            RpcCompatibilityMetadata.SCHEMA_FINGERPRINT,
                            "expected"));
            ServiceInstance legacy = instance(
                    key,
                    "legacy",
                    19092);
            ServiceInstance mismatch = new ServiceInstance(
                    "mismatch",
                    key,
                    new RpcEndpoint("127.0.0.1", 19093),
                    100,
                    Map.of(
                            RpcCompatibilityMetadata.PROTOCOL_VERSION, "1",
                            RpcCompatibilityMetadata.SCHEMA_VERSION,
                            Integer.toString(
                                    com.peachsoft.otryx.api.RpcSchemaFingerprint.SCHEMA_VERSION),
                            RpcCompatibilityMetadata.SCHEMA_FINGERPRINT,
                            "different"));

            registry.publish(new RegistrySnapshot(
                    List.of(
                            matching,
                            legacy,
                            mismatch),
                    1));

            assertArrayEquals(
                    new ServiceInstance[] {
                            matching
                    },
                    directory.snapshot());
        }
    }

    private static ServiceInstance instance(ServiceKey key, String id, int port) {
        return new ServiceInstance(
                id,
                key,
                new RpcEndpoint("127.0.0.1", port),
                100,
                Map.of());
    }

    private static final class CapturingRegistry implements ServiceDiscovery {
        private final AtomicReference<RegistryListener> listener = new AtomicReference<>();

        @Override
        public CompletionStage<RegistrySnapshot> lookup(ServiceKey key) {
            return CompletableFuture.completedFuture(new RegistrySnapshot(List.of(), 0));
        }

        @Override
        public RegistrySubscription subscribe(ServiceKey key, RegistryListener value) {
            listener.set(value);
            return () -> listener.compareAndSet(value, null);
        }

        private void publish(RegistrySnapshot snapshot) {
            RegistryListener current = listener.get();
            if (current != null) {
                current.onSnapshot(snapshot);
            }
        }

    }
}
