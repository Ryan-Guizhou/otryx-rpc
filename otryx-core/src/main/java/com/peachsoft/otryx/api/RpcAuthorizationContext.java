package com.peachsoft.otryx.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider 授权请求上下文，只携带可信连接身份和已绑定的方法标识。
 *
 * <p>不包含业务 Payload，也不把请求 Metadata 当作已认证身份。
 *
 * @param peer TLS/mTLS Transport 所验证的对端身份
 * @param localApplication 本机 Coordinator 的实际应用身份
 * @param receivedAt 本次接收请求的壁钟锚点
 * @param maximumValidUntil 原请求和连接共同限定的最大许可期限
 * @param serviceKey 已注册服务的规范标识
 * @param methodId 当前预绑定的 RPC 方法标识
 * @param remote 仅供审计的远端网络地址，不用于强身份认证
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
public record RpcAuthorizationContext(
        RpcPeerIdentity peer,
        ApplicationKey localApplication,
        ServiceKey serviceKey,
        int methodId,
        RpcEndpoint remote,
        Instant receivedAt,
        Instant maximumValidUntil) {

    /** 禁止构造缺失可信来源或目标方法的授权上下文。 */
    public RpcAuthorizationContext {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(localApplication, "localApplication");
        Objects.requireNonNull(serviceKey, "serviceKey");
        Objects.requireNonNull(remote, "remote");
        RpcIdentityTime.requireFinite(receivedAt, "receivedAt");
        RpcIdentityTime.requireFinite(maximumValidUntil, "maximumValidUntil");
    }
}
