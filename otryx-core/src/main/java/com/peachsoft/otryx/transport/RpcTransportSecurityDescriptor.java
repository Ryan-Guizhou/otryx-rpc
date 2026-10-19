package com.peachsoft.otryx.transport;

import com.peachsoft.otryx.api.RpcCertificateBinding;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 实际 Transport 对象已经生效的能力描述；读取不能创建线程、执行 IO 或激活连接。
 *
 * @param state 是否为实际已验证配置
 * @param mode 实际 TLS 模式，UNKNOWN 时不用于授信
 * @param localIdentity 实际本地叶证书的有限绑定
 * @param capabilities 实际实现的安全能力
 * @param enabledCodecIds 实际可协商的业务 Codec 集合
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public record RpcTransportSecurityDescriptor(
        State state,
        RpcSecurityMode mode,
        Optional<RpcCertificateBinding> localIdentity,
        Set<Capability> capabilities,
        Set<Byte> enabledCodecIds) {
    private static final RpcTransportSecurityDescriptor UNKNOWN = new RpcTransportSecurityDescriptor(
            State.UNKNOWN, RpcSecurityMode.PLAINTEXT, Optional.empty(), Set.of(), Set.of());

    /** 未知实现不会自动成为安全 Transport。 */
    public enum State {
        /** 未验证实际安全能力，不能凭配置意图或组件名称授信。 */
        UNKNOWN,
        /** 实际对象已安装声明的配置，仍须由 Core 逐项核验所选安全要求。 */
        EFFECTIVE
    }

    /** 由可信 Adapter 的实际行为保证的能力集合。 */
    public enum Capability {
        /** 使用实际配置的信任材料校验对端证书链。 */
        PEER_CERTIFICATE_CHAIN_VALIDATION,
        /** 将实际验证的叶证书绑定到包含命名空间的完整应用身份。 */
        PEER_APPLICATION_BINDING,
        /** 发送请求前核对对端完整应用身份与预期 RpcTarget 是否一致。 */
        EXPECTED_TARGET_APPLICATION,
        /** 按完整应用身份和 Endpoint 隔离连接池，禁止跨应用复用连接。 */
        TARGET_BOUND_CONNECTION_POOL,
        /** 将同一实际连接的对端身份交付请求处理器或响应结果。 */
        PEER_IDENTITY_DELIVERY,
        /** 在连接使用与交付边界复核证书、绑定及本地单调有效期限。 */
        IDENTITY_EXPIRY,
        /** 对实际连接目标执行 TLS 主机名或 IP 地址校验。 */
        HOSTNAME_VERIFICATION
    }

    /** 冻结描述，拒绝以 UNKNOWN 同时声明额外能力。 */
    public RpcTransportSecurityDescriptor {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(localIdentity, "localIdentity");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        enabledCodecIds = Set.copyOf(Objects.requireNonNull(enabledCodecIds, "enabledCodecIds"));
        if (state == State.UNKNOWN
                && (localIdentity.isPresent() || !capabilities.isEmpty() || !enabledCodecIds.isEmpty())) {
            throw new IllegalArgumentException("Unknown transport cannot declare effective capabilities");
        }
    }

    /**
     * 返回不会授信的缺省描述。
     *
     * @return 共享不可变未知描述
     */
    public static RpcTransportSecurityDescriptor unknown() {
        return UNKNOWN;
    }

    /** 返回模式与能力数量，不输出证书绑定内容。 */
    @Override
    public String toString() {
        return "RpcTransportSecurityDescriptor[state=" + state + ", mode=" + mode
                + ", capabilityCount=" + capabilities.size() + ", codecCount=" + enabledCodecIds.size() + "]";
    }
}
