package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider 响应编码输出缓冲的全局递增额度，按实际保留容量而非请求数记账。
 *
 * <p>每次扩容先 CAS 预留，再分配新数组。Lease 由当前编码操作持有直到 Frame
 * 已构造完毕，任意失败、取消或异常均在 finally 中释放。扩容前需要同时预留
 * 原数组和目标新数组的占用，复制完成后才归还旧数组。构造最终 Frame 前
 * 同时预留 Frame 与当前缓冲的容量，消除另一次 Payload 中间复制。
 * 此额度仍不包含 Codec 内部对象图、Frame 返回后的存活时间或 JVM DirectMemory。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 15:02
 */
final class RpcResponseEncodingBudget {
    static final long DEFAULT_TOTAL_BYTES = 64L * 1024L * 1024L;
    static final int DEFAULT_SINGLE_BYTES = 16 * 1024 * 1024;
    private static final int INITIAL_BUFFER_BYTES = 256;
    private static final int MAX_ERROR_DEPTH = 16;

    private final long totalBytes;
    private final int singleBytes;
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong highWaterBytes = new AtomicLong();

    /** 创建必须为正值的总额度与单请求输出限制。 */
    RpcResponseEncodingBudget(long totalBytes, int singleBytes) {
        if (totalBytes <= 0L || singleBytes <= 0 || singleBytes > totalBytes) {
            throw new IllegalArgumentException("Invalid RPC response encoding limits");
        }
        this.totalBytes = totalBytes;
        this.singleBytes = singleBytes;
    }

    /** 为当前请求创建独占有界输出，动态分配前预留全局容量。 */
    Buffer open(int codecLimit) {
        int limit = codecLimit > 0 ? Math.min(singleBytes, codecLimit) : singleBytes;
        return new Buffer(limit);
    }

    /** 框架输出缓冲的当前在途容量，不计算 Codec 内部对象图。 */
    long reservedBytes() {
        return reservedBytes.get();
    }

    /** 框架输出缓冲历史最大保留容量。 */
    long highWaterBytes() {
        return highWaterBytes.get();
    }

    /** 检查包装后的编码容量拒绝，不能公开第三方 Codec 异常文本。 */
    static boolean isCapacityExceeded(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_ERROR_DEPTH; depth++) {
            if (current instanceof CapacityExceededException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private void reserve(int delta) {
        if (delta <= 0) {
            return;
        }
        for (;;) {
            long previous = reservedBytes.get();
            if (delta > totalBytes - previous) {
                throw new CapacityExceededException();
            }
            long next = previous + delta;
            if (reservedBytes.compareAndSet(previous, next)) {
                highWaterBytes.accumulateAndGet(next, Math::max);
                return;
            }
        }
    }

    private void release(int bytes) {
        if (bytes != 0L) {
            long remaining = reservedBytes.addAndGet(-bytes);
            if (remaining < 0L) {
                throw new IllegalStateException("RPC encoding capacity released twice");
            }
        }
    }

    /** 可识别且不会携带请求参数的容量拒绝类型。 */
    static final class CapacityExceededException extends IllegalStateException {
        private CapacityExceededException() {
            super("RPC response encoding capacity exhausted");
        }
    }

    /**
     * 当前编码的有界内存 OutputStream；写入与关闭必须由单个拥有者按顺序执行。
     *
     * <p>最终的帧副本在退出 try-with-resources 前创建，避免异常打断后保留全局额度。
     */
    final class Buffer extends OutputStream {
        private final int maxBytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private byte[] bytes = new byte[0];
        private int length;

        private Buffer(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) {
            ensureCapacity((long) length + 1L);
            bytes[length++] = (byte) value;
        }

        @Override
        public void write(byte[] data, int offset, int count) {
            Objects.requireNonNull(data, "data");
            Objects.checkFromIndexSize(offset, count, data.length);
            if (count == 0) {
                return;
            }
            ensureCapacity((long) length + count);
            System.arraycopy(data, offset, bytes, length, count);
            length += count;
        }

        /** 返回实际写出的有效字节副本，由调用者负责作为完整 Payload 处理。 */
        byte[] toByteArray() {
            if (closed.get()) {
                throw new IllegalStateException("RPC encoding buffer is closed");
            }
            return Arrays.copyOf(bytes, length);
        }

        /**
         * 在创建完整 Wire Frame 前同时预留当前缓冲与 Frame 所需字节。
         *
         * <p>此额度覆盖创建时的瞬时复制峰值；Frame 离开本方法后不再
         * 由编码额度持有，其在写队列阶段仍由 Transport 独立配额管理。
         *
         * @param codecId 已协商 Codec ID
         * @param status 响应状态
         * @param requestId 请求 ID
         * @param serviceId 服务 ID
         * @param methodId 方法 ID
         * @return 独立持有、仅包含一份最终 Wire Frame 的字节数组
         */
        byte[] toResponseFrame(byte codecId, RpcStatus status, long requestId,
                int serviceId, int methodId) {
            if (closed.get()) {
                throw new IllegalStateException("RPC encoding buffer is closed");
            }
            if (length > RpcProtocolCodec.MAX_BODY_LENGTH) {
                throw new CapacityExceededException();
            }
            int frameBytes = RpcProtocolCodec.HEADER_LENGTH + length;
            reserve(frameBytes);
            try {
                return RpcProtocolCodec.encodeResponse(codecId, status, requestId,
                        serviceId, methodId, bytes, 0, length);
            } finally {
                release(frameBytes);
            }
        }

        private void ensureCapacity(long required) {
            if (closed.get()) {
                throw new IllegalStateException("RPC encoding buffer is closed");
            }
            if (required > maxBytes) {
                throw new CapacityExceededException();
            }
            if (required <= bytes.length) {
                return;
            }
            int previous = bytes.length;
            int doubled = previous == 0 ? INITIAL_BUFFER_BYTES
                    : (previous > maxBytes / 2 ? maxBytes : previous * 2);
            int capacity = Math.min(maxBytes, Math.max((int) required, doubled));
            // 增长期间旧数组与新数组同时存活；先保留完整新容量，完成复制再释放旧容量。
            reserve(capacity);
            try {
                byte[] grown = Arrays.copyOf(bytes, capacity);
                bytes = grown;
            } catch (RuntimeException | Error failure) {
                // OOME 也不得泄漏本次取得但尚未转交给 Buffer 的租约。
                release(capacity);
                throw failure;
            }
            release(previous);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(bytes.length);
                bytes = new byte[0];
                length = 0;
            }
        }
    }
}
