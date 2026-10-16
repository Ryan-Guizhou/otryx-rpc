package com.peachsoft.otryx.registry;

import java.util.concurrent.CompletionStage;

/**
 * 显式读取未经本注册中心成员健康过滤的服务映射候选应用。
 *
 * <p>仅用于分离 Member Registry 与 Metadata Backend 的装配，例如 Eureka
 * 成员身份由 Eureka 维护而 CAS Mapping 存储在 Etcd/Nacos/Consul。
 * 这里的候选 Owner 实例集合不能直接作为可路由结果；必须进一步通过主成员注册中心、
 * Manifest 与 Schema 校验。普通 ServiceMappingRepository.lookup 仍保持
 * 原后端提供的健康语义。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:15
 */
public interface ServiceMappingCandidateSource {

    /**
     * 有界查询全部有效 Owner 中声明目标接口的候选应用，不检查该后端的 Member Lease。
     *
     * @param key 完整命名空间和 ServiceKey
     * @return 未经成员验证的原子 Mapping 快照；异常表示后端不可用
     */
    CompletionStage<ServiceMappingOwnerSnapshot> lookupCandidateOwners(ServiceMappingKey key);
}
