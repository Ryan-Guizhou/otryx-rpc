package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * 限额缓存按应用与修订号寻址的不可变 Manifest，支持单飞读取及短期负缓存。
 *
 * <p>仅控制面可请求刷新；调用热路径须使用已验证的本地结果。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class ManifestSnapshotCache implements AutoCloseable {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_INFLIGHT = 32;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final long TIMEOUT_SECONDS = 15L;
    private static final long POSITIVE_NANOS = Duration.ofMinutes(10).toNanos();
    private static final long NEGATIVE_NANOS = Duration.ofSeconds(2).toNanos();

    private final ApplicationManifestRepository repository;
    private final LongSupplier clock;
    private final Map<Reference, Entry> entries = new HashMap<>();
    private boolean closed;
    private int running;
    private long bytes;

    /**
     * 创建限额元数据缓存。
     *
     * @param repository 可信的元数据仓库 SPI
     */
    public ManifestSnapshotCache(ApplicationManifestRepository repository) {
        this(repository, System::nanoTime);
    }

    /**
     * 注入可测试单调时钟。
     *
     * @param repository 元数据仓库
     * @param clock 单调纳秒时钟
     */
    public ManifestSnapshotCache(ApplicationManifestRepository repository, LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 请求指定 Manifest Revision。异常不得伪装为合法的 Optional.empty。
     *
     * @param application 应用身份
     * @param revision 内容寻址修订号
     * @return 可共享的待完成结果或缓存的可信结果
     */
    public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        ApplicationManifest.requireRevision(revision);
        Reference reference = new Reference(application, revision);
        CompletableFuture<Optional<ApplicationManifest>> target;
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Manifest cache closed"));
            }
            long now = clock.getAsLong();
            Entry found = entries.get(reference);
            if (found != null) {
                if (found.pending != null) {
                    return found.pending;
                }
                if (now - found.validFrom < found.ttlNanos) {
                    return CompletableFuture.completedFuture(found.value);
                }
                bytes -= found.weight;
                entries.remove(reference);
            }
            entries.entrySet().removeIf(item -> {
                Entry stale = item.getValue();
                if (stale.pending != null || now - stale.validFrom < stale.ttlNanos) {
                    return false;
                }
                bytes -= stale.weight;
                return true;
            });
            if (entries.size() >= MAX_ENTRIES || running >= MAX_INFLIGHT) {
                return CompletableFuture.failedFuture(new IllegalStateException("Manifest cache limit reached"));
            }
            Entry created = new Entry();
            target = new CompletableFuture<>();
            created.pending = target;
            entries.put(reference, created);
            running++;
            target.whenComplete((value, error) -> finish(reference, target, value, error));
        }
        CompletionStage<Optional<ApplicationManifest>> source;
        try {
            source = Objects.requireNonNull(repository.lookupVerified(application, revision), "lookup");
        } catch (Throwable error) {
            target.completeExceptionally(error);
            return target;
        }
        source.whenComplete((value, error) -> {
            if (error != null) {
                target.completeExceptionally(error);
            } else if (value == null) {
                target.completeExceptionally(new IllegalStateException("Null manifest response"));
            } else {
                target.complete(value);
            }
        });
        if (!target.isDone()) {
            CompletableFuture.delayedExecutor(TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() ->
                    target.completeExceptionally(new TimeoutException("Manifest lookup timeout")));
        }
        return target;
    }

    private synchronized void finish(Reference reference, CompletableFuture<Optional<ApplicationManifest>> target,
                                     Optional<ApplicationManifest> value, Throwable error) {
        Entry entry = entries.get(reference);
        if (entry == null || entry.pending != target) {
            return;
        }
        running--;
        if (closed || error != null) {
            entries.remove(reference);
            return;
        }
        long weight = value.map(manifest -> (long) manifest.canonicalBytes().length).orElse(0L);
        if (weight > MAX_BYTES - bytes) {
            entries.remove(reference);
            return;
        }
        entry.pending = null;
        entry.value = value;
        entry.weight = weight;
        entry.validFrom = clock.getAsLong();
        entry.ttlNanos = value.isPresent() ? POSITIVE_NANOS : NEGATIVE_NANOS;
        bytes += weight;
    }

    /** 取消缓存中的未决任务并清空本地数据。 */
    @Override
    public void close() {
        java.util.List<CompletableFuture<Optional<ApplicationManifest>>> pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pending = entries.values().stream().map(entry -> entry.pending).filter(Objects::nonNull).toList();
            entries.clear();
            running = 0;
            bytes = 0;
        }
        pending.forEach(future -> future.completeExceptionally(
                new IllegalStateException("Manifest cache closed")));
    }

    private record Reference(ApplicationKey application, String revision) {
    }

    private static final class Entry {
        private CompletableFuture<Optional<ApplicationManifest>> pending;
        private Optional<ApplicationManifest> value;
        private long validFrom;
        private long ttlNanos;
        private long weight;
    }
}
