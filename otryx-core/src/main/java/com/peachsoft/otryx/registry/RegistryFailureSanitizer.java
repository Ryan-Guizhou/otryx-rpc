package com.peachsoft.otryx.registry;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * 在 Adapter 外部错误越过 Registry SPI 之前移除任意 message、cause 和 suppressed 内容。
 *
 * <p>供应商特有的认证、授权及 TLS 类型必须先由对应 Adapter 分类，本工具不猜测文本。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:50
 */
public final class RegistryFailureSanitizer {
    private RegistryFailureSanitizer() {
    }

    /**
     * 保留稳定的安全、超时和取消类别；其他错误仅暴露固定失败描述。
     *
     * @param failure 已由供应商边界分类的错误
     * @return 不含外部数据的错误
     */
    public static Throwable sanitize(Throwable failure) {
        Throwable cause = RegistryFailures.unwrap(failure);
        if (cause instanceof RegistrySecurityException) {
            return cause;
        }
        if (cause instanceof InterruptedException) {
            return new InterruptedException("Registry operation interrupted");
        }
        if (cause instanceof CancellationException) {
            return new CancellationException("Registry operation cancelled");
        }
        if (cause instanceof TimeoutException) {
            return new TimeoutException("Registry operation deadline exceeded");
        }
        if (cause instanceof IOException) {
            return new IOException("Registry endpoint unavailable");
        }
        if (cause instanceof IllegalArgumentException) {
            return new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
        return new IllegalStateException("Registry operation failed");
    }
}
