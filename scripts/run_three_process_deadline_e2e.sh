#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVIDER_JAR="$(find "$ROOT_DIR/otryx-quickstart/otryx-quickstart-provider/target" -maxdepth 1 -name '*-exec.jar' -print -quit)"
CONSUMER_JAR="$(find "$ROOT_DIR/otryx-quickstart/otryx-quickstart-consumer/target" -maxdepth 1 -name '*-exec.jar' -print -quit)"
NACOS_ENDPOINT="${NACOS_TEST_ENDPOINT:-127.0.0.1:8848}"

if [[ -z "$PROVIDER_JAR" || -z "$CONSUMER_JAR" ]]; then
  echo "Executable Quickstart JARs missing; build otryx-quickstart first." >&2
  exit 1
fi

LOG_DIR="$(mktemp -d)"
LEAF_PID=""
RELAY_PID=""
CONSUMER_PID=""

cleanup() {
  for pid in "$CONSUMER_PID" "$RELAY_PID" "$LEAF_PID"; do
    if [[ -n "$pid" ]]; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  rm -rf "$LOG_DIR"
}
trap cleanup EXIT

start_provider() {
  local role="$1"
  local port="$2"
  local log="$LOG_DIR/$role.log"
  java -jar "$PROVIDER_JAR" \
    --otryx.rpc.quickstart.role="$role" \
    --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
    --otryx.rpc.server.port="$port" \
    --otryx.rpc.server.advertised-port="$port" \
    --otryx.rpc.quickstart.relay-delay-millis=250 \
    --otryx.rpc.client.timeout=10s \
    >"$log" 2>&1 &
  local pid=$!
  if [[ "$role" == "leaf" ]]; then
    LEAF_PID="$pid"
  else
    RELAY_PID="$pid"
  fi
  for _ in {1..300}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "Provider $role exited before ready." >&2
      cat "$log" >&2
      exit 1
    fi
    if grep -Fq "advertised=127.0.0.1:$port" "$log" \
        && grep -Fq "OTRYX RPC server started:" "$log" \
        && timeout 1 bash -c "</dev/tcp/127.0.0.1/$port" 2>/dev/null; then
      return
    fi
    sleep 0.1
  done
  echo "Provider $role not ready on port $port." >&2
  cat "$log" >&2
  exit 1
}

start_provider leaf 19090
start_provider relay 19091

java -jar "$CONSUMER_JAR" \
  --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
  --otryx.rpc.quickstart.use-relay=true \
  >"$LOG_DIR/consumer.log" 2>&1 &
CONSUMER_PID=$!

for _ in {1..300}; do
  if grep -Fq "RPC deadline chain completed: CHAIN_OK remainingMillis=" \
      "$LOG_DIR/consumer.log"; then
    echo "Three independent JVM deadline chain A -> B -> C verified."
    exit 0
  fi
  if ! kill -0 "$CONSUMER_PID" 2>/dev/null; then
    break
  fi
  sleep 0.1
done

echo "Three independent JVM deadline chain failed." >&2
for role in leaf relay consumer; do
  echo "----- $role -----" >&2
  cat "$LOG_DIR/$role.log" >&2
done
exit 1
