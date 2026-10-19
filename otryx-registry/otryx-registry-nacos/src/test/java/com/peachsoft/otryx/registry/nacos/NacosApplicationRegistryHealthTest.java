package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistrySubscription;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * 将 Naming 初始失败、稳定快照和无变更恢复合同应用于真实应用级订阅实现。
 *
 * <p>SDK 边界用可控替身触发事件，不将本测试当作真实 Nacos 网络证据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 07:01
 */
class NacosApplicationRegistryHealthTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("public", "health");

    @Test
    void failedInitialSubscribeRecoversWithoutFabricatingHealthyEmptySnapshot() throws Exception {
        FakeNaming remote = new FakeNaming();
        remote.reachable.set(false);
        Notifications listener = new Notifications();
        try (NacosApplicationRegistry registry = remote.registry();
             RegistrySubscription ignored = registry.subscribe(APPLICATION, listener)) {
            await(() -> listener.failures.get() == 1);
            assertEquals(0, listener.snapshots.get());
            assertEquals(0, listener.successes.get());
            remote.reachable.set(true);
            remote.change();
            await(() -> listener.snapshots.get() == 1 && listener.successes.get() > 0);
            assertEquals(1, listener.latest.get().instances().size());
            assertTrue(remote.subscriptions.get() >= 2);
            assertTrue(registry.capabilities().supports(RegistryCapability.REGISTRATION));
            assertTrue(registry.capabilities().supports(RegistryCapability.SUBSCRIPTION));
        }
        assertTrue(remote.namingClosed.get());
        assertTrue(remote.configClosed.get());
    }

    @Test
    void failedReconcilePreservesLastGoodAndQuietRecoveryOnlyConfirmsHealth() throws Exception {
        FakeNaming remote = new FakeNaming();
        Notifications listener = new Notifications();
        try (NacosApplicationRegistry registry = remote.registry();
             RegistrySubscription ignored = registry.subscribe(APPLICATION, listener)) {
            await(() -> listener.snapshots.get() == 1 && listener.successes.get() > 0);
            ApplicationDirectorySnapshot original = listener.latest.get();
            int healthyBefore = listener.successes.get();
            remote.reachable.set(false);
            remote.change();
            await(() -> listener.failures.get() == 1);
            remote.change();
            await(() -> remote.lookups.get() >= 3);
            assertEquals(original, listener.latest.get());
            assertEquals(1, listener.snapshots.get());
            assertEquals(1, listener.failures.get());
            remote.reachable.set(true);
            remote.change();
            await(() -> listener.successes.get() > healthyBefore);
            assertEquals(original, listener.latest.get());
            assertEquals(1, listener.snapshots.get());
            remote.empty.set(true);
            remote.change();
            await(() -> listener.latest.get().instances().isEmpty());
            assertTrue(listener.latest.get().revision() > original.revision());
        }
    }

    @Test
    void subscriptionClosePreventsLaterCallbacksAndReleasesSdkSubscription() throws Exception {
        FakeNaming remote = new FakeNaming();
        Notifications listener = new Notifications();
        try (NacosApplicationRegistry registry = remote.registry()) {
            RegistrySubscription subscription = registry.subscribe(APPLICATION, listener);
            await(() -> listener.snapshots.get() == 1);
            subscription.close();
            subscription.close();
            await(() -> remote.unsubscriptions.get() == 1);
            remote.empty.set(true);
            remote.change();
            assertFalse(listener.latest.get().instances().isEmpty());
            assertEquals(1, listener.snapshots.get());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), "Expected application subscription transition");
    }

    private static final class Notifications implements ApplicationRegistryListener {
        private final AtomicInteger snapshots = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();

        @Override
        public void onSnapshot(ApplicationDirectorySnapshot snapshot) {
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

    private static final class FakeNaming {
        private final AtomicBoolean reachable = new AtomicBoolean(true);
        private final AtomicBoolean empty = new AtomicBoolean();
        private final AtomicBoolean namingClosed = new AtomicBoolean();
        private final AtomicBoolean configClosed = new AtomicBoolean();
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final AtomicInteger unsubscriptions = new AtomicInteger();
        private final AtomicInteger lookups = new AtomicInteger();
        private final AtomicReference<EventListener> events = new AtomicReference<>();

        private NacosApplicationRegistry registry() {
            NamingService naming = (NamingService) Proxy.newProxyInstance(
                    NamingService.class.getClassLoader(), new Class<?>[] {NamingService.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "subscribe" -> {
                            events.set((EventListener) args[args.length - 1]);
                            subscriptions.incrementAndGet();
                            requireReachable();
                            yield null;
                        }
                        case "unsubscribe" -> {
                            unsubscriptions.incrementAndGet();
                            yield null;
                        }
                        case "getAllInstances" -> {
                            lookups.incrementAndGet();
                            requireReachable();
                            yield empty.get() ? List.of() : List.of(provider());
                        }
                        case "shutDown" -> {
                            namingClosed.set(true);
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            ConfigService config = (ConfigService) Proxy.newProxyInstance(
                    ConfigService.class.getClassLoader(), new Class<?>[] {ConfigService.class},
                    (proxy, method, args) -> {
                        if ("shutDown".equals(method.getName())) {
                            configClosed.set(true);
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            return new NacosApplicationRegistry(naming, config, APPLICATION.namespace(),
                    "CONTRACT", "DEFAULT", "127.0.0.1", RpcObserver.noop());
        }

        private void change() {
            events.get().onEvent(null);
        }

        private void requireReachable() {
            if (!reachable.get()) {
                throw new IllegalStateException("Naming unavailable");
            }
        }

        private Instance provider() {
            return NacosV2InstanceMapper.encode(new ApplicationInstance("healthy", APPLICATION,
                    ApplicationRole.PROVIDER, new RpcEndpoint("127.0.0.1", 19091), 100,
                    "sha256:" + "a".repeat(64), Map.of()), "DEFAULT", "127.0.0.1", "test-token");
        }
    }
}
