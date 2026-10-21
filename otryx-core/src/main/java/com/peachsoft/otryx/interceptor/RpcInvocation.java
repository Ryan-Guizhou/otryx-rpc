package com.peachsoft.otryx.interceptor;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.Objects;
import java.util.Optional;

/**
 * 可插拔调用拦截器的不可变安全上下文，不暴露请求参数、原始 Metadata 或证书。
 *
 * <p>Provider 的 caller 仅来自 Transport 已验证的身份，可信开发的匿名
 * 连接使用 Optional.empty；不能从调用方传入的 Header 还原或提升权限。
 *
 * @param side Consumer 或 Provider 调用边界
 * @param service 经本地契约检查的 ServiceKey
 * @param methodId 当前真实方法 ID
 * @param caller 实际应用成员身份（Consumer）或已认证 Peer 身份（Provider）
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 16:00
 */
public record RpcInvocation(Side side, ServiceKey service, int methodId, Optional<ApplicationKey> caller) {

    /** 区分 Consumer 逻辑调用与 Provider 授权后的执行。 */
    public enum Side {
        /** 发起调用、安排独立 Retry Attempt 的 Consumer。 */
        CONSUMER,
        /** 通过实际 Peer、方法授权和业务 Admission 的 Provider。 */
        PROVIDER
    }

    /** 冻结不含敏感输入的调用上下文。 */
    public RpcInvocation {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(caller, "caller");
    }
}
