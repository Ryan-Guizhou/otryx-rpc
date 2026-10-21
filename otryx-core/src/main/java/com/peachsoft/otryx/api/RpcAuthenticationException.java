package com.peachsoft.otryx.api;

import java.util.Objects;

/**
 * 本地连接认证失败；不属于可重试基础设施故障，不携带外部异常或凭据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public final class RpcAuthenticationException extends RpcException {
    /** 不包含证书、凭据或外部异常内容的固定失败分类。 */
    private final Reason reason;

    /** 稳定且有限的认证失败原因。 */
    public enum Reason {
        /** TLS 握手未能验证对端的证书身份。 */
        CERTIFICATE_REJECTED,
        /** 实际叶证书未找到受信的应用身份绑定。 */
        IDENTITY_UNBOUND,
        /** 证书绑定的应用名与本地应用或预期目标应用不一致。 */
        APPLICATION_MISMATCH,
        /** 证书绑定的命名空间与本地应用或预期目标命名空间不一致。 */
        NAMESPACE_MISMATCH,
        /** 证书或应用绑定已不满足墙钟与本地单调时间的有限有效期要求。 */
        IDENTITY_EXPIRED,
        /** 承载当前连接身份的实际 TLS 材料代次已替换，原证明不能继续使用。 */
        SECURITY_CONTEXT_CHANGED
    }

    /**
     * 创建只包含固定原因码且不能附加外部 cause 的认证异常。
     *
     * @param reason 白名单失败原因
     */
    public RpcAuthenticationException(Reason reason) {
        super("RPC authentication failed: " + Objects.requireNonNull(reason, "reason"), null, false, true);
        this.reason = reason;
    }

    /**
     * 返回稳定失败原因。
     *
     * @return 非 null 失败原因
     */
    public Reason reason() {
        return reason;
    }
}
