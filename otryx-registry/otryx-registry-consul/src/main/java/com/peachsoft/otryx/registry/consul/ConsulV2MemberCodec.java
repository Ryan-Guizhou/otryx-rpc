package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Consul V2 Agent Service 与应用成员的严格双向映射。
 *
 * <p>Consumer-only 的服务地址为空、端口为零，仅用于 TTL Health；
 * 绝不构造假的 RpcEndpoint。服务 ID、角色、Manifest 引用及应用身份
 * 均相互校验，禁止将 Nacos 兼容元数据或其他 ServiceKey 误用于路由。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
final class ConsulV2MemberCodec {

    private static final String META_NAMESPACE = ConsulV2Keys.METADATA_PREFIX + "namespace";
    private static final String META_APPLICATION = ConsulV2Keys.METADATA_PREFIX + "application";
    private static final String META_INSTANCE = ConsulV2Keys.METADATA_PREFIX + "instance";
    private static final String META_ROLE = ConsulV2Keys.METADATA_PREFIX + "role";
    private static final String META_REVISION = ConsulV2Keys.METADATA_PREFIX + "manifest";
    private static final String META_WEIGHT = ConsulV2Keys.METADATA_PREFIX + "weight";
    private static final String META_TOKEN = ConsulV2Keys.METADATA_PREFIX + "incarnation";
    private static final String META_ATTRIBUTE = ConsulV2Keys.METADATA_PREFIX + "a.";
    private static final String FIELD_SERVICE = "Service";
    private static final String FIELD_NODE = "Node";
    private static final String FIELD_CHECKS = "Checks";
    private static final String FIELD_META = "Meta";
    private static final String FIELD_ID = "ID";
    private static final String FIELD_NAME = "Name";
    private static final String FIELD_HEALTH_SERVICE_NAME = "Service";
    private static final String FIELD_ADDRESS = "Address";
    private static final String FIELD_PORT = "Port";
    private static final String FIELD_STATUS = "Status";
    private static final String FIELD_CHECK = "Check";
    private static final String FIELD_TTL = "TTL";
    private static final String FIELD_DEREGISTER_CRITICAL = "DeregisterCriticalServiceAfter";
    private static final String DEREGISTER_CRITICAL_AFTER = "1m";
    private static final String CHECK_STATUS_CRITICAL = "critical";
    private static final int MAX_METADATA_BYTES = 8 * 1024;
    private static final int MAX_METADATA_ITEMS = 64;
    private static final int MAX_META_KEY_CHARACTERS = 128;
    private static final int MAX_META_VALUE_CHARACTERS = 512;
    private static final int MAX_RPC_PORT = 65535;
    private static final String CHECK_STATUS_PASSING = "passing";

    private ConsulV2MemberCodec() {
    }

