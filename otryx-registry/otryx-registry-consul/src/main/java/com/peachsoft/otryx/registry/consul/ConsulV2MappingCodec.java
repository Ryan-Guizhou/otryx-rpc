package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Consul KV Owner/Fencing 与 Session Liveness Marker 的可校验文档格式。
 *
 * <p>一个 Owner 的 Generation、随机 Token 和全部 ServiceKey 放在同一个
 * KV CAS 对象内，revocation 保留空集合墓碑。活性 Marker 在独立 Session
 * Key 中，Session 到期时由 Consul 原生 Behavior=delete 自动回收。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
final class ConsulV2MappingCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FORMAT_VERSION = 1;
    private static final String FORMAT = "format";
    private static final String NAMESPACE = "namespace";
    private static final String APP = "application";
    private static final String INSTANCE = "instance";
    private static final String GENERATION = "generation";
    private static final String TOKEN = "token";
    private static final String SERVICES = "services";
    private static final String SERVICE_NAME = "service";
    private static final String VERSION = "version";
    private static final String GROUP = "group";
    private static final Comparator<ServiceKey> ORDER = Comparator
            .comparing(ServiceKey::serviceName)
            .thenComparing(ServiceKey::version)
            .thenComparing(ServiceKey::group);

    private ConsulV2MappingCodec() {
    }

    /** 生成确定性、与 URI 身份绑定的完整 Owner CAS 值。 */
    static JsonNode encode(OwnerState owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.services().size() > com.peachsoft.otryx.api.ApplicationManifest.MAX_SERVICES) {
            throw new IllegalArgumentException("Too many Consul exported services");
        }
        List<Map<String, String>> exports = new ArrayList<>();
        for (ServiceKey key : owner.services().stream().sorted(ORDER).toList()) {
            validateKey(key);
            exports.add(Map.of(SERVICE_NAME, key.serviceName(), VERSION, key.version(), GROUP, key.group()));
        }
        return MAPPER.valueToTree(Map.of(
                FORMAT, FORMAT_VERSION,
                NAMESPACE, owner.owner().application().namespace(),
                APP, owner.owner().application().applicationName(),
                INSTANCE, owner.owner().instanceId(),
                GENERATION, owner.owner().generation(),
                TOKEN, owner.token(),
                SERVICES, exports));
    }

    /** 从远端 JSON 验证 Owner 身份、分片、服务条目与 canonical 顺序。 */
    static OwnerState decode(String expectedKey, String expectedNamespace, JsonNode raw) {
        if (!raw.isObject() || raw.path(FORMAT).asInt(-1) != FORMAT_VERSION
                || !expectedNamespace.equals(raw.path(NAMESPACE).asText())) {
            throw new IllegalArgumentException("Malformed Consul Mapping format or namespace");
        }
        ApplicationKey app = new ApplicationKey(expectedNamespace, raw.path(APP).asText());
        long generation = raw.path(GENERATION).asLong(-1L);
        ServiceMappingOwner owner = new ServiceMappingOwner(app, raw.path(INSTANCE).asText(), generation);
        if (!ConsulV2Keys.owner(app, owner.instanceId()).equals(expectedKey)) {
            throw new IllegalArgumentException("Consul Mapping KV owner key mismatch");
        }
        String token = raw.path(TOKEN).asText("");
        if (!token.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Invalid Consul Mapping owner token");
        }
        JsonNode serialized = raw.path(SERVICES);
        if (!serialized.isArray()
                || serialized.size() > com.peachsoft.otryx.api.ApplicationManifest.MAX_SERVICES) {
            throw new IllegalArgumentException("Invalid Consul Mapping service count");
        }
        List<ServiceKey> keys = new ArrayList<>();
        for (JsonNode entry : serialized) {
            ServiceKey key = new ServiceKey(entry.path(SERVICE_NAME).asText(""),
                    entry.path(VERSION).asText(""), entry.path(GROUP).asText(""));
            validateKey(key);
            keys.add(key);
        }
        if (!keys.equals(keys.stream().sorted(ORDER).toList())
                || new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException("Noncanonical Consul Mapping service set");
        }
        return new OwnerState(owner, token, Set.copyOf(keys));
    }

    /** Session Marker 单独保存身份，旧 Generation 的 Marker 不使旧 Owner 重新生效。 */
    static JsonNode marker(OwnerState state) {
        return MAPPER.valueToTree(Map.of(
                GENERATION, state.owner().generation(),
                TOKEN, state.token()));
    }

    static boolean validMarker(ConsulV2Http.KvEntry entry, OwnerState owner) {
        return entry != null && !entry.sessionId().isBlank()
                && entry.value().path(GENERATION).asLong(-1L) == owner.owner().generation()
                && owner.token().equals(entry.value().path(TOKEN).asText(""))
                && entry.key().equals(ConsulV2Keys.live(
                        owner.owner().application(), owner.owner().instanceId(),
                        owner.owner().generation()));
    }

    private static void validateKey(ServiceKey key) {
        if (key.serviceName().length() > ExportedService.MAX_SERVICE_NAME_LENGTH
                || !key.serviceName().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                || !qualifier(key.version()) || !qualifier(key.group())) {
            throw new IllegalArgumentException("Invalid Consul mapped ServiceKey");
        }
    }

    private static boolean qualifier(String value) {
        return value != null && value.length() <= ExportedService.MAX_QUALIFIER_LENGTH
                && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    record OwnerState(ServiceMappingOwner owner, String token, Set<ServiceKey> services) {
        OwnerState {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(token, "token");
            services = Set.copyOf(Objects.requireNonNull(services, "services"));
        }
    }
}
