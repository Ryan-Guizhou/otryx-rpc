package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.config.ConfigQueryResult;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 验证 Nacos Manifest 发布确认只等待服务端可见性，不重写或接受冲突内容。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 09:54
 */
class NacosApplicationManifestRepositoryTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("public", "manifest-confirmation");

    @Test
    void publishWaitsForAuthoritativeVisibilityAfterAcknowledgedWrite() {
        ApplicationManifest manifest = manifest("demo.Catalog");
        ScriptedConfig config = new ScriptedConfig(List.of(empty(), empty(), response(manifest)));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            NacosApplicationManifestRepository repository = repository(config, executor);
            assertDoesNotThrow(() -> repository.publish(manifest).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, config.publishes.get());
            assertEquals(3, config.reads.get());
            assertEquals(encoded(manifest), config.publishedContent);
        }
    }

    @Test
    void publishDoesNotRetryAConflictingConfirmedValue() {
        ApplicationManifest expected = manifest("demo.Catalog");
        ScriptedConfig config = new ScriptedConfig(List.of(
                empty(), empty(), response(manifest("demo.Foreign")), response(expected)));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> repository(config, executor).publish(expected).toCompletableFuture().join());
            assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                    assertInstanceOf(RegistrySecurityException.class, failure.getCause()).reason());
            assertEquals(1, config.publishes.get());
            assertEquals(3, config.reads.get());
        }
    }

    @Test
    void publishDoesNotRetryAnSdkFailure() {
        NacosException denied = new NacosException(NacosException.NO_RIGHT, "denied");
        ScriptedConfig config = new ScriptedConfig(List.of(empty(), denied, response(manifest("demo.Catalog"))));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> repository(config, executor).publish(manifest("demo.Catalog")).toCompletableFuture().join());
            assertEquals(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                    assertInstanceOf(RegistrySecurityException.class, failure.getCause()).reason());
            assertEquals(null, failure.getCause().getCause());
            assertEquals(1, config.publishes.get());
            assertEquals(2, config.reads.get());
        }
    }

    @Test
    void publishDoesNotAcceptNonAuthoritativeSnapshotContent() {
        ApplicationManifest expected = manifest("demo.Catalog");
        ConfigQueryResult noMd5 = response(expected);
        noMd5.setMd5(null);
        ScriptedConfig config = new ScriptedConfig(List.of(empty(), empty(), noMd5, response(expected)));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> repository(config, executor).publish(expected).toCompletableFuture().join());
            assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                    assertInstanceOf(RegistrySecurityException.class, failure.getCause()).reason());
            assertEquals(1, config.publishes.get());
            assertEquals(3, config.reads.get());
        }
    }

    @Test
    void rejectedPublishCanOnlySucceedAfterMatchingRemoteContentIsVerified() {
        ApplicationManifest expected = manifest("demo.Catalog");
        ScriptedConfig config = new ScriptedConfig(List.of(empty(), empty(), response(expected)));
        config.publishAccepted = false;
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            assertDoesNotThrow(() -> repository(config, executor).publish(expected)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, config.publishes.get());
            assertEquals(3, config.reads.get());
        }
    }

    @Test
    void confirmationReadConsumesRemainingDeadlineAndRejectsLateSuccess() {
        AtomicLong clock = new AtomicLong();
        ApplicationManifest expected = manifest("demo.Catalog");
        ReadAction firstConfirmation = timeoutMillis -> {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1L));
            return empty();
        };
        ReadAction lateConfirmation = timeoutMillis -> {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2L));
            return response(expected);
        };
        ScriptedConfig config = new ScriptedConfig(List.of(empty(), firstConfirmation, lateConfirmation));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            NacosApplicationManifestRepository repository = new NacosApplicationManifestRepository(
                    new NacosV2ConfigStore(config.service(), "MANIFEST_TEST"), executor,
                    APPLICATION.namespace(), clock::get);
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> repository.publish(expected).toCompletableFuture().join());
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(List.of(3000L, 3000L, 2000L), config.readTimeouts);
            assertEquals(1, config.publishes.get());
        }
    }

    @Test
    void subMillisecondRemainderNeverBecomesAnUnlimitedSdkTimeout() {
        AtomicLong clock = new AtomicLong();
        ReadAction nearlyExpired = timeoutMillis -> {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3L) - 1L);
            return empty();
        };
        ScriptedConfig config = new ScriptedConfig(List.of(empty(), nearlyExpired));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            NacosApplicationManifestRepository repository = new NacosApplicationManifestRepository(
                    new NacosV2ConfigStore(config.service(), "MANIFEST_TEST"), executor,
                    APPLICATION.namespace(), clock::get);
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> repository.publish(manifest("demo.Catalog")).toCompletableFuture().join());
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(List.of(3000L, 3000L), config.readTimeouts);
        }
    }

    @Test
    void timedOutPublishCanBeRetriedWithoutRewritingTheNowVisibleManifest() {
        AtomicLong clock = new AtomicLong();
        ApplicationManifest expected = manifest("demo.Catalog");
        ReadAction unavailableUntilDeadline = timeoutMillis -> {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3L));
            return empty();
        };
        ScriptedConfig config = new ScriptedConfig(List.of(empty(), unavailableUntilDeadline, response(expected)));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            NacosApplicationManifestRepository repository = new NacosApplicationManifestRepository(
                    new NacosV2ConfigStore(config.service(), "MANIFEST_TEST"), executor,
                    APPLICATION.namespace(), clock::get);
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> repository.publish(expected).toCompletableFuture().join());
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertDoesNotThrow(() -> repository.publish(expected).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, config.publishes.get());
            assertEquals(3, config.reads.get());
        }
    }

    @Test
    void twoConcurrentPublishersOfTheSameRevisionVerifyContentIndependently() {
        ApplicationManifest expected = manifest("demo.Catalog");
        CountDownLatch initialReads = new CountDownLatch(2);
        ReadAction concurrentMissing = timeoutMillis -> {
            initialReads.countDown();
            assertTrue(initialReads.await(3, TimeUnit.SECONDS));
            return empty();
        };
        ScriptedConfig config = new ScriptedConfig(List.of(
                concurrentMissing, concurrentMissing, empty(), empty(), response(expected), response(expected)));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            NacosApplicationManifestRepository first = repository(config, executor);
            NacosApplicationManifestRepository second = repository(config, executor);
            assertDoesNotThrow(() -> CompletableFuture.allOf(
                    first.publish(expected).toCompletableFuture(),
                    second.publish(expected).toCompletableFuture()).get(5, TimeUnit.SECONDS));
            assertEquals(2, config.publishes.get());
            assertEquals(6, config.reads.get());
            assertEquals(encoded(expected), config.publishedContent);
        }
    }

    @Test
    void executorCloseInterruptsPendingConfirmationWithoutAnotherPublish() throws Exception {
        CountDownLatch confirming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReadAction blocked = timeoutMillis -> {
            confirming.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return empty();
        };
        ScriptedConfig config = new ScriptedConfig(List.of(empty(), blocked));
        try (NacosControlExecutor executor = new NacosControlExecutor()) {
            CompletableFuture<Void> published = repository(config, executor)
                    .publish(manifest("demo.Catalog")).toCompletableFuture();
            assertTrue(confirming.await(3, TimeUnit.SECONDS));
            executor.close();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> published.get(3, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(1, config.publishes.get());
            assertEquals(2, config.reads.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void configReadRequiresPositiveBudgetAndPreservesItsExistingMaximum() throws Exception {
        ScriptedConfig config = new ScriptedConfig(List.of(empty()));
        NacosV2ConfigStore store = new NacosV2ConfigStore(config.service(), "MANIFEST_TEST");
        assertThrows(IllegalArgumentException.class, () -> store.read("manifest", 0L));
        assertThrows(IllegalArgumentException.class, () -> store.read("manifest", -1L));
        assertEquals(0, config.reads.get());
        store.read("manifest", 5000L);
        assertEquals(List.of(3000L), config.readTimeouts);
    }

    private static NacosApplicationManifestRepository repository(
            ScriptedConfig config, NacosControlExecutor executor) {
        return new NacosApplicationManifestRepository(
                new NacosV2ConfigStore(config.service(), "MANIFEST_TEST"), executor, APPLICATION.namespace());
    }

    private static ApplicationManifest manifest(String serviceName) {
        ServiceKey service = new ServiceKey(serviceName, "1.0.0", "default");
        return ApplicationManifest.create(APPLICATION, List.of(new ExportedService(
                service, RpcIds.serviceId(service), Set.of(1), "a".repeat(64))));
    }

    private static String encoded(ApplicationManifest manifest) {
        return "otryx-v2-manifest:" + Base64.getEncoder().encodeToString(manifest.canonicalBytes());
    }

    private static ConfigQueryResult empty() {
        return new ConfigQueryResult();
    }

    private static ConfigQueryResult response(ApplicationManifest manifest) {
        ConfigQueryResult response = new ConfigQueryResult();
        response.setContent(encoded(manifest));
        try {
            response.setMd5(HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(response.getContent().getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
        return response;
    }

    private static final class ScriptedConfig {
        private final List<?> responses;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger publishes = new AtomicInteger();
        private final List<Long> readTimeouts = new CopyOnWriteArrayList<>();
        private volatile String publishedContent;
        private boolean publishAccepted = true;

        private ScriptedConfig(List<?> responses) {
            this.responses = responses;
        }

        private ConfigService service() {
            return (ConfigService) Proxy.newProxyInstance(
                    ConfigService.class.getClassLoader(), new Class<?>[] {ConfigService.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getConfigWithResult" -> {
                            long timeoutMillis = (Long) args[2];
                            readTimeouts.add(timeoutMillis);
                            Object next = responses.get(reads.getAndIncrement());
                            if (next instanceof ReadAction action) {
                                yield action.read(timeoutMillis);
                            }
                            if (next instanceof Exception error) {
                                throw error;
                            }
                            yield next;
                        }
                        case "publishConfig" -> {
                            publishes.incrementAndGet();
                            publishedContent = (String) args[2];
                            yield publishAccepted;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @FunctionalInterface
    private interface ReadAction {
        ConfigQueryResult read(long timeoutMillis) throws Exception;
    }
}
