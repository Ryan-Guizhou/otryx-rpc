package com.peachsoft.otryx.registry.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.test.EtcdClusterExtension;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 三节点真实 Etcd 的 V2 应用级控制面 Leader 转移故障注入。
 *
 * <p>使用 Maven -Petcd-chaos 单独运行，避免多节点 Cluster 生命周期
 * 阻塞普通 PR 单节点测试。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
@Tag("chaos")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EtcdApplicationRegistryChaosTest {

    @RegisterExtension
    static final EtcdClusterExtension CLUSTER = EtcdClusterExtension.builder()
            .withClusterName("otryx-v2-chaos-" + UUID.randomUUID())
            .withNodes(3)
            .withSsl(false)
            .build();

    @Test
    void leaderTransferKeepsProviderConsumerMembershipAndOwnership() throws Exception {
        String namespace = "v2-leader-" + UUID.randomUUID();
        ApplicationKey app = new ApplicationKey(namespace, "billing");
        ServiceKey key = new ServiceKey("demo.Billing", "1.0.0", "default");
        ApplicationInstance provider = new ApplicationInstance("provider-a", app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 18401), 100, "sha256:" + "b".repeat(64), Map.of());
        ApplicationInstance consumer = new ApplicationInstance("consumer-a", app, ApplicationRole.CONSUMER,
                null, 0, null, Map.of());

        try (EtcdApplicationRegistry first = registry(namespace);
             EtcdApplicationRegistry second = registry(namespace);
             Client raw = client()) {
            ServiceMappingOwner owner = first.serviceMappingRepository().claim(app, provider.instanceId())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            first.serviceMappingRepository().publish(owner, Set.of(key))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            first.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            second.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);

            long originalLeader = leader(raw);
            long target = 0;
            URI leaderEndpoint = null;
            for (URI endpoint : CLUSTER.clientEndpoints()) {
                var status = raw.getMaintenanceClient().statusMember(endpoint.toString())
                        .get(10, TimeUnit.SECONDS);
                if (status.getHeader().getMemberId() == originalLeader) {
                    leaderEndpoint = endpoint;
                } else {
                    target = status.getHeader().getMemberId();
                }
            }
            assertTrue(leaderEndpoint != null && target != 0);
            try (Client currentLeader = Client.builder().endpoints(leaderEndpoint).build()) {
                currentLeader.getMaintenanceClient().moveLeader(target).get(15, TimeUnit.SECONDS);
            }
            assertTrue(await(Duration.ofSeconds(15), () -> leader(raw) != originalLeader));
            assertTrue(await(Duration.ofSeconds(20), () ->
                    first.lookup(app).toCompletableFuture().join().instances()
                            .containsAll(Set.of(provider, consumer))));
            assertEquals(Set.of(app), first.serviceMappingRepository()
                    .lookup(new ServiceMappingKey(namespace, key))
                    .toCompletableFuture().join().applications());
        }
    }

    private static long leader(Client client) throws Exception {
        return client.getMaintenanceClient()
                .statusMember(CLUSTER.clientEndpoints().getFirst().toString())
                .get(10, TimeUnit.SECONDS).getLeader();
    }

    private static EtcdApplicationRegistry registry(String namespace) {
        return new EtcdApplicationRegistry(
                CLUSTER.clientEndpoints().stream().map(URI::toString).toArray(String[]::new),
                5, namespace);
    }

    private static Client client() {
        return Client.builder().endpoints(CLUSTER.clientEndpoints().toArray(URI[]::new)).build();
    }

    private static boolean await(Duration timeout, CheckedBoolean check) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.accept()) {
                return true;
            }
            Thread.sleep(100);
        }
        return check.accept();
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean accept() throws Exception;
    }
}
