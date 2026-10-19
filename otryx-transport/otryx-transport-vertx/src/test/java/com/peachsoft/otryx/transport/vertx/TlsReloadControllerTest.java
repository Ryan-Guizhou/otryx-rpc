package com.peachsoft.otryx.transport.vertx;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.SSLOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS 控制面线程、单飞与关闭竞争回归。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 14:33
 */
class TlsReloadControllerTest {

    @Test
    void unchangedInitialMaterialMustNotInterruptActiveTlsConnections()
            throws Exception {
        Vertx vertx = Vertx.vertx();
        AtomicReference<String> material = new AtomicReference<>("v1");
        AtomicInteger publications = new AtomicInteger();
        CountDownLatch publishedChanged = new CountDownLatch(1);
        TlsReloadController<String, SSLOptions> controller =
                new TlsReloadController<>(
                        vertx.getOrCreateContext(),
                        "v1",
                        material::get,
                        ignored -> new SSLOptions(),
                        ignored -> {
                            publications.incrementAndGet();
                            publishedChanged.countDown();
                            return Future.succeededFuture();
                        },
                        (start, error) -> {
                        });
        try {
            controller.requestReload();
            awaitIdle(controller);
            assertEquals(0, publications.get());

            material.set("v2");
            controller.requestReload();
            assertTrue(publishedChanged.await(2, TimeUnit.SECONDS));
            awaitIdle(controller);
            assertEquals(1, publications.get());
            assertEquals("v2", controller.lastSuccessfulState());
        } finally {
            controller.close();
            close(vertx);
        }
    }

