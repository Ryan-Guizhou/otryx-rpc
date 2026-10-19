#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/quickstart_e2e_common.sh"
DEFAULT_RESTART=false
if [[ "${QUICKSTART_REGISTRY:-nacos}" == nacos ]]; then DEFAULT_RESTART="${CI:-false}"; fi
RESTART_NACOS="${OTRYX_RPC_E2E_RESTART_NACOS:-$DEFAULT_RESTART}"
MAX_STALENESS="${OTRYX_RPC_E2E_MAX_STALENESS:-3s}"
# These script controls are not Spring configuration keys. Preserve normal application settings.
unset OTRYX_RPC_E2E_RESTART_NACOS OTRYX_RPC_E2E_MAX_STALENESS
quickstart_prepare process
if [[ "$RESTART_NACOS" != true && "$RESTART_NACOS" != false ]]; then
  echo "OTRYX_RPC_E2E_RESTART_NACOS must be true or false." >&2
  exit 1
fi
if [[ "$RESTART_NACOS" == true && "$QUICKSTART_REGISTRY" != nacos ]]; then
  echo "The Nacos restart control requires QUICKSTART_REGISTRY=nacos." >&2
  exit 1
fi
PROVIDER_APP=otryx-quickstart-process-provider
CONSUMER_APP=otryx-quickstart-process-consumer
PROVIDER_PID=""
CONSUMER_LOG="$QUICKSTART_WORK_DIR/consumer.log"

start_provider() {
  local port="$1" log="$QUICKSTART_WORK_DIR/provider-$1.log"
  # Clear the old readiness marker before the background shell opens this log.
  : >"$log"
  java -jar "$PROVIDER_JAR" \
    --spring.application.name="$PROVIDER_APP" \
    --otryx.rpc.registry.application-name="$PROVIDER_APP" \
    --otryx.rpc.registry.instance-id=provider-main \
    "${QUICKSTART_REGISTRY_ARGS[@]}" \
    --otryx.rpc.quickstart.role=leaf \
    --otryx.rpc.quickstart.details-enabled=false \
    --otryx.rpc.server.port="$port" \
    --otryx.rpc.server.advertised-port="$port" >"$log" 2>&1 &
  PROVIDER_PID=$!
  quickstart_track_pid "$PROVIDER_PID"
  quickstart_wait_provider "$PROVIDER_PID" "$log" "$port"
}

observe_process() {
  quickstart_snapshot process "$1" "" "$PROVIDER_APP" "$CONSUMER_APP"
}

wait_for_nacos_ports() {
  local deadline=$((SECONDS + QUICKSTART_READY_SECONDS))
  while (( SECONDS < deadline )); do
    if timeout 1 bash -c '</dev/tcp/127.0.0.1/8848' 2>/dev/null \
        && timeout 1 bash -c '</dev/tcp/127.0.0.1/9848' 2>/dev/null; then return 0; fi
    sleep 0.2
  done
  echo "Nacos did not reopen ports 8848/9848 in time." >&2
  return 1
}

probe_fresh_consumer() {
  local attempt pid log="$QUICKSTART_WORK_DIR/fresh-consumer.log"
  for attempt in 1 2 3; do
    # Clear the previous attempt's outcome before the background shell opens this log.
    : >"$log"
    java -jar "$CONSUMER_JAR" \
      --spring.application.name=otryx-quickstart-process-fresh-consumer \
      --otryx.rpc.registry.application-name=otryx-quickstart-process-fresh-consumer \
      --otryx.rpc.registry.instance-id=fresh-consumer \
      "${QUICKSTART_REGISTRY_ARGS[@]}" \
      --otryx.rpc.client.directory-max-staleness="$MAX_STALENESS" \
      --otryx.rpc.quickstart.use-relay=false \
      --otryx.rpc.quickstart.use-details=false \
      --otryx.rpc.quickstart.expect-authorization-denied=false \
      --otryx.rpc.quickstart.repeat-interval-millis=0 >"$log" 2>&1 &
    pid=$!
    quickstart_track_pid "$pid"
    if quickstart_wait_log "$pid" "$log" 'RPC quickstart completed successfully: Hello, OTRYX RPC!'; then
      quickstart_snapshot process fresh-consumer "" "$PROVIDER_APP" "$CONSUMER_APP" \
        otryx-quickstart-process-fresh-consumer
      quickstart_stop_pid "$pid"
      quickstart_record "A fresh Consumer completed RPC after the registry restart."
      return 0
    fi
    quickstart_stop_pid "$pid"
  done
  echo "A fresh Consumer could not discover the Provider after Nacos restart." >&2
  return 1
}

