package com.peachsoft.otryx.registry.nacos;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Nacos Config 不可变、内容寻址的应用 Manifest 后端。
 *
 * <p>单条 Nacos Config 的限制低于 Core Manifest 最大 256 KiB；超出可存储
 * Config 大小时安全拒绝，不截断、不拆成部分可见的 Config 片段。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
public final class NacosApplicationManifestRepository implements ApplicationManifestRepository {

    private static final String FORMAT_PREFIX = "otryx-v2-manifest:";
    private static final long CONFIRMATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long CONFIRMATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25L);
    private final NacosV2ConfigStore store;
    private final NacosControlExecutor executor;
    private final String namespace;
    private final LongSupplier clock;

    NacosApplicationManifestRepository(
            NacosV2ConfigStore store, NacosControlExecutor executor, String namespace) {
        this(store, executor, namespace, System::nanoTime);
    }

    /** 返回同一实际 Config 客户端的保护来源。 */
    @Override
    public RegistrySecurityDescriptor securityDescriptor() {
        return store.securityDescriptor();
    }

    /** 使用可注入的单调纳秒时钟核验发布后的总确认预算。 */
    NacosApplicationManifestRepository(
            NacosV2ConfigStore store, NacosControlExecutor executor, String namespace, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 幂等创建不可变应用清单，拒绝错误归属和内容哈希污染。
     *
     * <p>发布 ACK 不代表 Nacos 查询缓存已经可见；仅在既有控制面线程中以三秒总预算
     * 确认缺失记录，不重复写入。SDK 异常、错误内容或线程中断立即失败；确认超时后
     * 远端写入仍可能已完成，调用方可用同一 Manifest 幂等重试。
     */
    @Override
    public CompletionStage<Void> publish(ApplicationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        requireNamespace(manifest.application());
        String dataId = NacosV2Names.manifestId(manifest.application(), manifest.revision());
        byte[] canonical = ApplicationManifestCodec.encode(manifest);
        String encoded = FORMAT_PREFIX + Base64.getEncoder().encodeToString(canonical);
        if (encoded.length() > NacosV2ConfigStore.MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("Manifest exceeds Nacos Config size limit");
        }
        return executor.submit("publish-manifest", dataId, () -> {
            NacosV2ConfigStore.Entry current = store.read(dataId);
            if (current.content() != null) {
                requireContent(current.content(), manifest);
                return null;
            }
            boolean created = store.publishImmutable(dataId, encoded);
            confirmPublished(dataId, manifest, encoded, created);
            return null;
        });
    }

    private void confirmPublished(
            String dataId, ApplicationManifest manifest, String encoded, boolean created) throws Exception {
        long startedAt = clock.getAsLong();
        while (true) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingConfirmationNanos(startedAt));
            if (remainingMillis == 0L) {
                throw new TimeoutException("Nacos Manifest confirmation deadline exceeded");
            }
            NacosV2ConfigStore.Entry confirmed = store.read(dataId, remainingMillis);
            remainingConfirmationNanos(startedAt);
            if (confirmed.content() != null) {
                requireContent(confirmed.content(), manifest);
                if (!created && !encoded.equals(confirmed.content())) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
                }
                remainingConfirmationNanos(startedAt);
                return;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(
                    CONFIRMATION_POLL_NANOS, remainingConfirmationNanos(startedAt)));
        }
    }

    private long remainingConfirmationNanos(long startedAt) throws InterruptedException, TimeoutException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Nacos Manifest confirmation interrupted");
        }
        long remaining = CONFIRMATION_TIMEOUT_NANOS - (clock.getAsLong() - startedAt);
        if (remaining <= 0L) {
            throw new TimeoutException("Nacos Manifest confirmation deadline exceeded");
        }
        return remaining;
    }

    /** 按应用身份与完整 SHA256 Revision 读取并校验 Manifest。 */
    @Override
    public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        ApplicationManifest.requireRevision(revision);
        requireNamespace(application);
        String dataId = NacosV2Names.manifestId(application, revision);
        return executor.submit("lookup-manifest", dataId, () -> {
            NacosV2ConfigStore.Entry record = store.read(dataId);
            if (record.content() == null) {
                return Optional.empty();
            }
            ApplicationManifest decoded;
            try {
                decoded = ApplicationManifestCodec.decode(decodeContent(record.content()));
            } catch (IllegalArgumentException rejected) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
            }
            try {
                return Optional.of(decoded.requireReference(application, revision));
            } catch (IllegalArgumentException rejected) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
            }
        });
    }

    private static void requireContent(String content, ApplicationManifest expected) {
        byte[] actual = decodeContent(content);
        if (!Arrays.equals(actual, expected.canonicalBytes())) {
            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
    }

    private static byte[] decodeContent(String content) {
        if (!content.startsWith(FORMAT_PREFIX)) {
            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
        try {
            return Base64.getDecoder().decode(content.substring(FORMAT_PREFIX.length()));
        } catch (IllegalArgumentException rejected) {
            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
    }

    private void requireNamespace(ApplicationKey application) {
        if (!namespace.equals(application.namespace())) {
            throw new IllegalArgumentException("Nacos Manifest namespace does not match application");
        }
    }
}
