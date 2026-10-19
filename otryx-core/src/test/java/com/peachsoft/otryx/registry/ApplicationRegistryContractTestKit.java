package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 所有应用级注册中心共用的成员、Manifest、Mapping 和完整服务解析合同。
 *
 * <p>三十个实际 Java 接口通过当前 Wire v1 契约生成真实导出项。远端适配器必须
 * 创建十个不同 Registry 对象；Memory 使用其设计允许的一个进程内共享 Registry。
 * 两种模式均检查十个成员的 0→10→0，以及 Provider/Consumer/BOTH、多应用、
 * 多修订、命名空间、Fencing、并发注册与注销、初始健康空目录与订阅收敛。
 *
 * <p>计数代理分别记录 SPI 调用和正常、异常终态，保留原始 CompletionStage；
 * 另记录订阅句柄、全部回调及相邻目录的逻辑内容变化。初始快照单列，逻辑变化
 * 忽略 revision 与列表顺序，但包含成员角色、端点、Manifest revision 和属性。
 * 后台租约、SDK 重试、缓存及网络请求不包含在计数中。原生成员探针只证明
 * 服务端记录数量，后端实际请求流量未测量。收敛耗时不是生产延迟承诺。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 07:01
 */
public final class ApplicationRegistryContractTestKit {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationRegistryContractTestKit.class);
    private static final int CONTRACT_COUNT = 30;
    private static final int MEMBER_COUNT = 10;
    private static final int ROLLING_CONTRACT_COUNT = 15;
    private static final int RPC_BASE_PORT = 21000;
    private static final long POLL_MILLIS = 100L;
    private static final Duration MAX_STALE = Duration.ofSeconds(3);

    private ApplicationRegistryContractTestKit() {
    }

    /**
     * 在新的隔离后端域中运行完整合同，始终关闭本方法创建的 Registry。
     *
     * @param adapter 稳定的适配器名，仅用于证据标签
     * @param namespace 后端已配置的应用命名空间
     * @param clientCount Memory 为 1；真实共享后端必须为 10
     * @param factory 每次创建同一后端域内独立客户端的工厂
     * @param memberProbe 远端直接统计原生成员；Memory 统计进程内真实目录
     * @param timeout 单次异步操作与收敛等待的上限
     * @throws Exception 后端故障、操作超时或资源关闭失败
     */
    public static void verify(
            String adapter, String namespace, int clientCount, ClientFactory factory,
            MemberCountProbe memberProbe, Duration timeout) throws Exception {
        assertTrue(clientCount == 1 || clientCount == MEMBER_COUNT);
        assertEquals("memory".equals(adapter) ? 1 : MEMBER_COUNT, clientCount);
        List<Client> clients = new ArrayList<>();
        Evidence evidence = new Evidence();
        Set<ApplicationRegistry> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable failure = null;
        try {
            for (int index = 0; index < clientCount; index++) {
                ApplicationRegistry registry = factory.create();
                assertTrue(identities.add(registry), "Factory must return distinct Registry clients");
                clients.add(new Client(registry, evidence));
            }
            run(adapter, namespace, clients, memberProbe, timeout, evidence);
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            closeAll(identities, failure);
        }
    }

    private static void run(
            String adapter, String namespace, List<Client> clients, MemberCountProbe memberProbe,
            Duration timeout, Evidence evidence) throws Exception {
        ApplicationKey application = new ApplicationKey(namespace, "shared-contract");
        List<ExportedService> exports = exports();
        assertEquals(CONTRACT_COUNT, exports.size());
        assertEquals(CONTRACT_COUNT, exports.stream().map(ExportedService::serviceKey).distinct().count());
        ApplicationManifest full = ApplicationManifest.create(application, exports);
        Set<ServiceKey> serviceKeys = keys(exports);
        Client reader = clients.getFirst();
        Notifications notifications = new Notifications(application);
        List<ApplicationInstance> members = new ArrayList<>();
        List<ServiceMappingOwner> owners = new ArrayList<>();
        long registrationMillis;
        long unregistrationMillis;
        try (RegistrySubscription subscription = reader.discovery.subscribe(application, notifications);
             ServiceMappingCache mappings = new ServiceMappingCache(reader.mappings, MAX_STALE, Duration.ZERO);
             ManifestSnapshotCache manifests = new ManifestSnapshotCache(reader.manifests);
             ApplicationServiceResolver resolver = new ApplicationServiceResolver(
                     mappings, reader.discovery, manifests, MAX_STALE)) {
            await(timeout, () -> notifications.latest.get() != null
                    && notifications.latest.get().instances().isEmpty()
                    && notifications.successes.get() > 0);
            assertEquals(0, memberProbe.count(application));
            assertTrue(finish(reader.discovery.lookup(application), timeout).instances().isEmpty());
            finish(reader.manifests.publish(full), timeout);
            finish(reader.manifests.publish(full), timeout);

            long registeredAt = System.nanoTime();
            AtomicReferenceArray<ServiceMappingOwner> publishedOwners = new AtomicReferenceArray<>(MEMBER_COUNT);
            List<CompletableFuture<Void>> registrations = new ArrayList<>();
            for (int index = 0; index < MEMBER_COUNT; index++) {
                Client writer = clients.get(index % clients.size());
                ApplicationInstance member = provider(application, "member-" + index, index,
                        index % 2 == 0 ? ApplicationRole.PROVIDER : ApplicationRole.BOTH, full.revision());
                members.add(member);
                int ownerIndex = index;
                registrations.add(writer.mappings.claim(application, member.instanceId()).thenCompose(owner -> {
                    publishedOwners.set(ownerIndex, owner);
                    return writer.mappings.publish(owner, serviceKeys)
                            .thenCompose(ignored -> writer.registrar.register(member));
                }).toCompletableFuture());
            }
            finish(CompletableFuture.allOf(registrations.toArray(CompletableFuture[]::new)), timeout);
            for (int index = 0; index < MEMBER_COUNT; index++) {
                owners.add(publishedOwners.get(index));
            }
            awaitMembers(reader, application, members, notifications, memberProbe, timeout);
            registrationMillis = elapsedMillis(registeredAt);
            verifyNamespaceIsolation(reader, application, exports.getFirst(), timeout);
            for (Client client : clients) {
                await(timeout, () -> Set.copyOf(finish(client.discovery.lookup(application), timeout).instances())
                        .equals(Set.copyOf(members)));
            }
            verifyRoutes(resolver, application, exports, members, timeout);
            Map<String, Long> beforeLocalReads = evidence.callCounts();
            for (ExportedService exported : exports) {
                ServiceMappingKey key = new ServiceMappingKey(namespace, exported.serviceKey());
                assertEquals(Set.copyOf(members), Set.copyOf(resolver.current(
                        key, exported.schemaFingerprint()).orElseThrow().providers()));
            }
            assertEquals(beforeLocalReads, evidence.callCounts(), "Local routes must not perform Registry SPI IO");

            verifyRoles(clients, application, exports, full, members, reader, resolver, timeout);
            verifyRollingRevision(clients, application, exports, full, members, owners, reader, resolver, timeout);
            verifyAnotherApplication(reader, application, exports.getFirst(), members, resolver, timeout);
            verifyFencing(reader, application, exports, members, owners, resolver, timeout);
            assertEquals(MEMBER_COUNT, memberProbe.count(application));

            long unregisteredAt = System.nanoTime();
            List<CompletableFuture<Void>> removals = new ArrayList<>();
            for (int index = 0; index < MEMBER_COUNT; index++) {
                Client writer = clients.get(index % clients.size());
                ApplicationInstance member = members.get(index);
                ServiceMappingOwner owner = owners.get(index);
                removals.add(writer.registrar.unregister(member)
                        .thenCompose(ignored -> writer.mappings.revoke(owner)).toCompletableFuture());
                removals.add(writer.registrar.unregister(member).toCompletableFuture());
            }
            finish(CompletableFuture.allOf(removals.toArray(CompletableFuture[]::new)), timeout);
            awaitMembers(reader, application, List.of(), notifications, memberProbe, timeout);
            unregistrationMillis = elapsedMillis(unregisteredAt);
            verifyRoutes(resolver, application, exports, List.of(), timeout);
            for (Client client : clients) {
                assertTrue(finish(client.discovery.lookup(application), timeout).instances().isEmpty());
            }
            assertNull(notifications.violation.get());
            assertEquals(0, notifications.failures.get(), "Healthy contract must not report backend failure");
        }
        await(timeout, () -> evidence.operationCounts().values().stream().allMatch(count -> count.pending() == 0));
        Map<String, OperationCounts> operations = evidence.operationCounts();
        operations.forEach((name, count) -> assertEquals(count.calls(), count.successes() + count.failures(), name));
        assertEquals(1, operations.get("mapping.publish").failures(), "Stale-owner rejection must be recorded");
        SubscriptionCounts subscriptions = evidence.subscriptions();
        assertTrue(subscriptions.opened() > 0);
        assertEquals(subscriptions.opened(), subscriptions.closed());
        assertEquals(0, subscriptions.active(), "All observed subscription handles must be closed");
        assertEquals(0, subscriptions.callbacksAfterClose(), "Closed subscriptions must not receive new callbacks");
        assertTrue(subscriptions.logicalChanges() >= 2, "The populated and final empty contents must be observed");
        LOGGER.info("Application registry contract verified: adapter={}, registryClients={}, realContracts={}, "
                        + "memberCounts=0,10,0, memberCountSource={}, spiOperations={}, subscriptions={}, "
                        + "registerConvergenceMillis={}, unregisterConvergenceMillis={}, backendTraffic=not-measured",
                adapter, clients.size(), exports.size(),
                "memory".equals(adapter) ? "in-process-state" : "native-backend-probe",
                operations, subscriptions, registrationMillis, unregistrationMillis);
    }

    private static void verifyRoles(
            List<Client> clients, ApplicationKey application, List<ExportedService> exports,
            ApplicationManifest full, List<ApplicationInstance> members, Client reader,
            ApplicationServiceResolver resolver, Duration timeout) throws Exception {
        int index = MEMBER_COUNT - 1;
        Client writer = clients.get(index % clients.size());
        ApplicationInstance previous = members.get(index);
        ApplicationInstance consumer = new ApplicationInstance(previous.instanceId(), application,
                ApplicationRole.CONSUMER, null, 0, null, Map.of("zone", "contract"));
        finish(writer.registrar.register(consumer), timeout);
        members.set(index, consumer);
        awaitDirectory(reader, application, members, timeout);
        assertFalse(consumer.routableProvider());
        assertNull(consumer.rpcEndpoint());
        assertNull(consumer.manifestRevision());
        verifyRoutes(resolver, application, sample(exports), members.subList(0, index), timeout);
        ApplicationInstance both = provider(application, previous.instanceId(), index,
                ApplicationRole.BOTH, full.revision());
        finish(writer.registrar.register(both), timeout);
        finish(writer.registrar.unregister(consumer), timeout);
        members.set(index, both);
        awaitDirectory(reader, application, members, timeout);
        verifyRoutes(resolver, application, sample(exports), members, timeout);
    }

    private static void verifyRollingRevision(
            List<Client> clients, ApplicationKey application, List<ExportedService> exports,
            ApplicationManifest full, List<ApplicationInstance> members, List<ServiceMappingOwner> owners,
            Client reader, ApplicationServiceResolver resolver, Duration timeout) throws Exception {
        ApplicationManifest rolling = ApplicationManifest.create(application,
                exports.subList(0, ROLLING_CONTRACT_COUNT));
        assertNotEquals(full.revision(), rolling.revision());
        int index = MEMBER_COUNT - 1;
        Client writer = clients.get(index % clients.size());
        finish(writer.manifests.publish(rolling), timeout);
        finish(writer.mappings.publish(owners.get(index), keys(rolling.services())), timeout);
        ApplicationInstance original = members.get(index);
        ApplicationInstance updated = provider(application, original.instanceId(), index,
                original.role(), rolling.revision());
        finish(writer.registrar.register(updated), timeout);
        members.set(index, updated);
        awaitDirectory(reader, application, members, timeout);
        verifyRoutes(resolver, application, exports.subList(0, ROLLING_CONTRACT_COUNT), members, timeout);
        verifyRoutes(resolver, application, exports.subList(ROLLING_CONTRACT_COUNT, CONTRACT_COUNT),
                members.subList(0, index), timeout);
        assertEquals(full, finish(reader.manifests.lookupVerified(application, full.revision()), timeout)
                .orElseThrow());
        assertEquals(rolling, finish(reader.manifests.lookupVerified(application, rolling.revision()), timeout)
                .orElseThrow());
        finish(writer.mappings.publish(owners.get(index), keys(exports)), timeout);
        finish(writer.registrar.register(original), timeout);
        finish(writer.registrar.unregister(updated), timeout);
        members.set(index, original);
        awaitDirectory(reader, application, members, timeout);
        verifyRoutes(resolver, application, sample(exports), members, timeout);
    }

    private static void verifyAnotherApplication(
            Client client, ApplicationKey primary, ExportedService exported, List<ApplicationInstance> primaryMembers,
            ApplicationServiceResolver resolver, Duration timeout) throws Exception {
        ApplicationKey other = new ApplicationKey(primary.namespace(), "other-contract");
        ExportedService otherVersion = ExportedService.fromContract(new ServiceKey(
                Contract01.class.getName(), "2.0.0", exported.serviceKey().group()), Contract01.class);
        ExportedService otherGroup = ExportedService.fromContract(new ServiceKey(
                Contract01.class.getName(), exported.serviceKey().version(), "other-group"), Contract01.class);
        List<ExportedService> otherExports = List.of(exported, otherVersion, otherGroup);
        ApplicationManifest manifest = ApplicationManifest.create(other, otherExports);
        ApplicationInstance member = provider(other, "member-0", MEMBER_COUNT,
                ApplicationRole.PROVIDER, manifest.revision());
        ServiceMappingOwner owner = finish(client.mappings.claim(other, member.instanceId()), timeout);
        try {
            finish(client.manifests.publish(manifest), timeout);
            finish(client.mappings.publish(owner, keys(otherExports)), timeout);
            finish(client.registrar.register(member), timeout);
            ServiceMappingKey key = new ServiceMappingKey(primary.namespace(), exported.serviceKey());
            await(timeout, () -> finish(client.mappings.lookupVerified(key), timeout).applications()
                    .equals(Set.of(primary, other)));
            List<ApplicationInstance> expected = new ArrayList<>(primaryMembers);
            expected.add(member);
            verifyRoutes(resolver, primary, List.of(exported), expected, timeout);
            verifyRoutes(resolver, primary, List.of(otherVersion, otherGroup), List.of(member), timeout);
            assertEquals(Set.copyOf(primaryMembers),
                    Set.copyOf(finish(client.discovery.lookup(primary), timeout).instances()));
        } finally {
            finish(client.registrar.unregister(member), timeout);
            finish(client.mappings.revoke(owner), timeout);
        }
        verifyRoutes(resolver, primary, List.of(exported), primaryMembers, timeout);
        verifyRoutes(resolver, primary, List.of(otherVersion, otherGroup), List.of(), timeout);
    }

    private static void verifyFencing(
            Client client, ApplicationKey application, List<ExportedService> exports,
            List<ApplicationInstance> members, List<ServiceMappingOwner> owners,
            ApplicationServiceResolver resolver, Duration timeout) throws Exception {
        ServiceMappingOwner stale = owners.getFirst();
        ServiceMappingOwner current = finish(client.mappings.claim(application, stale.instanceId()), timeout);
        assertTrue(current.generation() > stale.generation());
        assertThrows(ExecutionException.class,
                () -> finish(client.mappings.publish(stale, keys(exports)), timeout));
        finish(client.mappings.publish(current, keys(exports)), timeout);
        finish(client.mappings.revoke(stale), timeout);
        owners.set(0, current);
        verifyRoutes(resolver, application, sample(exports), members, timeout);
        finish(client.mappings.revoke(current), timeout);
        for (ExportedService exported : sample(exports)) {
            assertEquals(Set.of(application), finish(client.mappings.lookupVerified(
                    new ServiceMappingKey(application.namespace(), exported.serviceKey())), timeout).applications(),
                    "Revoking one owner must retain the other nine owners");
        }
        ServiceMappingOwner replacement = finish(client.mappings.claim(application, stale.instanceId()), timeout);
        assertTrue(replacement.generation() > current.generation());
        finish(client.mappings.publish(replacement, keys(exports)), timeout);
        finish(client.mappings.revoke(current), timeout);
        owners.set(0, replacement);
        verifyRoutes(resolver, application, sample(exports), members, timeout);
    }

    private static void verifyNamespaceIsolation(
            Client client, ApplicationKey application, ExportedService service, Duration timeout) throws Exception {
        ApplicationKey foreign = new ApplicationKey("other-" + application.namespace(), application.applicationName());
        assertForeignNamespaceRejectedOrEmpty(
                () -> finish(client.discovery.lookup(foreign), timeout).instances().isEmpty());
        ServiceMappingKey foreignMapping = new ServiceMappingKey(foreign.namespace(), service.serviceKey());
        assertForeignNamespaceRejectedOrEmpty(
                () -> finish(client.mappings.lookupVerified(foreignMapping), timeout).applications().isEmpty());
    }

    private static void assertForeignNamespaceRejectedOrEmpty(CheckedCondition condition) throws Exception {
        try {
            assertTrue(condition.matches(), "Foreign namespace must not expose an application or mapping");
        } catch (IllegalArgumentException rejected) {
            // Scope-bound adapters reject a foreign namespace before issuing network IO.
        } catch (ExecutionException rejected) {
            assertTrue(rejected.getCause() instanceof IllegalArgumentException,
                    "Namespace isolation must not hide a backend failure");
        }
    }

    private static void closeAll(Set<ApplicationRegistry> registries, Throwable primaryFailure) {
        Throwable failure = primaryFailure;
        for (ApplicationRegistry registry : registries) {
            try {
                registry.close();
            } catch (RuntimeException | Error error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            }
        }
        if (primaryFailure == null && failure instanceof RuntimeException error) {
            throw error;
        }
        if (primaryFailure == null && failure instanceof Error error) {
            throw error;
        }
    }

    private static void verifyRoutes(
            ApplicationServiceResolver resolver, ApplicationKey application, List<ExportedService> exports,
            List<ApplicationInstance> expected, Duration timeout) throws Exception {
        for (ExportedService exported : exports) {
            ServiceMappingKey key = new ServiceMappingKey(application.namespace(), exported.serviceKey());
            await(timeout, () -> Set.copyOf(finish(resolver.refresh(key, exported.schemaFingerprint()), timeout)
                    .providers()).equals(Set.copyOf(expected)));
            assertEquals(ApplicationServiceResolver.Status.HEALTHY,
                    resolver.status(key, exported.schemaFingerprint()));
        }
    }

    private static void awaitMembers(
            Client reader, ApplicationKey application, List<ApplicationInstance> expected,
            Notifications notifications, MemberCountProbe probe, Duration timeout) throws Exception {
        awaitDirectory(reader, application, expected, timeout);
        await(timeout, () -> notifications.latest.get() != null
                && Set.copyOf(notifications.latest.get().instances()).equals(Set.copyOf(expected))
                && probe.count(application) == expected.size());
        assertEquals(expected.size(), probe.count(application));
    }

    private static void awaitDirectory(
            Client reader, ApplicationKey application, List<ApplicationInstance> expected,
            Duration timeout) throws Exception {
        await(timeout, () -> Set.copyOf(finish(reader.discovery.lookup(application), timeout).instances())
                .equals(Set.copyOf(expected)));
    }

    private static void await(Duration timeout, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(POLL_MILLIS);
        }
        assertTrue(condition.matches(), "Application contract did not converge within its bounded timeout");
    }

    private static <T> T finish(CompletionStage<T> operation, Duration timeout) throws Exception {
        return operation.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static long elapsedMillis(long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }

    private static ApplicationInstance provider(
            ApplicationKey application, String instanceId, int index, ApplicationRole role, String revision) {
        return new ApplicationInstance(instanceId, application, role,
                new RpcEndpoint("127.0.0.1", RPC_BASE_PORT + index), 100, revision, Map.of("zone", "contract"));
    }

    private static List<ExportedService> sample(List<ExportedService> exports) {
        return List.of(exports.getFirst(), exports.getLast());
    }

    private static Set<ServiceKey> keys(List<ExportedService> exports) {
        return exports.stream().map(ExportedService::serviceKey).collect(Collectors.toUnmodifiableSet());
    }

    private static List<ExportedService> exports() {
        List<Class<?>> contracts = List.of(
                Contract01.class, Contract02.class, Contract03.class, Contract04.class, Contract05.class,
                Contract06.class, Contract07.class, Contract08.class, Contract09.class, Contract10.class,
                Contract11.class, Contract12.class, Contract13.class, Contract14.class, Contract15.class,
                Contract16.class, Contract17.class, Contract18.class, Contract19.class, Contract20.class,
                Contract21.class, Contract22.class, Contract23.class, Contract24.class, Contract25.class,
                Contract26.class, Contract27.class, Contract28.class, Contract29.class, Contract30.class);
        return contracts.stream().map(api -> ExportedService.fromContract(
                new ServiceKey(api.getName(), "1.0.0", "contract"), api)).toList();
    }

    /**
     * 透明观测 SPI：异步结果保留原对象，取消计为异常终态，同步拒绝立即计为失败。
     * 完成回调可能晚于调用者恢复，因此合同结束前另以有界等待确认计数已结清。
     */
    static <T> T counted(Class<T> spi, T delegate, String prefix, Evidence evidence) {
        return spi.cast(Proxy.newProxyInstance(spi.getClassLoader(), new Class<?>[] {spi}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }
            Operation operation = evidence.start(prefix + '.' + method.getName());
            SubscriptionObservation observation = null;
            Object[] invocationArgs = args;
            if (method.getReturnType() == RegistrySubscription.class) {
                observation = new SubscriptionObservation((ApplicationRegistryListener) args[1], evidence);
                invocationArgs = args.clone();
                invocationArgs[1] = observation;
            }
            Object result;
            try {
                result = method.invoke(delegate, invocationArgs);
            } catch (InvocationTargetException failure) {
                operation.complete(false);
                throw failure.getCause();
            } catch (ReflectiveOperationException | RuntimeException | Error failure) {
                operation.complete(false);
                throw failure;
            }
            if (result instanceof CompletionStage<?> stage) {
                try {
                    stage.whenComplete((value, error) -> operation.complete(error == null));
                } catch (RuntimeException | Error failure) {
                    operation.complete(false);
                    throw failure;
                }
                return stage;
            }
            if (observation != null && result instanceof RegistrySubscription subscription) {
                result = observation.open(subscription);
            } else if (result == null) {
                operation.complete(false);
                throw new IllegalStateException("Registry SPI returned a null completion stage or subscription");
            }
            operation.complete(true);
            return result;
        }));
    }

    /** 每个方法入口及其已观测终态的原子一致快照；pending 不代表后端网络请求。 */
    record OperationCounts(long calls, long successes, long failures, long pending) {
    }

    /**
     * 已返回句柄、正常关闭句柄及回调统计。初始快照不计逻辑变化；
     * A→B→A 是两次相邻状态变化，而不是两个历史唯一内容。
     */
    record SubscriptionCounts(
            long opened, long closed, long active, long snapshotCallbacks, long healthSuccessCallbacks,
            long healthFailureCallbacks, long initialSnapshots, long logicalChanges, long callbacksAfterClose) {
    }

    /** 仅用于共享合同及记录器故障测试，不参与任何生产或原生后端请求。 */
    static final class Evidence {
        private final Map<String, OperationCounter> operations = new ConcurrentHashMap<>();
        private long opened;
        private long closed;
        private long snapshotCallbacks;
        private long healthSuccessCallbacks;
        private long healthFailureCallbacks;
        private long initialSnapshots;
        private long logicalChanges;
        private long callbacksAfterClose;

        private Operation start(String name) {
            return operations.computeIfAbsent(name, ignored -> new OperationCounter()).start();
        }

        Map<String, OperationCounts> operationCounts() {
            Map<String, OperationCounts> result = new TreeMap<>();
            operations.forEach((name, counter) -> result.put(name, counter.snapshot()));
            return Collections.unmodifiableMap(result);
        }

        private Map<String, Long> callCounts() {
            Map<String, Long> result = new TreeMap<>();
            operationCounts().forEach((name, count) -> result.put(name, count.calls()));
            return Collections.unmodifiableMap(result);
        }

        synchronized SubscriptionCounts subscriptions() {
            return new SubscriptionCounts(opened, closed, opened - closed, snapshotCallbacks,
                    healthSuccessCallbacks, healthFailureCallbacks, initialSnapshots, logicalChanges,
                    callbacksAfterClose);
        }

        private synchronized void opened() {
            opened++;
        }

        private synchronized void closed() {
            closed++;
        }

        private synchronized void snapshot(boolean afterClose, boolean initial, boolean changed) {
            snapshotCallbacks++;
            callbacksAfterClose += afterClose ? 1 : 0;
            initialSnapshots += initial ? 1 : 0;
            logicalChanges += changed ? 1 : 0;
        }

        private synchronized void health(boolean afterClose, boolean success) {
            callbacksAfterClose += afterClose ? 1 : 0;
            if (success) {
                healthSuccessCallbacks++;
            } else {
                healthFailureCallbacks++;
            }
        }
    }

    private static final class OperationCounter {
        private long calls;
        private long successes;
        private long failures;

        private synchronized Operation start() {
            calls++;
            return new Operation(this);
        }

        private synchronized void complete(boolean success) {
            if (success) {
                successes++;
            } else {
                failures++;
            }
        }

        private synchronized OperationCounts snapshot() {
            return new OperationCounts(calls, successes, failures, calls - successes - failures);
        }
    }

    private static final class Operation {
        private final OperationCounter counter;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Operation(OperationCounter counter) {
            this.counter = counter;
        }

        private void complete(boolean success) {
            if (completed.compareAndSet(false, true)) {
                counter.complete(success);
            }
        }
    }

    private record SnapshotContents(ApplicationKey application, Set<ApplicationInstance> instances) {
    }

    private static final class SubscriptionObservation implements ApplicationRegistryListener {
        private final ApplicationRegistryListener delegate;
        private final Evidence evidence;
        private final AtomicBoolean closed = new AtomicBoolean();
        private SnapshotContents previous;

        private SubscriptionObservation(ApplicationRegistryListener delegate, Evidence evidence) {
            this.delegate = delegate;
            this.evidence = evidence;
        }

        private RegistrySubscription open(RegistrySubscription subscription) {
            evidence.opened();
            return () -> {
                Operation operation = evidence.start("subscription.close");
                try {
                    subscription.close();
                    if (closed.compareAndSet(false, true)) {
                        evidence.closed();
                    }
                    operation.complete(true);
                } catch (RuntimeException | Error failure) {
                    operation.complete(false);
                    throw failure;
                }
            };
        }

        @Override
        public synchronized void onSnapshot(ApplicationDirectorySnapshot snapshot) {
            SnapshotContents contents = new SnapshotContents(snapshot.application(), Set.copyOf(snapshot.instances()));
            evidence.snapshot(closed.get(), previous == null, previous != null && !previous.equals(contents));
            previous = contents;
            delegate.onSnapshot(snapshot);
        }

        @Override
        public void onSynchronizationSucceeded() {
            evidence.health(closed.get(), true);
            delegate.onSynchronizationSucceeded();
        }

        @Override
        public void onSynchronizationFailed(Throwable error) {
            evidence.health(closed.get(), false);
            delegate.onSynchronizationFailed(error);
        }
    }

    /** 同一测试域内的独立应用 Registry 创建入口。 */
    @FunctionalInterface
    public interface ClientFactory {
        /**
         * 创建一个由本 TestKit 关闭的客户端。
         *
         * @return 新的应用级 Registry
         * @throws Exception 后端连接或客户端创建失败
         */
        ApplicationRegistry create() throws Exception;
    }

    /** 绕过被测 SPI 直接观测后端应用成员数；不是网络流量计数器。 */
    @FunctionalInterface
    public interface MemberCountProbe {
        /**
         * 读取指定应用的原生成员数，连接失败不能转成零。
         *
         * @param application 已隔离的测试应用
         * @return 服务端真实记录数，或 Memory 的进程内目录数
         * @throws Exception 读取失败
         */
        long count(ApplicationKey application) throws Exception;
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean matches() throws Exception;
    }

    private static final class Client {
        private final ApplicationDiscovery discovery;
        private final ApplicationRegistrar registrar;
        private final ApplicationManifestRepository manifests;
        private final ServiceMappingRepository mappings;

        private Client(ApplicationRegistry registry, Evidence evidence) {
            assertTrue(registry.capabilities().supports(RegistryCapability.REGISTRATION));
            assertTrue(registry.capabilities().supports(RegistryCapability.SUBSCRIPTION));
            assertTrue(registry instanceof ApplicationMetadataBackend);
            ApplicationMetadataBackend metadata = (ApplicationMetadataBackend) registry;
            discovery = counted(ApplicationDiscovery.class, registry, "application", evidence);
            registrar = counted(ApplicationRegistrar.class, registry.registrar().orElseThrow(), "member", evidence);
            manifests = counted(ApplicationManifestRepository.class, metadata.manifestRepository(),
                    "manifest", evidence);
            mappings = counted(ServiceMappingRepository.class, metadata.serviceMappingRepository(),
                    "mapping", evidence);
        }
    }

    private static final class Notifications implements ApplicationRegistryListener {
        private final ApplicationKey application;
        private final AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();
        private final AtomicReference<String> violation = new AtomicReference<>();
        private final AtomicInteger snapshots = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        private Notifications(ApplicationKey application) {
            this.application = application;
        }

        @Override
        public synchronized void onSnapshot(ApplicationDirectorySnapshot snapshot) {
            ApplicationDirectorySnapshot previous = latest.get();
            if (!application.equals(snapshot.application())
                    || previous != null && snapshot.revision() < previous.revision()) {
                violation.compareAndSet(null, "Application identity or monotonic subscription revision violated");
            }
            latest.set(snapshot);
            snapshots.incrementAndGet();
        }

        @Override
        public void onSynchronizationSucceeded() {
            successes.incrementAndGet();
        }

        @Override
        public void onSynchronizationFailed(Throwable error) {
            failures.incrementAndGet();
        }
    }

    private interface Contract01 {
        String call01(String request);
    }
    private interface Contract02 {
        String call02(String request);
    }
    private interface Contract03 {
        String call03(String request);
    }
    private interface Contract04 {
        String call04(String request);
    }
    private interface Contract05 {
        String call05(String request);
    }
    private interface Contract06 {
        String call06(String request);
    }
    private interface Contract07 {
        String call07(String request);
    }
    private interface Contract08 {
        String call08(String request);
    }
    private interface Contract09 {
        String call09(String request);
    }
    private interface Contract10 {
        String call10(String request);
    }
    private interface Contract11 {
        String call11(String request);
    }
    private interface Contract12 {
        String call12(String request);
    }
    private interface Contract13 {
        String call13(String request);
    }
    private interface Contract14 {
        String call14(String request);
    }
    private interface Contract15 {
        String call15(String request);
    }
    private interface Contract16 {
        String call16(String request);
    }
    private interface Contract17 {
        String call17(String request);
    }
    private interface Contract18 {
        String call18(String request);
    }
    private interface Contract19 {
        String call19(String request);
    }
    private interface Contract20 {
        String call20(String request);
    }
    private interface Contract21 {
        String call21(String request);
    }
    private interface Contract22 {
        String call22(String request);
    }
    private interface Contract23 {
        String call23(String request);
    }
    private interface Contract24 {
        String call24(String request);
    }
    private interface Contract25 {
        String call25(String request);
    }
    private interface Contract26 {
        String call26(String request);
    }
    private interface Contract27 {
        String call27(String request);
    }
    private interface Contract28 {
        String call28(String request);
    }
    private interface Contract29 {
        String call29(String request);
    }
    private interface Contract30 {
        String call30(String request);
    }
}
