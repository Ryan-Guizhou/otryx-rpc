package com.peachsoft.otryx.api;

/**
 * RPC 基础运行时异常。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public class RpcException extends RuntimeException {

    /**
     * 创建 RPC 异常。
     *
     * @param message 错误描述
     */
    public RpcException(String message) {
        super(message);
    }

    /**
     * 创建携带原始原因的 RPC 异常。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 为受控框架异常设置原因与诊断附加信息边界。
     *
     * @param message 固定错误描述
     * @param cause 原因，安全异常固定为 null
     * @param enableSuppression 是否允许附加异常
     * @param writableStackTrace 是否保留调用位置
     */
    protected RpcException(
            String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

}
