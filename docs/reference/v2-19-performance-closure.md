# V2-19 性能矩阵与应用级注册规模对照

> 工具完成不代表性能实验完成：截至本文编写时尚无可验证的固定物理机六轮 60 分钟原始结果，因此 **BLOCKED（正式证据）**，不可宣称 P0 性能验收或生产 QPS/SLO。

## 已有的受控实验基础

仓库原有 `scripts/run_v2d2_benchmark_matrix.sh` 覆盖 Payload、连接分片、线程、TLS、错误注入、不同执行模式，并使用 JMH `-prof gc` 记录分配。实际逻辑并发 `PerformanceSoakRunner` 与 `scripts/check_soak_quality.py` 不把配置的 `concurrency=10000` 当作实际 inflight。固化的 `check_u13_campaign.py` 要求 **AB BA AB 六次完整固定主机运行**、每次至少 3600s、真实原始 Full Matrix/JMH 与 SHA256、固定 JDK21/CPU/JVM/内存/主机指纹、事先定义错误率及并发阈值；共享 Runner 只能 Smoke，永不充当官方容量结论。

## 本期补充：控制面服务注册缩减的真实量测

应用级注册的理论 Registry Member 数不会随接口数成倍扩张，但理论值**不是实测**。需分别在真正旧接口级版本和现行应用级版本，以同一后端和固定物理机，给同一 30 接口×10 实例工作负载采集至少三轮原生后台计数，记录：

- `nativeMembers`：实际后端原生成员条目数，而不是 Java Client 对象数；
- `activeSubscriptions` / `watchEvents`：控制面订阅数、固定观察窗口发生的原生 Watch 事件；
- `consumerRssBytes`：进程真实 RSS，而不是 JVM 配额或者虚构估算；
- `recoveryMillis`：实际故障恢复耗时，必须固定注入时点及观察窗口；
- 源代码完整 SHA、固定 Host fingerprint、JDK21、JVM flags、后端、接口/实例数、负载标识和唯一 runId。

**注意：** 历史接口级源码必须在隔离 worktree 和测试 Registry 中真实运行；不能将旧 ServiceKey 注册 SPI 重新带回主线。后端 `origin` 必须是 `backend-native`，任意 `formula-estimate` 等推算数据将被拒绝。为避免泄漏，不记录真实 InstanceId 或业务参数。

原始两侧 JSON：

```json
{
  "registryModel": "application",
  "commit": "<40-hex-sha>",
  "hostFingerprintSha256": "<64-hex-host-hash>",
  "backend": "etcd",
  "jdkMajor": 21,
  "jvmFlags": "-Xms256m -Xmx256m",
  "interfaces": 30,
  "instances": 10,
  "workloadId": "<fixed-experiment-id>",
  "runs": [
    {
      "runId": "<unique-run-id>",
      "capturedAt": "<ISO-8601-with-zone>",
      "sampleSeconds": 60,
      "origin": "backend-native",
      "nativeMembers": 0,
      "activeSubscriptions": 0,
      "watchEvents": 0,
      "consumerRssBytes": 0,
      "recoveryMillis": 0
    }
  ]
}
```

此 JSON 只说明**字段结构**，不是可运行或可验收的样例：零成员/RSS 会被拒绝，runs 不足三条会被拒绝。指标必须来自真实测量并保留底层原始监测记录。

```bash
python3 scripts/test_v2_19_registration_scale.py
python3 scripts/check_v2_19_registration_scale.py \
  --legacy <measured-legacy.json> \
  --application <measured-application.json> \
  --output target/v2-19/registry-scale-report.json
```

若文件缺失、主机不一致、Commit 相同、缺少真实原生计数或重复 runId，命令退出状态 2 且不生成虚假证据。通过也只输出 `REPORT_ONLY`，从不自行宣布性能达标；需结合 [U13 性能证据总控](performance-evidence.md) 和 [Soak 正式门禁](soak-acceptance.md) 人工签署 SLO 与严重退化阈值。

## 不能关闭 #146 的外部前置条件

1. 确定真实固定物理机、独立目标应用和后端，锁定两版完整 Git SHA 和不可变 JVM/CPU 参数。
2. **测试前**声明错误率、p99/p99.9、吞吐、Allocation、恢复/抖动的门限与严重退化阈值。
3. 六次 AB BA AB Full Matrix + 每次至少一小时 soak，保留原始性能数据、JFR/GC/JMH、CPU/DirectMemory/Heap/FD、各类失败及限流指标。
4. 完成注册规模实测、真实 TLS/mTLS 和故障场景，并经人工核对原始来源及回归结论。

缺少这些真实结果时保持 `BLOCKED`，不得以 CI Smoke 或模拟输出关闭 Issue #146。
