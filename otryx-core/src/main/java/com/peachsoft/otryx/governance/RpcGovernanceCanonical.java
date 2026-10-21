package com.peachsoft.otryx.governance;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.routing.RpcRouteRule;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 跨客户端与配置发布端唯一确定性的 HMAC 输入，包含所有安全相关治理字段。
 *
 * <p>二进制格式以 schema 版本标记开头、字段分界明确，集合固定排序，不能
 * 对 Map.toString 或不稳定 JSON 结果签名。该编码不属于 RPC Wire v1。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
public final class RpcGovernanceCanonical {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_CANONICAL_BYTES = 1024 * 1024;
    private static final Comparator<ApplicationKey> APPLICATION_ORDER = Comparator
            .comparing(ApplicationKey::namespace).thenComparing(ApplicationKey::applicationName);
    private static final Comparator<ServiceKey> SERVICE_ORDER = Comparator
            .comparing(ServiceKey::serviceName).thenComparing(ServiceKey::version).thenComparing(ServiceKey::group);

    private RpcGovernanceCanonical() {
    }

    /**
     * 输出完整稳定的二进制签名正文，含签发人及递增版本。
     *
     * @param issuer 执行权限白名单中的发行人
     * @param snapshot 完整不可变治理快照
     * @return 用于 HMAC 的确定性字节
     */
    public static byte[] encode(String issuer, RpcGovernanceSnapshot snapshot) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(issuer);
            out.writeLong(snapshot.revision());
            List<Map.Entry<ServiceKey, RpcGovernanceServiceRule>> services = snapshot.services()
                    .entrySet().stream().sorted(Map.Entry.comparingByKey(SERVICE_ORDER)).toList();
            out.writeInt(services.size());
            for (var entry : services) {
                ServiceKey key = entry.getKey();
                out.writeUTF(key.serviceName());
                out.writeUTF(key.version());
                out.writeUTF(key.group());
                RpcGovernanceServiceRule rule = entry.getValue();
                route(out, rule.route());
                var weights = rule.applicationWeights().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(APPLICATION_ORDER)).toList();
                out.writeInt(weights.size());
                for (var weight : weights) {
                    application(out, weight.getKey());
                    out.writeInt(weight.getValue());
                }
                var methods = rule.methods().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList();
                out.writeInt(methods.size());
                for (var method : methods) {
                    out.writeInt(method.getKey());
                    RpcGovernanceMethodRule policy = method.getValue();
                    optionalDuration(out, policy.maxTimeout());
                    out.writeInt(policy.maxConcurrent());
                    out.writeInt(policy.circuitFailures());
                    optionalDuration(out, policy.circuitOpenDuration());
                }
                if (bytes.size() > MAX_CANONICAL_BYTES) {
                    throw new IllegalArgumentException("Governance update exceeds canonical size limit");
                }
            }
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_CANONICAL_BYTES) {
                throw new IllegalArgumentException("Governance update exceeds canonical size limit");
            }
            return encoded;
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid governance canonical encoding", error);
        }
    }

    private static void route(DataOutputStream out, RpcRouteRule rule) throws IOException {
        out.writeBoolean(rule != null);
        if (rule == null) {
            return;
        }
        var allow = rule.allowedApplications().stream().sorted(APPLICATION_ORDER).toList();
        out.writeInt(allow.size());
        for (ApplicationKey app : allow) {
            application(out, app);
        }
        out.writeInt(rule.applicationPriority().size());
        for (ApplicationKey app : rule.applicationPriority()) {
            application(out, app);
        }
        var labels = rule.requiredLabels().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        out.writeInt(labels.size());
        for (var label : labels) {
            out.writeUTF(label.getKey());
            out.writeUTF(label.getValue());
        }
        optionalText(out, rule.zone());
        optionalText(out, rule.release());
        out.writeBoolean(rule.grayPercentage() != null);
        if (rule.grayPercentage() != null) {
            out.writeInt(rule.grayPercentage());
        }
    }

    private static void application(DataOutputStream out, ApplicationKey application) throws IOException {
        out.writeUTF(application.namespace());
        out.writeUTF(application.applicationName());
    }

    private static void optionalText(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeUTF(value);
        }
    }

    private static void optionalDuration(DataOutputStream out, Duration value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeLong(value.toNanos());
        }
    }
}
