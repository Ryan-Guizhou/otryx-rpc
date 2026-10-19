package com.peachsoft.otryx.quickstart.provider;

import com.peachsoft.otryx.quickstart.api.GreetingDetailsService;
import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 仅在显式启用时绑定的附加服务，使同一应用实际发布第二种导出清单。
 *
 * <p>不设置 Manifest Revision，不替换共同 Greeting 服务；修订版由真实
 * Spring 服务绑定后的完整导出集合计算，便于验证共存、升级与回滚。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:04
 */
@OtryxRpcService(interfaceClass = GreetingDetailsService.class, version = "1.0.0")
@ConditionalOnProperty(prefix = "otryx.rpc.quickstart", name = "details-enabled", havingValue = "true")
public final class GreetingDetailsServiceImpl implements GreetingDetailsService {

    private static final String DETAILS_REPLY = "DETAILS_OK";

    /** 创建无状态的附加服务实现。 */
    public GreetingDetailsServiceImpl() {
    }

    /**
     * 在实际 Provider 业务执行器上返回附加服务的确定性结果。
     *
     * @param request 非空请求，内容不写入日志或响应
     * @return 固定成功响应
     */
    @Override
    public GreetingReply details(GreetingRequest request) {
        Objects.requireNonNull(request, "request");
        return new GreetingReply(DETAILS_REPLY);
    }
}
