package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcOverloadedException;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.governance.RpcGovernanceMethodRule;
import com.peachsoft.otryx.governance.RpcGovernanceServiceRule;
import com.peachsoft.otryx.governance.RpcGovernanceSignedUpdate;
import com.peachsoft.otryx.governance.RpcGovernanceSnapshot;
import com.peachsoft.otryx.routing.RpcRouteRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 治理 HMAC、强制并发上限、同版本快照、老熔断实例与有界 LKG 的回归。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:45
 */
class RpcGovernanceRuntimeTest {

    @Test
    void oneAtomicSnapshotMustBindRouteTimeoutQuotaAndCircuitAcrossConcurrentReads() throws Exception {
        var repo = new RpcGovernanceTestFixtures.MemoryRepository();
        try (var runtime = new RpcGovernanceRuntime(repo, RpcGovernanceTestFixtures.verifier(),
                Duration.ofSeconds(1))) {
            var first = RpcGovernanceTestFixtures.snapshot(1L, 2, 2, Duration.ofMillis(500));
            var second = RpcGovernanceTestFixtures.snapshot(2L, 1, 3, Duration.ofMillis(80));
            repo.initial.complete(RpcGovernanceTestFixtures.sign(first));
            runtime.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var pinned = runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123);
            assertEquals(500L, pinned.method().maxTimeout().toMillis());
            assertEquals(2, pinned.method().maxConcurrent());
            var oldCircuit = pinned.circuit();
            var pool = Executors.newFixedThreadPool(4);
            try {
                List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    tasks.add(pool.submit(() -> {
                        for (int j = 0; j < 1000; j++) {
                            var captured = runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123);
                            if (captured.revision() == 1L) {
                                assertEquals(2, captured.method().maxConcurrent());
                                assertEquals(500L, captured.method().maxTimeout().toMillis());
                            } else {
                                assertEquals(2L, captured.revision());
                                assertEquals(1, captured.method().maxConcurrent());
                                assertEquals(80L, captured.method().maxTimeout().toMillis());
                            }
                        }
                    }));
                }
                repo.send(RpcGovernanceTestFixtures.sign(second));
                for (var task : tasks) {
                    task.get(3, TimeUnit.SECONDS);
                }
                awaitRevision(runtime, 2L);
            } finally {
                pool.shutdownNow();
            }
            var current = runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123);
            assertNotSame(oldCircuit, current.circuit());
            assertSame(oldCircuit, pinned.circuit());
            assertEquals(500L, pinned.method().maxTimeout().toMillis());
            assertEquals(1, repo.loads.get(), "The data plane must not read remote governance");
        }
        assertTrue(repo.closed);
    }

    @Test
    void reducedQuotaMustCountOldRevisionInflightUntilTrueCompletion() throws Exception {
        var repo = new RpcGovernanceTestFixtures.MemoryRepository();
        try (var runtime = new RpcGovernanceRuntime(repo, RpcGovernanceTestFixtures.verifier(),
                Duration.ofSeconds(1))) {
            repo.initial.complete(RpcGovernanceTestFixtures.sign(
                    RpcGovernanceTestFixtures.snapshot(1L, 2, 2, Duration.ofSeconds(2))));
            runtime.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var older = runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123);
            var first = runtime.acquireQuota(older, RpcGovernanceRuntime.QuotaSide.PROVIDER,
                    RpcGovernanceTestFixtures.SERVICE, 123);
            var second = runtime.acquireQuota(older, RpcGovernanceRuntime.QuotaSide.PROVIDER,
                    RpcGovernanceTestFixtures.SERVICE, 123);
            assertThrows(RpcOverloadedException.class, () -> runtime.acquireQuota(
                    RpcGovernanceRuntime.QuotaSide.PROVIDER, RpcGovernanceTestFixtures.SERVICE, 123));
            repo.send(RpcGovernanceTestFixtures.sign(
                    RpcGovernanceTestFixtures.snapshot(2L, 1, 2, Duration.ofSeconds(1))));
            awaitRevision(runtime, 2L);
            assertThrows(RpcOverloadedException.class, () -> runtime.acquireQuota(
                    RpcGovernanceRuntime.QuotaSide.PROVIDER, RpcGovernanceTestFixtures.SERVICE, 123));
            assertTrue(first.release());
            assertFalse(first.release());
            assertThrows(RpcOverloadedException.class, () -> runtime.acquireQuota(
                    RpcGovernanceRuntime.QuotaSide.PROVIDER, RpcGovernanceTestFixtures.SERVICE, 123));
            assertTrue(second.release());
            var newVersion = runtime.acquireQuota(
                    RpcGovernanceRuntime.QuotaSide.PROVIDER, RpcGovernanceTestFixtures.SERVICE, 123);
            var independentConsumer = runtime.acquireQuota(
                    RpcGovernanceRuntime.QuotaSide.CONSUMER, RpcGovernanceTestFixtures.SERVICE, 123);
            assertTrue(newVersion.release());
            assertTrue(independentConsumer.release());
        }
    }

    @Test
    void wrongSignatureMustFailClosedWithoutReplacingOldSnapshotAndRecoverWithNewRevision() throws Exception {
        var repo = new RpcGovernanceTestFixtures.MemoryRepository();
        try (var runtime = new RpcGovernanceRuntime(repo, RpcGovernanceTestFixtures.verifier(),
                Duration.ofSeconds(1))) {
            var accepted = RpcGovernanceTestFixtures.snapshot(1L, 2, 2, Duration.ofMillis(500));
            repo.initial.complete(RpcGovernanceTestFixtures.sign(accepted));
            runtime.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            RpcGovernanceSignedUpdate signed = RpcGovernanceTestFixtures.sign(accepted);
            var altered = new RpcGovernanceSignedUpdate(signed.issuer(),
                    RpcGovernanceTestFixtures.snapshot(2L, 100, 100, Duration.ofSeconds(50)),
                    signed.signature());
            repo.send(altered);
            awaitInvalid(runtime);
            assertEquals(1L, runtime.revision());
            assertThrows(RpcUnavailableException.class, () ->
                    runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123));
            repo.send(RpcGovernanceTestFixtures.sign(
                    RpcGovernanceTestFixtures.snapshot(3L, 1, 2, Duration.ofMillis(70))));
            awaitRevision(runtime, 3L);
            assertFalse(runtime.securityRejected());
            repo.send(RpcGovernanceTestFixtures.sign(accepted));
            assertEquals(3L, runtime.revision(), "A replay must never overwrite an accepted revision");
        }
    }

    @Test
    void boundedLastKnownGoodMustExpireAfterOutageAndSameRevisionMayRestoreIt() throws Exception {
        var repo = new RpcGovernanceTestFixtures.MemoryRepository();
        try (var runtime = new RpcGovernanceRuntime(repo, RpcGovernanceTestFixtures.verifier(),
                Duration.ofMillis(35))) {
            RpcGovernanceSnapshot original = RpcGovernanceTestFixtures.snapshot(
                    1L, 1, 0, Duration.ofSeconds(1));
            RpcGovernanceSignedUpdate signed = RpcGovernanceTestFixtures.sign(original);
            repo.initial.complete(signed);
            runtime.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            repo.unavailable();
            runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123);
            Thread.sleep(70L);
            assertThrows(RpcUnavailableException.class, () ->
                    runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123));
            repo.send(signed);
            awaitRecovered(runtime);
            assertEquals(1L, runtime.revision());
        }
    }

    @Test
    void governanceRoutingMustNotInventProvidersOrTrustUnverifiedWeights() throws Exception {
        var repo = new RpcGovernanceTestFixtures.MemoryRepository();
        ApplicationKey stable = new ApplicationKey("tenant", "stable");
        ApplicationKey gray = new ApplicationKey("tenant", "gray");
        var route = new RpcRouteRule(Set.of(stable), List.of(), Map.of(), null, null, null);
        var rule = new RpcGovernanceServiceRule(route, Map.of(stable, 5, gray, 50),
                Map.of(123, new RpcGovernanceMethodRule(Duration.ofMillis(50), 1, 0, null)));
        try (var runtime = new RpcGovernanceRuntime(repo, RpcGovernanceTestFixtures.verifier(),
                Duration.ofSeconds(1))) {
            repo.initial.complete(RpcGovernanceTestFixtures.sign(
                    new RpcGovernanceSnapshot(1L, Map.of(RpcGovernanceTestFixtures.SERVICE, rule))));
            runtime.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            ServiceInstance first = instance("stable", stable);
            ServiceInstance other = instance("gray", gray);
            ServiceInstance[] after = runtime.route(
                    runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123),
                    new ServiceInstance[] {first, other});
            assertEquals(1, after.length);
            assertSame(first, after[0], "A governance rule may only select existing verified references");
            assertEquals(1, repo.loads.get());
        }
    }

    private static ServiceInstance instance(String id, ApplicationKey key) {
        return new ServiceInstance(id, key, RpcGovernanceTestFixtures.SERVICE,
                new RpcEndpoint("127.0.0.1", 29000), 100, Map.of());
    }

    private static void awaitRevision(RpcGovernanceRuntime runtime, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (runtime.revision() != expected && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
        assertEquals(expected, runtime.revision());
    }

    private static void awaitInvalid(RpcGovernanceRuntime runtime) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!runtime.securityRejected() && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
        assertTrue(runtime.securityRejected());
    }

    private static void awaitRecovered(RpcGovernanceRuntime runtime) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                runtime.capture(RpcGovernanceTestFixtures.SERVICE, 123);
                return;
            } catch (RpcUnavailableException stillUnavailable) {
                Thread.sleep(2L);
            }
        }
        throw new AssertionError("Governance did not recover from same signed revision");
    }
}
