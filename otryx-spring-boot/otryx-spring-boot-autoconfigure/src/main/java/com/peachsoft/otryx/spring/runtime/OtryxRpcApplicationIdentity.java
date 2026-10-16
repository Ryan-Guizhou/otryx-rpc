package com.peachsoft.otryx.spring.runtime;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import java.util.Map;
import java.util.Objects;

/**
 * 一个 Spring 进程共享的唯一应用身份，Provider/Consumer/BOTH 不按接口重复注册。
 *
 * <p>ApplicationKey 由 spring.application.name（或明确覆盖）确定，instanceId
 * 由配置指定或在进程启动时生成一次。实例必须满足 V2 ApplicationInstance
 * 的严格可打印字符和容量限制。
 *
 * @param application Namespace 与 ApplicationName 构成的确定身份
 * @param instanceId 进程存活期间唯一、固定的应用成员 ID
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 19:00
 */
public record OtryxRpcApplicationIdentity(ApplicationKey application, String instanceId) {

    /** 验证身份，以实际 Consumer 模型约束 InstanceId 格式。 */
    public OtryxRpcApplicationIdentity {
        Objects.requireNonNull(application, "application");
        instanceId = new ApplicationInstance(instanceId, application, ApplicationRole.CONSUMER,
                null, 0, null, Map.of()).instanceId();
    }
}
