package com.peachsoft.otryx.registry.http;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 在完整字符串构造之前验证远端 JSON 的真实 UTF-8 字节预算。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:37
 */
class HttpRegistryByteBoundaryTest {

    private static final int OLD_CHARACTER_LIMIT = 4_194_304;

    @Test
    void multibyteResponseExceedingByteBudgetIsRejected() throws Exception {
        byte[] body = ("\"" + "汉".repeat(OLD_CHARACTER_LIMIT / 3) + "\"")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metadata", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (HttpRegistryClient client = new HttpRegistryClient(
                List.of("http://127.0.0.1:" + server.getAddress().getPort()),
                "", "", Duration.ofSeconds(5))) {
            assertThrows(Exception.class,
                    () -> client.request(HttpRegistryClient.METHOD_GET, "/metadata", null));
        } finally {
            server.stop(0);
        }
    }
}
