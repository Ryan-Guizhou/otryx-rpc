package com.peachsoft.otryx.registry.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.RegistryClientKeyMaterial;
import com.peachsoft.otryx.registry.RegistryCredentials;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySecurityOptions;
import com.peachsoft.otryx.registry.RegistryTlsMaterial;
import com.peachsoft.otryx.registry.RegistryTlsOptions;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 从独立真实 Etcd fixture 的私有文件读取角色凭据；测试不创建服务器也不回退明文。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:59
 */
final class EtcdSecurityTestFixture {
    static final String NAMESPACE = environment("ETCD_TLS_TEST_NAMESPACE");
    static final String ENDPOINT = environment("ETCD_TLS_TEST_ENDPOINT");
    static final ServiceKey SERVICE_A = new ServiceKey("test.SecurityA", "1", "default");
    static final ServiceKey SERVICE_B = new ServiceKey("test.SecurityB", "1", "default");
    static final String SCHEMA = "1".repeat(64);
    static final ApplicationKey PROVIDER = new ApplicationKey(NAMESPACE, "security-provider");
    static final ApplicationKey CONSUMER = new ApplicationKey(NAMESPACE, "security-consumer");
    private static final int MAX_CREDENTIAL_BYTES = 4096;

    private EtcdSecurityTestFixture() {
    }

    static ApplicationManifest manifest() {
        return ApplicationManifest.create(PROVIDER, List.of(
                exported(SERVICE_A), exported(SERVICE_B)));
    }

    static ExportedService exported(ServiceKey key) {
        return new ExportedService(key, RpcIds.serviceId(key), Set.of(1), SCHEMA);
    }

    static EtcdApplicationRegistry registry(String role) throws Exception {
        return registry(role, NAMESPACE, ENDPOINT, path("ETCD_TLS_TEST_CA_FILE"), false, false);
    }

    static EtcdApplicationRegistry registry(
            String role, String namespace, String endpoint, Path ca, boolean clientCertificate, boolean wrongPassword)
            throws Exception {
        return (EtcdApplicationRegistry) new EtcdApplicationRegistryFactory().create(
                options(role, namespace, endpoint, ca, clientCertificate, wrongPassword));
    }

    static RegistryOptions options(
            String role, String namespace, String endpoint, Path ca, boolean clientCertificate, boolean wrongPassword)
            throws Exception {
        RegistryCredentials credentials = credentials(role, wrongPassword);
        return new RegistryOptions(List.of(endpoint), namespace, Map.of("leaseTtlSeconds", "10"),
                RpcObserver.noop(), RpcSecurityProfile.NETWORK_MTLS,
                new RegistrySecurityOptions(Optional.of(tls(ca, clientCertificate)), credentials), Optional.empty());
    }

    static RegistryTlsOptions tls(Path ca, boolean clientCertificate) {
        Optional<RegistryClientKeyMaterial> identity = clientCertificate
                ? Optional.of(new RegistryClientKeyMaterial(path("ETCD_TLS_TEST_CLIENT_CERT_FILE"),
                        path("ETCD_TLS_TEST_CLIENT_KEY_FILE"), new char[0]))
                : Optional.empty();
        return new RegistryTlsOptions(ca, identity);
    }

    static Client administrator() throws Exception {
        RegistryCredentials credentials = credentials("admin", false);
        RegistryTlsMaterial material = RegistryTlsMaterial.load(tls(path("ETCD_TLS_TEST_CA_FILE"), true));
        var builder = Client.builder().endpoints(ENDPOINT).connectTimeout(Duration.ofSeconds(3));
        builder.sslContext(context -> {
            context.trustManager(material.trustManagerFactory());
            material.privateKey().ifPresent(key -> context.keyManager(key, material.certificateChain()));
        });
        char[] password = credentials.password();
        try {
            return builder.user(ByteSequence.from(credentials.username(), StandardCharsets.UTF_8))
                    .password(ByteSequence.from(new String(password), StandardCharsets.UTF_8)).build();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static String username(String role) throws Exception {
        return readCredential(role + ".username");
    }

    private static RegistryCredentials credentials(String role, boolean wrongPassword) throws Exception {
        char[] password = wrongPassword ? "intentionally-wrong-registry-password".toCharArray()
                : readCredential(role + ".password").toCharArray();
        try {
            return RegistryCredentials.userPassword(username(role), password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String readCredential(String name) throws Exception {
        Path source = path("ETCD_TLS_TEST_CREDENTIALS_DIR").resolve(name);
        try (InputStream input = Files.newInputStream(source)) {
            byte[] bytes = input.readNBytes(MAX_CREDENTIAL_BYTES + 1);
            try {
                if (bytes.length == 0 || bytes.length > MAX_CREDENTIAL_BYTES) {
                    throw new IllegalStateException("Invalid fixture credential file size");
                }
                return new String(bytes, StandardCharsets.UTF_8).strip();
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    static RegistrySecurityException rejection(RegistrySecurityFailure reason, CompletionStage<?> stage) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS));
        Throwable cause = failure.getCause();
        RegistrySecurityException security = assertInstanceOf(RegistrySecurityException.class, cause);
        assertEquals(reason, security.reason());
        assertNull(security.getCause());
        assertEquals(0, security.getSuppressed().length);
        return security;
    }

    static Path path(String key) {
        return Path.of(environment(key));
    }

    static String environment(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "Required protected Etcd fixture variable is missing: " + key);
        if (value.isBlank()) {
            throw new IllegalStateException("Required protected Etcd fixture variable is blank: " + key);
        }
        return value;
    }
}
