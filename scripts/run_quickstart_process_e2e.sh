#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVIDER_JAR="$(find "$ROOT_DIR/otryx-quickstart/otryx-quickstart-provider/target" \
  -maxdepth 1 -name '*-exec.jar' -print -quit)"
CONSUMER_JAR="$(find "$ROOT_DIR/otryx-quickstart/otryx-quickstart-consumer/target" \
  -maxdepth 1 -name '*-exec.jar' -print -quit)"
NACOS_ENDPOINT="${NACOS_TEST_ENDPOINT:-127.0.0.1:8848}"
NACOS_NAMESPACE=public
RESTART_NACOS="${OTRYX_RPC_E2E_RESTART_NACOS:-${CI:-false}}"
# Explicit freshness budget exercises LKG recovery; short outages may never enter STALE.
MAX_STALENESS="${OTRYX_RPC_E2E_MAX_STALENESS:-3s}"
# These are script controls, not Spring application configuration keys.
unset OTRYX_RPC_E2E_RESTART_NACOS OTRYX_RPC_E2E_MAX_STALENESS

if [[ -z "$PROVIDER_JAR" || -z "$CONSUMER_JAR" ]]; then
  echo "Executable Quickstart JARs were not found. Build the reactor first." >&2
  exit 1
fi

PROVIDER_LOG="$(mktemp)"
CONSUMER_LOG="$(mktemp)"
NACOS_GROUP="OTRYX_RPC_E2E_$(basename "$PROVIDER_LOG")"
PROVIDER_PID=""
CONSUMER_PID=""

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
  local port="$1"
  : >"$PROVIDER_LOG"
  java -jar "$PROVIDER_JAR" \
    --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
    --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
    --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
    --otryx.rpc.server.port="$port" \
    --otryx.rpc.server.advertised-port="$port" \
    >"$PROVIDER_LOG" 2>&1 &
  PROVIDER_PID=$!

  # TCP bind happens before registry registration and STARTED state.
  # Wait for the explicit Provider-ready marker before launching the Consumer.
  for _ in {1..300}; do
    if ! kill -0 "$PROVIDER_PID" 2>/dev/null; then
      echo "Provider process exited before becoming ready." >&2
      cat "$PROVIDER_LOG" >&2
      exit 1
    fi
    if grep -F "OTRYX RPC server started:" "$PROVIDER_LOG" | \
        grep -Fq "advertised=127.0.0.1:$port" \
        && timeout 1 bash -c "</dev/tcp/127.0.0.1/$port" 2>/dev/null; then
      return
    fi
    sleep 0.1
  done

  echo "Provider was not fully registered and ready on port $port." >&2
  cat "$PROVIDER_LOG" >&2
  exit 1
}

stop_provider() {
  if [[ -z "$PROVIDER_PID" ]]; then
    return
  fi
  kill "$PROVIDER_PID"
  wait "$PROVIDER_PID" 2>/dev/null || true
  PROVIDER_PID=""
}

success_count() {
  grep -c "RPC quickstart completed successfully: Hello, OTRYX RPC!" "$CONSUMER_LOG" || true
}

wait_for_success_count() {
  local expected="$1"
  for _ in {1..300}; do
    local current
    current="$(success_count)"
    if (( current >= expected )); then
      return
    fi
    if ! kill -0 "$CONSUMER_PID" 2>/dev/null; then
      echo "Consumer process exited before recovery completed." >&2
      break
    fi
    sleep 0.1
  done

  echo "Independent JVM RPC recovery did not complete successfully." >&2
  echo "----- provider log -----" >&2
  cat "$PROVIDER_LOG" >&2
  echo "----- consumer log -----" >&2
  cat "$CONSUMER_LOG" >&2
  exit 1
}

wait_for_nacos_ports() {
  for _ in {1..180}; do
    if timeout 1 bash -c '</dev/tcp/127.0.0.1/8848' 2>/dev/null \
        && timeout 1 bash -c '</dev/tcp/127.0.0.1/9848' 2>/dev/null; then
      return
    fi
    sleep 0.2
  done
  echo "Nacos did not reopen ports 8848/9848 in time." >&2
  exit 1
}

probe_fresh_consumer() {
  local probe_log
  probe_log="$(mktemp)"
  local probe_pid=""

  for _ in {1..3}; do
    : >"$probe_log"
    java -jar "$CONSUMER_JAR" \
      --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
      --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
      --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
      --otryx.rpc.client.directory-max-staleness="$MAX_STALENESS" \
      >"$probe_log" 2>&1 &
    probe_pid=$!

    for _ in {1..150}; do
      if grep -q "RPC quickstart completed successfully: Hello, OTRYX RPC!" "$probe_log"; then
        kill "$probe_pid" 2>/dev/null || true
        wait "$probe_pid" 2>/dev/null || true
        rm -f "$probe_log"
        return
      fi
      if ! kill -0 "$probe_pid" 2>/dev/null; then
        break
      fi
      sleep 0.1
    done

    kill "$probe_pid" 2>/dev/null || true
    wait "$probe_pid" 2>/dev/null || true
    sleep 1
  done

  echo "A fresh Consumer could not discover the running Provider after Nacos restart." >&2
  cat "$probe_log" >&2
  rm -f "$probe_log"
  exit 1
}

bash "$ROOT_DIR/scripts/bootstrap_quickstart_nacos.sh" \
  "$PROVIDER_JAR" "$NACOS_ENDPOINT" "$NACOS_NAMESPACE" "$NACOS_GROUP"

start_provider 19090

java -jar "$CONSUMER_JAR" \
  --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
  --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
  --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
  --otryx.rpc.client.directory-max-staleness="$MAX_STALENESS" \
  --otryx.rpc.quickstart.repeat-interval-millis=200 \
  --otryx.rpc.client.resilience.circuit-open-duration=500ms \
  --otryx.rpc.client.resilience.outlier-ejection-duration=500ms \
  >"$CONSUMER_LOG" 2>&1 &
CONSUMER_PID=$!

wait_for_success_count 1

# Verify transport recovery with the same Consumer JVM.
stop_provider
successes_before_provider_restart="$(success_count)"
start_provider 19090
wait_for_success_count "$((successes_before_provider_restart + 1))"

if [[ "$RESTART_NACOS" == "true" ]]; then
  nacos_container="$(docker ps --filter 'ancestor=nacos/nacos-server:v3.2.4' --format '{{.ID}}' | head -n 1)"
  if [[ -z "$nacos_container" ]]; then
    echo "Nacos container was not found for the recovery E2E." >&2
    exit 1
  fi

  # The existing data-plane connection must remain usable while the control plane restarts.
  successes_before_nacos_restart="$(success_count)"
  docker restart "$nacos_container" >/dev/null
  wait_for_nacos_ports
  wait_for_success_count "$((successes_before_nacos_restart + 1))"

  # A brand-new Consumer can only succeed if the still-running Provider has restored
  # its ephemeral Nacos registration after the server restart.
  probe_fresh_consumer

  # Move the Provider to a different port. The existing Consumer JVM must receive
  # the recovered Nacos subscription snapshot; a stale 19090 directory cannot pass.
  stop_provider
  successes_before_endpoint_change="$(success_count)"
  start_provider 19091
  wait_for_success_count "$((successes_before_endpoint_change + 1))"
fi

echo "Independent JVM RPC recovery succeeded without restarting the long-lived Consumer."
