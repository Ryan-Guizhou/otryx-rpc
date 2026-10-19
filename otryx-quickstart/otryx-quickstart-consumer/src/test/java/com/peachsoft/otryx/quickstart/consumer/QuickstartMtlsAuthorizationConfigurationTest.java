package com.peachsoft.otryx.quickstart.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.core.RpcAuthorizer;
import com.peachsoft.otryx.quickstart.api.GreetingDetailsService;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.quickstart.api.RelayGreetingService;
import com.peachsoft.otryx.quickstart.provider.QuickstartMtlsAuthorizationConfiguration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

/**
 * 验证 mTLS 示例仅授权已验证指纹和完整 Greeting 服务方法，并在启动时严格校验配置。
 *
 * <p>测试直接使用可信 Transport 边界的身份值，不执行证书握手；同 CA 的真实握手
 * 与独立 JVM 授权由端到端脚本验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:05
 */
class QuickstartMtlsAuthorizationConfigurationTest {

    private static final String FINGERPRINT_PROPERTY =
            "otryx.rpc.quickstart.mtls.allowed-client-sha256";
    private static final String ALLOWED_FINGERPRINT = "0123456789abcdef".repeat(4);
    private static final ServiceKey GREETING_KEY =
            new ServiceKey(GreetingService.class.getName(), "1.0.0", "default");
    private static final RpcEndpoint REMOTE = new RpcEndpoint("127.0.0.1", 18081);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QuickstartMtlsAuthorizationConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"CN=quickstart-client", ""})
    void allowsVerifiedFingerprintAndGreetingWithoutDependingOnSubject(String principal) throws Exception {
        RpcAuthorizationContext request = new RpcAuthorizationContext(
                RpcPeerIdentity.verifiedClientCertificate(principal, ALLOWED_FINGERPRINT),
                GREETING_KEY, helloMethodId(), REMOTE);

        configuredRunner(ALLOWED_FINGERPRINT).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RpcAuthorizer.class);
            assertDecision(context.getBean(RpcAuthorizer.class), request, true);
        });
    }

    @ParameterizedTest
    @MethodSource("deniedRequests")
    void rejectsOtherIdentitiesOrServiceMethods(RpcAuthorizationContext request) {
        configuredRunner(ALLOWED_FINGERPRINT).run(context -> {
            assertThat(context).hasNotFailed();
            assertDecision(context.getBean(RpcAuthorizer.class), request, false);
        });
    }

    @Test
    void remoteAddressDoesNotGrantOrRemoveCertificatePermission() throws Exception {
        RpcAuthorizationContext request = new RpcAuthorizationContext(
                RpcPeerIdentity.verifiedClientCertificate("CN=quickstart-client", ALLOWED_FINGERPRINT),
                GREETING_KEY, helloMethodId(), new RpcEndpoint("198.51.100.42", 54321));

        configuredRunner(ALLOWED_FINGERPRINT).run(context ->
                assertDecision(context.getBean(RpcAuthorizer.class), request, true));
    }

    @Test
    void missingFingerprintFailsAtStartup() {
        mtlsRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining(FINGERPRINT_PROPERTY);
        });
    }

    @ParameterizedTest
    @MethodSource("invalidFingerprints")
    void invalidFingerprintFailsWithoutNormalizingOrEchoingItsValue(String fingerprint) {
        configuredRunner(fingerprint).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            while (failure.getCause() != null) {
                failure = failure.getCause();
            }
            assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FINGERPRINT_PROPERTY);
            if (!fingerprint.isBlank()) {
                assertThat(failure.getMessage()).doesNotContain(fingerprint);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "tls", "memory"})
    void otherProfilesDoNotInstallAnAuthorizerOrRequireFingerprint(String profile) {
        contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RpcAuthorizer.class));
    }

    private ApplicationContextRunner configuredRunner(String fingerprint) {
        return mtlsRunner().withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("testFingerprint", Map.of(FINGERPRINT_PROPERTY, fingerprint))));
    }

    private ApplicationContextRunner mtlsRunner() {
        return contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles("mtls"));
    }

    private static void assertDecision(RpcAuthorizer authorizer, RpcAuthorizationContext request, boolean allowed) {
        CompletableFuture<Boolean> decision = authorizer.authorize(request).toCompletableFuture();
        assertTrue(decision.isDone(), "The example policy must return an already completed decision");
        assertEquals(allowed, decision.join());
    }

    private static Stream<Arguments> deniedRequests() throws Exception {
        RpcPeerIdentity allowed = RpcPeerIdentity.verifiedClientCertificate(
                "CN=quickstart-client", ALLOWED_FINGERPRINT);
        int hello = helloMethodId();
        return Stream.of(
                Arguments.of(new RpcAuthorizationContext(
                        RpcPeerIdentity.verifiedClientCertificate("CN=quickstart-client", "f".repeat(64)),
                        GREETING_KEY, hello, REMOTE)),
                Arguments.of(new RpcAuthorizationContext(RpcPeerIdentity.anonymous(), GREETING_KEY, hello, REMOTE)),
                Arguments.of(new RpcAuthorizationContext(allowed,
                        new ServiceKey(RelayGreetingService.class.getName(), "1.0.0", "default"), hello, REMOTE)),
                Arguments.of(new RpcAuthorizationContext(allowed,
                        new ServiceKey(GreetingDetailsService.class.getName(), "1.0.0", "default"),
                        RpcIds.methodId(GreetingDetailsService.class.getMethod("details", GreetingRequest.class)),
                        REMOTE)),
                Arguments.of(new RpcAuthorizationContext(allowed,
                        new ServiceKey(GreetingService.class.getName(), "2.0.0", "default"), hello, REMOTE)),
                Arguments.of(new RpcAuthorizationContext(allowed,
                        new ServiceKey(GreetingService.class.getName(), "1.0.0", "other"), hello, REMOTE)),
                Arguments.of(new RpcAuthorizationContext(allowed, GREETING_KEY, hello + 1, REMOTE)));
    }

    private static Stream<String> invalidFingerprints() {
        return Stream.of("", " ", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64),
                "aa:".repeat(21) + "aa", " " + ALLOWED_FINGERPRINT, ALLOWED_FINGERPRINT + " ",
                "DO_NOT_LOG_TEST_CREDENTIAL");
    }

    private static int helloMethodId() throws NoSuchMethodException {
        return RpcIds.methodId(GreetingService.class.getMethod("hello", GreetingRequest.class));
    }
}
