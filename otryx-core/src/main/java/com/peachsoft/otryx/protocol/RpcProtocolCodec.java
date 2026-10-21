package com.peachsoft.otryx.protocol;

import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.codec.RpcCodecIds;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * OTRYX RPC Wire v1 二进制协议编解码器。
 *
 * <p>负责编码和解析固定长度 Header、RPC 状态和 Payload 边界，
 * 并拒绝不符合协议长度、Magic 或版本约束的输入。
 *
 * <p>Wire v1 已冻结：不得以重构、格式化或代码生成便利为由改变
 * Header 布局、Codec/Message Type ID 或 Metadata 编码语义。
 * 所有兼容性变更需要配套现有 Rolling Compatibility 验证。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public final class RpcProtocolCodec {

    /** OTRYX RPC 协议魔数。 */
    public static final short MAGIC = (short) 0xCAFE;
    /** 当前协议版本。 */
    public static final byte VERSION = 1;
    /** v1 固定协议头长度。 */
    public static final int HEADER_LENGTH = 32;
    /** v1 metadata 与 payload 合计最大长度。 */
    public static final int MAX_BODY_LENGTH = 16 * 1024 * 1024;

    private static final int REQUEST_ID_OFFSET = 10;
    private static final int SERVICE_ID_OFFSET = 18;
    private static final int METHOD_ID_OFFSET = 22;
    private static final int METADATA_LENGTH_OFFSET = 26;
    private static final int PAYLOAD_LENGTH_OFFSET = 28;
    private static final byte[] DEADLINE_PREFIX =
            "deadlineEpochMillis=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TIMEOUT_BUDGET_PREFIX =
            "timeoutBudgetMillis=".getBytes(StandardCharsets.US_ASCII);
    private static final int TIMEOUT_BUDGET_DIGITS = 19;
    private static final int MAX_METADATA_KEY_CHARS = 256;
    private static final int MAX_METADATA_VALUE_CHARS = 16 * 1024;

    private RpcProtocolCodec() {
    }

    /**
     * 编码完整 RPC 帧。
     *
     * <p>该入口用于控制帧与兼容场景。Unary 数据面应优先使用
     * {@link #encodeRequest(byte, int, int, long, byte[])} 或
     * {@link #encodeResponse(byte, RpcStatus, long, int, int, byte[])}。
     *
     * @param frame RPC 帧
     * @return 完整线协议字节
     */
    public static byte[] encode(RpcFrame frame) {
        byte[] metadata = encodeMetadata(frame.metadata());
        byte[] payload = frame.payload();
        int bodyLength = checkedBodyLength(
                metadata.length,
                payload.length);

        byte[] bytes = new byte[HEADER_LENGTH + bodyLength];
        writeHeader(
                bytes,
                frame.messageType(),
                frame.codec(),
                frame.status(),
                frame.requestId(),
                frame.serviceId(),
                frame.methodId(),
                metadata.length,
                payload.length);
        System.arraycopy(
                metadata,
                0,
                bytes,
                HEADER_LENGTH,
                metadata.length);
        System.arraycopy(
                payload,
                0,
                bytes,
                HEADER_LENGTH + metadata.length,
                payload.length);
        return bytes;
    }

    /**
     * 编码 Unary REQUEST 快路径。
     *
     * <p>Request ID 初始写 0，由 Transport 在 connection-local Event Loop
     * 内分配并覆盖固定 Header。Deadline 直接写 ASCII metadata，不创建
     * Map、StringBuilder 或 deadline String。
     *
     * @param codec Codec 编号
     * @param serviceId 服务 ID
     * @param methodId 方法 ID
     * @param deadlineEpochMillis 截止时间；小于等于 0 表示不携带
     * @param payload 已编码参数
     * @return 完整线协议字节
     */
    public static byte[] encodeRequest(
            byte codec,
            int serviceId,
            int methodId,
            long deadlineEpochMillis,
            byte[] payload) {
        return encodeRequest(
                codec,
                serviceId,
                methodId,
                deadlineEpochMillis,
                0L,
                Map.of(),
                payload);
    }

    /**
     * 编码同时携带绝对 Deadline 与相对剩余预算的 Unary REQUEST。
     *
     * <p>相对预算用于新版本 Provider 避免跨节点 wall-clock 偏差；
     * 绝对 Deadline 保留给旧版本 Provider，支持滚动升级。
     *
     * @param codec Codec 编号
     * @param serviceId 服务 ID
     * @param methodId 方法 ID
     * @param deadlineEpochMillis 绝对截止时间；小于等于 0 表示不携带
     * @param timeoutBudgetMillis 剩余超时预算；小于等于 0 表示不携带
     * @param payload 已编码参数
     * @return 完整线协议字节
     */
    public static byte[] encodeRequest(
            byte codec,
            int serviceId,
            int methodId,
            long deadlineEpochMillis,
            long timeoutBudgetMillis,
            byte[] payload) {
        return encodeRequest(
                codec,
                serviceId,
                methodId,
                deadlineEpochMillis,
                timeoutBudgetMillis,
                Map.of(),
                payload);
    }

    /**
     * 编码携带额外 Metadata 的 Unary REQUEST。
     *
     * <p>额外 Metadata 仅在上下文传播等扩展启用时使用；默认空 Map 保持
     * deadline/timeout budget 快路径。保留字段不能由扩展覆盖。
     *
     * @param codec Codec 编号
     * @param serviceId 服务 ID
     * @param methodId 方法 ID
     * @param deadlineEpochMillis 绝对截止时间
     * @param timeoutBudgetMillis 相对剩余预算
     * @param metadata 额外 Metadata
     * @param payload 已编码参数
     * @return 完整线协议字节
     */
    public static byte[] encodeRequest(
            byte codec,
            int serviceId,
            int methodId,
            long deadlineEpochMillis,
            long timeoutBudgetMillis,
            Map<String, String> metadata,
            byte[] payload) {
        Map<String, String> extraMetadata =
                metadata == null ? Map.of() : metadata;
        if (extraMetadata.containsKey("deadlineEpochMillis")
                || extraMetadata.containsKey("timeoutBudgetMillis")) {
            throw new RpcProtocolException(
                    "Reserved deadline metadata cannot be overridden");
        }
        byte[] encodedMetadata = encodeMetadata(extraMetadata);
        byte[] actualPayload =
                payload == null ? new byte[0] : payload;
        int deadlineLength = deadlineEpochMillis > 0
                ? DEADLINE_PREFIX.length
                        + decimalLength(deadlineEpochMillis)
                        + 1
                : 0;
        int budgetLength = timeoutBudgetMillis > 0
                ? TIMEOUT_BUDGET_PREFIX.length
                        + TIMEOUT_BUDGET_DIGITS
                        + 1
                : 0;
        int metadataLength =
                deadlineLength
                        + budgetLength
                        + encodedMetadata.length;
        int bodyLength = checkedBodyLength(
                metadataLength,
                actualPayload.length);

        byte[] bytes = new byte[HEADER_LENGTH + bodyLength];
        writeHeader(
                bytes,
                RpcMessageType.REQUEST,
                codec,
                RpcStatus.OK,
                0L,
                serviceId,
                methodId,
                metadataLength,
                actualPayload.length);

        int metadataOffset = HEADER_LENGTH;
        if (deadlineLength > 0) {
            System.arraycopy(
                    DEADLINE_PREFIX,
                    0,
                    bytes,
                    metadataOffset,
                    DEADLINE_PREFIX.length);
            int valueOffset =
                    metadataOffset + DEADLINE_PREFIX.length;
            writePositiveLong(
                    bytes,
                    valueOffset,
                    deadlineEpochMillis);
            metadataOffset += deadlineLength;
            bytes[metadataOffset - 1] = '\n';
        }
        if (budgetLength > 0) {
            System.arraycopy(
                    TIMEOUT_BUDGET_PREFIX,
                    0,
                    bytes,
                    metadataOffset,
                    TIMEOUT_BUDGET_PREFIX.length);
            int valueOffset =
                    metadataOffset + TIMEOUT_BUDGET_PREFIX.length;
            writePaddedPositiveLong(
                    bytes,
                    valueOffset,
                    TIMEOUT_BUDGET_DIGITS,
                    timeoutBudgetMillis);
            metadataOffset += budgetLength;
            bytes[metadataOffset - 1] = '\n';
        }
        if (encodedMetadata.length > 0) {
            System.arraycopy(
                    encodedMetadata,
                    0,
                    bytes,
                    metadataOffset,
                    encodedMetadata.length);
        }
        System.arraycopy(
                actualPayload,
                0,
                bytes,
                HEADER_LENGTH + metadataLength,
                actualPayload.length);
        return bytes;
    }

    /**
     * 编码无 Metadata 的 Unary RESPONSE 快路径。
     *
     * @param codec Codec 编号
     * @param status RPC 状态
     * @param requestId Request ID
     * @param serviceId 服务 ID
     * @param methodId 方法 ID
     * @param payload 已编码返回值或错误
     * @return 完整线协议字节
     */
    public static byte[] encodeResponse(
            byte codec,
            RpcStatus status,
            long requestId,
            int serviceId,
            int methodId,
            byte[] payload) {
        byte[] actualPayload = payload == null ? new byte[0] : payload;
        return encodeResponse(codec, status, requestId, serviceId, methodId,
                actualPayload, 0, actualPayload.length);
    }

    /**
     * 直接从受限缓冲的有效 Payload 区间构造 Wire v1 RESPONSE。
     *
     * <p>不创建中间 Payload 副本；完整 Frame 仍是独立的 byte[]，
     * 调用者需在创建前为该 Frame 的瞬时内存保留足够额度。
     *
     * @param codec 已协商 Codec ID
     * @param status 响应状态
     * @param requestId 原请求 ID
     * @param serviceId 服务 ID
     * @param methodId 方法 ID
     * @param payload 调用期间不可修改的底层 Payload 字节
     * @param offset 有效 Payload 起始偏移
     * @param length 有效 Payload 字节数
     * @return 可独立持有的完整 Wire v1 响应帧
     */
    public static byte[] encodeResponse(
            byte codec,
            RpcStatus status,
            long requestId,
            int serviceId,
            int methodId,
            byte[] payload,
            int offset,
            int length) {
        Objects.requireNonNull(payload, "payload");
        Objects.checkFromIndexSize(offset, length, payload.length);
        checkedBodyLength(0, length);
        byte[] bytes = new byte[HEADER_LENGTH + length];
        writeHeader(bytes, RpcMessageType.RESPONSE, codec, status,
                requestId, serviceId, methodId, 0, length);
        System.arraycopy(payload, offset, bytes, HEADER_LENGTH, length);
        return bytes;
    }

    /**
     * 编码调用取消控制帧。
     *
     * @param requestId 需要取消的 connection-local Request ID
     * @return 完整取消帧
     */
    public static byte[] encodeCancel(long requestId) {
        if (requestId == 0L) {
            throw new IllegalArgumentException("requestId must be non-zero");
        }
        byte[] bytes = new byte[HEADER_LENGTH];
        writeHeader(
                bytes,
                RpcMessageType.CANCEL,
                RpcCodecIds.CONTROL,
                RpcStatus.OK,
                requestId,
                0,
                0,
                0,
                0);
        return bytes;
    }

    /**
     * 编码连接级 PING/PONG 控制帧。
     *
     * @param type PING 或 PONG
     * @return 完整心跳控制帧
     */
    public static byte[] encodeHeartbeat(RpcMessageType type) {
        if (type != RpcMessageType.PING && type != RpcMessageType.PONG) {
            throw new IllegalArgumentException(
                    "Heartbeat type must be PING or PONG");
        }
        byte[] bytes = new byte[HEADER_LENGTH];
        writeHeader(
                bytes,
                type,
                RpcCodecIds.CONTROL,
                RpcStatus.OK,
                0L,
                0,
                0,
                0,
                0);
        return bytes;
    }

    /**
     * 在不改变帧长度的前提下刷新 REQUEST 中的相对 timeout budget。
     *
     * <p>V2-C.2 将该字段编码为固定 19 位十进制值，因此 Transport 可以在
     * connect / handshake 完成后、真正写 Socket 前把预算更新为发送时剩余值。
     *
     * @param bytes 完整 REQUEST 帧
     * @param timeoutBudgetMillis 发送时剩余预算，必须大于 0
     * @return 找到并刷新字段时返回 true；旧请求未携带该字段时返回 false
     */
    public static boolean rewriteTimeoutBudgetMillis(
            byte[] bytes,
            long timeoutBudgetMillis) {
        if (timeoutBudgetMillis <= 0L) {
            throw new IllegalArgumentException(
                    "timeoutBudgetMillis must be positive");
        }
        requireHeader(bytes);
        validatePreamble(bytes);
        if (bytes[6] != RpcMessageType.REQUEST.code()) {
            return false;
        }
        int metadataLength = readUnsignedShort(
                bytes,
                METADATA_LENGTH_OFFSET);
        int payloadLength = readInt(
                bytes,
                PAYLOAD_LENGTH_OFFSET);
        validateLengths(
                bytes.length,
                metadataLength,
                payloadLength);
        int end = HEADER_LENGTH + metadataLength;
        int lineStart = HEADER_LENGTH;
        while (lineStart < end) {
            int lineEnd = lineStart;
            while (lineEnd < end && bytes[lineEnd] != '\n') {
                lineEnd++;
            }
            if (matchesPrefix(
                    bytes,
                    lineStart,
                    lineEnd,
                    TIMEOUT_BUDGET_PREFIX)) {
                int valueOffset =
                        lineStart + TIMEOUT_BUDGET_PREFIX.length;
                if (lineEnd - valueOffset != TIMEOUT_BUDGET_DIGITS) {
                    throw new RpcProtocolException(
                            "Invalid timeout budget metadata width");
                }
                writePaddedPositiveLong(
                        bytes,
                        valueOffset,
                        TIMEOUT_BUDGET_DIGITS,
                        timeoutBudgetMillis);
                return true;
            }
            lineStart = lineEnd + 1;
        }
        return false;
    }

    /**
     * 从固定 Header 直接读取 connection-local Request ID。
     *
     * <p>该入口只读取 Header，不创建 RpcFrame/RpcFrameView，也不复制
     * Metadata/Payload。调用方若需要完整协议校验，仍应使用 {@link #view(byte[])}。
     *
     * @param bytes 至少包含完整固定 Header 的帧
     * @return Request ID
     */
    public static long readRequestId(byte[] bytes) {
        requireHeader(bytes);
        return readLong(
                bytes,
                REQUEST_ID_OFFSET);
    }

    /**
     * 覆盖 Unary REQUEST 固定 Header 中的 connection-local Request ID。
     *
     * @param bytes 完整 REQUEST 帧
     * @param requestId connection-local Request ID，必须非零
     */
    public static void writeRequestId(
            byte[] bytes,
            long requestId) {
        requireHeader(bytes);
        writeLong(
                bytes,
                REQUEST_ID_OFFSET,
                requestId);
    }

    /**
     * 解码并校验完整 RPC 帧。
     *
     * @param bytes 完整线协议字节
     * @return 解码后的 RPC 帧
     */
    public static RpcFrame decode(byte[] bytes) {
        RpcFrameView view = view(bytes);
        byte[] metadata = java.util.Arrays.copyOfRange(
                bytes,
                HEADER_LENGTH,
                view.payloadOffset());
        return new RpcFrame(
                view.messageType(),
                view.codec(),
                view.status(),
                view.requestId(),
                view.serviceId(),
                view.methodId(),
                decodeMetadata(metadata),
                view.payloadCopy());
    }

    /**
     * 解析完整 RPC 帧为只读 view，不复制 Metadata 与 Payload。
     *
     * @param bytes 完整线协议字节
     * @return RPC 帧视图
     */
    public static RpcFrameView view(byte[] bytes) {
        requireHeader(bytes);
        validatePreamble(bytes);

        RpcMessageType messageType =
                RpcMessageType.fromCode(bytes[6]);
        byte codec = bytes[7];

        RpcStatus status;
        try {
            status = RpcStatus.fromCode(bytes[9]);
        } catch (IllegalArgumentException error) {
            throw new RpcProtocolException(
                    "Invalid status",
                    error);
        }

        long requestId = readLong(
                bytes,
                REQUEST_ID_OFFSET);
        int serviceId = readInt(
                bytes,
                SERVICE_ID_OFFSET);
        int methodId = readInt(
                bytes,
                METHOD_ID_OFFSET);
        int metadataLength = readUnsignedShort(
                bytes,
                METADATA_LENGTH_OFFSET);
        int payloadLength = readInt(
                bytes,
                PAYLOAD_LENGTH_OFFSET);
        validateLengths(
                bytes.length,
                metadataLength,
                payloadLength);

        int metadataOffset = HEADER_LENGTH;
        int payloadOffset =
                metadataOffset + metadataLength;
        return new RpcFrameView(
                bytes,
                messageType,
                codec,
                status,
                requestId,
                serviceId,
                methodId,
                metadataOffset,
                metadataLength,
                payloadOffset,
                payloadLength);
    }

    /**
     * 根据固定头计算完整帧长度，用于 TCP 字节流重组。
     *
     * @param header 至少 32 字节的协议头
     * @return 完整帧长度
     */
    public static int expectedFrameLength(byte[] header) {
        requireHeader(header);
        validatePreamble(header);
        int metadataLength = readUnsignedShort(
                header,
                METADATA_LENGTH_OFFSET);
        int payloadLength = readInt(
                header,
                PAYLOAD_LENGTH_OFFSET);
        validateLengths(
                -1,
                metadataLength,
                payloadLength);
        return HEADER_LENGTH
                + metadataLength
                + payloadLength;
    }

    private static void writeHeader(
            byte[] bytes,
            RpcMessageType messageType,
            byte codec,
            RpcStatus status,
            long requestId,
            int serviceId,
            int methodId,
            int metadataLength,
            int payloadLength) {
        bytes[0] = (byte) (MAGIC >>> 8);
        bytes[1] = (byte) MAGIC;
        bytes[2] = VERSION;
        bytes[3] = (byte) HEADER_LENGTH;
        bytes[4] = 0;
        bytes[5] = 0;
        bytes[6] = messageType.code();
        bytes[7] = codec;
        bytes[8] = 0;
        bytes[9] = status.code();
        writeLong(
                bytes,
                REQUEST_ID_OFFSET,
                requestId);
        writeInt(
                bytes,
                SERVICE_ID_OFFSET,
                serviceId);
        writeInt(
                bytes,
                METHOD_ID_OFFSET,
                methodId);
        writeShort(
                bytes,
                METADATA_LENGTH_OFFSET,
                metadataLength);
        writeInt(
                bytes,
                PAYLOAD_LENGTH_OFFSET,
                payloadLength);
    }

    private static void validatePreamble(byte[] bytes) {
        short magic = (short) readUnsignedShort(bytes, 0);
        byte version = bytes[2];
        int headerLength = Byte.toUnsignedInt(bytes[3]);
        if (magic != MAGIC) {
            throw new RpcProtocolException("Invalid magic");
        }
        if (version != VERSION) {
            throw new RpcProtocolException(
                    "Unsupported version: " + version);
        }
        if (headerLength != HEADER_LENGTH) {
            throw new RpcProtocolException(
                    "Unsupported header length: "
                            + headerLength);
        }
        if (bytes[4] != 0 || bytes[5] != 0) {
            throw new RpcProtocolException(
                    "Reserved protocol flags must be zero");
        }
        if (bytes[8] != RpcCompressionIds.NONE) {
            throw new RpcProtocolException(
                    "Unsupported compression id: "
                            + Byte.toUnsignedInt(bytes[8]));
        }
    }

    private static void requireHeader(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_LENGTH) {
            throw new RpcProtocolException(
                    "Incomplete RPC header");
        }
    }

    private static int checkedBodyLength(
            int metadataLength,
            int payloadLength) {
        long bodyLength =
                metadataLength + (long) payloadLength;
        if (metadataLength < 0
                || metadataLength > 65_535
                || payloadLength < 0
                || bodyLength > MAX_BODY_LENGTH) {
            throw new RpcProtocolException(
                    "Invalid body length");
        }
        return (int) bodyLength;
    }

    private static void validateLengths(
            int actualLength,
            int metadataLength,
            int payloadLength) {
        int bodyLength = checkedBodyLength(
                metadataLength,
                payloadLength);
        if (actualLength >= 0
                && actualLength
                        != HEADER_LENGTH + bodyLength) {
            throw new RpcProtocolException(
                    "Frame length mismatch");
        }
    }

    private static byte[] encodeMetadata(
            Map<String, String> metadata) {
        if (metadata.isEmpty()) {
            return new byte[0];
        }
        StringBuilder builder = new StringBuilder();
        metadata.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    validateMetadata(
                            entry.getKey(),
                            entry.getValue());
                    builder.append(entry.getKey())
                            .append('=')
                            .append(entry.getValue())
                            .append('\n');
                });
        byte[] encoded = builder.toString()
                .getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 65_535) {
            throw new RpcProtocolException(
                    "Metadata exceeds 65535 bytes");
        }
        return encoded;
    }

    static void validateMetadata(
            String key,
            String value) {
        if (key == null
                || key.isEmpty()
                || value == null
                || value.isEmpty()) {
            throw new RpcProtocolException(
                    "Metadata key/value must not be null or empty");
        }
        if (key.length() > MAX_METADATA_KEY_CHARS) {
            throw new RpcProtocolException(
                    "Metadata key exceeds "
                            + MAX_METADATA_KEY_CHARS
                            + " characters");
        }
        if (value.length() > MAX_METADATA_VALUE_CHARS) {
            throw new RpcProtocolException(
                    "Metadata value exceeds "
                            + MAX_METADATA_VALUE_CHARS
                            + " characters");
        }
        if (key.indexOf('=') >= 0
                || key.indexOf('\n') >= 0
                || value.indexOf('\n') >= 0) {
            throw new RpcProtocolException(
                    "Illegal metadata character");
        }
    }

    private static Map<String, String> decodeMetadata(
            byte[] bytes) {
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            Map<String, String> metadata =
                    new LinkedHashMap<>();
            for (String line : new String(
                    bytes,
                    StandardCharsets.UTF_8).split("\\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new RpcProtocolException(
                            "Malformed metadata");
                }
                String key =
                        line.substring(0, separator);
                String value =
                        line.substring(separator + 1);
                validateMetadata(key, value);
                metadata.put(key, value);
            }
            return Map.copyOf(metadata);
        } catch (RuntimeException error) {
            if (error instanceof RpcProtocolException) {
                throw error;
            }
            throw new RpcProtocolException(
                    "Failed to decode metadata",
                    error);
        }
    }

    private static boolean matchesPrefix(
            byte[] bytes,
            int start,
            int end,
            byte[] prefix) {
        if (end - start <= prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[start + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static void writePaddedPositiveLong(
            byte[] target,
            int offset,
            int width,
            long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "value must be non-negative");
        }
        int cursor = offset + width - 1;
        long current = value;
        while (cursor >= offset) {
            target[cursor--] =
                    (byte) ('0' + (current % 10L));
            current /= 10L;
        }
        if (current != 0L) {
            throw new IllegalArgumentException(
                    "value exceeds fixed decimal width");
        }
    }

    private static int decimalLength(long value) {
        int length = 1;
        long current = value;
        while (current >= 10L) {
            current /= 10L;
            length++;
        }
        return length;
    }

    private static void writePositiveLong(
            byte[] bytes,
            int offset,
            long value) {
        int length = decimalLength(value);
        int index = offset + length - 1;
        long current = value;
        do {
            bytes[index--] =
                    (byte) ('0' + current % 10L);
            current /= 10L;
        } while (current > 0L);
    }

    private static int readUnsignedShort(
            byte[] bytes,
            int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 8)
                | Byte.toUnsignedInt(bytes[offset + 1]);
    }

    private static int readInt(
            byte[] bytes,
            int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 24)
                | (Byte.toUnsignedInt(bytes[offset + 1]) << 16)
                | (Byte.toUnsignedInt(bytes[offset + 2]) << 8)
                | Byte.toUnsignedInt(bytes[offset + 3]);
    }

    private static long readLong(
            byte[] bytes,
            int offset) {
        return ((long) Byte.toUnsignedInt(bytes[offset]) << 56)
                | ((long) Byte.toUnsignedInt(bytes[offset + 1]) << 48)
                | ((long) Byte.toUnsignedInt(bytes[offset + 2]) << 40)
                | ((long) Byte.toUnsignedInt(bytes[offset + 3]) << 32)
                | ((long) Byte.toUnsignedInt(bytes[offset + 4]) << 24)
                | ((long) Byte.toUnsignedInt(bytes[offset + 5]) << 16)
                | ((long) Byte.toUnsignedInt(bytes[offset + 6]) << 8)
                | Byte.toUnsignedInt(bytes[offset + 7]);
    }

    private static void writeShort(
            byte[] bytes,
            int offset,
            int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static void writeInt(
            byte[] bytes,
            int offset,
            int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void writeLong(
            byte[] bytes,
            int offset,
            long value) {
        bytes[offset] = (byte) (value >>> 56);
        bytes[offset + 1] = (byte) (value >>> 48);
        bytes[offset + 2] = (byte) (value >>> 40);
        bytes[offset + 3] = (byte) (value >>> 32);
        bytes[offset + 4] = (byte) (value >>> 24);
        bytes[offset + 5] = (byte) (value >>> 16);
        bytes[offset + 6] = (byte) (value >>> 8);
        bytes[offset + 7] = (byte) value;
    }
}
