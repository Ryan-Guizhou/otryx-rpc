package com.peachsoft.otryx.transport.vertx;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcRemoteError;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcTimeoutException;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.observability.RpcCertificateReloadOutcome;
import com.peachsoft.otryx.observability.RpcConnectionCloseReason;
import com.peachsoft.otryx.observability.RpcConnectionRole;
import com.peachsoft.otryx.protocol.RpcErrorCodec;
import com.peachsoft.otryx.protocol.RpcFeature;
import com.peachsoft.otryx.protocol.RpcFrame;
import com.peachsoft.otryx.protocol.RpcFrameView;
import com.peachsoft.otryx.protocol.RpcHandshakeCodec;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcNegotiatedCapabilities;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.protocol.RpcProtocolException;
import com.peachsoft.otryx.transport.ResponseByteBudget;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportServer;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x TCP Provider 传输服务器，管理入站连接、帧重组与关闭排空。
 *
 * <p>每个连接持有独立的帧缓冲和请求状态；Transport 负责在 EventLoop
 * 解析 Wire v1 帧并把完整请求交给 Core，不负责直接执行业务逻辑。
 *
 * <p>响应写入必须遵守协商的最大 Frame 大小与 Netty/Vert.x 写队列
 * 容量限制。连接排空和 TLS 资源在关闭路径中统一处理。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
final class VertxRpcTransportServer implements RpcTransportServer {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(VertxRpcTransportServer.class);
    private static final int MESSAGE_TYPE_OFFSET = 6;
    private static final int CODEC_OFFSET = 7;
    private static final String INFLIGHT_OVERLOADED_MESSAGE =
            "RPC connection inflight capacity exhausted";

    private final Vertx vertx = Vertx.vertx();
    private final NetServer server;
    private final RpcTransportOptions options;
    private final TransportAdmissionController admission;
    private final ResponseByteBudget responseBudget;
    private final VertxTlsSupport.FileState initialTlsFileState;
    private final Set<ServerConnection> connections =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closingServer = new AtomicBoolean();
    private volatile CompletableFuture<Void> drainFuture;
    private volatile long drainTimerId = -1L;
    private volatile long tlsReloadTimerId = -1L;
    private volatile TlsReloadController<VertxTlsSupport.FileState> tlsReloadController;

    VertxRpcTransportServer(RpcTransportOptions options) {
        this.options = options;
        this.admission = new TransportAdmissionController(options.admission());
        this.responseBudget = new ResponseByteBudget(
                options.admission().maxBufferedResponseBytes());
        NetServerOptions serverOptions =
                new NetServerOptions()
                        .setTcpKeepAlive(true)
                        .setTcpNoDelay(true);
        VertxTlsSupport.configureServer(
                serverOptions,
                options.security(),
                options.observer());
        // 与初始 SSL 配置在同一构造阶段建立基线；运行期不在 EventLoop 读取文件。
        this.initialTlsFileState = options.security().enabled()
                ? VertxTlsSupport.fileState(options.security())
                : null;
        this.server = vertx.createNetServer(serverOptions);
    }

    @Override
    public CompletionStage<RpcEndpoint> start(
            RpcEndpoint bind,
            RpcRequestHandler handler) {
        CompletableFuture<RpcEndpoint> started =
                new CompletableFuture<>();
        server.connectHandler(socket -> {
                    if (draining.get()) {
                        socket.close();
                        return;
                    }
                    TransportAdmissionController.Lease connectionLease =
                            admission.tryAcquireConnection();
                    if (connectionLease == null) {
                        // 配额已满：在进入连接状态机之前立即关闭。
                        socket.close();
                        return;
                    }
                    TransportAdmissionController.Lease handshakeLease =
                            admission.tryAcquireHandshake();
                    if (handshakeLease == null) {
                        connectionLease.close();
                        socket.close();
                        return;
                    }
                    try {
                        if (options.security().enabled()
                                && options.observer().enabled()) {
                            RpcEndpoint remote = new RpcEndpoint(
                                    socket.remoteAddress().host(),
                                    Math.max(socket.remoteAddress().port(), 1));
                            options.observer()
                                    .onTlsHandshakeCompleted(
                                            RpcConnectionRole.SERVER,
                                            remote,
                                            options.security().mode(),
                                            0L,
                                            null);
                        }
                        ServerConnection connection =
                                new ServerConnection(
                                        socket, handler, connectionLease, handshakeLease);
                        connections.add(connection);
                        if (draining.get()) {
                            connection.closeLocally();
                        }
                    } catch (RuntimeException error) {
                        handshakeLease.close();
                        connectionLease.close();
                        socket.close();
                        LOGGER.warn(
                                "RPC admission setup failed: errorType={}",
                                error.getClass().getName());
                    }
                })
                .listen(bind.port(), bind.host())
                .onComplete(result -> {
                    if (result.failed()) {
                        started.completeExceptionally(result.cause());
                    } else {
                        startTlsReload();
                        started.complete(new RpcEndpoint(
                                bind.host(),
                                result.result().actualPort()));
                    }
                });
        return started;
    }

