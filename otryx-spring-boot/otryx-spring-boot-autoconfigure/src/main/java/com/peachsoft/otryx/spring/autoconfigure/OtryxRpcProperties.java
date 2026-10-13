package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.transport.RpcTransportAdmissionOptions;
import com.peachsoft.otryx.codec.fory.ForyRpcSecurityOptions;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OTRYX RPC Spring Boot 配置属性，统一承载 {@code otryx.rpc} 下的装配参数。
 *
 * <p>内部属性组包括 Registry、Transport、Client、Server 与 Codec。
 * 字段默认值应以当前代码和对应启动测试为准；影响 TLS、重试、背压和
 * 线程资源的配置必须验证合法范围及生效时机，不能只依赖 Javadoc。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@ConfigurationProperties(prefix = "otryx.rpc")
public class OtryxRpcProperties implements InitializingBean {

    /**
     * 创建 OTRYX RPC 配置属性。
     */
    public OtryxRpcProperties() {
    }

    /**
     * Spring 完成所有属性绑定后进行一次完整校验，并输出脱敏启动摘要。
     */
    @Override
    public void afterPropertiesSet() {
        OtryxRpcStartupDiagnostics.validateAndReport(this);
    }

    /** RPC 安全部署意图，默认不允许隐式使用不安全连接。 */
    public enum SecurityProfile {
        /** 未指定安全意图。 */
        UNSPECIFIED,
        /** 明确隔离的开发或可信内网。 */
        TRUSTED_DEVELOPMENT,
        /** 需要双向 TLS 和严格 DTO 白名单的网络部署。 */
        NETWORK_MTLS
    }

    private boolean enabled = true;
    private SecurityProfile securityProfile = SecurityProfile.UNSPECIFIED;
    private final Registry registry = new Registry();
    private final Transport transport = new Transport();
    private final Client client = new Client();
    private final Server server = new Server();
    private final Codec codec = new Codec();

    /**
     * 返回 OTRYX RPC 总开关。
     *
     * @return 是否启用 OTRYX RPC
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置 OTRYX RPC 总开关。
     *
     * @param enabled 是否启用 OTRYX RPC
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回显式安全意图。
     *
     * @return 部署安全模式
     */
    public SecurityProfile getSecurityProfile() {
        return securityProfile;
    }

    /**
     * 设置 RPC 部署安全意图。
     *
     * @param value 部署安全模式
     */
    public void setSecurityProfile(SecurityProfile value) {
        securityProfile = value;
    }

    /**
     * 返回注册中心配置。
     *
     * @return 注册中心配置
     */
    public Registry getRegistry() {
        return registry;
    }

    /**
     * 返回传输层配置。
     *
     * @return 传输层配置
     */
    public Transport getTransport() {
        return transport;
    }

    /**
     * 返回 Consumer 配置。
     *
     * @return Consumer 配置
     */
    public Client getClient() {
        return client;
    }

    /**
     * 返回 Provider 配置。
     *
     * @return Provider 配置
     */
    public Server getServer() {
        return server;
    }

    /**
     * 返回编解码安全配置。
     *
     * @return Codec 配置
     */
    public Codec getCodec() {
        return codec;
    }

    /** 编解码配置。 */
    public static class Codec {
        private final ForySecurity fory = new ForySecurity();

        /** 创建 Codec 配置。 */
        public Codec() {
        }

        /**
         * 返回 Fory 安全配置。
         *
         * @return Fory 配置
         */
        public ForySecurity getFory() {
            return fory;
        }
    }

    /** Fory Native 反序列化安全配置。 */
    public static class ForySecurity {
        private ForyRpcSecurityOptions.Mode mode =
                ForyRpcSecurityOptions.Mode.TRUSTED_COMPATIBILITY;
        private Set<String> allowedClassPatterns = new LinkedHashSet<>();
        private int maxDepth = 50;
        private long maxGraphMemoryBytes = 64L * 1024 * 1024;
        private int maxPayloadBytes = 16 * 1024 * 1024;

        /** 创建 Fory 安全配置。 */
        public ForySecurity() {
        }

        /**
         * 返回安全模式。
         *
         * @return 安全模式
         */
        public ForyRpcSecurityOptions.Mode getMode() {
            return mode;
        }

        /**
         * 设置安全模式。
         *
         * @param value 安全模式
         */
        public void setMode(ForyRpcSecurityOptions.Mode value) {
            this.mode = value;
        }

        /**
         * 返回允许反序列化的应用类及包规则。
         *
         * @return 可解码应用类名及应用包模式
         */
        public Set<String> getAllowedClassPatterns() {
            return allowedClassPatterns;
        }

        /**
         * 设置允许的应用类型或包模式。
         *
         * @param value 允许的应用类型或包模式
         */
        public void setAllowedClassPatterns(Set<String> value) {
            this.allowedClassPatterns = value;
        }

        /**
         * 返回反序列化对象图最大嵌套层数。
         *
         * @return 最大对象图嵌套深度
         */
        public int getMaxDepth() {
            return maxDepth;
        }

        /**
         * 设置最大对象图嵌套深度。
         *
         * @param value 最大对象图嵌套深度
         */
        public void setMaxDepth(int value) {
            this.maxDepth = value;
        }

        /**
         * 返回Fory 对象图内存估算上限（字节）。
         *
         * @return 最大近似图内存字节数
         */
        public long getMaxGraphMemoryBytes() {
            return maxGraphMemoryBytes;
        }

        /**
         * 设置最大近似图内存字节数。
         *
         * @param value 最大近似图内存字节数
         */
        public void setMaxGraphMemoryBytes(long value) {
            this.maxGraphMemoryBytes = value;
        }

