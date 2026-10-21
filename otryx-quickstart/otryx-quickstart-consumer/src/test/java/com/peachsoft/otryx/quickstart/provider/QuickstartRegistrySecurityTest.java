package com.peachsoft.otryx.quickstart.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationDiscovery;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.ChannelProtection;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySourceProtection;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证观察者的私有输入边界、实际来源预检和零写入，不把受控描述当作真实 TLS 证据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:54
 */
class QuickstartRegistrySecurityTest {
    private static final String NAMESPACE = "observer-security";
    private static final String SECRET = "synthetic-observer-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    private Path directory;

    @Test
    void privateUserPasswordFileSelectsNetworkProfileAndKeepsMaterialReferencesSeparate() throws Exception {
        Map<String, String> security = userPassword("observer", SECRET);
        Path file = configuration(Map.of("security", security));
        RegistryOptions options = parse("etcd", file.toString());
        assertEquals(RpcSecurityProfile.NETWORK_MTLS, options.securityProfile());
        assertEquals(CredentialKind.USER_PASSWORD, options.security().credentials().kind());
        assertEquals("observer", options.security().credentials().username());
        assertArrayEquals(SECRET.toCharArray(), options.security().credentials().password());
        assertEquals(directory.resolve("trust.pem"),
                options.security().tls().orElseThrow().trustCertificateCollection());
        assertTrue(options.metadataSecurity().isEmpty());
        assertFalse(options.toString().contains(SECRET));
        assertFalse(options.toString().contains("trust.pem"));
    }

    @Test
    void eurekaRequiresIndependentMetadataSecurityWithoutInheritingMembershipCaOrCredentials() throws Exception {
        Map<String, String> membership = userPassword("member-reader", "membership-secret");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("trustCertificateCollection", "metadata-trust.pem");
        metadata.put("credentialKind", "ACL_TOKEN");
        metadata.put("tokenFile", privateFile("metadata-token", "metadata-secret\n").getFileName().toString());
        Path file = configuration(Map.of("security", membership, "metadataSecurity", metadata));
        RegistryOptions options = parse("eureka", file.toString());
        assertEquals("member-reader", options.security().credentials().username());
        var metadataOptions = options.metadataSecurity().orElseThrow();
        assertEquals(directory.resolve("metadata-trust.pem"),
                metadataOptions.tls().orElseThrow().trustCertificateCollection());
        assertEquals(CredentialKind.ACL_TOKEN, metadataOptions.credentials().kind());
        assertArrayEquals("metadata-secret".toCharArray(), metadataOptions.credentials().token());
        assertRejected(() -> parse("etcd", file.toString()));
        Path missing = configuration(Map.of("security", membership));
        assertRejected(() -> parse("eureka", missing.toString()));
    }

    @Test
    void aclTokenAndExplicitClientCertificateKindsUseOnlyTheirOwnFields() throws Exception {
        Path token = privateFile("token", SECRET);
        Map<String, String> security = new LinkedHashMap<>(Map.of("trustCertificateCollection", "trust.pem",
                "credentialKind", "ACL_TOKEN", "tokenFile", token.getFileName().toString()));
        RegistryOptions tokenOptions = parse("consul", configuration(Map.of("security", security)).toString());
        assertArrayEquals(SECRET.toCharArray(), tokenOptions.security().credentials().token());
        security.remove("tokenFile");
        security.put("credentialKind", "CLIENT_CERTIFICATE");
        security.put("clientCertificateChain", "client.pem");
        security.put("clientPrivateKey", "client-key.pem");
        RegistryOptions certificateOptions = parse("etcd", configuration(Map.of("security", security)).toString());
        assertEquals(CredentialKind.CLIENT_CERTIFICATE, certificateOptions.security().credentials().kind());
        assertEquals(directory.resolve("client-key.pem"), certificateOptions.security().tls().orElseThrow()
                .clientIdentity().orElseThrow().privateKey());
        security.put("tokenFile", token.toString());
        assertRejected(() -> parse("etcd", configuration(Map.of("security", security)).toString()));
        security.remove("tokenFile");
        security.remove("clientPrivateKey");
        assertRejected(() -> parse("etcd", configuration(Map.of("security", security)).toString()));
    }