    /**
     * 只信任成功完成 mTLS 客户端证书校验的连接主体；其他模式一律匿名。
     *
     * @param socket 已建立的 TLS/明文连接
     * @return Transport 验证的身份，不能从请求 Metadata 取得
     */
    private RpcPeerIdentity verifiedPeer(NetSocket socket) {
        if (!options.security().mutualTls()) {
            return RpcPeerIdentity.anonymous();
        }
        try {
            var certificates = socket.peerCertificates();
            if (certificates == null || certificates.isEmpty()
                    || !(certificates.getFirst() instanceof X509Certificate certificate)) {
                throw new IllegalStateException("Verified mTLS client certificate is missing");
            }
            byte[] fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            return RpcPeerIdentity.verifiedClientCertificate(
                    certificate.getSubjectX500Principal().getName(),
                    HexFormat.of().formatHex(fingerprint));
        } catch (SSLPeerUnverifiedException | CertificateEncodingException
                 | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Failed to resolve verified mTLS client identity", error);
        }
    }

    private final class ServerConnection {
        private final NetSocket socket;
        private final TransportAdmissionController.Lease connectionLease;
        private final TransportAdmissionController.Lease handshakeLease;
        private final RpcRequestHandler handler;
        private final Context context;
        private final RpcEndpoint remote;
        private final RpcPeerIdentity peerIdentity;
        private final FrameAccumulator accumulator =
                new FrameAccumulator(options.maxFrameBytes(), admission);
        private final Map<Long, CompletableFuture<byte[]>> inflight =
                new HashMap<>();
        private final Set<ResponseByteBudget.Lease> pendingResponses =
                new HashSet<>();

        private boolean open = true;
        private boolean handshakeComplete;
        private boolean connectionDraining;
        private long handshakeTimerId = -1L;
        private long heartbeatTimerId = -1L;
        private long partialFrameTimerId = -1L;
        private final long createdAtNanos = System.nanoTime();
        private long lastReadNanos = createdAtNanos;
        private long pingSentAtNanos;
        private RpcConnectionCloseReason closeReason =
                RpcConnectionCloseReason.REMOTE_CLOSE;
        private Throwable closeError;
        private boolean closeObserved;
        private RpcNegotiatedCapabilities negotiated;

        private ServerConnection(
                NetSocket socket,
                RpcRequestHandler handler,
                TransportAdmissionController.Lease connectionLease,
                TransportAdmissionController.Lease handshakeLease) {
            this.socket = socket;
            this.peerIdentity = verifiedPeer(socket);
            this.connectionLease = connectionLease;
            this.handshakeLease = handshakeLease;
            this.handler = handler;
            this.context = Vertx.currentContext();
            if (context == null) {
                throw new IllegalStateException(
                        "Vert.x server connection must use an event loop");
            }
            this.remote = new RpcEndpoint(
                    socket.remoteAddress().host(),
                    Math.max(
                            socket.remoteAddress().port(),
                            1));

            socket.setWriteQueueMaxSize(
                    options.maxWriteQueueBytes());
            socket.handler(this::onBuffer);
            socket.exceptionHandler(this::closeWithError);
            socket.closeHandler(ignored -> {
                cancelHandshakeTimer();
                cancelHeartbeatTimer();
                cancelPartialFrameTimer();
                accumulator.clear();
                open = false;
                inflight.values().forEach(future ->
                        future.cancel(true));
                inflight.clear();
                // 连接关闭后必须归还其持有的所有待写 RESPONSE 额度。
                pendingResponses.forEach(ResponseByteBudget.Lease::close);
                pendingResponses.clear();
                connections.remove(this);
                // 所有异步关闭、异常与握手失败共用此所有权释放点。
                handshakeLease.close();
                connectionLease.close();
                observeClosed();
                checkDrainComplete();
            });
            handshakeTimerId = vertx.setTimer(
                    options.handshakeTimeout().toMillis(),
                    ignored -> closeMalformed(new RpcProtocolException(
                            "RPC handshake timed out")));
        }

