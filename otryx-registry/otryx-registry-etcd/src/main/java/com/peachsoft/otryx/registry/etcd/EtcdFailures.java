package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import io.etcd.jetcd.common.exception.EtcdException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLException;

/**
 * 以真实 gRPC 状态和 TLS 类型归一化 Etcd 拒绝，绝不读取异常描述文本猜测原因。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:49
 */
final class EtcdFailures {
    private static final int MAX_CAUSES = 32;

    private EtcdFailures() {
    }

    static Throwable normalize(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_CAUSES
                && (current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        if (current instanceof RegistrySecurityException) {
            return current;
        }
        Throwable link = current;
        for (int depth = 0; link != null && depth < MAX_CAUSES; depth++) {
            if (link instanceof SSLException || link instanceof CertificateException) {
                return new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
            }
            if (link.getCause() == link) {
                break;
            }
            link = link.getCause();
        }
        Status.Code code = statusCode(current);
        if (code != null) {
            return code == Status.Code.UNAUTHENTICATED && current.getCause() != null
                    ? normalizeAuthentication(current) : normalizeStatus(code);
        }
        if (current instanceof IllegalArgumentException) {
            return new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
        if (current instanceof CancellationException) {
            return new CancellationException("Etcd control-plane operation cancelled");
        }
        return new IllegalStateException("Etcd control-plane operation failed");
    }

    /**
     * 只剥离 jetcd AuthCredential 合成的 UNAUTHENTICATED 包装与标准异步包装。
     *
     * <p>实际 Authenticate 拒绝已在该 RPC 边界规范为无 cause 的 UNAUTHENTICATED；
     * SDK 也会把超时、不可达和本地准备失败放入同名外层状态。第一个其他状态保留原分类，
     * 不能继续寻找更深的安全状态。未知 cause、链深度耗尽或环均为普通不可用，不递归调用
     * normalize，以免把认证准备阶段的 IllegalArgumentException 当成元数据完整性拒绝。
     *
     * @param failure 带有 cause 的已知 UNAUTHENTICATED 异常载体
     * @return 已分类且不含供应商异常链的失败
     */
    private static Throwable normalizeAuthentication(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (current instanceof CompletionException || current instanceof ExecutionException) {
                current = current.getCause();
                continue;
            }
            Status.Code code = statusCode(current);
            if (code == null) {
                break;
            }
            if (code != Status.Code.UNAUTHENTICATED || current.getCause() == null) {
                return normalizeStatus(code);
            }
            current = current.getCause();
        }
        return new IOException("Etcd control-plane authentication unavailable");
    }

    /**
     * 只从已知 gRPC 与 jetcd 异常载体提取状态，不解析错误描述。
     *
     * @param failure 待检查的异常载体
     * @return 已知载体的状态；其他异常返回 null
     */
    private static Status.Code statusCode(Throwable failure) {
        return failure instanceof StatusRuntimeException runtime ? runtime.getStatus().getCode()
                : failure instanceof StatusException checked ? checked.getStatus().getCode()
                : failure instanceof EtcdException etcd ? Status.Code.valueOf(etcd.getErrorCode().name()) : null;
    }

    /**
     * 保留已确认的状态分类，并在 Registry 边界移除供应商描述与异常链。
     *
     * @param code 已确认的 gRPC 状态
     * @return 固定内容的安全拒绝或普通不可用异常
     */
    private static Throwable normalizeStatus(Status.Code code) {
        if (code == Status.Code.UNAUTHENTICATED) {
            return new RegistrySecurityException(RegistrySecurityFailure.AUTHENTICATION_REJECTED);
        }
        if (code == Status.Code.PERMISSION_DENIED) {
            return new RegistrySecurityException(RegistrySecurityFailure.AUTHORIZATION_REJECTED);
        }
        if (code == Status.Code.RESOURCE_EXHAUSTED) {
            return new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
        }
        return new IOException("Etcd control-plane request unavailable: status=" + code.name());
    }

    static <T> CompletionStage<T> safe(CompletionStage<T> source) {
        CompletableFuture<T> underlying = source.toCompletableFuture();
        CompletableFuture<T> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean won = super.cancel(mayInterruptIfRunning);
                if (won) {
                    underlying.cancel(mayInterruptIfRunning);
                }
                return won;
            }
        };
        source.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(normalize(failure));
            }
        });
        return result;
    }
}
