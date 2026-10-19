#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OUTPUT_DIR="${RPC_COMPARISON_OUTPUT_DIR:-target/rpc-comparison-smoke}"
OTRYX_JAR="benchmarks/rpc-comparison/otryx/target/otryx-comparison.jar"
DUBBO_JAR="benchmarks/rpc-comparison/dubbo/target/dubbo-comparison.jar"
CONCURRENCY="${RPC_COMPARISON_CONCURRENCY:-16}"
WARMUP="${RPC_COMPARISON_WARMUP_SECONDS:-2}"
DURATION="${RPC_COMPARISON_DURATION_SECONDS:-3}"
PAYLOAD="${RPC_COMPARISON_PAYLOAD_BYTES:-256}"
readonly DEFAULT_STOP_SECONDS=35
readonly DEFAULT_CLIENT_OVERHEAD_SECONDS=120
readonly PROCESS_POLL_SECONDS=0.1
STOP_SECONDS="${RPC_COMPARISON_STOP_SECONDS:-$DEFAULT_STOP_SECONDS}"
CLIENT_OVERHEAD_SECONDS="${RPC_COMPARISON_CLIENT_OVERHEAD_SECONDS:-$DEFAULT_CLIENT_OVERHEAD_SECONDS}"

if [[ ! "$STOP_SECONDS" =~ ^[1-9][0-9]?$ ]] || (( STOP_SECONDS > DEFAULT_STOP_SECONDS )); then
  echo "RPC_COMPARISON_STOP_SECONDS must be an integer from 1 to 35" >&2
  exit 1
fi
if [[ ! "$CLIENT_OVERHEAD_SECONDS" =~ ^[1-9][0-9]{0,2}$ ]] \
    || (( CLIENT_OVERHEAD_SECONDS > DEFAULT_CLIENT_OVERHEAD_SECONDS )); then
  echo "RPC_COMPARISON_CLIENT_OVERHEAD_SECONDS must be an integer from 1 to 120" >&2
  exit 1
fi
# Test overrides only shorten shell budgets; they are not JVM configuration.
unset RPC_COMPARISON_STOP_SECONDS RPC_COMPARISON_CLIENT_OVERHEAD_SECONDS

if [[ ! "$CONCURRENCY" =~ ^[0-9]+$ ]] ||
   [[ ! "$WARMUP" =~ ^[0-9]+$ ]] ||
   [[ ! "$DURATION" =~ ^[0-9]+$ ]] ||
   [[ ! "$PAYLOAD" =~ ^[0-9]+$ ]]; then
  echo "Comparison parameters must be nonnegative integers" >&2
  exit 1
fi

CLIENT_SECONDS="$(python3 - "$WARMUP" "$DURATION" "$CLIENT_OVERHEAD_SECONDS" <<'PY'
import sys

warmup, measurement, overhead = map(int, sys.argv[1:])
if not all(1 <= value <= 2_147_483_647 for value in (warmup, measurement)):
    raise SystemExit("Comparison warmup and measurement must be positive Java integer seconds")
print(warmup + measurement + overhead)
PY
)"

export RPC_COMPARISON_EVIDENCE_CLASS="smoke"
export RPC_COMPARISON_GIT_SHA="$(git rev-parse HEAD)"
export RPC_COMPARISON_RUN_ID="${RPC_COMPARISON_RUN_ID:-smoke-${RPC_COMPARISON_GIT_SHA:0:12}}"
export RPC_COMPARISON_REGISTRY_ENDPOINT="${RPC_COMPARISON_REGISTRY_ENDPOINT:-http://127.0.0.1:2379}"
export RPC_COMPARISON_REGISTRY_NAMESPACE="${RPC_COMPARISON_REGISTRY_NAMESPACE:-comparison-$(date -u +%Y%m%d%H%M%S)-$$}"

# Use an explicitly selected, already running shared Etcd. No process-local mirror is valid evidence.
python3 - <<'PY'
import json
import os
import re
import urllib.parse
import urllib.request

endpoint = os.environ["RPC_COMPARISON_REGISTRY_ENDPOINT"].rstrip("/")
parsed = urllib.parse.urlsplit(endpoint)
if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password \
        or parsed.query or parsed.fragment or parsed.path:
    raise SystemExit("RPC_COMPARISON_REGISTRY_ENDPOINT must be one explicit Etcd HTTP origin without credentials")
if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", os.environ["RPC_COMPARISON_REGISTRY_NAMESPACE"]):
    raise SystemExit("RPC_COMPARISON_REGISTRY_NAMESPACE must be a valid isolated application namespace")
try:
    with urllib.request.urlopen(endpoint + "/health", timeout=5) as response:
        healthy = json.load(response).get("health")
    if healthy not in (True, "true"):
        raise ValueError("Etcd is not healthy")