        private void onBuffer(Buffer buffer) {
            try {
                boolean consumedFrame =
                        accumulator.accept(buffer, this::onFrame);
                if (accumulator.bufferedBytes() == 0) {
                    cancelPartialFrameTimer();
                } else if (consumedFrame || partialFrameTimerId < 0L) {
                    // 追加少量碎片不重置时限，避免慢速发送攻击无限续命。
                    cancelPartialFrameTimer();
                    partialFrameTimerId = vertx.setTimer(
                            Math.max(1L, options.admission()
                                    .partialFrameTimeout().toMillis()),
                            ignored -> expirePartialFrame());
                }
            } catch (RuntimeException error) {
                cancelPartialFrameTimer();
                accumulator.clear();
                closeMalformed(error);
            }
        }

        private void expirePartialFrame() {
            partialFrameTimerId = -1L;
            if (!open || accumulator.bufferedBytes() == 0) {
                return;
            }
            accumulator.clear();
            closeMalformed(new RpcProtocolException(
                    "Incomplete RPC frame exceeded configured timeout"));
        }

        private void cancelPartialFrameTimer() {
            if (partialFrameTimerId >= 0L) {
                vertx.cancelTimer(partialFrameTimerId);
                partialFrameTimerId = -1L;
            }
        }

        private void onFrame(byte[] frame) {
            lastReadNanos = System.nanoTime();
            pingSentAtNanos = 0L;
            if (!handshakeComplete) {
                handleHello(frame);
                return;
            }
            if (frame.length > negotiated.maxFrameBytes()) {
                closeMalformed(new RpcProtocolException(
                        "RPC frame exceeds negotiated maxFrameBytes"));
                return;
            }
            byte messageType = frame[MESSAGE_TYPE_OFFSET];
            if (messageType == RpcMessageType.PING.code()
                    || messageType == RpcMessageType.PONG.code()) {
                handleHeartbeat(frame);
                return;
            }
            if (messageType == RpcMessageType.CANCEL.code()) {
                handleCancel(frame);
                return;
            }
            if (messageType != RpcMessageType.REQUEST.code()) {
                closeMalformed(new RpcProtocolException(
                        "Expected REQUEST or CANCEL after handshake"));
                return;
            }
            // drain() publishes the global flag before each connection is
            // switched to its EventLoop-owned draining state. Reject new
            // requests across that handover window as well.
            if (draining.get() || connectionDraining) {
                rejectDrainingRequest(frame);
                return;
            }
            byte codecId = frame[CODEC_OFFSET];
            if (!negotiated.codecIds().contains(codecId)) {
                closeMalformed(new RpcProtocolException(
                        "RPC codec was not negotiated: "
                                + Byte.toUnsignedInt(codecId)));
                return;
            }

            long requestId =
                    RpcProtocolCodec.readRequestId(frame);
            if (requestId == 0L) {
                closeMalformed(new RpcProtocolException(
                        "RPC REQUEST requires a request id"));
                return;
            }
            if (inflight.containsKey(requestId)) {
                closeMalformed(new RpcProtocolException(
                        "Duplicate RPC request id: "
                                + requestId));
                return;
            }
            if (inflight.size() >= options.maxInflightPerConnection()) {
                rejectOverloadedRequest(frame);
                return;
            }
            CompletableFuture<byte[]> requestFuture =
                    handler.handle(remote, peerIdentity, frame).toCompletableFuture();
            // 只有返回 Future 并取得所有权之后才计入连接级协议槽位。
            inflight.put(requestId, requestFuture);
            requestFuture.whenComplete((response, error) ->
                    context.runOnContext(ignored ->
                            completeRequest(
                                    requestId,
                                    requestFuture,
                                    response,
                                    error)));
        }

