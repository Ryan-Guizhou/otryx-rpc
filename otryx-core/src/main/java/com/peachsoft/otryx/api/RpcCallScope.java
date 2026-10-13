package com.peachsoft.otryx.api;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 线程局部 RPC 上下文作用域，必须在创建它的线程以 LIFO 顺序关闭。
 *
 * <p>作用域不自动跨异步任务传播。使用 `RpcCallContext.wrap` 对业务
 * 异步任务做显式捕获与恢复。重复关闭不会破坏外层上下文。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 16:10
 */
public final class RpcCallScope implements AutoCloseable {

    private final RpcCallContext previous;
    private final RpcCallContext active;
    private final Thread owner;
    private final AtomicBoolean closed = new AtomicBoolean();

    RpcCallScope(RpcCallContext previous, RpcCallContext active) {
        this.previous = previous;
        this.active = active;
        this.owner = Thread.currentThread();
    }

    /**
     * 恢复进入本次作用域前的上下文。
     */
    @Override
    public void close() {
        if (closed.get()) {
            return;
        }
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "RPC call scope must close on the creating thread");
        }
        if (RpcCallContext.current() != active) {
            throw new IllegalStateException("RPC call scopes must close in LIFO order");
        }
        if (closed.compareAndSet(false, true)) {
            RpcCallContext.restore(previous);
        }
    }
}
