package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Consul 的请求预算必须覆盖响应头之后停滞的 body，不能占住控制面工作槽。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:37
 */
class ConsulHttpDeadlineBoundaryTest {

    @Test
    void responseHeadersDoNotEndTheTotalRequestDeadline() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch finishBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/services", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headers.countDown();
                try {
                    finishBody.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.getResponseBody().write('}');
            }
        });
        server.start();
        try (var worker = Executors.newSingleThreadExecutor();
                var client = new ConsulV2Client(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "", "", Duration.ofMillis(150))) {
            var request = worker.submit(() -> client.json("GET", "/v1/agent/services", null));
            try {
                assertTrue(headers.await(2, TimeUnit.SECONDS), "fixture must send response headers");
                assertThrows(ExecutionException.class,
                        () -> request.get(750, TimeUnit.MILLISECONDS),
                        "the request must fail before the stalled body is released");
            } finally {
                finishBody.countDown();
                request.cancel(true);
            }
        } finally {
            finishBody.countDown();
            server.stop(0);
        }
    }
}
