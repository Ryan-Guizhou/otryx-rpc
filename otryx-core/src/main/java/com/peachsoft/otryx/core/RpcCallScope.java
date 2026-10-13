package com.peachsoft.otryx.core;

/**
 * RPC 调用上下文的线程作用域。
 *
 * <p>仅允许在创建该作用域的线程中关闭；务必使用 try-with-resources，
 * 以便嵌套调用正确恢复上一个上下文。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
@FunctionalInterface
public interface RpcCallScope extends AutoCloseable {

    /**
     * 关闭当前作用域并恢复先前上下文，不抛出已检查异常。
     */
    @Override
    void close();
}
