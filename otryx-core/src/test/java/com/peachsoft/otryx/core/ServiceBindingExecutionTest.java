package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.OtryxRpcExecution;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 验证服务方法绑定和执行模式的契约。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 10:13
 */
public class ServiceBindingExecutionTest {

    @Test
    void shouldResolveExecutionModePerMethod() throws Exception {
        ServiceKey key =
                new ServiceKey("example.ExecutionService", "1.0.0", "default");
        ServiceBinding binding = new ServiceBinding(
                key,
                ExecutionService.class,
                new ExecutionServiceImpl(),
                RpcCodecRegistry.of(new NoopCodec()),
                com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT);

        assertEquals(
                RpcExecutionMode.BLOCKING_VIRTUAL,
                binding.executionMode(methodId("blocking")));
        assertEquals(
                RpcExecutionMode.CPU,
                binding.executionMode(methodId("cpu")));
        assertEquals(
                RpcExecutionMode.DIRECT,
                binding.executionMode(methodId("direct")));
        assertTrue(binding.usesDirectExecution());
    }

    @Test
    void shouldReportNoDirectExecutionForSafeService() {
        ServiceKey key =
                new ServiceKey("example.SafeService", "1.0.0", "default");
        ServiceBinding binding = new ServiceBinding(
                key,
                SafeService.class,
                new SafeServiceImpl(),
                RpcCodecRegistry.of(new NoopCodec()),
                com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT);

        assertFalse(binding.usesDirectExecution());
    }

    private static int methodId(String name) throws Exception {
        Method method = ExecutionService.class.getMethod(name);
        return RpcIds.methodId(method);
    }

    public interface ExecutionService {
        String blocking();

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String cpu();

        @OtryxRpcExecution(RpcExecutionMode.DIRECT)
        String direct();
    }

    public static final class ExecutionServiceImpl implements ExecutionService {
        @Override
        public String blocking() {
            return "blocking";
        }

        @Override
        public String cpu() {
            return "cpu";
        }

        @Override
        public String direct() {
            return "direct";
        }
    }

    public interface SafeService {
        String execute();
    }

    public static final class SafeServiceImpl implements SafeService {
        @Override
        public String execute() {
            return "ok";
        }
    }

    static final class NoopCodec implements RpcCodec {
        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            return new byte[0];
        }

        @Override
        public <T> T decode(byte[] bytes, Class<T> type) {
            return null;
        }
    }
}
