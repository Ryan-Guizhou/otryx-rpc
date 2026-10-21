package com.peachsoft.otryx.quickstart.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.api.RpcCertificateBinding;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
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
 * 验证示例策略仅授权完整应用及 Greeting 方法，支持身份不变的证书轮换并限制许可期限。
 *
 * <p>身份对象为 Core 边界的单元夹具；真实证书验证、绑定和独立 JVM 行为由 Transport
 * 及端到端测试另行证明，本测试不把构造身份值当作 TLS 握手证据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:05
 */
class QuickstartMtlsAuthorizationConfigurationTest {

    private static final String PREFIX = "otryx.rpc.quickstart.mtls.";
    private static final Instant RECEIVED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant REQUEST_LIMIT = RECEIVED.plusSeconds(30);
    private static final ApplicationKey CONSUMER = new ApplicationKey("security-test", "consumer");
    private static final ApplicationKey PROVIDER = new ApplicationKey("security-test", "provider");
    private static final ServiceKey GREETING_KEY =
            new ServiceKey(GreetingService.class.getName(), "1.0.0", "default");
    private static final RpcEndpoint REMOTE = new RpcEndpoint("127.0.0.1", 18081);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QuickstartMtlsAuthorizationConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"a", "b"})
    void rotatedCertificatesBoundToTheSameApplicationReceiveTheOriginalRequestLimit(String hex) throws Exception {
        RpcAuthorizationContext request = request(peer(hex, CONSUMER), GREETING_KEY, helloMethodId(), REMOTE);
        configuredRunner(RECEIVED.plusSeconds(60)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RpcAuthorizer.class);
            RpcAuthorizationDecision decision = decision(context.getBean(RpcAuthorizer.class), request);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.validUntil()).contains(REQUEST_LIMIT);
        });
    }

    @Test
    void policyExpiryCapsTheGrantBeforeTheRequestLimit() throws Exception {
        Instant policyExpiry = RECEIVED.plusSeconds(5);
        RpcAuthorizationContext request = request(peer("a", CONSUMER), GREETING_KEY, helloMethodId(), REMOTE);
        configuredRunner(policyExpiry).run(context ->
                assertThat(decision(context.getBean(RpcAuthorizer.class), request).validUntil())
                        .contains(policyExpiry));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L})
    void finiteExpiredPolicyStartsButDeniesTheRequest(long seconds) throws Exception {
        RpcAuthorizationContext request = request(peer("a", CONSUMER), GREETING_KEY, helloMethodId(), REMOTE);
        configuredRunner(RECEIVED.plusSeconds(seconds)).run(context -> {
            assertThat(context).hasNotFailed();
            assertEquals(RpcAuthorizationDecision.deny(), decision(context.getBean(RpcAuthorizer.class), request));
        });
    }

    @ParameterizedTest
    @MethodSource("deniedRequests")
    void rejectsOtherApplicationsOrServiceMethods(RpcAuthorizationContext request) {
        configuredRunner(REQUEST_LIMIT).run(context -> {
            assertThat(context).hasNotFailed();
            assertEquals(RpcAuthorizationDecision.deny(), decision(context.getBean(RpcAuthorizer.class), request));
        });
    }

    @Test
    void remoteAddressDoesNotChangeApplicationPermission() throws Exception {
        RpcAuthorizationContext request = request(peer("a", CONSUMER), GREETING_KEY, helloMethodId(),
                new RpcEndpoint("198.51.100.42", 54321));
        configuredRunner(REQUEST_LIMIT).run(context ->
                assertThat(decision(context.getBean(RpcAuthorizer.class), request).allowed()).isTrue());
    }

    @Test
    void missingPolicyFailsAtStartup() {
        mtlsRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("Invalid Quickstart mTLS allowed-consumer or policy-valid-until");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "DO_NOT_LOG_TEST_CREDENTIAL", "+1000000000-12-31T23:59:59.999999999Z"})
    void invalidPolicyIsRejectedWithoutEchoingTheValueOrCause(String expiry) {
        configuredRunner(REQUEST_LIMIT).withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("invalidPolicy", Map.of(PREFIX + "policy-valid-until", expiry))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                    if (!expiry.isBlank()) {
                        StringWriter trace = new StringWriter();
                        context.getStartupFailure().printStackTrace(new PrintWriter(trace));
                        assertThat(trace.toString()).contains("Invalid Quickstart mTLS").doesNotContain(expiry);
                    }
                });
    }

    @Test
    void explicitPolicyDisableLeavesNoAuthorizerForStarterToReject() {
        configuredRunner(REQUEST_LIMIT).withPropertyValues(PREFIX + "authorization-enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RpcAuthorizer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "tls", "memory"})
    void otherProfilesDoNotInstallAnAuthorizerOrRequireItsConfiguration(String profile) {
        contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RpcAuthorizer.class));
    }

    private ApplicationContextRunner configuredRunner(Instant expiry) {
        return mtlsRunner().withPropertyValues(
                PREFIX + "allowed-consumer.namespace=" + CONSUMER.namespace(),
                PREFIX + "allowed-consumer.application-name=" + CONSUMER.applicationName(),
                PREFIX + "policy-valid-until=" + expiry);
    }

    private ApplicationContextRunner mtlsRunner() {
        return contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles("mtls"));
    }

    private static RpcAuthorizationDecision decision(RpcAuthorizer authorizer, RpcAuthorizationContext request) {
        CompletableFuture<RpcAuthorizationDecision> decision = authorizer.authorize(request).toCompletableFuture();
        assertTrue(decision.isDone(), "The example policy must return an already completed decision");
        return decision.join();
    }

    private static Stream<Arguments> deniedRequests() throws Exception {
        RpcPeerIdentity allowed = peer("a", CONSUMER);
        int hello = helloMethodId();
        return Stream.of(
                Arguments.of(request(peer("a", new ApplicationKey("security-test", "other")),
                        GREETING_KEY, hello, REMOTE)),
                Arguments.of(request(peer("a", new ApplicationKey("other", "consumer")),
                        GREETING_KEY, hello, REMOTE)),
                Arguments.of(request(RpcPeerIdentity.anonymous(), GREETING_KEY, hello, REMOTE)),
                Arguments.of(request(allowed,
                        new ServiceKey(RelayGreetingService.class.getName(), "1.0.0", "default"), hello, REMOTE)),
                Arguments.of(request(allowed,
                        new ServiceKey(GreetingDetailsService.class.getName(), "1.0.0", "default"),
                        RpcIds.methodId(GreetingDetailsService.class.getMethod("details", GreetingRequest.class)),
                        REMOTE)),
                Arguments.of(request(allowed,
                        new ServiceKey(GreetingService.class.getName(), "2.0.0", "default"), hello, REMOTE)),
                Arguments.of(request(allowed,
                        new ServiceKey(GreetingService.class.getName(), "1.0.0", "other"), hello, REMOTE)),
                Arguments.of(request(allowed, GREETING_KEY, hello + 1, REMOTE)));
    }

    private static RpcPeerIdentity peer(String hex, ApplicationKey application) {
        return RpcPeerIdentity.verifiedMutualTls(
                new RpcCertificateBinding(hex.repeat(64), application, RECEIVED.plusSeconds(120)),
                RECEIVED.minusSeconds(60), RECEIVED.plusSeconds(120), RECEIVED, 10L);
    }

    private static RpcAuthorizationContext request(
            RpcPeerIdentity peer, ServiceKey key, int method, RpcEndpoint remote) {
        return new RpcAuthorizationContext(peer, PROVIDER, key, method, remote, RECEIVED, REQUEST_LIMIT);
    }

    private static int helloMethodId() throws NoSuchMethodException {
        return RpcIds.methodId(GreetingService.class.getMethod("hello", GreetingRequest.class));
    }
}
