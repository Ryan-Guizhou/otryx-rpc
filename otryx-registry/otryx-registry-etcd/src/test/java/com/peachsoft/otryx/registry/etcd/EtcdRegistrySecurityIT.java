package com.peachsoft.otryx.registry.etcd;

import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.CONSUMER;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.ENDPOINT;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.NAMESPACE;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.PROVIDER;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.SCHEMA;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.SERVICE_A;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.SERVICE_B;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.await;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.path;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.registry;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.rejection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistryCredentials;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySecurityOptions;
import com.peachsoft.otryx.registry.RegistrySourceProtection;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.VerifiedProviderSnapshot;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.options.GetOption;
import io.vertx.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 通过独立 Etcd 3.5.21 TLS/RBAC fixture 验证真实 Factory、全角色权限和缓存安全失效。
 *
 * <p>此 IT 只由显式安全验证任务运行；缺少 fixture 变量直接失败，不使用条件跳过。
 * 管理身份仅用于测试设置、原始状态核验及权限撤销，不传给运行时 Registry。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:59
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EtcdRegistrySecurityIT {
    private static final ServiceMappingKey KEY_A = new ServiceMappingKey(NAMESPACE, SERVICE_A);
    private static final ServiceMappingKey KEY_B = new ServiceMappingKey(NAMESPACE, SERVICE_B);

    @Test
    void providerConsumerAndObserverFollowActualRolePathsAndEventLoopRegistration() throws Exception {
        ApplicationManifest manifest = EtcdSecurityTestFixture.manifest();
        ApplicationInstance provider = provider(manifest);
        ApplicationInstance consumer = new ApplicationInstance(
                "consumer-main", CONSUMER, ApplicationRole.CONSUMER, null, 0, null, Map.of());
        Vertx vertx = Vertx.vertx();
        try (EtcdApplicationRegistry publishing = registry("provider");
                EtcdApplicationRegistry consuming = registry("consumer");
                Client administrator = EtcdSecurityTestFixture.administrator()) {
            await(publishing.manifestRepository().publish(manifest));
            var owner = await(publishing.serviceMappingRepository().claim(PROVIDER, provider.instanceId()));
            await(publishing.serviceMappingRepository().publish(owner, Set.of(SERVICE_A, SERVICE_B)));
            CompletableFuture<Void> registered = new CompletableFuture<>();
            vertx.runOnContext(ignored -> publishing.register(provider).whenComplete((value, failure) -> {
                if (failure == null) {
                    registered.complete(null);
                } else {
                    registered.completeExceptionally(failure);
                }
            }));
            await(registered);
            await(consuming.register(consumer));
            try {
                var before = await(administrator.getKVClient().get(
                        bytes(new EtcdV2Paths(NAMESPACE).ownerPrefix()),
                        GetOption.builder().isPrefix(true).build())).getKvs().stream()
                        .map(entry -> Map.entry(entry.getKey(), entry.getModRevision())).toList();
                try (EtcdApplicationRegistry observing = registry("observer");
                        ManifestSnapshotCache manifests = new ManifestSnapshotCache(observing.manifestRepository());
                        ServiceMappingCache mappings = new ServiceMappingCache(
                                observing.serviceMappingRepository(), Duration.ofSeconds(10), Duration.ZERO);
                        ApplicationServiceResolver resolver = new ApplicationServiceResolver(
                                mappings, observing, manifests, Duration.ofSeconds(10))) {
                    assertEquals(List.of(provider), await(observing.lookup(PROVIDER)).instances());
                    assertEquals(List.of(consumer), await(observing.lookup(CONSUMER)).instances());
                    assertEquals(manifest, await(observing.manifestRepository()
                            .lookup(PROVIDER, manifest.revision())).orElseThrow());
                    assertEquals(Set.of(PROVIDER), await(observing.serviceMappingRepository()
                            .lookup(KEY_A)).applications());
                    VerifiedProviderSnapshot candidate = await(resolver.refresh(KEY_A, SCHEMA));
                    assertTrue(resolver.isSnapshotUsable(KEY_A, SCHEMA, candidate));
                    assertEquals(List.of(provider), candidate.providers());
                    for (RegistryChannel channel : RegistryChannel.values()) {
                        var protection = observing.securityDescriptor().channels().get(channel).getFirst();
                        assertEquals(RegistrySourceProtection.AUTHENTICATED_TLS, protection.protection());
                        assertEquals(Set.of(CredentialKind.USER_PASSWORD), protection.credentials());
                        assertEquals(Optional.of(NAMESPACE), protection.namespace());
                    }
                    assertEquals(before, await(administrator.getKVClient().get(
                            bytes(new EtcdV2Paths(NAMESPACE).ownerPrefix()),
                            GetOption.builder().isPrefix(true).build())).getKvs().stream()
                                    .map(entry -> Map.entry(entry.getKey(), entry.getModRevision())).toList(),
                            "observer reads cannot publish or claim an owner");
                }
            } finally {
                await(publishing.serviceMappingRepository().revoke(owner));
                await(publishing.unregister(provider));
                await(consuming.unregister(consumer));
            }
            assertTrue(await(publishing.lookup(PROVIDER)).instances().isEmpty());
            assertTrue(await(consuming.lookup(CONSUMER)).instances().isEmpty());
        } finally {
            await(vertx.close().toCompletionStage());
        }
    }

    @Test
    void wrongCaRejectsMembershipAndManifestWithoutHealthyEmptyFallback() throws Exception {
        try (EtcdApplicationRegistry rejected = registry("observer", NAMESPACE, ENDPOINT,
                path("ETCD_TLS_TEST_WRONG_CA_FILE"), false, false)) {
            rejection(RegistrySecurityFailure.TLS_REJECTED, rejected.lookup(PROVIDER));
            rejection(RegistrySecurityFailure.TLS_REJECTED,
                    rejected.manifestRepository().lookup(PROVIDER, EtcdSecurityTestFixture.manifest().revision()));
        }
    }

    @Test
    void wrongHostnameFailsOnTheSameRealTlsListener() throws Exception {
        try (EtcdApplicationRegistry rejected = registry("observer", NAMESPACE,
                EtcdSecurityTestFixture.environment("ETCD_TLS_TEST_WRONG_HOST_ENDPOINT"),
                path("ETCD_TLS_TEST_CA_FILE"), false, false)) {
            rejection(RegistrySecurityFailure.TLS_REJECTED, rejected.lookup(PROVIDER));
        }
    }

    @Test
    void wrongPasswordRemainsAuthenticationFailureAcrossAllReadChannels() throws Exception {
        try (EtcdApplicationRegistry rejected = registry("observer", NAMESPACE, ENDPOINT,
                path("ETCD_TLS_TEST_CA_FILE"), false, true)) {
            rejection(RegistrySecurityFailure.AUTHENTICATION_REJECTED, rejected.lookup(PROVIDER));
            rejection(RegistrySecurityFailure.AUTHENTICATION_REJECTED,
                    rejected.manifestRepository().lookup(PROVIDER, EtcdSecurityTestFixture.manifest().revision()));
            rejection(RegistrySecurityFailure.AUTHENTICATION_REJECTED,
                    rejected.serviceMappingRepository().lookup(KEY_A));
        }
    }

    @Test
    void networkRequiresExplicitTlsAndCredentialsBeforeCreatingSdk() {
        var withoutCredentials = new RegistryOptions(List.of(ENDPOINT), NAMESPACE, Map.of(), RpcObserver.noop(),
                RpcSecurityProfile.NETWORK_MTLS,
                new RegistrySecurityOptions(Optional.of(
                        EtcdSecurityTestFixture.tls(path("ETCD_TLS_TEST_CA_FILE"), false)), RegistryCredentials.none()),
                Optional.empty());
        RegistrySecurityException rejected = assertThrows(RegistrySecurityException.class,
                () -> new EtcdApplicationRegistryFactory().create(withoutCredentials));
        assertEquals(RegistrySecurityFailure.UNSUPPORTED_PROTECTION, rejected.reason());
        RegistryOptions withoutTls = new RegistryOptions(
                List.of(ENDPOINT), NAMESPACE, Map.of(), RpcObserver.noop(), RpcSecurityProfile.NETWORK_MTLS,
                RegistrySecurityOptions.none(), Optional.empty());
        assertEquals(RegistrySecurityFailure.UNSUPPORTED_PROTECTION,
                assertThrows(RegistrySecurityException.class,
                        () -> new EtcdApplicationRegistryFactory().create(withoutTls)).reason());
    }

    @Test
    void providerCannotWriteAnotherInstanceOrNamespace() throws Exception {
        ApplicationManifest manifest = EtcdSecurityTestFixture.manifest();
        try (EtcdApplicationRegistry publishing = registry("provider");
                EtcdApplicationRegistry foreign = registry("provider", NAMESPACE + "-foreign", ENDPOINT,
                        path("ETCD_TLS_TEST_CA_FILE"), false, false)) {
            ApplicationInstance another = new ApplicationInstance("not-provider-main", PROVIDER,
                    ApplicationRole.PROVIDER, new RpcEndpoint("127.0.0.1", 19111), 100, manifest.revision(), Map.of());
            rejection(RegistrySecurityFailure.AUTHORIZATION_REJECTED, publishing.register(another));
            rejection(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                    publishing.serviceMappingRepository().claim(PROVIDER, "not-provider-main"));
            ApplicationKey foreignApp = new ApplicationKey(NAMESPACE + "-foreign", PROVIDER.applicationName());
            rejection(RegistrySecurityFailure.AUTHORIZATION_REJECTED, foreign.lookup(foreignApp));
        }
    }

    @Test
    void consumerAndObserverCannotPublishMetadataOrOtherMembers() throws Exception {
        ApplicationManifest manifest = EtcdSecurityTestFixture.manifest();
        try (EtcdApplicationRegistry consumer = registry("consumer");
                EtcdApplicationRegistry observer = registry("observer")) {
            rejection(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                    consumer.serviceMappingRepository().claim(CONSUMER, "consumer-main"));
            rejection(RegistrySecurityFailure.AUTHORIZATION_REJECTED, consumer.manifestRepository().publish(
                    ApplicationManifest.create(CONSUMER, List.of(EtcdSecurityTestFixture.exported(SERVICE_A)))));
            rejection(RegistrySecurityFailure.AUTHORIZATION_REJECTED, observer.register(provider(manifest)));
            rejection(RegistrySecurityFailure.AUTHORIZATION_REJECTED, observer.manifestRepository().publish(
                    ApplicationManifest.create(PROVIDER,
                            List.of(EtcdSecurityTestFixture.exported(new com.peachsoft.otryx.api.ServiceKey(
                                    "test.ObserverDenied", "1", "default"))))));
        }
    }

    @Test
    void observedPermissionRevocationInvalidatesOtherRouteUntilACompleteNewVerification() throws Exception {
        ApplicationManifest manifest = EtcdSecurityTestFixture.manifest();
        ApplicationInstance member = provider(manifest);
        String observerUser = EtcdSecurityTestFixture.username("observer");
        try (Client administrator = EtcdSecurityTestFixture.administrator();
                EtcdApplicationRegistry publishing = registry("provider");
                EtcdApplicationRegistry observing = registry("observer");
                ManifestSnapshotCache manifests = new ManifestSnapshotCache(observing.manifestRepository());
                ServiceMappingCache mappings = new ServiceMappingCache(
                        observing.serviceMappingRepository(), Duration.ofSeconds(10), Duration.ZERO);
                ApplicationServiceResolver resolver = new ApplicationServiceResolver(
                        mappings, observing, manifests, Duration.ofSeconds(10))) {
            await(publishing.manifestRepository().publish(manifest));
            var owner = await(publishing.serviceMappingRepository().claim(PROVIDER, member.instanceId()));
            await(publishing.serviceMappingRepository().publish(owner, Set.of(SERVICE_A, SERVICE_B)));
            await(publishing.register(member));
            try {
                await(resolver.refresh(KEY_A, SCHEMA));
                VerifiedProviderSnapshot oldB = await(resolver.refresh(KEY_B, SCHEMA));
                assertTrue(resolver.isSnapshotUsable(KEY_B, SCHEMA, oldB));
                await(administrator.getAuthClient().userRevokeRole(
                        ByteSequence.from(observerUser, StandardCharsets.UTF_8),
                        ByteSequence.from(observerUser, StandardCharsets.UTF_8)));
                RegistrySecurityFailure observedReason;
                try {
                    ExecutionException rejected = assertThrows(ExecutionException.class,
                            () -> await(resolver.refresh(KEY_A, SCHEMA)));
                    RegistrySecurityException security = assertInstanceOf(
                            RegistrySecurityException.class, rejected.getCause());
                    observedReason = security.reason();
                    assertTrue(Set.of(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                            RegistrySecurityFailure.AUTHENTICATION_REJECTED).contains(observedReason));
                    assertEquals("Registry security rejected: " + observedReason.name(), security.getMessage());
                    assertNull(security.getCause());
                    assertEquals(0, security.getSuppressed().length);
                    assertEquals(observedReason,
                            assertThrows(RegistrySecurityException.class,
                                    () -> resolver.isSnapshotUsable(KEY_B, SCHEMA, oldB)).reason());
                    assertTrue(resolver.current(KEY_B, SCHEMA).isEmpty());
                } finally {
                    await(administrator.getAuthClient().userGrantRole(
                            ByteSequence.from(observerUser, StandardCharsets.UTF_8),
                            ByteSequence.from(observerUser, StandardCharsets.UTF_8)));
                }
                try (EtcdApplicationRegistry restored = registry("observer");
                        ManifestSnapshotCache freshManifests = new ManifestSnapshotCache(restored.manifestRepository());
                        ServiceMappingCache freshMappings = new ServiceMappingCache(
                                restored.serviceMappingRepository(), Duration.ofSeconds(10), Duration.ZERO);
                        ApplicationServiceResolver fresh = new ApplicationServiceResolver(
                                freshMappings, restored, freshManifests, Duration.ofSeconds(10))) {
                    assertFalse(await(fresh.refresh(KEY_A, SCHEMA)).providers().isEmpty());
                    assertEquals(observedReason,
                            assertThrows(RegistrySecurityException.class,
                                    () -> resolver.isSnapshotUsable(KEY_B, SCHEMA, oldB)).reason());
                }
            } finally {
                await(publishing.serviceMappingRepository().revoke(owner));
                await(publishing.unregister(member));
            }
        }
    }


    @Test
    void aHealthyFirstMemberCannotHideALaterCorruptedMemberValue() throws Exception {
        ApplicationManifest manifest = EtcdSecurityTestFixture.manifest();
        ApplicationInstance member = provider(manifest);
        EtcdV2Paths paths = new EtcdV2Paths(NAMESPACE);
        ByteSequence corruptedKey = bytes(paths.memberKey(PROVIDER, "zz-corrupted"));
        try (Client administrator = EtcdSecurityTestFixture.administrator();
                EtcdApplicationRegistry publishing = registry("provider");
                EtcdApplicationRegistry observing = registry("observer")) {
            await(publishing.manifestRepository().publish(manifest));
            var owner = await(publishing.serviceMappingRepository().claim(PROVIDER, member.instanceId()));
            await(publishing.serviceMappingRepository().publish(owner, Set.of(SERVICE_A)));
            await(publishing.register(member));
            try {
                await(administrator.getKVClient().put(corruptedKey, ByteSequence.from(new byte[] {1, 2})));
                rejection(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                        observing.serviceMappingRepository().lookup(KEY_A));
                rejection(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED, observing.lookup(PROVIDER));
            } finally {
                await(administrator.getKVClient().delete(corruptedKey));
                await(publishing.serviceMappingRepository().revoke(owner));
                await(publishing.unregister(member));
            }
        }
    }

    private static ApplicationInstance provider(ApplicationManifest manifest) {
        return new ApplicationInstance("provider-main", PROVIDER, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19110), 100, manifest.revision(), Map.of("zone", "security-test"));
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }
}
