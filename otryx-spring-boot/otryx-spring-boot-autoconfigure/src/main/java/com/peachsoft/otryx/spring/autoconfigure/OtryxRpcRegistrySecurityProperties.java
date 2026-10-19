package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistryClientKeyMaterial;
import com.peachsoft.otryx.registry.RegistryCredentials;
import com.peachsoft.otryx.registry.RegistrySecurityOptions;
import com.peachsoft.otryx.registry.RegistryTlsOptions;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 单条 Registry 访问链的有类型安全配置；Eureka 的元数据链使用独立实例。
 *
 * <p>绑定不创建 SDK。转换产生独立凭据副本，拒绝与凭据种类矛盾的多余字段；
 * 任何错误只携带固定配置键和原因，不保留含秘密输入的原始异常。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:21
 */
public final class OtryxRpcRegistrySecurityProperties {
    private Tls tls;
    private final Credentials credentials = new Credentials();

    /** 创建未配置 TLS、无凭据的绑定对象。 */
    public OtryxRpcRegistrySecurityProperties() {
    }

    /**
     * 返回独立 TLS 配置。
     *
     * @return 未提供时为 null
     */
    public Tls getTls() {
        return tls;
    }

    /**
     * 设置该访问链的 TLS 配置，不继承其他后端材料。
     *
     * @param value 可选 TLS 配置
     */
    public void setTls(Tls value) {
        tls = value;
    }

    /**
     * 返回该访问链的凭据配置。
     *
     * @return 非空凭据配置
     */
    public Credentials getCredentials() {
        return credentials;
    }

    /**
     * 校验字段组合并转换为 Core 安全参数；不读取文件或访问网络。
     *
     * @return 不可变安全参数及防御性复制的凭据
     * @throws IllegalArgumentException 字段组合或路径格式非法
     */
    public RegistrySecurityOptions toOptions() {
        try {
            return new RegistrySecurityOptions(
                    tls == null ? Optional.empty() : Optional.of(tls.toOptions()), credentials.toCredentials());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid otryx.rpc.registry.security: TLS or credentials rejected");
        }
    }

    /**
     * 返回不含路径、用户名和凭据的诊断描述。
     *
     * @return 固定脱敏描述
     */
    @Override
    public String toString() {
        return "OtryxRpcRegistrySecurityProperties[redacted]";
    }

    /** 单条访问链的信任 CA 和可选客户端材料。 */
    public static final class Tls {
        private String trustCertificateCollection = "";
        private ClientIdentity clientIdentity;

        /** 创建空 TLS 配置，使用前必须提供信任材料。 */
        public Tls() {
        }

        /**
         * 返回信任证书集合路径。
         *
         * @return PEM CA 文件路径
         */
        public String getTrustCertificateCollection() {
            return trustCertificateCollection;
        }

        /**
         * 设置该链自己的信任证书集合。
         *
         * @param value PEM CA 文件路径
         */
        public void setTrustCertificateCollection(String value) {
            trustCertificateCollection = value;
        }

        /**
         * 返回可选客户端证书材料。
         *
         * @return 未配置时为 null
         */
        public ClientIdentity getClientIdentity() {
            return clientIdentity;
        }

        /**
         * 设置该链自己的客户端证书材料。
         *
         * @param value 可选客户端材料
         */
        public void setClientIdentity(ClientIdentity value) {
            clientIdentity = value;
        }

        private RegistryTlsOptions toOptions() {
            return new RegistryTlsOptions(path(trustCertificateCollection),
                    clientIdentity == null ? Optional.empty() : Optional.of(clientIdentity.toMaterial()));
        }
    }

    /** Registry 客户端证书和私钥的配置，密码始终防御性复制。 */
    public static final class ClientIdentity {
        private String certificateChain = "";
        private String privateKey = "";
        private char[] privateKeyPassword = new char[0];

        /** 创建尚未提供材料的客户端身份配置。 */
        public ClientIdentity() {
        }

        /**
         * 返回客户端证书链路径。
         *
         * @return PEM 证书链路径
         */
        public String getCertificateChain() {
            return certificateChain;
        }

