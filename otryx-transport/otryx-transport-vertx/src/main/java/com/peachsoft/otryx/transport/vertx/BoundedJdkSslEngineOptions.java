package com.peachsoft.otryx.transport.vertx;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.spi.tls.SslContextFactory;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * 为每次实际创建的 JDK TLS Context 安装有限会话缓存。
 *
 * <p>私有会话证明会使 JSSE 使用有状态恢复，因此初始、复制及重载均显式
 * 设置正容量和 TTL；不依赖可被全局属性改为无限的默认值。不限制握手前 FD。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:20
 */
class BoundedJdkSslEngineOptions extends JdkSSLEngineOptions {
    static final int SESSION_CACHE_ENTRIES = 1024;
    static final int SESSION_TIMEOUT_SECONDS = 300;

    BoundedJdkSslEngineOptions() {
    }

    BoundedJdkSslEngineOptions(BoundedJdkSslEngineOptions other) {
        super(other);
    }

    @Override
    public BoundedJdkSslEngineOptions copy() {
        return new BoundedJdkSslEngineOptions(this);
    }

    @Override
    public SslContextFactory sslContextFactory() {
        return new BoundedContextFactory(super.sslContextFactory());
    }

    private static final class BoundedContextFactory implements SslContextFactory {
        private SslContextFactory delegate;

        private BoundedContextFactory(SslContextFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public SslContextFactory useAlpn(boolean value) {
            delegate = delegate.useAlpn(value);
            return this;
        }

        @Override
        public SslContextFactory clientAuth(ClientAuth value) {
            delegate = delegate.clientAuth(value);
            return this;
        }

        @Override
        public SslContextFactory forClient(boolean value) {
            delegate = delegate.forClient(value);
            return this;
        }

        @Override
        public SslContextFactory keyMananagerFactory(KeyManagerFactory value) {
            delegate = delegate.keyMananagerFactory(value);
            return this;
        }

        @Override
        public SslContextFactory trustManagerFactory(TrustManagerFactory value) {
            delegate = delegate.trustManagerFactory(value);
            return this;
        }

        @Override
        public SslContextFactory enabledCipherSuites(Set<String> value) {
            delegate = delegate.enabledCipherSuites(value);
            return this;
        }

        @Override
        public SslContextFactory applicationProtocols(List<String> value) {
            delegate = delegate.applicationProtocols(value);
            return this;
        }

        @Override
        public SslContextFactory serverName(String value) {
            delegate = delegate.serverName(value);
            return this;
        }

        @Override
        public SslContext create() throws SSLException {
            SslContext context = delegate.create();
            if (!(context instanceof JdkSslContext jdk)
                    || !"SunJSSE".equals(jdk.context().getProvider().getName())) {
                throw new SSLException("Verified SunJSSE TLS provider is required");
            }
            configureSessions(jdk.context().getClientSessionContext());
            configureSessions(jdk.context().getServerSessionContext());
            return context;
        }

        private static void configureSessions(SSLSessionContext sessions) throws SSLException {
            if (sessions == null) {
                throw new SSLException("JDK TLS session context is unavailable");
            }
            sessions.setSessionCacheSize(SESSION_CACHE_ENTRIES);
            sessions.setSessionTimeout(SESSION_TIMEOUT_SECONDS);
            if (sessions.getSessionCacheSize() != SESSION_CACHE_ENTRIES
                    || sessions.getSessionTimeout() != SESSION_TIMEOUT_SECONDS) {
                throw new SSLException("Finite JDK TLS session cache is required");
            }
        }
    }
}

