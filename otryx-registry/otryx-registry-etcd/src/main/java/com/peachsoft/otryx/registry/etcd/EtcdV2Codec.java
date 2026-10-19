package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Etcd V2 应用成员与映射所有权的严格有界二进制编码。
 *
 * <p>存储格式独立于 RPC Wire v1 和 Manifest 格式；实例发布者 Token 仅用于
 * Etcd CAS 所有权校验，不能当作远端调用的认证凭据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
final class EtcdV2Codec {

    static final int MAX_MEMBER_BYTES = 96 * 1024;
    static final int MAX_OWNER_BYTES = 256 * 1024;
    static final int MAX_SERVICES = 512;
    private static final int MEMBER_MAGIC = 0x4f41504d;
    private static final int OWNER_MAGIC = 0x4f41504f;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_INSTANCE_BYTES = 1024;
    private static final int MAX_NAME_BYTES = 128;
    private static final int MAX_HOST_BYTES = 1024;
    private static final int MAX_REVISION_BYTES = 128;
    private static final int MAX_TOKEN_BYTES = 64;
    private static final int MAX_ATTRIBUTE_KEY_BYTES = 128;
    private static final int MAX_ATTRIBUTE_VALUE_BYTES = 4096;
    private static final int MAX_SERVICE_NAME_BYTES = 1024;
    private static final int MAX_QUALIFIER_BYTES = 512;
    private static final int MAX_ATTRIBUTES = 64;
    private static final Comparator<ServiceKey> SERVICE_ORDER = Comparator
            .comparing(ServiceKey::serviceName)
            .thenComparing(ServiceKey::version)
            .thenComparing(ServiceKey::group);

    private EtcdV2Codec() {
    }

    /** 编码真实 Provider、Consumer 或 BOTH 成员。 */
    static byte[] encodeMember(ApplicationInstance member, String token) {
        return encode(MAX_MEMBER_BYTES, output -> {
            output.writeInt(MEMBER_MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, token);
            writeString(output, member.application().namespace());
            writeString(output, member.application().applicationName());
            writeString(output, member.instanceId());
            writeString(output, member.role().name());
            output.writeBoolean(member.rpcEndpoint() != null);
            if (member.rpcEndpoint() != null) {
                writeString(output, member.rpcEndpoint().host());
                output.writeInt(member.rpcEndpoint().port());
            }
            output.writeInt(member.weight());
            output.writeBoolean(member.manifestRevision() != null);
            if (member.manifestRevision() != null) {
                writeString(output, member.manifestRevision());
            }
            output.writeInt(member.attributes().size());
            for (Map.Entry<String, String> attribute : member.attributes().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                writeString(output, attribute.getKey());
                writeString(output, attribute.getValue());
            }
        });
    }

    /** 解码时拒绝跨应用污染、非法 Role/Endpoint、重复属性、尾随字节及畸形 UTF-8。 */
    static MemberRecord decodeMember(byte[] payload) {
        return decode(payload, MAX_MEMBER_BYTES, input -> {
            requireHeader(input, MEMBER_MAGIC);
            String token = readString(input, MAX_TOKEN_BYTES);
            ApplicationKey application = new ApplicationKey(
                    readString(input, MAX_NAME_BYTES), readString(input, MAX_NAME_BYTES));
            String instanceId = readString(input, MAX_INSTANCE_BYTES);
            ApplicationRole role = ApplicationRole.valueOf(readString(input, MAX_NAME_BYTES));
            RpcEndpoint endpoint = input.readBoolean()
                    ? new RpcEndpoint(readString(input, MAX_HOST_BYTES), input.readInt())
                    : null;
            int weight = input.readInt();
            String revision = input.readBoolean() ? readString(input, MAX_REVISION_BYTES) : null;
            int count = input.readInt();
            if (count < 0 || count > MAX_ATTRIBUTES) {
                throw new IllegalArgumentException("Invalid Etcd member attribute count");
            }
            Map<String, String> attributes = new HashMap<>();
            for (int index = 0; index < count; index++) {
                String key = readString(input, MAX_ATTRIBUTE_KEY_BYTES);
                String value = readString(input, MAX_ATTRIBUTE_VALUE_BYTES, true);
                if (attributes.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("Duplicate Etcd member attribute");
                }
            }
            return new MemberRecord(
                    new ApplicationInstance(instanceId, application, role, endpoint, weight,
                            revision, attributes),
                    token);
        });
    }

