package com.peachsoft.otryx.codec;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 已在服务方法粒度预绑定的 Codec 热路径接口。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 11:46
 */
public interface RpcMethodCodec {

    /**
     * 返回实际编解码器的只读安全能力，不从协议编号推断。
     *
     * @return 未声明的实现返回 UNKNOWN
     */
    default RpcCodecSecurityDescriptor securityDescriptor() {
        return RpcCodecSecurityDescriptor.unknown();
    }

    /**
     * 返回线协议 Codec 编号。
     *
     * @return Codec 编号
     */
    byte codecId();

    /**
     * 编码零参数调用。
     *
     * @return 编码结果
     */
    default byte[] encode0() {
        return encodeArguments(new Object[0]);
    }

    /**
     * 编码单参数调用。
     *
     * @param argument0 参数 0
     * @return 编码结果
     */
    default byte[] encode1(Object argument0) {
        return encodeArguments(new Object[] {argument0});
    }

    /**
     * 编码双参数调用。
     *
     * @param argument0 参数 0
     * @param argument1 参数 1
     * @return 编码结果
     */
    default byte[] encode2(Object argument0, Object argument1) {
        return encodeArguments(new Object[] {argument0, argument1});
    }

    /**
     * 编码三参数调用。
     *
     * @param argument0 参数 0
     * @param argument1 参数 1
     * @param argument2 参数 2
     * @return 编码结果
     */
    default byte[] encode3(
            Object argument0,
            Object argument1,
            Object argument2) {
        return encodeArguments(new Object[] {
                argument0,
                argument1,
                argument2
        });
    }

    /**
     * 编码四参数调用。
     *
     * @param argument0 参数 0
     * @param argument1 参数 1
     * @param argument2 参数 2
     * @param argument3 参数 3
     * @return 编码结果
     */
    default byte[] encode4(
            Object argument0,
            Object argument1,
            Object argument2,
            Object argument3) {
        return encodeArguments(new Object[] {
                argument0,
                argument1,
                argument2,
                argument3
        });
    }

    /**
     * 编码调用参数。
     *
     * @param arguments 参数
     * @return 编码结果
     */
    byte[] encodeArguments(Object[] arguments);

    /**
     * 解码调用参数。
     *
     * @param payload 编码载荷
     * @return 参数
     */
    Object[] decodeArguments(byte[] payload);

    /**
     * 从完整帧的 Payload 区间解码调用参数。
     *
     * <p>默认实现为兼容旧 Codec 创建 Payload 副本；支持 buffer/slice
     * 解码的 Codec 应覆盖该方法。
     *
     * @param frame 完整帧
     * @param offset Payload offset
     * @param length Payload length
     * @return 参数
     */
    default Object[] decodeArguments(
            byte[] frame,
            int offset,
            int length) {
        return decodeArguments(java.util.Arrays.copyOfRange(
                frame,
                offset,
                offset + length));
    }

    /**
     * 编码成功返回值。
     *
     * @param value 返回值
     * @return 编码结果
     */
    byte[] encodeResult(Object value);

    /**
     * 将结果编码写入调用者提供的有界 OutputStream。
     *
     * <p>默认实现兼容第三方 Codec，但会先产生完整 Payload 数组；
     * 对高并发或不可信大对象应覆盖此方法并直接写入有界输出。
     * Provider 在调用前已校验身份、方法授权和执行预算。
     *
     * @param value 业务返回值
     * @param output 已预留资源并检查写入长度的 OutputStream
     * @throws IOException 输出失败
     */
    default void encodeResultTo(Object value, OutputStream output) throws IOException {
        output.write(encodeResult(value));
    }

    /**
     * 解码成功返回值。
     *
     * @param payload 编码载荷
     * @return 返回值
     */
    Object decodeResult(byte[] payload);

    /**
     * 从完整帧的 Payload 区间解码成功返回值。
     *
     * @param frame 完整帧
     * @param offset Payload offset
     * @param length Payload length
     * @return 返回值
     */
    default Object decodeResult(
            byte[] frame,
            int offset,
            int length) {
        return decodeResult(java.util.Arrays.copyOfRange(
                frame,
                offset,
                offset + length));
    }
}