    @Test
    void presentButBlankOrMissingSecurityFileNeverFallsBackToDevelopment() {
        assertRejected(() -> parse("etcd", " "));
        assertRejected(() -> parse("etcd", directory.resolve(SECRET).toString()));
    }

    @Test
    void securityFileMustBePrivateRegularAndCannotBeAFinalSymlink() throws Exception {
        Path file = configuration(Map.of("security", userPassword("observer", SECRET)));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertRejected(() -> parse("etcd", file.toString()));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        assertRejected(() -> parse("etcd", file.toString()));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Path link = directory.resolve("config-link");
        Files.createSymbolicLink(link, file);
        assertRejected(() -> parse("etcd", link.toString()));
        assertRejected(() -> parse("etcd", directory.toString()));
    }

    @Test
    void unknownDuplicateTrailingNullDeepAndOversizedJsonAreRejectedWithoutInputDisclosure() throws Exception {
        String valid = JSON.writeValueAsString(Map.of("security", userPassword("observer", SECRET)));
        List<String> invalid = new ArrayList<>(List.of(
                "null", "[]", "{}", "{\"security\":null}",
                "{\"security\":{\"credentialKind\":null}}",
                "{\"security\":{\"unknown\":\"" + SECRET + "\"}}",
                "{\"security\":{},\"security\":{}}",
                "{\"security\":{\"credentialKind\":\"USER_PASSWORD\",\"credentialKind\":\"ACL_TOKEN\"}}",
                valid + " {}", valid.substring(0, valid.length() - 1) + ",\"trustAll\":true}",
                "{\"security\":{\"trustCertificateCollection\":\"" + "x".repeat(4097) + "\"}}",
                "{\"security\":{\"trustCertificateCollection\":" + "[".repeat(9) + "0" + "]".repeat(9) + "}}",
                "{\"security\":{\"credentialKind\":\"" + SECRET + "\ninvalid\"}}"));
        invalid.add(" ".repeat(64 * 1024 + 1));
        for (int i = 0; i < invalid.size(); i++) {
            Path file = privateFile("invalid-json-" + i, invalid.get(i));
            assertRejected(() -> parse("etcd", file.toString()));
        }
    }

    @Test
    void missingContradictoryAndInlineCredentialFieldsAreRejected() throws Exception {
        Map<String, String> valid = userPassword("observer", SECRET);
        List<Map<String, String>> invalid = new ArrayList<>();
        for (String required : List.of("usernameFile", "passwordFile", "trustCertificateCollection")) {
            Map<String, String> value = new LinkedHashMap<>(valid);
            value.remove(required);
            invalid.add(value);
        }
        for (String forbidden : List.of("password", "tokenFile", "class", "clientCertificateChain")) {
            Map<String, String> value = new LinkedHashMap<>(valid);
            value.put(forbidden, SECRET);
            invalid.add(value);
        }
        Map<String, String> none = new LinkedHashMap<>(valid);
        none.put("credentialKind", "NONE");
        invalid.add(none);
        for (Map<String, String> value : invalid) {
            Path file = configuration(Map.of("security", value));
            assertRejected(() -> parse("etcd", file.toString()));
        }
    }

    @Test
    void secretFilesRejectMalformedUtf8MultipleLinesOversizeAndUnsafePermissions() throws Exception {
        Map<String, String> security = userPassword("observer", SECRET);
        Path password = directory.resolve(security.get("passwordFile"));
        Path file = configuration(Map.of("security", security));
        for (byte[] invalid : List.of(new byte[0], new byte[] {(byte) 0xc3, 0x28},
                (SECRET + "\nsecond-line").getBytes(StandardCharsets.UTF_8),
                (SECRET + "\0").getBytes(StandardCharsets.UTF_8), new byte[4097])) {
            Files.write(password, invalid);
            assertRejected(() -> parse("etcd", file.toString()));
        }
        Files.writeString(password, SECRET);
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-r-----"));
        assertRejected(() -> parse("etcd", file.toString()));
        Path privatePassword = privateFile("private-password", SECRET);
        Files.delete(password);
        Files.createSymbolicLink(password, privatePassword);
        assertRejected(() -> parse("etcd", file.toString()));
    }

