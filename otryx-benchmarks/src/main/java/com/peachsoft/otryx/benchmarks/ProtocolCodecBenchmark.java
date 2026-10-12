package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.protocol.RpcFrame;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * 协议编解码微基准。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@State(Scope.Thread)
public class ProtocolCodecBenchmark {

    /** 基准测试元数据中的请求截止时间键。 */
    static final String DEADLINE_EPOCH_MILLIS_KEY = "deadlineEpochMillis";
    private RpcFrame frame;
    private byte[] encoded;

    /**
     * 为当前 JMH Trial 构造固定的请求帧和可复用解码输入。
     *
     * <p>初始化开销不计入基准吞吐量；请求负载固定为 256B，
     * Metadata 和 MessageId 不随迭代变化。
     */
    @Setup(Level.Trial)
    public void setup() {
        frame = new RpcFrame(
                RpcMessageType.REQUEST,
                (byte) 1,
                RpcStatus.OK,
                1L,
                1001,
                2001,
                Map.of(DEADLINE_EPOCH_MILLIS_KEY, "1800000000000"),
                new byte[256]);
        encoded = RpcProtocolCodec.encode(frame);
    }

    /**
     * 测量将相同的 RPC 请求帧编码为 Wire v1 字节数组的吞吐量。
     *
     * @return 每次编码生成的独立二进制帧
     */
    @Benchmark
    public byte[] encode() {
        return RpcProtocolCodec.encode(frame);
    }

    /**
     * 测量从预先编码的 Wire v1 字节数组解码请求帧的吞吐量。
     *
     * <p>不包含传输、注册中心调用以及预先执行的编码开销。
     *
     * @return 解码得到的 RPC 帧
     */
    @Benchmark
    public RpcFrame decode() {
        return RpcProtocolCodec.decode(encoded);
    }
}
