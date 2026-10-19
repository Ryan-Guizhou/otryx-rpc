#!/usr/bin/env bash
set -euo pipefail

# Process-recovery controls are not Memory application configuration properties.
unset OTRYX_RPC_E2E_RESTART_NACOS OTRYX_RPC_E2E_MAX_STALENESS

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$(find "$ROOT_DIR/otryx-quickstart/otryx-quickstart-memory/target" \
  -maxdepth 1 -name '*-exec.jar' -print -quit)"
if [[ -z "$JAR" ]]; then
  echo "Quickstart memory executable JAR is missing; build otryx-quickstart first." >&2
  exit 1
fi

LOG_FILE="$(mktemp)"
APP_PID=""
cleanup() {
  if [[ -n "$APP_PID" ]]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  rm -f "$LOG_FILE"
}
trap cleanup EXIT

java -jar "$JAR" >"$LOG_FILE" 2>&1 &
APP_PID=$!
for _ in {1..240}; do
  if grep -Fq "Quickstart memory RPC succeeded: Hello, Quickstart!" "$LOG_FILE"; then
    echo "Memory Quickstart: PASS (real RPC, no external registry)"
    exit 0
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "Memory Quickstart terminated without a successful round trip." >&2
    cat "$LOG_FILE" >&2
    exit 1
  fi
  sleep 0.1
done

echo "Memory Quickstart exceeded its startup budget." >&2
cat "$LOG_FILE" >&2
exit 1
