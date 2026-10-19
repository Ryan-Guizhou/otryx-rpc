package com.peachsoft.otryx.registry.eureka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySubscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真 Eureka Server 重启后，验证 UP Member 订阅错误通知、租约恢复和 Consumer 隔离。
 *
 * <p>仅隔离 CI 传入明确测试脚本时允许控制 Server 进程；禁止主动操作生产 Eureka。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:09
 */
@EnabledIfSystemProperty(named = "eureka.test.control", matches = ".+")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class EurekaApplicationRegistryChaosTest {

    private static final String CONSUL = "http://127.0.0.1:8500";
    private static final String EUREKA = "http://127.0.0.1:8761/eureka";

    @Test
    void realServerRestartRetainsCorrectApplicationRoutingAndLeaseRecovery() throws Exception {
        String ns = "chaos-" + UUID.randomUUID().toString().substring(0, 8);
        ApplicationKey app = new ApplicationKey(ns, "switch");
        ApplicationInstance provider = new ApplicationInstance("provider", app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19391), 100, "sha256:" + "a".repeat(64), Map.of());
        ApplicationInstance consumer = new ApplicationInstance("consumer", app, ApplicationRole.CONSUMER,
                null, 0, null, Map.of());
        SubscriptionProbe probe = new SubscriptionProbe(provider, consumer);

        try (EurekaApplicationRegistry server = registry(ns);
             EurekaApplicationRegistry client = registry(ns);
             RegistrySubscription ignored = client.subscribe(app, probe)) {
            server.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            server.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(probe.initial.await(25, TimeUnit.SECONDS),
                    () -> "Eureka initial membership did not converge: " + probe.diagnostics());
            String script = System.getProperty("eureka.test.control");
            probe.beginOutage();
            control(script, "stop");
            try {
                assertTrue(probe.failed.await(20, TimeUnit.SECONDS),
                        () -> "Eureka subscription must signal unavailable server: " + probe.diagnostics());
            } finally {
                control(script, "start");
                probe.restartCompleted();
            }
            assertTrue(probe.recovered.await(65, TimeUnit.SECONDS),
                    () -> "Eureka members must recover after a real restart: " + probe.diagnostics());
            ApplicationDirectorySnapshot finalSnapshot = client.lookup(app).toCompletableFuture().join();
            assertTrue(finalSnapshot.instances().containsAll(List.of(provider, consumer)),
                    () -> "Eureka final membership is incomplete: " + probe.describe(finalSnapshot));
            assertEquals(List.of(provider), finalSnapshot.instances().stream()
                    .filter(ApplicationInstance::routableProvider).toList(),
                    () -> "Eureka final Provider pool is incorrect: " + probe.describe(finalSnapshot));
        }
    }

    /** 仅记录本次订阅证据；阶段切换由控制真实 Server 的测试线程负责。 */
    static final class SubscriptionProbe implements ApplicationRegistryListener {
        private enum Phase { INITIAL, OUTAGE, RECOVERY }

        private final List<ApplicationInstance> expected;
        private final AtomicInteger snapshotCount = new AtomicInteger();
        private final AtomicInteger successfulSyncs = new AtomicInteger();
        private final AtomicInteger failedSyncs = new AtomicInteger();
        private final AtomicReference<ApplicationDirectorySnapshot> lastSnapshot = new AtomicReference<>();
        private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.INITIAL);
        final CountDownLatch initial = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        final CountDownLatch recovered = new CountDownLatch(1);

        SubscriptionProbe(ApplicationInstance provider, ApplicationInstance consumer) {
            expected = List.of(provider, consumer);
        }

        void beginOutage() {
            if (!phase.compareAndSet(Phase.INITIAL, Phase.OUTAGE)) {
                throw new IllegalStateException("Eureka outage phase is out of order");
            }
        }

        void restartCompleted() {
            if (!phase.compareAndSet(Phase.OUTAGE, Phase.RECOVERY)) {
                throw new IllegalStateException("Eureka recovery phase is out of order");
            }
        }

        @Override
        public void onSnapshot(ApplicationDirectorySnapshot snapshot) {
            Phase observedPhase = phase.get();
            lastSnapshot.set(snapshot);
            snapshotCount.incrementAndGet();
            if (observedPhase == Phase.INITIAL && containsExpected(snapshot)) {
                initial.countDown();
            }
        }

        @Override
        public void onSynchronizationSucceeded() {
            Phase observedPhase = phase.get();
            successfulSyncs.incrementAndGet();
            // A healthy post-restart poll may retain the previous member list,
            // so recovery must not depend on another content-change callback.
            if (observedPhase == Phase.RECOVERY && failed.getCount() == 0 && containsExpected(lastSnapshot.get())) {
                recovered.countDown();
            }
        }

        @Override
        public void onSynchronizationFailed(Throwable error) {
            Phase observedPhase = phase.get();
            lastFailure.set(error);
            failedSyncs.incrementAndGet();
            if (observedPhase == Phase.OUTAGE) {
                failed.countDown();
            }
        }

        private boolean containsExpected(ApplicationDirectorySnapshot snapshot) {
            return snapshot != null && snapshot.instances().containsAll(expected);
        }

        String diagnostics() {
            List<String> failureTypes = new ArrayList<>();
            Throwable failure = lastFailure.get();
            for (int depth = 0; failure != null && depth < 4; depth++) {
                failureTypes.add(failure.getClass().getName());
                failure = failure.getCause();
            }
            return "phase=" + phase.get() + ", snapshots=" + snapshotCount.get()
                    + ", successfulSyncs=" + successfulSyncs.get() + ", failedSyncs=" + failedSyncs.get()
                    + ", lastSnapshot=" + describe(lastSnapshot.get()) + ", lastFailureTypes=" + failureTypes;
        }

        String describe(ApplicationDirectorySnapshot snapshot) {
            if (snapshot == null) {
                return "none";
            }
            List<String> members = snapshot.instances().stream()
                    .map(member -> member.instanceId() + ":" + member.role()
                            + ":matchesExpected=" + expected.contains(member))
                    .toList();
            return "revision=" + snapshot.revision() + ", members=" + members
                    + ", expectedMembersPresent=" + containsExpected(snapshot);
        }
    }

    private static EurekaApplicationRegistry registry(String namespace) {
        return (EurekaApplicationRegistry) new EurekaApplicationRegistryFactory().create(
                RegistryOptions.trustedDevelopment(List.of(EUREKA), namespace, Map.of(
                        "eurekaMetadataBackend", "consul",
                        "eurekaMetadataEndpoints", CONSUL,
                        "eurekaMemberHost", "127.0.0.1",
                        "eurekaLeaseSeconds", "9",
                        "eurekaHeartbeatSeconds", "1",
                        "eurekaPollIntervalMillis", "200",
                        "eurekaRequestTimeoutMillis", "1000",
                        "eurekaMetadata.consulTtlSeconds", "10",
                        "eurekaMetadata.consulHeartbeatSeconds", "1")));
    }

    private static void control(String script, String action) throws Exception {
        Process operation = new ProcessBuilder("bash", script, action)
                .redirectErrorStream(true).start();
        if (!operation.waitFor(80, TimeUnit.SECONDS) || operation.exitValue() != 0) {
            operation.destroyForcibly();
            throw new IllegalStateException("Isolated Eureka fixture control failed: " + action);
        }
    }
}
