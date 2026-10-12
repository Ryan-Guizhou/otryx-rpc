package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.protocol.RpcFrame;
import com.peachsoft.otryx.protocol.RpcFrameView;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * 完整 Frame decode 与零 body-copy Frame View 的协议解析基准。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 15:46
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ProtocolDecodeBenchmark {

    /** Payload 字节数。 */
    @Param({"64", "256", "1024", "16384", "1048576"})
    public int payloadSize;

    private byte[] encoded;

    /** 创建协议解析基准。 */
    public ProtocolDecodeBenchmark() {
    }

    /** 按参数准备固定测试帧。 */
    @Setup
    public void setup() {
        encoded = RpcProtocolCodec.encode(new RpcFrame(
                RpcMessageType.REQUEST,
                (byte) 1,
                RpcStatus.OK,
                42L,
                100,
                200,
                Map.of(
                        ProtocolCodecBenchmark.DEADLINE_EPOCH_MILLIS_KEY,
                        "2000000000000"),
                new byte[payloadSize]));
    }

    /**
     * 测量兼容完整解码路径。
     *
     * @return 消费字段，防止 JIT 消除
     */
    @Benchmark
    public long fullDecode() {
        RpcFrame frame = RpcProtocolCodec.decode(encoded);
        return frame.requestId()
                + frame.payload().length
                + Long.parseLong(
                        frame.metadata().get(
                                ProtocolCodecBenchmark.DEADLINE_EPOCH_MILLIS_KEY));
    }

    /**
     * 测量只读取 Request ID 的固定 Header 快路径。
     *
     * @return Request ID
     */
    @Benchmark
    public long requestIdHeaderFastPath() {
        return RpcProtocolCodec.readRequestId(encoded);
    }

    /**
     * 测量零 body-copy view 路径。
     *
     * @return 消费字段，防止 JIT 消除
     */
    @Benchmark
    public long frameView() {
        RpcFrameView frame = RpcProtocolCodec.view(encoded);
        return frame.requestId()
                + frame.payloadLength()
                + frame.deadlineEpochMillis();
    }
}
