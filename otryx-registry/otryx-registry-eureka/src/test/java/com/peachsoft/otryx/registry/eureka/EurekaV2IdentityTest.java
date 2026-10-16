package com.peachsoft.otryx.registry.eureka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.registry.RegistryOptions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 验证 Eureka 真实 Member 角色、Endpoint disabled 语义与外置 Backend 失败关闭。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:09
 */
class EurekaV2IdentityTest {

    private static final ApplicationKey APP = new ApplicationKey("production", "orders");
    private static final String TOKEN = "caaacaeb-bf35-4132-8e2b-18511148fae9";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void allRolesShareOneApplicationNameButDifferentProcessesGetUniqueNativeIds() {
        EurekaV2Identity identity = new EurekaV2Identity(APP.namespace());
        ApplicationInstance p = provider("provider");
        ApplicationInstance c = consumer("consumer");
        assertEquals(identity.appName(p.application()), identity.appName(c.application()));
        assertTrue(identity.appName(APP).startsWith("OTRYX-"));
        assertFalse(identity.nativeInstanceId(p, TOKEN).equals(
                identity.nativeInstanceId(p, UUID.randomUUID().toString())));
        assertThrows(IllegalArgumentException.class,
                () -> identity.appName(new ApplicationKey("other", "orders")));
    }

    @Test
    void genuineConsumerMemberHasDisabledNativePortAndIsNeverRoutable() {
        EurekaV2Identity identity = new EurekaV2Identity(APP.namespace());
        ApplicationInstance c = consumer("consumer");
        Map<String, Object> nativeEntry = identity.nativeRegistration(c, TOKEN,
                "127.0.0.1", 1, 9);
        JsonNode raw = MAPPER.valueToTree(nativeEntry).path(EurekaProtocol.FIELD_INSTANCE);
        assertEquals(0, raw.path(EurekaProtocol.FIELD_PORT)
                .path(EurekaProtocol.FIELD_VALUE).asInt());
        assertEquals(EurekaProtocol.SECURE_PORT_DISABLED, raw.path(EurekaProtocol.FIELD_PORT)
                .path(EurekaProtocol.FIELD_ENABLED).asText());
        ApplicationInstance decoded = identity.fromInstance(APP, raw);
        assertEquals(c, decoded);
        assertNull(decoded.rpcEndpoint());
        assertFalse(decoded.routableProvider());
        ((java.util.Map<String, Object>) nativeEntry.get(EurekaProtocol.FIELD_INSTANCE))
                .put(EurekaProtocol.FIELD_PORT, Map.of(
                        EurekaProtocol.FIELD_VALUE, 80,
                        EurekaProtocol.FIELD_ENABLED, EurekaProtocol.PORT_ENABLED));
        assertNull(identity.fromInstance(APP, MAPPER.valueToTree(nativeEntry)
                .path(EurekaProtocol.FIELD_INSTANCE)));
    }

    @Test
    void providerMustExposeRealHostEnabledPortAndCorrectApplicationMetadata() {
        EurekaV2Identity identity = new EurekaV2Identity(APP.namespace());
        ApplicationInstance provider = provider("p1");
        Map<String, Object> body = identity.nativeRegistration(provider,
                TOKEN, "127.0.0.1", 1, 9);
        JsonNode raw = MAPPER.valueToTree(body).path(EurekaProtocol.FIELD_INSTANCE);
        assertEquals(provider, identity.fromInstance(APP, raw));
        com.fasterxml.jackson.databind.node.ObjectNode invalid = raw.deepCopy();
        invalid.withObject(EurekaProtocol.FIELD_PORT).put(EurekaProtocol.FIELD_ENABLED, "false");
        assertNull(identity.fromInstance(APP, invalid));
        invalid = raw.deepCopy();
        invalid.put(EurekaProtocol.FIELD_STATUS, EurekaProtocol.STATUS_DOWN);
        assertNull(identity.fromInstance(APP, invalid));
        invalid = raw.deepCopy();
        invalid.withObject(EurekaProtocol.FIELD_METADATA).put("otryxV2Application", "forged");
        assertNull(identity.fromInstance(APP, invalid));
    }

    @Test
    void metadataRejectsUtf8OverflowBeforeIssuingAnyHttpRequest() {
        EurekaV2Identity identity = new EurekaV2Identity(APP.namespace());
        ApplicationInstance member = new ApplicationInstance(
                "consumer", APP, ApplicationRole.CONSUMER, null, 0, null,
                Map.of("region", "中".repeat(180)));
        assertThrows(IllegalArgumentException.class,
                () -> identity.nativeRegistration(member, TOKEN, "127.0.0.1", 1, 9));
    }

    @Test
    void factoryRequiresExternalTrustedMetadataStoreEvenWhenEurekaEndpointExists() {
        EurekaApplicationRegistryFactory factory = new EurekaApplicationRegistryFactory();
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(new RegistryOptions(List.of("http://127.0.0.1:8761/eureka"),
                        APP.namespace(), Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(new RegistryOptions(List.of("http://127.0.0.1:8761/eureka"),
                        APP.namespace(), Map.of("eurekaMetadataBackend", "eureka",
                                "eurekaMetadataEndpoints", "http://127.0.0.1:2379"))));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(new RegistryOptions(List.of("http://127.0.0.1:8761/eureka"),
                        APP.namespace(), Map.of("eurekaMetadataBackend", "etcd"))));
    }

    private static ApplicationInstance provider(String id) {
        return new ApplicationInstance(id, APP, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19091), 100,
                "sha256:" + "a".repeat(64), Map.of());
    }

    private static ApplicationInstance consumer(String id) {
        return new ApplicationInstance(id, APP, ApplicationRole.CONSUMER,
                null, 0, null, Map.of());
    }
}
