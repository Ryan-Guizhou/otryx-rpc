package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.core.ApplicationMembershipCoordinator;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 为进程内 RPC 基准管理真实的应用成员、元数据缓存及有界就绪等待。
 *
 * <p>Provider 和纯 Consumer 使用独立身份并共享同一个内存控制面。
 * 预热探测只用于可重复的 echo/no-op 基准调用，测量前结束；测量阶段不主动刷新远端目录。
 * 使用方先关闭 Client/Server，再关闭本对象以注销成员并回收缓存。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 07:02
 */
final class BenchmarkApplicationControlPlane implements AutoCloseable {

    private static final String REGISTRY_EXTENSION = "memory";
    private static final String PROVIDER_APPLICATION = "benchmark-provider";
    private static final String CONSUMER_APPLICATION = "benchmark-consumer";
    private static final String PROVIDER_INSTANCE = "provider-1";
    private static final String CONSUMER_INSTANCE = "consumer-1";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(3);
    private static final long READY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long READY_POLL_MILLIS = 10L;
    private static final long CLOSE_TIMEOUT_SECONDS = 4L;

    private final String namespace;
    private final ApplicationRegistry registry;
    private final ServiceMappingCache mappingCache;
    private final ManifestSnapshotCache manifestCache;
    private final ApplicationServiceResolver resolver;
    private final ApplicationMembershipCoordinator provider;
    private final ApplicationMembershipCoordinator consumer;

    BenchmarkApplicationControlPlane(String namespace) {
        this.namespace = namespace;
        registry = ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
                .getExtension(REGISTRY_EXTENSION)
                .create(new RegistryOptions(List.of(), namespace, Map.of()));
        if (!(registry instanceof ApplicationMetadataBackend metadata)) {
            registry.close();
            throw new IllegalStateException("Benchmark application registry has no metadata backend");
        }
        mappingCache = new ServiceMappingCache(
                metadata.serviceMappingRepository(), CONTROL_TIMEOUT, Duration.ZERO);
        manifestCache = new ManifestSnapshotCache(metadata.manifestRepository());
        resolver = new ApplicationServiceResolver(mappingCache, registry, manifestCache, CONTROL_TIMEOUT);
        provider = new ApplicationMembershipCoordinator(
                new ApplicationKey(namespace, PROVIDER_APPLICATION), PROVIDER_INSTANCE,
                registry.registrar().orElseThrow(), metadata.manifestRepository(),
                metadata.serviceMappingRepository(), CONTROL_TIMEOUT);
        consumer = new ApplicationMembershipCoordinator(
                new ApplicationKey(namespace, CONSUMER_APPLICATION), CONSUMER_INSTANCE,
                registry.registrar().orElseThrow(), metadata.manifestRepository(),
                metadata.serviceMappingRepository(), CONTROL_TIMEOUT);
    }

    ApplicationMembershipCoordinator provider() {
        return provider;
    }

    OtryxRpcClient.Builder clientBuilder() {
        return OtryxRpcClient.builder().applicationControlPlane(resolver, consumer, namespace);
    }

    void warmup(OtryxRpcClient client, Runnable probe) throws Exception {
        client.membershipReady().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long started = System.nanoTime();
        CompletionException lastFailure = null;
        while (System.nanoTime() - started < READY_TIMEOUT_NANOS) {
            try {
                probe.run();
                return;
            } catch (CompletionException error) {
                if (!(error.getCause() instanceof RpcUnavailableException)) {
                    throw error;
                }
                lastFailure = error;
            }
            Thread.sleep(READY_POLL_MILLIS);
        }
        throw new IllegalStateException("Benchmark application route did not become ready", lastFailure);
    }

    @Override
    public void close() {
        RuntimeException cleanupFailure = null;
        for (ApplicationMembershipCoordinator membership : List.of(consumer, provider)) {
            try {
                membership.shutdown().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    private static RuntimeException recordFailure(RuntimeException failure, Exception error) {
        if (failure == null) {
            return new IllegalStateException("Benchmark application control plane cleanup failed", error);
        }
        failure.addSuppressed(error);
        return failure;
    }
}
