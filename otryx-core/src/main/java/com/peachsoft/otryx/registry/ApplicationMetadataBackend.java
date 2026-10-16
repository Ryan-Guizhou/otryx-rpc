package com.peachsoft.otryx.registry;

/**
 * 提供可复用的应用级元数据仓库，而不强制使用该后端作为成员注册中心。
 *
 * <p>Eureka 等仅提供 Member Lease 的注册中心必须显式组合真正支持 CAS 的
 * Manifest/Mapping 后端。持有者负责与创建时的 Registry 共同管理生命周期；
 * Core 不能反向依赖具体 Etcd、Nacos 或 Consul SDK。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:15
 */
public interface ApplicationMetadataBackend {

    /**
     * 返回可靠的内容寻址 Manifest Repository。
     *
     * @return 生命周期与创建方共享的 Manifest Repository
     */
    ApplicationManifestRepository manifestRepository();

    /**
     * 返回支持实例级 Generation Fencing 的 Mapping Repository。
     *
     * @return 生命周期与创建方共享的 Mapping Repository
     */
    ServiceMappingRepository serviceMappingRepository();
}
