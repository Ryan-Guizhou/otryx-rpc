package com.peachsoft.otryx.registry.etcd;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import io.etcd.jetcd.common.exception.EtcdExceptionFactory;
import io.grpc.Status;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * 验证固定 jetcd 异常的实际状态与 TLS 原因分类，拒绝用服务器错误正文猜测安全语义。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 12:14
 */
class EtcdFailureSecurityTest {
    private static final String SECRET = "backend-credential-and-url-secret";

    @Test
    void realSdkStatusTypesPreserveAuthenticationAuthorizationAndLimitReasons() {
        assertReason(Status.UNAUTHENTICATED, RegistrySecurityFailure.AUTHENTICATION_REJECTED);
        assertReason(Status.PERMISSION_DENIED, RegistrySecurityFailure.AUTHORIZATION_REJECTED);
        assertReason(Status.RESOURCE_EXHAUSTED, RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
    }

    @Test
    void sdkPreservedHandshakeCauseBecomesTlsRejectionWithoutExposingTheCause() {
        Throwable source = EtcdExceptionFactory.toEtcdException(Status.UNAVAILABLE
                .withDescription(SECRET).withCause(new SSLHandshakeException(SECRET)));
        RegistrySecurityException failure = assertInstanceOf(RegistrySecurityException.class,
                EtcdFailures.normalize(source));
        assertEquals(RegistrySecurityFailure.TLS_REJECTED, failure.reason());
        assertSafe(failure);
    }

    @Test
    void arbitraryInvalidArgumentTextCannotPretendToBeAuthentication() {
        Throwable source = EtcdExceptionFactory.toEtcdException(Status.INVALID_ARGUMENT
                .withDescription("authentication failed: " + SECRET));
        Throwable safe = EtcdFailures.normalize(source);
        assertInstanceOf(IOException.class, safe);
        assertSafe(safe);
    }

    @Test
    void syntheticAuthenticationDeadlineDoesNotBecomeCredentialRejection() {
        assertSyntheticUnavailable(Status.DEADLINE_EXCEEDED.withDescription(SECRET).asRuntimeException());
    }

    @Test
    void syntheticAuthenticationUnavailableDoesNotBecomeCredentialRejection() {
        assertSyntheticUnavailable(Status.UNAVAILABLE.withDescription(SECRET).asRuntimeException());
    }

    @Test
    void syntheticAuthenticationSetupFailureDoesNotBecomeSecurityRejection() {
        assertAll(
                () -> assertSyntheticUnavailable(new RejectedExecutionException(SECRET)),
                () -> assertSyntheticUnavailable(new IllegalArgumentException(SECRET)));
    }

    @Test
    void syntheticAuthenticationCauseLimitAndCycleDoNotBecomeCredentialRejection() {
        Throwable deep = Status.UNAUTHENTICATED.withDescription(SECRET).asRuntimeException();
        for (int depth = 0; depth < 40; depth++) {
            deep = new CompletionException(SECRET, deep);
        }
        Throwable limited = deep;
        IllegalStateException first = new IllegalStateException(SECRET);
        IllegalStateException second = new IllegalStateException(SECRET);
        first.initCause(second);
        second.initCause(first);
        assertAll(
                () -> assertSyntheticUnavailable(limited),
                () -> assertSyntheticUnavailable(first));
    }

    @Test
    void wrappedAuthenticationRejectionRetainsSanitizedSecurityReason() {
        Status rejected = syntheticAuthentication(
                Status.UNAUTHENTICATED.withDescription(SECRET).asRuntimeException());
        assertAll(
                () -> assertAuthenticationRejection(rejected.asRuntimeException()),
                () -> assertAuthenticationRejection(EtcdExceptionFactory.toEtcdException(rejected)),
                () -> assertAuthenticationRejection(wrapped(rejected.asRuntimeException())),
                () -> assertAuthenticationRejection(wrapped(EtcdExceptionFactory.toEtcdException(rejected))));
    }

    private static void assertSyntheticUnavailable(Throwable cause) {
        Status synthetic = syntheticAuthentication(cause);
        assertAll(
                () -> assertUnavailable(synthetic.asRuntimeException()),
                () -> assertUnavailable(EtcdExceptionFactory.toEtcdException(synthetic)),
                () -> assertUnavailable(wrapped(synthetic.asRuntimeException())),
                () -> assertUnavailable(wrapped(EtcdExceptionFactory.toEtcdException(synthetic))));
    }

    private static Status syntheticAuthentication(Throwable cause) {
        cause.addSuppressed(new IllegalStateException(SECRET));
        // jetcd AuthCredential 将任意 Authenticate 失败放入父请求的 UNAUTHENTICATED cause。
        return Status.UNAUTHENTICATED.withDescription(SECRET).withCause(cause);
    }

    private static Throwable wrapped(Throwable source) {
        return new CompletionException(SECRET, new ExecutionException(SECRET, source));
    }

    private static void assertUnavailable(Throwable source) {
        Throwable safe = EtcdFailures.normalize(source);
        assertInstanceOf(IOException.class, safe);
        assertSafe(safe);
    }

    private static void assertAuthenticationRejection(Throwable source) {
        RegistrySecurityException failure = assertInstanceOf(RegistrySecurityException.class,
                EtcdFailures.normalize(source));
        assertEquals(RegistrySecurityFailure.AUTHENTICATION_REJECTED, failure.reason());
        assertSafe(failure);
    }

    private static void assertReason(Status status, RegistrySecurityFailure reason) {
        RegistrySecurityException failure = assertInstanceOf(RegistrySecurityException.class,
                EtcdFailures.normalize(EtcdExceptionFactory.toEtcdException(status.withDescription(SECRET))));
        assertEquals(reason, failure.reason());
        assertSafe(failure);
    }

    private static void assertSafe(Throwable failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains(SECRET));
    }
}
