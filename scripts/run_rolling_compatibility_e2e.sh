#!/usr/bin/env bash
set -euo pipefail

OLD_ROOT="${1:?old checkout root is required}"
NEW_ROOT="${2:?new checkout root is required}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NACOS_ENDPOINT="${NACOS_TEST_ENDPOINT:-127.0.0.1:8848}"
NACOS_NAMESPACE=public
PORT="${OTRYX_RPC_COMPAT_PORT:-19090}"
# Consume only this script-owned setting; preserve legitimate application env.
unset OTRYX_RPC_COMPAT_PORT
REGISTRY_GENERATION="$(python3 "$SCRIPT_DIR/check_registry_generation_boundary.py" "$OLD_ROOT" "$NEW_ROOT")"
case "$REGISTRY_GENERATION" in
  same-generation|registry-migration) ;;
  *) echo "Unrecognized registry generation result." >&2; exit 1 ;;
esac

find_exec() {
  local root="$1"
  local module="$2"
  find "$root/otryx-quickstart/$module/target"     -maxdepth 1     -name '*-exec.jar'     -print     -quit
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

cleanup() {
  if [[ -n "$CONSUMER_PID" ]]; then
    kill "$CONSUMER_PID" 2>/dev/null || true
    wait "$CONSUMER_PID" 2>/dev/null || true
  fi
  if [[ -n "$PROVIDER_PID" ]]; then
    kill "$PROVIDER_PID" 2>/dev/null || true
    wait "$PROVIDER_PID" 2>/dev/null || true
  fi
  rm -f "$PROVIDER_LOG" "$CONSUMER_LOG"
}
trap cleanup EXIT

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
  kill "$PROVIDER_PID" 2>/dev/null || true
  wait "$PROVIDER_PID" 2>/dev/null || true
  PROVIDER_PID=""
  sleep 1
}

stop_consumer() {
  if [[ -z "$CONSUMER_PID" ]]; then
    return
  fi
  kill "$CONSUMER_PID" 2>/dev/null || true
  wait "$CONSUMER_PID" 2>/dev/null || true
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
      if grep -q         "RPC quickstart completed successfully: Hello, OTRYX RPC!"         "$CONSUMER_LOG"; then
        stop_consumer
        echo "Compatibility PASS: $label"
        return
      fi
      if ! kill -0 "$CONSUMER_PID" 2>/dev/null; then
        wait "$CONSUMER_PID" 2>/dev/null || true
        CONSUMER_PID=""
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

# The current artifact supplies the explicit CLI; an interface-era baseline need
# not have it. This new isolated scope has no application writers during bootstrap.
bash "$SCRIPT_DIR/bootstrap_quickstart_nacos.sh" \
  "$NEW_PROVIDER" "$NACOS_ENDPOINT" "$NACOS_NAMESPACE" "$NACOS_GROUP"

# N Consumer -> N Provider.
start_provider "$OLD_PROVIDER" "N"
run_consumer "$OLD_CONSUMER" "N consumer -> N provider"

# Mixed snapshots only have a rolling gate when the Starter control plane agrees.
if [[ "$REGISTRY_GENERATION" == "same-generation" ]]; then
  run_consumer "$NEW_CONSUMER" "N+1 consumer -> N provider"
fi

# N Consumer -> N+1 Provider and N+1 Consumer -> N+1 Provider.
stop_provider
start_provider "$NEW_PROVIDER" "N+1"
if [[ "$REGISTRY_GENERATION" == "same-generation" ]]; then
  run_consumer "$OLD_CONSUMER" "N consumer -> N+1 provider"
fi
run_consumer "$NEW_CONSUMER" "N+1 consumer -> N+1 provider"

if [[ "$REGISTRY_GENERATION" == "same-generation" ]]; then
  # Roll back Provider to N while keeping N+1 Consumer.
  stop_provider
  start_provider "$OLD_PROVIDER" "N rollback"
  run_consumer "$NEW_CONSUMER" "N+1 consumer -> N rollback provider"
  echo "OTRYX RPC N/N+1 rolling compatibility matrix passed."
else
  echo "Registry migration verified: N/N and N+1/N+1 independent JVM RPC passed."
  echo "Cross-generation rolling compatibility has not been established."
  echo "Use isolated registry scopes and deploy or roll back matching Provider/Consumer generations."
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      echo "## Explicit interface/application registry generation boundary"
      echo "Both N/N and N+1/N+1 independent Provider/Consumer JVM checks passed."
      echo "Mixed-generation and mixed-generation rollback pairs were not run."
      echo "Cross-generation rolling compatibility has **not** been established."
      echo "Current-version transport, security, recovery and contract CI gates remain required."
    } >> "$GITHUB_STEP_SUMMARY"
  fi
fi
