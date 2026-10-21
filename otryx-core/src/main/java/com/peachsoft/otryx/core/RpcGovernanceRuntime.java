package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcOverloadedException;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.governance.RpcGovernanceMethodRule;
import com.peachsoft.otryx.governance.RpcGovernanceRepository;
import com.peachsoft.otryx.governance.RpcGovernanceServiceRule;
import com.peachsoft.otryx.governance.RpcGovernanceSignedUpdate;
import com.peachsoft.otryx.governance.RpcGovernanceSnapshot;
import com.peachsoft.otryx.governance.RpcGovernanceVerifier;
import com.peachsoft.otryx.routing.RuleBasedRpcRouter;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 签名治理配置的有界异步控制面与同 revision 数据面。
 *
 * <p>只允许完整有权限签名事件更新全部路由/超时/熔断/配额规则。
 * 发布由单线程控制池预编译后一次 CAS 完成，消费调用仅从同一 State 获取规则。
 * 离线 LKG 有固定上限，安全失败立即停止提供治理配置；不会覆盖 TLS、
 * Codec、Frame 或静态原始 Provider Admission 限额。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
public final class RpcGovernanceRuntime implements AutoCloseable {
    private static final int MAX_UPDATE_QUEUE = 64;
    private static final int MAX_TRACKED_METHODS = 8192;
    private static final Duration MAX_LKG = Duration.ofMinutes(30);
    private static final String WORKER_NAME = "otryx-governance-control";
    private final RpcGovernanceRepository source;
    private final RpcGovernanceVerifier verifier;
    private final long maxLkgNanos;
    private final ThreadPoolExecutor controls;
    private final ConcurrentMap<Method, AtomicInteger> inflight = new ConcurrentHashMap<>();
    private final AtomicReference<State> active = new AtomicReference<>(State.EMPTY);
    private final AtomicLong failureAtNanos = new AtomicLong();
    private final AtomicBoolean securityRejected = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile AutoCloseable watch;

