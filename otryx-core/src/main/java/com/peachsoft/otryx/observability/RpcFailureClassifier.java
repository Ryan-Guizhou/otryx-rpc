package com.peachsoft.otryx.observability;

import com.peachsoft.otryx.api.RpcOverloadedException;
import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcTimeoutException;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.protocol.RpcProtocolException;
import java.util.concurrent.CancellationException;

/**
 * RPC 失败分类工具，供指标、日志和告警统一使用。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/30 11:21
 */
public final class RpcFailureClassifier {

    private RpcFailureClassifier() {
    }

    /**
     * 根据归一化状态与异常确定低基数分类。
     *
     * @param status RPC 状态
     * @param error 异常，可为空
     * @return 失败分类
     */
    public static RpcFailureCategory classify(
            RpcStatus status,
            Throwable error) {
        if (status == RpcStatus.OK && error == null) {
            return RpcFailureCategory.NONE;
        }
        if (error instanceof RpcAuthenticationException || error instanceof RegistrySecurityException) {
            return RpcFailureCategory.SECURITY;
        }
        if (error instanceof RpcProtocolException) {
            return RpcFailureCategory.PROTOCOL;
        }
        if (error instanceof RpcTimeoutException
                || error instanceof RpcUnavailableException) {
            return RpcFailureCategory.TRANSPORT;
        }
        if (error instanceof RpcOverloadedException) {
            return RpcFailureCategory.PROVIDER;
        }
        if (error instanceof CancellationException) {
            return RpcFailureCategory.CLIENT;
        }
        if (error instanceof RpcRemoteException remote) {
            return classify(remote.status(), null);
        }
        return switch (status) {
            case OK -> RpcFailureCategory.UNKNOWN;
            case BAD_REQUEST, SERVICE_NOT_FOUND, METHOD_NOT_FOUND ->
                    RpcFailureCategory.PROTOCOL;
            case DEADLINE_EXCEEDED, UNAVAILABLE ->
                    RpcFailureCategory.TRANSPORT;
            case OVERLOADED, BUSINESS_ERROR, INTERNAL_ERROR ->
                    RpcFailureCategory.PROVIDER;
        };
    }
}
