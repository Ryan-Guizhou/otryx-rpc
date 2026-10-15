#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:-target/v2d2-preflight}"
mkdir -p "$OUTPUT_DIR"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

command_required() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

for command_name in git java python3 openssl lscpu awk getconf sha256sum; do
  command_required "$command_name"
done

[[ "$(uname -s)" == "Linux" ]] || fail "Controlled performance evidence currently requires Linux"

EVIDENCE_CLASS="${OTRYX_RPC_EVIDENCE_CLASS:-controlled}"
RUNNER_ID="${OTRYX_RPC_RUNNER_ID:-}"
[[ "$EVIDENCE_CLASS" == "controlled" ]] || fail "OTRYX_RPC_EVIDENCE_CLASS must be controlled"
[[ -n "$RUNNER_ID" ]] || fail "OTRYX_RPC_RUNNER_ID is required"
JVM_FLAGS="${OTRYX_RPC_JVM_FLAGS:-}"
[[ -n "$JVM_FLAGS" ]] || fail "OTRYX_RPC_JVM_FLAGS must explicitly define the controlled JVM configuration"
[[ "$RUNNER_ID" != "github-hosted-ephemeral" ]] || fail "A stable runner ID is required"
EXPECTED_RUNNER_ID="${OTRYX_RPC_EXPECT_RUNNER_ID:-}"
if [[ -n "$EXPECTED_RUNNER_ID" && "$RUNNER_ID" != "$EXPECTED_RUNNER_ID" ]]; then
  fail "Runner ID mismatch: expected '$EXPECTED_RUNNER_ID', actual '$RUNNER_ID'"
fi

host_identity_material=""
if [[ -r /etc/machine-id ]]; then
  host_identity_material="$(cat /etc/machine-id)"
fi
if [[ -r /sys/class/dmi/id/product_uuid ]]; then
  host_identity_material="${host_identity_material}|$(cat /sys/class/dmi/id/product_uuid)"
fi
[[ -n "$host_identity_material" ]] || fail "Unable to derive a stable host identity"
HOST_FINGERPRINT_SHA256="$(printf '%s' "$host_identity_material" | sha256sum | awk '{print $1}')"
EXPECTED_HOST_FINGERPRINT_SHA256="${OTRYX_RPC_EXPECT_HOST_FINGERPRINT_SHA256:-}"
if [[ -n "$EXPECTED_HOST_FINGERPRINT_SHA256" && "$HOST_FINGERPRINT_SHA256" != "$EXPECTED_HOST_FINGERPRINT_SHA256" ]]; then
  fail "Host fingerprint mismatch: expected '$EXPECTED_HOST_FINGERPRINT_SHA256', actual '$HOST_FINGERPRINT_SHA256'"
fi

GIT_HEAD="$(git rev-parse HEAD)"
EXPECTED_COMMIT="${OTRYX_RPC_BENCHMARK_COMMIT:-$GIT_HEAD}"
[[ "$EXPECTED_COMMIT" == "$GIT_HEAD" ]] || fail "Benchmark commit $EXPECTED_COMMIT does not match git HEAD $GIT_HEAD"