        /**
         * 返回Fory Payload 长度限制（字节）。
         *
         * @return 最大 Fory Payload 字节数
         */
        public int getMaxPayloadBytes() {
            return maxPayloadBytes;
        }

        /**
         * 设置最大 Fory Payload 字节数。
         *
         * @param value 最大 Fory Payload 字节数
         */
        public void setMaxPayloadBytes(int value) {
            this.maxPayloadBytes = value;
        }
    }

    /**
     * 注册中心配置。
     */
    public static class Registry {

        /**
         * 创建注册中心配置。
         */
        public Registry() {
        }
        private String type = "memory";
        private String endpoints = "";
        private String namespace = "";
        private long leaseTtlSeconds = 30;
        private final Nacos nacos = new Nacos();
        private final Consul consul = new Consul();
        private final Eureka eureka = new Eureka();

        /**
         * 返回注册中心 SPI 名称。
         *
         * @return 注册中心 SPI 名称
         */
        public String getType() {
            return type;
        }

        /**
         * 设置注册中心 SPI 名称。
         *
         * @param type 注册中心 SPI 名称
         */
        public void setType(String type) {
            this.type = type;
        }

        /**
         * 返回注册中心节点地址，多个地址使用逗号分隔。
         *
         * @return 注册中心节点地址，多个地址使用逗号分隔
         */
        public String getEndpoints() {
            return endpoints;
        }

        /**
         * 设置注册中心节点地址，多个地址使用逗号分隔。
         *
         * @param endpoints Etcd 节点地址，多个地址使用逗号分隔
         */
        public void setEndpoints(String endpoints) {
            this.endpoints = endpoints;
        }

        /**
         * 返回注册中心逻辑命名空间。
         *
         * @return 注册中心逻辑命名空间
         */
        public String getNamespace() {
            return namespace;
        }

        /**
         * 设置注册中心逻辑命名空间。
         *
         * @param namespace 注册中心逻辑命名空间
         */
        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        /**
         * 返回Etcd Lease TTL，单位秒。
         *
         * @return Etcd Lease TTL，单位秒
         */
        public long getLeaseTtlSeconds() {
            return leaseTtlSeconds;
        }

        /**
         * 设置Etcd Lease TTL，单位秒。
         *
         * @param leaseTtlSeconds Etcd Lease TTL，单位秒
         */
        public void setLeaseTtlSeconds(long leaseTtlSeconds) {
            this.leaseTtlSeconds = leaseTtlSeconds;
        }

        /**
         * 返回 Nacos Adapter 配置。
         *
         * @return Nacos Adapter 配置
         */
        public Nacos getNacos() {
            return nacos;
        }

        /**
     * 返回相应配置或运行状态。
     *
     * @return Consul 专用 Registry 配置
     */
        public Consul getConsul() {
            return consul;
        }

        /**
     * 返回相应配置或运行状态。
     *
     * @return Eureka 专用 Registry 配置
     */
        public Eureka getEureka() {
            return eureka;
        }
    }

    /**
     * Consul Registry 控制面私有参数。
     *
     * @Author Ryan
     * @Version 1.0.0
     * @CreateTime 2026/10/10 18:00
     */
    public static class Consul {
        private String token = "";
        private String datacenter = "";
        private String enterpriseNamespace = "";
        private long ttlSeconds = 30;
        private long heartbeatSeconds = 10;
        private long pollIntervalMillis = 1000;
        private long requestTimeoutMillis = 3000;

        /** 创建 Consul 配置。 */
        public Consul() {
        }