quickstart_bootstrap
start_provider 19090
java -jar "$CONSUMER_JAR" \
  --spring.application.name="$CONSUMER_APP" \
  --otryx.rpc.registry.application-name="$CONSUMER_APP" \
  --otryx.rpc.registry.instance-id=consumer-main \
  "${QUICKSTART_REGISTRY_ARGS[@]}" \
  --otryx.rpc.client.directory-max-staleness="$MAX_STALENESS" \
  --otryx.rpc.quickstart.use-relay=false \
  --otryx.rpc.quickstart.use-details=false \
  --otryx.rpc.quickstart.expect-authorization-denied=false \
  --otryx.rpc.quickstart.repeat-interval-millis=200 \
  --otryx.rpc.client.resilience.circuit-open-duration=500ms \
  --otryx.rpc.client.resilience.outlier-ejection-duration=500ms >"$CONSUMER_LOG" 2>&1 &
CONSUMER_PID=$!
quickstart_track_pid "$CONSUMER_PID"
quickstart_wait_success_count "$CONSUMER_PID" "$CONSUMER_LOG" 1
observe_process initial
quickstart_record "Long-lived Consumer started: pid=$CONSUMER_PID instanceId=consumer-main"

# The stopped Provider must leave without removing the independently registered Consumer.
quickstart_stop_pid "$PROVIDER_PID"
PROVIDER_PID=""
observe_process provider-stopped
successes="$(quickstart_success_count "$CONSUMER_LOG")"
start_provider 19090
quickstart_wait_success_count "$CONSUMER_PID" "$CONSUMER_LOG" "$((successes + 1))"
observe_process provider-restarted

if [[ "$RESTART_NACOS" == true ]]; then
  if [[ "$QUICKSTART_REGISTRY_ENDPOINT" != 127.0.0.1:8848 ]]; then
    echo "The Nacos container restart scenario requires the explicit local test endpoint." >&2
    exit 1
  fi
  mapfile -t nacos_containers < <(docker ps --filter 'ancestor=nacos/nacos-server:v3.2.4' --format '{{.ID}}')
  if (( ${#nacos_containers[@]} != 1 )); then
    echo "Exactly one test Nacos container is required for the recovery E2E." >&2
    exit 1
  fi
  successes="$(quickstart_success_count "$CONSUMER_LOG")"
  timeout --kill-after=5s 120s docker restart "${nacos_containers[0]}" >/dev/null
  wait_for_nacos_ports
  quickstart_wait_success_count "$CONSUMER_PID" "$CONSUMER_LOG" "$((successes + 1))"
  observe_process registry-recovered
  probe_fresh_consumer

  # A stale pre-restart directory pointing at 19090 cannot complete this phase.
  quickstart_stop_pid "$PROVIDER_PID"
  PROVIDER_PID=""
  successes="$(quickstart_success_count "$CONSUMER_LOG")"
  start_provider 19091
  quickstart_wait_success_count "$CONSUMER_PID" "$CONSUMER_LOG" "$((successes + 1))"
  observe_process endpoint-changed
fi

kill -0 "$CONSUMER_PID"
quickstart_record "Independent JVM RPC recovery succeeded without restarting the long-lived Consumer."
