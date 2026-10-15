package com.peachsoft.otryx.registry.memory;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistrySubscription;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 严格按 ApplicationKey 注册 Provider、Consumer 与 BOTH 的进程内成员目录。
 *
 * <p>一个实例只形成一份应用成员记录，与导出的接口数量无关；
 * Consumer-only 必须保留真实成员身份但不具 RPC Endpoint。
 * 通过锁保护注册/查询的原子快照，通知在全局锁外执行且按订阅者 Revision 去重。
 * 订阅回调在调用者的控制面线程执行，不得从 EventLoop 同步触发本组件写入。
 * 本实现不跨进程，不替代 Etcd/Nacos 等共享注册中心。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class MemoryApplicationRegistry implements ApplicationRegistry, ApplicationRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryApplicationRegistry.class);
    private static final int MAX_APPLICATIONS = 512;
    private static final int MAX_MEMBERS = 4096;
    private static final int MAX_PER_APPLICATION = 256;
    private static final int MAX_SUBSCRIBERS = 2048;
    private static final RegistryCapabilities CAPABILITIES = RegistryCapabilities.of(
            RegistryCapability.REGISTRATION,
            RegistryCapability.SUBSCRIPTION,
            RegistryCapability.REVISION,
            RegistryCapability.WEIGHT,
            RegistryCapability.METADATA,
            RegistryCapability.HEALTH);

    private final Object monitor = new Object();
    private final Map<ApplicationKey, Map<String, ApplicationInstance>> directories = new HashMap<>();
    private final Map<ApplicationKey, List<Subscriber>> subscriptions = new HashMap<>();
    private int members;
    private int subscribers;
    private long revision;
    private boolean closed;

    /** 创建一个隔离、初始为空的应用成员注册中心。 */
    public MemoryApplicationRegistry() {
    }

    /**
     * 返回可验证的原生应用注册能力。
     *
     * @return 声明能力集合
     */
    @Override
    public RegistryCapabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * 原子注册或更新同一个应用实例，重复注册同内容不产生额外 Revision。
     *
     * @param instance 应用成员
     * @return 记录完成信号；容量满或已关闭时异常完成
     */
    @Override
    public CompletionStage<Void> register(ApplicationInstance instance) {
        Objects.requireNonNull(instance, "instance");
        ApplicationDirectorySnapshot next;
        List<Subscriber> watchers;
        synchronized (monitor) {
            if (closed) {
                return unavailable();
            }
            Map<String, ApplicationInstance> current = directories.get(instance.application());
            if (current != null && instance.equals(current.get(instance.instanceId()))) {
                return CompletableFuture.completedFuture(null);
            }
            boolean existing = current != null && current.containsKey(instance.instanceId());
            if (!existing && (members >= MAX_MEMBERS
                    || current != null && current.size() >= MAX_PER_APPLICATION
                    || current == null && directories.size() >= MAX_APPLICATIONS)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Application member capacity exceeded"));
            }
            if (revision == Long.MAX_VALUE) {
                return CompletableFuture.failedFuture(new IllegalStateException("Application revision exhausted"));
            }
            Map<String, ApplicationInstance> updated =
                    current == null ? new HashMap<>() : new HashMap<>(current);
            updated.put(instance.instanceId(), instance);
            directories.put(instance.application(), updated);
            if (!existing) {
                members++;
            }
            revision++;
            next = snapshot(instance.application());
            watchers = List.copyOf(subscriptions.getOrDefault(instance.application(), List.of()));
        }
        notifyWatchers(watchers, next);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 仅移除与当前记录完全匹配的实例，过期注销不得删除滚动升级后的新记录。
     *
     * @param instance 待撤销的完整应用成员
     * @return 幂等注销完成信号
     */
    @Override
    public CompletionStage<Void> unregister(ApplicationInstance instance) {
        Objects.requireNonNull(instance, "instance");
        ApplicationDirectorySnapshot next;
        List<Subscriber> watchers;
        synchronized (monitor) {
            if (closed) {
                return unavailable();
            }
            Map<String, ApplicationInstance> current = directories.get(instance.application());
            if (current == null || !instance.equals(current.get(instance.instanceId()))) {
                return CompletableFuture.completedFuture(null);
            }
            if (revision == Long.MAX_VALUE) {
                return CompletableFuture.failedFuture(new IllegalStateException("Application revision exhausted"));
            }
            Map<String, ApplicationInstance> updated = new HashMap<>(current);
            updated.remove(instance.instanceId());
            if (updated.isEmpty()) {
                directories.remove(instance.application());
            } else {
                directories.put(instance.application(), updated);
            }
            members--;
            revision++;
            next = snapshot(instance.application());
            watchers = List.copyOf(subscriptions.getOrDefault(instance.application(), List.of()));
        }
        notifyWatchers(watchers, next);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 返回完整的不可变应用成员目录（含纯 Consumer）；空目录是合法健康快照。
     *
     * @param application 应用身份
     * @return 原子目录快照
     */
    @Override
    public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
        Objects.requireNonNull(application, "application");
        synchronized (monitor) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Memory application registry closed"));
            }
            return CompletableFuture.completedFuture(snapshot(application));
        }
    }

    /**
     * 订阅应用目录，初始立即发送原子快照；反向顺序的迟到事件会被 Revision 抑制。
     *
     * @param application 应用身份
     * @param listener 目录变化/健康监听器
     * @return 幂等关闭的订阅句柄
     */
    @Override
    public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(listener, "listener");
        Subscriber subscriber = new Subscriber(listener);
        ApplicationDirectorySnapshot initial;
        synchronized (monitor) {
            if (closed || subscribers >= MAX_SUBSCRIBERS) {
                throw new IllegalStateException("Memory application registry subscription unavailable");
            }
            subscriptions.computeIfAbsent(application, ignored -> new ArrayList<>()).add(subscriber);
            subscribers++;
            initial = snapshot(application);
        }
        subscriber.deliver(initial);
        return () -> {
            subscriber.stop();
            synchronized (monitor) {
                List<Subscriber> current = subscriptions.get(application);
                if (current != null && current.remove(subscriber)) {
                    subscribers--;
                    if (current.isEmpty()) {
                        subscriptions.remove(application);
                    }
                }
            }
        };
    }

    private ApplicationDirectorySnapshot snapshot(ApplicationKey application) {
        Map<String, ApplicationInstance> current = directories.get(application);
        return new ApplicationDirectorySnapshot(
                application, current == null ? List.of() : List.copyOf(current.values()), revision);
    }

    private static void notifyWatchers(
            List<Subscriber> watchers, ApplicationDirectorySnapshot snapshot) {
        for (Subscriber subscriber : watchers) {
            subscriber.deliver(snapshot);
        }
    }

    private static CompletionStage<Void> unavailable() {
        return CompletableFuture.failedFuture(new IllegalStateException("Memory application registry closed"));
    }

    /** 释放全部本地目录与订阅，之后不得重新注册或发现。 */
    @Override
    public void close() {
        List<Subscriber> all;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            all = subscriptions.values().stream().flatMap(List::stream).toList();
            directories.clear();
            subscriptions.clear();
            members = 0;
            subscribers = 0;
        }
        all.forEach(Subscriber::stop);
    }

    private static final class Subscriber {
        private final ApplicationRegistryListener listener;
        private long delivered = -1;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private Subscriber(ApplicationRegistryListener listener) {
            this.listener = listener;
        }

        private synchronized void deliver(ApplicationDirectorySnapshot snapshot) {
            if (stopped.get() || snapshot.revision() <= delivered) {
                return;
            }
            delivered = snapshot.revision();
            try {
                listener.onSnapshot(snapshot);
                listener.onSynchronizationSucceeded();
            } catch (RuntimeException error) {
                LOGGER.warn("Application registry subscriber failed: {}",
                        error.getClass().getSimpleName());
            }
        }

        private void stop() {
            stopped.set(true);
        }
    }
}