    @Test
    void allRealChannelsAndEveryMixedSourceMustPassBeforeAnyReadOrWrite() {
        ChannelProtection strong = protection(RegistrySourceProtection.AUTHENTICATED_TLS,
                Set.of(CredentialKind.USER_PASSWORD), NAMESPACE);
        List<ChannelProtection> weak = List.of(
                protection(RegistrySourceProtection.PLAINTEXT, Set.of(CredentialKind.USER_PASSWORD), NAMESPACE),
                protection(RegistrySourceProtection.UNKNOWN, Set.of(CredentialKind.USER_PASSWORD), NAMESPACE),
                protection(RegistrySourceProtection.LOCAL_PROCESS, Set.of(), NAMESPACE),
                protection(RegistrySourceProtection.AUTHENTICATED_TLS, Set.of(), NAMESPACE),
                protection(RegistrySourceProtection.AUTHENTICATED_TLS, Set.of(CredentialKind.NONE), NAMESPACE),
                protection(RegistrySourceProtection.AUTHENTICATED_TLS,
                        Set.of(CredentialKind.USER_PASSWORD), "foreign"));
        for (RegistryChannel channel : RegistryChannel.values()) {
            for (ChannelProtection unprotected : weak) {
                Map<RegistryChannel, List<ChannelProtection>> channels = completeChannels(strong);
                channels.put(channel, List.of(strong, unprotected));
                RecordingSources sources = new RecordingSources(new RegistrySecurityDescriptor(channels));
                assertRejected(() -> networkCapture(sources));
                sources.assertUntouched();
            }
            for (boolean absent : new boolean[] {false, true}) {
                Map<RegistryChannel, List<ChannelProtection>> channels = completeChannels(strong);
                if (absent) {
                    channels.remove(channel);
                } else {
                    channels.put(channel, List.of());
                }
                RecordingSources sources = new RecordingSources(new RegistrySecurityDescriptor(channels));
                assertRejected(() -> networkCapture(sources));
                sources.assertUntouched();
            }
        }
    }

    @Test
    void descriptorFailuresRemainSanitizedBeforeAnyIo() {
        RecordingSources missing = new RecordingSources(null);
        assertRejected(() -> networkCapture(missing));
        missing.assertUntouched();
        RecordingSources failing = new RecordingSources(null) {
            @Override
            public RegistrySecurityDescriptor securityDescriptor() {
                throw new IllegalStateException(SECRET);
            }
        };
        assertRejected(() -> networkCapture(failing));
        failing.assertUntouched();
    }

    @Test
    void validActualSourcesRemainReadOnlyAndDoNotCreateMembersOrMappingOwners() {
        ChannelProtection strong = protection(RegistrySourceProtection.AUTHENTICATED_TLS,
                Set.of(CredentialKind.USER_PASSWORD), NAMESPACE);
        RecordingSources sources = new RecordingSources(new RegistrySecurityDescriptor(completeChannels(strong)));
        var snapshot = networkCapture(sources);
        assertEquals(NAMESPACE, snapshot.applicationNamespace());
        assertEquals(1, snapshot.applications().size());
        assertTrue(sources.reads.get() > 0);
        assertEquals(0, sources.writes.get());
        assertEquals(0, sources.subscriptions.get());
    }

