package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证应用级 Registry SPI 的能力、快照与订阅约束。
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
class ApplicationRegistryContractTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("default", "peach-order");

    @Test
    void discoveryOnlyRegistryDoesNotExposeRegistrar() {
        ApplicationRegistry registry = new DiscoveryOnlyApplicationRegistry(
                RegistryCapabilities.of(RegistryCapability.SUBSCRIPTION, RegistryCapability.REVISION));
        assertTrue(registry.registrar().isEmpty());
    }

    @Test
    void mismatchedRegistrationCapabilityFailsFast() {
        ApplicationRegistry registry = new DiscoveryOnlyApplicationRegistry(
                RegistryCapabilities.of(RegistryCapability.REGISTRATION));
        assertThrows(IllegalStateException.class, registry::registrar);
    }

    @Test
    void directoryRejectsForeignOrDuplicateMembers() {
        ApplicationInstance first = new ApplicationInstance(
                "one", APPLICATION, ApplicationRole.CONSUMER, null, 0, null, Map.of());
        ApplicationInstance other = new ApplicationInstance(
                "two", new ApplicationKey("default", "another"),
                ApplicationRole.CONSUMER, null, 0, null, Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                new ApplicationDirectorySnapshot(APPLICATION, List.of(first, other), 1));
        assertThrows(IllegalArgumentException.class, () ->
                new ApplicationDirectorySnapshot(APPLICATION, List.of(first, first), 1));
        assertThrows(IllegalArgumentException.class, () ->
                new ApplicationDirectorySnapshot(APPLICATION, List.of(first), -1));
    }

    @Test
    void directoryIncludesConsumerMembershipButDoesNotMakeItRoutable() {
        ApplicationInstance provider = new ApplicationInstance(
                "provider", APPLICATION, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19090), 100, "rev", Map.of());
        ApplicationInstance consumer = new ApplicationInstance(
                "consumer", APPLICATION, ApplicationRole.CONSUMER, null, 0, null, Map.of());
        ApplicationDirectorySnapshot snapshot =
                new ApplicationDirectorySnapshot(APPLICATION, List.of(provider, consumer), 3L);
        assertEquals(2, snapshot.instances().size());
        assertEquals(1, snapshot.instances().stream()
                .filter(ApplicationInstance::routableProvider).count());
        assertFalse(consumer.routableProvider());
        assertEquals(3L, snapshot.revision());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.instances().clear());
    }

    @Test
    void listenerReceivesIndependentHealthSignals() {
        AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();
        ApplicationRegistryListener listener = new ApplicationRegistryListener() {
            @Override
            public void onSnapshot(ApplicationDirectorySnapshot snapshot) {
                latest.set(snapshot);
            }
        };
        listener.onSnapshot(new ApplicationDirectorySnapshot(APPLICATION, List.of(), 0));
        listener.onSynchronizationSucceeded();
        listener.onSynchronizationFailed(new IllegalStateException("offline"));
        assertEquals(0, latest.get().instances().size());
    }

    private static final class DiscoveryOnlyApplicationRegistry implements ApplicationRegistry {
        private final RegistryCapabilities values;

        private DiscoveryOnlyApplicationRegistry(RegistryCapabilities values) {
            this.values = values;
        }

        @Override
        public RegistryCapabilities capabilities() {
            return values;
        }

        @Override
        public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
            return CompletableFuture.completedFuture(
                    new ApplicationDirectorySnapshot(application, List.of(), 1));
        }

        @Override
        public RegistrySubscription subscribe(
                ApplicationKey application, ApplicationRegistryListener listener) {
            listener.onSnapshot(new ApplicationDirectorySnapshot(application, List.of(), 1));
            return () -> {
            };
        }

        @Override
        public void close() {
        }
    }
}
