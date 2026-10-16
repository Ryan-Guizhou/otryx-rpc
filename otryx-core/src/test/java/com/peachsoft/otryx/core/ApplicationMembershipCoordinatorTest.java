package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.memory.MemoryApplicationManifestRepository;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.registry.memory.MemoryServiceMappingRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 验证 Provider/Consumer 双向应用身份与映射所有权生命周期。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
class ApplicationMembershipCoordinatorTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("prod", "payments");
    private static final ServiceKey SERVICE =
            new ServiceKey(Payment.class.getName(), "1.0.0", "default");
    private static final ServiceMappingKey MAPPING = new ServiceMappingKey("prod", SERVICE);
    private static final RpcEndpoint ENDPOINT = new RpcEndpoint("127.0.0.1", 18080);

    @Test
    void consumerOnlyThenBothThenConsumerOnlyUsesSingleStableMembership() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
                MemoryApplicationManifestRepository manifests = new MemoryApplicationManifestRepository()) {
            MemoryServiceMappingRepository mappings = new MemoryServiceMappingRepository();
            ApplicationMembershipCoordinator coordinator = coordinator(
                    registry.registrar().orElseThrow(), manifests, mappings, "instance-1");
            try {
                coordinator.startConsumer().toCompletableFuture().join();
                coordinator.startConsumer().toCompletableFuture().join();
                assertEquals(ApplicationRole.CONSUMER, sole(registry).role());
                assertFalse(sole(registry).routableProvider());
                coordinator.prepareProvider(exported()).toCompletableFuture().join();
                assertEquals(ApplicationRole.CONSUMER, sole(registry).role());
                assertEquals(Set.of(APPLICATION),
                        mappings.lookup(MAPPING).toCompletableFuture().join().applications());
                coordinator.providerReady(ENDPOINT).toCompletableFuture().join();
                assertEquals(ApplicationRole.BOTH, sole(registry).role());
                assertEquals(ENDPOINT, sole(registry).rpcEndpoint());
                coordinator.stopProvider().toCompletableFuture().join();
                assertEquals(ApplicationRole.CONSUMER, sole(registry).role());
                assertTrue(mappings.lookup(MAPPING).toCompletableFuture().join().applications().isEmpty());
                coordinator.stopConsumer().toCompletableFuture().join();
                assertTrue(registry.lookup(APPLICATION).toCompletableFuture().join().instances().isEmpty());
            } finally {
                coordinator.shutdown().toCompletableFuture().join();
            }
        }
    }

    @Test
    void multipleProvidersAndManifestRevisionsMustRetainRemainingOwner() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
                MemoryApplicationManifestRepository manifests = new MemoryApplicationManifestRepository()) {
            MemoryServiceMappingRepository mappings = new MemoryServiceMappingRepository();
            ApplicationMembershipCoordinator first = coordinator(
                    registry.registrar().orElseThrow(), manifests, mappings, "instance-1");
            ApplicationMembershipCoordinator second = coordinator(
                    registry.registrar().orElseThrow(), manifests, mappings, "instance-2");
            try {
                first.prepareProvider(exported()).toCompletableFuture().join();
                second.prepareProvider(exported()).toCompletableFuture().join();
                first.providerReady(ENDPOINT).toCompletableFuture().join();
                second.providerReady(new RpcEndpoint("127.0.0.1", 18081)).toCompletableFuture().join();
                assertEquals(2, registry.lookup(APPLICATION).toCompletableFuture().join().instances().size());
                first.stopProvider().toCompletableFuture().join();
                assertEquals(Set.of(APPLICATION),
                        mappings.lookup(MAPPING).toCompletableFuture().join().applications());
                assertEquals(1, registry.lookup(APPLICATION).toCompletableFuture().join().instances().size());
                assertEquals("instance-2", sole(registry).instanceId());
                second.stopProvider().toCompletableFuture().join();
                assertTrue(mappings.lookup(MAPPING).toCompletableFuture().join().applications().isEmpty());
            } finally {
                first.shutdown().toCompletableFuture().join();
                second.shutdown().toCompletableFuture().join();
            }
        }
    }

    @Test
    void failedProviderRegistrationCanRollbackMappingWithoutDroppingConsumer() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
                MemoryApplicationManifestRepository manifests = new MemoryApplicationManifestRepository()) {
            MemoryServiceMappingRepository mappings = new MemoryServiceMappingRepository();
            ApplicationRegistrar rejecting = new ApplicationRegistrar() {
                @Override
                public CompletionStage<Void> register(ApplicationInstance instance) {
                    if (instance.role().providesRpc()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("No provider permission"));
                    }
                    return registry.register(instance);
                }

                @Override
                public CompletionStage<Void> unregister(ApplicationInstance instance) {
                    return registry.unregister(instance);
                }
            };
            ApplicationMembershipCoordinator coordinator = coordinator(rejecting, manifests, mappings, "failure");
            try {
                coordinator.startConsumer().toCompletableFuture().join();
                coordinator.prepareProvider(exported()).toCompletableFuture().join();
                assertThrows(CompletionException.class, () ->
                        coordinator.providerReady(ENDPOINT).toCompletableFuture().join());
                coordinator.stopProvider().toCompletableFuture().join();
                assertEquals(ApplicationRole.CONSUMER, sole(registry).role());
                assertTrue(mappings.lookup(MAPPING).toCompletableFuture().join().applications().isEmpty());
            } finally {
                coordinator.shutdown().toCompletableFuture().join();
            }
            assertTrue(registry.lookup(APPLICATION).toCompletableFuture().join().instances().isEmpty());
        }
    }

    @Test
    void concurrentCloseHasSingleBoundedResultAndPreventsNewRegistration() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
                MemoryApplicationManifestRepository manifests = new MemoryApplicationManifestRepository()) {
            MemoryServiceMappingRepository mappings = new MemoryServiceMappingRepository();
            ApplicationMembershipCoordinator coordinator = coordinator(
                    registry.registrar().orElseThrow(), manifests, mappings, "shutdown");
            coordinator.startConsumer().toCompletableFuture().join();
            coordinator.prepareProvider(exported()).toCompletableFuture().join();
            coordinator.providerReady(ENDPOINT).toCompletableFuture().join();
            CompletionStage<Void> first = coordinator.shutdown();
            CompletionStage<Void> duplicate = coordinator.shutdown();
            assertSame(first, duplicate);
            first.toCompletableFuture().join();
            assertThrows(CompletionException.class, () ->
                    coordinator.startConsumer().toCompletableFuture().join());
            assertTrue(registry.lookup(APPLICATION).toCompletableFuture().join().instances().isEmpty());
            assertTrue(mappings.lookup(MAPPING).toCompletableFuture().join().applications().isEmpty());
        }
    }

    @Test
    void hangingRegistryUnregisterMustNotHangShutdownOrDeleteOtherInstances() throws Exception {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
                MemoryApplicationManifestRepository manifests = new MemoryApplicationManifestRepository()) {
            MemoryServiceMappingRepository mappings = new MemoryServiceMappingRepository();
            ApplicationRegistrar blocked = new ApplicationRegistrar() {
                @Override
                public CompletionStage<Void> register(ApplicationInstance instance) {
                    return registry.register(instance);
                }

                @Override
                public CompletionStage<Void> unregister(ApplicationInstance instance) {
                    return new CompletableFuture<>();
                }
            };
            ApplicationMembershipCoordinator coordinator = new ApplicationMembershipCoordinator(
                    APPLICATION, "hung", blocked, manifests, mappings, Duration.ofMillis(80));
            coordinator.startConsumer().toCompletableFuture().join();
            long started = System.nanoTime();
            assertThrows(CompletionException.class,
                    () -> coordinator.shutdown().toCompletableFuture().join());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000L);
            assertEquals(1, registry.lookup(APPLICATION).toCompletableFuture().join().instances().size());
            assertThrows(CompletionException.class,
                    () -> coordinator.startConsumer().toCompletableFuture().join());
        }
    }

    @Test
    void providerMustRejectFakeEndpointBeforeBecomingVisible() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
                MemoryApplicationManifestRepository manifests = new MemoryApplicationManifestRepository()) {
            MemoryServiceMappingRepository mappings = new MemoryServiceMappingRepository();
            ApplicationMembershipCoordinator coordinator = coordinator(
                    registry.registrar().orElseThrow(), manifests, mappings, "bad-endpoint");
            try {
                coordinator.prepareProvider(exported()).toCompletableFuture().join();
                assertThrows(CompletionException.class, () ->
                        coordinator.providerReady(new RpcEndpoint("0.0.0.0", 18080))
                                .toCompletableFuture().join());
                assertTrue(registry.lookup(APPLICATION).toCompletableFuture().join().instances().isEmpty());
            } finally {
                coordinator.shutdown().toCompletableFuture().join();
            }
            assertTrue(mappings.lookup(MAPPING).toCompletableFuture().join().applications().isEmpty());
        }
    }

    private static List<ExportedService> exported() {
        return List.of(ExportedService.fromContract(SERVICE, Payment.class));
    }

    private static ApplicationInstance sole(MemoryApplicationRegistry registry) {
        return registry.lookup(APPLICATION).toCompletableFuture().join().instances().getFirst();
    }

    private static ApplicationMembershipCoordinator coordinator(
            ApplicationRegistrar registrar, MemoryApplicationManifestRepository manifests,
            MemoryServiceMappingRepository mappings, String instanceId) {
        return new ApplicationMembershipCoordinator(
                APPLICATION, instanceId, registrar, manifests, mappings, Duration.ofSeconds(2));
    }

    /** 提供真实可校验的 Java RPC 方法集。 */
    public interface Payment {
        /** 一个示例方法。 */
        String pay(String input);
    }
}