        /**
         * 拒绝超出当前连接入站请求容量的 RPC，不进入业务 Handler。
         *
         * <p>有限制的拒绝响应不会消耗 Inflight 槽位。如果写队列已满，
         * 则转为 GO_AWAY 关闭连接，避免继续积压拒绝帧。
         *
         * @param bytes 已通过请求类型、Request ID 与 Codec 校验的完整帧
         */
        private void rejectOverloadedRequest(byte[] bytes) {
            RpcFrameView request = RpcProtocolCodec.view(bytes);
            byte[] rejected = RpcProtocolCodec.encodeResponse(
                    RpcCodecIds.CONTROL,
                    RpcStatus.OVERLOADED,
                    request.requestId(),
                    request.serviceId(),
                    request.methodId(),
                    RpcErrorCodec.encode(new RpcRemoteError(
                            RpcProtocolException.class.getName(),
                            INFLIGHT_OVERLOADED_MESSAGE)));
            if (rejected.length > negotiated.maxFrameBytes()
                    || socket.writeQueueFull()) {
                goAwayAndClose(
                        RpcStatus.OVERLOADED,
                        INFLIGHT_OVERLOADED_MESSAGE);
                return;
            }
            socket.write(Buffer.buffer(rejected))
                    .onFailure(this::closeWithError);
        }

        private void handleHello(byte[] bytes) {
            try {
                RpcFrame hello = RpcProtocolCodec.decode(bytes);
                if (hello.messageType() != RpcMessageType.HELLO
                        || hello.requestId() != 0L
                        || hello.codec() != RpcCodecIds.CONTROL
                        || hello.status() != RpcStatus.OK
                        || hello.serviceId() != 0
                        || hello.methodId() != 0) {
                    throw new RpcProtocolException(
                            "Expected HELLO as first client frame");
                }
                var clientCapabilities =
                        RpcHandshakeCodec.decode(hello.payload());
                negotiated = RpcHandshakeCodec.negotiate(
                        clientCapabilities,
                        options.capabilities());

                RpcFrame ack = new RpcFrame(
                        RpcMessageType.HELLO_ACK,
                        RpcCodecIds.CONTROL,
                        RpcStatus.OK,
                        0L,
                        0,
                        0,
                        Map.of(),
                        RpcHandshakeCodec.encode(
                                options.capabilities()));
                byte[] encoded = RpcProtocolCodec.encode(ack);
                handshakeComplete = true;
                socket.write(Buffer.buffer(encoded))
                        .onSuccess(ignored -> {
                            // 握手超时覆盖 HELLO_ACK 写出全过程，避免挂起的 ACK 租约。
                            cancelHandshakeTimer();
                            handshakeLease.close();
                            if (options.observer().enabled()) {
                                options.observer()
                                        .onConnectionEstablished(
                                                RpcConnectionRole.SERVER,
                                                remote,
                                                System.nanoTime()
                                                        - createdAtNanos);
                            }
                            startHeartbeat();
                            if (draining.get()) {
                                beginDrain();
                            }
                        })
                        .onFailure(this::closeWithError);
            } catch (Throwable error) {
                closeMalformed(error);
            }
        }

        private void completeRequest(
                long requestId,
                CompletableFuture<byte[]> requestFuture,
                byte[] response,
                Throwable error) {
            // CANCEL 后的旧 Future 可能迟到：只释放它实际持有的槽位，
            // 不能仅凭 Request ID 删除后来复用同一 ID 的新请求。
            if (!inflight.remove(requestId, requestFuture)) {
                finishConnectionDrainIfIdle();
                return;
            }
            if (!open) {
                return;
            }
            if (error != null) {
                LOGGER.error(
                        "RPC request handler failed: remote={}, errorType={}",
                        remote.authority(),
                        error.getClass().getName());
                goAwayAndClose(
                        RpcStatus.INTERNAL_ERROR,
                        "RPC request handler failed: "
                                + error.getClass().getName());
                return;
            }
            if (response.length > negotiated.maxFrameBytes()) {
                LOGGER.warn(
                        "Closing RPC connection: oversized response frame. "
                                + "remote={}, frameBytes={}, maxFrameBytes={}",
                        remote.authority(),
                        response.length,
                        negotiated.maxFrameBytes());
                goAwayAndClose(
                        RpcStatus.OVERLOADED,
                        "RPC response exceeds negotiated max frame size");
                return;
            }
            if (socket.writeQueueFull()) {
                LOGGER.warn(
                        "Closing RPC connection: response write queue is full. remote={}",
                        remote.authority());
                goAwayAndClose(
                        RpcStatus.OVERLOADED,
                        "RPC response write queue is full");
                return;
            }
            ResponseByteBudget.Lease lease =
                    responseBudget.tryAcquire(response.length);
            if (lease == null) {
                goAwayAndClose(
                        RpcStatus.OVERLOADED,
                        "RPC encoded-response capacity exhausted");
                return;
            }
            pendingResponses.add(lease);
            try {
                socket.write(Buffer.buffer(response))
                        .onComplete(result -> {
                            pendingResponses.remove(lease);
                            lease.close();
                            if (result.failed()) {
                                closeWithError(result.cause());
                            } else {
                                finishConnectionDrainIfIdle();
                            }
                        });
            } catch (RuntimeException failure) {
                pendingResponses.remove(lease);
                lease.close();
                closeWithError(failure);
            }
        }

