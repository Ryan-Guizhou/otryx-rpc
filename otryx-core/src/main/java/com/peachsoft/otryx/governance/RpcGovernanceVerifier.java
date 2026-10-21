package com.peachsoft.otryx.governance;

/**
 * 治理负载授权和完整性验证策略。
 *
 * <p>实现必须覆盖 issuer、revision 及全部规则内容，不能只校验更新的来源字符串。
 * 在 Core 有界控制线程执行；异常被视为安全拒绝，不落到 RPC 请求热路径。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
@FunctionalInterface
public interface RpcGovernanceVerifier {

    /**
     * 验证完整不可变配置的认证码；不允许宽松接受缺失或伪造签名。
     *
     * @param update 待验证更新
     * @return 认证成功、发行人具备策略发布权限时为 true
     */
    boolean verify(RpcGovernanceSignedUpdate update);
}
