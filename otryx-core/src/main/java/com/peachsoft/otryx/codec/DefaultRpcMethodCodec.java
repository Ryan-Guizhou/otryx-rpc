package com.peachsoft.otryx.codec;

import java.util.Objects;

/**
 * 通用 RpcCodec 到方法级 Codec 的兼容适配器。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 11:46
 */
final class DefaultRpcMethodCodec implements RpcMethodCodec {
    private final RpcCodec delegate;

    DefaultRpcMethodCodec(RpcCodec delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public RpcCodecSecurityDescriptor securityDescriptor() {
        return delegate.securityDescriptor();
    }

    @Override
    public byte codecId() {
        return delegate.code();
    }

    @Override
    public byte[] encodeArguments(Object[] arguments) {
        return delegate.encode(arguments == null ? new Object[0] : arguments);
    }

    @Override
    public Object[] decodeArguments(byte[] payload) {
        return delegate.decode(payload, Object[].class);
    }

    @Override
    public byte[] encodeResult(Object value) {
        return delegate.encode(value);
    }

    @Override
    public Object decodeResult(byte[] payload) {
        return delegate.decode(payload, Object.class);
    }
}
