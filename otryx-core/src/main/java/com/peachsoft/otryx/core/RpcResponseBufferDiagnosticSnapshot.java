package com.peachsoft.otryx.core;

/**
 * Provider 响应编码阶段的资源水位，不表示 JVM Heap、TLS 或 DirectMemory 使用量。
 *
 * @param bufferedBytes 当前编码任务持有的数组及最终 Frame 临时预留量
 * @param highWaterBytes 运行期最大预留量
 * @param maxBufferedBytes 全局临时编码容量限制
 * @param maxResponseBytes 单次响应编码容量限制
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 18:20
 */
public record RpcResponseBufferDiagnosticSnapshot(
        long bufferedBytes, long highWaterBytes, long maxBufferedBytes, int maxResponseBytes) {

    /** 确保水位非负且不突破配置硬上限。 */
    public RpcResponseBufferDiagnosticSnapshot {
        if (bufferedBytes < 0L || highWaterBytes < bufferedBytes
                || highWaterBytes > maxBufferedBytes || maxResponseBytes <= 0
                || maxResponseBytes > maxBufferedBytes) {
            throw new IllegalArgumentException("Invalid response buffer diagnostics");
        }
    }
}
