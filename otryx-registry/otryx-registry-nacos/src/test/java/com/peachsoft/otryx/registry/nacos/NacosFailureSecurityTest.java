package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alibaba.nacos.api.exception.NacosException;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * 验证 Nacos 公共异步边界保留稳定状态而不传播 SDK 正文、cause 或 suppressed。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 12:14
 */
class NacosFailureSecurityTest {
    private static final String SECRET = "nacos-password-and-token-secret";

    @Test
    void credentialFailuresKeepStableReasonsAndDiscardSdkDetails() {
        for (int code : new int[] {401, 403}) {
            NacosException source = new NacosException(code, SECRET);
            source.addSuppressed(new IllegalArgumentException(SECRET));
            RegistrySecurityException failure = assertInstanceOf(RegistrySecurityException.class,
                    NacosFailures.normalize(new CompletionException(source)));
            assertEquals(code == 401 ? RegistrySecurityFailure.AUTHENTICATION_REJECTED
                    : RegistrySecurityFailure.AUTHORIZATION_REJECTED, failure.reason());
            assertSafe(failure);
        }
    }

    @Test
    void arbitraryBackendTextDoesNotTurnAnOrdinaryFailureIntoAuthorization() {
        Throwable safe = NacosFailures.normalize(new NacosException(500, "permission denied: " + SECRET));
        assertInstanceOf(IllegalStateException.class, safe);
        assertSafe(safe);
    }

    private static void assertSafe(Throwable failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains(SECRET));
    }
}
