package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Provider 与 Consumer 共享的应用成员生命周期与控制面发布协调器。
 *
 * <p>同一进程持有一个稳定 ApplicationKey/instanceId，Provider、Consumer、BOTH
 * 不重复注册应用成员。先发布 Manifest 与带 fencing 的 ServiceMapping，
 * Listener Ready 后再发布可路由实例。注销仅撤销本实例的映射所有权；
 * Consumer-only 不发布虚假的 RPC Endpoint。
 *
 * <p>外部控制面工作在有界独立单线程执行器上串行提交，不阻塞 EventLoop。
 * 远端 Stage 采用独立的超时副本，完成后才允许更新本地状态。
 * 本组件不拥有由上层注入的 Registry/Manifest/Mapping 后端的关闭权。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class ApplicationMembershipCoordinator implements AutoCloseable {

    private static final int MAX_PENDING_COMMANDS = 64;
    private static final int MAX_CONTROL_QUEUE = 16;
    private static final long MAX_OPERATION_MILLIS = 30_000L;

    private final ApplicationKey application;
    private final String instanceId;
    private final ApplicationRegistrar registrar;
    private final ApplicationManifestRepository manifests;
    private final ServiceMappingRepository mappings;
    private final long timeoutMillis;
    private final ThreadPoolExecutor control = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_CONTROL_QUEUE),
            Thread.ofPlatform().name("otryx-membership-control").daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Object sequenceLock = new Object();

    private CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> shutdown;
    private boolean consumerActive;
    private boolean providerActive;
    private ServiceMappingOwner owner;
    private ApplicationManifest manifest;
    private ApplicationInstance live;

    /**
     * 创建有界应用级控制面。所有外部仓库由调用方负责关闭。
     *
     * @param application 可信配置的应用身份
     * @param instanceId 进程生命周期内稳定的实例 ID
     * @param registrar 应用级成员发布 SPI
     * @param manifests 内容寻址元数据仓库
     * @param mappings 带所有权的应用映射仓库
     * @param timeout 每个异步后端操作的最大等待时间
     */
    public ApplicationMembershipCoordinator(
            ApplicationKey application, String instanceId,
            ApplicationRegistrar registrar, ApplicationManifestRepository manifests,
            ServiceMappingRepository mappings, Duration timeout) {
        this.application = Objects.requireNonNull(application, "application");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMillis(MAX_OPERATION_MILLIS)) > 0) {
            throw new IllegalArgumentException("Invalid membership control timeout");
        }
        this.timeoutMillis = Math.max(1L, timeout.toMillis());
        this.instanceId = new ApplicationInstance(
                instanceId, application, ApplicationRole.CONSUMER, null, 0, null, Map.of()).instanceId();
    }

    /**
     * 注册无 RPC Endpoint 的 Consumer；Provider 已就绪时原子升级为 BOTH。
     *
     * @return 成员注册的非阻塞结果
     */
    public CompletionStage<Void> startConsumer() {
        return submit(() -> {
            if (consumerActive) {
                return CompletableFuture.completedFuture(null);
            }
            ApplicationInstance next = providerActive
                    ? instance(ApplicationRole.BOTH, live.rpcEndpoint(), manifest.revision())
                    : instance(ApplicationRole.CONSUMER, null, null);
            return bounded(registrar.register(next)).thenRun(() -> {
                consumerActive = true;
                live = next;
            });
        }, false);
    }

    /**
     * 在 Listener 开放前发布 Manifest 与实例持有的完整 ServiceMapping 集合。
     *
     * @param exports 已完成实际业务绑定的 RPC 接口清单
     * @return 两个仓库均确认发布后完成
     */
    public CompletionStage<Void> prepareProvider(Collection<ExportedService> exports) {
        Objects.requireNonNull(exports, "exports");
        Collection<ExportedService> copied = java.util.List.copyOf(exports);
        return submit(() -> {
            if (owner != null || providerActive) {
                return CompletableFuture.failedFuture(new IllegalStateException("Provider already prepared"));
            }
            ApplicationManifest candidate = ApplicationManifest.create(application, copied);
            Set<ServiceKey> services = candidate.services().stream()
                    .map(ExportedService::serviceKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return bounded(manifests.publish(candidate))
                    .thenCompose(ignored -> bounded(mappings.claim(application, instanceId)))
                    .thenCompose(claim -> bounded(mappings.publish(claim, services))
                            .handle((ignored, error) -> {
                                if (error == null) {
                                    owner = claim;
                                    manifest = candidate;
                                    return CompletableFuture.<Void>completedFuture(null);
                                }
                                return bounded(mappings.revoke(claim)).<Void>handle((rolledBack, rollbackError) -> {
                                    if (rollbackError != null) {
                                        error.addSuppressed(rollbackError);
                                    }
                                    throw new CompletionException(error);
                                });
                            }).thenCompose(stage -> stage));
        }, false);
    }

    /**
     * Listener 真实监听成功后注册可路由 Provider 成员，Consumer 已启动则为 BOTH。
     *
     * @param endpoint 已经 Ready 的真实 RPC Endpoint
     * @return 应用成员已可被发现的确认结果
     */
    public CompletionStage<Void> providerReady(RpcEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        return submit(() -> {
            if (owner == null || manifest == null || providerActive) {
                return CompletableFuture.failedFuture(new IllegalStateException("Provider not prepared"));
            }
            if (com.peachsoft.otryx.registry.VerifiedProviderSnapshot.invalidEndpoint(endpoint)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Provider endpoint not routable"));
            }
            ApplicationInstance next = instance(
                    consumerActive ? ApplicationRole.BOTH : ApplicationRole.PROVIDER, endpoint, manifest.revision());
            return bounded(registrar.register(next)).thenRun(() -> {
                live = next;
                providerActive = true;
            });
        }, false);
    }

    /**
     * 撤销本进程 Provider 发布；BOTH 自动降级为真实 Consumer-only 成员。
     *
     * @return 注销和 fencing 所有权撤销结果
     */
    public CompletionStage<Void> stopProvider() {
        return submit(this::stopProviderInternal, false);
    }

    private CompletionStage<Void> stopProviderInternal() {
        if (owner == null) {
            return CompletableFuture.completedFuture(null);
        }
        ServiceMappingOwner activeOwner = owner;
        ApplicationInstance remaining = consumerActive
                ? instance(ApplicationRole.CONSUMER, null, null) : null;
        CompletionStage<Void> membership = providerActive
                ? bounded(remaining != null ? registrar.register(remaining) : registrar.unregister(live))
                : CompletableFuture.completedFuture(null);
        return membership.thenCompose(ignored -> {
            providerActive = false;
            live = remaining;
            return bounded(mappings.revoke(activeOwner));
        }).thenRun(() -> {
            owner = null;
            manifest = null;
        });
    }

    /**
     * 注销本进程 Consumer，BOTH 自动降级为 Provider-only 成员。
     *
     * @return 角色变更结果
     */
    public CompletionStage<Void> stopConsumer() {
        return submit(this::stopConsumerInternal, false);
    }

    private CompletionStage<Void> stopConsumerInternal() {
        if (!consumerActive) {
            return CompletableFuture.completedFuture(null);
        }
        ApplicationInstance remaining = providerActive
                ? instance(ApplicationRole.PROVIDER, live.rpcEndpoint(), manifest.revision()) : null;
        CompletionStage<Void> change = remaining == null
                ? bounded(registrar.unregister(live)) : bounded(registrar.register(remaining));
        return change.thenRun(() -> {
            consumerActive = false;
            live = remaining;
        });
    }

    /**
     * 统一撤销 Provider/Consumer 的应用成员及本实例映射，关闭后拒绝新命令。
     *
     * <p>即使成员注销失败仍尝试撤销映射；结果异常说明外部后端状态需排障，
     * 不得宣称清理成功。重复调用返回同一个完成结果。
     *
     * @return 有界终态的关闭 Stage
     */
    public CompletionStage<Void> shutdown() {
        synchronized (sequenceLock) {
            if (shutdown != null) {
                return shutdown;
            }
            closing.set(true);
            shutdown = submit(() -> {
                CompletionStage<Void> remove = live == null
                        ? CompletableFuture.completedFuture(null) : bounded(registrar.unregister(live));
                return remove.handle((ignored, registrationError) -> registrationError)
                        .thenCompose(registrationError -> {
                            CompletionStage<Void> revoke = owner == null
                                    ? CompletableFuture.completedFuture(null)
                                    : bounded(mappings.revoke(owner));
                            return revoke.handle((ignored, mappingError) -> {
                                if (registrationError != null || mappingError != null) {
                                    IllegalStateException failure =
                                            new IllegalStateException("Membership shutdown incomplete");
                                    if (registrationError != null) {
                                        failure.addSuppressed(registrationError);
                                    }
                                    if (mappingError != null) {
                                        failure.addSuppressed(mappingError);
                                    }
                                    throw failure;
                                }
                                live = null;
                                owner = null;
                                manifest = null;
                                providerActive = false;
                                consumerActive = false;
                                return null;
                            });
                        });
            }, true).toCompletableFuture();
            shutdown.whenComplete((ignored, error) -> control.shutdown());
            return shutdown;
        }
    }

    /**
     * 异步启动统一关闭；需要确认清理终态应等待 shutdown()。
     */
    @Override
    public void close() {
        shutdown();
    }

    private ApplicationInstance instance(ApplicationRole role, RpcEndpoint endpoint, String revision) {
        return new ApplicationInstance(instanceId, application, role,
                endpoint, endpoint == null ? 0 : 100, revision, Map.of());
    }

    private <T> CompletionStage<T> bounded(CompletionStage<T> stage) {
        return Objects.requireNonNull(stage, "control operation").toCompletableFuture()
                .copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private <T> CompletionStage<T> submit(Supplier<CompletionStage<T>> action, boolean allowClosing) {
        Objects.requireNonNull(action, "action");
        synchronized (sequenceLock) {
            if (!allowClosing && closing.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Membership control closed"));
            }
            int pending = queued.incrementAndGet();
            int limit = allowClosing ? MAX_PENDING_COMMANDS + 1 : MAX_PENDING_COMMANDS;
            if (pending > limit) {
                queued.decrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Membership control unavailable"));
            }
            CompletableFuture<T> next = sequence.handleAsync((ignored, error) -> null, control)
                    .thenCompose(ignored -> {
                        if (!allowClosing && closing.get()) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("Membership coordinator closed"));
                        }
                        try {
                            return Objects.requireNonNull(action.get(), "Control stage").toCompletableFuture();
                        } catch (Throwable error) {
                            return CompletableFuture.failedFuture(error);
                        }
                    });
            sequence = next.handle((value, error) -> null);
            next.whenComplete((value, error) -> queued.decrementAndGet());
            return next;
        }
    }
}
