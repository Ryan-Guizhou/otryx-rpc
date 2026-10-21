package com.peachsoft.otryx.codec.fory;

import com.peachsoft.otryx.api.RpcContractTypeModel;
import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.api.RpcTypeRegistry;
import com.peachsoft.otryx.codec.RpcBoundedResultMethodCodec;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.codec.RpcCodecSecurityDescriptor;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.spi.Extension;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.apache.fory.resolver.AllowListChecker;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

/**
 * Apache Fory 高性能 Java 对象编解码器。
 *
 * <p>Codec ID 1 的 wire payload 与 V2-A 保持兼容。V2-B 的方法级绑定
 * 只优化本机解码路径，不改变参数在线上的 Object[] 表示。
 *
 * <p>无参构造器保留历史可信环境的兼容模式；处理不可信输入前需明确启用
 * 严格类型白名单和对象图/长度预算。类型允许规则以及滚动部署兼容性
 * 不能由本 Codec 自动推断，应由应用配置和测试验证。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@Extension(ForyRpcCodec.EXTENSION_NAME)
public final class ForyRpcCodec implements RpcCodec {

    static final String EXTENSION_NAME = "fory";

    // Object[0] 不可变，无需在每次无参 RPC 上重新创建相同的临时数组。
    // 仍由 Fory 编码标准 Object[] Payload，完全不修改 Wire v1。
    private static final Object[] EMPTY_ARGUMENTS = new Object[0];

    private final RpcTypeRegistry typeRegistry =
            new RpcTypeRegistry();
    private final ThreadSafeFory fory;
    private final ForyRpcSecurityOptions securityOptions;
    private final RpcCodecSecurityDescriptor securityDescriptor;

    /** 创建与原有 Wire v1 报文格式兼容的 Fory 编解码器。 */
    public ForyRpcCodec() {
        this(ForyRpcSecurityOptions.trustedCompatibility());
    }

    /**
     * 按指定安全策略创建 Fory 编解码器。
     *
     * @param options 安全策略
     */
    public ForyRpcCodec(ForyRpcSecurityOptions options) {
        securityOptions = Objects.requireNonNull(options, "options");
        var builder = Fory.builder()
                .withXlang(false)
                .requireClassRegistration(false)
                .withMaxDepth(options.maxDepth())
                .withMaxGraphMemoryBytes(options.maxGraphMemoryBytes());
        if (options.mode()
                == ForyRpcSecurityOptions.Mode.STRICT_ALLOWLIST) {
            AllowListChecker checker = new AllowListChecker(
                    AllowListChecker.CheckLevel.STRICT);
            for (String pattern : options.allowedClassPatterns()) {
                checker.allowClass(pattern);
            }
            builder.withTypeChecker(checker);
        }
        fory = builder.buildThreadSafeFory();
        boolean strict = securityOptions.mode() == ForyRpcSecurityOptions.Mode.STRICT_ALLOWLIST;
        securityDescriptor = new RpcCodecSecurityDescriptor(
                strict
                        ? RpcCodecSecurityDescriptor.TypePolicy.EXPLICIT_ALLOWLIST
                        : RpcCodecSecurityDescriptor.TypePolicy.TRUSTED_COMPATIBILITY,
                strict,
                true,
                securityOptions.maxPayloadBytes(),
                securityOptions.maxDepth(),
                securityOptions.maxGraphMemoryBytes());
    }

    /**
     * 返回已安装的类型检查器和解码预算，不执行 IO 或重新构建 Fory。
     *
     * @return 本 Codec 及其方法绑定共同使用的不可变能力；对象图额度是估算预算
     */
    @Override
    public RpcCodecSecurityDescriptor securityDescriptor() {
        return securityDescriptor;
    }

    /**
     * 返回实际生效的 Fory 反序列化策略，供宿主验证替换后的 Codec 注册表。
     *
     * @return 创建本 Codec 时冻结的安全设置
     */
    public ForyRpcSecurityOptions securityOptions() {
        return securityOptions;
    }

    private void checkPayload(int length) {
        if (length < 0 || length > securityOptions.maxPayloadBytes()) {
            throw new IllegalArgumentException(
                    "Fory RPC payload exceeds configured maximum: " + length);
        }
    }

    private byte[] encodeChecked(Object value) {
        byte[] encoded = fory.serialize(value);
        checkPayload(encoded.length);
        return encoded;
    }

    private Object decodeChecked(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        checkPayload(bytes.length);
        return fory.deserialize(bytes);
    }

    private Object decodeSlice(byte[] frame, int offset, int length) {
        Objects.requireNonNull(frame, "frame");
        if (offset < 0 || length < 0 || offset > frame.length - length) {
            throw new IllegalArgumentException("Invalid Fory payload slice");
        }
        checkPayload(length);
        return fory.deserialize(ByteBuffer.wrap(frame, offset, length));
    }

    @Override
    public byte code() {
        return RpcCodecIds.FORY_NATIVE;
    }

    @Override
    public byte[] encode(Object value) {
        return encodeChecked(value);
    }

    @Override
    public <T> T decode(
            byte[] bytes,
            Class<T> type) {
        Object value = decodeChecked(bytes);
        return type.cast(value);
    }

    @Override
    public RpcMethodCodec bind(
            RpcMethodDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        typeRegistry.register(descriptor);
        ensureAllowedContractTypes(descriptor);
        return new ForyMethodCodec();
    }

    /**
     * 在方法绑定时验证声明 DTO 不会绕过应用显式的严格白名单。
     *
     * <p>多态 Object 或实际未知子类仍必须由应用的 Fory ClassChecker
     * 在真实反序列化时验证；这项静态检查不保证运行期动态子类安全。
     *
     * @param descriptor RPC 方法的静态类型契约
     */
    private void ensureAllowedContractTypes(RpcMethodDescriptor descriptor) {
        if (securityOptions.mode()
                != ForyRpcSecurityOptions.Mode.STRICT_ALLOWLIST) {
            return;
        }
        for (Class<?> type :
                RpcContractTypeModel.applicationClasses(descriptor)) {
            boolean allowed = securityOptions.allowedClassPatterns().stream()
                    .anyMatch(rule -> matchesAllowPattern(rule, type.getName()));
            if (!allowed) {
                throw new IllegalArgumentException(
                        "STRICT_ALLOWLIST missing RPC contract DTO: "
                                + type.getName());
            }
        }
    }

    private static boolean matchesAllowPattern(String rule, String className) {
        if (rule.equals(className)) {
            return true;
        }
        if (rule.endsWith(".*")) {
            return className.startsWith(
                    rule.substring(0, rule.length() - 1));
        }
        return false;
    }

    private final class ForyMethodCodec
            implements RpcBoundedResultMethodCodec {

        @Override
        public RpcCodecSecurityDescriptor securityDescriptor() {
            return securityDescriptor;
        }

        @Override
        public byte codecId() {
            return RpcCodecIds.FORY_NATIVE;
        }

        @Override
        public byte[] encode0() {
            return encodeChecked(EMPTY_ARGUMENTS);
        }

        @Override
        public byte[] encodeArguments(Object[] arguments) {
            return encodeChecked(
                    arguments == null
                            ? EMPTY_ARGUMENTS
                            : arguments);
        }

        @Override
        public Object[] decodeArguments(byte[] payload) {
            return (Object[]) decodeChecked(payload);
        }

        @Override
        public Object[] decodeArguments(
                byte[] frame,
                int offset,
                int length) {
            return (Object[]) decodeSlice(frame, offset, length);
        }

        @Override
        public byte[] encodeResult(Object value) {
            return encodeChecked(value);
        }

        /**
         * 直接写入调用者的有界 OutputStream，避免在 Codec 出口创建无额度的完整结果数组。
         *
         * @param value 实际业务返回值
         * @param output 调用方预留容量的输出流
         */
        @Override
        public void encodeResultTo(Object value, java.io.OutputStream output) {
            fory.serialize(output, value);
        }

        @Override
        public Object decodeResult(byte[] payload) {
            return decodeChecked(payload);
        }

        @Override
        public Object decodeResult(
                byte[] frame,
                int offset,
                int length) {
            return decodeSlice(frame, offset, length);
        }
    }
}
