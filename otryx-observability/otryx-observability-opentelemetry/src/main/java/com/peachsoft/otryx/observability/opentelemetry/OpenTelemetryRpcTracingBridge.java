package com.peachsoft.otryx.observability.opentelemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcMetadataScope;
import com.peachsoft.otryx.observability.RpcTraceContext;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenTelemetry OTRYX RPC 分布式 Trace Bridge。
 *
 * <p>Span 仅记录有界 RPC 状态与错误类型，不直接记录第三方或业务异常消息、
 * StackTrace 和原始 RPC Metadata，避免 Trace 出口向外暴露用户数据。
 * 对于需要诊断详情的调用方，应使用经过授权的受控日志或 Trace 采样策略。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 14:59
 */
public final class OpenTelemetryRpcTracingBridge
        implements RpcTracingBridge {

    private static final String INSTRUMENTATION_NAME =
            "com.peachsoft.otryx";
    private static final String RPC_SYSTEM_NAME = "otryx";
    private static final String ATTRIBUTE_RPC_SYSTEM = "rpc.system";
    private static final String ATTRIBUTE_RPC_SERVICE = "rpc.service";
    private static final String ATTRIBUTE_RPC_SERVICE_ID = "rpc.service.id";
    private static final String ATTRIBUTE_RPC_METHOD_ID = "rpc.method.id";
    private static final String ATTRIBUTE_RPC_STATUS = "rpc.status";
    private static final String ATTRIBUTE_ERROR_TYPE = "error.type";
    private static final TextMapGetter<Map<String, String>>
            GETTER = new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(
                        Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(
                        Map<String, String> carrier,
                        String key) {
                    return carrier.get(key);
                }
            };

    private final Tracer tracer;
    private final TextMapPropagator propagator;

    /**
     * 创建 OpenTelemetry Trace Bridge。
     *
     * @param openTelemetry OpenTelemetry
     */
    public OpenTelemetryRpcTracingBridge(
            OpenTelemetry openTelemetry) {
        Objects.requireNonNull(
                openTelemetry,
                "openTelemetry");
        this.tracer = openTelemetry.getTracer(
                INSTRUMENTATION_NAME);
        this.propagator = openTelemetry
                .getPropagators()
                .getTextMapPropagator();
    }

    @Override
    public RpcTraceContext startClient(
            ServiceKey serviceKey,
            int methodId) {
        Context parent = Context.current();
        Span span = tracer.spanBuilder(
                        serviceKey.serviceName()
                                + "/"
                                + methodId)
                .setParent(parent)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(
                        ATTRIBUTE_RPC_SYSTEM,
                        RPC_SYSTEM_NAME)
                .setAttribute(
                        ATTRIBUTE_RPC_SERVICE,
                        serviceKey.canonicalName())
                .setAttribute(
                        ATTRIBUTE_RPC_METHOD_ID,
                        methodId)
                .startSpan();
        Context context = parent.with(span);
        Map<String, String> metadata =
                new LinkedHashMap<>();
        propagator.inject(
                context,
                metadata,
                Map::put);
        return new OtelTraceContext(
                span,
                context,
                Map.copyOf(metadata));
    }

    @Override
    public RpcTraceContext startServer(
            int serviceId,
            int methodId,
            Map<String, String> metadata) {
        Context parent = propagator.extract(
                Context.root(),
                metadata,
                GETTER);
        Span span = tracer.spanBuilder(
                        serviceId + "/" + methodId)
                .setParent(parent)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(
                        ATTRIBUTE_RPC_SYSTEM,
                        RPC_SYSTEM_NAME)
                .setAttribute(
                        ATTRIBUTE_RPC_SERVICE_ID,
                        serviceId)
                .setAttribute(
                        ATTRIBUTE_RPC_METHOD_ID,
                        methodId)
                .startSpan();
        return new OtelTraceContext(
                span,
                parent.with(span),
                Map.of());
    }

    private static final class OtelTraceContext
            implements RpcTraceContext {

        private final Span span;
        private final Context context;
        private final Map<String, String> metadata;
        private final AtomicBoolean ended =
                new AtomicBoolean();

        private OtelTraceContext(
                Span span,
                Context context,
                Map<String, String> metadata) {
            this.span = span;
            this.context = context;
            this.metadata = metadata;
        }

        @Override
        public Map<String, String> metadata() {
            return metadata;
        }

        @Override
        public RpcMetadataScope makeCurrent() {
            Scope scope = context.makeCurrent();
            return scope::close;
        }

        @Override
        public void end(
                RpcStatus status,
                Throwable error) {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            span.setAttribute(
                    ATTRIBUTE_RPC_STATUS,
                    status.name());
            if (error != null) {
                // Untrusted business exception messages / stack traces may
                // contain raw request payloads and credentials. The safe
                // default only exports the exception type.
                span.setAttribute(
                        ATTRIBUTE_ERROR_TYPE,
                        error.getClass().getName());
                span.setStatus(StatusCode.ERROR);
            } else if (status != RpcStatus.OK) {
                span.setStatus(StatusCode.ERROR);
            } else {
                span.setStatus(StatusCode.OK);
            }
            span.end();
        }
    }
}
