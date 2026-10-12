package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.protocol.RpcFrame;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.protocol.RpcProtocolException;
import com.peachsoft.otryx.transport.RpcTransportAdmissionOptions;
import io.vertx.core.buffer.Buffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 验证帧累积器的边界、拼装及异常输入处理。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
class FrameAccumulatorTest {

    @Test
    void shouldAcceptMultipleFramesWhoseCombinedReadExceedsSingleFrameLimit() {
        byte[] first = frame(1L, new byte[] {1});
        byte[] second = frame(2L, new byte[] {2});
        int maxFrameBytes = Math.max(first.length, second.length);
        FrameAccumulator accumulator = new FrameAccumulator(maxFrameBytes);
        List<byte[]> decoded = new ArrayList<>();

        accumulator.accept(Buffer.buffer().appendBytes(first).appendBytes(second), decoded::add);

        assertEquals(2, decoded.size());
        assertArrayEquals(first, decoded.get(0));
        assertArrayEquals(second, decoded.get(1));
    }

    @Test
    void shouldReassembleFragmentedHeaderAndPayload() {
        byte[] frame = frame(
                21L,
                new byte[1024]);
        FrameAccumulator accumulator =
                new FrameAccumulator(frame.length);
        List<byte[]> decoded =
                new ArrayList<>();

        int split =
                RpcProtocolCodec.HEADER_LENGTH / 2;
        accumulator.accept(
                Buffer.buffer(
                        java.util.Arrays.copyOfRange(
                                frame,
                                0,
                                split)),
                decoded::add);
        assertEquals(0, decoded.size());

        accumulator.accept(
                Buffer.buffer(
                        java.util.Arrays.copyOfRange(
                                frame,
                                split,
                                frame.length)),
                decoded::add);

        assertEquals(1, decoded.size());
        assertArrayEquals(
                frame,
                decoded.get(0));
    }

    @Test
    void shouldRemainWritableAfterExactlyConsumedFrame() {
        byte[] first = frame(11L, new byte[] {1, 2});
        byte[] second = frame(12L, new byte[] {3, 4});
        int maxFrameBytes = Math.max(first.length, second.length);
        FrameAccumulator accumulator =
                new FrameAccumulator(maxFrameBytes);
        List<byte[]> decoded = new ArrayList<>();

        accumulator.accept(Buffer.buffer(first), decoded::add);
        accumulator.accept(Buffer.buffer(second), decoded::add);

        assertEquals(2, decoded.size());
        assertArrayEquals(first, decoded.get(0));
        assertArrayEquals(second, decoded.get(1));
    }

    @Test
    void shouldReassembleDeterministicRandomFragmentation() {
        Random random = new Random(20260930L);
        for (int iteration = 0;
                iteration < 100;
                iteration++) {
            byte[] expected = frame(
                    iteration + 1L,
                    new byte[1 + random.nextInt(4096)]);
            FrameAccumulator accumulator =
                    new FrameAccumulator(expected.length);
            List<byte[]> decoded = new ArrayList<>();

            int offset = 0;
            while (offset < expected.length) {
                int remaining = expected.length - offset;
                int length = Math.min(
                        remaining,
                        1 + random.nextInt(
                                Math.min(remaining, 97)));
                accumulator.accept(
                        Buffer.buffer(
                                java.util.Arrays.copyOfRange(
                                        expected,
                                        offset,
                                        offset + length)),
                        decoded::add);
                offset += length;
            }

            assertEquals(1, decoded.size());
            assertArrayEquals(
                    expected,
                    decoded.get(0));
        }
    }

    @Test
    void shouldReassembleRandomCoalescingAndFragmentation() {
        Random random = new Random(20261001L);
        List<byte[]> expected = new ArrayList<>();
        Buffer stream = Buffer.buffer();
        for (int index = 0; index < 20; index++) {
            byte[] value = frame(
                    index + 1L,
                    new byte[random.nextInt(256)]);
            expected.add(value);
            stream.appendBytes(value);
        }

        FrameAccumulator accumulator =
                new FrameAccumulator(1024);
        List<byte[]> decoded = new ArrayList<>();
        int offset = 0;
        while (offset < stream.length()) {
            int remaining = stream.length() - offset;
            int length = Math.min(
                    remaining,
                    1 + random.nextInt(
                            Math.min(remaining, 131)));
            accumulator.accept(
                    stream.getBuffer(
                            offset,
                            offset + length),
                    decoded::add);
            offset += length;
        }

        assertEquals(expected.size(), decoded.size());
        for (int index = 0;
                index < expected.size();
                index++) {
            assertArrayEquals(
                    expected.get(index),
                    decoded.get(index));
        }
    }

