package com.peachsoft.otryx.transport;

import java.time.Duration;
import java.util.Objects;

/**
 * Transport 连接、协议握手与部分帧缓存的服务端准入预算。
 *
 * <p>本阶段连接计数从 Vert.x NetSocket 回调开始，不能约束 TCP
 * 内核 backlog，也不代表 TLS 引擎完成握手之前的内存占用。
 * 不完整 Frame 的全局字节和驻留超时由 U03-B 阶段实施。
 *
 * @param maxConnections 单 Provider 实例已进入 NetSocket 回调的最大连接数
 * @param maxHandshakes 单 Provider 实例同时执行 OTRYX HELLO 握手的连接数
 * @param maxBufferedPartialFrameBytes 全局不完整帧字节预算
 * @param partialFrameTimeout 不完整帧在缓冲区中的最大驻留时间
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 16:54
 */
public record RpcTransportAdmissionOptions(
        int maxConnections,
        int maxHandshakes,
        long maxBufferedPartialFrameBytes,
        Duration partialFrameTimeout) {

    /** 基线默认值；上线前应使用容量测试校准。 */
    public static final RpcTransportAdmissionOptions DEFAULT =
            new RpcTransportAdmissionOptions(
                    4096,
                    1024,
                    64L * 1024 * 1024,
                    Duration.ofSeconds(10));

    /** 验证准入配置不能使容量或计时无限制。 */
    public RpcTransportAdmissionOptions {
        Objects.requireNonNull(partialFrameTimeout, "partialFrameTimeout");
        if (maxConnections <= 0 || maxHandshakes <= 0
                || maxBufferedPartialFrameBytes <= 0L
                || partialFrameTimeout.isNegative()
                || partialFrameTimeout.isZero()
                || partialFrameTimeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(
                    "Transport admission limits must be positive and finite");
        }
    }
}
