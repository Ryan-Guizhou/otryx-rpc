package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.codec.RpcBoundedResultMethodCodec;
import com.peachsoft.otryx.codec.RpcCodecSecurityDescriptor;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;

/**
 * 验证生产安全 Profile 不允许未知 Codec 在配额之外先分配完整返回数组。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 15:40
 */
class RpcBoundedResultMethodCodecSecurityTest {
    private static final byte CODEC_ID = 1;

    @Test
    void networkProfileMustRejectNonStreamingCodecDuringBinding() {
        RpcMethodCodec legacy = new LegacyResultCodec();
        assertSame(legacy, RpcSecurityChecks.method(
                RpcSecurityProfile.TRUSTED_DEVELOPMENT, legacy, CODEC_ID));
        assertThrows(IllegalStateException.class, () -> RpcSecurityChecks.method(
                RpcSecurityProfile.NETWORK_MTLS, legacy, CODEC_ID));
    }

    @Test
    void networkProfileMustAcceptExplicitStreamingImplementation() {
        RpcBoundedResultMethodCodec streaming = new StreamingResultCodec();
        assertSame(streaming, RpcSecurityChecks.method(
                RpcSecurityProfile.NETWORK_MTLS, streaming, CODEC_ID));
    }

    private static class LegacyResultCodec implements RpcMethodCodec {
        @Override
        public byte codecId() {
            return CODEC_ID;
        }

        @Override
        public RpcCodecSecurityDescriptor securityDescriptor() {
            return new RpcCodecSecurityDescriptor(
                    RpcCodecSecurityDescriptor.TypePolicy.REGISTERED_CONTRACT_TYPES,
                    true, true, 1024, 32, 1024L * 1024);
        }

        @Override
        public byte[] encodeArguments(Object[] arguments) {
            return new byte[0];
        }

        @Override
        public Object[] decodeArguments(byte[] payload) {
            return new Object[0];
        }

        @Override
        public byte[] encodeResult(Object value) {
            return new byte[0];
        }

        @Override
        public Object decodeResult(byte[] payload) {
            return null;
        }
    }

    private static final class StreamingResultCodec extends LegacyResultCodec
            implements RpcBoundedResultMethodCodec {
        @Override
        public void encodeResultTo(Object value, OutputStream output) {
            // 仅验证方法级能力分派，不代替真实 Fory Buffer 容量与性能回归。
        }
    }
}
