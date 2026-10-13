package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Provider 解码业务参数前的非阻塞身份与方法授权策略。
 *
 * <p>回调在 Transport 的请求处理线程调用，必须立即返回 Stage，禁止阻塞
 * EventLoop。耗时授权需由策略自行使用有界异步执行器。异常或 null 结果
 * 均拒绝；超时后执行仍可能继续，因此资源配额直到真实 Stage 完成才归还。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
@FunctionalInterface
public interface RpcAuthorizer {

    /**
     * 判定已绑定服务方法对当前已验证的连接身份是否开放。
     *
     * @param context 可信 Transport 与服务绑定产生的授权上下文
     * @return true 表示允许调用；false、null 或异常均拒绝
     */
    CompletionStage<Boolean> authorize(RpcAuthorizationContext context);

    /**
     * 默认只接纳 mTLS 客户端证书已验证的 Peer，不按 Subject 自动授予额外权限。
     *
     * @return 基础认证策略，业务可进一步限定服务和方法
     */
    static RpcAuthorizer requireMutualTls() {
        return context -> CompletableFuture.completedFuture(
                context.peer().authenticated());
    }

    /**
     * 显式可信开发环境用策略，不适用于不可信网络。
     *
     * @return 全部许可策略
     */
    static RpcAuthorizer allowTrustedDevelopment() {
        return context -> CompletableFuture.completedFuture(true);
    }
}
