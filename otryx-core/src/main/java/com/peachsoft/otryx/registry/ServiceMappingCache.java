package com.peachsoft.otryx.registry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 受限本地 ServiceMapping 缓存。数据面只读取已完成快照，控制面单飞刷新。
 *
 * <p>接收点独立校验业务键与 revision 下限；安全失效撤销可读值但保留下限。
 * 普通网络失败只允许有限 LKG。取消、超时和关闭先提交本地终态，再通知调用方。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class ServiceMappingCache implements AutoCloseable, RegistrySecuritySource {
    private static final int MAX_ENTRIES = 2048;
    private static final int MAX_CONCURRENT_REFRESH = 64;
    private static final long REFRESH_TIMEOUT_SECONDS = 20L;
    private final ServiceMappingRepository repository;
    private final long staleNanos;
    private final long retryNanos;
    private final LongSupplier clock;
    private final Map<ServiceMappingKey, Entry> entries = new HashMap<>();
    private boolean closed;
    private int running;
    private long generation;

    /**
     * 使用单调时间源建立缓存，所有时间参数必须非负且可转换为纳秒。
     *
     * @param repository 后端映射仓库
     * @param maxStale 远端失败之后的最大 LKG 使用时长
     * @param minRetry 控制面刷新失败的最短退避时间
     */
    public ServiceMappingCache(
            ServiceMappingRepository repository, Duration maxStale, Duration minRetry) {
        this(repository, maxStale, minRetry, System::nanoTime);
    }

    /**
     * 注入单调时钟方便复现失联、负缓存和并发时序。
     *
     * @param repository 应用服务映射仓库
     * @param maxStale 失败后允许的最长 LKG 时长
     * @param minRetry 失败后的最小重试间隔
     * @param clock 单调时间来源
     */
    public ServiceMappingCache(
            ServiceMappingRepository repository, Duration maxStale, Duration minRetry, LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        staleNanos = duration(maxStale);
        retryNanos = duration(minRetry);
    }

    /** 返回实际被调用 Repository 的全部 Mapping 来源。 */
    @Override
    public RegistrySecurityDescriptor securityDescriptor() {
        return RegistrySecurityDescriptor.from(repository, RegistryChannel.MAPPING);
    }

    /**
     * 返回当前可用的不可变快照；首次未就绪、安全失效或普通失败超期返回空值。
     *
     * @param key 服务映射键
     * @return 本地快照；没有有效快照不等于合法空目录
     */
    public synchronized Optional<ServiceMappingSnapshot> current(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (closed || entry == null || entry.snapshot == null || (entry.failureSince != null
                && clock.getAsLong() - entry.failureSince >= staleNanos)) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot);
    }

    /**
     * 控制面单飞刷新；同一 key 的调用共享任务，取消会取消共享读取。
     *
     * @param key 待刷新映射
     * @return 完整新快照或异常
     */
    public CompletionStage<ServiceMappingSnapshot> refresh(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        RefreshFuture target;
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Mapping cache closed"));
            }
            Entry entry = entries.get(key);
            if (entry == null) {
                if (entries.size() >= MAX_ENTRIES) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Mapping entry limit exceeded"));
                }
                entry = new Entry();
                entries.put(key, entry);
            }
            if (entry.pending != null) {
                return entry.pending;
            }
            if (entry.lastFailure != null && clock.getAsLong() - entry.lastFailure < retryNanos) {
                return CompletableFuture.failedFuture(new IllegalStateException("Mapping refresh rate limited"));
            }
            if (running >= MAX_CONCURRENT_REFRESH) {
                return CompletableFuture.failedFuture(new IllegalStateException("Mapping refresh capacity exceeded"));
            }
            target = new RefreshFuture(key, entry, generation);
            entry.pending = target;
            running++;
        }
        target.orTimeout(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletionStage<ServiceMappingSnapshot> source;
        try {
            source = repository.lookupVerified(key);
            if (source == null) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
            }
        } catch (Throwable error) {
            target.completeExceptionally(error);
            return target;
        }
        CompletableFuture<?> cancellable = source.toCompletableFuture();
        boolean active;
        synchronized (this) {
            active = target.entry.pending == target;
            if (active) {
                target.source = cancellable;
            }
        }
        if (!active) {
            RegistryFailures.cancel(cancellable);
        }
        source.whenComplete((snapshot, error) -> {
            if (error == null) {
                finish(target, snapshot, null);
            } else {
                target.completeExceptionally(error);
            }
        });
        return target;
    }

    private boolean finish(RefreshFuture target, ServiceMappingSnapshot snapshot, Throwable error) {
        Throwable failure = RegistryFailures.unwrap(error);
        synchronized (this) {
            Entry entry = target.entry;
            if (entry.pending != target || target.generation != generation) {
                return false;
            }
            if (closed) {
                failure = new IllegalStateException("Mapping cache closed");
            } else if (failure == null) {
                if (snapshot == null) {
                    failure = new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
                } else if (!target.key.equals(snapshot.key())) {
                    failure = new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
                } else if (snapshot.revision() < entry.minimumVerifiedRevision) {
                    failure = new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
                }
            }
            if (failure == null) {
                entry.minimumVerifiedRevision = Math.max(entry.minimumVerifiedRevision, snapshot.revision());
                entry.snapshot = snapshot;
                entry.failureSince = null;
                entry.lastFailure = null;
            } else {
                long now = clock.getAsLong();
                entry.lastFailure = now;
                if (entry.failureSince == null) {
                    entry.failureSince = now;
                }
                if (failure instanceof RegistrySecurityException) {
                    entry.snapshot = null;
                }
            }
            entry.pending = null;
            running--;
        }
        if (failure != null) {
            target.cancelSource();
        }
        return target.publish(snapshot, failure);
    }

    /** 安全失效保留每键最低已验证 revision，旧 generation 不能更新值或下限。 */
    void invalidateForSecurity(RegistrySecurityFailure reason) {
        List<RefreshFuture> pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            generation++;
            pending = entries.values().stream().map(entry -> entry.pending).filter(Objects::nonNull).toList();
            entries.values().forEach(entry -> {
                entry.pending = null;
                entry.snapshot = null;
                entry.failureSince = null;
                entry.lastFailure = null;
            });
            running = 0;
        }
        pending.forEach(target -> {
            target.cancelSource();
            target.publish(null, new RegistrySecurityException(reason));
        });
    }

    private static long duration(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Duration must not be negative");
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Duration exceeds range");
        }
    }

    /** 禁止迟到刷新发布并释放本地快照；不关闭调用方拥有的 Repository。 */
    @Override
    public void close() {
        List<RefreshFuture> pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pending = entries.values().stream().map(entry -> entry.pending).filter(Objects::nonNull).toList();
            entries.values().forEach(entry -> entry.pending = null);
            entries.clear();
            running = 0;
        }
        pending.forEach(target -> {
            target.cancelSource();
            target.publish(null, new IllegalStateException("Mapping cache closed"));
        });
    }

    private static final class Entry {
        private ServiceMappingSnapshot snapshot;
        private Long failureSince;
        private Long lastFailure;
        private long minimumVerifiedRevision;
        private RefreshFuture pending;
    }

    private final class RefreshFuture extends CompletableFuture<ServiceMappingSnapshot> {
        private final ServiceMappingKey key;
        private final Entry entry;
        private final long generation;
        private volatile CompletableFuture<?> source;

        private RefreshFuture(ServiceMappingKey key, Entry entry, long generation) {
            this.key = key;
            this.entry = entry;
            this.generation = generation;
        }

        @Override
        public boolean complete(ServiceMappingSnapshot ignored) {
            return false;
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            return finish(this, null, Objects.requireNonNull(failure, "failure"));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return finish(this, null, new CancellationException("Mapping refresh cancelled")) || isCancelled();
        }

        private boolean publish(ServiceMappingSnapshot snapshot, Throwable failure) {
            return failure == null ? super.complete(snapshot) : super.completeExceptionally(failure);
        }

        private void cancelSource() {
            RegistryFailures.cancel(source);
        }
    }
}
