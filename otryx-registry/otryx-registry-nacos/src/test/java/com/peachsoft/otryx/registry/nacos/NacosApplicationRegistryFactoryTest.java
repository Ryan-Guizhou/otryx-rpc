package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryCredentials;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySecurityOptions;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        RegistryOptions options = RegistryOptions.trustedDevelopment(
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
        RegistryOptions options = new RegistryOptions(List.of("127.0.0.1:8848"), "public", Map.of(),
                RpcObserver.noop(), RpcSecurityProfile.TRUSTED_DEVELOPMENT,
                new RegistrySecurityOptions(Optional.empty(),
                        RegistryCredentials.userPassword("otryx", secret.toCharArray())), Optional.empty());

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
        RegistryOptions options = RegistryOptions.trustedDevelopment(
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

    @Test
    void unspecifiedAndNetworkProfilesRejectBeforeSdkPropertiesOrBootstrap() {
        for (RpcSecurityProfile profile : List.of(
                RpcSecurityProfile.UNSPECIFIED, RpcSecurityProfile.NETWORK_MTLS)) {
            RegistryOptions options = new RegistryOptions(List.of("127.0.0.1:8848"), "public", Map.of(),
                    RpcObserver.noop(), profile, RegistrySecurityOptions.none(), Optional.empty());
            assertEquals(RegistrySecurityFailure.UNSUPPORTED_PROTECTION,
                    assertThrows(RegistrySecurityException.class,
                            () -> new NacosApplicationRegistryFactory().create(options)).reason());
            assertEquals(RegistrySecurityFailure.UNSUPPORTED_PROTECTION,
                    assertThrows(RegistrySecurityException.class,
                            () -> NacosApplicationRegistryFactory.clientProperties(
                                    options, options.endpoints(), options.namespace())).reason());
            assertEquals(RegistrySecurityFailure.UNSUPPORTED_PROTECTION,
                    assertThrows(RegistrySecurityException.class,
                            () -> NacosV2MetadataBootstrap.initialize(options)).reason());
        }
    }

    @Test
    void legacyCredentialAndUnknownTlsKeysCannotEnterSdkProperties() {
        for (String key : List.of("nacosPassword", "nacosUsername", "tlsTrustAll", "unknown")) {
            RegistryOptions options = RegistryOptions.trustedDevelopment(
                    List.of("127.0.0.1:8848"), "public", Map.of(key, "must-not-escape"));
            RegistrySecurityException failure = assertThrows(RegistrySecurityException.class,
                    () -> NacosApplicationRegistryFactory.clientProperties(
                            options, options.endpoints(), options.namespace()));
            assertEquals(RegistrySecurityFailure.UNSUPPORTED_PROTECTION, failure.reason());
            assertTrue(!failure.toString().contains("must-not-escape"));
        }
    }

}
