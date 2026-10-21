package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.api.OtryxRpcExecution;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.governance.RpcGovernanceMethodRule;
import com.peachsoft.otryx.governance.RpcGovernanceServiceRule;
import com.peachsoft.otryx.governance.RpcGovernanceSnapshot;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Provider 方法治理配额需保留到真正业务 Stage 与响应都结束，即使调用方提前取消。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:50
 */
class RpcGovernanceProviderIntegrationTest {
    private static final ServiceKey SERVICE = new ServiceKey(Api.class.getName(), "1.0.0", "default");

    @Test
    void providerQuotaMustNotReopenAfterCancelUntilActualBusinessStageFinishes() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var source = new RpcGovernanceTestFixtures.MemoryRepository();
            int methodId = RpcIds.methodId(Api.class.getMethod("call"));
            RpcGovernanceMethodRule limited = new RpcGovernanceMethodRule(
                    Duration.ofSeconds(2), 1, 0, null);
            source.initial.complete(RpcGovernanceTestFixtures.sign(new RpcGovernanceSnapshot(
                    1L, Map.of(SERVICE,
                            new RpcGovernanceServiceRule(null, Map.of(), Map.of(methodId, limited))))));
            try (var governance = new RpcGovernanceRuntime(source, RpcGovernanceTestFixtures.verifier(),
                    Duration.ofSeconds(1))) {
                governance.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
                var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
                var service = new SlowService();
                var server = control.serverBuilder(transport,
                        RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                        .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                        .authorizer(context -> CompletableFuture.completedFuture(
                                RpcAuthorizationDecision.allowUntil(context.maximumValidUntil())))
                        .governanceRuntime(governance).build();
                try {
                    server.registerService(Api.class, service, "1.0.0", "default");
                    server.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
                    byte[] request = request(methodId);
                    var peer = RpcSecurityTestFixtures.peer(RpcSecurityTestFixtures.CONSUMER,
                            control.clock, Duration.ofMinutes(1));
                    CompletableFuture<byte[]> first = transport.call(peer, request, Runnable::run)
                            .toCompletableFuture();
                    ApplicationTestControlPlane.await(() -> service.calls.get() == 1,
                            "First async business Stage did not start");
                    byte[] limitedResponse = transport.call(peer, request, Runnable::run)
                            .toCompletableFuture().get(2, TimeUnit.SECONDS);
                    assertEquals(RpcStatus.OVERLOADED, RpcProtocolCodec.view(limitedResponse).status());
                    first.cancel(true);
                    byte[] stillLimited = transport.call(peer, request, Runnable::run)
                            .toCompletableFuture().get(2, TimeUnit.SECONDS);
                    assertEquals(RpcStatus.OVERLOADED, RpcProtocolCodec.view(stillLimited).status());
                    assertEquals(1, service.calls.get());

                    service.pending.complete("ok");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    boolean recovered = false;
                    while (System.nanoTime() < deadline) {
                        byte[] response = transport.call(peer, request, Runnable::run)
                                .toCompletableFuture().get(2, TimeUnit.SECONDS);
                        if (RpcProtocolCodec.view(response).status() == RpcStatus.OK) {
                            recovered = true;
                            break;
                        }
                        Thread.sleep(2L);
                    }
                    assertTrue(recovered, "Governance must release only after actual async work is completed");
                    assertEquals(2, service.calls.get());
                } finally {
                    server.close();
                }
            }
        }
    }

    private static byte[] request(int methodId) {
        return RpcProtocolCodec.encodeRequest((byte) 1, RpcIds.serviceId(SERVICE), methodId,
                0L, 0L, Map.of(), new byte[] {1});
    }

    public interface Api {
        @OtryxRpcExecution(RpcExecutionMode.BLOCKING_VIRTUAL)
        CompletionStage<String> call();
    }

    public static final class SlowService implements Api {
        final CompletableFuture<String> pending = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<String> call() {
            calls.incrementAndGet();
            return pending;
        }
    }
}