        /**
         * 设置客户端证书链路径。
         *
         * @param value PEM 证书链路径
         */
        public void setCertificateChain(String value) {
            certificateChain = value;
        }

        /**
         * 返回客户端私钥路径。
         *
         * @return PEM 私钥路径
         */
        public String getPrivateKey() {
            return privateKey;
        }

        /**
         * 设置客户端私钥路径。
         *
         * @param value PEM 私钥路径
         */
        public void setPrivateKey(String value) {
            privateKey = value;
        }

        /**
         * 返回私钥密码副本。
         *
         * @return 独立字符数组；未加密私钥使用空数组
         */
        public char[] getPrivateKeyPassword() {
            return privateKeyPassword.clone();
        }

        /**
         * 防御性复制私钥密码。
         *
         * @param value 密码字符；null 表示无密码
         */
        public void setPrivateKeyPassword(char[] value) {
            privateKeyPassword = value == null ? new char[0] : value.clone();
        }

        private RegistryClientKeyMaterial toMaterial() {
            return new RegistryClientKeyMaterial(path(certificateChain), path(privateKey), privateKeyPassword);
        }
    }

    /** 显式凭据种类与对应字段，禁止匿名 Map 或多种凭据同时生效。 */
    public static final class Credentials {
        private CredentialKind kind = CredentialKind.NONE;
        private String username = "";
        private char[] password = new char[0];
        private char[] token = new char[0];

        /** 创建无凭据配置。 */
        public Credentials() {
        }

        /**
         * 返回明确的凭据种类。
         *
         * @return 凭据种类
         */
        public CredentialKind getKind() {
            return kind;
        }

        /**
         * 设置凭据种类。
         *
         * @param value 凭据种类，使用前必须非 null
         */
        public void setKind(CredentialKind value) {
            kind = value;
        }

        /**
         * 返回用户名，仅用于生成访问凭据。
         *
         * @return 用户名
         */
        public String getUsername() {
            return username;
        }

        /**
         * 设置用户名，不作日志输出。
         *
         * @param value 用户名
         */
        public void setUsername(String value) {
            username = value;
        }

        /**
         * 返回密码的独立副本。
         *
         * @return 密码字符数组
         */
        public char[] getPassword() {
            return password.clone();
        }

        /**
         * 防御性复制密码。
         *
         * @param value 密码，null 规范化为空
         */
        public void setPassword(char[] value) {
            password = value == null ? new char[0] : value.clone();
        }

        /**
         * 返回 ACL Token 的独立副本。
         *
         * @return Token 字符数组
         */
        public char[] getToken() {
            return token.clone();
        }

        /**
         * 防御性复制 ACL Token。
         *
         * @param value Token，null 规范化为空
         */
        public void setToken(char[] value) {
            token = value == null ? new char[0] : value.clone();
        }

        private RegistryCredentials toCredentials() {
            if (kind == null) {
                throw new IllegalArgumentException("Registry credential kind is required");
            }
            boolean hasUsername = username != null && !username.isEmpty();
            boolean hasPassword = password.length > 0;
            boolean hasToken = token.length > 0;
            if (kind != CredentialKind.USER_PASSWORD && (hasUsername || hasPassword)
                    || kind != CredentialKind.ACL_TOKEN && hasToken) {
                throw new IllegalArgumentException("Registry credential fields do not match their kind");
            }
            return switch (kind) {
                case NONE -> RegistryCredentials.none();
                case USER_PASSWORD -> RegistryCredentials.userPassword(username, password);
                case ACL_TOKEN -> RegistryCredentials.aclToken(token);
                case CLIENT_CERTIFICATE -> RegistryCredentials.clientCertificate();
            };
        }

        /**
         * 返回不含用户名、密码和 Token 的描述。
         *
         * @return 固定脱敏描述
         */
        @Override
        public String toString() {
            return "RegistryCredentials[redacted]";
        }
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Registry TLS material path is required");
        }
        return Path.of(value);
    }
}