    /** 按 Consul Agent 原生格式注册真实 Provider/Consumer/BOTH 生命周期成员。 */
    static Map<String, Object> encode(ApplicationInstance member, String incarnation, int ttlSeconds) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(incarnation, "incarnation");
        Map<String, String> metadata = new HashMap<>();
        metadata.put(META_NAMESPACE, member.application().namespace());
        metadata.put(META_APPLICATION, member.application().applicationName());
        metadata.put(META_INSTANCE, member.instanceId());
        metadata.put(META_ROLE, member.role().name());
        metadata.put(META_WEIGHT, Integer.toString(member.weight()));
        metadata.put(META_TOKEN, incarnation);
        if (member.routableProvider()) {
            ApplicationManifest.requireRevision(member.manifestRevision());
            metadata.put(META_REVISION, member.manifestRevision());
        }
        member.attributes().forEach((key, value) -> metadata.put(META_ATTRIBUTE
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8)), value));
        if (metadata.size() > MAX_METADATA_ITEMS || metadataBytes(metadata) > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("Consul membership metadata exceeds safe capacity");
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_-]{1,128}")
                    || entry.getKey().length() > MAX_META_KEY_CHARACTERS
                    || entry.getValue().length() > MAX_META_VALUE_CHARACTERS) {
                throw new IllegalArgumentException("Consul Service Meta key/value is invalid or oversized");
            }
        }
        String host = member.routableProvider() ? member.rpcEndpoint().host() : "";
        int port = member.routableProvider() ? member.rpcEndpoint().port() : 0;
        if (member.routableProvider() && (!validHost(host) || port < 1 || port > MAX_RPC_PORT)) {
            throw new IllegalArgumentException("Consul Provider must advertise a routable RPC endpoint");
        }
        return Map.of(
                FIELD_ID, ConsulV2Keys.serviceId(member.application(), member.instanceId(), incarnation),
                FIELD_NAME, ConsulV2Keys.serviceName(member.application()),
                FIELD_ADDRESS, host,
                FIELD_PORT, port,
                FIELD_META, Map.copyOf(metadata),
                FIELD_CHECK, Map.of(
                        FIELD_TTL, ttlSeconds + "s",
                        FIELD_STATUS, CHECK_STATUS_CRITICAL,
                        FIELD_DEREGISTER_CRITICAL, DEREGISTER_CRITICAL_AFTER));
    }

    /** Consul passing 服务实际地址转回应用快照，过滤损坏或其他应用记录。 */
    static ApplicationInstance decode(ApplicationKey application, JsonNode item) {
        Objects.requireNonNull(application, "application");
        JsonNode service = item.path(FIELD_SERVICE);
        JsonNode meta = service.path(FIELD_META);
        if (!meta.isObject() || !meta.has(META_TOKEN)) {
            return null;
        }
        if (!application.namespace().equals(meta.path(META_NAMESPACE).asText())
                || !application.applicationName().equals(meta.path(META_APPLICATION).asText())
                || !ConsulV2Keys.serviceName(application).equals(service.path(FIELD_HEALTH_SERVICE_NAME).asText())) {
            throw new IllegalArgumentException("Consul application membership identity mismatch");
        }
        JsonNode checks = item.path(FIELD_CHECKS);
        if (!checks.isArray() || checks.isEmpty()) {
            throw new IllegalArgumentException("Consul application membership has no health checks");
        }
        for (JsonNode check : checks) {
            if (!CHECK_STATUS_PASSING.equals(check.path(FIELD_STATUS).asText())) {
                return null;
            }
        }
        String instanceId = requireText(meta.path(META_INSTANCE).asText(""), "instanceId");
        String incarnation = requireText(meta.path(META_TOKEN).asText(""), "incarnation");
        if (!ConsulV2Keys.serviceId(application, instanceId, incarnation)
                .equals(service.path(FIELD_ID).asText())) {
            throw new IllegalArgumentException("Consul application member service ID mismatch");
        }
        ApplicationRole role = ApplicationRole.valueOf(requireText(meta.path(META_ROLE).asText(""), "role"));
        JsonNode weightNode = meta.path(META_WEIGHT);
        int weight;
        try {
            weight = Integer.parseInt(requireText(weightNode.asText(""), "weight"));
        } catch (NumberFormatException badWeight) {
            throw new IllegalArgumentException("Consul application weight is invalid", badWeight);
        }
        int port = service.path(FIELD_PORT).asInt(-1);
        String host = service.path(FIELD_ADDRESS).asText("");
        RpcEndpoint rpcEndpoint;
        String revision;
        if (role.providesRpc()) {
            if (port <= 0 || port > MAX_RPC_PORT || !validHost(host)) {
                throw new IllegalArgumentException("Consul Provider has an invalid RPC endpoint");
            }
            revision = requireText(meta.path(META_REVISION).asText(""), "revision");
            ApplicationManifest.requireRevision(revision);
            rpcEndpoint = new RpcEndpoint(host, port);
        } else {
            // Consul Health may expand the Agent's empty Address to its Node.Address.
            // Port=0 and role=CONSUMER are authoritative: never create an RpcEndpoint.
            if (port != 0 || meta.has(META_REVISION) || weight != 0) {
                // Safe boolean-only diagnostics: never include untrusted metadata or addresses.
                throw new IllegalArgumentException(
                        "Consul consumer-only endpoint invariant failed: portNonZero=" + (port != 0)
                                + ", manifestPresent=" + meta.has(META_REVISION)
                                + ", weightNonZero=" + (weight != 0));
            }
            rpcEndpoint = null;
            revision = null;
        }
        if (meta.size() > MAX_METADATA_ITEMS) {
            throw new IllegalArgumentException("Consul member metadata item limit exceeded");
        }
        for (var entry : meta.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (!key.matches("[A-Za-z0-9_-]{1,128}") || !value.isTextual()
                    || value.asText().length() > MAX_META_VALUE_CHARACTERS) {
                throw new IllegalArgumentException("Consul member metadata contains invalid key/value");
            }
        }
        Map<String, String> attributes = new HashMap<>();
        meta.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key.startsWith(META_ATTRIBUTE)) {
                String decoded = new String(Base64.getUrlDecoder().decode(
                        key.substring(META_ATTRIBUTE.length())), StandardCharsets.UTF_8);
                if (attributes.putIfAbsent(decoded, value.asText()) != null) {
                    throw new IllegalArgumentException("Duplicate Consul member attribute");
                }
            }
        });
        return new ApplicationInstance(instanceId, application, role, rpcEndpoint, weight, revision, attributes);
    }

    private static int metadataBytes(Map<String, String> values) {
        int size = 0;
        for (Map.Entry<String, String> item : values.entrySet()) {
            size += item.getKey().getBytes(StandardCharsets.UTF_8).length;
            size += item.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        return size;
    }

    private static boolean validHost(String host) {
        return host != null && !host.isBlank()
                && !RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)
                && !RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)
                && !RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Consul membership " + field);
        }
        return value;
    }
}