if [[ "${OTRYX_RPC_REQUIRE_CLEAN_GIT:-true}" == "true" ]] && [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  fail "Controlled evidence requires a clean tracked working tree"
fi

JAVA_LINE="$(java -version 2>&1 | head -n 1)"
JAVA_MAJOR="$(printf '%s' "$JAVA_LINE" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
if [[ -z "$JAVA_MAJOR" ]]; then
  JAVA_MAJOR="$(printf '%s' "$JAVA_LINE" | sed -n 's/.*openjdk \([0-9][0-9]*\).*/\1/p')"
fi
[[ "$JAVA_MAJOR" == "21" ]] || fail "JDK 21 is required, found: $JAVA_LINE"

[[ -x ./mvnw ]] || fail "Controlled run must use the checked-in executable ./mvnw"
MAVEN_VERSION_OUTPUT="$(./mvnw -version 2>&1)" || fail "Maven Wrapper failed"
MAVEN_LINE="$(printf '%s\n' "$MAVEN_VERSION_OUTPUT" | grep -m1 '^Apache Maven ')"
MAVEN_VERSION="$(printf '%s' "$MAVEN_LINE" | awk '{print $3}')"
MAVEN_MAJOR="$(printf '%s' "$MAVEN_VERSION" | cut -d. -f1)"
MAVEN_MINOR="$(printf '%s' "$MAVEN_VERSION" | cut -d. -f2)"
if ! [[ "$MAVEN_MAJOR" =~ ^[0-9]+$ && "$MAVEN_MINOR" =~ ^[0-9]+$ ]]; then
  fail "Unable to parse Maven version: $MAVEN_LINE"
fi
if (( MAVEN_MAJOR < 3 || (MAVEN_MAJOR == 3 && MAVEN_MINOR < 9) )); then
  fail "Maven 3.9+ is required, found: $MAVEN_VERSION"
fi

CPU_MODEL="$(lscpu | awk -F: '/Model name/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}')"
LOGICAL_CORES="$(getconf _NPROCESSORS_ONLN)"
PHYSICAL_CORES="$(lscpu -p=CORE,SOCKET | grep -v '^#' | sort -u | wc -l | tr -d ' ')"
MEMORY_BYTES="$(awk '/MemTotal:/ {printf "%.0f", $2 * 1024}' /proc/meminfo)"
CPU_GOVERNOR="unknown"
if [[ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]]; then
  CPU_GOVERNOR="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
fi

CONTAINERIZED=false
if [[ -f /.dockerenv || -f /run/.containerenv ]]; then
  CONTAINERIZED=true
fi
if [[ "${OTRYX_RPC_REQUIRE_BARE_METAL:-false}" == "true" && "$CONTAINERIZED" == "true" ]]; then
  fail "OTRYX_RPC_REQUIRE_BARE_METAL=true but the runner is containerized"
fi

if [[ -n "${OTRYX_RPC_EXPECT_CPU_MODEL:-}" && "$CPU_MODEL" != "$OTRYX_RPC_EXPECT_CPU_MODEL" ]]; then
  fail "CPU model mismatch: expected '$OTRYX_RPC_EXPECT_CPU_MODEL', found '$CPU_MODEL'"
fi
if [[ -n "${OTRYX_RPC_EXPECT_LOGICAL_CORES:-}" && "$LOGICAL_CORES" != "$OTRYX_RPC_EXPECT_LOGICAL_CORES" ]]; then
  fail "Logical core mismatch: expected $OTRYX_RPC_EXPECT_LOGICAL_CORES, found $LOGICAL_CORES"
fi
if [[ -n "${OTRYX_RPC_EXPECT_PHYSICAL_CORES:-}" && "$PHYSICAL_CORES" != "$OTRYX_RPC_EXPECT_PHYSICAL_CORES" ]]; then
  fail "Physical core mismatch: expected $OTRYX_RPC_EXPECT_PHYSICAL_CORES, found $PHYSICAL_CORES"
fi
if [[ -n "${OTRYX_RPC_EXPECT_CPU_GOVERNOR:-}" && "$CPU_GOVERNOR" != "$OTRYX_RPC_EXPECT_CPU_GOVERNOR" ]]; then
  fail "CPU governor mismatch: expected $OTRYX_RPC_EXPECT_CPU_GOVERNOR, found $CPU_GOVERNOR"
fi
if [[ -n "${OTRYX_RPC_MIN_MEMORY_BYTES:-}" ]] && (( MEMORY_BYTES < OTRYX_RPC_MIN_MEMORY_BYTES )); then
  fail "Memory is below OTRYX_RPC_MIN_MEMORY_BYTES: $MEMORY_BYTES"
fi

RUN_ID="${OTRYX_RPC_EVIDENCE_RUN_ID:-unknown}"
CAPTURED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

cat > "$OUTPUT_DIR/preflight.properties" <<EOF
schema_version=1
status=PASS
captured_at=$CAPTURED_AT
run_id=$RUN_ID
commit=$GIT_HEAD
evidence_class=$EVIDENCE_CLASS
runner_id=$RUNNER_ID
host_fingerprint_sha256=$HOST_FINGERPRINT_SHA256
cpu_model=$CPU_MODEL
logical_cores=$LOGICAL_CORES
physical_cores=$PHYSICAL_CORES
memory_bytes=$MEMORY_BYTES
cpu_governor=$CPU_GOVERNOR
containerized=$CONTAINERIZED
java_major=$JAVA_MAJOR
maven_version=$MAVEN_VERSION
jvm_flags=$JVM_FLAGS
soak_concurrency=${OTRYX_RPC_SOAK_CONCURRENCY:-}
soak_payload_size=${OTRYX_RPC_SOAK_PAYLOAD_SIZE:-}
soak_connections=${OTRYX_RPC_SOAK_CONNECTIONS:-}
soak_warmup_seconds=${OTRYX_RPC_SOAK_WARMUP_SECONDS:-}
soak_duration_seconds=${OTRYX_RPC_SOAK_DURATION_SECONDS:-}
soak_client_timeout_ms=${OTRYX_RPC_SOAK_CLIENT_TIMEOUT_MS:-}
soak_max_error_rate=${OTRYX_RPC_SOAK_MAX_ERROR_RATE:-}
soak_min_observed_inflight=${OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT:-}
soak_max_p99_micros=${OTRYX_RPC_SOAK_MAX_P99_MICROS:-}
EOF

cat > "$OUTPUT_DIR/preflight.md" <<EOF
# V2-D.2-E1 Runner Preflight

**Status:** PASS

- Run ID: `$RUN_ID`
- Commit: `$GIT_HEAD`
- Runner ID: `$RUNNER_ID`
- Host fingerprint SHA-256: `$HOST_FINGERPRINT_SHA256`
- CPU: `$CPU_MODEL`
- Physical / logical cores: `$PHYSICAL_CORES / $LOGICAL_CORES`
- Memory bytes: `$MEMORY_BYTES`
- CPU governor: `$CPU_GOVERNOR`
- Containerized: `$CONTAINERIZED`
- Java major: `$JAVA_MAJOR`
- Maven: `$MAVEN_VERSION`
- JVM flags: `$JVM_FLAGS`
- Captured at: `$CAPTURED_AT`
EOF

echo "V2-D.2-E1 runner preflight passed"
