#!/usr/bin/env bash
# Shared bounded process ownership and evidence collection for Quickstart scenarios.

QUICKSTART_ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QUICKSTART_PIDS=()
QUICKSTART_STOP_SECONDS=35
QUICKSTART_SNAPSHOT_SECONDS=120
QUICKSTART_READY_SECONDS=45
QUICKSTART_SNAPSHOT=""

quickstart_record() {
  printf '%s\n' "$*" | tee -a "$QUICKSTART_EVIDENCE_DIR/summary.log"
}

quickstart_track_pid() {
  QUICKSTART_PIDS+=("$1")
}

quickstart_forget_pid() {
  local pid kept=()
  for pid in "${QUICKSTART_PIDS[@]}"; do
    if [[ "$pid" != "$1" ]]; then
      kept+=("$pid")
    fi
  done
  QUICKSTART_PIDS=("${kept[@]}")
}

quickstart_stop_pid() {
  local pid="$1" deadline=$((SECONDS + QUICKSTART_STOP_SECONDS))
  [[ -n "$pid" ]] || return 0
  kill "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do
    sleep 0.1
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -KILL "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
  quickstart_forget_pid "$pid"
}

quickstart_cleanup() {
  local status=$? pid deadline=$((SECONDS + QUICKSTART_STOP_SECONDS)) alive
  trap - EXIT
  for pid in "${QUICKSTART_PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  while (( SECONDS < deadline )); do
    alive=false
    for pid in "${QUICKSTART_PIDS[@]}"; do
      if kill -0 "$pid" 2>/dev/null; then alive=true; fi
    done
    if [[ "$alive" == false ]]; then break; fi
    sleep 0.1
  done
  for pid in "${QUICKSTART_PIDS[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
    wait "$pid" 2>/dev/null || true
  done
  if (( status != 0 )); then
    quickstart_record "Quickstart scenario failed; completed snapshots remain in $QUICKSTART_EVIDENCE_DIR"
    # Raw application output is diagnostic only. Artifacts contain snapshots and controlled summary lines.
    for log in "$QUICKSTART_WORK_DIR"/*.log; do
      if [[ -f "$log" ]]; then
        printf 'Diagnostic log: %s\n' "$(basename "$log")" >&2
        tail -n 60 "$log" >&2
      fi
    done
  fi
  rm -rf "$QUICKSTART_WORK_DIR"
  exit "$status"
}

quickstart_prepare() {
  local scenario="$1" executable
  # The process scenario captures these before preparation. Other scenarios must
  # not pass parent-shell script controls to Spring's strict configuration binder.
  unset OTRYX_RPC_E2E_RESTART_NACOS OTRYX_RPC_E2E_MAX_STALENESS
  for executable in java python3 timeout; do
    if ! command -v "$executable" >/dev/null; then
      echo "Required Quickstart command is missing: $executable" >&2
      exit 1
    fi
  done
  local provider=("$QUICKSTART_ROOT_DIR"/otryx-quickstart/otryx-quickstart-provider/target/*-exec.jar)
  local consumer=("$QUICKSTART_ROOT_DIR"/otryx-quickstart/otryx-quickstart-consumer/target/*-exec.jar)
  if (( ${#provider[@]} != 1 || ${#consumer[@]} != 1 )) \
      || [[ ! -f "${provider[0]}" || ! -f "${consumer[0]}" ]]; then
    echo "Exactly one Provider and one Consumer executable JAR are required. Build the reactor first." >&2
    exit 1
  fi
  PROVIDER_JAR="${provider[0]}"
  CONSUMER_JAR="${consumer[0]}"
  NACOS_ENDPOINT="${NACOS_TEST_ENDPOINT:-127.0.0.1:8848}"
  NACOS_NAMESPACE=public
  # The current Starter uses registry.namespace for the ApplicationKey as well.
  APPLICATION_NAMESPACE=public
  local evidence_root="${OTRYX_QUICKSTART_EVIDENCE_DIR:-$QUICKSTART_ROOT_DIR/target/quickstart-e2e}"
  mkdir -p "$evidence_root"
  QUICKSTART_EVIDENCE_DIR="$(mktemp -d "$evidence_root/$scenario.XXXXXXXX")"
  QUICKSTART_WORK_DIR="$(mktemp -d)"
  NACOS_GROUP="OTRYX_RPC_E2E_$(basename "$QUICKSTART_EVIDENCE_DIR")"
  trap quickstart_cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  quickstart_record "Quickstart scenario=$scenario group=$NACOS_GROUP applicationNamespace=$APPLICATION_NAMESPACE"
}

quickstart_bootstrap() {
  bash "$QUICKSTART_ROOT_DIR/scripts/bootstrap_quickstart_nacos.sh" \
    "$PROVIDER_JAR" "$NACOS_ENDPOINT" "$NACOS_NAMESPACE" "$NACOS_GROUP" \
    >"$QUICKSTART_WORK_DIR/bootstrap.log" 2>&1
  quickstart_record "Nacos scope initialized before application startup."
}

quickstart_wait_log() {
  local pid="$1" log="$2" marker="$3" deadline=$((SECONDS + QUICKSTART_READY_SECONDS))
  while (( SECONDS < deadline )); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "Quickstart process exited before the required outcome." >&2
      return 1
    fi
    if grep -Fq "$marker" "$log"; then return 0; fi
    sleep 0.1
  done
  echo "Quickstart process exceeded its readiness or RPC outcome budget." >&2
  return 1
}

quickstart_wait_provider() {
  local pid="$1" log="$2" port="$3"
  quickstart_wait_log "$pid" "$log" "OTRYX RPC server started:"
  if ! grep -Fq "advertised=127.0.0.1:$port" "$log"; then
    echo "Provider did not advertise the required real endpoint." >&2
    return 1
  fi
}

quickstart_success_count() {
  grep -Fc 'RPC quickstart completed successfully: Hello, OTRYX RPC!' "$1" || true
}

quickstart_wait_success_count() {
  local pid="$1" log="$2" expected="$3" deadline=$((SECONDS + QUICKSTART_READY_SECONDS)) count
  while (( SECONDS < deadline )); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "The long-lived Consumer exited before recovery." >&2
      return 1
    fi
    count="$(quickstart_success_count "$log")"
    if (( count >= expected )); then return 0; fi
    sleep 0.1
  done
  echo "The long-lived Consumer did not recover within its RPC outcome budget." >&2
  return 1
}

quickstart_snapshot() {
  local scenario="$1" stage="$2" previous="$3"
  shift 3
  local deadline=$((SECONDS + QUICKSTART_SNAPSHOT_SECONDS)) attempt=0 remaining candidate
  local previous_args=()
  if [[ -n "$previous" ]]; then previous_args=(--previous "$previous"); fi
  while (( SECONDS < deadline )); do
    attempt=$((attempt + 1))
    remaining=$((deadline - SECONDS))
    candidate="$QUICKSTART_EVIDENCE_DIR/$stage-attempt-$attempt.json"
    # Each read has a new output path. Failed reads are never accepted as empty directories.
    if timeout --kill-after=5s "${remaining}s" \
        bash "$QUICKSTART_ROOT_DIR/scripts/observe_quickstart_nacos.sh" \
        "$PROVIDER_JAR" "$NACOS_ENDPOINT" "$NACOS_NAMESPACE" "$NACOS_GROUP" \
        "$APPLICATION_NAMESPACE" "$candidate" "$@" \
        >"$QUICKSTART_WORK_DIR/snapshot.log" 2>&1 \
        && python3 "$QUICKSTART_ROOT_DIR/scripts/assert_quickstart_snapshot.py" \
        "$scenario" "$stage" "$candidate" "${previous_args[@]}" \
        >"$QUICKSTART_WORK_DIR/assertion.log" 2>&1; then
      QUICKSTART_SNAPSHOT="$candidate"
      quickstart_record "Registry snapshot verified: scenario=$scenario stage=$stage file=$(basename "$candidate")"
      return 0
    fi
    quickstart_record "Registry observation has not converged: scenario=$scenario stage=$stage attempt=$attempt"
    sleep 0.5
  done
  echo "Registry observation failed or did not converge within its budget." >&2
  return 1
}
