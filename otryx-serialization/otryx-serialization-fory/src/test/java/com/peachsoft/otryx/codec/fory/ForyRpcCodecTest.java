package com.peachsoft.otryx.codec.fory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证 Fory RPC 编解码的往返一致性和异常输入。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 15:46
 */
class ForyRpcCodecTest {

    @Test
    void shouldDecodeArgumentsAndResultFromFrameSlices()
            throws Exception {
        ForyRpcCodec codec = new ForyRpcCodec();
        Method method = SampleService.class.getMethod(
                "echo",
                String.class);
        RpcMethodDescriptor descriptor = RpcMethodDescriptor.from(
                new ServiceKey(
                        SampleService.class.getName(),
                        "1.0.0",
                        "default"),
                method);
        RpcMethodCodec binding = codec.bind(descriptor);

        byte[] encodedArguments =
                binding.encodeArguments(new Object[] {"value"});
        byte[] argumentFrame = wrap(encodedArguments);
        Object[] decodedArguments = binding.decodeArguments(
                argumentFrame,
                3,
                encodedArguments.length);

        byte[] encodedResult = binding.encodeResult("result");
        byte[] resultFrame = wrap(encodedResult);
        Object decodedResult = binding.decodeResult(
                resultFrame,
                3,
                encodedResult.length);

        assertArrayEquals(
                new Object[] {"value"},
                decodedArguments);
        assertEquals("result", decodedResult);
    }

    @Test
    void zeroArgumentFastPathMustPreserveHistoricalForyBytes()
            throws Exception {
        ForyRpcCodec codec = new ForyRpcCodec();
        RpcMethodCodec binding = codec.bind(RpcMethodDescriptor.from(
                new ServiceKey(
                        SampleService.class.getName(),
                        "1.0.0",
                        "default"),
                SampleService.class.getMethod("ping")));

        byte[] historical = binding.encodeArguments(new Object[0]);
        byte[] generatedFastPath = binding.encode0();

        assertArrayEquals(historical, generatedFastPath);
        assertArrayEquals(historical, binding.encodeArguments(null));
        assertArrayEquals(
                new Object[0],
                binding.decodeArguments(generatedFastPath));
    }

    @Test
    void strictAllowlistMustAcceptExplicitApplicationContracts() throws Exception {
        ForyRpcCodec strict = new ForyRpcCodec(
                ForyRpcSecurityOptions.strictAllowlist(
                        Set.of("com.peachsoft.otryx.codec.fory.*")));
        Method method = SampleService.class.getMethod(
                "echo", String.class);
        RpcMethodCodec binding = strict.bind(RpcMethodDescriptor.from(
                new ServiceKey(
                        SampleService.class.getName(), "1.0.0", "default"),
                method));
        Object[] args = binding.decodeArguments(
                binding.encodeArguments(new Object[] {"hello"}));
        assertArrayEquals(new Object[] {"hello"}, args);
        assertEquals("ok", binding.decodeResult(
                binding.encodeResult("ok")));
    }

    @Test
    void strictAllowlistMustRejectUnexpectedApplicationTypes() {
        ForyRpcCodec legacy = new ForyRpcCodec();
        ForyRpcCodec strict = new ForyRpcCodec(
                ForyRpcSecurityOptions.strictAllowlist(
                        Set.of("com.peachsoft.otryx.codec.fory.allowed.*")));
        byte[] payload = legacy.encode(new ForbiddenPayload("secret"));
        assertThrows(RuntimeException.class,
                () -> strict.decode(payload, Object.class));
    }

    @Test
    void strictModeAcceptsApprovedApplicationRecordFromLegacyPayload() {
        ForyRpcCodec legacy = new ForyRpcCodec();
        ForyRpcCodec strict = new ForyRpcCodec(
                ForyRpcSecurityOptions.strictAllowlist(
                        Set.of(ForbiddenPayload.class.getName())));
        ForbiddenPayload source = new ForbiddenPayload("approved");

        byte[] legacyWireBytes = legacy.encode(source);

        assertEquals(source, strict.decode(legacyWireBytes, ForbiddenPayload.class));
        assertArrayEquals(legacyWireBytes, strict.encode(source));
    }

