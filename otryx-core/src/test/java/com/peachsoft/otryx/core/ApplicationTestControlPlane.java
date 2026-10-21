package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationDiscovery;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 为 Core 行为测试提供真实的应用成员、Manifest 和 Mapping 控制面。
 *
 * <p>每个测试拥有隔离的内存仓库，关闭时先终止所有成员协调器，再释放缓存和仓库。
 * 目录故障仅替换应用查询的完成结果，不绕过契约校验或创建接口级发现路径。
 * 数据面目录的反射读取只用于等待异步首刷与状态迁移，避免试调用污染业务计数。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 07:01
 */
final class ApplicationTestControlPlane implements AutoCloseable {

    static final String NAMESPACE = "core-tests";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(3);
    private static final long AWAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long POLL_MILLIS = 1L;
    private static final Field CLIENT_DIRECTORIES = clientDirectoriesField();

    final MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
    final ApplicationManifestRepository manifests = registry.manifestRepository();
    final ServiceMappingRepository mappings = registry.serviceMappingRepository();
    final ServiceMappingCache mappingCache = new ServiceMappingCache(mappings, CONTROL_TIMEOUT, Duration.ZERO);
    final ManifestSnapshotCache manifestCache = new ManifestSnapshotCache(manifests);
    final ApplicationServiceResolver resolver = new ApplicationServiceResolver(
            mappingCache, new ControlledDiscovery(), manifestCache, CONTROL_TIMEOUT);

    private final List<ApplicationMembershipCoordinator> memberships = new ArrayList<>();
    private final AtomicBoolean discoveryFailed = new AtomicBoolean();

    ApplicationMembershipCoordinator membership() {
        return membership(registry, CONTROL_TIMEOUT);
    }

    ApplicationMembershipCoordinator membership(ApplicationRegistrar registrar, Duration timeout) {
        int sequence = memberships.size() + 1;
        ApplicationMembershipCoordinator membership = new ApplicationMembershipCoordinator(
                new ApplicationKey(NAMESPACE, "application-" + sequence),
                "instance-" + sequence, registrar, manifests, mappings, timeout);
        memberships.add(membership);
        return membership;
    }

    OtryxRpcClient.Builder clientBuilder() {
        return OtryxRpcClient.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationControlPlane(resolver, membership(), NAMESPACE);
    }

    ApplicationMembershipCoordinator publishProvider(Class<?> api, String version, String group, RpcEndpoint endpoint) {
        ApplicationMembershipCoordinator provider = membership();
        ServiceKey key = new ServiceKey(api.getName(), version, group);
        provider.prepareProvider(List.of(ExportedService.fromContract(key, api))).toCompletableFuture().join();
        provider.providerReady(endpoint).toCompletableFuture().join();
        return provider;
    }

    void failDiscovery() {
        discoveryFailed.set(true);
    }

    void recoverDiscovery() {
        discoveryFailed.set(false);
    }

    static void awaitReady(OtryxRpcClient client, ServiceKey key) {
        try {
            client.membershipReady().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for application membership", error);
        } catch (Exception error) {
            throw new AssertionError("Application membership did not become ready", error);
        }
        await(() -> directory(client, key).snapshot().length > 0, "Application provider route did not become ready");
    }

    static void awaitStatus(OtryxRpcClient client, ServiceKey key, ServiceDirectory.Status expected) {
        await(() -> directory(client, key).status() == expected,
                "Application directory status did not become " + expected);
    }

    static void awaitEmpty(OtryxRpcClient client, ServiceKey key) {
        await(() -> {
            ServiceDirectory directory = directory(client, key);
            return directory.status() == ServiceDirectory.Status.HEALTHY && directory.snapshot().length == 0;
        }, "Application directory did not become a healthy empty snapshot");
    }

    static void await(BooleanSupplier condition, String message) {
        long started = System.nanoTime();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - started >= AWAIT_TIMEOUT_NANOS) {
                throw new AssertionError(message);
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for application control plane", error);
            }
        }
    }

    private static Field clientDirectoriesField() {
        try {
            Field field = OtryxRpcClient.class.getDeclaredField("directories");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static ServiceDirectory directory(OtryxRpcClient client, ServiceKey key) {
        try {
            Object value = CLIENT_DIRECTORIES.get(client);
            if (!(value instanceof Map<?, ?> directories)) {
                throw new AssertionError("Client directory storage was not available");
            }
            Object directory = directories.get(key);
            if (!(directory instanceof ServiceDirectory selected)) {
                throw new AssertionError("Client reference has no application directory: " + key);
            }
            return selected;
        } catch (IllegalAccessException error) {
            throw new AssertionError("Client directory could not be inspected", error);
        }
    }

    @Override
    public void close() {
        AssertionError cleanupFailure = null;
        for (ApplicationMembershipCoordinator membership : memberships) {
            try {
                membership.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                cleanupFailure = recordFailure(cleanupFailure, error);
            } catch (Exception error) {
                cleanupFailure = recordFailure(cleanupFailure, error);
            }
        }
        resolver.close();
        manifestCache.close();
        mappingCache.close();
        registry.close();
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private static AssertionError recordFailure(AssertionError failure, Exception error) {
        if (failure == null) {
            return new AssertionError("Application test control plane cleanup failed", error);
        }
        failure.addSuppressed(error);
        return failure;
    }

    private final class ControlledDiscovery implements ApplicationDiscovery {
        @Override
        public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
            return discoveryFailed.get()
                    ? CompletableFuture.failedFuture(new RpcUnavailableException("Application directory disconnected"))
                    : registry.lookup(application);
        }

        @Override
        public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
            return registry.subscribe(application, listener);
        }
    }
}
