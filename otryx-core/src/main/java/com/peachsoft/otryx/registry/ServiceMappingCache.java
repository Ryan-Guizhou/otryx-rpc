package com.peachsoft.otryx.registry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 受限本地 ServiceMapping 缓存。数据面只读取已完成快照，控制面单飞刷新。
 *
 * <p>未获取首次快照与可信空快照相互区分；显式刷新失败只能在有限 LKG 窗口内
 * 使用旧数据。每个 Key 的刷新间隔受限，关闭时禁止新刷新和迟到快照发布。
 * 此组件由上层控制面驱动 refresh，不能在 RPC 调用热路径发起远程 IO。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class ServiceMappingCache implements AutoCloseable {

    private static final int MAX_ENTRIES = 2048;
    private static final int MAX_CONCURRENT_REFRESH = 64;
    private static final long REFRESH_TIMEOUT_SECONDS = 20L;
    private final ServiceMappingRepository repository;
    private final long staleNanos;
    private final long retryNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<ServiceMappingKey, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger allocated = new AtomicInteger();

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
            ServiceMappingRepository repository,
            Duration maxStale,
            Duration minRetry,
            LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        staleNanos = duration(maxStale);
        retryNanos = duration(minRetry);
    }

    /**
     * 返回当前可用的不可变快照；首次未就绪/失败超期返回空 Optional。
     *
     * @param key 服务映射键
     * @return 本地快照；没有有效快照不等于合法空目录
     */
    public java.util.Optional<ServiceMappingSnapshot> current(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) {
            return java.util.Optional.empty();
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            return java.util.Optional.empty();
        }
        State state = entry.state.get();
        if (state.snapshot() == null || (state.failureSince() != null
                && elapsed(clock.getAsLong(), state.failureSince()) >= staleNanos)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(state.snapshot());
    }

    /**
     * 控制面请求刷新，同一 key 只允许一个未决刷新；后续调用共享同一 Future。
     *
     * @param key 待刷新映射
     * @return 完整新快照或异常
     */
    public CompletionStage<ServiceMappingSnapshot> refresh(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Mapping cache closed"));
        }
        Entry entry = entries.computeIfAbsent(key, ignored -> {
            if (allocated.incrementAndGet() > MAX_ENTRIES) {
                allocated.decrementAndGet();
                throw new IllegalStateException("Mapping cache entry limit exceeded");
            }
            return new Entry();
        });
        CompletableFuture<ServiceMappingSnapshot> future;
        synchronized (entry) {
            if (entry.inflight != null) {
                return entry.inflight;
            }
            long now = clock.getAsLong();
            if (entry.lastFailure != null && elapsed(now, entry.lastFailure) < retryNanos) {
                return CompletableFuture.failedFuture(new IllegalStateException("Mapping refresh rate limited"));
            }
            if (running.incrementAndGet() > MAX_CONCURRENT_REFRESH) {
                running.decrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Mapping refresh capacity exceeded"));
            }
            future = new CompletableFuture<>();
            entry.inflight = future;
        }
        if (closed.get()) {
            complete(entry, future, null, new IllegalStateException("Mapping cache closed"));
            return future;
        }
        CompletionStage<ServiceMappingSnapshot> source;
        try {
            source = Objects.requireNonNull(repository.lookupVerified(key), "lookup result");
        } catch (Throwable error) {
            complete(entry, future, null, error);
            return future;
        }
        source.whenComplete((snapshot, error) -> complete(entry, future, snapshot, error));
        if (!future.isDone()) {
            CompletableFuture.delayedExecutor(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() ->
                    complete(entry, future, null, new TimeoutException("Mapping refresh timed out")));
        }
        return future;
    }

    private void complete(
            Entry entry,
            CompletableFuture<ServiceMappingSnapshot> future,
            ServiceMappingSnapshot snapshot,
            Throwable error) {
        ServiceMappingSnapshot valid = snapshot;
        Throwable failure = error;
        synchronized (entry) {
            if (entry.inflight != future) {
                return;
            }
            if (closed.get()) {
                failure = new IllegalStateException("Mapping cache closed");
            }
            if (failure != null || valid == null) {
                if (failure == null) {
                    failure = new IllegalStateException("Null mapping snapshot");
                }
                long now = clock.getAsLong();
                entry.lastFailure = now;
                entry.state.updateAndGet(previous -> new State(previous.snapshot(),
                        previous.failureSince() == null ? now : previous.failureSince()));
            } else {
                entry.lastFailure = null;
                ServiceMappingSnapshot accepted = valid;
                entry.state.updateAndGet(previous -> previous.snapshot() != null
                        && accepted.revision() < previous.snapshot().revision()
                        ? previous : new State(accepted, null));
                valid = entry.state.get().snapshot();
            }
            entry.inflight = null;
            running.decrementAndGet();
        }
        if (failure != null) {
            future.completeExceptionally(failure);
        } else {
            future.complete(valid);
        }
    }

    private static long duration(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Duration must not be negative");
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Duration exceeds range", error);
        }
    }

    private static long elapsed(long now, long then) {
        return now - then;
    }

    /** 禁止迟到刷新发布并释放本地快照。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (Entry entry : entries.values()) {
                CompletableFuture<ServiceMappingSnapshot> waiting;
                synchronized (entry) {
                    waiting = entry.inflight;
                    if (waiting != null) {
                        entry.inflight = null;
                        running.decrementAndGet();
                    }
                }
                if (waiting != null) {
                    waiting.completeExceptionally(new IllegalStateException("Mapping cache closed"));
                }
            }
            entries.clear();
            allocated.set(0);
        }
    }

    private static final class Entry {
        private final AtomicReference<State> state = new AtomicReference<>(new State(null, null));
        private CompletableFuture<ServiceMappingSnapshot> inflight;
        private Long lastFailure;
    }

    private record State(ServiceMappingSnapshot snapshot, Long failureSince) {
    }
}
