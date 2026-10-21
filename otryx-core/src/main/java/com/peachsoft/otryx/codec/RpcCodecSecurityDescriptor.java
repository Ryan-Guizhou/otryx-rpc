package com.peachsoft.otryx.codec;

import java.util.Objects;

/**
 * 实际 Codec 的类型校验及有限解码能力，不代表编码前内存硬预算。
 *
 * @param typePolicy 实际安装的类型策略
 * @param validatesDeclaredContractTypes 是否验证本地声明的契约类型
 * @param validatesPayloadBeforeDecode 是否在 DTO 实例化前验证载荷
 * @param maxPayloadBytes 最大编码载荷长度
 * @param maxDepth 最大对象图深度
 * @param maxEstimatedGraphMemoryBytes 估算对象图内存上限，并非 DirectMemory 或编码分配硬上限
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public record RpcCodecSecurityDescriptor(
        TypePolicy typePolicy,
        boolean validatesDeclaredContractTypes,
        boolean validatesPayloadBeforeDecode,
        int maxPayloadBytes,
        int maxDepth,
        long maxEstimatedGraphMemoryBytes) {
    private static final RpcCodecSecurityDescriptor UNKNOWN = new RpcCodecSecurityDescriptor(
            TypePolicy.UNKNOWN, false, false, 0, 0, 0);

    /** 实际执行的类型策略，不能由 Codec 编号推断。 */
    public enum TypePolicy {
        /** 尚未提供可用于安全预检的实际类型策略。 */
        UNKNOWN,
        /** 信任载荷类型的兼容策略，不满足网络安全配置所需的严格类型约束。 */
        TRUSTED_COMPATIBILITY,
        /** 按已安装的显式类型白名单限制反序列化。 */
        EXPLICIT_ALLOWLIST,
        /** 按预先注册并校验的本地契约类型限制反序列化。 */
        REGISTERED_CONTRACT_TYPES
    }

    /** 校验描述完整性，并禁止以零或无限哨兵假冒有限上限。 */
    public RpcCodecSecurityDescriptor {
        Objects.requireNonNull(typePolicy, "typePolicy");
        if (typePolicy == TypePolicy.UNKNOWN) {
            if (validatesDeclaredContractTypes || validatesPayloadBeforeDecode
                    || maxPayloadBytes != 0 || maxDepth != 0 || maxEstimatedGraphMemoryBytes != 0) {
                throw new IllegalArgumentException("Unknown codec cannot declare effective capabilities");
            }
        } else if (maxPayloadBytes <= 0 || maxDepth <= 0 || maxEstimatedGraphMemoryBytes <= 0) {
            throw new IllegalArgumentException("Codec security limits must be positive");
        }
    }

    /**
     * 返回不会被 Network Profile 接受的缺省描述。
     *
     * @return 共享不可变未知描述
     */
    public static RpcCodecSecurityDescriptor unknown() {
        return UNKNOWN;
    }
}
