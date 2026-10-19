package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证离线初始化命令要求显式目标，且非法参数在连接 Nacos 前失败。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 02:18
 */
class NacosV2MetadataBootstrapTest {

    @Test
    void commandLineShouldPreserveExplicitEndpointsAndScope() {
        RegistryOptions options = NacosV2MetadataBootstrap.commandLineOptions(new String[]{
                "127.0.0.1:8848, localhost:8858", " isolated-namespace ",
                " OTRYX_BOOTSTRAP_TEST ", "TRUSTED_DEVELOPMENT"});
        assertEquals(List.of("127.0.0.1:8848", "localhost:8858"), options.endpoints());
        assertEquals("isolated-namespace", options.namespace());
        assertEquals(Map.of("nacosGroup", "OTRYX_BOOTSTRAP_TEST"), options.providerOptions());
        assertEquals(RpcSecurityProfile.TRUSTED_DEVELOPMENT, options.securityProfile());
    }

    @Test
    void missingArgumentsMustNotSelectDefaultScope() {
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2MetadataBootstrap.commandLineOptions(null));
        for (String[] args : List.of(new String[]{}, new String[]{"127.0.0.1:8848"},
                new String[]{"127.0.0.1:8848", "public"},
                new String[]{"127.0.0.1:8848", "public", "OTRYX_TEST"})) {
            assertThrows(IllegalArgumentException.class,
                    () -> NacosV2MetadataBootstrap.commandLineOptions(args));
        }
    }

    @Test
    void extraArgumentsMustBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> NacosV2MetadataBootstrap.commandLineOptions(
                new String[]{"127.0.0.1:8848", "public", "OTRYX_TEST", "TRUSTED_DEVELOPMENT", "unexpected"}));
    }

    @Test
    void blankArgumentsMustNotSelectDefaultScope() {
        String[] valid = {"127.0.0.1:8848", "public", "OTRYX_TEST", "TRUSTED_DEVELOPMENT"};
        for (int index = 0; index < valid.length; index++) {
            for (String blank : new String[]{null, "", " \t "}) {
                String[] args = valid.clone();
                args[index] = blank;
                assertThrows(IllegalArgumentException.class,
                        () -> NacosV2MetadataBootstrap.commandLineOptions(args));
            }
        }
    }

    @Test
    void emptyParsedEndpointsMustNotFallBackToLocalhost() {
        assertThrows(IllegalArgumentException.class, () -> NacosV2MetadataBootstrap.commandLineOptions(
                new String[]{", ,", "public", "OTRYX_TEST", "TRUSTED_DEVELOPMENT"}));
    }

    @Test
    void invalidGroupMustBeRejectedBeforeConnecting() {
        for (String group : List.of("bad/group", "bad group", "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> NacosV2MetadataBootstrap.commandLineOptions(
                    new String[]{"127.0.0.1:8848", "public", group, "TRUSTED_DEVELOPMENT"}));
        }
    }

    @Test
    void mainMustRejectCredentialEndpointsWithoutExposingInput() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NacosV2MetadataBootstrap.main(new String[]{
                        "user:secret@127.0.0.1:8848", "public", "OTRYX_TEST", "TRUSTED_DEVELOPMENT"}));
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test
    void mainMustValidateArgumentsBeforeInitializing() {
        assertThrows(IllegalArgumentException.class, () -> NacosV2MetadataBootstrap.main(new String[]{}));
    }
    @Test
    void unsupportedSecurityProfileMustNotInitializeAnySdkClient() {
        for (String profile : List.of("NETWORK_MTLS", "UNSPECIFIED", "trusted-development")) {
            RegistrySecurityException failure = assertThrows(RegistrySecurityException.class,
                    () -> NacosV2MetadataBootstrap.commandLineOptions(new String[]{
                            "127.0.0.1:8848", "public", "OTRYX_TEST", profile}));
            assertEquals(RegistrySecurityFailure.UNSUPPORTED_PROTECTION, failure.reason());
        }
    }

}
