package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.governance.RpcGovernanceCanonical;
import com.peachsoft.otryx.governance.RpcGovernanceMethodRule;
import com.peachsoft.otryx.governance.RpcGovernanceRepository;
import com.peachsoft.otryx.governance.RpcGovernanceServiceRule;
import com.peachsoft.otryx.governance.RpcGovernanceSignedUpdate;
import com.peachsoft.otryx.governance.RpcGovernanceSnapshot;
import com.peachsoft.otryx.governance.RpcHmacGovernanceVerifier;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 提供测试用 HMAC 签发者和完全位于进程内的独立控制面。
 *
 * <p>无网络、无伪造认证通道，签名后修改任意规则会被生产验证器拒绝。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:45
 */
final class RpcGovernanceTestFixtures {
    static final ServiceKey SERVICE = new ServiceKey("example.Greeting", "1.0.0", "default");
    static final String ISSUER = "test-admin";
    static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (byte) (i + 1);
        }
    }

    private RpcGovernanceTestFixtures() {
    }

    static RpcHmacGovernanceVerifier verifier() {
        return new RpcHmacGovernanceVerifier(ISSUER, KEY);
    }

    static RpcGovernanceSnapshot snapshot(long revision, int maxConcurrent, int failures, Duration timeout) {
        RpcGovernanceMethodRule method = new RpcGovernanceMethodRule(
                timeout, maxConcurrent, failures,
                failures == 0 ? null : Duration.ofSeconds(2));
        return new RpcGovernanceSnapshot(revision, Map.of(SERVICE,
                new RpcGovernanceServiceRule(null, Map.of(), Map.of(123, method))));
    }

    static RpcGovernanceSignedUpdate sign(RpcGovernanceSnapshot snapshot) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return new RpcGovernanceSignedUpdate(ISSUER, snapshot,
                    mac.doFinal(RpcGovernanceCanonical.encode(ISSUER, snapshot)));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid test HMAC", e);
        }
    }

    /** 进程内可推送有序完整快照的测试控制面。 */
    static final class MemoryRepository implements RpcGovernanceRepository {
        final CompletableFuture<RpcGovernanceSignedUpdate> initial = new CompletableFuture<>();
        final AtomicInteger loads = new AtomicInteger();
        volatile Consumer<RpcGovernanceSignedUpdate> updates;
        volatile Consumer<Failure> failures;
        volatile boolean closed;

        @Override
        public CompletionStage<RpcGovernanceSignedUpdate> load() {
            loads.incrementAndGet();
            return initial;
        }

        @Override
        public AutoCloseable watch(
                Consumer<RpcGovernanceSignedUpdate> updateConsumer, Consumer<Failure> failureConsumer) {
            updates = updateConsumer;
            failures = failureConsumer;
            return () -> closed = true;
        }

        void send(RpcGovernanceSignedUpdate next) {
            updates.accept(next);
        }

        void unavailable() {
            failures.accept(Failure.UNAVAILABLE);
        }
    }
}
