package com.peachsoft.otryx.transport.vertx;

import com.peachsoft.otryx.transport.RpcTransportAdmissionOptions;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vert.x Provider 多 EventLoop 共享的连接与协议握手准入控制器。
 *
 * <p>只使用有限次 CAS，无阻塞、无队列与任何文件 IO。
 * Lease 在所有权转交 NetSocket 后，由对应连接完成或关闭时释放；
 * 任何超时/错误/并发关闭都不会重复归还额度。
 *
 * <p>TLS 握手发生在 NetSocket 回调之前，本控制器并非 TLS
 * 引擎或操作系统 Socket 接入层的全局安全上限。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 16:54
 */
final class TransportAdmissionController {

    private final RpcTransportAdmissionOptions options;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger handshakes = new AtomicInteger();

    /** 以进程内 Transport 实例级额度创建控制器。 */
    TransportAdmissionController(RpcTransportAdmissionOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** 尝试预留已接收连接的所有权；耗尽时立即返回 null。 */
    Lease tryAcquireConnection() {
        return tryAcquire(connections, options.maxConnections());
    }

    /** 尝试预留正在进行的 OTRYX HELLO 握手；耗尽时立即返回 null。 */
    Lease tryAcquireHandshake() {
        return tryAcquire(handshakes, options.maxHandshakes());
    }

    /** 返回连接数，供包内的受控测试核验。 */
    int activeConnections() {
        return connections.get();
    }

    /** 返回尚未完成的协议握手数，供包内测试核验。 */
    int activeHandshakes() {
        return handshakes.get();
    }

    private static Lease tryAcquire(
            AtomicInteger counter,
            int limit) {
        for (;;) {
            int current = counter.get();
            if (current >= limit) {
                return null;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return new Lease(counter);
            }
        }
    }

    /**
     * 一次性配额租约；close() 不会关闭实际 Socket。
     */
    static final class Lease implements AutoCloseable {
        private final AtomicInteger counter;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(AtomicInteger counter) {
            this.counter = counter;
        }

        /** 幂等归还原始计数器。 */
        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                counter.decrementAndGet();
            }
        }
    }
}
