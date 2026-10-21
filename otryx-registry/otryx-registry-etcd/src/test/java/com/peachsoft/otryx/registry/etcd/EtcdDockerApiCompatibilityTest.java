package com.peachsoft.otryx.registry.etcd;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.DockerClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;

/**
 * 通过受控 HTTP 端点验证 Etcd 测试使用的真实 Testcontainers 客户端遵守 Docker API 版本边界。
 *
 * <p>端点只实现版本化 ping，不创建容器；真实 Etcd 集群启动仍由集成与 Chaos 门禁独立验证。
 * 每个用例独占临时回环端口及客户端，失败时也关闭 HTTP 服务与客户端连接。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 02:11
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class EtcdDockerApiCompatibilityTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(EtcdDockerApiCompatibilityTest.class);

    @Test
    void managedClientAcceptsEngineRequiringApi140OrNewer() throws Exception {
        verifyClientPing(40, 55);
    }

    @Test
    void managedClientRemainsCompatibleWithEngineSupportingAtMostApi143() throws Exception {
        verifyClientPing(32, 43);
    }

    private static void verifyClientPing(int minimumMinor, int maximumMinor) throws Exception {
        try (DockerPingEndpoint endpoint = new DockerPingEndpoint(minimumMinor, maximumMinor);
                DockerClient client = DockerClientProviderStrategy.getClientForConfig(
                        TransportConfig.builder().dockerHost(endpoint.dockerHost()).build())) {
            try {
                assertDoesNotThrow(() -> client.pingCmd().exec(),
                        () -> "Client must reach Docker API 1." + minimumMinor + " through 1." + maximumMinor
                                + "; observed requests=" + endpoint.observedRequests());
                assertTrue(endpoint.acceptedPings() > 0,
                        "The endpoint must receive an actual ping within its supported API range");
            } finally {
                LOGGER.info("Docker API compatibility range=1.{}..1.{}, requests={}",
                        minimumMinor, maximumMinor, endpoint.observedRequests());
            }
        }
    }

    /** 提供版本范围固定的回环 Docker ping 端点；请求记录有界，关闭时停止所属 HTTP 服务。 */
    private static final class DockerPingEndpoint implements AutoCloseable {
        private static final String LOOPBACK_ADDRESS = "127.0.0.1";
        private static final int MAX_RECORDED_REQUESTS = 16;
        private static final Pattern VERSIONED_PING = Pattern.compile("/v1\\.(\\d+)/_ping");

        private final int minimumMinor;
        private final int maximumMinor;
        private final HttpServer server;
        private final ArrayBlockingQueue<String> requests = new ArrayBlockingQueue<>(MAX_RECORDED_REQUESTS);
        private final AtomicInteger successfulPings = new AtomicInteger();

        private DockerPingEndpoint(int minimumMinor, int maximumMinor) throws IOException {
            this.minimumMinor = minimumMinor;
            this.maximumMinor = maximumMinor;
            server = HttpServer.create(new InetSocketAddress(LOOPBACK_ADDRESS, 0), 1);
            boolean started = false;
            try {
                server.createContext("/", this::handlePing);
                server.start();
                started = true;
            } finally {
                if (!started) {
                    server.stop(0);
                }
            }
        }

        private URI dockerHost() {
            return URI.create("tcp://" + LOOPBACK_ADDRESS + ":" + server.getAddress().getPort());
        }

        private List<String> observedRequests() {
            return List.copyOf(requests);
        }

        private int acceptedPings() {
            return successfulPings.get();
        }

        private void handlePing(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!requests.offer(exchange.getRequestMethod() + " " + path)) {
                    respond(exchange, 429, "{\"message\":\"request evidence capacity exceeded\"}");
                    return;
                }
                Matcher version = VERSIONED_PING.matcher(path);
                if (!"GET".equals(exchange.getRequestMethod()) || !version.matches()) {
                    respond(exchange, 404, "{\"message\":\"only versioned Docker ping is supported\"}");
                    return;
                }
                int minor = Integer.parseInt(version.group(1));
                if (minor < minimumMinor || minor > maximumMinor) {
                    respond(exchange, 400, "{\"message\":\"Docker API version outside supported range\"}");
                    return;
                }
                successfulPings.incrementAndGet();
                respond(exchange, 200, "OK");
            } finally {
                exchange.close();
            }
        }

        private void respond(HttpExchange exchange, int status, String content) throws IOException {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("API-Version", "1." + maximumMinor);
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/plain" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        /** 立即停止当前用例拥有的端口及服务线程，释放尚未完成的 HTTP 交换。 */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
