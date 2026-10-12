package com.peachsoft.otryx.transport.vertx;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.net.SSLOptions;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * TLS 文件重载的有界控制面协调器。
 *
 * <p>读取、哈希及校验证书材料只在自有单线程工作池执行；
 * SSL 配置发布必须回到指定 Vert.x Context。一次重载包括材料准备、
 * 发布和异步完成，整个周期内最多只允许一个任务在途。
 *
 * <p>关闭只停止接收新任务并请求中断工作线程，不等待或声称已经强制
 * 终止可能忽略中断的文件系统调用。
 *
 * @param <S> 不可变 TLS 材料状态快照类型
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 14:33
 */
final class TlsReloadController<S> implements AutoCloseable {

    private final Context publicationContext;
    private final Supplier<S> stateReader;
    private final Function<S, SSLOptions> materialPreparer;
    private final Function<SSLOptions, Future<?>> publisher;
    private final BiConsumer<Long, Throwable> completionObserver;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private volatile S lastSuccessfulState;

    /**
     * 创建单飞 TLS 热更新控制器。
     *
     * @param publicationContext SSL 配置归属的 Vert.x Context
     * @param stateReader 只允许在工作线程中调用的文件状态读取器
     * @param materialPreparer 只允许在工作线程中调用的材料校验器
     * @param publisher 在 Vert.x Context 执行的异步 SSL 更新器
     * @param completionObserver 重载实际完成后的统计回调
     */
    TlsReloadController(
            Context publicationContext,
            Supplier<S> stateReader,
            Function<S, SSLOptions> materialPreparer,
            Function<SSLOptions, Future<?>> publisher,
            BiConsumer<Long, Throwable> completionObserver) {
        this.publicationContext = Objects.requireNonNull(
                publicationContext, "publicationContext");
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
        this.materialPreparer = Objects.requireNonNull(
                materialPreparer, "materialPreparer");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.completionObserver = Objects.requireNonNull(
                completionObserver, "completionObserver");
        this.worker = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                Thread.ofPlatform()
                        .daemon(true)
                        .name("otryx-tls-control-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 非阻塞提交一次文件重载检查，重叠调用合并为一次在途任务。
     *
     * <p>调用方可以是 EventLoop；此方法不访问文件系统，也不等待工作池。
     */
    void requestReload() {
        if (closed.get() || !inFlight.compareAndSet(false, true)) {
            return;
        }
        long revision = generation.incrementAndGet();
        long startedAtNanos = System.nanoTime();
        try {
            worker.execute(() -> prepare(revision, startedAtNanos));
        } catch (RejectedExecutionException error) {
            finish(revision, startedAtNanos, error);
        }
    }

    private void prepare(long revision, long startedAtNanos) {
        try {
            S current = Objects.requireNonNull(
                    stateReader.get(), "TLS file state");
            if (isStale(revision)) {
                inFlight.set(false);
                return;
            }
            if (Objects.equals(current, lastSuccessfulState)) {
                inFlight.set(false);
                return;
            }

            SSLOptions prepared = Objects.requireNonNull(
                    materialPreparer.apply(current), "TLS reload options");
            // 材料可能在读出状态与构建 SSL 配置之间被覆盖，必须重新核实。
            if (!Objects.equals(current, stateReader.get())) {
                throw new IllegalStateException(
                        "TLS material changed while preparing SSL options");
            }
            if (isStale(revision)) {
                inFlight.set(false);
                return;
            }
            publicationContext.runOnContext(
                    ignored -> publish(revision, startedAtNanos, current, prepared));
        } catch (RuntimeException error) {
            finish(revision, startedAtNanos, error);
        }
    }

    private void publish(
            long revision,
            long startedAtNanos,
            S current,
            SSLOptions prepared) {
        if (isStale(revision)) {
            inFlight.set(false);
            return;
        }
        try {
            Future<?> update = Objects.requireNonNull(
                    publisher.apply(prepared), "TLS update future");
            update.onComplete(result -> {
                if (result.succeeded() && !isStale(revision)) {
                    lastSuccessfulState = current;
                }
                finish(revision, startedAtNanos, result.cause());
            });
        } catch (RuntimeException error) {
            finish(revision, startedAtNanos, error);
        }
    }

    private boolean isStale(long revision) {
        return closed.get() || generation.get() != revision;
    }

    private void finish(
            long revision,
            long startedAtNanos,
            Throwable error) {
        try {
            if (!isStale(revision)) {
                completionObserver.accept(startedAtNanos, error);
            }
        } finally {
            inFlight.set(false);
        }
    }

    /** 返回上一次真正成功发布的状态，用于包内诊断与确定性测试。 */
    S lastSuccessfulState() {
        return lastSuccessfulState;
    }

    /** 返回完整 TLS 准备与发布过程是否仍在途。 */
    boolean reloadInProgress() {
        return inFlight.get();
    }

    /**
     * 幂等关闭控制面工作池，不等待不可强制终止的外部文件 IO。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        generation.incrementAndGet();
        worker.shutdownNow();
    }
}
