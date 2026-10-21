package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Provider 解码业务参数前的有限方法授权策略。
 *
 * <p>Core 先建立超时与有界工作所有权，再在自有授权执行器调用策略体。超时不能证明
 * 策略体或原始 Stage 已退出；真实工作继续占用授权槽位。异常和 null 均拒绝。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
@FunctionalInterface
public interface RpcAuthorizer {

    /**
     * 判定已绑定服务方法对当前已验证身份是否开放。
     *
     * @param context 不包含 Payload 或自述身份的有限授权上下文
     * @return 有限授权决定；null Stage、null 决定或异常均拒绝
     */
    CompletionStage<RpcAuthorizationDecision> authorize(RpcAuthorizationContext context);

    /**
     * 返回全部拒绝策略，不能替代 Network Provider 显式装配的业务授权策略。
     *
     * @return 无外部副作用的拒绝策略
     */
    static RpcAuthorizer denyAll() {
        return context -> CompletableFuture.completedFuture(RpcAuthorizationDecision.deny());
    }
}
