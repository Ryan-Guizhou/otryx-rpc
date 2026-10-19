#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: run_quickstart_revision_e2e.sh (built JARs and a running Nacos are required)" >&2
  exit 2
fi

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/quickstart_e2e_common.sh"
quickstart_prepare revision
PROVIDER_APP=otryx-quickstart-revision-provider
CONSUMER_APP=otryx-quickstart-revision-consumer
BASELINE_PID=""
EXTENDED_PID=""
CONSUMER_PID=""
CONSUMER_LOG="$QUICKSTART_WORK_DIR/consumer.log"

start_revision() {
  local instance="$1" port="$2" details="$3" log="$QUICKSTART_WORK_DIR/$1.log"
  java -jar "$PROVIDER_JAR" \
    --spring.application.name="$PROVIDER_APP" \
    --otryx.rpc.registry.application-name="$PROVIDER_APP" \
    --otryx.rpc.registry.instance-id="$instance" \
    --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
    --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
    --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
    --otryx.rpc.quickstart.role=leaf \
    --otryx.rpc.quickstart.details-enabled="$details" \
    --otryx.rpc.server.port="$port" \
    --otryx.rpc.server.advertised-port="$port" >"$log" 2>&1 &
  local pid=$!
  quickstart_track_pid "$pid"
  if [[ "$instance" == baseline ]]; then BASELINE_PID="$pid"; else EXTENDED_PID="$pid"; fi
  quickstart_wait_provider "$pid" "$log" "$port"
}

observe_revision() {
  quickstart_snapshot revision "$1" "${2:-}" "$PROVIDER_APP" "$CONSUMER_APP"
}

quickstart_bootstrap
start_revision baseline 19090 false
java -jar "$CONSUMER_JAR" \
  --spring.application.name="$CONSUMER_APP" \
  --otryx.rpc.registry.application-name="$CONSUMER_APP" \
  --otryx.rpc.registry.instance-id=consumer-main \
  --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
  --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
  --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
  --otryx.rpc.quickstart.use-relay=false \
  --otryx.rpc.quickstart.use-details=false \
  --otryx.rpc.quickstart.expect-authorization-denied=false \
  --otryx.rpc.quickstart.repeat-interval-millis=200 \
  --otryx.rpc.client.resilience.circuit-open-duration=500ms \
  --otryx.rpc.client.resilience.outlier-ejection-duration=500ms >"$CONSUMER_LOG" 2>&1 &
CONSUMER_PID=$!
quickstart_track_pid "$CONSUMER_PID"
quickstart_wait_success_count "$CONSUMER_PID" "$CONSUMER_LOG" 1
observe_revision baseline
BASELINE_SNAPSHOT="$QUICKSTART_SNAPSHOT"
quickstart_record "Long-lived Consumer started: pid=$CONSUMER_PID instanceId=consumer-main"

# Both instances use this checkout's same executable JAR. Their bound export sets differ.
start_revision extended 19091 true
observe_revision coexist "$BASELINE_SNAPSHOT"

# Exercise the additional contract through a real Consumer and the normal resolver.
java -jar "$CONSUMER_JAR" \
  --spring.application.name=otryx-quickstart-revision-details-probe \
  --otryx.rpc.registry.application-name=otryx-quickstart-revision-details-probe \
  --otryx.rpc.registry.instance-id=details-probe \
  --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
  --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
  --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
  --otryx.rpc.quickstart.use-relay=false \
  --otryx.rpc.quickstart.use-details=true \
  --otryx.rpc.quickstart.expect-authorization-denied=false \
  --otryx.rpc.quickstart.repeat-interval-millis=0 >"$QUICKSTART_WORK_DIR/details-probe.log" 2>&1 &
DETAILS_PID=$!
quickstart_track_pid "$DETAILS_PID"
quickstart_wait_log "$DETAILS_PID" "$QUICKSTART_WORK_DIR/details-probe.log" \
  'RPC details quickstart completed successfully: DETAILS_OK'
quickstart_snapshot revision details-probed "$BASELINE_SNAPSHOT" "$PROVIDER_APP" "$CONSUMER_APP" \
  otryx-quickstart-revision-details-probe
quickstart_stop_pid "$DETAILS_PID"
quickstart_record "Additional exported contract completed a real RPC: DETAILS_OK"

quickstart_stop_pid "$BASELINE_PID"
BASELINE_PID=""
observe_revision promoted "$BASELINE_SNAPSHOT"
successes="$(quickstart_success_count "$CONSUMER_LOG")"
quickstart_wait_success_count "$CONSUMER_PID" "$CONSUMER_LOG" "$((successes + 1))"
quickstart_record "Promotion RPC verified with the original Consumer pid=$CONSUMER_PID"

start_revision baseline 19090 false
observe_revision rollback-coexist "$BASELINE_SNAPSHOT"
quickstart_stop_pid "$EXTENDED_PID"
EXTENDED_PID=""
observe_revision rolled-back "$BASELINE_SNAPSHOT"
successes="$(quickstart_success_count "$CONSUMER_LOG")"
quickstart_wait_success_count "$CONSUMER_PID" "$CONSUMER_LOG" "$((successes + 1))"
quickstart_record "Rollback RPC verified with the original Consumer pid=$CONSUMER_PID"

quickstart_stop_pid "$BASELINE_PID"
BASELINE_PID=""
observe_revision providers-stopped "$BASELINE_SNAPSHOT"
kill -0 "$CONSUMER_PID"
quickstart_record "Two actual application Manifest revisions and rollback: PASS"
