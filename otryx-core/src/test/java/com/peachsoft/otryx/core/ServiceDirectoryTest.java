package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.RpcCompatibilityMetadata;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceDiscovery;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
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
    void initialNotReadyMustNotBeConfusedWithValidEmptySnapshot() {
        ServiceKey key = new ServiceKey("example.Service", "1.0.0", "default");
        CapturingRegistry registry = new CapturingRegistry();
        try (ServiceDirectory directory = new ServiceDirectory(registry, key)) {
            assertEquals(ServiceDirectory.Status.NOT_READY, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
            registry.publish(new RegistrySnapshot(List.of(), 1L));
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

    @Test
    void unhealthyLastKnownGoodExpiresOnlyAfterExplicitFailure() {
        ServiceKey key = new ServiceKey("example.Service", "1.0.0", "default");
        CapturingRegistry registry = new CapturingRegistry();
        AtomicLong monotonicClock = new AtomicLong(10_000L);
        RpcDirectoryFreshnessOptions options =
                new RpcDirectoryFreshnessOptions(Duration.ofNanos(100L));
        ServiceInstance instance = instance(key, "healthy", 19091);
        try (ServiceDirectory directory = new ServiceDirectory(
                registry, key, null, options, monotonicClock::get)) {
            registry.publish(new RegistrySnapshot(List.of(instance), 1L));
            monotonicClock.addAndGet(20_000L);
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status(),
                    "A quiet healthy Watch does not make its directory stale");
            assertArrayEquals(new ServiceInstance[] {instance}, directory.snapshot());
            registry.fail();
            monotonicClock.addAndGet(99L);
            assertEquals(ServiceDirectory.Status.LAST_KNOWN_GOOD, directory.status());
            assertArrayEquals(new ServiceInstance[] {instance}, directory.snapshot());
            monotonicClock.incrementAndGet();
            assertEquals(ServiceDirectory.Status.STALE, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
            registry.healthy();
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
            assertArrayEquals(new ServiceInstance[] {instance}, directory.snapshot());
            registry.publish(new RegistrySnapshot(List.of(), 2L));
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

    @Test
    void failedInitialSubscriptionCannotFabricateHealthyEmptySnapshot() {
        ServiceKey key = new ServiceKey("example.Service", "1.0.0", "default");
        CapturingRegistry registry = new CapturingRegistry();
        try (ServiceDirectory directory = new ServiceDirectory(registry, key)) {
            registry.fail();
            registry.healthy();
            assertEquals(ServiceDirectory.Status.NOT_READY, directory.status());
        }
    }

    @Test
    void closeMustRejectFurtherDirectoryReadsAndUpdates() {
        ServiceKey key = new ServiceKey("example.Service", "1.0.0", "default");
        CapturingRegistry registry = new CapturingRegistry();
        ServiceDirectory directory = new ServiceDirectory(registry, key);
        ServiceInstance instance = instance(key, "live", 19091);
        registry.publish(new RegistrySnapshot(List.of(instance), 1L));
        assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
        directory.close();
        registry.publish(new RegistrySnapshot(List.of(instance), 2L));
        assertEquals(ServiceDirectory.Status.CLOSED, directory.status());
        assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        directory.close();
    }

    @Test
    void unlimitedDefaultMustKeepLastGoodSnapshotAvailable() {
        ServiceKey key = new ServiceKey("example.Service", "1.0.0", "default");
        CapturingRegistry registry = new CapturingRegistry();
        ServiceInstance instance = instance(key, "healthy", 19091);
        try (ServiceDirectory directory = new ServiceDirectory(registry, key)) {
            registry.publish(new RegistrySnapshot(List.of(instance), 1L));
            registry.fail();
            assertEquals(ServiceDirectory.Status.LAST_KNOWN_GOOD, directory.status());
            assertArrayEquals(new ServiceInstance[] {instance}, directory.snapshot());
        }
    }

    @Test
    void invalidDurationMustFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new RpcDirectoryFreshnessOptions(Duration.ofSeconds(-1L)));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcDirectoryFreshnessOptions(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void olderSnapshotMustNotRestoreExpiredLastGoodState() {
        ServiceKey key = new ServiceKey("example.Service", "1.0.0", "default");
        CapturingRegistry registry = new CapturingRegistry();
        AtomicLong clock = new AtomicLong(1_000L);
        RpcDirectoryFreshnessOptions options =
                new RpcDirectoryFreshnessOptions(Duration.ofNanos(10L));
        try (ServiceDirectory directory = new ServiceDirectory(
                registry, key, null, options, clock::get)) {
            registry.publish(new RegistrySnapshot(List.of(instance(key, "fresh", 19091)), 3L));
            registry.fail();
            clock.addAndGet(10L);
            assertEquals(ServiceDirectory.Status.STALE, directory.status());
            registry.publish(new RegistrySnapshot(List.of(instance(key, "old", 19092)), 2L));
            assertEquals(ServiceDirectory.Status.STALE, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

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

        private void fail() {
            RegistryListener current = listener.get();
            if (current != null) {
                current.onSynchronizationFailed(new RpcUnavailableException("Registry disconnected"));
            }
        }

        private void healthy() {
            RegistryListener current = listener.get();
            if (current != null) {
                current.onSynchronizationSucceeded();
            }
        }

    }
}
