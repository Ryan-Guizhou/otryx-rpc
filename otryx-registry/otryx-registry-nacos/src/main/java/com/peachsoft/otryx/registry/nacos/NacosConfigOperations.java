package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.config.ConfigQueryResult;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Nacos 元数据所需的最小配置操作边界。
 *
 * <p>生产入口由 {@link NacosConfigClient} 实现，只返回远端查询结果。所有操作均可能阻塞，
 * 运行时由 NacosControlExecutor 隔离，初始化工具由独占维护线程调用；客户端由创建者关闭。
 * 不暴露监听、模糊查询或本地 Snapshot 开关。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 16:20
 */
interface NacosConfigOperations {

    /**
     * 获取 Config 内容及服务端 CAS MD5，后端失败必须以异常返回。
     *
     * @param dataId 非空配置标识
     * @param group 配置组
     * @param timeoutMillis 正毫秒查询预算
     * @return 远端响应；远端明确不存在时 content 为 null
     * @throws NacosException SDK 查询或过滤器失败时抛出
     * @throws InterruptedException 调用线程已中断时抛出
     * @throws TimeoutException 进入 SDK 前剩余预算不足一毫秒时抛出
     */
    ConfigQueryResult query(String dataId, String group, long timeoutMillis)
            throws NacosException, InterruptedException, TimeoutException;

    /**
     * 发布不可变 Manifest，或在独占初始化窗口创建固定 Mapping 分片。
     *
     * <p>底层不保证 create-if-absent；调用方仍须完成不可变内容校验或满足独占初始化约束。
     *
     * @param dataId 非空配置标识
     * @param group 配置组
     * @param content 已通过大小校验的非空内容
     * @return SDK 发布确认；false 不代表已写入
     * @throws NacosException 参数或过滤器拒绝发布时抛出
     */
    boolean publishImmutable(String dataId, String group, String content) throws NacosException;

    /**
     * 以先前远端读取的 MD5 执行条件发布，不允许缺少 CAS 条件。
     *
     * @param dataId 非空配置标识
     * @param group 配置组
     * @param content 已通过大小校验的非空内容
     * @param casMd5 先前远端响应的非 null MD5
     * @return SDK 条件发布确认；false 由上层按现有有界冲突流程处理
     * @throws NacosException 参数或过滤器拒绝发布时抛出
     */
    boolean compareAndSet(String dataId, String group, String content, String casMd5) throws NacosException;

    /**
     * 关闭此实例拥有的配置资源，不关闭独立 Naming 客户端。
     *
     * @throws NacosException SDK 关闭失败时抛出
     * @throws InterruptedException 等待关闭时线程中断，仍保留关闭所有权
     * @throws TimeoutException 等待关闭预算耗尽，仍保留关闭所有权
     */
    void shutDown() throws NacosException, InterruptedException, TimeoutException;

    /**
     * 保留包内原始 ConfigService 注入测试的窄桥，不赋予注入对象任何可信来源证明。
     *
     * <p>工厂与初始化工具必须直接创建 {@link NacosConfigClient}，不得使用此桥选择 SDK 回退读。
     * 桥接响应仍由 NacosV2ConfigStore 和各元数据仓库执行原有严格校验。
     *
     * @param service 测试或包内调用方显式拥有的非 null SDK 对象
     * @return 只转发四项能力的适配器，关闭时关闭原对象
     */
    static NacosConfigOperations fromInjectedService(ConfigService service) {
        Objects.requireNonNull(service, "config");
        return new NacosConfigOperations() {
            @Override
            public ConfigQueryResult query(String dataId, String group, long timeoutMillis) throws NacosException {
                return service.getConfigWithResult(dataId, group, timeoutMillis);
            }

            @Override
            public boolean publishImmutable(String dataId, String group, String content) throws NacosException {
                return service.publishConfig(dataId, group, content);
            }

            @Override
            public boolean compareAndSet(String dataId, String group, String content, String casMd5)
                    throws NacosException {
                return service.publishConfigCas(dataId, group, content, casMd5);
            }

            @Override
            public void shutDown() throws NacosException {
                service.shutDown();
            }
        };
    }
}
