package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.protocol.RpcFrame;
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
 * 通用协议编码与 Unary 快路径编码基准。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 15:46
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ProtocolEncodeBenchmark {

    private static final long DEADLINE = 2_000_000_000_123L;

    /** Payload 字节数。 */
    @Param({"64", "256", "1024", "16384", "1048576"})
    public int payloadSize;

    private byte[] payload;
    private byte[] budgetRequest;

    /** 创建协议编码基准。 */
    public ProtocolEncodeBenchmark() {
    }

    /** 按参数准备 Payload。 */
    @Setup
    public void setup() {
        payload = new byte[payloadSize];
        budgetRequest = RpcProtocolCodec.encodeRequest(
                RpcCodecIds.FORY_NATIVE,
                100,
                200,
                DEADLINE,
                1500L,
                payload);
    }

    /**
     * 测量通用 RpcFrame + Metadata Map 编码。
     *
     * @return 完整帧
     */
    @Benchmark
    public byte[] genericRequestEncode() {
        return RpcProtocolCodec.encode(new RpcFrame(
                RpcMessageType.REQUEST,
                RpcCodecIds.FORY_NATIVE,
                RpcStatus.OK,
                0L,
                100,
                200,
                Map.of(
                        ProtocolCodecBenchmark.DEADLINE_EPOCH_MILLIS_KEY,
                        Long.toString(DEADLINE)),
                payload));
    }

    /**
     * 测量发送前相对 Timeout Budget 原地刷新。
     *
     * @return 是否找到并刷新 Budget
     */
    @Benchmark
    public boolean rewriteTimeoutBudgetFastPath() {
        return RpcProtocolCodec.rewriteTimeoutBudgetMillis(
                budgetRequest,
                25L);
    }

    /**
     * 测量 Unary REQUEST 专用编码。
     *
     * @return 完整帧
     */
    @Benchmark
    public byte[] unaryRequestFastEncode() {
        return RpcProtocolCodec.encodeRequest(
                RpcCodecIds.FORY_NATIVE,
                100,
                200,
                DEADLINE,
                payload);
    }
}
