package com.peachsoft.otryx.registry.etcd;

import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.ENDPOINT;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.NAMESPACE;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.PROVIDER;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.await;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.path;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.registry;
import static com.peachsoft.otryx.registry.etcd.EtcdSecurityTestFixture.rejection;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 在明确要求客户端证书的独立真实 Etcd listener 验证缺证书和密码角色边界。
 *
 * <p>必须由 fixture --require-client-cert 显式运行；错误 listener 会导致断言失败。
 * 客户端证书 CN 没有对应 RBAC 用户，不能绕过后端用户密码权限。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:59
 */
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class EtcdRegistryClientCertificateIT {

    @Test
    void requiredClientCertificateRejectsMissingIdentityAndAcceptsInstalledIdentity() throws Exception {
        try (EtcdApplicationRegistry missing = registry("observer")) {
            rejection(RegistrySecurityFailure.TLS_REJECTED, missing.lookup(PROVIDER));
        }
        try (EtcdApplicationRegistry accepted = registry("observer", NAMESPACE, ENDPOINT,
                path("ETCD_TLS_TEST_CA_FILE"), true, false)) {
            assertTrue(await(accepted.lookup(PROVIDER)).instances().isEmpty());
        }
    }

    @Test
    void clientCertificateCannotReplaceAValidBackendPassword() throws Exception {
        try (EtcdApplicationRegistry rejected = registry("observer", NAMESPACE, ENDPOINT,
                path("ETCD_TLS_TEST_CA_FILE"), true, true)) {
            rejection(RegistrySecurityFailure.AUTHENTICATION_REJECTED, rejected.lookup(PROVIDER));
        }
    }
}
