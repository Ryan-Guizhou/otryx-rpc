package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.api.ApplicationKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Etcd 应用成员、不可变 Manifest 与实例 Mapping Owner 的独立键空间。
 *
 * <p>所有可变用户字段使用 URL-safe Base64 编码，禁止分隔符注入和跨命名空间遍历。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
final class EtcdV2Paths {

    private static final String ROOT = "/otryx/v2/ns/";
    private static final String APPS = "apps/";
    private static final String MEMBERS = "members/";
    private static final String MANIFESTS = "manifests/";
    private static final String OWNERS = "owners/";

    private final String namespace;
    private final String root;

    EtcdV2Paths(String namespace) {
        this.namespace = new ApplicationKey(namespace, "registry").namespace();
        this.root = ROOT + segment(namespace) + '/';
    }

    /** 校验调用的应用与适配器的隔离命名空间一致。 */
    void requireNamespace(ApplicationKey application) {
        Objects.requireNonNull(application, "application");
        if (!namespace.equals(application.namespace())) {
            throw new IllegalArgumentException("Etcd namespace does not match application identity");
        }
    }

    /** 校验服务映射使用相同 Namespace。 */
    void requireNamespace(String expected) {
        if (!namespace.equals(expected)) {
            throw new IllegalArgumentException("Etcd namespace does not match mapping identity");
        }
    }

    String memberPrefix(ApplicationKey application) {
        requireNamespace(application);
        return root + APPS + segment(application.applicationName()) + '/' + MEMBERS;
    }

    String memberKey(ApplicationKey application, String instanceId) {
        return memberPrefix(application) + segment(instanceId);
    }

    String namespaceMemberPrefix() {
        return root + APPS;
    }

    String manifestKey(ApplicationKey application, String revision) {
        requireNamespace(application);
        return root + APPS + segment(application.applicationName()) + '/'
                + MANIFESTS + segment(revision);
    }

    String ownerPrefix() {
        return root + OWNERS;
    }

    String ownerKey(ApplicationKey application, String instanceId) {
        requireNamespace(application);
        return ownerPrefix() + segment(application.applicationName()) + '/' + segment(instanceId);
    }

    static String segment(String value) {
        Objects.requireNonNull(value, "key component");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Blank Etcd key component");
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