    @Test
    void auditedFinalMemorySourcesCanSatisfyNetworkPreflightWithoutRemoteClaims() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            var snapshot = QuickstartRegistrySnapshot.capture(registry, registry, NAMESPACE,
                    List.of("observer-target"), Duration.ofSeconds(2), RpcSecurityProfile.NETWORK_MTLS);
            assertEquals(1, snapshot.applications().size());
            assertTrue(snapshot.applications().getFirst().members().isEmpty());
            assertTrue(registry.lookup(new ApplicationKey(NAMESPACE, "observer-target"))
                    .toCompletableFuture().join().instances().isEmpty());
        }
    }

    private RegistryOptions parse(String backend, String securityFile) throws Exception {
        return QuickstartRegistrySnapshot.parseArguments(new String[] {
                backend, "https://127.0.0.1:2379", NAMESPACE, "nacos".equals(backend) ? "OBSERVER" : "-",
                "eureka".equals(backend) ? "https://127.0.0.1:8500" : "-",
                directory.resolve("snapshot.json").toString(), "observer-target"}, securityFile).options();
    }

    private Map<String, String> userPassword(String username, String password) throws Exception {
        Path user = privateFile("username", username + "\n");
        Path pass = privateFile("password", password + "\r\n");
        return new LinkedHashMap<>(Map.of("trustCertificateCollection", "trust.pem",
                "credentialKind", "USER_PASSWORD", "usernameFile", user.getFileName().toString(),
                "passwordFile", pass.getFileName().toString()));
    }

    private Path configuration(Map<String, ?> value) throws Exception {
        return privateFile("security-" + System.nanoTime() + ".json", JSON.writeValueAsString(value));
    }

    private Path privateFile(String name, String value) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, value);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private static void assertRejected(org.junit.jupiter.api.function.Executable action) {
        RegistrySecurityException failure = assertThrows(RegistrySecurityException.class, action);
        assertEquals(RegistrySecurityFailure.UNSUPPORTED_PROTECTION, failure.reason());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        failure.addSuppressed(new IllegalStateException(SECRET));
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(SECRET));
    }

    private static QuickstartRegistrySnapshot.Snapshot networkCapture(RecordingSources sources) {
        return QuickstartRegistrySnapshot.capture(sources, sources, NAMESPACE, List.of("observer-target"),
                Duration.ofSeconds(2), RpcSecurityProfile.NETWORK_MTLS);
    }

    private static ChannelProtection protection(RegistrySourceProtection protection,
            Set<CredentialKind> credentials, String namespace) {
        return new ChannelProtection(protection, credentials, Optional.of(namespace), Optional.empty());
    }

    private static Map<RegistryChannel, List<ChannelProtection>> completeChannels(ChannelProtection source) {
        Map<RegistryChannel, List<ChannelProtection>> channels = new EnumMap<>(RegistryChannel.class);
        for (RegistryChannel channel : RegistryChannel.values()) {
            channels.put(channel, List.of(source));
        }
        return channels;
    }

    private static class RecordingSources implements ApplicationDiscovery, ApplicationMetadataBackend,
            ApplicationRegistrar {
        private final RegistrySecurityDescriptor descriptor;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger subscriptions = new AtomicInteger();

        private RecordingSources(RegistrySecurityDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public RegistrySecurityDescriptor securityDescriptor() {
            return descriptor;
        }

        @Override
        public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
            reads.incrementAndGet();
            return CompletableFuture.completedFuture(new ApplicationDirectorySnapshot(application, List.of(), 1));
        }

        @Override
        public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
            subscriptions.incrementAndGet();
            throw new AssertionError("Observer must not subscribe");
        }

        @Override
        public CompletionStage<Void> register(ApplicationInstance instance) {
            return forbiddenWrite();
        }

        @Override
        public CompletionStage<Void> unregister(ApplicationInstance instance) {
            return forbiddenWrite();
        }

        @Override
        public ApplicationManifestRepository manifestRepository() {
            return new ApplicationManifestRepository() {
                @Override
                public RegistrySecurityDescriptor securityDescriptor() {
                    return descriptor;
                }

                @Override
                public CompletionStage<Void> publish(ApplicationManifest manifest) {
                    return forbiddenWrite();
                }

                @Override
                public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey app, String revision) {
                    reads.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.empty());
                }
            };
        }

        @Override
        public ServiceMappingRepository serviceMappingRepository() {
            return new ServiceMappingRepository() {
                @Override
                public RegistrySecurityDescriptor securityDescriptor() {
                    return descriptor;
                }

                @Override
                public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
                    return forbiddenWrite();
                }

                @Override
                public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
                    return forbiddenWrite();
                }

                @Override
                public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
                    return forbiddenWrite();
                }

                @Override
                public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
                    reads.incrementAndGet();
                    return CompletableFuture.completedFuture(new ServiceMappingSnapshot(key, Set.of(), 1));
                }
            };
        }

        private <T> CompletionStage<T> forbiddenWrite() {
            writes.incrementAndGet();
            throw new AssertionError("Observer must never register, unregister, claim, publish, or revoke");
        }

        private void assertUntouched() {
            assertEquals(0, reads.get());
            assertEquals(0, writes.get());
            assertEquals(0, subscriptions.get());
        }
    }
}