    /** 完整替换同一 Owner 的导出集合，单键更新对应单个 Etcd 修订版。 */
    static byte[] encodeOwner(ServiceMappingOwner owner, String token, Set<ServiceKey> services) {
        if (services.size() > MAX_SERVICES) {
            throw new IllegalArgumentException("Too many exported services");
        }
        return encode(MAX_OWNER_BYTES, output -> {
            output.writeInt(OWNER_MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, token);
            writeString(output, owner.application().namespace());
            writeString(output, owner.application().applicationName());
            writeString(output, owner.instanceId());
            output.writeLong(owner.generation());
            output.writeInt(services.size());
            for (ServiceKey key : services.stream().sorted(SERVICE_ORDER).toList()) {
                writeString(output, key.serviceName());
                writeString(output, key.version());
                writeString(output, key.group());
            }
        });
    }

    /** 读取映射所有权墓碑，保留 generation 以阻断跨进程 ABA 写入。 */
    static OwnerRecord decodeOwner(byte[] payload) {
        return decode(payload, MAX_OWNER_BYTES, input -> {
            requireHeader(input, OWNER_MAGIC);
            String token = readString(input, MAX_TOKEN_BYTES);
            ApplicationKey application = new ApplicationKey(
                    readString(input, MAX_NAME_BYTES), readString(input, MAX_NAME_BYTES));
            String instanceId = readString(input, MAX_INSTANCE_BYTES);
            ServiceMappingOwner owner = new ServiceMappingOwner(application, instanceId, input.readLong());
            int count = input.readInt();
            if (count < 0 || count > MAX_SERVICES) {
                throw new IllegalArgumentException("Invalid Etcd mapping export count");
            }
            List<ServiceKey> services = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                services.add(new ServiceKey(
                        readString(input, MAX_SERVICE_NAME_BYTES),
                        readString(input, MAX_QUALIFIER_BYTES),
                        readString(input, MAX_QUALIFIER_BYTES)));
            }
            Set<ServiceKey> unique = Set.copyOf(services);
            if (unique.size() != count
                    || !services.equals(services.stream().sorted(SERVICE_ORDER).toList())) {
                throw new IllegalArgumentException("Invalid Etcd mapping export order or duplicates");
            }
            return new OwnerRecord(owner, token, unique);
        });
    }

    private static void requireHeader(DataInputStream input, int expected) throws IOException {
        if (input.readInt() != expected || input.readInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Etcd V2 record header");
        }
    }

    private static byte[] encode(int maximum, Encoder encoder) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            encoder.write(output);
            output.flush();
            if (buffer.size() > maximum) {
                throw new IllegalArgumentException("Etcd record exceeds byte budget");
            }
            return buffer.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unexpected Etcd in-memory encoding error", error);
        }
    }

    private static <T> T decode(byte[] payload, int maximum, Decoder<T> decoder) {
        if (payload == null || payload.length == 0) {
            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
        if (payload.length > maximum) {
            throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            T result = decoder.read(input);
            if (input.available() != 0) {
                throw new IllegalArgumentException("Unexpected trailing Etcd record bytes");
            }
            return result;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximum) throws IOException {
        return readString(input, maximum, false);
    }

    private static String readString(DataInputStream input, int maximum, boolean allowEmpty) throws IOException {
        int length = input.readInt();
        if (length < (allowEmpty ? 0 : 1) || length > maximum || length > input.available()) {
            throw new IllegalArgumentException("Invalid Etcd string length");
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
            throw new IllegalArgumentException("Invalid UTF-8 in Etcd record", error);
        }
    }

    record MemberRecord(ApplicationInstance instance, String token) {
    }

    record OwnerRecord(ServiceMappingOwner owner, String token, Set<ServiceKey> services) {
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T read(DataInputStream input) throws IOException;
    }
}
