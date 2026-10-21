package com.peachsoft.otryx.codec;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 声明方法级 Codec 在结果编码期间直接向调用方有界输出写入的能力。
 *
 * <p>NETWORK_MTLS Provider 只能使用该接口而非默认整块数组适配器。
 * 实现者必须在写入时尊重 OutputStream 的拒绝与取消，不得先通过
 * serialize/encodeResult() 构造完整 Payload 并绕过输出流容量配额。
 * 这是一项实现契约；框架无法单独证明第三方编码器内部对象图内存上限。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 15:40
 */
public interface RpcBoundedResultMethodCodec extends RpcMethodCodec {

    /**
     * 直接编码到已受容量限制的输出流，拒绝应立即停止继续分配和写入。
     *
     * @param value 业务返回对象，必须遵守预先验证的类型契约
     * @param output 调用方独占、可拒绝超额写入的流；调用方负责关闭
     * @throws IOException 写入的受限流出现 I/O 失败
     */
    @Override
    void encodeResultTo(Object value, OutputStream output) throws IOException;
}