        private void startHeartbeat() {
            if (!negotiated.features().contains(RpcFeature.HEARTBEAT)) {
                return;
            }
            long intervalMillis = options.heartbeatInterval().toMillis();
            long timeoutMillis = options.heartbeatTimeout().toMillis();
            long tickMillis = Math.max(
                    1L,
                    Math.min(intervalMillis, timeoutMillis));
            heartbeatTimerId = vertx.setPeriodic(
                    tickMillis,
                    ignored -> heartbeatTick());
        }

        private void heartbeatTick() {
            if (!open || connectionDraining || negotiated == null) {
                return;
            }
            long now = System.nanoTime();
            if (pingSentAtNanos > 0L) {
                if (now - pingSentAtNanos
                        >= options.heartbeatTimeout().toNanos()) {
                    RpcTimeoutException error =
                            new RpcTimeoutException(
                                    "RPC heartbeat timed out for "
                                            + remote.authority());
                    if (options.observer().enabled()) {
                        options.observer()
                                .onConnectionHeartbeatTimeout(
                                        RpcConnectionRole.SERVER,
                                        remote,
                                        now - pingSentAtNanos);
                    }
                    markClose(
                            RpcConnectionCloseReason.HEARTBEAT_TIMEOUT,
                            error);
                    closeWithError(error);
                }
                return;
            }
            if (now - lastReadNanos
                    < options.heartbeatInterval().toNanos()) {
                return;
            }
            pingSentAtNanos = now;
            socket.write(Buffer.buffer(
                            RpcProtocolCodec.encodeHeartbeat(
                                    RpcMessageType.PING)))
                    .onFailure(this::closeWithError);
        }

        private void handleHeartbeat(byte[] bytes) {
            try {
                if (!negotiated.features().contains(RpcFeature.HEARTBEAT)) {
                    throw new RpcProtocolException(
                            "RPC heartbeat was not negotiated");
                }
                RpcFrame frame = RpcProtocolCodec.decode(bytes);
                if (frame.requestId() != 0L
                        || frame.codec() != RpcCodecIds.CONTROL
                        || frame.status() != RpcStatus.OK
                        || frame.payload().length != 0) {
                    throw new RpcProtocolException(
                            "Invalid RPC heartbeat frame");
                }
                if (frame.messageType() == RpcMessageType.PING) {
                    socket.write(Buffer.buffer(
                                    RpcProtocolCodec.encodeHeartbeat(
                                            RpcMessageType.PONG)))
                            .onFailure(this::closeWithError);
                    return;
                }
                if (frame.messageType() != RpcMessageType.PONG) {
                    throw new RpcProtocolException(
                            "Unexpected RPC heartbeat frame");
                }
            } catch (Throwable error) {
                closeMalformed(error);
            }
        }

        private void handleCancel(byte[] bytes) {
            if (!negotiated.features().contains(RpcFeature.CANCEL)) {
                closeMalformed(new RpcProtocolException(
                        "RPC CANCEL was not negotiated"));
                return;
            }
            RpcFrame cancel = RpcProtocolCodec.decode(bytes);
            if (cancel.requestId() == 0L
                    || cancel.codec() != RpcCodecIds.CONTROL
                    || cancel.status() != RpcStatus.OK
                    || cancel.serviceId() != 0
                    || cancel.methodId() != 0
                    || cancel.payload().length != 0) {
                closeMalformed(new RpcProtocolException(
                        "Invalid RPC CANCEL frame"));
                return;
            }
            CompletableFuture<byte[]> request =
                    inflight.remove(cancel.requestId());
            if (request != null) {
                request.cancel(true);
            }
            finishConnectionDrainIfIdle();
        }

