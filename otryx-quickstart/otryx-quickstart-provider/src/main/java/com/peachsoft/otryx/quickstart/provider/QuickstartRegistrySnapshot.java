package com.peachsoft.otryx.quickstart.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcSchemaFingerprint;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.quickstart.api.GreetingDetailsService;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.quickstart.api.RelayGreetingService;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationDiscovery;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import com.peachsoft.otryx.registry.VerifiedProviderSnapshot;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读取明确指定的应用成员、Manifest 和经 Core 校验的 Quickstart 路由。
 *
 * <p>仅用于一次性控制面诊断，不启动 Spring、不注册观察者成员。
 * 输出为白名单投影，不包含成员扩展属性；多次后端读取不是全局事务快照。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:04
 */
public final class QuickstartRegistrySnapshot {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuickstartRegistrySnapshot.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_APPLICATIONS = 16;
    private static final int MAX_MEMBERS = 128;
    private static final int MAX_MANIFESTS = 16;
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int FIXED_ARGUMENTS = 6;
    private static final int BACKEND_ARGUMENT = 0;
    private static final int ENDPOINTS_ARGUMENT = 1;
    private static final int NAMESPACE_ARGUMENT = 2;
    private static final int NACOS_GROUP_ARGUMENT = 3;
    private static final int METADATA_ENDPOINTS_ARGUMENT = 4;
    private static final int OUTPUT_ARGUMENT = 5;
    private static final String NACOS = "nacos";
    private static final String ETCD = "etcd";
    private static final String CONSUL = "consul";
    private static final String EUREKA = "eureka";
    private static final String NO_OPTION = "-";
    private static final String SERVICE_VERSION = "1.0.0";
    private static final String SERVICE_GROUP = "default";
    private static final String GROUP_OPTION = "nacosGroup";
    private static final String METADATA_BACKEND_OPTION = "eurekaMetadataBackend";
    private static final String METADATA_ENDPOINTS_OPTION = "eurekaMetadataEndpoints";
    private static final String VALID_GROUP = "[A-Za-z0-9._:-]{1,128}";
    private static final List<Class<?>> CONTRACTS = List.of(
            GreetingService.class, RelayGreetingService.class, GreetingDetailsService.class);

    private QuickstartRegistrySnapshot() {
    }

    /**
     * 从 Provider 可执行 JAR 独立读取指定应用后端，不触发 Spring 或成员注册。
     *
     * <p>先校验显式目标，再创建 SDK；后端关闭调用返回后才发布新的 JSON 文件。
     * Java 读取预算十五秒，SDK 初始化和关闭由外层脚本的进程总预算约束。
     * 失败时只暴露异常类型，不将 SDK 异常消息或原因链写入命令输出。
     *
     * @param arguments 后端类型、endpoints、namespace、Nacos group 或短横线、
     *                  Eureka 的 Consul metadata endpoints 或短横线、新输出文件、一至十六个应用名
     */
    public static void main(String[] arguments) {
        try {
            Command command = parseArguments(arguments,
                    System.getenv(QuickstartRegistrySecurity.SECURITY_FILE_ENVIRONMENT));
            Snapshot snapshot;
            ApplicationRegistryFactory factory = ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
                    .getExtension(command.backend());
            try (ApplicationRegistry registry = factory.create(command.options())) {
                if (!(registry instanceof ApplicationMetadataBackend metadata)) {
                    throw new IllegalStateException("Application metadata backend is unavailable");
                }
                snapshot = capture(registry, metadata, command.namespace(),
                        command.applicationNames(), DEFAULT_TIMEOUT, command.options().securityProfile());
            }
            writeSnapshot(command.output(), snapshot);
            LOGGER.info("Quickstart registry snapshot completed: applications={}, services={}",
                    snapshot.applications().size(), snapshot.services().size());
        } catch (RuntimeException | IOException error) {
            LOGGER.error("Quickstart registry inspection failed: type={}", error.getClass().getSimpleName());
            throw new IllegalStateException("Quickstart registry inspection failed: type="
                    + error.getClass().getSimpleName());
        }
    }