    @Test
    void completeFrameFastPathMustNotRetainCallerBuffer() {
        byte[] frame = frame(71L, new byte[] {4, 3, 2, 1});
        Buffer input = Buffer.buffer(frame.clone());
        List<byte[]> received = new ArrayList<>();
        FrameAccumulator accumulator = new FrameAccumulator(frame.length);

        accumulator.accept(input, received::add);
        assertEquals(1, received.size());
        assertArrayEquals(frame, received.get(0));

        // 收到的帧拥有独立 byte[]，之后可安全复用原有 Netty Buffer。
        input.setByte(RpcProtocolCodec.HEADER_LENGTH, (byte) 0);
        assertArrayEquals(frame, received.get(0));

        byte[] next = frame(72L, new byte[] {9});
        accumulator.accept(Buffer.buffer(next), received::add);
        assertEquals(2, received.size());
        assertArrayEquals(next, received.get(1));
    }

    @Test
    void fastPathShouldRetainOnlyIncompleteCoalescedTail() {
        byte[] first = frame(80L, new byte[] {1, 2});
        byte[] second = frame(81L, new byte[] {3, 4});
        byte[] third = frame(82L, new byte[120]);
        byte[] fourth = frame(83L, new byte[] {5});

        int split = RpcProtocolCodec.HEADER_LENGTH - 3;
        Buffer coalesced = Buffer.buffer()
                .appendBytes(first)
                .appendBytes(second)
                .appendBytes(java.util.Arrays.copyOfRange(
                        third, 0, split));
        FrameAccumulator accumulator = new FrameAccumulator(third.length);
        List<byte[]> decoded = new ArrayList<>();

        accumulator.accept(coalesced, decoded::add);
        assertEquals(2, decoded.size());
        assertArrayEquals(first, decoded.get(0));
        assertArrayEquals(second, decoded.get(1));

        accumulator.accept(
                Buffer.buffer(java.util.Arrays.copyOfRange(
                        third, split, third.length)),
                decoded::add);
        assertEquals(3, decoded.size());
        assertArrayEquals(third, decoded.get(2));

        accumulator.accept(Buffer.buffer(fourth), decoded::add);
        assertEquals(4, decoded.size());
        assertArrayEquals(fourth, decoded.get(3));
    }

    @Test
    void fragmentedFirstChunkMustNotAliasReusedInboundBuffer() {
        byte[] expected = frame(86L, new byte[256]);
        int split = RpcProtocolCodec.HEADER_LENGTH / 2;
        Buffer initial = Buffer.buffer(
                java.util.Arrays.copyOfRange(expected, 0, split));
        FrameAccumulator accumulator = new FrameAccumulator(expected.length);
        List<byte[]> received = new ArrayList<>();

        accumulator.accept(initial, received::add);
        initial.setByte(0, (byte) 0x00);

        accumulator.accept(
                Buffer.buffer(java.util.Arrays.copyOfRange(
                        expected, split, expected.length)),
                received::add);

        assertEquals(1, received.size());
        assertArrayEquals(expected, received.get(0));
    }

    @Test
    void fragmentedOversizedHeaderMustFailBeforeReceivingPayload() {
        byte[] bigFrame = frame(84L, new byte[128]);
        FrameAccumulator accumulator = new FrameAccumulator(
                RpcProtocolCodec.HEADER_LENGTH + 64);
        List<byte[]> received = new ArrayList<>();
        int split = RpcProtocolCodec.HEADER_LENGTH / 2;
        accumulator.accept(
                Buffer.buffer(java.util.Arrays.copyOfRange(
                        bigFrame, 0, split)),
                received::add);

        assertThrows(
                RpcProtocolException.class,
                () -> accumulator.accept(
                        Buffer.buffer(java.util.Arrays.copyOfRange(
                                bigFrame, split, RpcProtocolCodec.HEADER_LENGTH)),
                        received::add));
        assertEquals(0, received.size());
    }

