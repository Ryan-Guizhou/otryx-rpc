package com.peachsoft.otryx.quickstart.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.quickstart.api.GreetingDetailsService;
import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.quickstart.api.RelayGreetingService;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证示例调用模式与授权负例只接受精确的远端拒绝，避免将其他故障误报为成功。
 *
 * <p>以本地契约实现隔离服务发现和网络，只验证 Runner 的调用选择、失败分类与稳定标记。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:05
 */
@ExtendWith(OutputCaptureExtension.class)
class GreetingRunnerModeTest {

    private static final String AUTHORIZATION_DENIED_MESSAGE = "RPC authorization denied";
    private static final String DENIAL_MARKER = "RPC authorization denial verified.";
    private static final String GREETING_MARKER = "RPC quickstart completed successfully:";
    private static final String DETAILS_MARKER = "RPC details quickstart completed successfully:";

    @Test
    void ordinaryGreetingRetainsItsSuccessMarker(CapturedOutput output) {
        GreetingRunner runner = runner(request -> new GreetingReply("Hello OTRYX RPC"), unusedDetails());

        run(runner);

        assertThat(output).contains(GREETING_MARKER + " Hello OTRYX RPC")
                .doesNotContain(DETAILS_MARKER, DENIAL_MARKER);
    }

    @Test
    void detailsModeCallsOnlyTheDetailsContract(CapturedOutput output) {
        GreetingRunner runner = runner(unusedGreeting(), request -> {
            assertEquals("OTRYX RPC", request.name());
            return new GreetingReply("DETAILS_OK");
        });
        ReflectionTestUtils.setField(runner, "useDetails", true);

        run(runner);

        assertThat(output).contains(DETAILS_MARKER + " DETAILS_OK")
                .doesNotContain(GREETING_MARKER, DENIAL_MARKER);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Hello", "DETAILS_OK extra"})
    void detailsModeRequiresTheExactReply(String message, CapturedOutput output) {
        GreetingRunner runner = runner(unusedGreeting(), request -> new GreetingReply(message));
        ReflectionTestUtils.setField(runner, "useDetails", true);

        assertThrows(IllegalStateException.class, () -> run(runner));

        assertThat(output).doesNotContain(DETAILS_MARKER, GREETING_MARKER, DENIAL_MARKER);
    }

    @Test
    void relayModeRetainsItsExistingMarker(CapturedOutput output) {
        GreetingRunner runner = runner(unusedGreeting(), unusedDetails());
        ReflectionTestUtils.setField(runner, "useRelay", true);
        ReflectionTestUtils.setField(runner, "relayGreetingService",
                (RelayGreetingService) request -> new GreetingReply("CHAIN_OK deadline propagated"));

        run(runner);

        assertThat(output).contains("RPC deadline chain completed: CHAIN_OK deadline propagated")
                .doesNotContain(DETAILS_MARKER, GREETING_MARKER, DENIAL_MARKER);
    }

    @Test
    void relayAndDetailsAreRejectedBeforeAnyRpcCall() {
        GreetingRunner runner = runner(unusedGreeting(), unusedDetails());
        ReflectionTestUtils.setField(runner, "useRelay", true);
        ReflectionTestUtils.setField(runner, "useDetails", true);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> run(runner));

        assertThat(failure).hasMessageContaining("otryx.rpc.quickstart.use-relay")
                .hasMessageContaining("otryx.rpc.quickstart.use-details");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expectedDenialAcceptsTheExactRemoteFailureForTheSelectedContract(
            boolean details, CapturedOutput output) {
        GreetingService greeting = details ? unusedGreeting() : request -> {
            throw authorizationDenied();
        };
        GreetingDetailsService extra = details ? request -> {
            throw authorizationDenied();
        } : unusedDetails();
        GreetingRunner runner = expectedDenialRunner(greeting, extra, details);

        run(runner);

        assertThat(output).contains(DENIAL_MARKER).doesNotContain(GREETING_MARKER, DETAILS_MARKER);
    }

    @Test
    void expectedDenialUnwrapsOnlyCompletionFailures(CapturedOutput output) {
        GreetingRunner runner = expectedDenialRunner(request -> {
            throw new CompletionException(new CompletionException(authorizationDenied()));
        }, unusedDetails(), false);

        run(runner);

        assertThat(output).contains(DENIAL_MARKER).doesNotContain(GREETING_MARKER, DETAILS_MARKER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedSuccessFailsTheAuthorizationNegativeCase(boolean details, CapturedOutput output) {
        GreetingRunner runner = expectedDenialRunner(
                request -> new GreetingReply("Hello OTRYX RPC"),
                request -> new GreetingReply("DETAILS_OK"), details);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> run(runner));

        assertThat(failure).hasMessageContaining("Expected RPC authorization denial");
        assertThat(output).doesNotContain(DENIAL_MARKER, GREETING_MARKER, DETAILS_MARKER);
    }

    @ParameterizedTest
    @MethodSource("otherFailures")
    void otherFailuresCannotSatisfyTheAuthorizationNegativeCase(RuntimeException failure, CapturedOutput output) {
        GreetingRunner runner = expectedDenialRunner(request -> {
            throw failure;
        }, unusedDetails(), false);

        assertSame(failure, assertThrows(RuntimeException.class, () -> run(runner)));

        assertThat(output).doesNotContain(DENIAL_MARKER, GREETING_MARKER, DETAILS_MARKER);
    }

    @Test
    void ordinaryModePropagatesAuthorizationDenial(CapturedOutput output) {
        RpcRemoteException failure = authorizationDenied();
        GreetingRunner runner = runner(request -> {
            throw failure;
        }, unusedDetails());

        assertSame(failure, assertThrows(RpcRemoteException.class, () -> run(runner)));

        assertThat(output).doesNotContain(DENIAL_MARKER, GREETING_MARKER, DETAILS_MARKER);
    }

    @Test
    void expectedDenialCannotRunWithAnUnboundedRepeatMode() {
        GreetingRunner runner = expectedDenialRunner(unusedGreeting(), unusedDetails(), false);
        ReflectionTestUtils.setField(runner, "repeatIntervalMillis", 1L);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> run(runner));

        assertThat(failure).hasMessageContaining("otryx.rpc.quickstart.expect-authorization-denied")
                .hasMessageContaining("otryx.rpc.quickstart.repeat-interval-millis");
    }

    @Test
    void discoveryUnavailableIsRetriedBeforeTheExactDenial(CapturedOutput output) {
        AtomicInteger attempts = new AtomicInteger();
        GreetingRunner runner = expectedDenialRunner(request -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RpcUnavailableException("Provider is not discovered yet");
            }
            throw authorizationDenied();
        }, unusedDetails(), false);

