# OTRYX RPC 与 Apache Dubbo 独立进程对比基准

> **状态：OTRYX 1.0.0-SNAPSHOT 的可执行 Smoke 对比工具（非正式性能结论）。** 下述脚本构成可执行的、独立 JVM 的 **Smoke 基线**，并不表示已完成固定硬件性能矩阵。任何 p99、QPS、吞吐领先结论必须建立在后续受控 Evidence 上。

## 1. 对比边界

| 项目 | OTRYX RPC | Apache Dubbo |
|---|---|---|
| 代码基线 | OTRYX RPC 1.0.0-SNAPSHOT，应用级注册 | 3.3.6（实验依赖固定） |
| 协议 | OTRYX Wire v1 / Vert.x TCP | Dubbo TCP |
| 序列化 | Fory Native | Hessian2 |
| 测试服务 | `byte[] echo(byte[])` | `byte[] echo(byte[])` |
| 服务发现 | 真实共享 Etcd：应用成员、Manifest、Mapping | 直连 URL |
| 传输加密 | 当前 Smoke 为明文 | 当前 Smoke 为明文 |
| Provider/Consumer | 独立 JVM，可跨机器 | 独立 JVM，可跨机器 |
| 负载模型 | 共用 `ComparisonHarness` | 共用 `ComparisonHarness` |

此对照衡量两套完整 RPC 栈的行为，协议、序列化与控制面条件各不相同。它不能单独归因传输层效率，也不等价于相同算法或注册策略间的 A/B。控制不同 Codec、TLS、JVM、线程模型的受控矩阵需要另外取得证据。

### 执行拓扑

~~~mermaid
flowchart LR
    Bench[共同负载模型及统一 JSON Schema]
    CP[OTRYX Consumer JVM]
    PP[OTRYX Provider JVM]
    CD[Dubbo Consumer JVM]
    PD[Dubbo Provider JVM]
    Registry[Etcd 应用控制面]
    V[校验器和 Evidence Report]

    Bench --> CP
    Bench --> CD
    CP -->|Wire v1 / Fory| PP
    CP -. 成员与发现 .-> Registry
    PP -. 成员与清单 .-> Registry
    CD -->|Dubbo TCP / Hessian2| PD
    CP --> V
    CD --> V
~~~

两组测试顺序执行，避免同机不同 Provider 进程互相抢占资源。正式测试应采用独占、固定硬件，控制 CPU 频率、JDK 版本、Heap 与 GC 参数、CPU Affinity、连接数与响应大小。

## 2. Smoke 快速开始

环境：JDK 21、Maven 3、Python 3、Bash、可访问 Maven Central 的网络，以及两个 OTRYX JVM 均可访问的真实 Etcd。脚本使用已经运行的 Etcd；CI 会自行启动并清理固定的 Etcd 3.5.21 容器。从仓库根目录运行：

~~~bash
export RPC_COMPARISON_REGISTRY_ENDPOINT=http://127.0.0.1:2379
bash scripts/run_rpc_comparison_smoke.sh
~~~

脚本的实际行为：

1. 校验共享 Etcd endpoint、隔离 namespace 和 `/health`，失败时在构建前退出；随后编译并安装包括 Etcd Adapter 在内的 OTRYX RPC 模块；
2. 独立构建 `benchmarks/rpc-comparison/otryx` 和 `benchmarks/rpc-comparison/dubbo`，输出各自独立的 shaded JAR；
3. 向两个 OTRYX JVM 传入相同 Etcd endpoint 与 namespace；Provider 发布真实应用成员和 Manifest/Mapping，Consumer 等待其唯一 Provider 和已验证路由，并在预热前执行 Echo 探测；结束后注销成员并关闭；
4. 再以同样方式运行 Dubbo；
5. 生成 `target/rpc-comparison-smoke/` 下的 `otryx.json`、`dubbo.json`、`environment.json`、`report.md` 和 Provider 日志。

