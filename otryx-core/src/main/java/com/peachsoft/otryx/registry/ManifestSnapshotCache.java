package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
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
 * 限额缓存按应用与修订号寻址的不可变 Manifest，支持单飞读取及短期负缓存。
 *
 * <p>完整引用校验和缓存终态先于外部 Future 通知提交；取消、超时和安全失效
 * 不能让旧来源的迟到响应回填。仅控制面可请求刷新。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class ManifestSnapshotCache implements AutoCloseable, RegistrySecuritySource {
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
     * @param repository 实际元数据仓库 SPI，响应仍须独立校验
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

    /** 返回实际被调用 Repository 的全部 Manifest 来源。 */
    @Override
    public RegistrySecurityDescriptor securityDescriptor() {
        return RegistrySecurityDescriptor.from(repository, RegistryChannel.MANIFEST);
    }

    /**
     * 请求指定 Manifest Revision。任何错误不得伪装为合法的 Optional.empty。
     *
     * <p>同键调用共享任务；取消会取消这一共享读取。返回任务终态先提交本地配额，
     * 之后在锁外通知调用方；实际来源取消为尽力执行。
     *
     * @param application 应用身份
     * @param revision 内容寻址修订号
     * @return 可共享的待完成结果或缓存的可信结果
     */
    public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        ApplicationManifest.requireRevision(revision);
        Reference reference = new Reference(application, revision);
        LookupFuture target;
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
            target = new LookupFuture(reference);
            created.pending = target;
            entries.put(reference, created);
            running++;
        }
        // CompletableFuture removes its scheduled timeout when the owned task finishes.
        target.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletionStage<Optional<ApplicationManifest>> source;
        try {
            source = repository.lookupVerified(application, revision);
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
            Entry entry = entries.get(reference);
            active = entry != null && entry.pending == target;
            if (active) {
                target.source = cancellable;
            }
        }
        if (!active) {
            RegistryFailures.cancel(cancellable);
        }
        source.whenComplete((value, error) -> {
            if (error != null) {
                target.completeExceptionally(error);
            } else {
                finish(target, value, null);
            }
        });
        return target;
    }

    private boolean finish(LookupFuture target, Optional<ApplicationManifest> value, Throwable error) {
        Throwable failure = RegistryFailures.unwrap(error);
        if (failure == null) {
            if (value == null) {
                failure = new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
            } else if (value.isPresent()) {
                try {
                    value.orElseThrow().requireReference(target.reference.application(), target.reference.revision());
                } catch (IllegalArgumentException rejected) {
                    failure = new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
                }
            }
        }
        synchronized (this) {
            Entry entry = entries.get(target.reference);
            if (entry == null || entry.pending != target) {
                return false;
            }
            running--;
            if (closed) {
                failure = new IllegalStateException("Manifest cache closed");
            }
            if (failure != null) {
                entries.remove(target.reference);
            } else {
                long weight = value.map(manifest -> (long) manifest.canonicalBytes().length).orElse(0L);
                if (weight > MAX_BYTES - bytes) {
                    entries.remove(target.reference);
                } else {
                    entry.pending = null;
                    entry.value = value;
                    entry.weight = weight;
                    entry.validFrom = clock.getAsLong();
                    entry.ttlNanos = value.isPresent() ? POSITIVE_NANOS : NEGATIVE_NANOS;
                    bytes += weight;
                }
            }
        }
        if (failure != null) {
            target.cancelSource();
        }
        return target.publish(value, failure);
    }

    /** 清空正负缓存和旧任务；任务身份即缓存 generation，迟到完成不能重新获得条目。 */
    void invalidateForSecurity(RegistrySecurityFailure reason) {
        clear(new RegistrySecurityException(reason), false);
    }

    private void clear(Throwable failure, boolean closing) {
        List<LookupFuture> pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = closing;
            pending = entries.values().stream().map(entry -> entry.pending).filter(Objects::nonNull).toList();
            entries.clear();
            running = 0;
            bytes = 0;
        }
        pending.forEach(target -> {
            target.cancelSource();
            target.publish(null, failure);
        });
    }

    /** 取消未决任务并清空数据；不关闭由调用方拥有的实际 Repository。 */
    @Override
    public void close() {
        clear(new IllegalStateException("Manifest cache closed"), true);
    }

    private record Reference(ApplicationKey application, String revision) {
    }

    private static final class Entry {
        private LookupFuture pending;
        private Optional<ApplicationManifest> value;
        private long validFrom;
        private long ttlNanos;
        private long weight;
    }

    /** 任何公开终态入口均先提交缓存状态，避免取消/超时后发生异步回填。 */
    private final class LookupFuture extends CompletableFuture<Optional<ApplicationManifest>> {
        private final Reference reference;
        private volatile CompletableFuture<?> source;

        private LookupFuture(Reference reference) {
            this.reference = reference;
        }

        @Override
        public boolean complete(Optional<ApplicationManifest> ignored) {
            return false;
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            return finish(this, null, Objects.requireNonNull(failure, "failure"));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return finish(this, null, new CancellationException("Manifest lookup cancelled")) || isCancelled();
        }

        private boolean publish(Optional<ApplicationManifest> value, Throwable failure) {
            return failure == null ? super.complete(value) : super.completeExceptionally(failure);
        }

        private void cancelSource() {
            RegistryFailures.cancel(source);
        }
    }
}
