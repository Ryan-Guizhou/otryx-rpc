package com.peachsoft.otryx.transport;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RPC 已编码响应在框架所有权范围内等待写出的字节预算。
 *
 * <p>每个 Transport Server 可拥有独立实例。预算只统计已编码且持有
 * 本类 Lease 的响应字节，不覆盖业务对象图、Codec 编码期间的分配
 * 峰值、Socket/Netty 队列、TLS 引擎缓存或远端尚未读取的字节。
 *
 * <p>预算耗尽立即拒绝，不会在 Vert.x EventLoop 或工作线程阻塞等待
 * 可用容量。归还通过独占 Lease 实现，重复关闭不会多次释放。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 18:25
 */
public final class ResponseByteBudget {

    private final long capacityBytes;
    private final AtomicLong reservedBytes = new AtomicLong();

    /**
     * 创建具有固定字节上限的响应预算。
     *
     * @param capacityBytes 最大可同时保留的编码响应字节数，必须为正
     */
    public ResponseByteBudget(long capacityBytes) {
        if (capacityBytes <= 0L) {
            throw new IllegalArgumentException(
                    "Response byte capacity must be positive");
        }
        this.capacityBytes = capacityBytes;
    }

    /**
     * 在响应被框架保留/写入前尝试获取独占预算。
     *
     * <p>当容量不足时不排队，直接返回 null。无效的负数或零字节申请
     * 抛出参数异常；合法的协议 RESPONSE 必须至少包含 Header。
     *
     * @param bytes 本轮要预留的完整编码响应字节数
     * @return 已预留的独占 Lease，超出容量时返回 null
     */
    public Lease tryAcquire(long bytes) {
        if (bytes <= 0L) {
            throw new IllegalArgumentException(
                    "Response reservation bytes must be positive");
        }
        if (bytes > capacityBytes) {
            return null;
        }
        for (;;) {
            long current = reservedBytes.get();
            if (bytes > capacityBytes - current) {
                return null;
            }
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                return new Lease(this, bytes);
            }
        }
    }

    /**
     * 返回固定预算上限。
     *
     * @return 配置的最大响应保留字节数
     */
    public long capacityBytes() {
        return capacityBytes;
    }

    /**
     * 返回目前仍持有 Lease 的响应字节数。
     *
     * @return 当前被预留的响应字节数
     */
    public long reservedBytes() {
        return reservedBytes.get();
    }

    private void release(long bytes) {
        for (;;) {
            long current = reservedBytes.get();
            if (current < bytes) {
                throw new IllegalStateException(
                        "Response byte reservation released too often");
            }
            if (reservedBytes.compareAndSet(current, current - bytes)) {
                return;
            }
        }
    }

    /**
     * 绑定一次响应字节预留的所有权令牌。
     *
     * <p>Transport 在写入完成、失败、取消或连接关闭时释放。
     * Future/线程中断不会自动调用本 Lease.close()，必须由其资源
     * 所有者显式管理；Lease 本身不强制停止任何用户业务。
     */
    public static final class Lease implements AutoCloseable {

        private final ResponseByteBudget owner;
        private final long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(ResponseByteBudget owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        /**
         * 返回该 Lease 实际持有的字节。
         *
         * @return 预留的字节数
         */
        public long bytes() {
            return bytes;
        }

        /**
         * 恰好一次地归还所持有的响应字节预算。
         *
         * @return 首次成功归还时为 true；再次调用时为 false
         */
        public boolean release() {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            owner.release(bytes);
            return true;
        }

        /**
         * 幂等归还本次预留额度。
         */
        @Override
        public void close() {
            release();
        }
    }
}
