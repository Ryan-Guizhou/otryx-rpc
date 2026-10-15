package com.peachsoft.otryx.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 完整、不可变且可确定性验证的应用服务导出清单。
 *
 * <p>Manifest 仅按应用和内容 SHA-256 修订版寻址。多个实例可引用同一修订版，
 * 一个应用在滚动升级中也可同时拥有多个修订版。哈希校验内容一致性，
 * 不证明发布方身份，必须配合可信元数据源和传输鉴权。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 17:48
 */
public final class ApplicationManifest {

    /** 每个 Manifest 的最大导出接口数。 */
    public static final int MAX_SERVICES = 512;

    /** Manifest 标准编码最大字节数，不能在实例 Metadata 中内联存储。 */
    public static final int MAX_CANONICAL_BYTES = 256 * 1024;

    /** 标识内容寻址使用的哈希算法及版本。 */
    public static final String REVISION_PREFIX = "sha256:";

    private static final int HASH_HEX_LENGTH = 64;
    private static final Comparator<ExportedService> SERVICE_ORDER =
            Comparator.comparing((ExportedService value) -> value.serviceKey().serviceName())
                    .thenComparing(value -> value.serviceKey().version())
                    .thenComparing(value -> value.serviceKey().group());

    private final ApplicationKey application;
    private final List<ExportedService> services;
    private final byte[] canonicalBytes;
    private final String revision;

    private ApplicationManifest(
            ApplicationKey application,
            List<ExportedService> services,
            byte[] canonicalBytes) {
        this.application = application;
        this.services = List.copyOf(services);
        this.canonicalBytes = canonicalBytes.clone();
        this.revision = REVISION_PREFIX + hash(canonicalBytes);
    }

    /**
     * 从已绑定服务集合生成确定性 Manifest，无论输入遍历顺序如何。
     *
     * @param application 发布方应用身份
     * @param exportedServices 导出的业务接口集合，不允许重复 ServiceKey 或 Service ID
     * @return 可通过内容修订版共享的不可变 Manifest
     * @throws IllegalArgumentException 清单为空、重复、冲突或超出字节预算时抛出
     */
    public static ApplicationManifest create(
            ApplicationKey application,
            Collection<ExportedService> exportedServices) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(exportedServices, "exportedServices");
        if (exportedServices.isEmpty() || exportedServices.size() > MAX_SERVICES) {
            throw new IllegalArgumentException("Invalid exported service count");
        }
        List<ExportedService> sorted = new ArrayList<>(exportedServices);
        if (sorted.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Manifest cannot contain null services");
        }
        sorted.sort(SERVICE_ORDER);
        Set<ServiceKey> keys = new HashSet<>();
        Map<Integer, ServiceKey> serviceIds = new HashMap<>();
        for (ExportedService service : sorted) {
            if (!keys.add(service.serviceKey())) {
                throw new IllegalArgumentException("Duplicate ServiceKey in application Manifest");
            }
            ServiceKey previous = serviceIds.putIfAbsent(service.serviceId(), service.serviceKey());
            if (previous != null) {
                throw new IllegalArgumentException("Colliding service ID in application Manifest");
            }
        }
        byte[] bytes = ApplicationManifestCodec.encodeCanonical(application, sorted);
        return new ApplicationManifest(application, sorted, bytes);
    }

    /**
     * 返回 Manifest 所属的应用身份。
     *
     * @return 应用身份
     */
    public ApplicationKey application() {
        return application;
    }

    /**
     * 返回不可变且按接口、版本、分组排序的服务条目。
     *
     * @return 已校验的导出服务列表
     */
    public List<ExportedService> services() {
        return services;
    }

    /**
     * 返回 Manifest 与 Wire/Schema 独立的格式版本。
     *
     * @return 格式版本
     */
    public int formatVersion() {
        return ApplicationManifestCodec.FORMAT_VERSION;
    }

    /**
     * 返回当前 Manifest 声明的 RPC Wire 版本。
     *
     * @return Wire 版本
     */
    public int wireProtocolVersion() {
        return ApplicationManifestCodec.WIRE_PROTOCOL_VERSION;
    }

    /**
     * 返回当前 RPC Schema Fingerprint 的规范版本。
     *
     * @return RPC Schema 版本
     */
    public int rpcSchemaVersion() {
        return RpcSchemaFingerprint.SCHEMA_VERSION;
    }

    /**
     * 返回 SHA-256 内容寻址修订版（sha256: + 64 位小写十六进制）。
     *
     * @return 稳定修订版
     */
    public String revision() {
        return revision;
    }

    /**
     * 返回 Manifest 的完整标准二进制内容，每次返回独立副本。
     *
     * @return 不可变 Manifest 的字节副本
     */
    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    /**
     * 校验仓库返回的 Manifest 是否严格匹配请求的应用及修订版。
     *
     * <p>不匹配时必须拒绝，禁止退回旧接口级发现或其它修订版。
     *
     * @param expectedApplication 请求的应用
     * @param expectedRevision 请求的修订版
     * @return 当前已验证的 Manifest
     */
    public ApplicationManifest requireReference(
            ApplicationKey expectedApplication, String expectedRevision) {
        Objects.requireNonNull(expectedApplication, "expectedApplication");
        requireRevision(expectedRevision);
        if (!application.equals(expectedApplication) || !revision.equals(expectedRevision)) {
            throw new IllegalArgumentException("Manifest does not match requested application revision");
        }
        return this;
    }

    /**
     * 校验修订版的算法标识及十六进制编码。
     *
     * @param revision 待校验的 Manifest revision
     * @throws IllegalArgumentException 修订版格式非法时抛出
     */
    public static void requireRevision(String revision) {
        Objects.requireNonNull(revision, "revision");
        if (revision.length() != REVISION_PREFIX.length() + HASH_HEX_LENGTH
                || !revision.startsWith(REVISION_PREFIX)) {
            throw new IllegalArgumentException("Invalid Manifest revision");
        }
        for (int index = REVISION_PREFIX.length(); index < revision.length(); index++) {
            char value = revision.charAt(index);
            if ((value < '0' || value > '9') && (value < 'a' || value > 'f')) {
                throw new IllegalArgumentException("Invalid Manifest revision");
            }
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Required SHA-256 implementation unavailable", error);
        }
    }

    /**
     * 将内容相同的 Manifest 视为等价，不依赖实例 ID 或发布顺序。
     *
     * @param other 比较对象
     * @return 内容编码完全相等时为 true
     */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ApplicationManifest candidate
                && Arrays.equals(canonicalBytes, candidate.canonicalBytes);
    }

    /**
     * 返回由标准内容计算的散列值。
     *
     * @return 标准二进制内容的哈希值
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(canonicalBytes);
    }
}
