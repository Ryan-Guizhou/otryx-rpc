package com.peachsoft.otryx.quickstart.consumer;

import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcTimeoutException;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.core.RpcCallContext;
import com.peachsoft.otryx.core.RpcCallOptions;
import com.peachsoft.otryx.core.RpcCallScope;
import com.peachsoft.otryx.quickstart.api.GreetingDetailsService;
import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.quickstart.api.QuickstartDeadlineMetadata;
import com.peachsoft.otryx.quickstart.api.RelayGreetingService;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 示例 Consumer，验证真实 Greeting、附加导出服务或精确的授权拒绝结果。
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
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(5L);
    private static final long DISCOVERY_RETRY_DELAY_MILLIS = 100L;
    private static final String SERVICE_VERSION = "1.0.0";
    private static final String USE_RELAY_PROPERTY = "otryx.rpc.quickstart.use-relay";
    private static final String USE_DETAILS_PROPERTY = "otryx.rpc.quickstart.use-details";
    private static final String EXPECT_AUTHORIZATION_DENIED_PROPERTY =
            "otryx.rpc.quickstart.expect-authorization-denied";
    private static final String REPEAT_INTERVAL_MILLIS_PROPERTY = "otryx.rpc.quickstart.repeat-interval-millis";
    private static final String REQUEST_NAME = "OTRYX RPC";
    private static final String RELAY_SUCCESS_PREFIX = "CHAIN_OK ";
    private static final String DETAILS_SUCCESS_MESSAGE = "DETAILS_OK";
    private static final String AUTHORIZATION_DENIED_MESSAGE = "RPC authorization denied";

    @OtryxRpcReference(version = SERVICE_VERSION)
    private GreetingService greetingService;

    @OtryxRpcReference(version = SERVICE_VERSION)
    private RelayGreetingService relayGreetingService;

    @OtryxRpcReference(version = SERVICE_VERSION)
    private GreetingDetailsService greetingDetailsService;

    @Value("${" + USE_RELAY_PROPERTY + ":false}")
    private boolean useRelay;

    @Value("${" + USE_DETAILS_PROPERTY + ":false}")
    private boolean useDetails;

    @Value("${" + EXPECT_AUTHORIZATION_DENIED_PROPERTY + ":false}")
    private boolean expectAuthorizationDenied;

    @Value("${" + REPEAT_INTERVAL_MILLIS_PROPERTY + ":0}")
    private long repeatIntervalMillis;

    /** 创建示例 Consumer。 */
    public GreetingRunner() {
    }

    /**
     * 在 Spring 启动线程执行所选示例模式，授权负例仅允许一次有界发现等待。
     *
     * <p>正例的显式重复模式持续等待注册中心恢复，直至外部停止或线程中断；
     * 仅普通幂等 Greeting 探测额外容忍本地请求超时，不延长每次 RPC 的 Deadline；
     * 授权负例只接受框架的确定性远端拒绝，不把 TLS、业务或发现失败视为成功。
     *
     * @param args Spring 启动参数，调用模式由配置属性注入
     * @throws IllegalArgumentException 调用模式冲突或授权负例启用重复执行时抛出
     * @throws IllegalStateException 回复不符合所选场景、发现超时或等待被中断时抛出
     */
    @Override
    public void run(ApplicationArguments args) {
        validateModes();
        do {
            invokeWithDiscoveryWait();
            if (repeatIntervalMillis <= 0L) {
                return;
            }
            sleep(repeatIntervalMillis);
        } while (!Thread.currentThread().isInterrupted());
    }

    private void validateModes() {
        if (useRelay && useDetails) {
            throw new IllegalArgumentException(USE_RELAY_PROPERTY + " and " + USE_DETAILS_PROPERTY
                    + " are mutually exclusive");
        }
        if (expectAuthorizationDenied && repeatIntervalMillis > 0L) {
            throw new IllegalArgumentException(EXPECT_AUTHORIZATION_DENIED_PROPERTY
                    + " requires " + REPEAT_INTERVAL_MILLIS_PROPERTY + " <= 0");
        }
    }

    private void invokeWithDiscoveryWait() {
        long deadline =
                System.nanoTime() + DISCOVERY_TIMEOUT.toNanos();
        RuntimeException lastFailure = null;
        while (shouldContinueDiscovery(
                repeatIntervalMillis > 0L, System.nanoTime(), deadline)) {
            try {
                GreetingReply reply = invokeSelectedService();
                if (expectAuthorizationDenied) {
                    throw new IllegalStateException("Expected RPC authorization denial but the call succeeded");
                }
                logSuccessfulReply(reply);
                return;
            } catch (RuntimeException error) {
                if (expectAuthorizationDenied && isAuthorizationDenied(error)) {
                    LOGGER.info("RPC authorization denial verified.");
                    return;
                }
                if (!isTransientUnavailable(error) && !isRepeatGreetingTimeout(error)) {
                    throw error;
                }
                lastFailure = error;
                sleep(DISCOVERY_RETRY_DELAY_MILLIS);
            }
        }
        throw new IllegalStateException(
                "RPC quickstart could not discover a provider within "
                        + DISCOVERY_TIMEOUT,
                lastFailure);
    }

    private GreetingReply invokeSelectedService() {
        GreetingRequest request = new GreetingRequest(REQUEST_NAME);
        if (useRelay) {
            try (RpcCallScope ignored = RpcCallContext.open(new RpcCallOptions(RELAY_TIMEOUT,
                    Map.of(QuickstartDeadlineMetadata.E2E_KEY, QuickstartDeadlineMetadata.E2E_VALUE,
                            QuickstartDeadlineMetadata.TENANT_KEY, QuickstartDeadlineMetadata.TENANT_VALUE)))) {
                return relayGreetingService.hello(request);
            }
        }
        return useDetails ? greetingDetailsService.details(request) : greetingService.hello(request);
    }

    private void logSuccessfulReply(GreetingReply reply) {
        if (useRelay) {
            if (reply == null || reply.message() == null || !reply.message().startsWith(RELAY_SUCCESS_PREFIX)) {
                throw new IllegalStateException("Relay deadline chain was not verified");
            }
            LOGGER.info("RPC deadline chain completed: {}", reply.message());
        } else if (useDetails) {
            if (reply == null || !DETAILS_SUCCESS_MESSAGE.equals(reply.message())) {
                throw new IllegalStateException("Details service was not verified");
            }
            LOGGER.info("RPC details quickstart completed successfully: {}", DETAILS_SUCCESS_MESSAGE);
        } else {
            LOGGER.info("RPC quickstart completed successfully: {}", reply.message());
        }
    }

    private static boolean isAuthorizationDenied(RuntimeException error) {
        Throwable cause = unwrapCompletionFailure(error);
        return cause instanceof RpcRemoteException remote
                && remote.status() == RpcStatus.BAD_REQUEST
                && AUTHORIZATION_DENIED_MESSAGE.equals(remote.getMessage());
    }

    private boolean isRepeatGreetingTimeout(RuntimeException error) {
        // The explicit recovery probe calls only the side-effect-free, idempotent hello contract.
        return repeatIntervalMillis > 0L
                && !useRelay
                && !useDetails
                && !expectAuthorizationDenied
                && unwrapCompletionFailure(error) instanceof RpcTimeoutException;
    }

    /**
     * 决定示例是否继续等待服务发现。
     *
     * <p>一次性调用最多等待启动预算；显式重复调用模式在外部停止前持续恢复，
     * 防止长时间 Registry 重启窗口提前终止独立 JVM 示例。
     *
     * @param repeating 是否处于重复调用模式
     * @param nowNanos 本地单调时间
     * @param deadlineNanos 一次性调用的截止时间
     * @return 仍应等待时返回 true
     */
    static boolean shouldContinueDiscovery(
            boolean repeating, long nowNanos, long deadlineNanos) {
        return repeating || deadlineNanos - nowNanos > 0L;
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
        Throwable cause = unwrapCompletionFailure(error);
        if (cause instanceof RpcUnavailableException) {
            return true;
        }
        return cause instanceof RpcRemoteException remote
                && remote.status() == RpcStatus.UNAVAILABLE;
    }

    private static Throwable unwrapCompletionFailure(RuntimeException error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
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
