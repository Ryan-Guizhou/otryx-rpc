package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证应用身份、角色与实例不变量。
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
class ApplicationIdentityTest {

    @Test
    void shouldNormalizeApplicationIdentity() {
        ApplicationKey key = new ApplicationKey(" default ", " peach-order ");
        assertEquals("default", key.namespace());
        assertEquals("peach-order", key.applicationName());
        assertEquals(new ApplicationKey("default", "peach-order"), key);
    }

    @Test
    void shouldRejectInvalidIdentity() {
        assertThrows(NullPointerException.class, () -> new ApplicationKey(null, "app"));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationKey("", "app"));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationKey("name space", "app"));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationKey("default", "hello/path"));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationKey("default", " "));
        assertThrows(IllegalArgumentException.class, () ->
                new ApplicationKey("default", "a".repeat(129)));
    }

    @Test
    void providerMustDeclareRealEndpointAndManifest() {
        ApplicationKey key = new ApplicationKey("default", "peach-order");
        RpcEndpoint endpoint = new RpcEndpoint("127.0.0.1", 19090);
        ApplicationInstance provider = new ApplicationInstance(
                "order-node-1", key, ApplicationRole.PROVIDER, endpoint, 100, "rev-v1", Map.of());
        assertTrue(provider.routableProvider());
        assertTrue(provider.role().providesRpc());
        assertFalse(provider.role().consumesRpc());

        assertThrows(NullPointerException.class, () -> new ApplicationInstance(
                "node-1", key, ApplicationRole.PROVIDER, null, 100, "rev-v1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationInstance(
                "node-1", key, ApplicationRole.PROVIDER, endpoint, 0, "rev-v1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationInstance(
                "node-1", key, ApplicationRole.PROVIDER, endpoint, 100, " ", Map.of()));
    }

    @Test
    void pureConsumerMustNotAdvertiseRpcEndpoint() {
        ApplicationKey key = new ApplicationKey("default", "peach-front");
        ApplicationInstance consumer = new ApplicationInstance(
                "consumer-1", key, ApplicationRole.CONSUMER, null, 0, null, Map.of());
        assertFalse(consumer.routableProvider());
        assertTrue(consumer.role().consumesRpc());
        assertThrows(IllegalArgumentException.class, () -> new ApplicationInstance(
                "consumer-1", key, ApplicationRole.CONSUMER,
                new RpcEndpoint("127.0.0.1", 19090), 0, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationInstance(
                "consumer-1", key, ApplicationRole.CONSUMER, null, 0, "rev", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationInstance(
                "consumer-1", key, ApplicationRole.CONSUMER, null, 100, null, Map.of()));
    }

    @Test
    void bothRoleSupportsInboundAndOutboundCalls() {
        ApplicationKey key = new ApplicationKey("default", "peach-gateway");
        ApplicationInstance both = new ApplicationInstance(
                "gateway-1", key, ApplicationRole.BOTH,
                new RpcEndpoint("127.0.0.1", 19090), 100, "rev", Map.of());
        assertTrue(both.routableProvider());
        assertTrue(both.role().consumesRpc());
    }

    @Test
    void attributesAreDefensiveAndBounded() {
        ApplicationKey key = new ApplicationKey("default", "app");
        Map<String, String> source = new HashMap<>();
        source.put("region", "cn-south");
        ApplicationInstance consumer = new ApplicationInstance(
                "consumer-1", key, ApplicationRole.CONSUMER, null, 0, null, source);
        source.put("region", "us-east");
        assertEquals("cn-south", consumer.attributes().get("region"));
        assertThrows(UnsupportedOperationException.class, () ->
                consumer.attributes().put("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationInstance(
                "consumer-1", key, ApplicationRole.CONSUMER, null, 0, null,
                Map.of("bad\nkey", "value")));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationInstance(
                "consumer-1", key, ApplicationRole.CONSUMER, null, 0, null,
                Map.of("value", "v".repeat(1025))));
    }
}
