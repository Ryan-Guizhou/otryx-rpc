package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.config.ConfigQueryResult;
import com.alibaba.nacos.api.config.ConfigService;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Nacos Config 的有界、严格 MD5-CAS 访问层。
 *
 * <p>生产读取仅接受远端 Config 响应；本地 Snapshot 不能代替权威 MD5。
 * 对于需要 fencing 的元数据操作仍拒绝没有 MD5 的非空数据。
 * 所有调用须运行在 NacosControlExecutor 上，不得阻塞 RPC EventLoop。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
final class NacosV2ConfigStore {

    static final int MAX_CONFIG_BYTES = 90 * 1024;
    static final int MAX_CAS_ATTEMPTS = 12;
    private static final long CONFIG_TIMEOUT_MILLIS = 3000L;

    private final RegistrySecurityDescriptor descriptor;
    private final NacosConfigOperations config;
    private final String group;

    NacosV2ConfigStore(ConfigService config, String group) {
        this(NacosConfigOperations.fromInjectedService(config), group, RegistrySecurityDescriptor.unknown());
    }

    NacosV2ConfigStore(NacosConfigOperations config, String group, RegistrySecurityDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.config = Objects.requireNonNull(config, "config");
        this.group = Objects.requireNonNull(group, "group");
    }

    RegistrySecurityDescriptor securityDescriptor() {
        return descriptor;
    }

    /** 从服务端获取数据和真正的 CAS MD5；没有服务端 MD5 的旧快照必须拒绝。 */
    Entry read(String dataId) throws Exception {
        return read(dataId, CONFIG_TIMEOUT_MILLIS);
    }

    /** 使用调用方剩余正毫秒预算读取，仍受单次 SDK 查询上限约束。 */
    Entry read(String dataId, long remainingTimeoutMillis) throws Exception {
        if (remainingTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("Nacos Config read timeout must be positive");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Nacos Config read interrupted");
        }
        ConfigQueryResult response = config.query(
                dataId, group, Math.min(remainingTimeoutMillis, CONFIG_TIMEOUT_MILLIS));
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Nacos Config read interrupted");
        }
        if (response == null || response.getContent() == null) {
            return new Entry(null, null);
        }
        String value = response.getContent();
        requireSize(value);
        String md5 = response.getMd5();
        if (md5 == null || !md5.matches("[0-9a-fA-F]{32}")) {
            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
        return new Entry(value, md5);
    }

    /** 只允许 CAS 条件提交；false 表示冲突或后端拒绝，需重读/重试或上抛。 */
    boolean compareAndSet(String dataId, String next, Entry expected) throws Exception {
        Objects.requireNonNull(expected, "expected");
        requireSize(next);
        if (expected.md5() == null) {
            throw new IllegalStateException(
                    "Nacos mapping CAS requires a preinitialized Config document with authoritative MD5");
        }
        return config.compareAndSet(dataId, group, next, expected.md5());
    }

    /**
     * 仅用于带内容哈希的不可变 Manifest 首次创建；Nacos 首次创建不保证 CAS，
     * 调用方必须读取后严格校验 canonical bytes 和 SHA256，禁止用于 Mapping。
     */
    boolean publishImmutable(String dataId, String value) throws Exception {
        requireSize(value);
        return config.publishImmutable(dataId, group, value);
    }

    private static void requireSize(String content) {
        Objects.requireNonNull(content, "config content");
        if (content.length() > MAX_CONFIG_BYTES
                || content.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
        }
    }

    record Entry(String content, String md5) {
        @Override
        public String toString() {
            return "NacosConfigEntry[present=" + (content != null) + ", authoritative=" + (md5 != null) + ']';
        }
    }
}