        private void rejectDrainingRequest(byte[] bytes) {
            RpcFrame request = RpcProtocolCodec.decode(bytes);
            byte[] response = RpcProtocolCodec.encodeResponse(
                    RpcCodecIds.CONTROL,
                    RpcStatus.UNAVAILABLE,
                    request.requestId(),
                    request.serviceId(),
                    request.methodId(),
                    RpcErrorCodec.encode(new RpcRemoteError(
                            RpcProtocolException.class.getName(),
                            "RPC server is draining")));
            socket.write(Buffer.buffer(response))
                    .onFailure(this::closeWithError);
        }

        private void beginDrain() {
            if (Vertx.currentContext() != context) {
                context.runOnContext(ignored -> beginDrain());
                return;
            }
            if (!open || connectionDraining) {
                return;
            }
            connectionDraining = true;
            markClose(
                    RpcConnectionCloseReason.GO_AWAY,
                    null);
            cancelHeartbeatTimer();
            if (!handshakeComplete) {
                socket.close();
                return;
            }
            sendGoAway(
                    RpcStatus.UNAVAILABLE,
                    "RPC server is draining");
            finishConnectionDrainIfIdle();
        }

        private void finishConnectionDrainIfIdle() {
            if (connectionDraining
                    && inflight.isEmpty()
                    && pendingResponses.isEmpty()
                    && open) {
                socket.end();
            }
        }

        private void sendGoAway(
                RpcStatus status,
                String message) {
            RpcFrame goAway = new RpcFrame(
                    RpcMessageType.GO_AWAY,
                    RpcCodecIds.CONTROL,
                    status,
                    0L,
                    0,
                    0,
                    Map.of(),
                    RpcErrorCodec.encode(new RpcRemoteError(
                            RpcProtocolException.class.getName(),
                            message)));
            socket.write(Buffer.buffer(
                            RpcProtocolCodec.encode(goAway)))
                    .onFailure(this::closeWithError);
        }

        private void cancelHandshakeTimer() {
            if (handshakeTimerId >= 0L) {
                vertx.cancelTimer(handshakeTimerId);
                handshakeTimerId = -1L;
            }
        }

        private void cancelHeartbeatTimer() {
            if (heartbeatTimerId >= 0L) {
                vertx.cancelTimer(heartbeatTimerId);
                heartbeatTimerId = -1L;
            }
            pingSentAtNanos = 0L;
        }

        private void closeMalformed(Throwable error) {
            LOGGER.warn(
                    "Closing malformed RPC connection: remote={}, errorType={}",
                    remote.authority(),
                    error == null ? "unknown" : error.getClass().getName());
            if (!open) {
                return;
            }
            markClose(
                    RpcConnectionCloseReason.PROTOCOL_ERROR,
                    error);
            goAwayAndClose(
                    RpcStatus.BAD_REQUEST,
                    error.getMessage() == null
                            ? "RPC protocol rejected"
                            : error.getMessage());
        }

        private void goAwayAndClose(
                RpcStatus status,
                String message) {
            if (!open) {
                return;
            }
            if (closeReason == RpcConnectionCloseReason.REMOTE_CLOSE) {
                markClose(
                        RpcConnectionCloseReason.GO_AWAY,
                        null);
            }
            cancelHandshakeTimer();
            cancelHeartbeatTimer();
            RpcFrame goAway = new RpcFrame(
                    RpcMessageType.GO_AWAY,
                    RpcCodecIds.CONTROL,
                    status,
                    0L,
                    0,
                    0,
                    Map.of(),
                    RpcErrorCodec.encode(new RpcRemoteError(
                            RpcProtocolException.class.getName(),
                            message)));
            socket.end(Buffer.buffer(
                    RpcProtocolCodec.encode(goAway)));
        }

        private void closeWithError(Throwable error) {
            if (!open) {
                return;
            }
            if (closeReason == RpcConnectionCloseReason.REMOTE_CLOSE) {
                markClose(
                        error instanceof RpcProtocolException
                                ? RpcConnectionCloseReason.PROTOCOL_ERROR
                                : RpcConnectionCloseReason.TRANSPORT_ERROR,
                        error);
            }
            cancelHandshakeTimer();
            cancelHeartbeatTimer();
            LOGGER.debug(
                    "RPC connection failed: remote={}, errorType={}",
                    remote.authority(),
                    error == null ? "unknown" : error.getClass().getName());
            socket.close();
        }

