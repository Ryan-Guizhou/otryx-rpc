package com.peachsoft.otryx.routing;

import com.peachsoft.otryx.api.ApplicationKey;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单个 ServiceKey 的不可变本地路由规则；标签仅用于过滤，不参与认证授权。
 *
 * <p>应用白名单为空表示已验证的全部应用。优先列表命中时仅使用首个有候选的应用；
 * 全部未命中时回退到已通过其他规则的候选。灰度比例为 null 时不启用 track 标签过滤，
 * 否则通过 Provider 的 track=gray/stable 标签分别决定灰度和稳定目标。
 *
 * @param allowedApplications 可选的完整应用身份白名单
 * @param applicationPriority 可选的应用优先级列表，优先项必须属于非空白名单
 * @param requiredLabels 必须匹配的非可信实例标签
 * @param zone 可选的 zone 属性精确值
 * @param release 可选的 release 属性精确值；与业务 ServiceKey.version 无关
 * @param grayPercentage 可选 0 至 100 灰度请求比例，null 表示停用灰度过滤
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 13:38
 */
public record RpcRouteRule(
        Set<ApplicationKey> allowedApplications,
        List<ApplicationKey> applicationPriority,
        Map<String, String> requiredLabels,
        String zone,
        String release,
        Integer grayPercentage) {

    private static final int MAX_APPLICATIONS = 128;
    private static final int MAX_LABELS = 32;
    private static final int MAX_LABEL_LENGTH = 128;

    /** 检查规则规模和域，冻结配置以免热更新出现部分态。 */
    public RpcRouteRule {
        allowedApplications = Set.copyOf(Objects.requireNonNull(allowedApplications, "allowedApplications"));
        applicationPriority = List.copyOf(Objects.requireNonNull(applicationPriority, "applicationPriority"));
        requiredLabels = Map.copyOf(Objects.requireNonNull(requiredLabels, "requiredLabels"));
        if (allowedApplications.size() > MAX_APPLICATIONS
                || applicationPriority.size() > MAX_APPLICATIONS
                || requiredLabels.size() > MAX_LABELS) {
            throw new IllegalArgumentException("RPC routing rule exceeds bounded policy limits");
        }
        if (new HashSet<>(applicationPriority).size() != applicationPriority.size()
                || (!allowedApplications.isEmpty()
                        && !allowedApplications.containsAll(applicationPriority))) {
            throw new IllegalArgumentException("Invalid RPC application priority");
        }
        for (Map.Entry<String, String> entry : requiredLabels.entrySet()) {
            checkLabel(entry.getKey(), "routing label key");
            checkLabel(entry.getValue(), "routing label value");
        }
        zone = optionalLabel(zone, "zone");
        release = optionalLabel(release, "release");
        if (grayPercentage != null && (grayPercentage < 0 || grayPercentage > 100)) {
            throw new IllegalArgumentException("RPC gray percentage must be between 0 and 100");
        }
    }

    private static String optionalLabel(String value, String field) {
        return value == null ? null : checkLabel(value, field);
    }

    private static String checkLabel(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("Invalid RPC " + field);
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("Invalid RPC " + field);
            }
        }
        return value;
    }
}