except Exception as error:
    raise SystemExit("Shared Etcd readiness failed: " + type(error).__name__) from None
PY

mkdir -p "$OUTPUT_DIR"

echo "Building the installed OTRYX RPC dependency graph"
mvn -B -ntp -DskipTests \
  -pl otryx-codegen,otryx-registry/otryx-registry-etcd,otryx-serialization/otryx-serialization-fory,otryx-transport/otryx-transport-vertx \
  -am install

echo "Building isolated Maven runtimes"
mvn -B -ntp -f benchmarks/rpc-comparison/pom.xml clean package

if [[ ! -s "$OTRYX_JAR" || ! -s "$DUBBO_JAR" ]]; then
  echo "Comparison jars were not produced" >&2
  exit 1
fi

python3 - "$OUTPUT_DIR/environment.json" <<'PY'
import json
import os
import platform
import subprocess
import sys

def capture(*command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT).strip()

environment = {
    "schema": "otryx.rpc.comparison.environment.v1",
    "evidence_class": "smoke",
    "run_id": os.environ["RPC_COMPARISON_RUN_ID"],
    "git_sha": os.environ["RPC_COMPARISON_GIT_SHA"],
    "host": platform.node(),
    "os": platform.platform(),
    "python_version": platform.python_version(),
    "java_version": capture("java", "-version"),
    "cpu_count": os.cpu_count(),
    "runner_id": os.getenv("RUNNER_NAME", "local-uncontrolled"),
    "physical_host_fingerprint": None,
    "jvm_flags": os.getenv("JAVA_TOOL_OPTIONS", ""),
    "otryx_registry_backend": "etcd",
    "otryx_registry_namespace": os.environ["RPC_COMPARISON_REGISTRY_NAMESPACE"],
    "registry_traffic_measurement": "not-measured",
}
with open(sys.argv[1], "w", encoding="utf-8") as stream:
    json.dump(environment, stream, indent=2)
PY

find_port() {
  python3 - <<'PY'
import socket
with socket.socket() as s:
    s.bind(("127.0.0.1", 0))
    print(s.getsockname()[1])
PY
}

provider_pid=""
client_pid=""

stop_owned_process() {
  local pid="$1" deadline=$((SECONDS + STOP_SECONDS))
  [[ -n "$pid" ]] || return 0
  kill "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do
    sleep "$PROCESS_POLL_SECONDS"
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "Comparison process stop budget exhausted; forcing termination." >&2
    kill -KILL "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
}

cleanup() {
  local status=$?
  trap - EXIT
  trap '' INT TERM
  stop_owned_process "$client_pid"
  stop_owned_process "$provider_pid"
  client_pid=""
  provider_pid=""
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_client() {
  local framework="$1" deadline=$((SECONDS + CLIENT_SECONDS)) status=0
  while kill -0 "$client_pid" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "${framework} client exceeded its overall budget of ${CLIENT_SECONDS}s." >&2
      stop_owned_process "$client_pid"
      client_pid=""
      return 124
    fi
    sleep "$PROCESS_POLL_SECONDS"
  done
  wait "$client_pid" || status=$?
  client_pid=""
  return "$status"
}

run_one() {
  local framework="$1"
  local jar="$2"
  local port
  port="$(find_port)"
  local provider_log="$OUTPUT_DIR/${framework}-provider.log"

  java -jar "$jar" provider "$port" > "$provider_log" 2>&1 &
  provider_pid="$!"
  local ready=0
  for _ in $(seq 1 90); do
    if grep -q "READY framework=${framework} " "$provider_log"; then
      ready=1
      break
    fi
    if ! kill -0 "$provider_pid" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if (( ready != 1 )); then
    echo "${framework} provider did not become ready" >&2
    tail -n 100 "$provider_log" >&2
    return 1
  fi

  java -jar "$jar" client 127.0.0.1 "$port" \
    "$CONCURRENCY" "$WARMUP" "$DURATION" "$PAYLOAD" \
    "$OUTPUT_DIR/${framework}.json" &
  client_pid=$!
  local client_status=0
  wait_client "$framework" || client_status=$?
  stop_owned_process "$provider_pid"
  provider_pid=""
  return "$client_status"
}

run_one otryx "$OTRYX_JAR"
run_one dubbo "$DUBBO_JAR"

python3 scripts/validate_rpc_comparison.py \
  --otryx "$OUTPUT_DIR/otryx.json" \
  --dubbo "$OUTPUT_DIR/dubbo.json" \
  --environment "$OUTPUT_DIR/environment.json" \
  --report "$OUTPUT_DIR/report.md"

echo "Completed independent-JVM smoke comparison: $OUTPUT_DIR"
