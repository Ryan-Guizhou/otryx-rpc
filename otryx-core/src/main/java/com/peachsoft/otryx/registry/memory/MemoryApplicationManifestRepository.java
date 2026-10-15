package com.peachsoft.otryx.registry.memory;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 进程内不可变 Manifest Repository，支持滚动部署期间的多个内容 Revision。
 *
 * <p>以完整规范字节存储，每次读取都重新解码及校验应用归属；
 * 没有基于 TTL 的任意远端删除接口。存储采用严格条目数和总字节预算，
 * 满容量拒绝新增 Revision，绝不删除仍可能被实例引用的旧 Revision。
 * 仅供 Memory 控制面；跨进程一致性及 ACL 必须由真实 Adapter 实现。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class MemoryApplicationManifestRepository implements ApplicationManifestRepository, AutoCloseable {

    private static final int MAX_MANIFESTS = 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    private final Map<Reference, byte[]> contents = new HashMap<>();
    private long bytes;
    private boolean closed;

    /** 创建独立的有限容量元数据空间。 */
    public MemoryApplicationManifestRepository() {
    }

    /**
     * 幂等创建内容 Revision，相同键写入不同字节必须拒绝。
     *
     * @param manifest 完整不可变清单
     * @return 持久化（内存）确认
     */
    @Override
    public synchronized CompletionStage<Void> publish(ApplicationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Manifest store closed"));
        }
        Reference key = new Reference(manifest.application(), manifest.revision());
        byte[] candidate = manifest.canonicalBytes();
        byte[] existing = contents.get(key);
        if (existing != null) {
            if (!Arrays.equals(existing, candidate)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Immutable Manifest conflict"));
            }
            return CompletableFuture.completedFuture(null);
        }
        if (contents.size() >= MAX_MANIFESTS || candidate.length > MAX_TOTAL_BYTES - bytes) {
            return CompletableFuture.failedFuture(new IllegalStateException("Manifest store capacity exceeded"));
        }
        contents.put(key, candidate);
        bytes += candidate.length;
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 根据 (ApplicationKey,Revision) 查找严格受限内容，缺失不回退其他修订号。
     *
     * @param application 应用身份
     * @param revision 内容修订号
     * @return 完整 Manifest 或合法不存在
     */
    @Override
    public CompletionStage<Optional<ApplicationManifest>> lookup(
            ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        ApplicationManifest.requireRevision(revision);
        byte[] stored;
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Manifest store closed"));
            }
            stored = contents.get(new Reference(application, revision));
        }
        if (stored == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            ApplicationManifest manifest = ApplicationManifestCodec.decode(stored)
                    .requireReference(application, revision);
            return CompletableFuture.completedFuture(Optional.of(manifest));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    /** 关闭时清除本 JVM 持有的 Manifest 字节。 */
    @Override
    public synchronized void close() {
        closed = true;
        contents.clear();
        bytes = 0;
    }

    private record Reference(ApplicationKey application, String revision) {
    }
}
