package com.peachsoft.otryx.registry;

/**
 * 划分 Registry 实际调用图中的成员、清单和映射来源。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public enum RegistryChannel {
    /** 应用成员注册、发现与健康来源。 */
    MEMBERSHIP,
    /** 不可变应用清单来源。 */
    MANIFEST,
    /** 业务合同到应用的所有权映射来源。 */
    MAPPING
}
