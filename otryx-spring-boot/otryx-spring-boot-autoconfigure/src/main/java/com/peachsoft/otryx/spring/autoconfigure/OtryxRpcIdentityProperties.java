package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.api.RpcCertificateBinding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 由部署方维护的叶证书与应用绑定列表，不从远端 Metadata 补充信任。
 *
 * <p>保留列表直到校验同指纹冲突，避免转为集合时静默丢失矛盾的配置。
 * 本地 ApplicationKey 始终来自实际应用成员身份，不提供第二个配置入口。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:21
 */
public final class OtryxRpcIdentityProperties {
    private static final int MAX_BINDINGS = 1024;
    private List<RpcCertificateBinding> certificateBindings = List.of();

    /** 创建空绑定表。 */
    public OtryxRpcIdentityProperties() {
    }

    /**
     * 返回不可变证书绑定列表。
     *
     * @return 尚未按指纹去重的绑定列表
     */
    public List<RpcCertificateBinding> getCertificateBindings() {
        return certificateBindings;
    }

    /**
     * 保存绑定列表的不可变副本，不静默合并同指纹条目。
     *
     * @param value 非 null 绑定列表，最多 1024 项
     * @throws IllegalArgumentException 列表为空引用、过大或包含 null
     */
    public void setCertificateBindings(List<RpcCertificateBinding> value) {
        if (value == null || value.size() > MAX_BINDINGS || value.stream().anyMatch(binding -> binding == null)) {
            throw new IllegalArgumentException("Invalid otryx.rpc.transport.security.identity.certificate-bindings");
        }
        certificateBindings = List.copyOf(value);
    }

    /**
     * 拒绝同指纹不同应用或期限，完全一致的条目只保留一份。
     *
     * @return 不可变且每个指纹唯一的绑定集合；允许空集合供显式开发使用
     * @throws IllegalArgumentException 同指纹存在矛盾配置
     */
    public Set<RpcCertificateBinding> validatedBindings() {
        Map<String, RpcCertificateBinding> byFingerprint = new LinkedHashMap<>();
        for (RpcCertificateBinding binding : certificateBindings) {
            RpcCertificateBinding previous = byFingerprint.putIfAbsent(binding.certificateSha256(), binding);
            if (previous != null && !previous.equals(binding)) {
                throw new IllegalArgumentException(
                        "Invalid otryx.rpc.transport.security.identity.certificate-bindings: conflicting fingerprint");
            }
        }
        return Set.copyOf(byFingerprint.values());
    }
}
