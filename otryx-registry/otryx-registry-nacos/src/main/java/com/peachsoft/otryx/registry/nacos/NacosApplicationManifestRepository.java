package com.peachsoft.otryx.registry.nacos;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

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
    private final NacosV2ConfigStore store;
    private final NacosControlExecutor executor;
    private final String namespace;

    NacosApplicationManifestRepository(
            NacosV2ConfigStore store, NacosControlExecutor executor, String namespace) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** 幂等创建不可变应用清单，拒绝错误归属和内容哈希污染。 */
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
            NacosV2ConfigStore.Entry confirmed = store.read(dataId);
            if (confirmed.content() == null) {
                throw new IllegalStateException("Nacos immutable Manifest was not persisted");
            }
            requireContent(confirmed.content(), manifest);
            if (!created && !encoded.equals(confirmed.content())) {
                throw new IllegalStateException("Nacos Manifest CAS was rejected");
            }
            return null;
        });
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
            if (!record.content().startsWith(FORMAT_PREFIX)) {
                throw new IllegalArgumentException("Unexpected Nacos Manifest metadata format");
            }
            byte[] payload = Base64.getDecoder().decode(record.content().substring(FORMAT_PREFIX.length()));
            return Optional.of(ApplicationManifestCodec.decode(payload).requireReference(application, revision));
        });
    }

    private static void requireContent(String content, ApplicationManifest expected) {
        if (!content.startsWith(FORMAT_PREFIX)) {
            throw new IllegalStateException("Unexpected Nacos immutable Manifest value");
        }
        byte[] actual = Base64.getDecoder().decode(content.substring(FORMAT_PREFIX.length()));
        if (!Arrays.equals(actual, expected.canonicalBytes())) {
            throw new IllegalStateException("Nacos immutable Manifest revision collision");
        }
    }

    private void requireNamespace(ApplicationKey application) {
        if (!namespace.equals(application.namespace())) {
            throw new IllegalArgumentException("Nacos Manifest namespace does not match application");
        }
    }
}
