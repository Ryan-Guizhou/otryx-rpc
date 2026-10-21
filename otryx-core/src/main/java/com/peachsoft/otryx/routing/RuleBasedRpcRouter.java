package com.peachsoft.otryx.routing;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.spi.Extension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无 Registry IO 的本地规则路由器，按 ServiceKey 原子切换快照。
 *
 * <p>先按完整应用白名单、标签、区域、发布版本和灰度分桶进行硬过滤，再执行
 * 应用优先级选择。绝不将被过滤实例重新补回或将 Consumer-only 转换为 Provider。
 * 配置回滚必须使用新的更高 revision，防止旧事件覆盖新配置。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 13:38
 */
@Extension(RpcRouter.DEFAULT_EXTENSION)
public final class RuleBasedRpcRouter implements RpcRouter {
    /** Provider 实例属性中的业务区域名称。 */
    public static final String ZONE_ATTRIBUTE = "zone";
    /** Provider 发布版本标签，与 RPC Schema 版本不同。 */
    public static final String RELEASE_ATTRIBUTE = "release";
    /** Provider 运行灰度轨道标签。 */
    public static final String TRACK_ATTRIBUTE = "track";
    /** 灰度轨道值。 */
    public static final String GRAY_TRACK = "gray";
    /** 稳定轨道值。 */
    public static final String STABLE_TRACK = "stable";

    private final AtomicReference<RpcRoutingSnapshot> active =
            new AtomicReference<>(RpcRoutingSnapshot.EMPTY);

    /** 创建无过滤规则的路由器。 */
    public RuleBasedRpcRouter() {
    }

    /**
     * 读取当时的不可变快照，不读取后端配置。
     *
     * @return 当前规则版本及内容
     */
    public RpcRoutingSnapshot snapshot() {
        return active.get();
    }

    /**
     * 原子发布较新的完整快照；重复或过期版本不覆盖当前规则。
     *
     * @param next 已完成验证的不可变配置
     * @return 发布成功返回 true，过期或竞争中的更高版本返回 false
     */
    public boolean publish(RpcRoutingSnapshot next) {
        Objects.requireNonNull(next, "next");
        for (;;) {
            RpcRoutingSnapshot previous = active.get();
            if (next.revision() <= previous.revision()) {
                return false;
            }
            if (active.compareAndSet(previous, next)) {
                return true;
            }
        }
    }

    /**
     * 使用递增 revision 将较早的规则内容重新发布，避免直接回退版本。
     *
     * @param previous 经过授权保留的历史不可变快照
     * @param revision 必须大于当前版本的回滚事件版本
     * @return 是否成功应用完整的回滚快照
     */
    public boolean rollback(RpcRoutingSnapshot previous, long revision) {
        Objects.requireNonNull(previous, "previous");
        return publish(new RpcRoutingSnapshot(revision, previous.rules()));
    }

    @Override
    public boolean hasRules(ServiceKey service) {
        return active.get().rules().containsKey(Objects.requireNonNull(service, "service"));
    }

    @Override
    public ServiceInstance[] route(ServiceKey service, ApplicationKey caller,
                                   Map<String, String> metadata, ServiceInstance[] candidates) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(candidates, "candidates");
        RpcRouteRule rule = active.get().rules().get(service);
        if (rule == null || candidates.length == 0) {
            return candidates;
        }
        String track = null;
        if (rule.grayPercentage() != null) {
            track = ThreadLocalRandom.current().nextInt(100) < rule.grayPercentage()
                    ? GRAY_TRACK : STABLE_TRACK;
        }
        List<ServiceInstance> filtered = new ArrayList<>(candidates.length);
        for (ServiceInstance candidate : candidates) {
            if (!rule.allowedApplications().isEmpty()
                    && !rule.allowedApplications().contains(candidate.application())) {
                continue;
            }
            Map<String, String> labels = candidate.metadata();
            if (!labels.entrySet().containsAll(rule.requiredLabels().entrySet())) {
                continue;
            }
            if (rule.zone() != null && !rule.zone().equals(labels.get(ZONE_ATTRIBUTE))) {
                continue;
            }
            if (rule.release() != null && !rule.release().equals(labels.get(RELEASE_ATTRIBUTE))) {
                continue;
            }
            if (track != null && !track.equals(labels.get(TRACK_ATTRIBUTE))) {
                continue;
            }
            filtered.add(candidate);
        }
        for (ApplicationKey prioritized : rule.applicationPriority()) {
            List<ServiceInstance> preferred = new ArrayList<>();
            for (ServiceInstance candidate : filtered) {
                if (prioritized.equals(candidate.application())) {
                    preferred.add(candidate);
                }
            }
            if (!preferred.isEmpty()) {
                return preferred.toArray(ServiceInstance[]::new);
            }
        }
        return filtered.toArray(ServiceInstance[]::new);
    }
}
