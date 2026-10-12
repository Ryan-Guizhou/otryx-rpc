package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 有效 RPC 方法集合在继承、静态、默认和 Object 方法下的契约测试。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 19:07
 */
class RpcContractMethodsTest {

    @Test
    void allInvocationPathsMustResolveSameMethods() {
        List<java.lang.reflect.Method> methods =
                RpcContractMethods.resolve(Derived.class);
        assertEquals(2, methods.size());
        assertEquals("fetch", methods.get(0).getName());
        assertEquals("run", methods.get(1).getName());
        assertTrue(methods.stream().noneMatch(
                method -> method.isDefault()
                        || method.getDeclaringClass() == Object.class));
    }

    @Test
    void genericRpcMethodMustFailAtBinding() {
        assertThrows(IllegalArgumentException.class,
                () -> RpcContractMethods.resolve(GenericContract.class));
    }

    @Test
    void concreteClassMustNotBeAcceptedAsRpcContract() {
        assertThrows(IllegalArgumentException.class,
                () -> RpcContractMethods.resolve(Object.class));
    }

    interface Base {
        String run();
    }

    interface Derived extends Base {
        long fetch(int id);

        default String local() {
            return "local";
        }

        static String helper() {
            return "helper";
        }
    }

    interface GenericContract {
        <T> T echo(T value);
    }
}
