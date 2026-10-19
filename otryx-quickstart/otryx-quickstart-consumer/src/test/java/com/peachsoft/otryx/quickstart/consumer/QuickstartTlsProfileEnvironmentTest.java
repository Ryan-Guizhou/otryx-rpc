package com.peachsoft.otryx.quickstart.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.codec.fory.ForyRpcSecurityOptions;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.quickstart.provider.ProviderApplication;
import com.peachsoft.otryx.spring.autoconfigure.OtryxRpcAutoConfiguration;
import com.peachsoft.otryx.spring.autoconfigure.OtryxRpcProperties;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

/**
 * 验证两个示例模块的真实 TLS/mTLS Profile 使用环境变量时可通过 Starter 严格绑定。
 *
 * <p>从应用类所在产物读取资源，避免同名 classpath 资源相互遮蔽；仅执行实际配置
 * 校验器与属性绑定，不启动 Registry、TLS 连接或授权器。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 02:40
 */
class QuickstartTlsProfileEnvironmentTest {

    private static final String CERTIFICATE_PATH = "/tmp/otryx-quickstart-test-certificate.pem";
    private static final String PRIVATE_KEY_PATH = "/tmp/otryx-quickstart-test-private-key.pem";
    private static final String TRUST_CERTIFICATE_PATH = "/tmp/otryx-quickstart-test-ca.pem";

    @ParameterizedTest(name = "{0} {1} resolves certificate environment variables")
    @MethodSource("profiles")
    void quickstartCertificateAliasesBindRealProfile(Class<?> application, String profile) throws Exception {
        StandardEnvironment environment = environment(application, profile, certificateEnvironment());
        validateConfiguration(environment);
        OtryxRpcProperties properties = Binder.get(environment)
                .bind("otryx.rpc", Bindable.of(OtryxRpcProperties.class)).get();
        OtryxRpcProperties.Security security = properties.getTransport().getSecurity();
        boolean mutualTls = "mtls".equals(profile);
        boolean hasIdentity = mutualTls || application == ProviderApplication.class;
        boolean hasTrust = mutualTls || application == ConsumerApplication.class;

        assertEquals(mutualTls ? RpcSecurityMode.MTLS : RpcSecurityMode.TLS, security.getMode());
        assertEquals(hasIdentity ? CERTIFICATE_PATH : "", security.getCertificatePath());
        assertEquals(hasIdentity ? PRIVATE_KEY_PATH : "", security.getPrivateKeyPath());
        assertEquals(hasTrust ? TRUST_CERTIFICATE_PATH : "", security.getTrustCertificatePath());
        assertTrue(security.isHostnameVerification());
        if (mutualTls) {
            assertEquals(OtryxRpcProperties.SecurityProfile.NETWORK_MTLS, properties.getSecurityProfile());
            assertEquals(ForyRpcSecurityOptions.Mode.STRICT_ALLOWLIST, properties.getCodec().getFory().getMode());
            assertEquals(Set.of("com.peachsoft.otryx.quickstart.api.*"),
                    properties.getCodec().getFory().getAllowedClassPatterns());
        }
    }

    @ParameterizedTest(name = "{0} {1} rejects unsupported Starter certificate aliases")
    @MethodSource("profiles")
    void legacyCertificateAliasesRemainUnsupported(Class<?> application, String profile) throws Exception {
        Map<String, Object> variables = new HashMap<>(certificateEnvironment());
        variables.put("OTRYX_RPC_TLS_CERT", CERTIFICATE_PATH);
        variables.put("OTRYX_RPC_TLS_KEY", PRIVATE_KEY_PATH);
        variables.put("OTRYX_RPC_TLS_CA", TRUST_CERTIFICATE_PATH);
        StandardEnvironment environment = environment(application, profile, variables);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> validateConfiguration(environment));
        assertTrue(failure.getMessage().contains("Unsupported OTRYX RPC configuration keys:"));
        assertTrue(failure.getMessage().contains("otryx.rpc.tls.cert"));
        assertTrue(failure.getMessage().contains("otryx.rpc.tls.key"));
        assertTrue(failure.getMessage().contains("otryx.rpc.tls.ca"));
    }

    private static Stream<Arguments> profiles() {
        return Stream.of(
                Arguments.of(ProviderApplication.class, "tls"),
                Arguments.of(ConsumerApplication.class, "tls"),
                Arguments.of(ProviderApplication.class, "mtls"),
                Arguments.of(ConsumerApplication.class, "mtls"));
    }

    private static Map<String, Object> certificateEnvironment() {
        return Map.of(
                "OTRYX_QUICKSTART_TLS_CERT", CERTIFICATE_PATH,
                "OTRYX_QUICKSTART_TLS_KEY", PRIVATE_KEY_PATH,
                "OTRYX_QUICKSTART_TLS_CA", TRUST_CERTIFICATE_PATH);
    }

    private static StandardEnvironment environment(Class<?> application, String profile,
            Map<String, Object> variables) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        URL location = application.getProtectionDomain().getCodeSource().getLocation();
        Path artifact = Path.of(location.toURI());
        String resourceName = "application-" + profile + ".yml";
        Resource resource = Files.isDirectory(artifact)
                ? new FileSystemResource(artifact.resolve(resourceName))
                : new UrlResource("jar:" + location.toExternalForm() + "!/" + resourceName);
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                application.getSimpleName() + "-" + profile, resource)) {
            environment.getPropertySources().addLast(source);
        }
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    private static void validateConfiguration(StandardEnvironment environment) {
        OtryxRpcAutoConfiguration.peachRpcApplicationConfigurationValidator(environment)
                .postProcessBeanFactory(new DefaultListableBeanFactory());
    }
}
