package com.peachsoft.otryx.api;

import com.peachsoft.otryx.protocol.RpcProtocolCodec;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * V2 应用 Manifest 的确定性二进制编解码，不影响 RPC Wire v1 帧协议。
 *
 * <p>所有整数采用 Big Endian，字符串以 int32 字节长度 + UTF-8 编码；
 * 服务和方法顺序固定，解码时拒绝任何非标准排序、未知版本、非法 UTF-8、
 * 截断、尾随数据及超长载荷，避免同一内容出现多个可接受编码。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 17:48
 */
public final class ApplicationManifestCodec {

    /** 与 RpcSchemaFingerprint.SCHEMA_VERSION 相互独立的清单格式版本。 */
    public static final int FORMAT_VERSION = 1;

    /** 与现有 Unary RPC Wire 保持一致的协议版本。 */
    public static final int WIRE_PROTOCOL_VERSION = Byte.toUnsignedInt(RpcProtocolCodec.VERSION);

    private static final int MAGIC = 0x4f4d464d;
    private static final int MAX_NAMESPACE_BYTES = 128;
    private static final int MAX_APPLICATION_NAME_BYTES = 128;
    private static final int MAX_SERVICE_NAME_BYTES = 256;
    private static final int MAX_QUALIFIER_BYTES = 128;
    private static final int MAX_FINGERPRINT_BYTES = 64;

    private ApplicationManifestCodec() {
    }

    /**
     * 将已校验的 Manifest 编码为确定性、受限大小的完整字节数组。
     *
     * @param manifest 不可变清单
     * @return 标准二进制内容的独立副本
     */
    public static byte[] encode(ApplicationManifest manifest) {
        return java.util.Objects.requireNonNull(manifest, "manifest").canonicalBytes();
    }

    /**
     * 解码并验证 Manifest 的版本、结构、规范排序及内容边界。
     *
     * @param payload 来自可信或不可信仓库的 Manifest 二进制内容
     * @return 已标准化且拥有真实内容 revision 的清单
     * @throws IllegalArgumentException 内容无效、超限或与当前格式不兼容时抛出
     */
    public static ApplicationManifest decode(byte[] payload) {
        if (payload == null || payload.length == 0
                || payload.length > ApplicationManifest.MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException("Invalid Manifest payload length");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readInt() != MAGIC
                    || input.readInt() != FORMAT_VERSION
                    || input.readInt() != WIRE_PROTOCOL_VERSION
                    || input.readInt() != RpcSchemaFingerprint.SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported Manifest header version or magic");
            }
            ApplicationKey application = new ApplicationKey(
                    readString(input, MAX_NAMESPACE_BYTES),
                    readString(input, MAX_APPLICATION_NAME_BYTES));
            int serviceCount = input.readInt();
            if (serviceCount < 1 || serviceCount > ApplicationManifest.MAX_SERVICES) {
                throw new IllegalArgumentException("Invalid Manifest service count");
            }
            List<ExportedService> services = new ArrayList<>(serviceCount);
            for (int index = 0; index < serviceCount; index++) {
                ServiceKey key = new ServiceKey(
                        readString(input, MAX_SERVICE_NAME_BYTES),
                        readString(input, MAX_QUALIFIER_BYTES),
                        readString(input, MAX_QUALIFIER_BYTES));
                int serviceId = input.readInt();
                String fingerprint = readString(input, MAX_FINGERPRINT_BYTES);
                int count = input.readInt();
                if (count < 1 || count > ExportedService.MAX_METHODS) {
                    throw new IllegalArgumentException("Invalid Manifest method count");
                }
                Set<Integer> methodIds = new HashSet<>();
                for (int methodIndex = 0; methodIndex < count; methodIndex++) {
                    if (!methodIds.add(input.readInt())) {
                        throw new IllegalArgumentException("Duplicate method ID in Manifest payload");
                    }
                }
                services.add(new ExportedService(key, serviceId, methodIds, fingerprint));
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Trailing bytes in Manifest payload");
            }
            ApplicationManifest decoded = ApplicationManifest.create(application, services);
            if (!Arrays.equals(decoded.canonicalBytes(), payload)) {
                throw new IllegalArgumentException("Noncanonical Manifest payload");
            }
            return decoded;
        } catch (IOException error) {
            throw new IllegalArgumentException("Truncated or malformed Manifest payload", error);
        }
    }

    static byte[] encodeCanonical(ApplicationKey application, List<ExportedService> services) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(WIRE_PROTOCOL_VERSION);
            output.writeInt(RpcSchemaFingerprint.SCHEMA_VERSION);
            writeString(output, application.namespace());
            writeString(output, application.applicationName());
            output.writeInt(services.size());
            for (ExportedService service : services) {
                ServiceKey key = service.serviceKey();
                writeString(output, key.serviceName());
                writeString(output, key.version());
                writeString(output, key.group());
                output.writeInt(service.serviceId());
                writeString(output, service.schemaFingerprint());
                List<Integer> methodIds = service.methodIds().stream().sorted().toList();
                output.writeInt(methodIds.size());
                for (int methodId : methodIds) {
                    output.writeInt(methodId);
                }
            }
            output.flush();
            if (buffer.size() > ApplicationManifest.MAX_CANONICAL_BYTES) {
                throw new IllegalArgumentException("Manifest payload exceeds size limit");
            }
            return buffer.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unexpected in-memory Manifest encoding failure", error);
        }
    }

    private static void writeString(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxLength) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maxLength || length > input.available()) {
            throw new IllegalArgumentException("Invalid Manifest field length");
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
            throw new IllegalArgumentException("Invalid UTF-8 in Manifest", error);
        }
    }
}
