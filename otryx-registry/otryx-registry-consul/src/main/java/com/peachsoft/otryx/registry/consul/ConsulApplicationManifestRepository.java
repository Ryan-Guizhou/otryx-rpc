package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Consul KV 内容寻址、不可变且跨进程 CAS 的应用 Manifest。
 *
 * <p>以逻辑 Namespace+Application+完整 SHA-256 Revision 确定唯一 KV Key。
 * 首次创建使用 Consul cas=0；已存在时严格比对 canonical bytes；不删除旧 Revision，
 * 避免滚动升级/回滚时丢失仍被实例引用的清单。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
public final class ConsulApplicationManifestRepository implements ApplicationManifestRepository {

    private static final int FORMAT_VERSION = 1;
    private static final String FIELD_VERSION = "format";
    private static final String FIELD_NAMESPACE = "namespace";
    private static final String FIELD_APPLICATION = "application";
    private static final String FIELD_REVISION = "revision";
    private static final String FIELD_PAYLOAD = "payload";

    private final ConsulV2Http http;
    private final ConsulV2Executor executor;
    private final String namespace;
    private final ObjectMapper mapper = new ObjectMapper();

    ConsulApplicationManifestRepository(ConsulV2Http http, ConsulV2Executor executor, String namespace) {
        this.http = Objects.requireNonNull(http, "http");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** 将不可变版本清单首次原子创建，已存在时验证完整字节与内容身份。 */
    @Override
    public CompletionStage<Void> publish(ApplicationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        requireNamespace(manifest.application());
        String key = ConsulV2Keys.manifest(namespace, manifest.application().applicationName(),
                manifest.revision());
        JsonNode document = mapper.valueToTree(Map.of(
                FIELD_VERSION, FORMAT_VERSION,
                FIELD_NAMESPACE, namespace,
                FIELD_APPLICATION, manifest.application().applicationName(),
                FIELD_REVISION, manifest.revision(),
                FIELD_PAYLOAD, Base64.getEncoder().encodeToString(manifest.canonicalBytes())));
        return executor.submit("manifest-publish", () -> {
            ConsulV2Http.KvEntry current = http.get(key, ConsulV2Http.MAX_MANIFEST_VALUE_BYTES);
            if (current == null) {
                boolean inserted = http.compareAndSet(key, document, 0L,
                        ConsulV2Http.MAX_MANIFEST_VALUE_BYTES);
                if (inserted) {
                    return null;
                }
                current = http.get(key, ConsulV2Http.MAX_MANIFEST_VALUE_BYTES);
            }
            if (current == null) {
                throw new IllegalStateException("Consul immutable Manifest CAS raced without a visible value");
            }
            ApplicationManifest existing = parse(current.value(), manifest.application(), manifest.revision());
            if (!Arrays.equals(existing.canonicalBytes(), manifest.canonicalBytes())) {
                throw new IllegalStateException("Consul immutable Manifest revision collision");
            }
            return null;
        });
    }

    /** 读取 Manifest 并校验类型、原始二进制和内容 SHA-256。 */
    @Override
    public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        ApplicationManifest.requireRevision(revision);
        requireNamespace(application);
        String key = ConsulV2Keys.manifest(namespace, application.applicationName(), revision);
        return executor.submit("manifest-lookup", () -> {
            ConsulV2Http.KvEntry entry = http.get(key, ConsulV2Http.MAX_MANIFEST_VALUE_BYTES);
            return entry == null ? Optional.empty() : Optional.of(parse(entry.value(), application, revision));
        });
    }

    private static ApplicationManifest parse(
            JsonNode content, ApplicationKey application, String revision) {
        if (content.path(FIELD_VERSION).asInt(-1) != FORMAT_VERSION
                || !application.namespace().equals(content.path(FIELD_NAMESPACE).asText())
                || !application.applicationName().equals(content.path(FIELD_APPLICATION).asText())
                || !revision.equals(content.path(FIELD_REVISION).asText())
                || !content.path(FIELD_PAYLOAD).isTextual()) {
            throw new IllegalArgumentException("Consul immutable Manifest identity or format mismatch");
        }
        byte[] bytes = Base64.getDecoder().decode(content.path(FIELD_PAYLOAD).asText());
        return ApplicationManifestCodec.decode(bytes).requireReference(application, revision);
    }

    private void requireNamespace(ApplicationKey application) {
        if (!namespace.equals(application.namespace())) {
            throw new IllegalArgumentException("Consul Manifest namespace does not match application");
        }
    }
}
