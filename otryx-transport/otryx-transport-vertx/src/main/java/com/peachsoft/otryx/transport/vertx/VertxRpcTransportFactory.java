package com.peachsoft.otryx.transport.vertx;

import com.peachsoft.otryx.spi.Extension;
import com.peachsoft.otryx.transport.RpcTransportClient;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportServer;

/**
 * Vert.x TCP 传输工厂。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@Extension(VertxRpcTransportFactory.EXTENSION_NAME)
public final class VertxRpcTransportFactory implements RpcTransportFactory {

    static final String EXTENSION_NAME = "vertx";

    /**
     * 创建 Vert.x TCP 传输工厂。
     */
    public VertxRpcTransportFactory() {
    }

    @Override
    public RpcTransportClient createClient(RpcTransportOptions options) {
        return new VertxRpcTransportClient(options);
    }

    @Override
    public RpcTransportServer createServer(RpcTransportOptions options) {
        return new VertxRpcTransportServer(options);
    }
}
