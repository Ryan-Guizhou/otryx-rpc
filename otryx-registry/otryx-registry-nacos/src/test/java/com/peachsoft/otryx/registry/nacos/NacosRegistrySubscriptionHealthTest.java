package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Nacos 控制面同步失败与恢复的确定性回归，不依赖外部容器。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 10:06
 */
class NacosRegistrySubscriptionHealthTest {

    private static final ServiceKey KEY =
            new ServiceKey("demo.NacosHealth", "1.0.0", "default");

    @Test
    void failedInitialSubscribeMustRecoverWithoutFabricatingEmptySnapshot() throws Exception {
        FakeNacos remote = new FakeNacos();
        remote.reachable.set(false);
        CapturingListener notifications = new CapturingListener();
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            NacosRegistrySubscription subscription =
                    subscription(remote, executor, notifications);
            try {
                subscription.start();
                await(() -> notifications.failures.get() == 1);
                assertEquals(0, notifications.snapshots.get());
                remote.reachable.set(true);
                subscription.reconcile();
                await(() -> notifications.snapshots.get() == 1
                        && notifications.successes.get() > 0);
                assertEquals(1, notifications.latest.get().instances().size());
                assertTrue(remote.subscriptions.get() >= 2);
            } finally {
                subscription.closeAsync().get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void failedReconcileMustKeepLastGoodAndRecoverWithoutNewChangeEvent() throws Exception {
        FakeNacos remote = new FakeNacos();
        CapturingListener notifications = new CapturingListener();
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            NacosRegistrySubscription subscription =
                    subscription(remote, executor, notifications);
            try {
                subscription.start();
                await(() -> notifications.snapshots.get() == 1);
                remote.reachable.set(false);
                subscription.reconcile();
                subscription.reconcile();
                await(() -> notifications.failures.get() == 1);
                assertEquals(1, notifications.snapshots.get(),
                        "Network failure must not fabricate an empty registry snapshot");
                remote.reachable.set(true);
                subscription.reconcile();
                await(() -> notifications.successes.get() >= 2);
                assertEquals(1, notifications.snapshots.get(),
                        "A quiet successful reconcile must not republish unchanged instances");
            } finally {
                subscription.closeAsync().get(2, TimeUnit.SECONDS);
            }
        }
    }

    private static NacosRegistrySubscription subscription(
            FakeNacos remote, NacosControlExecutor executor, CapturingListener listener) {
        return new NacosRegistrySubscription(
                remote.namingService(), executor, KEY, "OTRYX_RPC", "DEFAULT",
                listener, new AtomicLong(), RpcObserver.noop());
    }

    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(ready.getAsBoolean(), "Expected Nacos subscription state transition");
    }

    private static final class CapturingListener implements RegistryListener {
        private final AtomicInteger snapshots = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicReference<RegistrySnapshot> latest = new AtomicReference<>();

        @Override
        public void onSnapshot(RegistrySnapshot snapshot) {
            latest.set(snapshot);
            snapshots.incrementAndGet();
        }

        @Override
        public void onSynchronizationSucceeded() {
            successes.incrementAndGet();
        }

        @Override
        public void onSynchronizationFailed(Throwable error) {
            failures.incrementAndGet();
        }
    }

    private static final class FakeNacos {
        private final AtomicBoolean reachable = new AtomicBoolean(true);
        private final AtomicInteger subscriptions = new AtomicInteger();

        private NamingService namingService() {
            return (NamingService) Proxy.newProxyInstance(
                    NamingService.class.getClassLoader(),
                    new Class<?>[] {NamingService.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "subscribe" -> {
                            subscriptions.incrementAndGet();
                            yield null;
                        }
                        case "unsubscribe" -> null;
                        case "getAllInstances" -> {
                            if (!reachable.get()) {
                                throw new IllegalStateException("Nacos unreachable");
                            }
                            yield List.of(instance());
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private Instance instance() {
            Instance instance = NacosInstanceMapper.toNacos(
                    new ServiceInstance("nacos-healthy", KEY,
                            new RpcEndpoint("127.0.0.1", 19091), 100, Map.of()),
                    "DEFAULT");
            instance.setHealthy(true);
            return instance;
        }
    }
}
