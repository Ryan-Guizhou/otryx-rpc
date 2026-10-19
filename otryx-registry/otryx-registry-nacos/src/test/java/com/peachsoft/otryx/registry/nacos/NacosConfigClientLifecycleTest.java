package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.config.ConfigQueryResult;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 以实际操作闩锁验证远端配置客户端的关闭所有权与主要失败保留。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 16:30
 */
@Timeout(10)
class NacosConfigClientLifecycleTest {

    private static final Duration CLOSE_WAIT = Duration.ofMillis(50);

    @Test
    void closeWaitKeepsCancelledQueryOwnedUntilItsBodyExits() throws Exception {
        ScriptedRemote remote = new ScriptedRemote(null, null, false);
        NacosConfigClient client = new NacosConfigClient(remote, CLOSE_WAIT);
        CountDownLatch actualBodyExited = new CountDownLatch(1);
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            try {
                var query = executor.submit("lifecycle-read", "test", () -> {
                    try {
                        return client.query("data", "group", 3000L);
                    } finally {
                        actualBodyExited.countDown();
                    }
                });
                assertTrue(remote.operationEntered.await(2, TimeUnit.SECONDS));
                assertTrue(query.cancel(true));
                assertThrows(TimeoutException.class, client::shutDown);
                assertEquals(0, remote.closeCalls.get());
                assertEquals(0, remote.remoteInvocations.get());
                assertThrows(IllegalStateException.class, () -> client.query("data", "group", 3000L));
                assertThrows(IllegalStateException.class, () -> client.publishImmutable("data", "group", "value"));
                assertThrows(IllegalStateException.class,
                        () -> client.compareAndSet("data", "group", "value", "md5"));
                assertEquals(1, remote.operationEntries.get());

                remote.operationRelease.countDown();
                assertTrue(remote.closeReturned.await(2, TimeUnit.SECONDS));
                assertTrue(actualBodyExited.await(2, TimeUnit.SECONDS));
                client.shutDown();
                client.shutDown();
                assertTrue(query.isCancelled());
                assertEquals(1, remote.remoteInvocations.get());
                assertEquals(1, remote.closeCalls.get());
                assertEquals(0, remote.invocationsAfterClose.get());
            } finally {
                remote.operationRelease.countDown();
                remote.closeRelease.countDown();
                client.shutDown();
            }
        }
    }

    @Test
    void closeFailureDoesNotReplacePrimaryOperationFailure() throws Exception {
        List<Throwable> primaryFailures = List.of(
                new NacosException(401, "authentication rejected"),
                new NacosException(NacosException.NO_RIGHT, "authorization rejected"),
                new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED));
        for (Throwable primary : primaryFailures) {
            NacosException closeFailure = new NacosException(NacosException.CLIENT_ERROR, "cleanup failed");
            ScriptedRemote remote = new ScriptedRemote(primary, closeFailure, false);
            NacosConfigClient client = new NacosConfigClient(remote, CLOSE_WAIT);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<ConfigQueryResult> query = executor.submit(() -> client.query("data", "group", 3000L));
                assertTrue(remote.operationEntered.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, client::shutDown);
                assertEquals(0, remote.closeCalls.get());
                remote.operationRelease.countDown();
                ExecutionException operationFailure = assertThrows(ExecutionException.class,
                        () -> query.get(2, TimeUnit.SECONDS));
                assertSame(primary, operationFailure.getCause());
                assertSame(closeFailure, assertThrows(NacosException.class, client::shutDown));
                assertSame(closeFailure, assertThrows(NacosException.class, client::shutDown));
                assertEquals(1, remote.closeCalls.get());
                assertEquals(0, remote.invocationsAfterClose.get());
            } finally {
                remote.operationRelease.countDown();
                remote.closeRelease.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
                assertThrows(NacosException.class, client::shutDown);
            }
        }
    }

    @Test
    void repeatedCloseObservesSameInProgressSdkShutdown() throws Exception {
        ScriptedRemote remote = new ScriptedRemote(null, null, true);
        NacosConfigClient client = new NacosConfigClient(remote, CLOSE_WAIT);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Void> first = executor.submit(() -> {
                client.shutDown();
                return null;
            });
            assertTrue(remote.closeEntered.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, client::shutDown);
            assertFalse(first.isDone());
            assertEquals(1, remote.closeCalls.get());
            assertThrows(IllegalStateException.class, () -> client.query("data", "group", 3000L));
            remote.closeRelease.countDown();
            ExecutionException expiredWait = assertThrows(ExecutionException.class,
                    () -> first.get(2, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, expiredWait.getCause());
            client.shutDown();
            assertEquals(1, remote.closeCalls.get());
        } finally {
            remote.operationRelease.countDown();
            remote.closeRelease.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            client.shutDown();
        }
    }

    @Test
    void namingShutdownFailureStillClosesConfigExactlyOnce() throws Exception {
        AtomicInteger namingCloses = new AtomicInteger();
        NamingService naming = (NamingService) Proxy.newProxyInstance(
                NamingService.class.getClassLoader(), new Class<?>[] {NamingService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("shutDown")) {
                        namingCloses.incrementAndGet();
                        throw new NacosException(NacosException.CLIENT_ERROR, "naming cleanup failed");
                    }
                    throw new AssertionError("Unexpected Naming operation");
                });
        ScriptedRemote remote = new ScriptedRemote(null, null, false);
        NacosConfigClient client = new NacosConfigClient(remote, Duration.ofSeconds(3));
        NacosApplicationRegistry registry = new NacosApplicationRegistry(
                naming, client, "public", "TEST", "DEFAULT", "127.0.0.1", RpcObserver.noop(),
                RegistrySecurityDescriptor.unknown());
        try {
            registry.close();
            assertTrue(namingCloses.get() >= 1);
            assertEquals(1, remote.closeCalls.get());
            assertThrows(IllegalStateException.class, () -> client.query("data", "group", 3000L));
        } finally {
            remote.operationRelease.countDown();
            remote.closeRelease.countDown();
            registry.close();
            client.shutDown();
        }
    }

    @Test
    void bootstrapCleanupFailureCannotReplaceAuthenticationAuthorizationOrMissingMd5() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            NacosException primary = switch (scenario) {
                case 0 -> new NacosException(401, "authentication rejected");
                case 1 -> new NacosException(NacosException.NO_RIGHT, "authorization rejected");
                default -> null;
            };
            NacosException closeFailure = new NacosException(NacosException.CLIENT_ERROR, "cleanup failed");
            ScriptedRemote remote = new ScriptedRemote(primary, closeFailure, false);
            remote.response.setContent("content without authoritative md5");
            remote.operationRelease.countDown();
            NacosConfigClient client = new NacosConfigClient(remote, Duration.ofSeconds(3));
            try {
                Exception failure = assertThrows(Exception.class,
                        () -> NacosV2MetadataBootstrap.initializeOwnedConfig(client, "public", "TEST"));
                if (primary != null) {
                    assertSame(primary, failure);
                } else {
                    assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                            assertInstanceOf(RegistrySecurityException.class, failure).reason());
                }
                assertEquals(1, remote.remoteInvocations.get());
                assertEquals(1, remote.closeCalls.get());
                assertThrows(IllegalStateException.class, () -> client.query("data", "group", 3000L));
            } finally {
                remote.closeRelease.countDown();
                assertSame(closeFailure, assertThrows(NacosException.class, client::shutDown));
            }
        }
    }

    /** 在真实远端调用前及同步 SDK 关闭内部设置独立闩锁，不模拟客户端的所有权状态。 */
    private static final class ScriptedRemote implements NacosConfigOperations {
        private final Throwable operationFailure;
        private final ConfigQueryResult response = new ConfigQueryResult();
        private final NacosException closeFailure;
        private final CountDownLatch operationEntered = new CountDownLatch(1);
        private final CountDownLatch operationRelease = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch closeRelease;
        private final CountDownLatch closeReturned = new CountDownLatch(1);
        private final AtomicInteger operationEntries = new AtomicInteger();
        private final AtomicInteger remoteInvocations = new AtomicInteger();
        private final AtomicInteger invocationsAfterClose = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicBoolean physicallyClosed = new AtomicBoolean();

        private ScriptedRemote(Throwable operationFailure, NacosException closeFailure, boolean blockClose) {
            this.operationFailure = operationFailure;
            this.closeFailure = closeFailure;
            closeRelease = new CountDownLatch(blockClose ? 1 : 0);
        }

        @Override
        public ConfigQueryResult query(String dataId, String group, long timeoutMillis) throws NacosException {
            invoke();
            return response;
        }

        @Override
        public boolean publishImmutable(String dataId, String group, String content) throws NacosException {
            invoke();
            return true;
        }

        @Override
        public boolean compareAndSet(String dataId, String group, String content, String casMd5) throws NacosException {
            invoke();
            return true;
        }

        private void invoke() throws NacosException {
            operationEntries.incrementAndGet();
            operationEntered.countDown();
            await(operationRelease);
            if (physicallyClosed.get()) {
                invocationsAfterClose.incrementAndGet();
            }
            remoteInvocations.incrementAndGet();
            if (operationFailure instanceof NacosException nacos) {
                throw nacos;
            }
            if (operationFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
        }

        @Override
        public void shutDown() throws NacosException {
            closeCalls.incrementAndGet();
            closeEntered.countDown();
            try {
                await(closeRelease);
                physicallyClosed.set(true);
                if (closeFailure != null) {
                    throw closeFailure;
                }
            } finally {
                closeReturned.countDown();
            }
        }

        private static void await(CountDownLatch latch) throws NacosException {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test gate was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new NacosException(NacosException.CLIENT_ERROR, "Test operation interrupted");
            }
        }
    }
}