        run(runner);

        assertEquals(2, attempts.get());
        assertThat(output).contains(DENIAL_MARKER).doesNotContain(GREETING_MARKER, DETAILS_MARKER);
    }

    private static Stream<RuntimeException> otherFailures() {
        return Stream.of(
                new RpcRemoteException(RpcStatus.BAD_REQUEST, "RpcException", "RPC authorization denied."),
                new RpcRemoteException(RpcStatus.BAD_REQUEST, "RpcException", "Invalid payload"),
                new RpcRemoteException(RpcStatus.SERVICE_NOT_FOUND, "RpcException", AUTHORIZATION_DENIED_MESSAGE),
                new RpcRemoteException(RpcStatus.METHOD_NOT_FOUND, "RpcException", AUTHORIZATION_DENIED_MESSAGE),
                new RpcRemoteException(RpcStatus.BUSINESS_ERROR, "BusinessException", AUTHORIZATION_DENIED_MESSAGE),
                new RpcRemoteException(RpcStatus.INTERNAL_ERROR, "RpcException", AUTHORIZATION_DENIED_MESSAGE),
                new RpcRemoteException(RpcStatus.DEADLINE_EXCEEDED, "RpcException", AUTHORIZATION_DENIED_MESSAGE),
                new IllegalStateException(AUTHORIZATION_DENIED_MESSAGE),
                new IllegalStateException("Unexpected wrapper", authorizationDenied()),
                new CompletionException(new SSLHandshakeException("Certificate verification failed")),
                new CompletionException(new RpcRemoteException(
                        RpcStatus.BAD_REQUEST, "RpcException", "RPC authorization denied ")));
    }

    private static GreetingRunner expectedDenialRunner(
            GreetingService greeting, GreetingDetailsService details, boolean useDetails) {
        GreetingRunner runner = runner(greeting, details);
        ReflectionTestUtils.setField(runner, "useDetails", useDetails);
        ReflectionTestUtils.setField(runner, "expectAuthorizationDenied", true);
        return runner;
    }

    private static GreetingRunner runner(GreetingService greeting, GreetingDetailsService details) {
        GreetingRunner runner = new GreetingRunner();
        ReflectionTestUtils.setField(runner, "greetingService", greeting);
        ReflectionTestUtils.setField(runner, "greetingDetailsService", details);
        ReflectionTestUtils.setField(runner, "relayGreetingService", (RelayGreetingService) request -> {
            throw new AssertionError("Relay service must not be called");
        });
        return runner;
    }

    private static GreetingService unusedGreeting() {
        return request -> {
            throw new AssertionError("Greeting service must not be called");
        };
    }

    private static GreetingDetailsService unusedDetails() {
        return request -> {
            throw new AssertionError("Details service must not be called");
        };
    }

    private static RpcRemoteException authorizationDenied() {
        return new RpcRemoteException(RpcStatus.BAD_REQUEST, "RpcException", AUTHORIZATION_DENIED_MESSAGE);
    }

    private static void run(GreetingRunner runner) {
        runner.run(new DefaultApplicationArguments(new String[0]));
    }
}
