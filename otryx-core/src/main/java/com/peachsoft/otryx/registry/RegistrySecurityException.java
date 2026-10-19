package com.peachsoft.otryx.registry;

import java.util.Objects;

/**
 * 终止安全失效的控制面读取或调用，不携带原始响应、凭据或外部异常链。
 *
 * <p>该异常不同于暂时不可达；调用方不能依靠重试或旧快照绕过拒绝。
 * 禁用 suppressed 与可写 cause，避免清理异常重新引入外部敏感内容。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public final class RegistrySecurityException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** 稳定拒绝原因，仅保存固定分类，不保存后端原始异常或敏感内容。 */
    private final RegistrySecurityFailure reason;

    /**
     * 使用固定安全分类构造异常。
     *
     * @param reason 非 null 的稳定安全分类
     */
    public RegistrySecurityException(RegistrySecurityFailure reason) {
        super("Registry security rejected: " + Objects.requireNonNull(reason, "reason").name(),
                null, false, true);
        this.reason = reason;
    }

    /**
     * 返回固定分类，不暴露原始远端异常。
     *
     * @return 稳定安全分类
     */
    public RegistrySecurityFailure reason() {
        return reason;
    }
}
