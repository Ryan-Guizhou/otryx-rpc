package com.peachsoft.otryx.transport;

import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.protocol.RpcCompressionIds;
import com.peachsoft.otryx.protocol.RpcConnectionCapabilities;
import com.peachsoft.otryx.protocol.RpcFeature;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.observability.RpcObserver;
import java.time.Duration;
import java.util.Set;

/**
 * 传输层容量、超时、连接恢复与连接能力配置。
 *
 * @param maxInflightPerConnection 单连接最大并发请求数
 * @param maxFrameBytes 单个完整协议帧最大字节数
 * @param maxWriteQueueBytes 单连接写队列最大字节数
 * @param connectTimeout TCP 建连超时时间
 * @param handshakeTimeout 协议握手超时时间
 * @param codecIds 当前进程可用 Codec 线协议编号
 * @param connectionsPerEndpoint 每个服务端点的连接分片数
 * @param heartbeatInterval 空闲连接发送 PING 前的间隔
 * @param heartbeatTimeout PING 发出后等待活跃流量/PONG 的最长时间
 * @param reconnectBaseBackoff 异常建连/断链后的基础重连退避
 * @param reconnectMaxBackoff 重连退避最大窗口
 * @param observer 连接生命周期 Observer
 * @param security TLS/mTLS 配置
 * @param admission Transport 连接、握手和部分帧预算
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public record RpcTransportOptions(
        int maxInflightPerConnection,
        int maxFrameBytes,
        int maxWriteQueueBytes,
        Duration connectTimeout,
        Duration handshakeTimeout,
        Set<Byte> codecIds,
        int connectionsPerEndpoint,
        Duration heartbeatInterval,
        Duration heartbeatTimeout,
        Duration reconnectBaseBackoff,
        Duration reconnectMaxBackoff,
        RpcObserver observer,
        RpcTransportSecurityOptions security,
        RpcTransportAdmissionOptions admission) {

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL =
            Duration.ofSeconds(30);
    private static final Duration DEFAULT_HEARTBEAT_TIMEOUT =
            Duration.ofSeconds(10);
    private static final Duration DEFAULT_RECONNECT_BASE_BACKOFF =
            Duration.ofMillis(50);
    private static final Duration DEFAULT_RECONNECT_MAX_BACKOFF =
            Duration.ofSeconds(3);

    /** 默认传输配置。 */
    public static final RpcTransportOptions DEFAULT = new RpcTransportOptions(
            1024,
            16 * 1024 * 1024,
            4 * 1024 * 1024,
            Duration.ofSeconds(3),
            Duration.ofSeconds(3),
            Set.of(RpcCodecIds.FORY_NATIVE),
            1,
            DEFAULT_HEARTBEAT_INTERVAL,
            DEFAULT_HEARTBEAT_TIMEOUT,
            DEFAULT_RECONNECT_BASE_BACKOFF,
            DEFAULT_RECONNECT_MAX_BACKOFF,
            RpcObserver.noop(),
            RpcTransportSecurityOptions.PLAINTEXT);

    /**
     * 保留 V2-A 四参数构造方式。
     *
     * @param maxInflightPerConnection 单连接最大并发请求数
     * @param maxFrameBytes 单帧最大字节数
     * @param maxWriteQueueBytes 写队列最大字节数
     * @param connectTimeout 建连超时
     */
    public RpcTransportOptions(
            int maxInflightPerConnection,
            int maxFrameBytes,
            int maxWriteQueueBytes,
            Duration connectTimeout) {
        this(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                connectTimeout,
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
    }

    /**
     * 保留 V2-B Codec 能力五参数构造方式。
     *
     * @param maxInflightPerConnection 单连接最大并发请求数
     * @param maxFrameBytes 单帧最大字节数
     * @param maxWriteQueueBytes 写队列最大字节数
     * @param connectTimeout 建连超时
     * @param codecIds 可用 Codec
     */
    public RpcTransportOptions(
            int maxInflightPerConnection,
            int maxFrameBytes,
            int maxWriteQueueBytes,
            Duration connectTimeout,
            Set<Byte> codecIds) {
        this(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                connectTimeout,
                codecIds,
                1);
    }

    /**
     * 保留 V2-B 连接分片六参数构造方式。
     *
     * @param maxInflightPerConnection 单连接最大并发请求数
     * @param maxFrameBytes 单帧最大字节数
     * @param maxWriteQueueBytes 写队列最大字节数
     * @param connectTimeout 建连超时
     * @param codecIds 可用 Codec
     * @param connectionsPerEndpoint 每端点连接数
     */
    public RpcTransportOptions(
            int maxInflightPerConnection,
            int maxFrameBytes,
            int maxWriteQueueBytes,
            Duration connectTimeout,
            Set<Byte> codecIds,
            int connectionsPerEndpoint) {
        this(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                connectTimeout,
                codecIds,
                connectionsPerEndpoint);
    }

    /**
     * 保留 V2-C.1 七参数构造方式。
     *
     * @param maxInflightPerConnection 单连接最大并发请求数
     * @param maxFrameBytes 单帧最大字节数
     * @param maxWriteQueueBytes 写队列最大字节数
     * @param connectTimeout 建连超时
     * @param handshakeTimeout 握手超时
     * @param codecIds 可用 Codec
     * @param connectionsPerEndpoint 每端点连接数
     */
    public RpcTransportOptions(
            int maxInflightPerConnection,
            int maxFrameBytes,
            int maxWriteQueueBytes,
            Duration connectTimeout,
            Duration handshakeTimeout,
            Set<Byte> codecIds,
            int connectionsPerEndpoint) {
        this(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                handshakeTimeout,
                codecIds,
                connectionsPerEndpoint,
                DEFAULT_HEARTBEAT_INTERVAL,
                DEFAULT_HEARTBEAT_TIMEOUT,
                DEFAULT_RECONNECT_BASE_BACKOFF,
                DEFAULT_RECONNECT_MAX_BACKOFF);
    }

    /**
     * 保留 V2-C.2 十一参数构造方式，默认使用 NOOP Observer。
     *
     * @param maxInflightPerConnection 单连接最大并发请求数
     * @param maxFrameBytes 单帧最大字节数
     * @param maxWriteQueueBytes 写队列最大字节数
     * @param connectTimeout 建连超时
     * @param handshakeTimeout 握手超时
     * @param codecIds 可用 Codec
     * @param connectionsPerEndpoint 每端点连接数
     * @param heartbeatInterval Heartbeat 间隔
     * @param heartbeatTimeout Heartbeat 超时
     * @param reconnectBaseBackoff 基础重连退避
     * @param reconnectMaxBackoff 最大重连退避
     */
    public RpcTransportOptions(
            int maxInflightPerConnection,
            int maxFrameBytes,
            int maxWriteQueueBytes,
            Duration connectTimeout,
            Duration handshakeTimeout,
            Set<Byte> codecIds,
            int connectionsPerEndpoint,
            Duration heartbeatInterval,
            Duration heartbeatTimeout,
            Duration reconnectBaseBackoff,
            Duration reconnectMaxBackoff) {
        this(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                handshakeTimeout,
                codecIds,
                connectionsPerEndpoint,
                heartbeatInterval,
                heartbeatTimeout,
                reconnectBaseBackoff,
                reconnectMaxBackoff,
                RpcObserver.noop(),
                RpcTransportSecurityOptions.PLAINTEXT);
    }

    /**
     * 保留 V2-C.2 Observer 构造方式，默认明文 Transport。
     *
     * @param maxInflightPerConnection 单连接最大并发请求数
     * @param maxFrameBytes 单帧最大字节数
     * @param maxWriteQueueBytes 写队列最大字节数
     * @param connectTimeout 建连超时
     * @param handshakeTimeout Peach 协议握手超时
     * @param codecIds 可用 Codec
     * @param connectionsPerEndpoint 每端点连接数
     * @param heartbeatInterval Heartbeat 间隔
     * @param heartbeatTimeout Heartbeat 超时
     * @param reconnectBaseBackoff 基础重连退避
     * @param reconnectMaxBackoff 最大重连退避
     * @param observer 连接 Observer
     */
    public RpcTransportOptions(
            int maxInflightPerConnection,
            int maxFrameBytes,
            int maxWriteQueueBytes,
            Duration connectTimeout,
            Duration handshakeTimeout,
            Set<Byte> codecIds,
            int connectionsPerEndpoint,
            Duration heartbeatInterval,
            Duration heartbeatTimeout,
            Duration reconnectBaseBackoff,
            Duration reconnectMaxBackoff,
            RpcObserver observer) {
        this(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                handshakeTimeout,
                codecIds,
                connectionsPerEndpoint,
                heartbeatInterval,
                heartbeatTimeout,
                reconnectBaseBackoff,
                reconnectMaxBackoff,
                observer,
                RpcTransportSecurityOptions.PLAINTEXT);
    }

    /**
     * 将旧版参数表映射到默认 Transport 准入预算。
     *
     * @param maxInflightPerConnection 单连接最大并发请求数
     * @param maxFrameBytes 单帧字节数
     * @param maxWriteQueueBytes 写队列字节数
     * @param connectTimeout TCP 建连超时
     * @param handshakeTimeout RPC 握手超时
     * @param codecIds Codec 能力
     * @param connectionsPerEndpoint 每 Endpoint 的连接分片数
     * @param heartbeatInterval 心跳间隔
     * @param heartbeatTimeout 心跳超时
     * @param reconnectBaseBackoff 重连基础退避
     * @param reconnectMaxBackoff 最大退避
     * @param observer Observer
     * @param security TLS/mTLS 配置
     */
    public RpcTransportOptions(
            int maxInflightPerConnection,
            int maxFrameBytes,
            int maxWriteQueueBytes,
            Duration connectTimeout,
            Duration handshakeTimeout,
            Set<Byte> codecIds,
            int connectionsPerEndpoint,
            Duration heartbeatInterval,
            Duration heartbeatTimeout,
            Duration reconnectBaseBackoff,
            Duration reconnectMaxBackoff,
            RpcObserver observer,
            RpcTransportSecurityOptions security) {
        this(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                handshakeTimeout,
                codecIds,
                connectionsPerEndpoint,
                heartbeatInterval,
                heartbeatTimeout,
                reconnectBaseBackoff,
                reconnectMaxBackoff,
                observer,
                security,
                RpcTransportAdmissionOptions.DEFAULT);
    }

    /** 校验容量、超时与能力配置。 */
    public RpcTransportOptions {
        if (maxInflightPerConnection <= 0
                || maxFrameBytes <= 0
                || maxWriteQueueBytes <= 0
                || connectionsPerEndpoint <= 0) {
            throw new IllegalArgumentException(
                    "transport limits must be positive");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(handshakeTimeout, "handshakeTimeout");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(heartbeatTimeout, "heartbeatTimeout");
        requirePositive(reconnectBaseBackoff, "reconnectBaseBackoff");
        requirePositive(reconnectMaxBackoff, "reconnectMaxBackoff");
        if (reconnectMaxBackoff.compareTo(reconnectBaseBackoff) < 0) {
            throw new IllegalArgumentException(
                    "reconnectMaxBackoff must be >= reconnectBaseBackoff");
        }
        codecIds = codecIds == null ? Set.of() : Set.copyOf(codecIds);
        observer = observer == null ? RpcObserver.noop() : observer;
        security = security == null
                ? RpcTransportSecurityOptions.PLAINTEXT
                : security;
        admission = admission == null
                ? RpcTransportAdmissionOptions.DEFAULT
                : admission;
        if (codecIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one transport codec id is required");
        }
    }

    /**
     * 使用指定 Observer 创建等价传输配置。
     *
     * @param value 连接生命周期 Observer
     * @return 带 Observer 的新配置
     */
    public RpcTransportOptions withObserver(RpcObserver value) {
        return new RpcTransportOptions(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                handshakeTimeout,
                codecIds,
                connectionsPerEndpoint,
                heartbeatInterval,
                heartbeatTimeout,
                reconnectBaseBackoff,
                reconnectMaxBackoff,
                value,
                security,
                admission);
    }

    /**
     * 使用指定安全配置创建等价传输配置。
     *
     * @param value TLS/mTLS 配置
     * @return 带安全配置的新参数
     */
    public RpcTransportOptions withSecurity(
            RpcTransportSecurityOptions value) {
        return new RpcTransportOptions(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                handshakeTimeout,
                codecIds,
                connectionsPerEndpoint,
                heartbeatInterval,
                heartbeatTimeout,
                reconnectBaseBackoff,
                reconnectMaxBackoff,
                observer,
                value,
                admission);
    }

    /**
     * 使用指定的 Transport 准入预算构建等价配置。
     *
     * @param value 连接、握手与部分帧准入预算
     * @return 带新预算的配置
     */
    public RpcTransportOptions withAdmission(RpcTransportAdmissionOptions value) {
        return new RpcTransportOptions(
                maxInflightPerConnection,
                maxFrameBytes,
                maxWriteQueueBytes,
                connectTimeout,
                handshakeTimeout,
                codecIds,
                connectionsPerEndpoint,
                heartbeatInterval,
                heartbeatTimeout,
                reconnectBaseBackoff,
                reconnectMaxBackoff,
                observer,
                security,
                value);
    }

    /**
     * 创建本端连接握手能力。
     *
     * @return 连接能力
     */
    public RpcConnectionCapabilities capabilities() {
        return new RpcConnectionCapabilities(
                Set.of(RpcProtocolCodec.VERSION),
                codecIds,
                Set.of(RpcCompressionIds.NONE),
                Set.of(
                        RpcFeature.DEADLINE,
                        RpcFeature.CANCEL,
                        RpcFeature.GO_AWAY,
                        RpcFeature.HEARTBEAT),
                maxFrameBytes);
    }

    private static void requirePositive(
            Duration value,
            String name) {
        if (value == null
                || value.isNegative()
                || value.isZero()) {
            throw new IllegalArgumentException(
                    name + " must be positive");
        }
    }
}
