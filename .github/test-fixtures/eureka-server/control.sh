#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TARGET="$ROOT/target"
PID_FILE="$TARGET/eureka.pid"
JAR="$TARGET/eureka-test-server-1.0.0-SNAPSHOT.jar"
PORT="${EUREKA_TEST_PORT:-8761}"
ENDPOINT="http://127.0.0.1:$PORT/eureka/apps"
mkdir -p "$TARGET"

case "${1:-}" in
  start)
    if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      exit 0
    fi
    if [[ ! -f "$JAR" ]]; then
      echo "Eureka test fixture JAR missing" >&2
      exit 1
    fi
    java -jar "$JAR" \
      --server.port="$PORT" \
      --server.address=127.0.0.1 \
      --eureka.client.register-with-eureka=false \
      --eureka.client.fetch-registry=false \
      --eureka.server.enable-self-preservation=false \
      --eureka.server.eviction-interval-timer-in-ms=1000 \
      > "$TARGET/server.log" 2>&1 &
    echo "$!" > "$PID_FILE"
    for attempt in {1..100}; do
      if curl --silent --fail -H "Accept: application/json" "$ENDPOINT" >/dev/null; then
        exit 0
      fi
      if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        tail -n 60 "$TARGET/server.log" >&2 || true
        exit 1
      fi
      sleep 2
    done
    tail -n 60 "$TARGET/server.log" >&2 || true
    exit 1
    ;;
  stop)
    if [[ -f "$PID_FILE" ]]; then
      pid="$(cat "$PID_FILE")"
      kill "$pid" 2>/dev/null || true
      for attempt in {1..40}; do
        if ! kill -0 "$pid" 2>/dev/null; then
          break
        fi
        sleep 0.25
      done
      rm -f "$PID_FILE"
    fi
    ;;
  restart)
    "$0" stop
    "$0" start
    ;;
  *)
    echo "Usage: $0 {start|stop|restart}" >&2
    exit 2
    ;;
esac
