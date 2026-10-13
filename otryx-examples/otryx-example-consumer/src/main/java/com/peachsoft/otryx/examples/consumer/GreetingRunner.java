package com.peachsoft.otryx.examples.consumer;

import com.peachsoft.otryx.core.RpcCallContext;
import com.peachsoft.otryx.core.RpcCallOptions;
import com.peachsoft.otryx.core.RpcCallScope;
import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.examples.api.ExampleDeadlineMetadata;
import com.peachsoft.otryx.examples.api.GreetingReply;
import com.peachsoft.otryx.examples.api.GreetingRequest;
import com.peachsoft.otryx.examples.api.GreetingService;
import com.peachsoft.otryx.examples.api.RelayGreetingService;
import java.util.Map;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 示例 Consumer，在应用启动后完成一次真实 RPC 调用。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:36
 */
@Component
public class GreetingRunner implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GreetingRunner.class);
    private static final Duration DISCOVERY_TIMEOUT =
            Duration.ofSeconds(10);

    @OtryxRpcReference(version = "1.0.0")
    private GreetingService greetingService;

    @OtryxRpcReference(version = "1.0.0")
    private RelayGreetingService relayGreetingService;

    @Value("${otryx.rpc.example.use-relay:false}")
    private boolean useRelay;

    @Value("${otryx.rpc.example.repeat-interval-millis:0}")
    private long repeatIntervalMillis;

    /** 创建示例 Consumer。 */
    public GreetingRunner() {
    }

    @Override
    public void run(ApplicationArguments args) {
        do {
            invokeWithDiscoveryWait();
            if (repeatIntervalMillis <= 0L) {
                return;
            }
            sleep(repeatIntervalMillis);
        } while (!Thread.currentThread().isInterrupted());
    }

    private void invokeWithDiscoveryWait() {
        long deadline =
                System.nanoTime() + DISCOVERY_TIMEOUT.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (useRelay) {
                    GreetingReply reply;
                    try (RpcCallScope ignored = RpcCallContext.open(
                            new RpcCallOptions(Duration.ofSeconds(5L),
                                    Map.of(ExampleDeadlineMetadata.E2E_KEY, ExampleDeadlineMetadata.E2E_VALUE,
                                            ExampleDeadlineMetadata.TENANT_KEY,
                                            ExampleDeadlineMetadata.TENANT_VALUE)))) {
                        reply = relayGreetingService.hello(new GreetingRequest("OTRYX RPC"));
                    }
                    if (!reply.message().startsWith("CHAIN_OK ")) {
                        throw new IllegalStateException("Relay deadline chain was not verified");
                    }
                    LOGGER.info("RPC deadline chain completed: {}", reply.message());
                } else {
                    GreetingReply reply = greetingService.hello(
                            new GreetingRequest("OTRYX RPC"));
                    LOGGER.info("RPC demo completed successfully: {}", reply.message());
                }
                return;
            } catch (RuntimeException error) {
                if (!isTransientUnavailable(error)) {
                    throw error;
                }
                lastFailure = error;
                sleep(100L);
            }
        }
        throw new IllegalStateException(
                "RPC demo could not discover a provider within "
                        + DISCOVERY_TIMEOUT,
                lastFailure);
    }

    /**
     * 判断示例中的服务发现或 Provider 启动窗口是否暂时不可用。
     *
     * <p>只重试明确的 UNAVAILABLE 状态；业务、协议及其他框架错误
     * 必须原样向上抛出，避免示例掩盖真实故障。
     *
     * @param error RPC 调用抛出的异常
     * @return 为可重试的暂时不可用状态时返回 true
     */
    static boolean isTransientUnavailable(RuntimeException error) {
        Throwable cause = error;
        while (cause instanceof CompletionException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RpcUnavailableException) {
            return true;
        }
        return cause instanceof RpcRemoteException remote
                && remote.status() == RpcStatus.UNAVAILABLE;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "RPC demo wait was interrupted",
                    error);
        }
    }
}
