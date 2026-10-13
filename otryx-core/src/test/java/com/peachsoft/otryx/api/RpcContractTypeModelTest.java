package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 类型模型覆盖参数化 DTO、继承、嵌套集合及递归字段。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 19:28
 */
class RpcContractTypeModelTest {

    @Test
    void nestedGenericDtoTypesMustBeDiscoveredAndRegistered()
            throws Exception {
        Method method = Contract.class.getMethod("reply", Box.class);
        RpcMethodDescriptor descriptor = RpcMethodDescriptor.from(
                new ServiceKey(
                        Contract.class.getName(), "1.0.0", "test"),
                method);
        List<Class<?>> reachable =
                RpcContractTypeModel.applicationClasses(descriptor);
        assertTrue(reachable.contains(Box.class));
        assertTrue(reachable.contains(Payload.class));
        assertTrue(reachable.contains(Envelope.class));

        RpcTypeRegistry registry = new RpcTypeRegistry();
        registry.register(descriptor);
        Map<Integer, String> snapshot = registry.snapshot();
        assertTrue(snapshot.containsValue(Payload.class.getName()));
        assertTrue(snapshot.containsValue(Envelope.class.getName()));
        assertTrue(snapshot.containsValue(Box.class.getName()));
    }

    @Test
    void repeatedGraphTraversalMustRemainStableWithRecursion()
            throws Exception {
        Method method = RecursiveContract.class.getMethod("find", Node.class);
        RpcMethodDescriptor descriptor = RpcMethodDescriptor.from(
                new ServiceKey(
                        RecursiveContract.class.getName(), "1.0.0", "test"),
                method);
        List<java.lang.reflect.Type> first =
                RpcContractTypeModel.reachableTypes(descriptor);
        List<java.lang.reflect.Type> second =
                RpcContractTypeModel.reachableTypes(descriptor);
        assertEquals(first.size(), second.size());
        assertEquals(
                first.stream().map(RpcTypeIds::canonicalName).toList(),
                second.stream().map(RpcTypeIds::canonicalName).toList());
        assertTrue(RpcContractTypeModel.applicationClasses(descriptor)
                .contains(Node.class));
    }

    interface Contract {
        Envelope reply(Box<Payload> request);
    }

    interface RecursiveContract {
        Node find(Node head);
    }

    record Payload(String id) {
    }

    record Box<T>(T value, List<T> history) {
    }

    record Envelope(Box<Payload> box) {
    }

    static final class Node {
        private Node next;
        private Envelope payload;
    }
}
