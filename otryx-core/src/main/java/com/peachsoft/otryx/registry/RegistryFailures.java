package com.peachsoft.otryx.registry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只展开异步完成包装，按稳定类型识别安全拒绝，不解析外部异常文本。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:26
 */
final class RegistryFailures {
    private static final int MAX_WRAPPER_DEPTH = 32;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryFailures.class);

    private RegistryFailures() {
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < MAX_WRAPPER_DEPTH && current != null; depth++) {
            if (!(current instanceof CompletionException || current instanceof ExecutionException)
                    || current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }
    static void cancel(CompletableFuture<?> source) {
        if (source != null) {
            try {
                source.cancel(true);
            } catch (RuntimeException failure) {
                LOGGER.debug("Registry source cancellation failed, errorType={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

}
