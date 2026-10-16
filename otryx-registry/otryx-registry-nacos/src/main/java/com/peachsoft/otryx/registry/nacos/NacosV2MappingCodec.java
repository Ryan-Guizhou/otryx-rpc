package com.peachsoft.otryx.registry.nacos;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nacos Config 分片映射文档的确定性、有界二进制格式。
 *
 * <p>Owner generation、token、完整 ServiceKey 集合存入同一 Config CAS 单元。
 * revoke 保留空集合墓碑防止跨进程过期 Owner 复活。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
final class NacosV2MappingCodec {

    private static final String PREFIX = "otryx-v2-mapping:";
    private static final int MAGIC = 0x4f4e4d50;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_SHARD_OWNERS = 128;
    static final int MAX_OWNER_SERVICES = 512;
    private static final int MAX_STRING_BYTES = 1024;
    private static final int MAX_NAME_BYTES = 128;
    private static final int MAX_OWNER_BYTES = 90 * 1024;
    private static final Comparator<ServiceKey> SERVICE_ORDER = Comparator
            .comparing(ServiceKey::serviceName)
            .thenComparing(ServiceKey::version)
            .thenComparing(ServiceKey::group);
    private static final Comparator<OwnerState> OWNER_ORDER = Comparator
            .comparing((OwnerState record) -> record.owner().application().applicationName())
            .thenComparing(record -> record.owner().instanceId());

    private NacosV2MappingCodec() {
    }

    /** 序列化完整分片，不允许单次读写超过 Nacos Config 容量。 */
    static String encode(Map<String, OwnerState> owners) {
        if (owners.size() > MAX_SHARD_OWNERS) {
            throw new IllegalArgumentException("Nacos mapping shard owner capacity exceeded");
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(owners.size());
            for (OwnerState record : owners.values().stream().sorted(OWNER_ORDER).toList()) {
                ServiceMappingOwner owner = record.owner();
                writeString(output, owner.application().applicationName());
                writeString(output, owner.instanceId());
                output.writeLong(owner.generation());
                writeString(output, record.token());
                if (record.services().size() > MAX_OWNER_SERVICES) {
                    throw new IllegalArgumentException("Too many Nacos exported services");
                }
                output.writeInt(record.services().size());
                for (ServiceKey service : record.services().stream().sorted(SERVICE_ORDER).toList()) {
                    validateServiceKey(service);
                    writeString(output, service.serviceName());
                    writeString(output, service.version());
                    writeString(output, service.group());
                }
            }
            output.flush();
            String encoded = PREFIX + Base64.getEncoder().encodeToString(buffer.toByteArray());
            if (encoded.getBytes(StandardCharsets.UTF_8).length > NacosV2ConfigStore.MAX_CONFIG_BYTES) {
                throw new IllegalArgumentException("Nacos mapping document exceeds Config size limit");
            }
            return encoded;
        } catch (IOException error) {
            throw new IllegalStateException("Nacos mapping encoding failed", error);
        }
    }

    /** 读取分片并校验每条 Owner 的 namespace、分片归属与格式。 */
    static Map<String, OwnerState> decode(String content, String namespace, int shard) {
        if (content == null) {
            return Map.of();
        }
        if (content.length() > NacosV2ConfigStore.MAX_CONFIG_BYTES || !content.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Invalid Nacos mapping format or size");
        }
        byte[] payload = Base64.getDecoder().decode(content.substring(PREFIX.length()));
        if (payload.length > MAX_OWNER_BYTES) {
            throw new IllegalArgumentException("Nacos mapping document is too large");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported Nacos mapping document version");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_SHARD_OWNERS) {
                throw new IllegalArgumentException("Invalid Nacos mapping owner count");
            }
            Map<String, OwnerState> decoded = new HashMap<>();
            List<OwnerState> ordered = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                ApplicationKey app = new ApplicationKey(namespace, readString(input, MAX_NAME_BYTES));
                String instanceId = readString(input, MAX_STRING_BYTES);
                ServiceMappingOwner owner = new ServiceMappingOwner(app, instanceId, input.readLong());
                String token = readString(input, MAX_NAME_BYTES);
                int servicesCount = input.readInt();
                if (servicesCount < 0 || servicesCount > MAX_OWNER_SERVICES) {
                    throw new IllegalArgumentException("Invalid Nacos mapping export count");
                }
                List<ServiceKey> keys = new ArrayList<>(servicesCount);
                for (int serviceIndex = 0; serviceIndex < servicesCount; serviceIndex++) {
                    ServiceKey service = new ServiceKey(
                            readString(input, MAX_STRING_BYTES),
                            readString(input, MAX_NAME_BYTES),
                            readString(input, MAX_NAME_BYTES));
                    validateServiceKey(service);
                    keys.add(service);
                }
                Set<ServiceKey> unique = Set.copyOf(keys);
                if (unique.size() != keys.size()
                        || !keys.equals(keys.stream().sorted(SERVICE_ORDER).toList())) {
                    throw new IllegalArgumentException("Duplicate or unordered Nacos mapped services");
                }
                if (NacosV2Names.shard(app, instanceId) != shard) {
                    throw new IllegalArgumentException("Nacos mapping owner stored in the wrong shard");
                }
                OwnerState record = new OwnerState(owner, token, unique);
                if (decoded.putIfAbsent(identifier(owner), record) != null) {
                    throw new IllegalArgumentException("Duplicate Nacos mapping owner");
                }
                ordered.add(record);
            }
            if (input.available() != 0 || !ordered.equals(ordered.stream().sorted(OWNER_ORDER).toList())) {
                throw new IllegalArgumentException("Nacos mapping content is noncanonical");
            }
            return Map.copyOf(decoded);
        } catch (IOException error) {
            throw new IllegalArgumentException("Malformed Nacos mapping document", error);
        }
    }

    /** 形成不含分隔符歧义的 Owner 身份索引。 */
    static String identifier(ServiceMappingOwner owner) {
        return owner.application().applicationName() + '\u0000' + owner.instanceId();
    }

    private static void validateServiceKey(ServiceKey key) {
        if (!key.serviceName().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                || key.serviceName().length() > 256
                || !qualifier(key.version()) || !qualifier(key.group())) {
            throw new IllegalArgumentException("Invalid Nacos mapped ServiceKey");
        }
    }

    private static boolean qualifier(String value) {
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    private static void writeString(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximum || length > input.available()) {
            throw new IllegalArgumentException("Invalid Nacos mapping string length");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Malformed UTF-8 in Nacos mapping");
        }
    }

    record OwnerState(ServiceMappingOwner owner, String token, Set<ServiceKey> services) {
        OwnerState {
            java.util.Objects.requireNonNull(owner, "owner");
            java.util.Objects.requireNonNull(token, "token");
            services = Set.copyOf(services);
        }
    }
}