    /**
     * 创建独立治理控制池，源/验证器必须由受信宿主显式提供。
     *
     * @param source 可非阻塞订阅的有界治理配置源
     * @param verifier 覆盖每个治理字段的签名与权限验证器
     * @param maxLastKnownGood 单次普通失联保留最近有效规则的最大时长
     */
    public RpcGovernanceRuntime(
            RpcGovernanceRepository source, RpcGovernanceVerifier verifier, Duration maxLastKnownGood) {
        this.source = Objects.requireNonNull(source, "source");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        if (maxLastKnownGood == null || maxLastKnownGood.isZero() || maxLastKnownGood.isNegative()
                || maxLastKnownGood.compareTo(MAX_LKG) > 0) {
            throw new IllegalArgumentException("Invalid governance LKG window");
        }
        maxLkgNanos = maxLastKnownGood.toNanos();
        controls = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_UPDATE_QUEUE), task -> {
                    Thread thread = new Thread(task, WORKER_NAME);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 异步注册订阅并读取第一个完整快照；热路径在就绪前立即拒绝调用。
     *
     * @return 首个成功验证的快照就绪信号
     */
    public CompletionStage<Void> start() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new RpcUnavailableException("Governance runtime is closed"));
        }
        if (started.compareAndSet(false, true)) {
            schedule(() -> {
                try {
                    watch = Objects.requireNonNull(
                            source.watch(this::accept, this::fail), "governance watch");
                    CompletionStage<RpcGovernanceSignedUpdate> first = source.load();
                    if (first == null) {
                        fail(RpcGovernanceRepository.Failure.SECURITY_REJECTED);
                    } else {
                        first.whenComplete((value, failure) -> {
                            if (failure != null || value == null) {
                                fail(RpcGovernanceRepository.Failure.UNAVAILABLE);
                            } else {
                                accept(value);
                            }
                        });
                    }
                } catch (Throwable failure) {
                    fail(RpcGovernanceRepository.Failure.SECURITY_REJECTED);
                }
            });
        }
        return ready;
    }

    /**
     * 从 Watch 到控制池的无阻塞投递；只传完整不可变签名更新。
     *
     * @param update 不信任的远端事件
     */
    public void accept(RpcGovernanceSignedUpdate update) {
        if (update == null) {
            fail(RpcGovernanceRepository.Failure.SECURITY_REJECTED);
            return;
        }
        schedule(() -> publishVerified(update));
    }

    /**
     * 设置控制面失败类别，普通失联不刷新原 LKG 时间。
     *
     * @param failure 低基数故障类型
     */
    public void fail(RpcGovernanceRepository.Failure failure) {
        if (failure == RpcGovernanceRepository.Failure.SECURITY_REJECTED || failure == null) {
            securityRejected.set(true);
        } else {
            long now = System.nanoTime();
            failureAtNanos.compareAndSet(0L, now == 0L ? 1L : now);
        }
    }

    private void schedule(Runnable operation) {
        if (closed.get()) {
            return;
        }
        try {
            controls.execute(operation);
        } catch (RejectedExecutionException rejection) {
            securityRejected.set(true);
        }
    }

    private void publishVerified(RpcGovernanceSignedUpdate update) {
        if (closed.get()) {
            return;
        }
        boolean verified;
        try {
            verified = verifier.verify(update);
        } catch (Throwable failure) {
            verified = false;
        }
        if (!verified) {
            securityRejected.set(true);
            return;
        }
        State previous = active.get();
        RpcGovernanceSnapshot snapshot = update.snapshot();
        if (snapshot.revision() <= previous.snapshot().revision()) {
            if (!securityRejected.get() && snapshot.revision() == previous.snapshot().revision()
                    && snapshot.equals(previous.snapshot())) {
                failureAtNanos.set(0L);
                securityRejected.set(false);
            }
            return;
        }
        Map<ServiceKey, Map<Integer, RpcCircuitBreaker>> circuits = new HashMap<>();
        for (var service : snapshot.services().entrySet()) {
            Map<Integer, RpcCircuitBreaker> methods = new HashMap<>();
            for (var method : service.getValue().methods().entrySet()) {
                RpcGovernanceMethodRule rule = method.getValue();
                if (rule.circuitFailures() > 0) {
                    methods.put(method.getKey(),
                            new RpcCircuitBreaker(rule.circuitFailures(), rule.circuitOpenDuration()));
                }
            }
            circuits.put(service.getKey(), Map.copyOf(methods));
        }
        // 控制池唯一发布者，读端获得同一份规则/熔断对象，不跨版本拼接。
        active.set(new State(snapshot, Map.copyOf(circuits)));
        failureAtNanos.set(0L);
        securityRejected.set(false);
        ready.complete(null);
    }

    /**
     * 捕获当前完整治理版本；仅进行本地常量时间就绪和 LKG 检查。
     *
     * @param key 真实 ServiceKey
     * @param methodId 真实 Method ID
     * @return 逻辑调用终态保持的不可变规则引用
     * @throws RpcUnavailableException 未验证、安全拒绝或 LKG 已到期
     */
    public Selection capture(ServiceKey key, int methodId) {
        checkActive();
        State state = active.get();
        if (state.snapshot().revision() == 0L) {
            throw new RpcUnavailableException("Verified governance snapshot is not ready");
        }
        RpcGovernanceServiceRule service = state.snapshot().services().get(key);
        RpcGovernanceMethodRule method = service == null ? null : service.methods().get(methodId);
        RpcCircuitBreaker circuit = state.circuits().getOrDefault(key, Map.of()).get(methodId);
        return new Selection(state.snapshot().revision(), service, method, circuit);
    }

    /**
     * 完成当前治理安全和 LKG 上界的本地检查，不编译配置或执行远程 IO。
     *
     * @throws RpcUnavailableException 初始未验证、密钥/权限失败或普通故障 LKG 已过期
     */
    public void checkActive() {
        if (!started.get() || closed.get() || securityRejected.get()) {
            throw new RpcUnavailableException("Verified governance configuration is unavailable");
        }
        long failedAt = failureAtNanos.get();
        if (failedAt != 0L && System.nanoTime() - failedAt >= maxLkgNanos) {
            throw new RpcUnavailableException("Governance last-known-good window expired");
        }
    }

    /**
     * 依据同一调用捕获版本收紧已由 Router 验证的候选，不会增加 Provider。
     *
     * @param selection 已捕获的完整规则选择
     * @param candidates 经实际应用身份及既有 Router 验证的 Provider 候选
     * @return 只包含原始 Provider 对象引用的数组
     */
    public ServiceInstance[] route(Selection selection, ServiceInstance[] candidates) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(candidates, "candidates");
        RpcGovernanceServiceRule policy = selection.service();
        if (policy == null || candidates.length == 0) {
            return candidates;
        }
        ServiceInstance[] filtered = policy.route() == null ? candidates
                : RuleBasedRpcRouter.routeRule(policy.route(), candidates);
        if (filtered.length == 0 || policy.applicationWeights().isEmpty()) {
            return filtered;
        }
        Set<ApplicationKey> apps = new HashSet<>();
        long total = 0L;
        for (ServiceInstance instance : filtered) {
            if (apps.add(instance.application())) {
                total += policy.applicationWeights().getOrDefault(instance.application(), 1);
            }
        }
        long pick = ThreadLocalRandom.current().nextLong(total);
        ApplicationKey chosen = null;
        for (ServiceInstance instance : filtered) {
            if (!apps.remove(instance.application())) {
                continue;
            }
            pick -= policy.applicationWeights().getOrDefault(instance.application(), 1);
            if (pick < 0L) {
                chosen = instance.application();
                break;
            }
        }
        int found = 0;
        for (ServiceInstance instance : filtered) {
            if (chosen.equals(instance.application())) {
                found++;
            }
        }
        ServiceInstance[] selected = new ServiceInstance[found];
        int index = 0;
        for (ServiceInstance instance : filtered) {
            if (chosen.equals(instance.application())) {
                selected[index++] = instance;
            }
        }
        return selected;
    }

    /**
     * 当前本地实时并发数受静态 Provider Admission 及治理更严格方法配额的共同约束。
     *
     * <p>每个服务/方法使用跨 revision 同一计数器；新版本降低配额时不会把仍在
     * 执行的旧调用数量重置。必须在实际业务与响应双终态后归还。
     *
     * @param key 真实服务键
     * @param methodId 真实方法标识
     * @return 可幂等释放的资源租约；未配置配额返回无操作共享租约
     */
    public QuotaLease acquireQuota(QuotaSide side, ServiceKey key, int methodId) {
        return acquireQuota(capture(key, methodId), side, key, methodId);
    }

    /**
     * 对已捕获逻辑快照申请跨 revision 的独立侧方法额度。
     *
     * @param selection 一次逻辑调用固有 revision
     * @param side Consumer 或 Provider 的独立计数空间
     * @param key 原始可信业务 ServiceKey
     * @param methodId 已验证 Method ID
     * @return 直到真正结束才释放的幂等租约
     */
    public QuotaLease acquireQuota(Selection selection, QuotaSide side, ServiceKey key, int methodId) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(selection, "selection");
        checkActive();
        int limit = selection.method() == null ? 0 : selection.method().maxConcurrent();
        // 新 revision 若收紧了额度，旧请求不得借仍持有的旧限额突破新的强制上界。
        State live = active.get();
        RpcGovernanceServiceRule currentService = live.snapshot().services().get(key);
        RpcGovernanceMethodRule currentMethod = currentService == null ? null
                : currentService.methods().get(methodId);
        int currentLimit = currentMethod == null ? 0 : currentMethod.maxConcurrent();
        if (currentLimit > 0 && (limit == 0 || currentLimit < limit)) {
            limit = currentLimit;
        }
        // 即使旧 revision 未配置额度也必须计入真实活动数，以免热更新时重置在途调用。
        Method method = new Method(side, key, methodId);
        AtomicInteger counter = inflight.get(method);
        if (counter == null) {
            if (inflight.size() >= MAX_TRACKED_METHODS) {
                throw new RpcOverloadedException("Governance method counters capacity exhausted");
            }
            counter = inflight.computeIfAbsent(method, ignored -> new AtomicInteger());
        }
        for (;;) {
            int current = counter.get();
            if (limit > 0 && current >= limit) {
                throw new RpcOverloadedException("Governance method capacity exhausted");
            }
            if (counter.compareAndSet(current, current + 1)) {
                return new QuotaLease(counter);
            }
        }
    }

    /** 控制面诊断中的当前全局 revision，不读取远端 Registry。 */
    public long revision() {
        return active.get().snapshot().revision();
    }

    /** 没有暴露敏感业务字段的真实签名失效状态。 */
    public boolean securityRejected() {
        return securityRejected.get();
    }

    /** 停止 Watch 和更新池；在旧调用仍拥有 QuotaLease 时也不提前清空计数器。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        securityRejected.set(true);
        try {
            if (watch != null) {
                watch.close();
            }
        } catch (Exception ignored) {
            // 第三方 Watch 关闭失败不允许恢复安全可用性。
        } finally {
            controls.shutdownNow();
        }
    }

    private record State(
            RpcGovernanceSnapshot snapshot,
            Map<ServiceKey, Map<Integer, RpcCircuitBreaker>> circuits) {
        private static final State EMPTY = new State(RpcGovernanceSnapshot.EMPTY, Map.of());
    }

    /** Provider 配额和 Consumer 逻辑并发额度必须使用不同的计数空间。 */
    public enum QuotaSide {
        /** Consumer 一次逻辑调用。 */
        CONSUMER,
        /** Provider 一次已接纳业务执行。 */
        PROVIDER
    }

    private record Method(QuotaSide side, ServiceKey service, int methodId) {
    }

    /**
     * 一次调用固定的治理参数对象，不因后续 Watch 事件改写。
     *
     * @param revision 当时已验证的治理版本
     * @param service 服务级路由与权重
     * @param method 方法级资源、超时与熔断
     * @param circuit 已预编译且独立拥有代际的治理熔断器
     */
    public record Selection(long revision, RpcGovernanceServiceRule service,
                            RpcGovernanceMethodRule method, RpcCircuitBreaker circuit) {
    }

    /** 真正业务完成前不得归还的治理方法配额。 */
    public static final class QuotaLease {
        private static final QuotaLease NOOP = new QuotaLease(null);
        private final AtomicInteger counter;
        private final AtomicBoolean released = new AtomicBoolean();

        private QuotaLease(AtomicInteger counter) {
            this.counter = counter;
        }

        /** 幂等释放；首次返回 true。 */
        public boolean release() {
            if (counter == null) {
                return false;
            }
            if (released.compareAndSet(false, true)) {
                counter.decrementAndGet();
                return true;
            }
            return false;
        }
    }
}
