package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcSchemaFingerprint;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 验证应用控制面解析后的本地服务目录、有限陈旧预算及实例选择逻辑。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
class ServiceDirectoryTest {

    private static final String NAMESPACE = "directory-tests";
    private static final ServiceKey KEY = new ServiceKey(DirectoryService.class.getName(), "1.0.0", "default");
    private static final ServiceMappingKey MAPPING_KEY = new ServiceMappingKey(NAMESPACE, KEY);
    private static final String SCHEMA = RpcSchemaFingerprint.serviceFingerprint(KEY, DirectoryService.class);
    private static final Duration CACHE_BUDGET = Duration.ofSeconds(3);

    @Test
    void initialNotReadyMustNotBeConfusedWithValidEmptySnapshot() {
        try (Fixture fixture = new Fixture(); ServiceDirectory directory = fixture.directory()) {
            assertEquals(ServiceDirectory.Status.NOT_READY, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
            fixture.publish(1L);
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

    @Test
    void unhealthyLastKnownGoodExpiresOnlyAfterExplicitFailure() {
        AtomicLong clock = new AtomicLong(10_000L);
        try (Fixture fixture = new Fixture(); ServiceDirectory directory = fixture.directory(
                new RpcDirectoryFreshnessOptions(Duration.ofNanos(100L)), clock::get)) {
            ApplicationInstance provider = fixture.provider("healthy", 19091, SCHEMA);
            fixture.publish(1L, provider);
            fixture.refresh();
            clock.addAndGet(20_000L);
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status(),
                    "A completed application refresh stays healthy until an explicit refresh failure");
            assertArrayEquals(new ServiceInstance[] {project(provider)}, directory.snapshot());
            fixture.mappings.failed = true;
            fixture.refresh();
            clock.addAndGet(99L);
            assertEquals(ServiceDirectory.Status.LAST_KNOWN_GOOD, directory.status());
            assertArrayEquals(new ServiceInstance[] {project(provider)}, directory.snapshot());
            clock.incrementAndGet();
            assertEquals(ServiceDirectory.Status.STALE, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
            fixture.mappings.failed = false;
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
            assertArrayEquals(new ServiceInstance[] {project(provider)}, directory.snapshot());
            fixture.publish(2L);
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

    @Test
    void failedInitialRefreshCannotFabricateHealthyEmptySnapshot() {
        try (Fixture fixture = new Fixture(); ServiceDirectory directory = fixture.directory()) {
            fixture.mappings.failed = true;
            fixture.refresh();
            fixture.mappings.failed = false;
            assertEquals(ServiceDirectory.Status.NOT_READY, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
        }
    }

    @Test
    void closeMustRejectFurtherDirectoryReadsAndUpdates() {
        try (Fixture fixture = new Fixture()) {
            ServiceDirectory directory = fixture.directory();
            ApplicationInstance provider = fixture.provider("live", 19091, SCHEMA);
            fixture.publish(1L, provider);
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
            directory.close();
            fixture.publish(2L, provider);
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.CLOSED, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
            assertTrue(fixture.scheduler.task.isCancelled());
            directory.close();
        }
    }

    @Test
    void lateSuccessfulRefreshMustNotReopenClosedDirectory() {
        try (Fixture fixture = new Fixture()) {
            ServiceDirectory directory = fixture.directory();
            CompletableFuture<ServiceMappingSnapshot> pending = new CompletableFuture<>();
            fixture.mappings.response = pending;
            fixture.refresh();
            directory.close();
            pending.complete(new ServiceMappingSnapshot(MAPPING_KEY, Set.of(), 1L));
            assertEquals(ServiceDirectory.Status.CLOSED, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

    @Test
    void defaultBudgetMustExpireLastKnownGoodSnapshot() {
        AtomicLong clock = new AtomicLong(1_000L);
        try (Fixture fixture = new Fixture(); ServiceDirectory directory = fixture.directory(
                RpcDirectoryFreshnessOptions.DEFAULT, clock::get)) {
            ApplicationInstance provider = fixture.provider("healthy", 19091, SCHEMA);
            fixture.publish(1L, provider);
            fixture.refresh();
            fixture.mappings.failed = true;
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.LAST_KNOWN_GOOD, directory.status());
            assertArrayEquals(new ServiceInstance[] {project(provider)}, directory.snapshot());
            clock.addAndGet(RpcDirectoryFreshnessOptions.DEFAULT.maxStaleness().toNanos());
            assertEquals(ServiceDirectory.Status.STALE, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

    @Test
    void invalidDurationMustFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new RpcDirectoryFreshnessOptions(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcDirectoryFreshnessOptions(Duration.ofSeconds(-1L)));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcDirectoryFreshnessOptions(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void olderSnapshotMustNotRestoreExpiredLastGoodState() {
        AtomicLong clock = new AtomicLong(1_000L);
        try (Fixture fixture = new Fixture(); ServiceDirectory directory = fixture.directory(
                new RpcDirectoryFreshnessOptions(Duration.ofNanos(10L)), clock::get)) {
            ApplicationInstance fresh = fixture.provider("fresh", 19091, SCHEMA);
            ApplicationInstance old = fixture.provider("old", 19092, SCHEMA);
            fixture.publish(3L, fresh);
            fixture.refresh();
            fixture.mappings.failed = true;
            fixture.refresh();
            clock.addAndGet(10L);
            assertEquals(ServiceDirectory.Status.STALE, directory.status());
            fixture.mappings.failed = false;
            fixture.publish(2L, old);
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.STALE, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

    @Test
    void shouldIgnoreOlderMappingSnapshots() {
        try (Fixture fixture = new Fixture(); ServiceDirectory directory = fixture.directory()) {
            ApplicationInstance newer = fixture.provider("newer", 19091, SCHEMA);
            ApplicationInstance older = fixture.provider("older", 19092, SCHEMA);
            fixture.publish(20L, newer);
            fixture.refresh();
            fixture.publish(19L, older);
            fixture.refresh();
            assertArrayEquals(new ServiceInstance[] {project(newer)}, directory.snapshot());
        }
    }

    @Test
    void shouldExcludeConsumerAndMismatchedManifestSchema() {
        try (Fixture fixture = new Fixture(); ServiceDirectory directory = fixture.directory()) {
            ApplicationInstance matching = fixture.provider("matching", 19091, SCHEMA);
            ApplicationInstance mismatch = fixture.provider("mismatch", 19093, "a".repeat(64));
            ApplicationInstance consumer = new ApplicationInstance("consumer-1",
                    new ApplicationKey(NAMESPACE, "consumer"), ApplicationRole.CONSUMER, null, 0, null, Map.of());
            fixture.registry.register(consumer).toCompletableFuture().join();
            fixture.publish(1L, matching, consumer, mismatch);
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.HEALTHY, directory.status());
            assertArrayEquals(new ServiceInstance[] {project(matching)}, directory.snapshot());
        }
    }

    @Test
    void missingManifestMustRejectRefreshWithoutPublishingPartialProviderList() {
        try (Fixture fixture = new Fixture(); ServiceDirectory directory = fixture.directory()) {
            ApplicationInstance matching = fixture.provider("matching", 19091, SCHEMA);
            ApplicationInstance missing = new ApplicationInstance("missing-1",
                    new ApplicationKey(NAMESPACE, "missing"), ApplicationRole.PROVIDER,
                    new RpcEndpoint("127.0.0.1", 19092), 100, "sha256:" + "b".repeat(64), Map.of());
            fixture.registry.register(missing).toCompletableFuture().join();
            fixture.publish(1L, matching, missing);
            fixture.refresh();
            assertEquals(ServiceDirectory.Status.NOT_READY, directory.status());
            assertArrayEquals(new ServiceInstance[0], directory.snapshot());
        }
    }

    private static ServiceInstance project(ApplicationInstance instance) {
        String id = instance.application().namespace() + "/" + instance.application().applicationName()
                + "/" + instance.instanceId();
        return new ServiceInstance(id, KEY, instance.rpcEndpoint(), instance.weight(), Map.of());
    }

    interface DirectoryService {
        String call();
    }

    private static final class Fixture implements AutoCloseable {
        private final MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
        private final ControlledMappings mappings = new ControlledMappings(registry.serviceMappingRepository());
        private final ServiceMappingCache mappingCache = new ServiceMappingCache(mappings, CACHE_BUDGET, Duration.ZERO);
        private final ManifestSnapshotCache manifestCache = new ManifestSnapshotCache(registry.manifestRepository());
        private final ApplicationServiceResolver resolver =
                new ApplicationServiceResolver(mappingCache, registry, manifestCache, CACHE_BUDGET);
        private final ManualScheduler scheduler = new ManualScheduler();

        private ServiceDirectory directory() {
            return directory(RpcDirectoryFreshnessOptions.DEFAULT, System::nanoTime);
        }

        private ServiceDirectory directory(RpcDirectoryFreshnessOptions freshness, LongSupplier clock) {
            return new ServiceDirectory(resolver, MAPPING_KEY, SCHEMA, freshness, scheduler, clock);
        }

        private ApplicationInstance provider(String id, int port, String schema) {
            ApplicationKey application = new ApplicationKey(NAMESPACE, id);
            ExportedService contract = ExportedService.fromContract(KEY, DirectoryService.class);
            ExportedService exported = new ExportedService(KEY, contract.serviceId(), contract.methodIds(), schema);
            ApplicationManifest manifest = ApplicationManifest.create(application, List.of(exported));
            registry.manifestRepository().publish(manifest).toCompletableFuture().join();
            ApplicationInstance instance = new ApplicationInstance(id + "-1", application, ApplicationRole.PROVIDER,
                    new RpcEndpoint("127.0.0.1", port), 100, manifest.revision(), Map.of());
            registry.register(instance).toCompletableFuture().join();
            return instance;
        }

        private void publish(long revision, ApplicationInstance... members) {
            Set<ApplicationKey> applications = Arrays.stream(members)
                    .map(ApplicationInstance::application).collect(Collectors.toUnmodifiableSet());
            mappings.response = CompletableFuture.completedFuture(
                    new ServiceMappingSnapshot(MAPPING_KEY, applications, revision));
        }

        private void refresh() {
            scheduler.refresh();
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
            resolver.close();
            manifestCache.close();
            mappingCache.close();
            registry.close();
        }
    }

    private static final class ControlledMappings implements ServiceMappingRepository {
        private final ServiceMappingRepository delegate;
        private CompletionStage<ServiceMappingSnapshot> response = CompletableFuture.completedFuture(
                new ServiceMappingSnapshot(MAPPING_KEY, Set.of(), 0L));
        private boolean failed;

        private ControlledMappings(ServiceMappingRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
            return delegate.claim(application, instanceId);
        }

        @Override
        public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
            return delegate.publish(owner, services);
        }

        @Override
        public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
            return delegate.revoke(owner);
        }

        @Override
        public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
            return failed
                    ? CompletableFuture.failedFuture(new RpcUnavailableException("Application mapping disconnected"))
                    : response;
        }
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final ManualTask task = new ManualTask();
        private Runnable refresh;

        private ManualScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            refresh = command;
            return task;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        private void refresh() {
            if (!task.isCancelled()) {
                refresh.run();
            }
        }
    }

    private static final class ManualTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        private ManualTask() {
            super(() -> null);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