    /**
     * 在一个绝对时间预算内，以明确的开发意图读取完整诊断视图。
     *
     * <p>该公开入口保留进程内测试和开发调用；命令行的受保护读取另行验证实际来源描述。
     *
     * @param discovery 调用方拥有的异步应用目录，不得从 EventLoop 调用本方法
     * @param metadata 调用方拥有的元数据后端，本方法不关闭它
     * @param namespace 业务应用命名空间
     * @param applicationNames 明确指定的应用名列表
     * @param timeout 正数且不超过三十秒的总读取预算
     * @return 完整成功后的不可变白名单视图
     * @throws IllegalArgumentException 目标重复、超过容量或预算非法时抛出
     * @throws IllegalStateException 后端失败、引用缺失或超时；中断时保留线程中断标志
     */
    public static Snapshot capture(ApplicationDiscovery discovery, ApplicationMetadataBackend metadata,
            String namespace, List<String> applicationNames, Duration timeout) {
        return capture(discovery, metadata, namespace, applicationNames, timeout,
                RpcSecurityProfile.TRUSTED_DEVELOPMENT);
    }

    static Snapshot capture(ApplicationDiscovery discovery, ApplicationMetadataBackend metadata,
            String namespace, List<String> applicationNames, Duration timeout, RpcSecurityProfile securityProfile) {
        Objects.requireNonNull(discovery, "discovery");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(timeout, "timeout");
        List<ApplicationKey> applications = applicationKeys(namespace, applicationNames);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Snapshot timeout must be positive and at most thirty seconds");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try (ServiceMappingCache mappings = new ServiceMappingCache(
                    metadata.serviceMappingRepository(), Duration.ZERO, Duration.ZERO);
                ManifestSnapshotCache manifests = new ManifestSnapshotCache(metadata.manifestRepository());
                ApplicationServiceResolver resolver = new ApplicationServiceResolver(
                        mappings, discovery, manifests, DEFAULT_TIMEOUT)) {
            QuickstartRegistrySecurity.verify(securityProfile, resolver::securityDescriptor,
                    applications.getFirst().namespace());
            List<ApplicationView> views = new ArrayList<>();
            int memberCount = 0;
            int manifestCount = 0;
            for (ApplicationKey application : applications) {
                ApplicationDirectorySnapshot directory = await(discovery.lookup(application), deadline);
                if (!application.equals(directory.application())) {
                    throw new IllegalStateException("Snapshot directory belongs to another application");
                }
                if (directory.instances().size() > MAX_MEMBERS - memberCount) {
                    throw new IllegalStateException("Snapshot member capacity exceeded");
                }
                memberCount += directory.instances().size();
                List<ApplicationInstance> instances = directory.instances().stream()
                        .sorted(Comparator.comparing(ApplicationInstance::instanceId)).toList();
                List<ManifestView> manifestViews = new ArrayList<>();
                List<String> revisions = instances.stream().filter(ApplicationInstance::routableProvider)
                        .map(ApplicationInstance::manifestRevision).distinct().sorted().toList();
                for (String revision : revisions) {
                    if (++manifestCount > MAX_MANIFESTS) {
                        throw new IllegalStateException("Snapshot manifest capacity exceeded");
                    }
                    ApplicationManifest manifest = await(manifests.lookup(application, revision), deadline)
                            .orElseThrow(() -> new IllegalStateException("Snapshot referenced manifest is missing"));
                    manifestViews.add(new ManifestView(manifest.revision(), manifest.services()));
                }
                List<MemberView> members = instances.stream().map(instance -> new MemberView(
                        instance.instanceId(), instance.role(), instance.rpcEndpoint(), instance.weight(),
                        instance.manifestRevision())).toList();
                views.add(new ApplicationView(application.applicationName(), members, manifestViews));
            }
            List<ServiceView> services = new ArrayList<>();
            String applicationNamespace = applications.getFirst().namespace();
            for (Class<?> contract : CONTRACTS) {
                ServiceKey service = new ServiceKey(contract.getName(), SERVICE_VERSION, SERVICE_GROUP);
                ServiceMappingKey key = new ServiceMappingKey(applicationNamespace, service);
                VerifiedProviderSnapshot verified = await(resolver.refresh(key,
                        RpcSchemaFingerprint.serviceFingerprint(service, contract)), deadline);
                if (verified.providers().size() > MAX_MEMBERS) {
                    throw new IllegalStateException("Snapshot provider capacity exceeded");
                }
                ServiceMappingSnapshot mapping = mappings.current(key)
                        .orElseThrow(() -> new IllegalStateException("Snapshot mapping is unavailable"));
                List<ProviderView> providers = verified.providers().stream()
                        .map(instance -> new ProviderView(instance.application().applicationName(),
                                instance.instanceId(), instance.role(), instance.rpcEndpoint(),
                                instance.manifestRevision()))
                        .sorted(Comparator.comparing(ProviderView::applicationName)
                                .thenComparing(ProviderView::instanceId)).toList();
                services.add(new ServiceView(service, mapping.applications().stream()
                        .map(ApplicationKey::applicationName).sorted().toList(), providers));
            }
            return new Snapshot(applicationNamespace, views, services);
        }
    }

    static void writeSnapshot(Path output, Snapshot snapshot) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        byte[] bytes = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            throw new IllegalStateException("Snapshot output capacity exceeded");
        }
        Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), ".otryx-snapshot-", ".json");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, output);
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            throw error;
        }
    }

    static Command parseArguments(String[] arguments) throws IOException {
        return parseArguments(arguments, null);
    }

    static Command parseArguments(String[] arguments, String securityFile) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length <= FIXED_ARGUMENTS || arguments.length > FIXED_ARGUMENTS + MAX_APPLICATIONS) {
            throw new IllegalArgumentException("Expected six target arguments and one to sixteen applications");
        }
        for (String argument : arguments) {
            if (argument == null || argument.isBlank()) {
                throw new IllegalArgumentException("Snapshot arguments must be explicit and nonblank");
            }
        }
        String backend = arguments[BACKEND_ARGUMENT].trim();
        String group = arguments[NACOS_GROUP_ARGUMENT].trim();
        String metadataEndpoints = arguments[METADATA_ENDPOINTS_ARGUMENT].trim();
        Map<String, String> providerOptions = switch (backend) {
            case NACOS -> {
                if (!group.matches(VALID_GROUP) || NO_OPTION.equals(group) || !NO_OPTION.equals(metadataEndpoints)) {
                    throw new IllegalArgumentException("Nacos requires an explicit group and no external metadata");
                }
                yield Map.of(GROUP_OPTION, group);
            }
            case ETCD, CONSUL -> {
                requireNoOptions(group, metadataEndpoints);
                yield Map.of();
            }
            case EUREKA -> {
                if (!NO_OPTION.equals(group) || NO_OPTION.equals(metadataEndpoints)) {
                    throw new IllegalArgumentException("Eureka snapshot requires explicit Consul metadata endpoints");
                }
                requireEndpoints(metadataEndpoints, arguments[NAMESPACE_ARGUMENT]);
                yield Map.of(METADATA_BACKEND_OPTION, CONSUL, METADATA_ENDPOINTS_OPTION, metadataEndpoints);
            }
            default -> throw new IllegalArgumentException("Unsupported application snapshot backend");
        };
        requireEndpoints(arguments[ENDPOINTS_ARGUMENT], arguments[NAMESPACE_ARGUMENT]);
        RegistryOptions options = QuickstartRegistrySecurity.options(backend,
                RegistryOptions.fromCsv(arguments[ENDPOINTS_ARGUMENT], arguments[NAMESPACE_ARGUMENT], providerOptions),
                securityFile);
        List<ApplicationKey> applications = applicationKeys(arguments[NAMESPACE_ARGUMENT],
                Arrays.asList(arguments).subList(FIXED_ARGUMENTS, arguments.length));
        Path output = Path.of(arguments[OUTPUT_ARGUMENT]).toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        if (!Files.isDirectory(output.getParent())) {
            throw new IllegalArgumentException("Snapshot output parent directory must already exist");
        }
        return new Command(backend, options, applications.getFirst().namespace(), output,
                applications.stream().map(ApplicationKey::applicationName).toList());
    }

    private static void requireNoOptions(String group, String metadataEndpoints) {
        if (!NO_OPTION.equals(group) || !NO_OPTION.equals(metadataEndpoints)) {
            throw new IllegalArgumentException("This snapshot backend does not use group or external metadata options");
        }
    }

    private static void requireEndpoints(String endpoints, String namespace) {
        RegistryOptions options = RegistryOptions.fromCsv(endpoints, namespace, Map.of());
        if (options.endpoints().isEmpty() || options.endpoints().stream().anyMatch(value -> value.contains("@"))) {
            throw new IllegalArgumentException("Snapshot endpoint must be explicit and must not embed credentials");
        }
    }

    private static List<ApplicationKey> applicationKeys(String namespace, List<String> names) {
        Objects.requireNonNull(names, "applicationNames");
        if (names.isEmpty() || names.size() > MAX_APPLICATIONS) {
            throw new IllegalArgumentException("Snapshot requires one to sixteen explicit applications");
        }
        List<ApplicationKey> applications = names.stream().map(name -> new ApplicationKey(namespace, name)).toList();
        if (new HashSet<>(applications).size() != applications.size()) {
            throw new IllegalArgumentException("Duplicate snapshot application names");
        }
        return applications;
    }

    private static <T> T await(CompletionStage<T> stage, long deadline) {
        CompletableFuture<T> future = Objects.requireNonNull(stage, "snapshot read stage").toCompletableFuture();
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new TimeoutException("Snapshot read budget expired");
            }
            return Objects.requireNonNull(future.get(remaining, TimeUnit.NANOSECONDS), "snapshot read result");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Snapshot read interrupted", error);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new IllegalStateException("Snapshot read timed out", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("Snapshot backend read failed", error.getCause());
        }
    }

    /** 已校验的独立命令目标；不携带默认后端或隐式应用名。 */
    record Command(String backend, RegistryOptions options, String namespace,
            Path output, List<String> applicationNames) {
    }

    /**
     * 完整诊断结果。
     * @param applicationNamespace 应用命名空间
     * @param applications 指定应用的成员与清单
     * @param services 固定 Quickstart 契约的映射和已验证候选
     */
    public record Snapshot(String applicationNamespace,
            List<ApplicationView> applications, List<ServiceView> services) {
        /** 固化诊断集合。 */
        public Snapshot {
            applications = List.copyOf(applications);
            services = List.copyOf(services);
        }
    }

    /**
     * 一个应用的观察结果。
     * @param applicationName 应用名
     * @param members 后端认可的健康成员，不代表均可调用
     * @param manifests 成员当前引用并通过内容校验的清单
     */
    public record ApplicationView(String applicationName, List<MemberView> members, List<ManifestView> manifests) {
        /** 固化应用集合。 */
        public ApplicationView {
            members = List.copyOf(members);
            manifests = List.copyOf(manifests);
        }
    }

    /**
     * 不携带扩展属性的成员投影。
     * @param instanceId 实例身份
     * @param role 实际成员角色
     * @param rpcEndpoint 真实 RPC 端点，纯 Consumer 为 null
     * @param weight Provider 权重，纯 Consumer 为零
     * @param manifestRevision 实际内容修订版，纯 Consumer 为 null
     */
    public record MemberView(String instanceId, ApplicationRole role, RpcEndpoint rpcEndpoint,
            int weight, String manifestRevision) {
    }

    /**
     * 内容引用已校验的 Manifest 投影。
     * @param revision 实际内容散列
     * @param services 完整导出契约列表
     */
    public record ManifestView(String revision, List<ExportedService> services) {
        /** 固化导出集合。 */
        public ManifestView {
            services = List.copyOf(services);
        }
    }

    /**
     * 业务接口的应用映射与已校验实例候选。
     * @param serviceKey 完整业务契约键
     * @param mappedApplications 拥有该服务映射的应用名
     * @param verifiedProviders Core Resolver 校验后的实例，不包含纯 Consumer
     */
    public record ServiceView(ServiceKey serviceKey, List<String> mappedApplications,
            List<ProviderView> verifiedProviders) {
        /** 固化映射与候选。 */
        public ServiceView {
            mappedApplications = List.copyOf(mappedApplications);
            verifiedProviders = List.copyOf(verifiedProviders);
        }
    }

    /**
     * 可用于指定业务契约的实例投影。
     * @param applicationName 应用名
     * @param instanceId 实例身份
     * @param role PROVIDER 或 BOTH
     * @param rpcEndpoint 已校验的 RPC 端点
     * @param manifestRevision 用于本次校验的清单修订版
     */
    public record ProviderView(String applicationName, String instanceId, ApplicationRole role,
            RpcEndpoint rpcEndpoint, String manifestRevision) {
    }
}
