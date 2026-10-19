#!/usr/bin/env bash
set -euo pipefail

OLD_ROOT="${1:?old checkout root is required}"
NEW_ROOT="${2:?new checkout root is required}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NACOS_ENDPOINT="${NACOS_TEST_ENDPOINT:-127.0.0.1:8848}"
NACOS_NAMESPACE=public
PORT="${OTRYX_RPC_COMPAT_PORT:-19090}"
readonly DEFAULT_STOP_SECONDS=35
readonly STOP_POLL_SECONDS=0.1
STOP_SECONDS="${ROLLING_COMPAT_STOP_SECONDS:-$DEFAULT_STOP_SECONDS}"
if [[ ! "$STOP_SECONDS" =~ ^[1-9][0-9]?$ ]] || (( STOP_SECONDS > DEFAULT_STOP_SECONDS )); then
  echo "ROLLING_COMPAT_STOP_SECONDS must be an integer from 1 to 35." >&2
  exit 1
fi
# Consume script-owned settings; preserve legitimate application environment.
unset OTRYX_RPC_COMPAT_PORT ROLLING_COMPAT_STOP_SECONDS
REGISTRY_GENERATION="$(python3 "$SCRIPT_DIR/check_registry_generation_boundary.py" "$OLD_ROOT" "$NEW_ROOT")"
case "$REGISTRY_GENERATION" in
  same-generation) ;;
  *) echo "Unrecognized registry generation result." >&2; exit 1 ;;
esac

find_exec() {
  local root="$1"
  local module="$2"
  find "$root/otryx-quickstart/$module/target" -maxdepth 1 -name '*-exec.jar' -print -quit
}

OLD_PROVIDER="$(find_exec "$OLD_ROOT" otryx-quickstart-provider)"
OLD_CONSUMER="$(find_exec "$OLD_ROOT" otryx-quickstart-consumer)"
NEW_PROVIDER="$(find_exec "$NEW_ROOT" otryx-quickstart-provider)"
NEW_CONSUMER="$(find_exec "$NEW_ROOT" otryx-quickstart-consumer)"

for jar in "$OLD_PROVIDER" "$OLD_CONSUMER" "$NEW_PROVIDER" "$NEW_CONSUMER"; do
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "Compatibility executable JAR is missing: $jar" >&2
    exit 1
  fi
done

PROVIDER_PID=""
CONSUMER_PID=""
PROVIDER_LOG="$(mktemp)"
CONSUMER_LOG="$(mktemp)"
NACOS_GROUP="OTRYX_RPC_E2E_$(basename "$PROVIDER_LOG")"

stop_owned_process() {
  local pid="$1" deadline=$((SECONDS + STOP_SECONDS))
  [[ -n "$pid" ]] || return 0
  kill "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do
    sleep "$STOP_POLL_SECONDS"
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "Compatibility process stop budget exhausted; forcing termination." >&2
    kill -KILL "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
}

cleanup() {
  local status=$?
  trap - EXIT
  stop_owned_process "$CONSUMER_PID"
  stop_owned_process "$PROVIDER_PID"
  CONSUMER_PID=""
  PROVIDER_PID=""
  rm -f "$PROVIDER_LOG" "$CONSUMER_LOG"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

start_provider() {
  local jar="$1"
  local label="$2"
  : >"$PROVIDER_LOG"

  java -jar "$jar" \
    --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
    --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
    --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
    --otryx.rpc.server.port="$PORT" \
    --otryx.rpc.server.advertised-port="$PORT" \
    >"$PROVIDER_LOG" 2>&1 &
  PROVIDER_PID=$!

  for _ in {1..200}; do
    if ! kill -0 "$PROVIDER_PID" 2>/dev/null; then
      echo "$label Provider exited before readiness." >&2
      cat "$PROVIDER_LOG" >&2
      exit 1
    fi
    if timeout 1 bash -c "</dev/tcp/127.0.0.1/$PORT" 2>/dev/null; then
      sleep 1
      return
    fi
    sleep 0.1
  done

  echo "$label Provider did not become ready." >&2
  cat "$PROVIDER_LOG" >&2
  exit 1
}

stop_provider() {
  if [[ -z "$PROVIDER_PID" ]]; then
    return
  fi
  stop_owned_process "$PROVIDER_PID"
  PROVIDER_PID=""
  sleep 1
}

stop_consumer() {
  if [[ -z "$CONSUMER_PID" ]]; then
    return
  fi
  stop_owned_process "$CONSUMER_PID"
  CONSUMER_PID=""
}

run_consumer() {
  local jar="$1"
  local label="$2"

  for _ in {1..4}; do
    : >"$CONSUMER_LOG"
    java -jar "$jar" \
      --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
      --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
      --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
      >"$CONSUMER_LOG" 2>&1 &
    CONSUMER_PID=$!

    for _ in {1..300}; do
      if grep -q "RPC quickstart completed successfully: Hello, OTRYX RPC!" "$CONSUMER_LOG"; then
        stop_consumer
        echo "Compatibility PASS: $label"
        return
      fi
      if ! kill -0 "$CONSUMER_PID" 2>/dev/null; then
        stop_consumer
        break
      fi
      sleep 0.1
    done

    stop_consumer
    sleep 1
  done

  echo "Compatibility FAIL: $label" >&2
  echo "----- provider log -----" >&2
  cat "$PROVIDER_LOG" >&2
  echo "----- consumer log -----" >&2
  cat "$CONSUMER_LOG" >&2
  exit 1
}

# Both checkouts use application membership. Bootstrap the new isolated scope
# before either version starts an application writer.
bash "$SCRIPT_DIR/bootstrap_quickstart_nacos.sh" \
  "$NEW_PROVIDER" "$NACOS_ENDPOINT" "$NACOS_NAMESPACE" "$NACOS_GROUP"

# N Consumer -> N Provider.
start_provider "$OLD_PROVIDER" "N"
run_consumer "$OLD_CONSUMER" "N consumer -> N provider"

run_consumer "$NEW_CONSUMER" "N+1 consumer -> N provider"

# N Consumer -> N+1 Provider and N+1 Consumer -> N+1 Provider.
stop_provider
start_provider "$NEW_PROVIDER" "N+1"
run_consumer "$OLD_CONSUMER" "N consumer -> N+1 provider"
run_consumer "$NEW_CONSUMER" "N+1 consumer -> N+1 provider"

# Roll back Provider to N while keeping N+1 Consumer.
stop_provider
start_provider "$OLD_PROVIDER" "N rollback"
run_consumer "$NEW_CONSUMER" "N+1 consumer -> N rollback provider"
echo "OTRYX RPC N/N+1 rolling compatibility matrix passed."
