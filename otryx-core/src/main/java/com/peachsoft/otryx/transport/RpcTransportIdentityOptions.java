package com.peachsoft.otryx.transport;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcCertificateBinding;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 冻结的本地应用身份与明确叶证书信任绑定，不从控制面补充身份。
 *
 * @param localApplication 本机应用身份
 * @param certificateBindings 本地及允许对端的有限叶证书绑定
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public record RpcTransportIdentityOptions(
        ApplicationKey localApplication,
        Set<RpcCertificateBinding> certificateBindings) {

    /** 防御复制，并禁止相同指纹被不同应用或期限重复绑定。 */
    public RpcTransportIdentityOptions {
        Objects.requireNonNull(localApplication, "localApplication");
        certificateBindings = Set.copyOf(Objects.requireNonNull(certificateBindings, "certificateBindings"));
        Map<String, RpcCertificateBinding> byFingerprint = new HashMap<>();
        for (RpcCertificateBinding binding : certificateBindings) {
            RpcCertificateBinding previous = byFingerprint.putIfAbsent(binding.certificateSha256(), binding);
            if (previous != null && !previous.equals(binding)) {
                throw new IllegalArgumentException("Certificate fingerprint has conflicting bindings");
            }
        }
    }

    /** 返回数量，不输出整张信任表或完整指纹。 */
    @Override
    public String toString() {
        return "RpcTransportIdentityOptions[bindingCount=" + certificateBindings.size() + "]";
    }
}
