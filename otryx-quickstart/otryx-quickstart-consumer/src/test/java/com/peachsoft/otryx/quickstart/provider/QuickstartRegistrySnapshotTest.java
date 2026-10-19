package com.peachsoft.otryx.quickstart.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.quickstart.api.GreetingDetailsService;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationDiscovery;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证诊断入口使用真实控制面契约，且超时、错误和旧文件不能形成成功证据。
 *
 * <p>这里使用隔离的 Memory 后端检验投影与失败边界；真实四后端成员、
 * 跨 JVM 滚动和 mTLS 调用另由可执行场景验收，不将本测试当作网络证据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:04
 */
class QuickstartRegistrySnapshotTest {

    private static final String NAMESPACE = "public";
    private static final String PROVIDERS = "snapshot-providers";
    private static final String CONSUMERS = "snapshot-consumers";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final ServiceKey GREETING = service(GreetingService.class);
    private static final ServiceKey DETAILS = service(GreetingDetailsService.class);

    @TempDir
    private Path directory;

    @Test
    void projectsAllRolesAndVerifiesEachActualManifestRevision() throws Exception {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            Member first = addProvider(registry, "base", ApplicationRole.PROVIDER, false);
            Member second = addProvider(registry, "extended", ApplicationRole.BOTH, true);
            ApplicationInstance consumer = addConsumer(registry, "consumer");
            QuickstartRegistrySnapshot.Snapshot snapshot = capture(registry);

            var providerView = application(snapshot, PROVIDERS);
            var consumerView = application(snapshot, CONSUMERS);
            assertEquals(2, providerView.members().size());
            assertEquals(2, providerView.manifests().size());
            assertNotEquals(first.instance().manifestRevision(), second.instance().manifestRevision());
            assertEquals(Set.of("base", "extended"), routes(snapshot, GREETING));
            assertEquals(Set.of("extended"), routes(snapshot, DETAILS));
            assertEquals(1, consumerView.members().size());
            assertEquals(ApplicationRole.CONSUMER, consumerView.members().getFirst().role());
            assertNull(consumerView.members().getFirst().rpcEndpoint());
            assertNull(consumerView.members().getFirst().manifestRevision());
            assertEquals(0, consumerView.members().getFirst().weight());
            assertTrue(consumerView.manifests().isEmpty());
            assertEquals(List.of(consumer), registry.lookup(consumer.application())
                    .toCompletableFuture().join().instances());

            Path output = directory.resolve("snapshot.json");
            QuickstartRegistrySnapshot.writeSnapshot(output, snapshot);
            String json = Files.readString(output);
            assertFalse(json.contains("private-hint"));
            assertFalse(json.contains("not-for-export"));
            assertFalse(json.contains("attributes"));
            assertEquals(NAMESPACE, new ObjectMapper().readTree(json).path("applicationNamespace").asText());
        }
    }

    @Test
    void providerDeparturePreservesOtherOwnerAndConsumerMembership() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            Member first = addProvider(registry, "base", ApplicationRole.PROVIDER, false);
            Member second = addProvider(registry, "extended", ApplicationRole.PROVIDER, true);
            addConsumer(registry, "consumer");

            remove(registry, first);
            var remaining = capture(registry);
            assertEquals(Set.of("extended"), routes(remaining, GREETING));
            assertEquals(Set.of("extended"), routes(remaining, DETAILS));
            assertEquals(List.of(PROVIDERS), serviceView(remaining, GREETING).mappedApplications());

            remove(registry, second);
            var emptyProviders = capture(registry);
            assertTrue(application(emptyProviders, PROVIDERS).members().isEmpty());
            assertTrue(routes(emptyProviders, GREETING).isEmpty());
            assertTrue(routes(emptyProviders, DETAILS).isEmpty());
            assertEquals("consumer", application(emptyProviders, CONSUMERS).members().getFirst().instanceId());
        }
    }

    @Test
    void missingManifestFailsInsteadOfPublishingAnEmptyRoute() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            ApplicationKey app = new ApplicationKey(NAMESPACE, PROVIDERS);
            ApplicationManifest unpublished = ApplicationManifest.create(app,
                    List.of(ExportedService.fromContract(GREETING, GreetingService.class)));
            registry.register(new ApplicationInstance("missing", app, ApplicationRole.PROVIDER,
                    new RpcEndpoint("127.0.0.1", 19090), 100, unpublished.revision(), Map.of()))
                    .toCompletableFuture().join();
            assertThrows(IllegalStateException.class, () -> capture(registry));
        }
    }

    @Test
    void schemaMismatchRemainsVisibleAsMemberButNeverBecomesVerifiedRoute() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            ApplicationKey app = new ApplicationKey(NAMESPACE, PROVIDERS);
            ExportedService actual = ExportedService.fromContract(GREETING, GreetingService.class);
            ApplicationManifest different = ApplicationManifest.create(app, List.of(new ExportedService(
                    GREETING, actual.serviceId(), actual.methodIds(), "a".repeat(64))));
            registry.manifestRepository().publish(different).toCompletableFuture().join();
            ServiceMappingOwner owner = registry.serviceMappingRepository().claim(app, "incompatible")
                    .toCompletableFuture().join();
            registry.serviceMappingRepository().publish(owner, Set.of(GREETING)).toCompletableFuture().join();
            registry.register(new ApplicationInstance("incompatible", app, ApplicationRole.PROVIDER,
                    new RpcEndpoint("127.0.0.1", 19090), 100, different.revision(), Map.of()))
                    .toCompletableFuture().join();

            var snapshot = capture(registry);
            assertEquals(1, application(snapshot, PROVIDERS).members().size());
            assertEquals(List.of(PROVIDERS), serviceView(snapshot, GREETING).mappedApplications());
            assertTrue(routes(snapshot, GREETING).isEmpty());
        }
    }

    @Test
    void backendFailureCannotBecomeHealthyEmptyEvidence() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            ApplicationDiscovery unavailable = discovery(CompletableFuture.failedFuture(
                    new IllegalStateException("Backend failed")));
            assertThrows(IllegalStateException.class, () -> QuickstartRegistrySnapshot.capture(
                    unavailable, registry, NAMESPACE, List.of(CONSUMERS), READ_TIMEOUT));
        }
    }

    @Test
    void absoluteReadBudgetCancelsPendingLookupAndKeepsBackendOwnership() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            CompletableFuture<ApplicationDirectorySnapshot> pending = new CompletableFuture<>();
            assertThrows(IllegalStateException.class, () -> QuickstartRegistrySnapshot.capture(
                    discovery(pending), registry, NAMESPACE, List.of(CONSUMERS), Duration.ofMillis(20)));
            assertTrue(pending.isCancelled());
            assertTrue(registry.lookup(new ApplicationKey(NAMESPACE, CONSUMERS))
                    .toCompletableFuture().join().instances().isEmpty());
        }
    }

    @Test
    void interruptedInspectionPreservesInterruptAndCancelsPendingWork() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            CompletableFuture<ApplicationDirectorySnapshot> pending = new CompletableFuture<>();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IllegalStateException.class, () -> QuickstartRegistrySnapshot.capture(
                        discovery(pending), registry, NAMESPACE, List.of(CONSUMERS), READ_TIMEOUT));
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(pending.isCancelled());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void existingOutputIsNeverOverwrittenWithFreshOrPartialEvidence() throws Exception {
        Path output = directory.resolve("previous.json");
        Files.writeString(output, "previous evidence");
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            var snapshot = capture(registry);
            assertThrows(FileAlreadyExistsException.class,
                    () -> QuickstartRegistrySnapshot.writeSnapshot(output, snapshot));
        }
        assertEquals("previous evidence", Files.readString(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ",,,", "user:secret@127.0.0.1:8848"})
    void rejectsImplicitOrCredentialBearingTargetBeforeCreatingSdk(String endpoints) {
        assertThrows(IllegalArgumentException.class, () -> QuickstartRegistrySnapshot.parseArguments(new String[] {
                "nacos", endpoints, NAMESPACE, "OTRYX_SNAPSHOT", "-",
                directory.resolve("new.json").toString(), CONSUMERS}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"etcd", "consul"})
    void parsesExplicitNativeMetadataBackend(String backend) throws Exception {
        var command = QuickstartRegistrySnapshot.parseArguments(new String[] {
                backend, "http://127.0.0.1:2379", NAMESPACE, "-", "-",
                directory.resolve("new.json").toString(), CONSUMERS});
        assertEquals(backend, command.backend());
        assertEquals(NAMESPACE, command.namespace());
        assertTrue(command.options().providerOptions().isEmpty());
    }

    @Test
    void eurekaUsesExplicitConsulMetadataAndRejectsImplicitFallback() throws Exception {
        var command = QuickstartRegistrySnapshot.parseArguments(new String[] {
                "eureka", "http://127.0.0.1:8761/eureka", NAMESPACE, "-", "http://127.0.0.1:8500",
                directory.resolve("new.json").toString(), CONSUMERS});
        assertEquals("consul", command.options().providerOption("eurekaMetadataBackend", ""));
        assertEquals("http://127.0.0.1:8500", command.options().providerOption("eurekaMetadataEndpoints", ""));
        for (String metadata : List.of("-", ",,,", "http://user:secret@127.0.0.1:8500")) {
            assertThrows(IllegalArgumentException.class, () -> QuickstartRegistrySnapshot.parseArguments(new String[] {
                    "eureka", "http://127.0.0.1:8761/eureka", NAMESPACE, "-", metadata,
                    directory.resolve("new.json").toString(), CONSUMERS}));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "interface", "unknown"})
    void unsupportedBackendFailsBeforeCreatingSdk(String backend) {
        assertThrows(IllegalArgumentException.class, () -> QuickstartRegistrySnapshot.parseArguments(new String[] {
                backend, "http://127.0.0.1:2379", NAMESPACE, "-", "-",
                directory.resolve("new.json").toString(), CONSUMERS}));
    }

    @Test
    void rejectsUnusedBackendOptionsAndExistingSnapshotTarget() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> QuickstartRegistrySnapshot.parseArguments(new String[] {
                "etcd", "http://127.0.0.1:2379", NAMESPACE, "ignored-group", "-",
                directory.resolve("new.json").toString(), CONSUMERS}));
        Path output = directory.resolve("existing.json");
        Files.writeString(output, "previous evidence");
        assertThrows(FileAlreadyExistsException.class, () -> QuickstartRegistrySnapshot.parseArguments(new String[] {
                "consul", "http://127.0.0.1:8500", NAMESPACE, "-", "-", output.toString(), CONSUMERS}));
    }

    @Test
    void rejectsDuplicateApplicationsAndUnboundedBudgets() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            assertThrows(IllegalArgumentException.class, () -> QuickstartRegistrySnapshot.capture(
                    registry, registry, NAMESPACE, List.of(CONSUMERS, CONSUMERS), READ_TIMEOUT));
            assertThrows(IllegalArgumentException.class, () -> QuickstartRegistrySnapshot.capture(
                    registry, registry, NAMESPACE, List.of(CONSUMERS), Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> QuickstartRegistrySnapshot.capture(
                    registry, registry, NAMESPACE, List.of(CONSUMERS), Duration.ofMinutes(1)));
        }
    }

    private static QuickstartRegistrySnapshot.Snapshot capture(MemoryApplicationRegistry registry) {
        return QuickstartRegistrySnapshot.capture(
                registry, registry, NAMESPACE, List.of(PROVIDERS, CONSUMERS), READ_TIMEOUT);
    }

    private static Member addProvider(MemoryApplicationRegistry registry, String id,
            ApplicationRole role, boolean details) {
        ApplicationKey app = new ApplicationKey(NAMESPACE, PROVIDERS);
        ExportedService greeting = ExportedService.fromContract(GREETING, GreetingService.class);
        List<ExportedService> services = details
                ? List.of(greeting, ExportedService.fromContract(DETAILS, GreetingDetailsService.class))
                : List.of(greeting);
        ApplicationManifest manifest = ApplicationManifest.create(app, services);
        registry.manifestRepository().publish(manifest).toCompletableFuture().join();
        ServiceMappingOwner owner = registry.serviceMappingRepository().claim(app, id).toCompletableFuture().join();
        registry.serviceMappingRepository().publish(owner,
                services.stream().map(ExportedService::serviceKey).collect(Collectors.toSet()))
                .toCompletableFuture().join();
        ApplicationInstance instance = new ApplicationInstance(id, app, role,
                new RpcEndpoint("127.0.0.1", details ? 19091 : 19090), 100, manifest.revision(),
                Map.of("private-hint", "not-for-export"));
        registry.register(instance).toCompletableFuture().join();
        return new Member(instance, owner);
    }

    private static ApplicationInstance addConsumer(MemoryApplicationRegistry registry, String id) {
        ApplicationInstance consumer = new ApplicationInstance(id, new ApplicationKey(NAMESPACE, CONSUMERS),
                ApplicationRole.CONSUMER, null, 0, null, Map.of());
        registry.register(consumer).toCompletableFuture().join();
        return consumer;
    }

    private static void remove(MemoryApplicationRegistry registry, Member member) {
        registry.unregister(member.instance()).toCompletableFuture().join();
        registry.serviceMappingRepository().revoke(member.owner()).toCompletableFuture().join();
    }

    private static ServiceKey service(Class<?> contract) {
        return new ServiceKey(contract.getName(), "1.0.0", "default");
    }

    private static QuickstartRegistrySnapshot.ApplicationView application(
            QuickstartRegistrySnapshot.Snapshot snapshot, String name) {
        return snapshot.applications().stream().filter(value -> value.applicationName().equals(name))
                .findFirst().orElseThrow();
    }

    private static QuickstartRegistrySnapshot.ServiceView serviceView(
            QuickstartRegistrySnapshot.Snapshot snapshot, ServiceKey service) {
        return snapshot.services().stream().filter(value -> value.serviceKey().equals(service))
                .findFirst().orElseThrow();
    }

    private static Set<String> routes(QuickstartRegistrySnapshot.Snapshot snapshot, ServiceKey service) {
        return serviceView(snapshot, service).verifiedProviders().stream()
                .map(QuickstartRegistrySnapshot.ProviderView::instanceId).collect(Collectors.toSet());
    }

    private static ApplicationDiscovery discovery(CompletionStage<ApplicationDirectorySnapshot> result) {
        return new ApplicationDiscovery() {
            @Override
            public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
                return result;
            }

            @Override
            public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
                throw new AssertionError("A one-shot observer must not subscribe or register a member");
            }
        };
    }

    private record Member(ApplicationInstance instance, ServiceMappingOwner owner) {
    }
}