默认 16 并发、预热 2 秒、测量 3 秒、256 字节数据包，**用于正确性和工具连通性检查，不能用于性能排名**。

每个 Consumer 的进程总预算为预热秒数 + 测量秒数 + 120 秒，额外时间覆盖启动、控制面就绪和关闭；默认合计 125 秒。超过预算后脚本发送 TERM，最多再等待 35 秒，然后 KILL 并回收，返回 124。Provider 的正常停止、失败清理和信号退出也共用最多 35 秒的 TERM→KILL→wait 边界。业务进程或证据校验失败保留失败退出码；不会继续输出完整比较成功。强制回收只能证明进程已停止，不能作为成员优雅注销的证据。

仅为本地失败夹具缩短等待，可设置 `RPC_COMPARISON_STOP_SECONDS`（1–35 秒）和 `RPC_COMPARISON_CLIENT_OVERHEAD_SECONDS`（1–120 秒）；它们只调整脚本预算，不传入 JVM。正常流程仍依次执行 OTRYX、Dubbo 和证据校验器。

脚本默认 Etcd 为 `http://127.0.0.1:2379`，并按本次时间与 PID 生成隔离 namespace；可用 `RPC_COMPARISON_REGISTRY_ENDPOINT` 和 `RPC_COMPARISON_REGISTRY_NAMESPACE` 显式覆盖。Java 入口没有隐式后端：任一环境变量缺失或为空都直接失败，不创建进程内成员镜像。每轮 namespace 只供对应的一组 OTRYX Provider/Consumer 使用。

可覆盖：

~~~bash
RPC_COMPARISON_CONCURRENCY=32 RPC_COMPARISON_WARMUP_SECONDS=5 \
RPC_COMPARISON_DURATION_SECONDS=15 RPC_COMPARISON_PAYLOAD_BYTES=1024 \
bash scripts/run_rpc_comparison_smoke.sh
~~~

## 3. 跨主机独立运行

构建完成后，将对应 JAR 部署到各自目标主机，启动独立 Provider：

~~~bash
# OTRYX Provider 主机
export RPC_COMPARISON_REGISTRY_ENDPOINT="http://<etcd-host>:2379"
export RPC_COMPARISON_REGISTRY_NAMESPACE=comparison-lab-1
export RPC_COMPARISON_ADVERTISED_HOST="<otryx-provider-host>"
java -jar benchmarks/rpc-comparison/otryx/target/otryx-comparison.jar provider 19501

# Dubbo Provider 主机（另一个终端或实例）
java -jar benchmarks/rpc-comparison/dubbo/target/dubbo-comparison.jar provider 19502
~~~

在 Consumer 负载机执行，使用相同并发、预热和测量窗口：

~~~bash
export RPC_COMPARISON_GIT_SHA="$(git rev-parse HEAD)"
export RPC_COMPARISON_RUN_ID="local-lab-1"
export RPC_COMPARISON_EVIDENCE_CLASS="smoke"
export RPC_COMPARISON_REGISTRY_ENDPOINT="http://<etcd-host>:2379"
export RPC_COMPARISON_REGISTRY_NAMESPACE=comparison-lab-1

java -jar benchmarks/rpc-comparison/otryx/target/otryx-comparison.jar \
  client "<otryx-provider-host>" 19501 32 5 15 1024 otryx.json

java -jar benchmarks/rpc-comparison/dubbo/target/dubbo-comparison.jar \
  client "<dubbo-provider-host>" 19502 32 5 15 1024 dubbo.json
~~~

OTRYX Consumer 的 host/port 参数用来核对从共享 Etcd 发现的唯一 Provider 端点，不能替代发现；它们必须与 Provider 的 advertised host/port 一致。Dubbo 使用直连 URL。当前比较路径是明文，**只在隔离的性能实验网络运行**；TLS 比较需要双方实际配置及独立验证，当前工具尚未覆盖。

## 4. Evidence 验证

~~~bash
python3 scripts/test_rpc_comparison_validator.py