    @Test
    void exactFrameLimitShouldBeAllowedAndOversizedFrameRejected() {
        byte[] valid = frame(85L, new byte[32]);
        List<byte[]> output = new ArrayList<>();
        new FrameAccumulator(valid.length)
                .accept(Buffer.buffer(valid), output::add);
        assertEquals(1, output.size());
        assertArrayEquals(valid, output.get(0));

        assertThrows(
                RpcProtocolException.class,
                () -> new FrameAccumulator(valid.length - 1)
                        .accept(Buffer.buffer(valid), ignored -> { }));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameAccumulator(
                        RpcProtocolCodec.HEADER_LENGTH - 1));
    }

    @Test
    void malformedHeaderMustNotBeEmitted() {
        FrameAccumulator accumulator = new FrameAccumulator(1024);
        List<byte[]> received = new ArrayList<>();
        assertThrows(
                RpcProtocolException.class,
                () -> accumulator.accept(
                        Buffer.buffer(new byte[RpcProtocolCodec.HEADER_LENGTH]),
                        received::add));
        assertEquals(0, received.size());
    }

    @Test
    void partialFrameBudgetMustReleaseOnDisconnect() {
        TransportAdmissionController budget = new TransportAdmissionController(
                new RpcTransportAdmissionOptions(
                        2, 1, 16L, Duration.ofSeconds(1)));
        FrameAccumulator first = new FrameAccumulator(512, budget);
        FrameAccumulator second = new FrameAccumulator(512, budget);

        first.accept(Buffer.buffer(new byte[12]), ignored -> { });
        assertEquals(12L, budget.bufferedPartialBytes());
        assertThrows(RpcProtocolException.class,
                () -> second.accept(
                        Buffer.buffer(new byte[8]), ignored -> { }));
        assertEquals(12L, budget.bufferedPartialBytes());

        first.clear();
        first.clear();
        assertEquals(0L, budget.bufferedPartialBytes());
        second.accept(Buffer.buffer(new byte[8]), ignored -> { });
        assertEquals(8L, budget.bufferedPartialBytes());
        second.clear();
        assertEquals(0L, budget.bufferedPartialBytes());
    }

    @Test
    void completeFrameFastPathMustNotChargeGlobalPartialBudget() {
        TransportAdmissionController budget = new TransportAdmissionController(
                new RpcTransportAdmissionOptions(
                        2, 1, 4L, Duration.ofSeconds(1)));
        FrameAccumulator accumulator = new FrameAccumulator(512, budget);
        List<byte[]> emitted = new ArrayList<>();
        byte[] full = frame(91L, new byte[] {1, 2, 3});

        accumulator.accept(Buffer.buffer(full), emitted::add);

        assertEquals(1, emitted.size());
        assertArrayEquals(full, emitted.get(0));
        assertEquals(0L, budget.bufferedPartialBytes());
        accumulator.clear();
    }

    @Test
    void completedFragmentMustReleaseEveryReservedByte() {
        byte[] full = frame(92L, new byte[128]);
        TransportAdmissionController budget = new TransportAdmissionController(
                new RpcTransportAdmissionOptions(
                        2, 1, full.length, Duration.ofSeconds(1)));
        FrameAccumulator accumulator = new FrameAccumulator(full.length, budget);
        List<byte[]> emitted = new ArrayList<>();
        accumulator.accept(Buffer.buffer(
                        java.util.Arrays.copyOfRange(full, 0, 14)),
                emitted::add);
        assertEquals(14L, budget.bufferedPartialBytes());
        accumulator.accept(Buffer.buffer(
                        java.util.Arrays.copyOfRange(full, 14, full.length)),
                emitted::add);

        assertEquals(1, emitted.size());
        assertArrayEquals(full, emitted.get(0));
        assertEquals(0L, budget.bufferedPartialBytes());
    }

    private static byte[] frame(long requestId, byte[] payload) {
        return RpcProtocolCodec.encode(new RpcFrame(
                RpcMessageType.RESPONSE,
                (byte) 1,
                RpcStatus.OK,
                requestId,
                10,
                20,
                Map.of(),
                payload));
    }
}
