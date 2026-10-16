package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 使用真实开启 ACL default-deny 的 Consul 验证授权访问、拒绝与凭据隔离。
 *
 * <p>凭据仅从进程环境获取；禁止把管理 Token 放入 Maven 参数、日志或仓库。
 * 测试只使用临时的隔离 Consul 容器，不能连接生产数据中心。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:19
 */
@EnabledIfEnvironmentVariable(named = "CONSUL_TEST_ACL_TOKEN", matches = ".+")
@Timeout(90)
class ConsulApplicationRegistryAclIntegrationTest {

    private static final String ENDPOINT = "http://127.0.0.1:8501";
    private static final String ACL_TOKEN_ENV = "CONSUL_TEST_ACL_TOKEN";

    @Test
    void credentialsAuthorizeRealMemberManifestAndMappingOperations() {
        String namespace = "acl-" + UUID.randomUUID().toString().substring(0, 8);
        ApplicationKey application = new ApplicationKey(namespace, "secureapp");
        ServiceKey key = new ServiceKey("demo.AclService", "1.0.0", "default");
        ApplicationManifest manifest = ApplicationManifest.create(application, List.of(
                new ExportedService(key, RpcIds.serviceId(key), Set.of(1), "a".repeat(64))));
        ApplicationInstance provider = new ApplicationInstance("node-1", application,
                ApplicationRole.PROVIDER, new RpcEndpoint("127.0.0.1", 19201), 100,
                manifest.revision(), Map.of());

        try (ConsulApplicationRegistry authorized = registry(namespace, System.getenv(ACL_TOKEN_ENV));
             ConsulApplicationRegistry denied = registry(namespace, "")) {
            authorized.manifestRepository().publish(manifest).toCompletableFuture().join();
            assertEquals(manifest, authorized.manifestRepository()
                    .lookupVerified(application, manifest.revision()).toCompletableFuture()
                    .join().orElseThrow());
            ServiceMappingOwner owner = authorized.serviceMappingRepository()
                    .claim(application, provider.instanceId()).toCompletableFuture().join();
            authorized.serviceMappingRepository().publish(owner, Set.of(key))
                    .toCompletableFuture().join();
            authorized.register(provider).toCompletableFuture().join();
            assertTrue(authorized.lookup(application).toCompletableFuture().join()
                    .instances().contains(provider));
            assertEquals(Set.of(application), authorized.serviceMappingRepository()
                    .lookup(new ServiceMappingKey(namespace, key)).toCompletableFuture()
                    .join().applications());

            assertThrows(CompletionException.class,
                    () -> denied.manifestRepository().publish(manifest).toCompletableFuture().join());
            assertThrows(CompletionException.class,
                    () -> denied.serviceMappingRepository()
                            .claim(application, "unauthorized").toCompletableFuture().join());
            assertThrows(CompletionException.class,
                    () -> denied.register(new ApplicationInstance("unauthorized", application,
                            ApplicationRole.CONSUMER, null, 0, null, Map.of()))
                            .toCompletableFuture().join());
            assertTrue(!new RegistryOptions(List.of(ENDPOINT), namespace,
                    Map.of("consulToken", System.getenv(ACL_TOKEN_ENV))).toString()
                    .contains(System.getenv(ACL_TOKEN_ENV)));
        }
    }

    private static ConsulApplicationRegistry registry(String namespace, String token) {
        return (ConsulApplicationRegistry) new ConsulApplicationRegistryFactory().create(
                new RegistryOptions(List.of(ENDPOINT), namespace, Map.of(
                        "consulToken", token,
                        "consulTtlSeconds", "10",
                        "consulHeartbeatSeconds", "1",
                        "consulRequestTimeoutMillis", "3000")));
    }
}
