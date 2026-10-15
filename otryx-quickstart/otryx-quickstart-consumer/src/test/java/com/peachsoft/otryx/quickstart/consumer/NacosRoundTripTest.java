package com.peachsoft.otryx.quickstart.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.quickstart.provider.ProviderApplication;
import com.peachsoft.otryx.generated.RpcGeneratedClients;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 两个独立 Spring Context 通过 Nacos 完成真实 RPC round-trip。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:38
 */
class NacosRoundTripTest {

    @Test
    void providerLifecycleShouldPropagateThroughNacos()
            throws Exception {
        String endpoint = System.getenv(
                "NACOS_TEST_ENDPOINT");
        assumeTrue(
                endpoint != null && !endpoint.isBlank(),
                "NACOS_TEST_ENDPOINT is required");

        String group = "OTRYX_RPC_E2E_"
                + UUID.randomUUID()
                        .toString()
                        .replace("-", "");
        ConfigurableApplicationContext provider = null;
        ConfigurableApplicationContext consumer = null;
        try {
            provider = new SpringApplicationBuilder(
                    ProviderApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            registryProperties(
                                    endpoint,
                                    group))
                    .properties(
                            "otryx.rpc.server.host=127.0.0.1",
                            "otryx.rpc.server.port=0",
                            "otryx.rpc.server.advertised-host=127.0.0.1",
                            "otryx.rpc.server.advertised-port=0")
                    .run();
            consumer = new SpringApplicationBuilder(
                    ConsumerApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            registryProperties(
                                    endpoint,
                                    group))
                    .run();

            assertTrue(
                    RpcGeneratedClients.find(
                                    GreetingService.class)
                            .isPresent());
            OtryxRpcClient client =
                    consumer.getBean(OtryxRpcClient.class);
            GreetingService service = client.refer(
                    GreetingService.class,
                    "1.0.0",
                    "default");
            GreetingReply reply = service.hello(
                    new GreetingRequest("OTRYX RPC"));
            assertEquals(
                    "Hello, OTRYX RPC!",
                    reply.message());

            provider.close();
            provider = null;

            assertTrue(await(
                    Duration.ofSeconds(15),
                    () -> unavailable(service)));
        } finally {
            if (consumer != null) {
                consumer.close();
            }
            if (provider != null) {
                provider.close();
            }
        }
    }

    private static boolean unavailable(
            GreetingService service) {
        try {
            service.hello(
                    new GreetingRequest("after-stop"));
            return false;
        } catch (RuntimeException error) {
            return unwrapUnavailable(error) != null;
        }
    }

    private static RpcUnavailableException unwrapUnavailable(
            RuntimeException error) {
        if (error instanceof RpcUnavailableException unavailable) {
            return unavailable;
        }
        if (error instanceof CompletionException
                && error.getCause()
                        instanceof RpcUnavailableException unavailable) {
            return unavailable;
        }
        return null;
    }

    private static boolean await(
            Duration timeout,
            CheckedBoolean condition) throws Exception {
        long deadline =
                System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return true;
            }
            Thread.sleep(100L);
        }
        return condition.get();
    }

    private static String[] registryProperties(
            String endpoint,
            String group) {
        return new String[]{
                "otryx.rpc.registry.type=nacos",
                "otryx.rpc.registry.endpoints=" + endpoint,
                "otryx.rpc.registry.namespace=public",
                "otryx.rpc.registry.nacos.group=" + group,
                "otryx.rpc.registry.nacos.cluster=DEFAULT"
        };
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
