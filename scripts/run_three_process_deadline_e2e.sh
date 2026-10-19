#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/quickstart_e2e_common.sh"
quickstart_prepare chain
LEAF_APP=otryx-quickstart-chain-leaf
RELAY_APP=otryx-quickstart-chain-relay
CONSUMER_APP=otryx-quickstart-chain-consumer

start_provider() {
  local role="$1" port="$2" app="$3" log="$QUICKSTART_WORK_DIR/$1.log"
  java -jar "$PROVIDER_JAR" \
    --spring.application.name="$app" \
    --otryx.rpc.registry.application-name="$app" \
    --otryx.rpc.registry.instance-id="$role" \
    --otryx.rpc.quickstart.role="$role" \
    --otryx.rpc.quickstart.details-enabled=false \
    "${QUICKSTART_REGISTRY_ARGS[@]}" \
    --otryx.rpc.server.port="$port" \
    --otryx.rpc.server.advertised-port="$port" \
    --otryx.rpc.quickstart.relay-delay-millis=250 \
    --otryx.rpc.client.timeout=10s >"$log" 2>&1 &
  local pid=$!
  quickstart_track_pid "$pid"
  quickstart_wait_provider "$pid" "$log" "$port"
}

quickstart_bootstrap
start_provider leaf 19090 "$LEAF_APP"
start_provider relay 19091 "$RELAY_APP"
java -jar "$CONSUMER_JAR" \
  --spring.application.name="$CONSUMER_APP" \
  --otryx.rpc.registry.application-name="$CONSUMER_APP" \
  --otryx.rpc.registry.instance-id=consumer \
  "${QUICKSTART_REGISTRY_ARGS[@]}" \
  --otryx.rpc.quickstart.use-relay=true \
  --otryx.rpc.quickstart.use-details=false \
  --otryx.rpc.quickstart.expect-authorization-denied=false \
  --otryx.rpc.quickstart.repeat-interval-millis=0 >"$QUICKSTART_WORK_DIR/consumer.log" 2>&1 &
CONSUMER_PID=$!
quickstart_track_pid "$CONSUMER_PID"
quickstart_wait_log "$CONSUMER_PID" "$QUICKSTART_WORK_DIR/consumer.log" \
  'RPC deadline chain completed: CHAIN_OK remainingMillis='
quickstart_snapshot chain complete "" "$LEAF_APP" "$RELAY_APP" "$CONSUMER_APP"
quickstart_record "Three independent JVM deadline chain A -> B -> C verified."
