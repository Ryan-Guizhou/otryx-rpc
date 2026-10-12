package com.peachsoft.otryx.transport.vertx;

import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.protocol.RpcProtocolException;
import io.vertx.core.buffer.Buffer;
import java.util.function.Consumer;

/**
 * TCP 字节流帧重组器，仅在 Vert.x Adapter 的 connection EventLoop 内调用。
 *
 * <p>当尚无不完整尾帧时，直接从当前入站 Buffer 扫描并复制每个完整帧，
 * 省去先 append 到中间累积 Buffer 的冗余内存拷贝。交付给 RPC Core 的
 * 完整帧仍是独立 byte[]，避免持有可复用的 Transport Buffer。
 *
 * <p>只有 TCP 分片或粘包尾部不完整时才将未消费字节复制到 pending。
 * 固定 32B Header 仍复用数组；已发生分片时沿用原来的追加和压缩策略，
 * 不修改 Wire v1 帧格式，也不改变最大帧大小校验。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
final class FrameAccumulator {

    private final int maxFrameBytes;
    private final TransportAdmissionController admission;
    private long chargedBytes;
    private final byte[] header =
            new byte[RpcProtocolCodec.HEADER_LENGTH];
    private Buffer pending = Buffer.buffer();

    /**
     * 创建每条连接的 TCP Frame Accumulator。
     *
     * @param maxFrameBytes 允许的单个完整 Frame 最大字节数
     */
    FrameAccumulator(int maxFrameBytes) {
        this(maxFrameBytes, null);
    }

    /**
     * 创建有全局部分帧预算约束的累积器。
     *
     * @param maxFrameBytes 单帧逻辑上限
     * @param admission 本 Transport Server 共用的预算控制器；null 用于无预算测试
     */
    FrameAccumulator(
            int maxFrameBytes,
            TransportAdmissionController admission) {
        if (maxFrameBytes < RpcProtocolCodec.HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "maxFrameBytes cannot be smaller than a Wire v1 header");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.admission = admission;
    }

    /**
     * 消费 TCP 输入并对实际缓冲的不完整帧预留字节预算。
     *
     * <p>完整帧的常见路径不触碰全局预算。已有残帧时先为新数据
     * 预留额度，随后在消费完整帧后归还。解析错误会立即清空状态，
     * 防止等待 Socket.closeHandler 才回收缓冲额度。
     *
     * @param incoming 当前 Transport 入站 Buffer
     * @param consumer 完整帧接收方
     * @return 本次是否交付过至少一个完整帧
     */
    boolean accept(
            Buffer incoming,
            Consumer<byte[]> consumer) {
        try {
            if (pending.length() == 0) {
                // 完整帧快速路径不需全局字节计数或中间拷贝。
                int consumed = consumeFrames(incoming, consumer);
                if (consumed == 0 && incoming.length() != 0) {
                    if (incoming.length() > maxFrameBytes) {
                        throw new RpcProtocolException(
                                "Buffered partial frame exceeds transport maxFrameBytes");
                    }
                    reserveBytes(incoming.length());
                    pending.appendBuffer(incoming);
                    return false;
                }
                retainIncompleteTail(incoming, consumed);
                return consumed > 0;
            }

            // 有残帧时，在内存追加之前预留额外字节额度。
            reserveBytes(incoming.length());
            pending.appendBuffer(incoming);
            int consumed = consumeFrames(pending, consumer);
            compact(consumed);
            releaseBytes(consumed);
            if (pending.length() > maxFrameBytes) {
                throw new RpcProtocolException(
                        "Buffered partial frame exceeds transport maxFrameBytes");
            }
            return consumed > 0;
        } catch (RuntimeException error) {
            clear();
            throw error;
        }
    }

    /** 返回当前真正保留的不完整字节数。 */
    int bufferedBytes() {
        return pending.length();
    }

    /**
     * 丢弃不完整帧并归还所有尚未释放的全局额度，重复调用安全。
     */
    void clear() {
        pending = Buffer.buffer();
        releaseBytes(chargedBytes);
    }

    private void reserveBytes(long bytes) {
        if (admission == null || bytes == 0L) {
            return;
        }
        if (!admission.tryReservePartialBytes(bytes)) {
            throw new RpcProtocolException(
                    "Global partial-frame buffer budget exceeded");
        }
        chargedBytes += bytes;
    }

    private void releaseBytes(long bytes) {
        if (admission == null || bytes == 0L) {
            return;
        }
        if (bytes < 0L || bytes > chargedBytes) {
            throw new IllegalStateException(
                    "Frame buffer released beyond its reserved quota");
        }
        chargedBytes -= bytes;
        admission.releasePartialBytes(bytes);
    }

    private int consumeFrames(
            Buffer source,
            Consumer<byte[]> consumer) {
        int readOffset = 0;
        while (source.length() - readOffset
                >= RpcProtocolCodec.HEADER_LENGTH) {
            int headerEnd =
                    readOffset + RpcProtocolCodec.HEADER_LENGTH;
            source.getBytes(
                    readOffset,
                    headerEnd,
                    header,
                    0);
            int frameLength =
                    RpcProtocolCodec.expectedFrameLength(header);
            if (frameLength > maxFrameBytes) {
                throw new RpcProtocolException(
                        "RPC frame exceeds transport maxFrameBytes");
            }
            if (source.length() - readOffset < frameLength) {
                break;
            }

            int frameEnd = readOffset + frameLength;
            // RPC Core 仍获得独立 byte[]，不暴露 Netty Buffer 所有权。
            consumer.accept(source.getBytes(
                    readOffset,
                    frameEnd));
            readOffset = frameEnd;
        }
        return readOffset;
    }

    private void retainIncompleteTail(
            Buffer source,
            int consumed) {
        int remaining = source.length() - consumed;
        if (remaining == 0) {
            return;
        }
        if (remaining > maxFrameBytes) {
            throw new RpcProtocolException(
                    "Buffered partial frame exceeds transport maxFrameBytes");
        }
        reserveBytes(remaining);
        pending = Buffer.buffer(
                source.getBytes(consumed, source.length()));
    }

    private void compact(int readOffset) {
        if (readOffset == 0) {
            return;
        }
        if (readOffset == pending.length()) {
            pending = Buffer.buffer();
            return;
        }
        pending = Buffer.buffer(
                pending.getBytes(
                        readOffset,
                        pending.length()));
    }
}
