package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.config.ConfigQueryResult;
import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.filter.impl.ConfigFilterChainManager;
import com.alibaba.nacos.client.config.filter.impl.ConfigRequest;
import com.alibaba.nacos.client.config.filter.impl.ConfigResponse;
import com.alibaba.nacos.client.config.impl.ClientWorker;
import com.alibaba.nacos.client.config.impl.ConfigServerListManager;
import com.alibaba.nacos.client.config.utils.ParamUtils;
import com.alibaba.nacos.client.env.NacosClientProperties;
import com.alibaba.nacos.client.utils.ClientBasicParamUtil;
import com.alibaba.nacos.client.utils.ValidatorUtils;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用单个 Nacos 3.2.4 ClientWorker 的远端配置访问器。
 *
 * <p>查询直接调用 SDK 远端入口，保留命名空间、鉴权和过滤器上下文，不经过
 * NacosConfigService 的本地 failover 或 Snapshot 回退。发布与 CAS 使用同一个 worker。
 * 操作实际退场之前不会关闭底层客户端，关闭开始后不再接纳新操作；不创建附加线程池、
 * 第二个 Config 客户端或全局 Snapshot 开关。SDK 的公开内部接口绑定当前依赖版本。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 16:20
 */
final class NacosConfigClient implements NacosConfigOperations {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosConfigClient.class);
    private static final Duration DEFAULT_CLOSE_WAIT = Duration.ofSeconds(3);

    private final NacosConfigOperations remote;
    private final long closeWaitNanos;
    private final Object lifecycle = new Object();
    private final CompletableFuture<Void> closeCompleted = new CompletableFuture<>();
    private int activeOperations;
    private boolean closing;
    private boolean closeClaimed;

    /**
     * 创建唯一远端配置客户端，初始化或构造失败由 SDK 及内部资源所有者处理。
     *
     * @param properties 非 null 的独立 SDK 属性，不记录属性或凭据
     * @throws NacosException SDK 初始化失败时抛出
     */
    NacosConfigClient(Properties properties) throws NacosException {
        this(new RemoteConfigOperations(properties), DEFAULT_CLOSE_WAIT);
    }

    /**
     * 注入同一所有权协议下的底层操作，供包内确定性生命周期测试使用。
     *
     * @param remote 非 null 的操作所有者，构造成功后由本对象唯一关闭
     * @param closeWait 等待在途操作和关闭信号的正预算，不是 SDK 同步清理的硬时限
     */
    NacosConfigClient(NacosConfigOperations remote, Duration closeWait) {
        this.remote = Objects.requireNonNull(remote, "remote");
        closeWaitNanos = Objects.requireNonNull(closeWait, "closeWait").toNanos();
        if (closeWaitNanos <= 0L) {
            throw new IllegalArgumentException("Nacos Config close wait must be positive");
        }
    }

    /** {@inheritDoc} */
    @Override
    public ConfigQueryResult query(String dataId, String group, long timeoutMillis)
            throws NacosException, InterruptedException, TimeoutException {
        long started = System.nanoTime();
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("Nacos Config read timeout must be positive");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Nacos Config read interrupted");
        }
        beginOperation();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
            if (remainingMillis <= 0L) {
                throw new TimeoutException("Nacos Config read deadline exceeded");
            }
            return remote.query(dataId, group, remainingMillis);
        } finally {
            endOperation();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean publishImmutable(String dataId, String group, String content) throws NacosException {
        beginOperation();
        try {
            return remote.publishImmutable(dataId, group, content);
        } finally {
            endOperation();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean compareAndSet(String dataId, String group, String content, String casMd5) throws NacosException {
        beginOperation();
        try {
            return remote.compareAndSet(dataId, group, content, casMd5);
        } finally {
            endOperation();
        }
    }

    /** 在同一短锁内登记真实操作；锁内不运行 SDK、用户过滤器或等待。 */
    private void beginOperation() {
        synchronized (lifecycle) {
            if (closing) {
                throw new IllegalStateException("Nacos Config client is closed");
            }
            activeOperations++;
        }
    }

    /** 真实同步调用退场后归还所有权；最后操作可接管先前关闭请求的 SDK 清理。 */
    private void endOperation() {
        boolean cleanup;
        synchronized (lifecycle) {
            activeOperations--;
            cleanup = claimClose();
        }
        if (cleanup) {
            closeRemote();
        }
    }

    /**
     * 在持有 lifecycle 短锁时唯一认领关闭；尚有操作能进入 SDK 时不能清理连接。
     *
     * @return 当前调用是否拥有底层唯一清理责任
     */
    private boolean claimClose() {
        if (closing && activeOperations == 0 && !closeClaimed) {
            closeClaimed = true;
            return true;
        }
        return false;
    }

    /**
     * 停止接纳操作，在入口起算的等待预算内观察同一个真实关闭结果。
     *
     * <p>超时或中断不撤销已登记操作的所有权；最后操作退场后仍负责唯一清理。
     * 没有在途操作时本线程同步执行 SDK 清理，其耗时不受等待预算硬性中断。
     * 关闭信号只表示操作退场且 SDK shutdown 返回，不代表其全部后台线程已退出。
     *
     * @throws NacosException SDK 关闭失败时抛出
     * @throws InterruptedException 等待时中断并恢复中断标志，清理责任仍然保留
     * @throws TimeoutException 本次等待预算耗尽，清理责任仍然保留
     */
    @Override
    public void shutDown() throws NacosException, InterruptedException, TimeoutException {
        long started = System.nanoTime();
        boolean cleanup;
        synchronized (lifecycle) {
            closing = true;
            cleanup = claimClose();
        }
        if (cleanup) {
            closeRemote();
        }
        long remaining = closeWaitNanos - (System.nanoTime() - started);
        if (remaining <= 0L) {
            throw new TimeoutException("Nacos Config close wait expired");
        }
        try {
            closeCompleted.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof NacosException nacos) {
                throw nacos;
            }
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (cause instanceof TimeoutException timeout) {
                throw timeout;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Nacos Config shutdown failed");
        }
    }

    /**
     * 只由已认领的线程执行一次 SDK 清理；结果仅进入私有关闭信号，不覆盖操作原始异常。
     */
    private void closeRemote() {
        try {
            remote.shutDown();
            closeCompleted.complete(null);
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            closeCompleted.completeExceptionally(failure);
            LOGGER.warn("Nacos Config shutdown failed: errorType={}", failure.getClass().getSimpleName());
        }
    }

    /** 绑定 Nacos 3.2.4 内部公开接口，读写共用一个 worker 及其过滤器和 server list。 */
    private static final class RemoteConfigOperations implements NacosConfigOperations {
        private final String namespace;
        private final ConfigFilterChainManager filters;
        private final ConfigServerListManager servers;
        private final ClientWorker worker;

        /**
         * 创建一个配置连接所有者，沿用 SDK 的属性、命名空间和过滤器初始化规则。
         *
         * <p>构造失败时关闭已取得的 server list；SDK worker 构造中途失败的内部对象未被 SDK 暴露，
         * 本封装不能获得该半成品的引用。已成功创建的实例必须由工厂或初始化工具关闭。
         *
         * @param properties 非 null 的独立 SDK 属性，不记录属性或凭据
         * @throws NacosException SDK 初始化失败时抛出
         */
        private RemoteConfigOperations(Properties properties) throws NacosException {
            Objects.requireNonNull(properties, "properties");
            NacosClientProperties clientProperties = NacosClientProperties.PROTOTYPE.derive(properties);
            ValidatorUtils.checkInitParam(clientProperties);
            namespace = ClientBasicParamUtil.parseNamespace(clientProperties);
            clientProperties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
            filters = new ConfigFilterChainManager(clientProperties.asProperties());
            servers = new ConfigServerListManager(clientProperties);
            boolean initialized = false;
            try {
                servers.start();
                worker = new ClientWorker(filters, servers, clientProperties);
                initialized = true;
            } finally {
                if (!initialized) {
                    closeServerListAfterFailure();
                }
            }
        }

        /** {@inheritDoc} */
        @Override
        public ConfigQueryResult query(String dataId, String group, long timeoutMillis) throws NacosException {
            if (timeoutMillis <= 0L) {
                throw new IllegalArgumentException("Nacos Config read timeout must be positive");
            }
            String actualGroup = normalizeGroup(group);
            ParamUtils.checkKeyParam(dataId, actualGroup);
            ConfigResponse remote = worker.getServerConfig(dataId, actualGroup, namespace, timeoutMillis, false);
            ConfigResponse response = new ConfigResponse();
            response.setDataId(dataId);
            response.setTenant(namespace);
            response.setGroup(actualGroup);
            response.setContent(remote.getContent());
            response.setMd5(remote.getMd5());
            response.setConfigType(remote.getConfigType());
            response.setEncryptedDataKey(remote.getEncryptedDataKey());
            filters.doFilter(null, response);
            ConfigQueryResult result = new ConfigQueryResult();
            result.setContent(response.getContent());
            result.setMd5(response.getMd5());
            result.setConfigType(response.getConfigType());
            result.setEncryptedDataKey(response.getEncryptedDataKey());
            return result;
        }

        /** {@inheritDoc} */
        @Override
        public boolean publishImmutable(String dataId, String group, String content) throws NacosException {
            return publish(dataId, group, content, null);
        }

        /** {@inheritDoc} */
        @Override
        public boolean compareAndSet(String dataId, String group, String content, String casMd5) throws NacosException {
            return publish(dataId, group, content, Objects.requireNonNull(casMd5, "casMd5"));
        }

        /**
         * 按 SDK 默认 Config 类型执行过滤和发布，不重复提交或自行构造 CAS MD5。
         *
         * @param dataId 配置标识
         * @param group 配置组
         * @param content 元数据内容
         * @param casMd5 首次发布为 null，条件发布为先前远端 MD5
         * @return SDK 的发布确认
         * @throws NacosException 参数或过滤器拒绝发布时抛出
         */
        private boolean publish(String dataId, String group, String content, String casMd5) throws NacosException {
            String actualGroup = normalizeGroup(group);
            ParamUtils.checkParam(dataId, actualGroup, content);
            String type = ConfigType.getDefaultType().getType();
            ConfigRequest request = new ConfigRequest();
            request.setDataId(dataId);
            request.setTenant(namespace);
            request.setGroup(actualGroup);
            request.setContent(content);
            request.setType(type);
            filters.doFilter(request, null);
            return worker.publishConfig(dataId, actualGroup, namespace, null, null, null,
                    request.getContent(), request.getEncryptedDataKey(), casMd5, type);
        }

        /**
         * 按外层唯一关闭所有者的指令释放 SDK；worker 失败也尝试关闭 server list。
         *
         * @throws NacosException worker 或 server list 关闭失败时抛出
         */
        @Override
        public void shutDown() throws NacosException {
            boolean workerClosed = false;
            try {
                worker.shutdown();
                workerClosed = true;
            } finally {
                if (workerClosed) {
                    servers.shutdown();
                } else {
                    closeServerListAfterFailure();
                }
            }
        }

        /** 在已有主要失败时关闭可访问的 server list，避免原始异常和凭据进入日志。 */
        private void closeServerListAfterFailure() {
            try {
                servers.shutdown();
            } catch (NacosException | RuntimeException cleanupFailure) {
                LOGGER.warn("Nacos Config server list cleanup failed: errorType={}",
                        cleanupFailure.getClass().getSimpleName());
            }
        }

        /**
         * 与 SDK 相同地归一化空组名和首尾空白。
         *
         * @param group 可为 null 的组名
         * @return 默认组或去除首尾空白后的组名
         */
        private static String normalizeGroup(String group) {
            return group == null || group.isBlank() ? Constants.DEFAULT_GROUP : group.trim();
        }
    }
}