    @Test
    void slowMaterialReadMustNotBlockConnectionContext()
            throws Exception {
        Vertx vertx = Vertx.vertx();
        Context context = vertx.getOrCreateContext();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LinkedBlockingQueue<String> outcomes = new LinkedBlockingQueue<>();
        TlsReloadController<String, SSLOptions> controller =
                new TlsReloadController<>(
                        context,
                        () -> {
                            reading.countDown();
                            try {
                                if (!release.await(3, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "TLS test did not release reader");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            }
                            return "material-v1";
                        },
                        ignored -> new SSLOptions(),
                        ignored -> Future.succeededFuture(),
                        (start, error) -> outcomes.offer(
                                error == null ? "OK" : "FAIL"));
        try {
            controller.requestReload();
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> ping = new CompletableFuture<>();
            context.runOnContext(ignored -> ping.complete(null));
            ping.get(2, TimeUnit.SECONDS);
            release.countDown();
            assertEquals("OK", outcomes.poll(2, TimeUnit.SECONDS));
            awaitIdle(controller);
            assertEquals("material-v1", controller.lastSuccessfulState());
        } finally {
            release.countDown();
            controller.close();
            close(vertx);
        }
    }

    @Test
    void overlappingReloadMustUseSinglePreparation()
            throws Exception {
        Vertx vertx = Vertx.vertx();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger readers = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        TlsReloadController<String, SSLOptions> controller =
                new TlsReloadController<>(
                        vertx.getOrCreateContext(),
                        () -> {
                            int active = concurrent.incrementAndGet();
                            maximum.accumulateAndGet(active, Math::max);
                            readers.incrementAndGet();
                            entered.countDown();
                            try {
                                if (!release.await(3, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "TLS preparation not released");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            } finally {
                                concurrent.decrementAndGet();
                            }
                            return "v1";
                        },
                        ignored -> new SSLOptions(),
                        ignored -> {
                            publications.incrementAndGet();
                            return Future.succeededFuture();
                        },
                        (start, error) -> completed.countDown());
        try {
            controller.requestReload();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int attempt = 0; attempt < 100; attempt++) {
                controller.requestReload();
            }
            release.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            awaitIdle(controller);
            assertEquals(1, maximum.get());
            assertEquals(2, readers.get());
            assertEquals(1, publications.get());
        } finally {
            release.countDown();
            controller.close();
            close(vertx);
        }
    }

    @Test
    void invalidMaterialMustKeepLastGoodConfiguration()
            throws Exception {
        Vertx vertx = Vertx.vertx();
        AtomicReference<String> files = new AtomicReference<>("v1");
        AtomicBoolean invalid = new AtomicBoolean();
        AtomicInteger publications = new AtomicInteger();
        LinkedBlockingQueue<String> outcomes = new LinkedBlockingQueue<>();
        TlsReloadController<String, SSLOptions> controller =
                new TlsReloadController<>(
                        vertx.getOrCreateContext(),
                        files::get,
                        ignored -> {
                            if (invalid.get()) {
                                throw new IllegalArgumentException(
                                        "Invalid certificate test input");
                            }
                            return new SSLOptions();
                        },
                        ignored -> {
                            publications.incrementAndGet();
                            return Future.succeededFuture();
                        },
                        (start, error) -> outcomes.offer(
                                error == null ? "OK" : "FAIL"));
        try {
            controller.requestReload();
            assertEquals("OK", outcomes.poll(2, TimeUnit.SECONDS));
            awaitIdle(controller);
            assertEquals("v1", controller.lastSuccessfulState());

            files.set("v2");
            invalid.set(true);
            controller.requestReload();
            assertEquals("FAIL", outcomes.poll(2, TimeUnit.SECONDS));
            awaitIdle(controller);
            assertEquals("v1", controller.lastSuccessfulState());
            assertEquals(1, publications.get());

            invalid.set(false);
            controller.requestReload();
            assertEquals("OK", outcomes.poll(2, TimeUnit.SECONDS));
            awaitIdle(controller);
            assertEquals("v2", controller.lastSuccessfulState());
            assertEquals(2, publications.get());
        } finally {
            controller.close();
            close(vertx);
        }
    }

    @Test
    void closeMustPreventPreparedConfigurationPublication()
            throws Exception {
        Vertx vertx = Vertx.vertx();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        TlsReloadController<String, SSLOptions> controller =
                new TlsReloadController<>(
                        vertx.getOrCreateContext(),
                        () -> {
                            entered.countDown();
                            boolean released = false;
                            while (!released) {
                                try {
                                    released = release.await(
                                            3, TimeUnit.SECONDS);
                                } catch (InterruptedException ignored) {
                                    // 用受控 latch 模拟不响应中断的外部读取。
                                }
                            }
                            return "v2";
                        },
                        ignored -> new SSLOptions(),
                        ignored -> {
                            publications.incrementAndGet();
                            return Future.succeededFuture();
                        },
                        (start, error) -> {
                        });
        try {
            controller.requestReload();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            controller.close();
            release.countDown();
            awaitIdle(controller);
            assertEquals(0, publications.get());
            assertNull(controller.lastSuccessfulState());
        } finally {
            release.countDown();
            controller.close();
            close(vertx);
        }
    }

    @Test
    void changedMaterialDuringPreparationMustNotBePublished()
            throws Exception {
        Vertx vertx = Vertx.vertx();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        LinkedBlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        TlsReloadController<String, SSLOptions> controller =
                new TlsReloadController<>(
                        vertx.getOrCreateContext(),
                        () -> reads.incrementAndGet() == 1 ? "old" : "new",
                        ignored -> new SSLOptions(),
                        ignored -> {
                            publications.incrementAndGet();
                            return Future.succeededFuture();
                        },
                        (start, error) -> {
                            if (error != null) {
                                failures.offer(error);
                            }
                        });
        try {
            controller.requestReload();
            Throwable error = failures.poll(2, TimeUnit.SECONDS);
            assertNotNull(error);
            awaitIdle(controller);
            assertTrue(error instanceof IllegalStateException);
            assertEquals(0, publications.get());
            assertNull(controller.lastSuccessfulState());
        } finally {
            controller.close();
            close(vertx);
        }
    }

    private static void awaitIdle(TlsReloadController<?, ?> controller) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (controller.reloadInProgress() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertFalse(controller.reloadInProgress());
    }

    private static void close(Vertx vertx) throws Exception {
        vertx.close().toCompletionStage()
                .toCompletableFuture().get(3, TimeUnit.SECONDS);
    }
}
