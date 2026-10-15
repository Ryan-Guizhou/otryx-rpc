#!/usr/bin/env bash
set -euo pipefail
OUTPUT_DIR="${1:-target/v2d2-evidence}"
mkdir -p "$OUTPUT_DIR"
OUTPUT="$OUTPUT_DIR/environment.properties"
MARKDOWN="$OUTPUT_DIR/environment.md"
single_line() { tr '\n' ' ' | sed 's/[[:space:]][[:space:]]*/ /g; s/^ //; s/ $//'; }
cpu_model="$(lscpu 2>/dev/null | awk -F: '/Model name/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}')"
logical_cores="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo unknown)"
physical_cores="$(if command -v lscpu >/dev/null 2>&1; then lscpu -p=CORE,SOCKET 2>/dev/null | grep -v '^#' | sort -u | wc -l | tr -d ' '; else echo unknown; fi)"
threads_per_core="$(lscpu 2>/dev/null | awk -F: '/Thread\(s\) per core/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}')"
numa_nodes="$(lscpu 2>/dev/null | awk -F: '/NUMA node\(s\)/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}')"
memory_bytes="$(awk '/MemTotal:/ {printf "%.0f", $2 * 1024}' /proc/meminfo 2>/dev/null || echo unknown)"
cpu_governor="$(if [[ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]]; then cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor; else echo unknown; fi)"
java_version="$(java -version 2>&1 | single_line)"
maven_version="$(if [[ -x ./mvnw ]]; then ./mvnw -version 2>&1 | single_line; else echo unavailable; fi)"
openssl_version="$(if command -v openssl >/dev/null 2>&1; then openssl version 2>/dev/null | single_line; else echo unavailable; fi)"
containerized=false
if [[ -f /.dockerenv || -f /run/.containerenv ]]; then containerized=true; fi
commit="${OTRYX_RPC_BENCHMARK_COMMIT:-${GITHUB_SHA:-unknown}}"
evidence_class="${OTRYX_RPC_EVIDENCE_CLASS:-shared-ci}"
runner_id="${OTRYX_RPC_RUNNER_ID:-${RUNNER_NAME:-unknown}}"
runner_labels="${OTRYX_RPC_RUNNER_LABELS:-unknown}"
host_identity_material=""
if [[ -r /etc/machine-id ]]; then host_identity_material="$(cat /etc/machine-id)"; fi
if [[ -r /sys/class/dmi/id/product_uuid ]]; then host_identity_material="${host_identity_material}|$(cat /sys/class/dmi/id/product_uuid)"; fi
host_fingerprint_sha256=unknown
if [[ -n "$host_identity_material" ]] && command -v sha256sum >/dev/null 2>&1; then
  host_fingerprint_sha256="$(printf '%s' "$host_identity_material" | sha256sum | awk '{print $1}')"
fi
run_id="${OTRYX_RPC_EVIDENCE_RUN_ID:-unknown}"
hostname_value="$(hostname 2>/dev/null || echo unknown)"
kernel="$(uname -a 2>/dev/null | single_line)"
captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "$OUTPUT" <<EOF
schema_version=1
captured_at=$captured_at
commit=$commit
evidence_class=$evidence_class
runner_id=$runner_id
runner_labels=$runner_labels
host_fingerprint_sha256=$host_fingerprint_sha256
run_id=$run_id
hostname=$hostname_value
kernel=$kernel
cpu_model=${cpu_model:-unknown}
logical_cores=${logical_cores:-unknown}
physical_cores=${physical_cores:-unknown}
threads_per_core=${threads_per_core:-unknown}
numa_nodes=${numa_nodes:-unknown}
memory_bytes=${memory_bytes:-unknown}
cpu_governor=${cpu_governor:-unknown}
java=$java_version
maven=$maven_version
openssl=$openssl_version
containerized=$containerized
jvm_flags=${OTRYX_RPC_JVM_FLAGS:-}
EOF
cat > "$MARKDOWN" <<EOF
# V2-D.2 Environment

| Field | Value |
|---|---|
| Evidence class | `$evidence_class` |
| Commit | `$commit` |
| Runner ID | `$runner_id` |
| Runner labels | `$runner_labels` |
| Host fingerprint SHA-256 | `$host_fingerprint_sha256` |
| Run ID | `$run_id` |
| Hostname | `$hostname_value` |
| CPU | `${cpu_model:-unknown}` |
| Physical / logical cores | `${physical_cores:-unknown} / ${logical_cores:-unknown}` |
| Threads per core | `${threads_per_core:-unknown}` |
| NUMA nodes | `${numa_nodes:-unknown}` |
| Memory bytes | `${memory_bytes:-unknown}` |
| CPU governor | `${cpu_governor:-unknown}` |
| Containerized | `$containerized` |
| Kernel | `$kernel` |
| Java | `$java_version` |
| Maven | `$maven_version` |
| OpenSSL | `$openssl_version` |
| Captured at | `$captured_at` |

> `controlled` evidence is meaningful only when the runner identity and hardware are intentionally fixed across runs.
EOF
