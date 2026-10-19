package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcCertificateBinding;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistryCredentials;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证证书绑定列表、有限许可及有类型凭据的真实 Spring 绑定和防御性复制。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:32
 */
class OtryxRpcSecurityPropertiesTest {
    private static final ApplicationKey APPLICATION = new ApplicationKey("test", "consumer");
    private static final Instant EXPIRY = Instant.parse("2100-01-01T00:00:00Z");
    private static final String SECRET = "synthetic-private-credential";

    @Test
    void duplicateFingerprintsMustBeCheckedBeforeSetConversion() {
        OtryxRpcIdentityProperties identity = new OtryxRpcIdentityProperties();
        RpcCertificateBinding first = new RpcCertificateBinding("a".repeat(64), APPLICATION, EXPIRY);
        RpcCertificateBinding same = new RpcCertificateBinding("A".repeat(64), APPLICATION, EXPIRY);
        identity.setCertificateBindings(List.of(first, same));
        assertThat(identity.getCertificateBindings()).hasSize(2);
        assertThat(identity.validatedBindings()).containsExactly(first);
        RpcCertificateBinding conflict = new RpcCertificateBinding("A".repeat(64),
                new ApplicationKey("test", "another-consumer"), EXPIRY);
        identity.setCertificateBindings(List.of(first, conflict));
        assertThatThrownBy(identity::validatedBindings).hasMessageContaining("conflicting fingerprint");
        identity.setCertificateBindings(List.of(first,
                new RpcCertificateBinding("a".repeat(64), APPLICATION, EXPIRY.minusSeconds(1))));
        assertThatThrownBy(identity::validatedBindings).hasMessageContaining("conflicting fingerprint");
    }

    @Test
    void oneApplicationCanHaveTwoDistinctRotationBindings() {
        OtryxRpcIdentityProperties identity = new OtryxRpcIdentityProperties();
        identity.setCertificateBindings(List.of(
                new RpcCertificateBinding("a".repeat(64), APPLICATION, EXPIRY),
                new RpcCertificateBinding("b".repeat(64), APPLICATION, EXPIRY)));
        assertThat(identity.validatedBindings()).hasSize(2);
    }

    @Test
    void springBindsRecordApplicationAndFiniteInstantInsideList() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "identity.certificate-bindings[0].certificate-sha256", "A".repeat(64),
                "identity.certificate-bindings[0].application.namespace", "test",
                "identity.certificate-bindings[0].application.application-name", "consumer",
                "identity.certificate-bindings[0].valid-until", EXPIRY.toString()));
        OtryxRpcIdentityProperties properties = new Binder(source)
                .bind("identity", Bindable.of(OtryxRpcIdentityProperties.class)).get();
        assertThat(properties.validatedBindings()).containsExactly(
                new RpcCertificateBinding("a".repeat(64), APPLICATION, EXPIRY));
    }

    @Test
    void typedRegistryCredentialsBindToDefensiveCharacterCopies() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "security.credentials.kind", "USER_PASSWORD",
                "security.credentials.username", "fixture-user",
                "security.credentials.password", SECRET));
        OtryxRpcRegistrySecurityProperties properties = new Binder(source)
                .bind("security", Bindable.of(OtryxRpcRegistrySecurityProperties.class)).get();
        RegistryCredentials credentials = properties.toOptions().credentials();
        assertThat(credentials.kind()).isEqualTo(CredentialKind.USER_PASSWORD);
        assertThat(credentials.password()).containsExactly(SECRET.toCharArray());
        char[] getterCopy = properties.getCredentials().getPassword();
        getterCopy[0] = 'X';
        assertThat(properties.toOptions().credentials().password()).containsExactly(SECRET.toCharArray());
        char[] setterInput = SECRET.toCharArray();
        properties.getCredentials().setPassword(setterInput);
        setterInput[0] = 'Y';
        assertThat(properties.toOptions().credentials().password()).containsExactly(SECRET.toCharArray());
        assertThat(properties.toString()).doesNotContain(SECRET, "fixture-user");
        assertThat(properties.getCredentials().toString()).doesNotContain(SECRET, "fixture-user");
    }

    @Test
    void contradictoryCredentialFieldsFailWithFixedRedactedReason() {
        OtryxRpcRegistrySecurityProperties properties = new OtryxRpcRegistrySecurityProperties();
        properties.getCredentials().setPassword(SECRET.toCharArray());
        assertThatThrownBy(properties::toOptions)
                .hasMessage("Invalid otryx.rpc.registry.security: TLS or credentials rejected")
                .hasNoCause();
        properties.getCredentials().setKind(CredentialKind.USER_PASSWORD);
        properties.getCredentials().setUsername("fixture-user");
        properties.getCredentials().setToken(SECRET.toCharArray());
        assertThatThrownBy(properties::toOptions)
                .hasMessage("Invalid otryx.rpc.registry.security: TLS or credentials rejected")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "86400001ms"})
    void authorizationGrantLifetimeMustBePositiveAndAtMostOneDay(String value) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OtryxRpcAutoConfiguration.class))
                .withPropertyValues("otryx.rpc.security-profile=TRUSTED_DEVELOPMENT",
                        "spring.application.name=finite-grant-test",
                        "otryx.rpc.server.authorization.max-grant-lifetime=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("max-grant-lifetime");
                });
    }

    @Test
    void independentMetadataCredentialsCannotBeConfiguredForAnotherBackend() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OtryxRpcAutoConfiguration.class))
                .withPropertyValues("otryx.rpc.security-profile=TRUSTED_DEVELOPMENT",
                        "spring.application.name=wrong-metadata-chain",
                        "otryx.rpc.registry.metadata-security.credentials.kind=ACL_TOKEN",
                        "otryx.rpc.registry.metadata-security.credentials.token=" + SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("registry.metadata-security")
                            .hasStackTraceContaining("only supported for Eureka")
                            .hasMessageNotContaining(SECRET);
                });
    }
}
