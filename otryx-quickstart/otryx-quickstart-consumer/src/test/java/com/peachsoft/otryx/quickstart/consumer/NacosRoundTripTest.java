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
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.nacos.NacosV2MetadataBootstrap;
import com.peachsoft.otryx.spring.autoconfigure.OtryxRpcProperties;
import java.time.Duration;
import java.util.Map;
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

    private static final String NAMESPACE = "public";
    private static final String PROVIDER_APPLICATION = "otryx-quickstart-e2e-provider";
    private static final String CONSUMER_APPLICATION = "otryx-quickstart-e2e-consumer";

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
        NacosV2MetadataBootstrap.initialize(RegistryOptions.fromCsv(
                endpoint, NAMESPACE, Map.of("nacosGroup", group)));
        ConfigurableApplicationContext provider = null;
        ConfigurableApplicationContext consumer = null;
        try {
            provider = new SpringApplicationBuilder(
                    ProviderApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(applicationArguments(endpoint, group, PROVIDER_APPLICATION));
            assertScope(provider, endpoint, group, PROVIDER_APPLICATION);
            consumer = new SpringApplicationBuilder(
                    ConsumerApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(applicationArguments(endpoint, group, CONSUMER_APPLICATION));
            assertScope(consumer, endpoint, group, CONSUMER_APPLICATION);

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

    private static String[] applicationArguments(
            String endpoint,
            String group,
            String applicationName) {
        return new String[]{
                "--spring.application.name=" + applicationName,
                "--otryx.rpc.registry.application-name=" + applicationName,
                "--otryx.rpc.registry.type=nacos",
                "--otryx.rpc.registry.endpoints=" + endpoint,
                "--otryx.rpc.registry.namespace=" + NAMESPACE,
                "--otryx.rpc.registry.nacos.group=" + group,
                "--otryx.rpc.registry.nacos.cluster=DEFAULT",
                "--otryx.rpc.server.host=127.0.0.1",
                "--otryx.rpc.server.port=0",
                "--otryx.rpc.server.advertised-host=127.0.0.1",
                "--otryx.rpc.server.advertised-port=0"
        };
    }

    private static void assertScope(ConfigurableApplicationContext context,
            String endpoint, String group, String applicationName) {
        OtryxRpcProperties properties = context.getBean(OtryxRpcProperties.class);
        assertEquals(endpoint, properties.getRegistry().getEndpoints());
        assertEquals(NAMESPACE, properties.getRegistry().getNamespace());
        assertEquals(group, properties.getRegistry().getNacos().getGroup());
        assertEquals(applicationName, properties.getRegistry().getApplicationName());
        assertEquals(0, properties.getServer().getPort());
        assertEquals(0, properties.getServer().getAdvertisedPort());
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
