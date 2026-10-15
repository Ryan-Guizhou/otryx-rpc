package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.Objects;

/**
 * 在命名空间隔离域内定位业务接口映射，ServiceKey 不用作应用实例的注册身份。
 *
 * @param namespace 受隔离的命名空间
 * @param serviceKey 接口、版本及分组的业务契约
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public record ServiceMappingKey(String namespace, ServiceKey serviceKey) {

    private static final String VALIDATION_APPLICATION = "service-mapping";

    /** 复用应用身份校验规则，避免映射与应用目录的命名空间语义漂移。 */
    public ServiceMappingKey {
        namespace = new ApplicationKey(namespace, VALIDATION_APPLICATION).namespace();
        Objects.requireNonNull(serviceKey, "serviceKey");
    }
}
