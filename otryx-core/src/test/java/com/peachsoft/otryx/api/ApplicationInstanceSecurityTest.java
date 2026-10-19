package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 非可信实例标签不得通过默认诊断文本暴露凭据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
class ApplicationInstanceSecurityTest {
    @Test
    void toStringMustNotExposeUntrustedAttributeValues() {
        String secret = "token-marker-for-redaction";
        ApplicationInstance instance = new ApplicationInstance(
                "consumer", new ApplicationKey("development", "consumer"),
                ApplicationRole.CONSUMER, null, 0, null, Map.of("credential", secret));
        assertFalse(instance.toString().contains(secret));
    }
}
