package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.exception.NacosException;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 根据 Nacos 的类型与稳定错误码归一化拒绝，不读取外部异常 message。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:47
 */
final class NacosFailures {
    private static final int MAX_CAUSES = 32;
    private static final int UNAUTHENTICATED = 401;
    private static final int FORBIDDEN = 403;

    private NacosFailures() {
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
        if (current instanceof NacosException nacos) {
            if (nacos.getErrCode() == UNAUTHENTICATED) {
                return new RegistrySecurityException(RegistrySecurityFailure.AUTHENTICATION_REJECTED);
            }
            if (nacos.getErrCode() == FORBIDDEN) {
                return new RegistrySecurityException(RegistrySecurityFailure.AUTHORIZATION_REJECTED);
            }
        }
        if (current instanceof IllegalArgumentException) {
            return new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
        if (current instanceof InterruptedException) {
            return new InterruptedException("Nacos control-plane operation interrupted");
        }
        if (current instanceof CancellationException) {
            return new CancellationException("Nacos control-plane operation cancelled");
        }
        if (current instanceof TimeoutException) {
            return new TimeoutException("Nacos control-plane deadline exceeded");
        }
        return new IllegalStateException("Nacos control-plane operation failed");
    }
}