    @Test
    void strictBindMustRejectNestedDtoMissingFromAllowlist()
            throws Exception {
        RpcMethodDescriptor descriptor = RpcMethodDescriptor.from(
                new ServiceKey(
                        GenericContract.class.getName(),
                        "1.0.0",
                        "default"),
                GenericContract.class.getMethod("find", Box.class));
        ForyRpcCodec incomplete = new ForyRpcCodec(
                ForyRpcSecurityOptions.strictAllowlist(
                        Set.of(Box.class.getName())));
        assertThrows(IllegalArgumentException.class,
                () -> incomplete.bind(descriptor));

        ForyRpcCodec complete = new ForyRpcCodec(
                ForyRpcSecurityOptions.strictAllowlist(
                        Set.of(
                                Box.class.getName(),
                                ForbiddenPayload.class.getName())));
        RpcMethodCodec methodCodec = complete.bind(descriptor);
        assertTrue(methodCodec.codecId() == RpcCodecIds.FORY_NATIVE);
    }

    @Test
    void rejectPayloadWithExcessiveObjectGraphDepth() {
        ForyRpcCodec legacy = new ForyRpcCodec();
        Object nested = "leaf";
        for (int i = 0; i < 12; i++) {
            nested = new Object[] {nested};
        }
        byte[] bytes = legacy.encode(nested);
        ForyRpcCodec bounded = new ForyRpcCodec(
                new ForyRpcSecurityOptions(
                        ForyRpcSecurityOptions.Mode.TRUSTED_COMPATIBILITY,
                        Set.of(),
                        3,
                        64L * 1024 * 1024,
                        16 * 1024 * 1024));
        assertThrows(RuntimeException.class,
                () -> bounded.decode(bytes, Object.class));
    }

    @Test
    void rejectMalformedSliceBounds() throws Exception {
        ForyRpcCodec codec = new ForyRpcCodec();
        RpcMethodCodec binding = codec.bind(RpcMethodDescriptor.from(
                new ServiceKey(
                        SampleService.class.getName(), "1.0.0", "default"),
                SampleService.class.getMethod("echo", String.class)));
        assertThrows(IllegalArgumentException.class,
                () -> binding.decodeResult(new byte[8], -1, 4));
        assertThrows(IllegalArgumentException.class,
                () -> binding.decodeArguments(new byte[8], 4, 16));
    }

    @Test
    void rejectTooLargePayloadBeforeDeserialization() {
        ForyRpcSecurityOptions security =
                new ForyRpcSecurityOptions(
                        ForyRpcSecurityOptions.Mode.TRUSTED_COMPATIBILITY,
                        Set.of(), 32, 1024 * 1024, 8);
        ForyRpcCodec strictSize = new ForyRpcCodec(security);
        byte[] largePayload = new byte[64];
        assertThrows(IllegalArgumentException.class,
                () -> strictSize.decode(largePayload, Object.class));
        assertThrows(IllegalArgumentException.class,
                () -> strictSize.encode("A message larger than the configured cap"));
    }

    @Test
    void rejectUnsafeOrMissingAllowlistSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> ForyRpcSecurityOptions.strictAllowlist(Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ForyRpcSecurityOptions.strictAllowlist(Set.of("*")));
        assertThrows(IllegalArgumentException.class,
                () -> ForyRpcSecurityOptions.strictAllowlist(Set.of("java.*")));
        assertTrue(ForyRpcSecurityOptions.trustedCompatibility()
                .maxPayloadBytes() > 0);
    }

    private record ForbiddenPayload(String value) {
    }

    private static byte[] wrap(byte[] payload) {
        byte[] frame = new byte[payload.length + 6];
        Arrays.fill(frame, (byte) 9);
        System.arraycopy(
                payload,
                0,
                frame,
                3,
                payload.length);
        return frame;
    }

    private record Box<T>(T value) {
    }

    interface GenericContract {
        Box<ForbiddenPayload> find(Box<ForbiddenPayload> request);
    }

    interface SampleService {
        String echo(String value);

        String ping();
    }
}
