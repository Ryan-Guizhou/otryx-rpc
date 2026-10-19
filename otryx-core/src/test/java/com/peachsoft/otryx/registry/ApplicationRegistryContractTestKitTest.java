package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 在同一进程内共享应用目录上执行与远端适配器一致的完整合同。
 *
 * <p>Memory 只有一个 Registry 实例；十个成员不伪装为十个远端客户端。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 07:01
 */
class ApplicationRegistryContractTestKitTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("contract-memory", "metrics");

    @Test
    void thirtyRealContractsAndTenMembersSatisfySharedApplicationContract() throws Exception {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            ApplicationRegistryContractTestKit.verify(
                    "memory", "contract-memory", 1, () -> registry,
                    application -> registry.lookup(application).toCompletableFuture().join().instances().size(),
                    Duration.ofSeconds(3));
        }
    }

    @Test
    void operationEvidenceWaitsForCompletionAndKeepsFailuresAndCancellation() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        CompletableFuture<Void> rejected = new CompletableFuture<>();
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        AtomicInteger registrations = new AtomicInteger();
        IllegalStateException synchronousFailure = new IllegalStateException("Synthetic synchronous rejection");
        ApplicationRegistrar delegate = new ApplicationRegistrar() {
            @Override
            public CompletionStage<Void> register(ApplicationInstance instance) {
                return switch (registrations.getAndIncrement()) {
                    case 0 -> pending;
                    case 1 -> rejected;
                    default -> throw synchronousFailure;
                };
            }

            @Override
            public CompletionStage<Void> unregister(ApplicationInstance instance) {
                return cancelled;
            }
        };
        ApplicationRegistryContractTestKit.Evidence evidence = new ApplicationRegistryContractTestKit.Evidence();
        ApplicationRegistrar registrar = ApplicationRegistryContractTestKit.counted(
                ApplicationRegistrar.class, delegate, "member", evidence);
        ApplicationInstance instance = consumer("one", "first");
        assertSame(pending, registrar.register(instance), "Observation must retain the original completion stage");
        assertSame(rejected, registrar.register(instance));
        assertOperation(evidence, "member.register", 2, 0, 0, 2);
        pending.complete(null);
        assertOperation(evidence, "member.register", 2, 1, 0, 1);
        rejected.completeExceptionally(new IllegalArgumentException("Synthetic asynchronous rejection"));
        assertSame(synchronousFailure, assertThrows(IllegalStateException.class, () -> registrar.register(instance)));
        assertOperation(evidence, "member.register", 3, 1, 2, 0);
        assertSame(cancelled, registrar.unregister(instance));
        assertOperation(evidence, "member.unregister", 1, 0, 0, 1);
        cancelled.cancel(false);
        assertOperation(evidence, "member.unregister", 1, 0, 1, 0);
    }

    @Test
    void subscriptionEvidenceDistinguishesContentChangesAndConfirmedClose() {
        AtomicReference<ApplicationRegistryListener> observed = new AtomicReference<>();
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger forwardedSnapshots = new AtomicInteger();
        ApplicationDiscovery delegate = new ApplicationDiscovery() {
            @Override
            public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
                return CompletableFuture.completedFuture(new ApplicationDirectorySnapshot(application, List.of(), 0));
            }

            @Override
            public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
                observed.set(listener);
                listener.onSnapshot(new ApplicationDirectorySnapshot(application, List.of(), 0));
                return () -> {
                    if (closeAttempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("Synthetic close failure");
                    }
                };
            }
        };
        ApplicationRegistryContractTestKit.Evidence evidence = new ApplicationRegistryContractTestKit.Evidence();
        ApplicationDiscovery discovery = ApplicationRegistryContractTestKit.counted(
                ApplicationDiscovery.class, delegate, "application", evidence);
        RegistrySubscription subscription = discovery.subscribe(APPLICATION, snapshot -> {
            forwardedSnapshots.incrementAndGet();
        });
        assertOperation(evidence, "application.subscribe", 1, 1, 0, 0);
        assertEquals(1, evidence.subscriptions().opened());
        assertEquals(1, evidence.subscriptions().active());
        ApplicationRegistryListener listener = observed.get();
        ApplicationInstance first = consumer("one", "first");
        ApplicationInstance second = consumer("two", "first");
        listener.onSnapshot(new ApplicationDirectorySnapshot(APPLICATION, List.of(), 1));
        listener.onSnapshot(new ApplicationDirectorySnapshot(APPLICATION, List.of(first, second), 2));
        listener.onSnapshot(new ApplicationDirectorySnapshot(APPLICATION, List.of(second, first), 3));
        listener.onSnapshot(new ApplicationDirectorySnapshot(APPLICATION,
                List.of(consumer("one", "changed"), second), 4));
        listener.onSnapshot(new ApplicationDirectorySnapshot(APPLICATION, List.of(), 5));
        listener.onSynchronizationSucceeded();
        listener.onSynchronizationFailed(new IllegalStateException("Synthetic synchronization failure"));
        assertEquals(6, evidence.subscriptions().snapshotCallbacks());
        assertEquals(1, evidence.subscriptions().initialSnapshots());
        assertEquals(3, evidence.subscriptions().logicalChanges());
        assertEquals(1, evidence.subscriptions().healthSuccessCallbacks());
        assertEquals(1, evidence.subscriptions().healthFailureCallbacks());
        assertThrows(IllegalStateException.class, subscription::close);
        assertEquals(0, evidence.subscriptions().closed());
        assertEquals(1, evidence.subscriptions().active(), "Failed close does not prove resource release");
        subscription.close();
        subscription.close();
        assertEquals(3, closeAttempts.get(), "Repeated close must still reach the actual adapter");
        assertEquals(1, evidence.subscriptions().closed());
        assertEquals(0, evidence.subscriptions().active());
        assertOperation(evidence, "subscription.close", 3, 2, 1, 0);
        listener.onSnapshot(new ApplicationDirectorySnapshot(APPLICATION, List.of(), 6));
        assertEquals(7, forwardedSnapshots.get(), "Observation must not suppress illegal late callbacks");
        assertEquals(1, evidence.subscriptions().callbacksAfterClose());
        assertEquals(3, evidence.subscriptions().logicalChanges());
    }

    @Test
    void failedSubscriptionDoesNotCountAsAnOpenedHandle() {
        ApplicationDiscovery delegate = new ApplicationDiscovery() {
            @Override
            public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
                throw new UnsupportedOperationException("Not used by this scenario");
            }

            @Override
            public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
                throw new IllegalStateException("Synthetic subscription rejection");
            }
        };
        ApplicationRegistryContractTestKit.Evidence evidence = new ApplicationRegistryContractTestKit.Evidence();
        ApplicationDiscovery discovery = ApplicationRegistryContractTestKit.counted(
                ApplicationDiscovery.class, delegate, "application", evidence);
        assertThrows(IllegalStateException.class, () -> discovery.subscribe(APPLICATION, snapshot -> { }));
        assertOperation(evidence, "application.subscribe", 1, 0, 1, 0);
        assertEquals(0, evidence.subscriptions().opened());
        assertEquals(0, evidence.subscriptions().closed());
        assertEquals(0, evidence.subscriptions().active());
    }

    private static ApplicationInstance consumer(String instanceId, String zone) {
        return new ApplicationInstance(instanceId, APPLICATION, ApplicationRole.CONSUMER,
                null, 0, null, Map.of("zone", zone));
    }

    private static void assertOperation(
            ApplicationRegistryContractTestKit.Evidence evidence, String operation,
            long calls, long successes, long failures, long pending) {
        assertEquals(new ApplicationRegistryContractTestKit.OperationCounts(calls, successes, failures, pending),
                evidence.operationCounts().get(operation));
    }
}
