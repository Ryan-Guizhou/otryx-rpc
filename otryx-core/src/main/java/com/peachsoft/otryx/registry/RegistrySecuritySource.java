package com.peachsoft.otryx.registry;

/**
 * 由实际被调用的 Registry 组件提供只读保护描述。
 *
 * <p>默认未知；配置意图、端点 scheme 或另一实例的描述不能替当前来源背书。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public interface RegistrySecuritySource {
    /**
     * 返回实际通道事实；默认不具备已验证的来源覆盖。
     *
     * @return 防御性复制的不可变描述
     */
    default RegistrySecurityDescriptor securityDescriptor() {
        return RegistrySecurityDescriptor.unknown();
    }
}
