package com.peachsoft.otryx.governance;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 仅用于控制面的治理配置来源，禁止在 RPC 调用热路径访问。
 *
 * <p>具体 Etcd/Nacos/Consul 或自定义 Adapter 负责安全传输和有界 Watch。
 * 任意更新在 Core 控制线程独立做完整签名验证、编译与原子发布。
 * watch 返回可终止订阅，关闭时必须停止后续远程回调。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
public interface RpcGovernanceRepository {

    /** 区分短暂连接故障和必须立即关闭治理通道的安全故障。 */
    enum Failure {
        /** 保留有期限的 Last-Known-Good，不刷新原快照年龄。 */
        UNAVAILABLE,
        /** 认证、授权或完整性拒绝，应立即 Fail Closed。 */
        SECURITY_REJECTED
    }

    /**
     * 在调用方的启动/控制面执行初始读取，不阻塞 RPC 请求线程。
     *
     * @return 一次完整签名快照；异常不得被当成空规则
     */
    CompletionStage<RpcGovernanceSignedUpdate> load();

    /**
     * 注册完整快照通知，须在关闭时解绑；不得发送局部字段差异。
     *
     * @param updates 完整签名事件
     * @param failures 低基数失败分类，不传递原始远程错误消息
     * @return 必须关闭的有界 Watch/订阅资源
     */
    AutoCloseable watch(Consumer<RpcGovernanceSignedUpdate> updates, Consumer<Failure> failures);
}
