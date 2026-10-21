package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 验证响应编码输出容量在扩容前获取，所有失败与重复关闭均能回收实际字节。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 15:05
 */
class RpcResponseEncodingBudgetTest {

    @Test
    void buffersMustReserveBeforeGrowingAndReleaseAfterSuccessfulEncoding() throws Exception {
        RpcResponseEncodingBudget budget = new RpcResponseEncodingBudget(32, 32);
        byte[] payload = new byte[28];
        Arrays.fill(payload, (byte) 7);
        try (RpcResponseEncodingBudget.Buffer first = budget.open(32)) {
            first.write(payload);
            assertEquals(32L, budget.reservedBytes());
            assertArrayEquals(payload, first.toByteArray());
            try (RpcResponseEncodingBudget.Buffer rejected = budget.open(32)) {
                assertThrows(RpcResponseEncodingBudget.CapacityExceededException.class,
                        () -> rejected.write(1));
            }
            assertEquals(32L, budget.reservedBytes());
        }
        assertEquals(0L, budget.reservedBytes());
        assertEquals(32L, budget.highWaterBytes());
        try (RpcResponseEncodingBudget.Buffer next = budget.open(32)) {
            next.write(1);
            assertEquals(1, next.toByteArray().length);
        }
        assertEquals(0L, budget.reservedBytes());
    }

    @Test
    void finalFrameMustBePreReservedAndMustNotMakeAnotherIntermediatePayloadCopy() throws Exception {
        RpcResponseEncodingBudget rejected = new RpcResponseEncodingBudget(80L, 64);
        try (RpcResponseEncodingBudget.Buffer output = rejected.open(64)) {
            output.write(new byte[8]);
            assertEquals(64L, rejected.reservedBytes());
            assertThrows(RpcResponseEncodingBudget.CapacityExceededException.class,
                    () -> output.toResponseFrame((byte) 1,
                            com.peachsoft.otryx.api.RpcStatus.OK, 5L, 8, 9));
            assertEquals(64L, rejected.reservedBytes());
        }
        assertEquals(0L, rejected.reservedBytes());

        RpcResponseEncodingBudget allowed = new RpcResponseEncodingBudget(128L, 64);
        byte[] expected = com.peachsoft.otryx.protocol.RpcProtocolCodec.encodeResponse(
                (byte) 1, com.peachsoft.otryx.api.RpcStatus.OK, 5L, 8, 9, new byte[8]);
        try (RpcResponseEncodingBudget.Buffer output = allowed.open(64)) {
            output.write(new byte[8]);
            assertArrayEquals(expected, output.toResponseFrame(
                    (byte) 1, com.peachsoft.otryx.api.RpcStatus.OK, 5L, 8, 9));
            assertEquals(64L, allowed.reservedBytes(), "Transient Frame must release its own allocation lease");
            assertEquals(104L, allowed.highWaterBytes(), "Both encoding array and Wire Frame must be measured");
        }
        assertEquals(0L, allowed.reservedBytes());
    }

    @Test
    void codecAndGlobalLimitsMustFailClosedWithoutDoubleRelease() throws Exception {
        RpcResponseEncodingBudget budget = new RpcResponseEncodingBudget(1024, 512);
        RpcResponseEncodingBudget.Buffer sink = budget.open(16);
        sink.write(new byte[16]);
        assertEquals(16L, budget.reservedBytes());
        var error = assertThrows(RpcResponseEncodingBudget.CapacityExceededException.class,
                () -> sink.write(new byte[1]));
        assertTrue(RpcResponseEncodingBudget.isCapacityExceeded(
                new IllegalStateException("wrapped", error)));
        sink.close();
        sink.close();
        assertEquals(0L, budget.reservedBytes());
        assertThrows(IllegalStateException.class, () -> sink.write(2));
        assertThrows(IllegalArgumentException.class, () -> new RpcResponseEncodingBudget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RpcResponseEncodingBudget(1, 2));
    }

    @Test
    void growingBufferMustReserveBothOldAndNewStorageBeforeAllocating() throws Exception {
        RpcResponseEncodingBudget budget = new RpcResponseEncodingBudget(850, 768);
        try (RpcResponseEncodingBudget.Buffer buffer = budget.open(768)) {
            buffer.write(new byte[256]);
            assertEquals(256L, budget.reservedBytes());
            buffer.write(1);
            assertEquals(512L, budget.reservedBytes());
            assertEquals(768L, budget.highWaterBytes(),
                    "Peak must include both arrays while the old buffer is copied");
            assertThrows(RpcResponseEncodingBudget.CapacityExceededException.class,
                    () -> buffer.write(new byte[400]));
            assertEquals(512L, budget.reservedBytes(),
                    "Rejected growth must preserve ownership of the existing buffer");
        }
        assertEquals(0L, budget.reservedBytes());
    }

    @Test
    void growthMustStayInsideGlobalBudgetAfterAllocationFailure() throws Exception {
        RpcResponseEncodingBudget budget = new RpcResponseEncodingBudget(512, 512);
        try (RpcResponseEncodingBudget.Buffer first = budget.open(512)) {
            first.write(new byte[128]);
            assertEquals(256L, budget.reservedBytes());
            try (RpcResponseEncodingBudget.Buffer second = budget.open(512)) {
                second.write(new byte[128]);
                assertEquals(512L, budget.reservedBytes());
                assertThrows(RpcResponseEncodingBudget.CapacityExceededException.class,
                        () -> second.write(new byte[256]));
            }
            assertEquals(256L, budget.reservedBytes());
        }
        assertEquals(0L, budget.reservedBytes());
        assertEquals(512L, budget.highWaterBytes());
    }
}
