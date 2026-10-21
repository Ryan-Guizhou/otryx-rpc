package com.peachsoft.otryx.codec;

import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.spi.SPI;

/**
 * RPC 消息体编解码扩展点。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@SPI("fory")
public interface RpcCodec {

    /**
     * 返回实际编解码器的只读安全能力，不从协议编号推断。
     *
     * @return 未声明的实现返回 UNKNOWN
     */
    default RpcCodecSecurityDescriptor securityDescriptor() {
        return RpcCodecSecurityDescriptor.unknown();
    }

    /**
     * 返回稳定线协议 Codec 编号。
     *
     * @return Codec 编号
     */
    byte code();

    /**
     * 序列化 Java 对象。
     *
     * <p>该通用入口属于兼容层；高性能调用路径应优先使用 {@link #bind(RpcMethodDescriptor)}。
     *
     * @param value 待序列化对象
     * @return 序列化结果
     */
    byte[] encode(Object value);

    /**
     * 反序列化消息体。
     *
     * <p>该通用入口属于兼容层；高性能调用路径应优先使用 {@link #bind(RpcMethodDescriptor)}。
     *
     * @param bytes 消息体
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 反序列化对象
     */
    <T> T decode(byte[] bytes, Class<T> type);

    /**
     * 为指定服务方法创建启动阶段绑定的 Codec。
     *
     * <p>Codec 实现可以覆盖该方法，生成或缓存方法专用 encoder/decoder。
     * 默认实现保留通用对象编解码兼容能力。
     *
     * @param descriptor 方法描述
     * @return 方法级 Codec
     */
    default RpcMethodCodec bind(RpcMethodDescriptor descriptor) {
        return new DefaultRpcMethodCodec(this);
    }
}