python3 scripts/validate_rpc_comparison.py \
  --otryx otryx.json \
  --dubbo dubbo.json \
  --environment environment.json \
  --report report.md
~~~

校验器验证 schema、工作负载一致性、同一 SHA/Run ID、错误率、错误分类总和、百分位单调性、基础指标以及缺失数值的显式空值。`environment.json` 必须包含 `otryx_registry_backend=etcd`、合法的 `otryx_registry_namespace` 和 `registry_traffic_measurement=not-measured`；缺失或标注为 Memory/其他模式的来源记录不能通过当前门禁。手动跨主机运行也必须保存同样的真实环境来源记录；JSON 自述不能代替真实进程日志和后端验证。

当前原生采集项为：throughput、p50/p99/p99.9、错误率与错误类型、Consumer JVM 进程 CPU 估算、GC 时间及内存起止采样。**未测得的** allocation/op、Server CPU/GC、实际连接数明示为 `null`，绝不伪造为 0。

本工具没有统计 Etcd HTTP/gRPC、Watch、keepalive 或完整控制面流量。应用注册共享合同的 SPI 计数不能填入这些空白，见[应用级注册证据口径](application-registry-acceptance.md)。Python preflight 测试中的本地 HTTP 服务仅是失败传播夹具，不是实际 Etcd 或 RPC 通过记录。

当前负载为 **closed-loop** 且仅保留最后最多 100 万条成功延迟样本；存在 coordinated omission 与抽样窗口限制。要声称 p99 优势，需要进一步实现 open-loop 驱动和外部 JFR/allocation profiling。

## 5. 受控性能矩阵（后续验收门）

| 维度 | 目标配置 |
|---|---|
| Payload | 64B、256B、1KiB、16KiB、1MiB |
| 并发 | 1、32、128、512、1k、5k、10k |
| 场景 | NOOP、CPU、阻塞、异步、慢服务、过载 |
| 重复次数 | 正式 #146 验收使用 AB、BA、AB 六次运行 |
| 长稳 | 正式 #146 验收每次至少 60 分钟；短时比较脚本不提供该证据 |
| 硬件 | 固定 CPU/内存/内核/网络拓扑/运行器指纹 |
| 必备指标 | QPS、p50/p99/p99.9、错误率、Client+Server CPU、GC、allocation/op、内存峰值、连接与 inflight |
| 变更控制 | 独立 JVM/主机、固定 JDK、同业务语义、相同数据与合理等价配置 |

这些属于**待实施的受控验收项目**，不是当前 Smoke 已经完成的测试。

已存在的 [V2-D.2 Evidence 工具](performance-evidence.md) 用于 OTRYX 自身定标；跨框架对照不替代既有受控基线门禁。正式条件见[连续实施计划](../engineering/plans/2026-10-19-v2-completion.md)，需要独占 Runner 与稳定网络后才能发布具有可复现条件的数值报告。

## 6. 外部实现依据

- [Apache Dubbo 官方 Java API 配置文档](https://dubbo.apache.org/zh-cn/overview/mannual/java-sdk/reference-manual/config/api/api/)：ServiceConfig、ReferenceConfig 与点对点直连。
- [Apache Dubbo 3.3.6 Maven Central](https://central.sonatype.com/artifact/org.apache.dubbo/dubbo/3.3.6)：版本依赖固定。

## 7. 已知风险

- 纯 Echo 对象基本无业务 CPU 消耗，只能测 RPC 调用完整路径；不同 Serializer 影响结论。
- 两侧 Provider 当前独立配置线程与 admission，压力测试需要按场景明确记录参数，不能说“默认等价”。
- Smoke 未衡量 JFR allocation、Server CPU、真实跨主机延迟或多负载强度。
- Provider Ready 文本代表进程启动成功，不替代端到端请求验证；Consumer 在正式测量前发送 Echo 探测。
- CI 所用 GitHub shared runner 仅供 Smoke，不得成为正式容量/SLO 基线。
