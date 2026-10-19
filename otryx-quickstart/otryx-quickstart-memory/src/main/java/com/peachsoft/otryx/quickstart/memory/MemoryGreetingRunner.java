package com.peachsoft.otryx.quickstart.memory;

import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.core.RpcCallContext;
import com.peachsoft.otryx.core.RpcCallOptions;
import com.peachsoft.otryx.core.RpcCallScope;
import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 进程内 RPC Consumer；在 Provider 生命周期启动后验证请求与响应。
 *
 * @Author Ryan
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 14:41
 */
@Component
public class MemoryGreetingRunner implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MemoryGreetingRunner.class);
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(10);
    private static final long DISCOVERY_RETRY_DELAY_NANOS = Duration.ofMillis(100).toNanos();

    @OtryxRpcReference(version = "1.0.0")
    private GreetingService greetingService;

    /** 创建进程内调用入口。 */
    public MemoryGreetingRunner() {
    }

    /**
     * 启动后验证一次真实的 RPC 序列化、传输和解码往返。
     *
     * <p>Provider 启动完成不代表 Consumer 目录已经收敛；仅对幂等 hello 的明确
     * 不可用结果重试。等待与各次 RPC 共享最多十秒的预算，已有父时限只能缩短预算。
     * 等待发生于 Spring 启动线程，不得从 EventLoop 调用；中断会终止等待并保留标记。
     *
     * @param args 应用启动参数
     * @throws IllegalStateException 发现预算耗尽、等待中断或返回值不符合预期
     */
    @Override
    public void run(ApplicationArguments args) {
        GreetingReply response = awaitGreeting();
        if (!"Hello, Quickstart!".equals(response.message())) {
            throw new IllegalStateException("Unexpected Memory Quickstart RPC response");
        }
        LOGGER.info("Quickstart memory RPC succeeded: {}", response.message());
    }

    private GreetingReply awaitGreeting() {
        RuntimeException lastFailure = null;
        try (RpcCallScope budgetScope = RpcCallContext.open(new RpcCallOptions(DISCOVERY_TIMEOUT, Map.of()))) {
            RpcCallContext budget = RpcCallContext.current();
            while (true) {
                long remainingNanos = budget.remainingNanos();
                if (remainingNanos <= 0L) {
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Memory Quickstart discovery wait was interrupted",
                            new InterruptedException("Startup thread interrupted"));
                }
                try (RpcCallScope callScope = RpcCallContext.open(
                        new RpcCallOptions(Duration.ofNanos(remainingNanos), Map.of()))) {
                    return greetingService.hello(new GreetingRequest("Quickstart"));
                } catch (RuntimeException failure) {
                    if (!isTransientUnavailable(failure)) {
                        throw failure;
                    }
                    lastFailure = failure;
                }
                waitForRetry(Math.min(DISCOVERY_RETRY_DELAY_NANOS, budget.remainingNanos()));
            }
        }
        throw new IllegalStateException("Memory Quickstart provider discovery budget expired", lastFailure);
    }

    private static boolean isTransientUnavailable(RuntimeException failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof RpcUnavailableException
                || cause instanceof RpcRemoteException remote && remote.status() == RpcStatus.UNAVAILABLE;
    }

    private static void waitForRetry(long delayNanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(delayNanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Memory Quickstart discovery wait was interrupted", interrupted);
        }
    }
}
