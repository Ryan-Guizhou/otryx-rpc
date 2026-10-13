package com.peachsoft.otryx.examples.api;

/**
 * 三独立进程 Deadline 演示的受限业务 Metadata 字段和值。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
public final class ExampleDeadlineMetadata {

    /** E2E 探针标志字段。 */
    public static final String E2E_KEY = "otryx-e2e";
    /** E2E 探针启用值。 */
    public static final String E2E_VALUE = "true";
    /** 租户 Metadata 字段。 */
    public static final String TENANT_KEY = "tenant";
    /** 测试租户标记。 */
    public static final String TENANT_VALUE = "alpha";
    /** 中间节点 Metadata 字段。 */
    public static final String RELAY_KEY = "relay";
    /** 中间节点标记。 */
    public static final String RELAY_VALUE = "B";

    private ExampleDeadlineMetadata() {
    }
}