        private void closeLocally() {
            if (!open) {
                return;
            }
            markClose(
                    RpcConnectionCloseReason.LOCAL_CLOSE,
                    null);
            socket.close();
        }

        private void markClose(
                RpcConnectionCloseReason reason,
                Throwable error) {
            closeReason = reason;
            closeError = error;
        }

        private void observeClosed() {
            if (closeObserved || !options.observer().enabled()) {
                return;
            }
            closeObserved = true;
            options.observer().onConnectionClosed(
                    RpcConnectionRole.SERVER,
                    remote,
                    closeReason,
                    closeError);
        }
    }

    private void startTlsReload() {
        if (!options.security().enabled()) {
            return;
        }
        TlsReloadController<VertxTlsSupport.FileState> controller =
                new TlsReloadController<>(
                        vertx.getOrCreateContext(),
                        initialTlsFileState,
                        () -> VertxTlsSupport.fileState(
                                options.security()),
                        ignored -> VertxTlsSupport.reloadOptions(
                                options.security(),
                                false,
                                options.observer()),
                        sslOptions -> server.updateSSLOptions(
                                sslOptions,
                                true),
                        this::observeCertificateReload);
        tlsReloadController = controller;
        tlsReloadTimerId = vertx.setPeriodic(
                options.security().reloadInterval().toMillis(),
                ignored -> controller.requestReload());
        // 首次校验同样必须在控制面执行器中执行，不在 EventLoop 读取文件。
        controller.requestReload();
    }

    private void observeCertificateReload(
            long startedAtNanos,
            Throwable error) {
        if (!options.observer().enabled()) {
            return;
        }
        options.observer()
                .onCertificateReloadCompleted(
                        options.security().mode(),
                        error == null
                                ? RpcCertificateReloadOutcome.SUCCESS
                                : RpcCertificateReloadOutcome.FAILURE,
                        System.nanoTime() - startedAtNanos,
                        error);
    }

    private void cancelTlsReload() {
        if (tlsReloadTimerId >= 0L) {
            vertx.cancelTimer(tlsReloadTimerId);
            tlsReloadTimerId = -1L;
        }
        TlsReloadController<VertxTlsSupport.FileState> controller =
                tlsReloadController;
        tlsReloadController = null;
        if (controller != null) {
            controller.close();
        }
    }

    @Override
    public CompletionStage<Void> drain(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "drain timeout must be positive"));
        }
        CompletableFuture<Void> current = drainFuture;
        if (current != null) {
            return current;
        }
        CompletableFuture<Void> created = new CompletableFuture<>();
        drainFuture = created;
        draining.set(true);
        connections.forEach(ServerConnection::beginDrain);
        drainTimerId = vertx.setTimer(
                timeout.toMillis(),
                ignored -> forceDrainTimeout());
        checkDrainComplete();
        return created;
    }

    private void checkDrainComplete() {
        if (draining.get() && connections.isEmpty()) {
            finishDrain();
        }
    }

    private void forceDrainTimeout() {
        connections.forEach(connection -> connection.socket.close());
        finishDrain();
    }

    private void finishDrain() {
        if (!closingServer.compareAndSet(false, true)) {
            return;
        }
        if (drainTimerId >= 0L) {
            vertx.cancelTimer(drainTimerId);
            drainTimerId = -1L;
        }
        cancelTlsReload();
        server.close().onComplete(result -> {
            CompletableFuture<Void> future = drainFuture;
            if (future == null) {
                return;
            }
            if (result.failed()) {
                future.completeExceptionally(result.cause());
            } else {
                future.complete(null);
            }
        });
    }

    /** 供受控网络测试观察连接租约数量。 */
    int admittedConnections() {
        return admission.activeConnections();
    }

    /** 供受控网络测试观察协议握手租约数量。 */
    int admittedHandshakes() {
        return admission.activeHandshakes();
    }

    /** 供受控网络测试观察当前不完整帧实际占用的逻辑字节。 */
    long bufferedPartialBytes() {
        return admission.bufferedPartialBytes();
    }

    /** 供受控网络测试观察当前仍持有的编码 RESPONSE 字节。 */
    long bufferedResponseBytes() {
        return responseBudget.reservedBytes();
    }

    @Override
    public void close() {
        draining.set(true);
        cancelTlsReload();
        connections.forEach(ServerConnection::closeLocally);
        server.close();
        vertx.close();
    }
}