        /**
     * 返回相应配置或运行状态。
     *
     * @return Consul ACL token
     */
        public String getToken() { return token; }
        /**
     * 设置对应配置项。
     *
     * @param value Consul ACL token
     */
        public void setToken(String value) { this.token = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return Consul Datacenter
     */
        public String getDatacenter() { return datacenter; }
        /**
     * 设置对应配置项。
     *
     * @param value Consul Datacenter
     */
        public void setDatacenter(String value) { this.datacenter = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return Consul Enterprise Namespace，可留空
     */
        public String getEnterpriseNamespace() { return enterpriseNamespace; }
        /**
     * 设置对应配置项。
     *
     * @param value Consul Enterprise Namespace
     */
        public void setEnterpriseNamespace(String value) { this.enterpriseNamespace = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return TTL 秒数
     */
        public long getTtlSeconds() { return ttlSeconds; }
        /**
     * 设置对应配置项。
     *
     * @param value TTL 秒数
     */
        public void setTtlSeconds(long value) { this.ttlSeconds = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return 续约周期秒数
     */
        public long getHeartbeatSeconds() { return heartbeatSeconds; }
        /**
     * 设置对应配置项。
     *
     * @param value 续约周期秒数
     */
        public void setHeartbeatSeconds(long value) { this.heartbeatSeconds = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return 轮询周期毫秒数
     */
        public long getPollIntervalMillis() { return pollIntervalMillis; }
        /**
     * 设置对应配置项。
     *
     * @param value 轮询周期毫秒数
     */
        public void setPollIntervalMillis(long value) { this.pollIntervalMillis = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return 请求超时毫秒数
     */
        public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
        /**
     * 设置对应配置项。
     *
     * @param value 请求超时毫秒数
     */
        public void setRequestTimeoutMillis(long value) { this.requestTimeoutMillis = value; }
    }

    /**
     * Eureka Registry 控制面私有参数。
     *
     * @Author Ryan
     * @Version 1.0.0
     * @CreateTime 2026/10/10 18:00
     */
    public static class Eureka {
        private String username = "";
        private String password = "";
        private long leaseSeconds = 30;
        private long heartbeatSeconds = 10;
        private long pollIntervalMillis = 1000;
        private long requestTimeoutMillis = 3000;

        /** 创建 Eureka 配置。 */
        public Eureka() {
        }

        /**
     * 返回相应配置或运行状态。
     *
     * @return Eureka Basic Auth 用户名
     */
        public String getUsername() { return username; }
        /**
     * 设置对应配置项。
     *
     * @param value Eureka Basic Auth 用户名
     */
        public void setUsername(String value) { this.username = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return Eureka Basic Auth 密码
     */
        public String getPassword() { return password; }
        /**
     * 设置对应配置项。
     *
     * @param value Eureka Basic Auth 密码
     */
        public void setPassword(String value) { this.password = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return 租约过期时间秒数
     */
        public long getLeaseSeconds() { return leaseSeconds; }
        /**
     * 设置对应配置项。
     *
     * @param value 租约过期时间秒数
     */
        public void setLeaseSeconds(long value) { this.leaseSeconds = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return 续约周期秒数
     */
        public long getHeartbeatSeconds() { return heartbeatSeconds; }
        /**
     * 设置对应配置项。
     *
     * @param value 续约周期秒数
     */
        public void setHeartbeatSeconds(long value) { this.heartbeatSeconds = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return 轮询周期毫秒数
     */
        public long getPollIntervalMillis() { return pollIntervalMillis; }
        /**
     * 设置对应配置项。
     *
     * @param value 轮询周期毫秒数
     */
        public void setPollIntervalMillis(long value) { this.pollIntervalMillis = value; }
        /**
     * 返回相应配置或运行状态。
     *
     * @return HTTP 超时毫秒数
     */
        public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
        /**
     * 设置对应配置项。
     *
     * @param value HTTP 超时毫秒数
     */
        public void setRequestTimeoutMillis(long value) { this.requestTimeoutMillis = value; }
    }

    /** Nacos Registry Adapter 配置。 */
    public static class Nacos {

        /** 创建 Nacos 配置。 */
        public Nacos() {
        }

        private String group = "OTRYX_RPC";
        private String cluster = "DEFAULT";
        private String username = "";
        private String password = "";

        /**
         * 返回 Nacos 管理分组。
         *
         * @return Nacos Group
         */
        public String getGroup() {
            return group;
        }

        /**
         * 设置 Nacos 管理分组。
         *
         * @param group Nacos Group
         */
        public void setGroup(String group) {
            this.group = group;
        }

        /**
         * 返回 Nacos 集群名称。
         *
         * @return Nacos Cluster
         */
        public String getCluster() {
            return cluster;
        }

        /**
         * 设置 Nacos 集群名称。
         *
         * @param cluster Nacos Cluster
         */
        public void setCluster(String cluster) {
            this.cluster = cluster;
        }

        /**
         * 返回 Nacos 用户名。
         *
         * @return Nacos 用户名
         */
        public String getUsername() {
            return username;
        }

        /**
         * 设置 Nacos 用户名。
         *
         * @param username Nacos 用户名
         */
        public void setUsername(String username) {
            this.username = username;
        }

        /**
         * 返回 Nacos 密码。
         *
         * @return Nacos 密码
         */
        public String getPassword() {
            return password;
        }

        /**
         * 设置 Nacos 密码。
         *
         * @param password Nacos 密码
         */
        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * 传输层配置。
     */
    public static class Transport {

        /**
         * 创建传输层配置。
         */
        public Transport() {
        }
        private String type = "vertx";
        private int maxInflightPerConnection = 1024;
        private int maxFrameBytes = 16 * 1024 * 1024;
        private int maxWriteQueueBytes = 4 * 1024 * 1024;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration handshakeTimeout = Duration.ofSeconds(3);
        private int connectionsPerEndpoint = 1;
        private Duration heartbeatInterval = Duration.ofSeconds(30);
        private Duration heartbeatTimeout = Duration.ofSeconds(10);
        private Duration reconnectBaseBackoff = Duration.ofMillis(50);
        private Duration reconnectMaxBackoff = Duration.ofSeconds(3);
        private final Security security = new Security();
        private final TransportAdmission admission = new TransportAdmission();

        /**
         * 返回Transport SPI 名称。
         *
         * @return Transport SPI 名称
         */
        public String getType() {
            return type;
        }

        /**
         * 设置Transport SPI 名称。
         *
         * @param type Transport SPI 名称
         */
        public void setType(String type) {
            this.type = type;
        }

        /**
         * 返回单连接最大并发请求数。
         *
         * @return 单连接最大并发请求数
         */
        public int getMaxInflightPerConnection() {
            return maxInflightPerConnection;
        }

        /**
         * 设置单连接最大并发请求数。
         *
         * @param maxInflightPerConnection 单连接最大并发请求数
         */
        public void setMaxInflightPerConnection(int maxInflightPerConnection) {
            this.maxInflightPerConnection = maxInflightPerConnection;
        }

        /**
         * 返回单帧最大字节数。
         *
         * @return 单帧最大字节数
         */
        public int getMaxFrameBytes() {
            return maxFrameBytes;
        }

        /**
         * 设置单帧最大字节数。
         *
         * @param maxFrameBytes 单帧最大字节数
         */
        public void setMaxFrameBytes(int maxFrameBytes) {
            this.maxFrameBytes = maxFrameBytes;
        }

        /**
         * 返回单连接写队列最大字节数。
         *
         * @return 单连接写队列最大字节数
         */
        public int getMaxWriteQueueBytes() {
            return maxWriteQueueBytes;
        }

        /**
         * 设置单连接写队列最大字节数。
         *
         * @param maxWriteQueueBytes 单连接写队列最大字节数
         */
        public void setMaxWriteQueueBytes(int maxWriteQueueBytes) {
            this.maxWriteQueueBytes = maxWriteQueueBytes;
        }

        /**
         * 返回协议握手超时时间。
         *
         * @return 协议握手超时时间
         */
        public Duration getHandshakeTimeout() {
            return handshakeTimeout;
        }

        /**
         * 设置协议握手超时时间。
         *
         * @param handshakeTimeout 协议握手超时时间
         */
        public void setHandshakeTimeout(Duration handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
        }

        /**
         * 返回每个服务端点的连接分片数。
         *
         * @return 每个服务端点的连接分片数
         */
        public int getConnectionsPerEndpoint() {
            return connectionsPerEndpoint;
        }

        /**
         * 设置每个服务端点的连接分片数。
         *
         * @param connectionsPerEndpoint 每个服务端点的连接分片数
         */
        public void setConnectionsPerEndpoint(int connectionsPerEndpoint) {
            this.connectionsPerEndpoint = connectionsPerEndpoint;
        }

        /**
         * 返回TCP 建连超时时间。
         *
         * @return TCP 建连超时时间
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * 设置TCP 建连超时时间。
         *
         * @param connectTimeout TCP 建连超时时间
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * 返回空闲连接发送心跳前的间隔。
         *
         * @return 心跳间隔
         */
        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        /**
         * 设置空闲连接发送心跳前的间隔。
         *
         * @param heartbeatInterval 心跳间隔
         */
        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        /**
         * 返回心跳响应最大等待时间。
         *
         * @return 心跳超时
         */
        public Duration getHeartbeatTimeout() {
            return heartbeatTimeout;
        }

        /**
         * 设置心跳响应最大等待时间。
         *
         * @param heartbeatTimeout 心跳超时
         */
        public void setHeartbeatTimeout(Duration heartbeatTimeout) {
            this.heartbeatTimeout = heartbeatTimeout;
        }

        /**
         * 返回连接异常后的基础重连退避。
         *
         * @return 基础重连退避
         */
        public Duration getReconnectBaseBackoff() {
            return reconnectBaseBackoff;
        }

        /**
         * 设置连接异常后的基础重连退避。
         *
         * @param reconnectBaseBackoff 基础重连退避
         */
        public void setReconnectBaseBackoff(Duration reconnectBaseBackoff) {
            this.reconnectBaseBackoff = reconnectBaseBackoff;
        }

        /**
         * 返回连接异常后的最大重连退避窗口。
         *
         * @return 最大重连退避
         */
        public Duration getReconnectMaxBackoff() {
            return reconnectMaxBackoff;
        }

        /**
         * 设置连接异常后的最大重连退避窗口。
         *
         * @param reconnectMaxBackoff 最大重连退避
         */
        public void setReconnectMaxBackoff(Duration reconnectMaxBackoff) {
            this.reconnectMaxBackoff = reconnectMaxBackoff;
        }

        /**
         * 返回 Transport 安全配置。
         *
         * @return TLS/mTLS 配置
         */
        public Security getSecurity() {
            return security;
        }

        /**
         * 返回 Transport 连接、协议握手和部分帧缓存预算。
         *
         * @return Transport 准入参数
         */
        public TransportAdmission getAdmission() {
            return admission;
        }
    }

    /**
     * Transport 准入配置，当前 U03-A 启用连接与协议握手限额。
     */
    public static class TransportAdmission {
        private int maxConnections =
                RpcTransportAdmissionOptions.DEFAULT.maxConnections();
        private int maxHandshakes =
                RpcTransportAdmissionOptions.DEFAULT.maxHandshakes();
        private long maxBufferedPartialFrameBytes =
                RpcTransportAdmissionOptions.DEFAULT.maxBufferedPartialFrameBytes();
        private Duration partialFrameTimeout =
                RpcTransportAdmissionOptions.DEFAULT.partialFrameTimeout();
        private long maxBufferedResponseBytes =
                RpcTransportAdmissionOptions.DEFAULT.maxBufferedResponseBytes();

        /** 创建准入配置。 */
        public TransportAdmission() {
        }

        /**
         * 返回单个 Provider 实例已接入的最大连接数。
         *
         * @return 最大已接入连接数
         */
        public int getMaxConnections() {
            return maxConnections;
        }

        /**
         * 设置单个 Provider 实例已接入的最大连接数。
         *
         * @param value 最大已接入连接数
         */
        public void setMaxConnections(int value) {
            maxConnections = value;
        }

        /**
         * 返回并发 OTRYX HELLO 握手容量。
         *
         * @return 并发 HELLO 握手限额
         */
        public int getMaxHandshakes() {
            return maxHandshakes;
        }

        /**
         * 设置并发 OTRYX HELLO 握手容量。
         *
         * @param value 并发 HELLO 握手限额
         */
        public void setMaxHandshakes(int value) {
            maxHandshakes = value;
        }

        /**
         * 返回预留的全局不完整帧内存预算。
         *
         * @return 全局不完整帧预算（字节）
         */
        public long getMaxBufferedPartialFrameBytes() {
            return maxBufferedPartialFrameBytes;
        }

        /**
         * 设置预留的全局不完整帧内存预算。
         *
         * @param value 全局不完整帧预算（字节）
         */
        public void setMaxBufferedPartialFrameBytes(long value) {
            maxBufferedPartialFrameBytes = value;
        }

        /**
         * 返回预留的不完整帧最大驻留时间。
         *
         * @return 不完整帧最大驻留时间
         */
        public Duration getPartialFrameTimeout() {
            return partialFrameTimeout;
        }

        /**
         * 设置预留的不完整帧最大驻留时间。
         *
         * @param value 不完整帧最大驻留时间
         */
        public void setPartialFrameTimeout(Duration value) {
            partialFrameTimeout = value;
        }

        /**
         * 返回同 Transport Server 已编码响应的最大待写字节容量。
         *
         * @return 最大待写响应字节数
         */
        public long getMaxBufferedResponseBytes() {
            return maxBufferedResponseBytes;
        }

        /**
         * 设置同 Transport Server 已编码响应的最大待写字节容量。
         *
         * @param value 最大待写响应字节数
         */
        public void setMaxBufferedResponseBytes(long value) {
            maxBufferedResponseBytes = value;
        }
    }

    /** Transport TLS/mTLS 配置。 */
    public static class Security {

        /** 创建安全配置。 */
        public Security() {
        }

        private RpcSecurityMode mode =
                RpcSecurityMode.PLAINTEXT;
        private String certificatePath = "";
        private String privateKeyPath = "";
        private String trustCertificatePath = "";
        private boolean hostnameVerification = true;
        private Duration handshakeTimeout =
                Duration.ofSeconds(3);
        private Duration reloadInterval =
                Duration.ofSeconds(30);
        private Duration expiryWarningThreshold =
                Duration.ofDays(7);

        /**
         * 返回 Transport 安全模式。
         *
         * @return 安全模式
         */
        public RpcSecurityMode getMode() {
            return mode;
        }

        /**
         * 设置 Transport 安全模式。
         *
         * @param mode 安全模式
         */
        public void setMode(RpcSecurityMode mode) {
            this.mode = mode;
        }

        /**
         * 返回 PEM 证书路径。
         *
         * @return PEM 证书路径
         */
        public String getCertificatePath() {
            return certificatePath;
        }

        /**
         * 设置 PEM 证书路径。
         *
         * @param certificatePath PEM 证书路径
         */
        public void setCertificatePath(
                String certificatePath) {
            this.certificatePath = certificatePath;
        }

        /**
         * 返回 PEM 私钥路径。
         *
         * @return PEM 私钥路径
         */
        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        /**
         * 设置 PEM 私钥路径。
         *
         * @param privateKeyPath PEM 私钥路径
         */
        public void setPrivateKeyPath(
                String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        /**
         * 返回 PEM CA/信任证书路径。
         *
         * @return PEM CA/信任证书路径
         */
        public String getTrustCertificatePath() {
            return trustCertificatePath;
        }

        /**
         * 设置 PEM CA/信任证书路径。
         *
         * @param trustCertificatePath PEM CA/信任证书路径
         */
        public void setTrustCertificatePath(
                String trustCertificatePath) {
            this.trustCertificatePath =
                    trustCertificatePath;
        }

        /**
         * 返回是否启用 Hostname Verification。
         *
         * @return 是否启用 Hostname Verification
         */
        public boolean isHostnameVerification() {
            return hostnameVerification;
        }

        /**
         * 设置是否启用 Hostname Verification。
         *
         * @param hostnameVerification 是否校验主机名
         */
        public void setHostnameVerification(
                boolean hostnameVerification) {
            this.hostnameVerification =
                    hostnameVerification;
        }

        /**
         * 返回 TLS 握手超时。
         *
         * @return TLS 握手超时
         */
        public Duration getHandshakeTimeout() {
            return handshakeTimeout;
        }

        /**
         * 设置 TLS 握手超时。
         *
         * @param handshakeTimeout TLS 握手超时
         */
        public void setHandshakeTimeout(
                Duration handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
        }

        /**
         * 返回证书 Reload 检查周期。
         *
         * @return 证书 Reload 检查周期
         */
        public Duration getReloadInterval() {
            return reloadInterval;
        }

        /**
         * 设置证书 Reload 检查周期。
         *
         * @param reloadInterval Reload 检查周期
         */
        public void setReloadInterval(
                Duration reloadInterval) {
            this.reloadInterval = reloadInterval;
        }

        /**
         * 返回证书过期前告警窗口。
         *
         * @return 证书过期前告警窗口
         */
        public Duration getExpiryWarningThreshold() {
            return expiryWarningThreshold;
        }

        /**
         * 设置证书过期前告警窗口。
         *
         * @param expiryWarningThreshold 过期告警窗口
         */
        public void setExpiryWarningThreshold(
                Duration expiryWarningThreshold) {
            this.expiryWarningThreshold =
                    expiryWarningThreshold;
        }
    }

    /**
     * Consumer 配置。
     */
    public static class Client {

        /**
         * 创建Consumer配置。
         */
        public Client() {
        }
        private boolean enabled = true;
        private Duration timeout = Duration.ofSeconds(3);
        private Map<String, Duration> methodTimeouts = Map.of();
        private String loadBalancer = "p2c-ewma";
        private String proxy = "jdk";
        private final Resilience resilience = new Resilience();

        /**
         * 返回是否启用 Consumer。
         *
         * @return 是否启用 Consumer
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用 Consumer。
         *
         * @param enabled 是否启用 Consumer
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 返回默认 RPC 调用超时时间。
         *
         * @return 默认 RPC 调用超时时间
         */
        public Duration getTimeout() {
            return timeout;
        }

        /**
         * 设置默认 RPC 调用超时时间。
         *
         * @param timeout 默认 RPC 调用超时时间
         */
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * 返回负载均衡 SPI 名称。
         *
         * @return 负载均衡 SPI 名称
         */
        /**
         * 返回以 Method.toGenericString() 为键的方法超时覆盖。
         *
         * @return 方法超时映射
         */
        public Map<String, Duration> getMethodTimeouts() {
            return methodTimeouts;
        }

        /**
         * 设置方法级默认超时。
         *
         * @param methodTimeouts 方法签名与 Duration 映射
         */
        public void setMethodTimeouts(Map<String, Duration> methodTimeouts) {
            this.methodTimeouts = Map.copyOf(methodTimeouts);
        }

        public String getLoadBalancer() {
            return loadBalancer;
        }

        /**
         * 设置负载均衡 SPI 名称。
         *
         * @param loadBalancer 负载均衡 SPI 名称
         */
        public void setLoadBalancer(String loadBalancer) {
            this.loadBalancer = loadBalancer;
        }

        /**
         * 返回Proxy SPI 名称。
         *
         * @return Proxy SPI 名称
         */
        public String getProxy() {
            return proxy;
        }

        /**
         * 设置Proxy SPI 名称。
         *
         * @param proxy Proxy SPI 名称
         */
        public void setProxy(String proxy) {
            this.proxy = proxy;
        }

        /**
         * 返回 Consumer 容错配置。
         *
         * @return Consumer 容错配置
         */
        public Resilience getResilience() {
            return resilience;
        }
    }

    /** Consumer 重试、异常实例剔除与熔断配置。 */
    public static class Resilience {

        /** 创建 Consumer 容错配置。 */
        public Resilience() {
        }

        private int maxAttempts = 2;
        private double retryBudgetRatio = 0.10d;
        private int retryBudgetMinRetries = 10;
        private int retryBudgetMaxRetries = 100;
        private Duration retryBaseBackoff = Duration.ofMillis(10);
        private Duration retryMaxBackoff = Duration.ofMillis(100);
        private int outlierConsecutiveFailureThreshold = 5;
        private Duration outlierEjectionDuration = Duration.ofSeconds(30);
        private int circuitConsecutiveFailureThreshold = 20;
        private Duration circuitOpenDuration = Duration.ofSeconds(10);

        /**
         * 返回单次逻辑调用最大尝试次数，包含首次调用。
         *
         * @return 单次逻辑调用最大尝试次数
         */
        public int getMaxAttempts() { return maxAttempts; }
        /**
         * 设置单次逻辑调用最大尝试次数，包含首次调用。
         *
         * @param value 单次逻辑调用最大尝试次数
         */
        public void setMaxAttempts(int value) { maxAttempts = value; }
        /**
         * 返回每个原始请求补充的重试额度比例。
         *
         * @return 重试额度比例
         */
        public double getRetryBudgetRatio() { return retryBudgetRatio; }
        /**
         * 设置每个原始请求补充的重试额度比例。
         *
         * @param value 重试额度比例
         */
        public void setRetryBudgetRatio(double value) { retryBudgetRatio = value; }
        /**
         * 返回初始最低重试额度。
         *
         * @return 初始最低重试额度
         */
        public int getRetryBudgetMinRetries() { return retryBudgetMinRetries; }
        /**
         * 设置初始最低重试额度。
         *
         * @param value 初始最低重试额度
         */
        public void setRetryBudgetMinRetries(int value) { retryBudgetMinRetries = value; }
        /**
         * 返回最大累计重试额度。
         *
         * @return 最大累计重试额度
         */
        public int getRetryBudgetMaxRetries() { return retryBudgetMaxRetries; }
        /**
         * 设置最大累计重试额度。
         *
         * @param value 最大累计重试额度
         */
        public void setRetryBudgetMaxRetries(int value) { retryBudgetMaxRetries = value; }
        /**
         * 返回首次重试最大退避窗口。
         *
         * @return 首次重试最大退避窗口
         */
        public Duration getRetryBaseBackoff() { return retryBaseBackoff; }
        /**
         * 设置首次重试最大退避窗口。
         *
         * @param value 首次重试最大退避窗口
         */
        public void setRetryBaseBackoff(Duration value) { retryBaseBackoff = value; }
        /**
         * 返回最大重试退避窗口。
         *
         * @return 最大重试退避窗口
         */
        public Duration getRetryMaxBackoff() { return retryMaxBackoff; }
        /**
         * 设置最大重试退避窗口。
         *
         * @param value 最大重试退避窗口
         */
        public void setRetryMaxBackoff(Duration value) { retryMaxBackoff = value; }
        /**
         * 返回连续基础设施失败的端点剔除阈值。
         *
         * @return 端点剔除阈值
         */
        public int getOutlierConsecutiveFailureThreshold() {
            return outlierConsecutiveFailureThreshold;
        }
        /**
         * 设置连续基础设施失败的端点剔除阈值。
         *
         * @param value 端点剔除阈值
         */
        public void setOutlierConsecutiveFailureThreshold(int value) {
            outlierConsecutiveFailureThreshold = value;
        }
        /**
         * 返回端点临时剔除时间。
         *
         * @return 端点临时剔除时间
         */
        public Duration getOutlierEjectionDuration() { return outlierEjectionDuration; }
        /**
         * 设置端点临时剔除时间。
         *
         * @param value 端点临时剔除时间
         */
        public void setOutlierEjectionDuration(Duration value) {
            outlierEjectionDuration = value;
        }
        /**
         * 返回方法连续基础设施失败熔断阈值。
         *
         * @return 方法熔断阈值
         */
        public int getCircuitConsecutiveFailureThreshold() {
            return circuitConsecutiveFailureThreshold;
        }
        /**
         * 设置方法连续基础设施失败熔断阈值。
         *
         * @param value 方法熔断阈值
         */
        public void setCircuitConsecutiveFailureThreshold(int value) {
            circuitConsecutiveFailureThreshold = value;
        }
        /**
         * 返回熔断打开时间。
         *
         * @return 熔断打开时间
         */
        public Duration getCircuitOpenDuration() { return circuitOpenDuration; }
        /**
         * 设置熔断打开时间。
         *
         * @param value 熔断打开时间
         */
        public void setCircuitOpenDuration(Duration value) { circuitOpenDuration = value; }
    }

    /**
     * Provider 配置。
     */
    public static class Server {

        /**
         * 创建Provider配置。
         */
        public Server() {
        }
        private boolean enabled = true;
        private String host = "0.0.0.0";
        private int port = 19090;
        private String advertisedHost = "";
        private int advertisedPort;
        private int maxConcurrent = 4096;
        private Duration drainTimeout = Duration.ofSeconds(30);
        private Duration controlPlaneTimeout = Duration.ofSeconds(3);
        private Duration shutdownTimeout = Duration.ofSeconds(25);
        private final Execution execution = new Execution();
        private final Admission admission = new Admission();
        private final Authorization authorization = new Authorization();

        /**
         * 返回是否启用 Provider。
         *
         * @return 是否启用 Provider
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用 Provider。
         *
         * @param enabled 是否启用 Provider
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 返回Provider 监听地址。
         *
         * @return Provider 监听地址
         */
        public String getHost() {
            return host;
        }

        /**
         * 设置Provider 监听地址。
         *
         * @param host Provider 监听地址
         */
        public void setHost(String host) {
            this.host = host;
        }

        /**
         * 返回Provider 监听端口。
         *
         * @return Provider 监听端口
         */
        public int getPort() {
            return port;
        }

        /**
         * 设置Provider 监听端口。
         *
         * @param port Provider 监听端口
         */
        public void setPort(int port) {
            this.port = port;
        }

        /**
         * 返回 Provider 对外发布主机地址。
         *
         * @return 对外发布主机地址
         */
        public String getAdvertisedHost() {
            return advertisedHost;
        }

        /**
         * 设置 Provider 对外发布主机地址。
         *
         * @param advertisedHost 对外发布主机地址
         */
        public void setAdvertisedHost(String advertisedHost) {
            this.advertisedHost = advertisedHost;
        }

        /**
         * 返回 Provider 对外发布端口。
         *
         * @return 对外发布端口，0 表示使用实际监听端口
         */
        public int getAdvertisedPort() {
            return advertisedPort;
        }

        /**
         * 设置 Provider 对外发布端口。
         *
         * @param advertisedPort 对外发布端口，0 表示使用实际监听端口
         */
        public void setAdvertisedPort(int advertisedPort) {
            this.advertisedPort = advertisedPort;
        }

        /**
         * 返回Provider 最大并发业务请求数。
         *
         * @return Provider 最大并发业务请求数
         */
        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        /**
         * 设置Provider 最大并发业务请求数。
         *
         * @param maxConcurrent Provider 最大并发业务请求数
         */
        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        /**
         * 返回 Provider 优雅排空超时时间。
         *
         * @return 优雅排空超时时间
         */
        public Duration getDrainTimeout() {
            return drainTimeout;
        }

        /**
         * 设置 Provider 优雅排空超时时间。
         *
         * @param drainTimeout 优雅排空超时时间
         */
        public void setDrainTimeout(Duration drainTimeout) {
            this.drainTimeout = drainTimeout;
        }

        /**
         * 返回 Registry 等控制面操作最大等待时间。
         *
         * @return 控制面操作超时时间
         */
        public Duration getControlPlaneTimeout() {
            return controlPlaneTimeout;
        }

        /**
         * 设置 Registry 等控制面操作最大等待时间。
         *
         * @param controlPlaneTimeout 控制面操作超时时间
         */
        public void setControlPlaneTimeout(Duration controlPlaneTimeout) {
            this.controlPlaneTimeout = controlPlaneTimeout;
        }

        /**
         * 返回 Provider 总关闭预算。Spring 默认 25 秒以适应默认 30 秒阶段超时。
         *
         * @return 所有清理阶段共享的总预算
         */
        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        /**
         * 设置 Provider 总关闭预算，必须为正值且不超过一天。
         *
         * @param shutdownTimeout 总关闭预算
         */
        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }

        /**
         * 返回 Provider 执行资源配置。
         *
         * @return Provider 执行资源配置
         */
        public Execution getExecution() {
            return execution;
        }

        /**
         * 返回 Provider 并发与在途请求字节准入策略。
         *
         * @return Provider Admission 参数
         */
        public Admission getAdmission() {
            return admission;
        }

        /**
         * 返回非阻塞认证授权的资源预算。
         *
         * @return Provider 授权设置
         */
        public Authorization getAuthorization() {
            return authorization;
        }
    }

    /**
     * Provider 解码前认证授权的容量和逻辑超时。
     *
     * @Author OpenAI
     * @Version 1.0.0-SNAPSHOT
     * @CreateTime 2026/10/13 13:43
     */
    public static class Authorization {
        private int maxPending = 1024;
        private long maxBufferedRequestBytes = 64L * 1024L * 1024L;
        private Duration timeout = Duration.ofSeconds(3);

        /** 创建 Provider 授权配置。 */
        public Authorization() {
        }

        /**
         * 返回最大未完成授权数。
         *
         * @return 同时授权并发数
         */
        public int getMaxPending() {
            return maxPending;
        }

        /**
         * 设置最大未完成授权数。
         *
         * @param value 授权容量
         */
        public void setMaxPending(int value) {
            maxPending = value;
        }

        /**
         * 返回授权阶段允许持有的原始请求 Frame 总字节数。
         *
         * @return 待授权 Frame 字节预算
         */
        public long getMaxBufferedRequestBytes() {
            return maxBufferedRequestBytes;
        }

        /**
         * 设置授权阶段原始请求 Frame 字节预算。
         *
         * @param value 待授权 Frame 字节上限
         */
        public void setMaxBufferedRequestBytes(long value) {
            maxBufferedRequestBytes = value;
        }

        /**
         * 返回授权逻辑超时。
         *
         * @return 授权超时
         */
        public Duration getTimeout() {
            return timeout;
        }

        /**
         * 设置授权逻辑超时。
         *
         * @param value 授权超时
         */
        public void setTimeout(Duration value) {
            timeout = value;
        }
    }

    /**
     * Provider 分层请求并发及 Frame 字节预算配置。
     *
     * <p>默认每个服务在启动阶段获得独立的固定并发/字节份额。
     * 服务和方法硬上限中的 0 表示自动使用分配的上限。
     */
    public static class Admission {
        private long maxInflightBytes = 256L * 1024L * 1024L;
        private int maxConcurrentPerService;
        private int maxConcurrentPerMethod;
        private long maxInflightBytesPerService;
        private long maxInflightBytesPerMethod;

        /** 创建 Provider Admission 配置。 */
        public Admission() {
        }

        /**
         * 返回全局已准入请求 Frame 总字节预算。
         *
         * @return 全局在途 Frame 字节上限
         */
        public long getMaxInflightBytes() {
            return maxInflightBytes;
        }

        /**
         * 设置全局请求 Frame 字节预算。
         *
         * @param value 全局在途 Frame 字节上限
         */
        public void setMaxInflightBytes(long value) {
            this.maxInflightBytes = value;
        }

        /**
         * 返回每个服务的并发上限。
         *
         * @return 并发上限，0 表示按启动服务数自动分配
         */
        public int getMaxConcurrentPerService() {
            return maxConcurrentPerService;
        }

        /**
         * 设置每个服务的并发上限。
         *
         * @param value 并发上限，0 表示自动分配
         */
        public void setMaxConcurrentPerService(int value) {
            this.maxConcurrentPerService = value;
        }

        /**
         * 返回单个服务方法的并发上限。
         *
         * @return 方法并发上限，0 表示沿用服务上限
         */
        public int getMaxConcurrentPerMethod() {
            return maxConcurrentPerMethod;
        }

        /**
         * 设置单个服务方法的并发上限。
         *
         * @param value 方法并发上限，0 表示沿用服务上限
         */
        public void setMaxConcurrentPerMethod(int value) {
            this.maxConcurrentPerMethod = value;
        }

        /**
         * 返回每个服务的 Frame 字节预算上限。
         *
         * @return 字节上限，0 表示自动分配
         */
        public long getMaxInflightBytesPerService() {
            return maxInflightBytesPerService;
        }

        /**
         * 设置每个服务的 Frame 字节预算上限。
         *
         * @param value 字节上限，0 表示自动分配
         */
        public void setMaxInflightBytesPerService(long value) {
            this.maxInflightBytesPerService = value;
        }

        /**
         * 返回每个方法的 Frame 字节预算上限。
         *
         * @return 字节上限，0 表示沿用服务上限
         */
        public long getMaxInflightBytesPerMethod() {
            return maxInflightBytesPerMethod;
        }

        /**
         * 设置每个方法的 Frame 字节预算上限。
         *
         * @param value 字节上限，0 表示沿用服务上限
         */
        public void setMaxInflightBytesPerMethod(long value) {
            this.maxInflightBytesPerMethod = value;
        }
    }

    /** Provider 业务执行资源配置。 */
    public static class Execution {

        /** 创建 Provider 执行资源配置。 */
        public Execution() {
        }

        private boolean allowDirect;
        private int cpuParallelism =
                Math.max(1, Runtime.getRuntime().availableProcessors());
        private int cpuQueueCapacity = 1024;

        /**
         * 返回是否允许 DIRECT 方法运行在 Transport Event Loop。
         *
         * @return 是否允许 DIRECT
         */
        public boolean isAllowDirect() {
            return allowDirect;
        }

        /**
         * 设置是否允许 DIRECT 方法运行在 Transport Event Loop。
         *
         * @param allowDirect 是否允许 DIRECT
         */
        public void setAllowDirect(boolean allowDirect) {
            this.allowDirect = allowDirect;
        }

        /**
         * 返回 CPU 执行池线程数。
         *
         * @return CPU 执行池线程数
         */
        public int getCpuParallelism() {
            return cpuParallelism;
        }

        /**
         * 设置 CPU 执行池线程数。
         *
         * @param cpuParallelism CPU 执行池线程数
         */
        public void setCpuParallelism(int cpuParallelism) {
            this.cpuParallelism = cpuParallelism;
        }

        /**
         * 返回 CPU 执行池有界队列容量。
         *
         * @return CPU 执行池队列容量
         */
        public int getCpuQueueCapacity() {
            return cpuQueueCapacity;
        }

        /**
         * 设置 CPU 执行池有界队列容量。
         *
         * @param cpuQueueCapacity CPU 执行池队列容量
         */
        public void setCpuQueueCapacity(int cpuQueueCapacity) {
            this.cpuQueueCapacity = cpuQueueCapacity;
        }
    }
}
