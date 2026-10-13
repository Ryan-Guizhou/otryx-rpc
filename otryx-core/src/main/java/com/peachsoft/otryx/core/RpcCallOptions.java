package com.peachsoft.otryx.core;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 单次 RPC 调用覆盖选项；未设置的超时使用方法级或 Consumer 默认值。
 *
 * <p>Metadata 是不可信的应用上下文，不得用于声明 TLS Peer 身份或服务授权。
 * 框架保留字段和超出条目、字节预算的数据在发送之前即被拒绝。
 *
 * @param timeout 可选的正数超时；null 表示采用方法级默认值
 * @param metadata 随调用传播的应用 Metadata；null 等价于空 Map
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
public record RpcCallOptions(Duration timeout, Map<String, String> metadata) {

    /** 用户 Metadata 的最大条目数量。 */
    public static final int MAX_METADATA_ENTRIES = 64;
    /** 用户 Metadata 以 UTF-8 编码后的最大总长度。 */
    public static final int MAX_METADATA_BYTES = 8 * 1024;
    /** Metadata 键的最大字符数。 */
    private static final int MAX_METADATA_KEY_CHARS = 256;
    /** Metadata 单个值的最大字符数。 */
    private static final int MAX_METADATA_VALUE_CHARS = 16 * 1024;
    /** Metadata 单项的 Wire 分隔符长度（= 和换行）。 */
    private static final int METADATA_DELIMITER_BYTES = 2;
    /** 无显式覆盖选项。 */
    public static final RpcCallOptions DEFAULT = new RpcCallOptions(null, Map.of());

    /**
     * 验证单次覆盖参数，生成不可变的 Metadata 快照。
     */
    public RpcCallOptions {
        if (timeout != null) {
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("RPC call timeout must be positive");
            }
            try {
                timeout.toNanos();
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("RPC call timeout exceeds nanosecond range", invalid);
            }
        }
        Map<String, String> copy = metadata == null
                ? Map.of() : new LinkedHashMap<>(metadata);
        validateMetadata(copy);
        metadata = Map.copyOf(copy);
    }

    /**
     * 校验用户可控 Metadata 的总预算与身份信任边界。
     *
     * @param values 待校验 Metadata
     */
    public static void validateMetadata(Map<String, String> values) {
        if (values.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("Too many RPC metadata entries");
        }
        long bytes = 0L;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isEmpty() || value == null || value.isEmpty()
                    || key.length() > MAX_METADATA_KEY_CHARS
                    || value.length() > MAX_METADATA_VALUE_CHARS
                    || key.indexOf('=') >= 0 || key.indexOf('\n') >= 0
                    || key.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Invalid RPC metadata key or value");
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.equals("deadlineepochmillis")
                    || normalized.equals("timeoutbudgetmillis")
                    || normalized.startsWith("rpc-peer-")
                    || normalized.startsWith("rpc-auth-")
                    || normalized.equals("peeridentity")) {
                throw new IllegalArgumentException("Reserved RPC metadata key");
            }
            bytes += key.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length + METADATA_DELIMITER_BYTES;
            if (bytes > MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("RPC metadata byte budget exceeded");
            }
        }
    }
}
