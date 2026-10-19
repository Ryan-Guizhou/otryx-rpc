package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.alibaba.nacos.api.PropertyKeyConst;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * 验证 Nacos Registry Factory 的扩展构建与参数校验。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:35
 */
class NacosApplicationRegistryFactoryTest {

    @Test
    void shouldLoadFactoryThroughSpi() {
        ApplicationRegistryFactory factory =
                ExtensionLoader.getLoader(
                                ApplicationRegistryFactory.class)
                        .getExtension("nacos");

        assertEquals(
                NacosApplicationRegistryFactory.class,
                factory.getClass());
    }

    @Test
    void shouldRejectEndpointEmbeddedCredentials() {
        RegistryOptions options = new RegistryOptions(
                List.of(
                        "user:secret@127.0.0.1:8848"),
                "public",
                Map.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new NacosApplicationRegistryFactory()
                        .create(options));

        assertTrue(
                !error.getMessage().contains("secret"));
        assertTrue(
                !options.toString().contains("secret"));
    }

    @Test
    void credentialsShouldOnlyEnterSdkProperties() {
        String secret = "NacosSecret@123";
        RegistryOptions options = new RegistryOptions(
                List.of("127.0.0.1:8848"),
                "public",
                Map.of(
                        "nacosUsername",
                        "otryx",
                        "nacosPassword",
                        secret));

        Properties properties =
                NacosApplicationRegistryFactory.clientProperties(
                        options,
                        options.endpoints(),
                        options.namespace());

        assertEquals(
                "127.0.0.1:8848",
                properties.getProperty(
                        PropertyKeyConst.SERVER_ADDR));
        assertEquals(
                "otryx",
                properties.getProperty(
                        PropertyKeyConst.USERNAME));
        assertEquals(
                secret,
                properties.getProperty(
                        PropertyKeyConst.PASSWORD));
        assertTrue(
                !properties.getProperty(
                                PropertyKeyConst.SERVER_ADDR)
                        .contains(secret));
        assertTrue(
                !options.toString().contains(secret));
    }

    @Test
    void shouldRejectInvalidClusterName() {
        RegistryOptions options = new RegistryOptions(
                List.of("127.0.0.1:8848"),
                "public",
                Map.of(
                        "nacosCluster",
                        "INVALID_CLUSTER"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new NacosApplicationRegistryFactory()
                        .create(options));
    }

}
