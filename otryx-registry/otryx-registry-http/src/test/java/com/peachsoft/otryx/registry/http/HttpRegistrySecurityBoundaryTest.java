package com.peachsoft.otryx.registry.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 验证网络接收、异常正文、解析和真实取消共用受限资源，而非事后检查 String 长度。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 12:10
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class HttpRegistrySecurityBoundaryTest {
    private static final String SECRET = "server-reflected-secret-must-not-escape";

    @Test
    void exactBodyLimitSucceedsAndTheNextByteCancelsBeforeCopying() {
        BoundedBodySubscriber exact = new BoundedBodySubscriber(8);
        Subscription exactSource = new Subscription();
        exact.onSubscribe(exactSource);
        exact.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        exact.onNext(List.of(ByteBuffer.wrap(new byte[] {4, 5, 6, 7, 8})));
        exact.onComplete();
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, exact.getBody().toCompletableFuture().join());
        assertEquals(0, exactSource.cancellations.get());

        BoundedBodySubscriber overflow = new BoundedBodySubscriber(8);
        Subscription source = new Subscription();
        overflow.onSubscribe(source);
        overflow.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        ByteBuffer rejected = ByteBuffer.wrap(new byte[] {4, 5, 6, 7, 8, 9});
        overflow.onNext(List.of(rejected));
        assertEquals(0, rejected.position(), "an oversized chunk must not be copied before rejection");
        CompletionException failure = assertThrows(CompletionException.class,
                () -> overflow.getBody().toCompletableFuture().join());
        assertEquals(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED,
                assertInstanceOf(RegistrySecurityException.class, failure.getCause()).reason());
        int requested = source.requests.get();
        overflow.onNext(List.of(ByteBuffer.wrap(new byte[] {0})));
        overflow.onComplete();
        overflow.cancel();
        assertEquals(requested, source.requests.get());
        assertEquals(1, source.cancellations.get());
    }

    @Test
    void declaredAndChunkedOversizedBodiesRejectOnSuccessAndErrorStatuses() throws Exception {
        for (int status : new int[] {200, 403, 404, 500}) {
            for (boolean chunked : new boolean[] {false, true}) {
                byte[] body = new byte[33];
                try (Server server = new Server(exchange -> {
                    try (exchange) {
                        exchange.sendResponseHeaders(status, chunked ? 0 : body.length);
                        try {
                            exchange.getResponseBody().write(body);
                        } catch (IOException expectedCancellation) {
                            // A bounded client can close the stream before this deliberately oversized body finishes.
                        }
                    }
                }); HttpRegistryClient client = client(server.endpoint(), 32)) {
                    RegistrySecurityException failure = assertThrows(RegistrySecurityException.class,
                            () -> client.request("GET", "/value", null));
                    assertEquals(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED, failure.reason());
                    assertSafe(failure);
                }
            }
        }
    }

    @Test
    void missingResourcePreservesStatusWithoutParsingBackendErrorBody() throws Exception {
        for (String method : List.of("GET", "PUT")) {
            for (String body : List.of("unknown service ID: " + SECRET,
                    "Session id '" + SECRET + "' not found")) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                AtomicInteger requests = new AtomicInteger();
                try (Server server = new Server(exchange -> {
                    requests.incrementAndGet();
                    try (exchange) {
                        exchange.sendResponseHeaders(404, payload.length);
                        exchange.getResponseBody().write(payload);
                    }
                });
                        HttpRegistryClient client = client(server.endpoint(), 4096)) {
                    HttpRegistryClient.Response response = assertDoesNotThrow(
                            () -> client.request(method, "/value", null));
                    assertEquals(1, requests.get(), "the expected Consul text response must be served");
                    assertEquals(404, response.status());
                    assertTrue(response.json().isNull());
                    assertFalse(response.toString().contains(SECRET));
                }
            }
        }
    }

    @Test
    void strictUtf8AndAmbiguousJsonRejectWithoutExposingBody() throws Exception {
        List<byte[]> bodies = List.of(
                new byte[] {'"', (byte) 0xc3, 0x28, '"'},
                ("{\"value\":\"" + SECRET + "\",\"value\":\"duplicate\"}").getBytes(StandardCharsets.UTF_8),
                ("{\"value\":\"" + SECRET + "\"} {}").getBytes(StandardCharsets.UTF_8));
        for (byte[] body : bodies) {
            try (Server server = bodyServer(200, body); HttpRegistryClient client = client(server.endpoint(), 4096)) {
                RegistrySecurityException failure = assertThrows(RegistrySecurityException.class,
                        () -> client.request("GET", "/value", null));
                assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED, failure.reason());
                assertSafe(failure);
            }
        }
    }

    @Test
    void excessiveJsonDepthAndScalarRejectWithinTheRawBodyLimit() throws Exception {
        for (String body : List.of("[".repeat(65) + "0" + "]".repeat(65),
                "\"" + "a".repeat(512 * 1024 + 1) + "\"")) {
            try (Server server = bodyServer(200, body.getBytes(StandardCharsets.UTF_8));
                    HttpRegistryClient client = client(server.endpoint(), 1024 * 1024)) {
                RegistrySecurityException failure = assertThrows(RegistrySecurityException.class,
                        () -> client.request("GET", "/value", null));
                assertEquals(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED, failure.reason());
                assertSafe(failure);
            }
        }
    }

    @Test
    void authenticationAndAuthorizationFailuresNeverReflectBackendBodyOrCredentials() throws Exception {
        for (int status : new int[] {401, 403}) {
            try (Server server = bodyServer(status, SECRET.getBytes(StandardCharsets.UTF_8));
                    HttpRegistryClient client = new HttpRegistryClient(List.of(server.endpoint()),
                            "Authorization", "Basic " + SECRET, Duration.ofSeconds(3))) {
                RegistrySecurityException failure = assertThrows(RegistrySecurityException.class,
                        () -> client.request("GET", "/value", null));
                assertEquals(status == 401 ? RegistrySecurityFailure.AUTHENTICATION_REJECTED
                        : RegistrySecurityFailure.AUTHORIZATION_REJECTED, failure.reason());
                assertSafe(failure);
            }
        }
    }

    @Test
    void redirectCannotForwardARegistryCredentialToAnotherAuthority() throws Exception {
        AtomicInteger targetRequests = new AtomicInteger();
        try (Server target = new Server(exchange -> {
            targetRequests.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        }); Server redirect = new Server(exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", target.endpoint() + "/value");
                exchange.sendResponseHeaders(302, -1);
            }
        }); HttpRegistryClient client = new HttpRegistryClient(List.of(redirect.endpoint()),
                "X-Consul-Token", SECRET, Duration.ofSeconds(3))) {
            IOException failure = assertThrows(IOException.class, () -> client.request("GET", "/value", null));
            assertEquals(0, targetRequests.get());
            assertSafe(failure);
        }
    }

    @Test
    void aFailedWriteIsNotReplayedAgainstAnotherEndpoint() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (Server first = new Server(exchange -> {
            writes.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(503, -1);
            }
        }); Server second = new Server(exchange -> {
            writes.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        }); HttpRegistryClient client = new HttpRegistryClient(
                List.of(first.endpoint(), second.endpoint()), "", "", Duration.ofSeconds(3))) {
            assertThrows(IOException.class, () -> client.request("PUT", "/value", java.util.Map.of("value", 1)));
            assertEquals(1, writes.get());
        }
    }

    @Test
    void closeCancelsTheActualBodyWaitAndReleasesTheCallingWorker() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (Server server = new Server(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                try {
                    releaseServer.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }); var worker = Executors.newSingleThreadExecutor();
                HttpRegistryClient client = client(server.endpoint(), 1024)) {
            var pending = worker.submit(() -> client.request("GET", "/value", null));
            try {
                assertTrue(bodyStarted.await(3, TimeUnit.SECONDS));
                client.close();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> pending.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause());
                assertSafe(failure);
                assertEquals("worker-released", worker.submit(() -> "worker-released").get(3, TimeUnit.SECONDS));
            } finally {
                releaseServer.countDown();
            }
        }
    }

    private static HttpRegistryClient client(String endpoint, int cap) {
        return new HttpRegistryClient(List.of(endpoint), "", "", Duration.ofSeconds(3), cap, Optional.empty());
    }

    private static Server bodyServer(int status, byte[] body) throws IOException {
        return new Server(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
    }

    private static void assertSafe(Throwable failure) {
        if (failure instanceof RegistrySecurityException) {
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
        }
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        assertFalse(text.toString().contains(SECRET));
    }

    private static final class Server implements AutoCloseable {
        private final HttpServer server;

        private Server(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/value", handler);
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class Subscription implements Flow.Subscription {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();

        @Override
        public void request(long count) {
            requests.incrementAndGet();
        }

        @Override
        public void cancel() {
            cancellations.incrementAndGet();
        }
    }
}
