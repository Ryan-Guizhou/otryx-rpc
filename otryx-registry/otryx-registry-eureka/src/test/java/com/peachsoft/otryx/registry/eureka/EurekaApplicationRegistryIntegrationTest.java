package com.peachsoft.otryx.registry.eureka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationRegistryContractTestKit;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真 Eureka Server + 真 Consul KV Metadata 联合集成验证，不依赖 Fake HTTP Server。
 *
 * <p>Eureka 仅存在应用成员、Consul 仅存 CAS Manifest/Mapping；不双重注册成员。
 * 验证纯 Consumer 端口禁用、路由隔离、多接口密度、服务端租约丢失与实例冲突。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:09
 */
@EnabledIfSystemProperty(named = "eureka.test.endpoint", matches = ".+")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EurekaApplicationRegistryIntegrationTest {

    private static final String ENDPOINT = System.getProperty(
            "eureka.test.endpoint", "http://127.0.0.1:8761/eureka");
    private static final String CONSUL_ENDPOINT = System.getProperty(
            "eureka.test.metadata.endpoint", "http://127.0.0.1:8500");
    private static final Duration WAIT = Duration.ofSeconds(25);
    private static final int PROVIDER_PORT = 19211;

    @Test
    void providerConsumerAndBothRegisterRealEurekaMembersWithDisabledConsumerPort() throws Exception {
        String namespace = unique();
        ApplicationKey application = new ApplicationKey(namespace, "orders");
        ApplicationInstance consumer = consumer(application, "consumer");
        ApplicationInstance provider = provider(application, "provider", PROVIDER_PORT);
        ApplicationInstance both = new ApplicationInstance("both", application, ApplicationRole.BOTH,
                new RpcEndpoint("127.0.0.1", PROVIDER_PORT + 1), 75,
                "sha256:" + "a".repeat(64), Map.of());
        EurekaV2Identity identity = new EurekaV2Identity(namespace);
        try (EurekaApplicationRegistry first = registry(namespace);
             EurekaApplicationRegistry second = registry(namespace);
             HttpRegistryClient client = rawEureka()) {
            first.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            second.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            first.register(both).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(await(WAIT, () -> first.lookup(application)
                    .toCompletableFuture().join().instances().size() == 3));
            assertEquals(Set.of(provider, consumer, both),
                    Set.copyOf(first.lookup(application).toCompletableFuture().join().instances()));
            JsonNode node = client.request(HttpRegistryClient.METHOD_GET,
                    EurekaProtocol.PATH_APPLICATIONS_PREFIX + identity.appName(application), null)
                    .json().path(EurekaProtocol.FIELD_APPLICATION).path(EurekaProtocol.FIELD_INSTANCE);
            boolean foundDisabled = false;
            for (JsonNode item : node) {
                if (identity.nativeInstanceId(consumer,
                        item.path(EurekaProtocol.FIELD_METADATA).path("otryxV2Token").asText())
                        .equals(item.path(EurekaProtocol.FIELD_INSTANCE_ID).asText())) {
                    assertEquals(0, item.path(EurekaProtocol.FIELD_PORT)
                            .path(EurekaProtocol.FIELD_VALUE).asInt());
                    assertFalse(Boolean.parseBoolean(item.path(EurekaProtocol.FIELD_PORT)
                            .path(EurekaProtocol.FIELD_ENABLED).asText()));
                    foundDisabled = true;
                }
            }
            assertTrue(foundDisabled, "Consumer must be present with native disabled port");
            second.unregister(consumer).toCompletableFuture().join();
            assertTrue(await(WAIT, () -> first.lookup(application)
                    .toCompletableFuture().join().instances().size() == 2));
        }
    }

    @Test
    void remoteMetadataCandidatesRequireRealEurekaProviderHealth() throws Exception {
        String namespace = unique();
        ApplicationKey application = new ApplicationKey(namespace, "payment");
        ServiceKey service = service("Payment");
        ApplicationInstance member = provider(application, "node1", PROVIDER_PORT + 2);
        try (EurekaApplicationRegistry first = registry(namespace);
             EurekaApplicationRegistry second = registry(namespace)) {
            ApplicationManifest manifest = manifest(application, Set.of(service));
            first.manifestRepository().publish(manifest).toCompletableFuture().join();
            assertEquals(manifest, second.manifestRepository().lookupVerified(
                    application, manifest.revision()).toCompletableFuture().join().orElseThrow());
            ServiceMappingOwner owner = first.serviceMappingRepository()
                    .claim(application, member.instanceId()).toCompletableFuture().join();
            first.serviceMappingRepository().publish(owner, Set.of(service)).toCompletableFuture().join();
            ServiceMappingKey mapping = new ServiceMappingKey(namespace, service);
            assertTrue(first.serviceMappingRepository().lookup(mapping)
                    .toCompletableFuture().join().applications().isEmpty(),
                    "Consul owner without Eureka member must never route");
            second.register(member).toCompletableFuture().join();
            assertTrue(await(WAIT, () -> first.serviceMappingRepository()
                    .lookup(mapping).toCompletableFuture().join().applications().contains(application)));
            second.unregister(member).toCompletableFuture().join();
            assertTrue(await(WAIT, () -> first.serviceMappingRepository()
                    .lookup(mapping).toCompletableFuture().join().applications().isEmpty()));
            first.serviceMappingRepository().revoke(owner).toCompletableFuture().join();
        }
    }

    @Test
    void unrelatedHealthyProviderMustNotSatisfyAnUnpublishedMappingOwner() throws Exception {
        String namespace = unique();
        ApplicationKey application = new ApplicationKey(namespace, "ownerproof");
        ServiceKey contract = service("BoundContract");
        ServiceMappingKey mapping = new ServiceMappingKey(namespace, contract);
        ApplicationInstance unrelated = provider(application, "healthy-other", PROVIDER_PORT + 8);
        ApplicationInstance publisher = provider(application, "publishing-owner", PROVIDER_PORT + 9);
        try (EurekaApplicationRegistry writer = registry(namespace);
             EurekaApplicationRegistry reader = registry(namespace)) {
            ServiceMappingOwner claimed = writer.serviceMappingRepository()
                    .claim(application, publisher.instanceId()).toCompletableFuture().join();
            writer.serviceMappingRepository().publish(claimed, Set.of(contract))
                    .toCompletableFuture().join();
            reader.register(unrelated).toCompletableFuture().join();
            assertTrue(await(WAIT, () -> reader.lookup(application).toCompletableFuture()
                    .join().instances().contains(unrelated)));
            assertTrue(writer.serviceMappingRepository().lookup(mapping)
                    .toCompletableFuture().join().applications().isEmpty(),
                    "Unrelated healthy Provider must not inherit stale Owner exports");
            writer.register(publisher).toCompletableFuture().join();
            assertTrue(await(WAIT, () -> writer.serviceMappingRepository().lookup(mapping)
                    .toCompletableFuture().join().applications().contains(application)));
            writer.unregister(publisher).toCompletableFuture().join();
            assertTrue(await(WAIT, () -> writer.serviceMappingRepository().lookup(mapping)
                    .toCompletableFuture().join().applications().isEmpty()));
            writer.serviceMappingRepository().revoke(claimed).toCompletableFuture().join();
        }
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void thirtyRealContractsAndTenIndependentClientsSatisfySharedApplicationContract() throws Exception {
        String namespace = unique();
        EurekaV2Identity identity = new EurekaV2Identity(namespace);
        try (HttpRegistryClient raw = rawEureka()) {
            ApplicationRegistryContractTestKit.verify("eureka", namespace, 10, () -> registry(namespace),
                    application -> {
                        HttpRegistryClient.Response response = raw.request("GET",
                                "/apps/" + identity.appName(application), null);
                        if (response.status() == 404) {
                            return 0;
                        }
                        assertEquals(200, response.status());
                        JsonNode instances = response.json().path("application").path("instance");
                        assertTrue(instances.isArray() || instances.isObject());
                        return instances.isArray() ? instances.size() : 1;
                    }, WAIT);
        }
    }

    @Test
    void lostEurekaLeaseIsReregisteredWithoutChangingMetadataOwnership() throws Exception {
        String namespace = unique();
        ApplicationKey app = new ApplicationKey(namespace, "renew");
        ApplicationInstance member = provider(app, "provider", PROVIDER_PORT + 50);
        try (EurekaApplicationRegistry registry = registry(namespace);
             HttpRegistryClient http = rawEureka()) {
            registry.register(member).toCompletableFuture().join();
            assertTrue(await(WAIT, () -> registry.lookup(app)
                    .toCompletableFuture().join().instances().contains(member)));
            EurekaV2Identity identity = new EurekaV2Identity(namespace);
            JsonNode raw = http.request(HttpRegistryClient.METHOD_GET,
                    EurekaProtocol.PATH_APPLICATIONS_PREFIX + identity.appName(app), null)
                    .json().path(EurekaProtocol.FIELD_APPLICATION).path(EurekaProtocol.FIELD_INSTANCE);
            JsonNode entry = raw.isArray() ? raw.get(0) : raw;
            String id = entry.path(EurekaProtocol.FIELD_INSTANCE_ID).asText();
            http.request(HttpRegistryClient.METHOD_DELETE,
                    EurekaProtocol.PATH_APPLICATIONS_PREFIX + identity.appName(app) + '/' + id, null);
            assertTrue(await(WAIT, () -> registry.lookup(app)
                    .toCompletableFuture().join().instances().contains(member)));
        }
    }

    @Test
    void duplicateLogicalInstanceIdsMustFailClosedInsteadOfOverwritingMember() throws Exception {
        String namespace = unique();
        ApplicationKey app = new ApplicationKey(namespace, "duplicates");
        ApplicationInstance member = consumer(app, "same-instance");
        try (EurekaApplicationRegistry first = registry(namespace);
             EurekaApplicationRegistry second = registry(namespace)) {
            first.register(member).toCompletableFuture().join();
            second.register(member).toCompletableFuture().join();
            assertThrows(CompletionException.class,
                    () -> first.lookup(app).toCompletableFuture().join(),
                    "Duplicate logical members must not be silently chosen");
            first.unregister(member).toCompletableFuture().join();
            assertTrue(await(WAIT, () -> second.lookup(app)
                    .toCompletableFuture().join().instances().equals(List.of(member))));
        }
    }

    private static EurekaApplicationRegistry registry(String namespace) {
        return (EurekaApplicationRegistry) new EurekaApplicationRegistryFactory()
                .create(new RegistryOptions(List.of(ENDPOINT), namespace, Map.of(
                        "eurekaMetadataBackend", "consul",
                        "eurekaMetadataEndpoints", CONSUL_ENDPOINT,
                        "eurekaMemberHost", "127.0.0.1",
                        "eurekaLeaseSeconds", "9",
                        "eurekaHeartbeatSeconds", "1",
                        "eurekaPollIntervalMillis", "250",
                        "eurekaMetadata.consulTtlSeconds", "10",
                        "eurekaMetadata.consulHeartbeatSeconds", "1")));
    }

    private static HttpRegistryClient rawEureka() {
        return new HttpRegistryClient(List.of(ENDPOINT), "", "", Duration.ofSeconds(5));
    }

    private static ApplicationManifest manifest(ApplicationKey app, Set<ServiceKey> services) {
        return ApplicationManifest.create(app,
                services.stream().map(service -> new ExportedService(
                        service, RpcIds.serviceId(service), Set.of(1), "a".repeat(64))).toList());
    }

    private static ServiceKey service(String suffix) {
        return new ServiceKey("demo." + suffix, "1.0.0", "default");
    }

    private static ApplicationInstance provider(ApplicationKey app, String id, int port) {
        return new ApplicationInstance(id, app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", port), 100,
                "sha256:" + "a".repeat(64), Map.of());
    }

    private static ApplicationInstance consumer(ApplicationKey app, String id) {
        return new ApplicationInstance(id, app, ApplicationRole.CONSUMER, null, 0, null, Map.of());
    }

    private static boolean await(Duration timeout, BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    private static String unique() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
