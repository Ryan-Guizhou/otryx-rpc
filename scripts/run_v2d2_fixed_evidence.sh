#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:-target/v2d2-fixed-evidence}"
PROFILE="${OTRYX_RPC_EVIDENCE_PROFILE:-full}"
CONCURRENCY="${OTRYX_RPC_SOAK_CONCURRENCY:-10000}"
DURATION_SECONDS="${OTRYX_RPC_SOAK_DURATION_SECONDS:-3600}"
PAYLOAD_SIZE="${OTRYX_RPC_SOAK_PAYLOAD_SIZE:-256}"
CONNECTIONS="${OTRYX_RPC_SOAK_CONNECTIONS:-4}"
WARMUP_SECONDS="${OTRYX_RPC_SOAK_WARMUP_SECONDS:-30}"
CLIENT_TIMEOUT_MS="${OTRYX_RPC_SOAK_CLIENT_TIMEOUT_MS:-15000}"
RUN_ID="${OTRYX_RPC_EVIDENCE_RUN_ID:-$(basename "$OUTPUT_DIR")}"
BASELINE="${OTRYX_RPC_RUNNER_BASELINE:-}"
MAX_ERROR_RATE="${OTRYX_RPC_SOAK_MAX_ERROR_RATE:-}"
MIN_OBSERVED_INFLIGHT="${OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT:-}"
MAX_P99_MICROS="${OTRYX_RPC_SOAK_MAX_P99_MICROS:-}"


if [[ "$PROFILE" != "full" ]]; then
  echo "Fixed production evidence requires OTRYX_RPC_EVIDENCE_PROFILE=full" >&2
  exit 1
fi
if [[ "${OTRYX_RPC_EVIDENCE_CLASS:-controlled}" != "controlled" ]]; then
  echo "Fixed production evidence requires OTRYX_RPC_EVIDENCE_CLASS=controlled" >&2
  exit 1
fi
if [[ -z "${OTRYX_RPC_RUNNER_ID:-}" ]]; then
  echo "OTRYX_RPC_RUNNER_ID must identify the fixed performance runner" >&2
  exit 1
fi
if (( DURATION_SECONDS < 3600 )); then
  echo "Fixed production evidence requires at least 3600 soak seconds" >&2
  exit 1
fi
if (( CONCURRENCY < 10000 )); then
  echo "Fixed production evidence requires at least 10000 logical callers" >&2
  exit 1
fi
# A configured 10k worker count alone does not prove 10k actual inflight calls.
# Require operator-selected quality thresholds before starting a 60+ minute run.
if [[ -z "$MAX_ERROR_RATE" || -z "$MIN_OBSERVED_INFLIGHT" ]]; then
  echo "Controlled soak requires OTRYX_RPC_SOAK_MAX_ERROR_RATE and OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT" >&2
  exit 1
fi
python3 - "$MAX_ERROR_RATE" "$MIN_OBSERVED_INFLIGHT" "$CONCURRENCY" "$MAX_P99_MICROS" <<'PY'
import math
import sys
max_rate = float(sys.argv[1])
observed = int(sys.argv[2])
configured = int(sys.argv[3])
p99 = sys.argv[4]
if not math.isfinite(max_rate) or not (0 <= max_rate <= 1):
    raise SystemExit("Max error rate must be finite and between 0 and 1")
if not 1 <= observed <= configured:
    raise SystemExit("Min observed inflight must be within configured logical callers")
if p99 and (not math.isfinite(float(p99)) or float(p99) <= 0):
    raise SystemExit("Optional max p99 must be positive")
PY

if [[ -e "$OUTPUT_DIR" && -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
  echo "Refusing to overwrite an existing evidence bundle: $OUTPUT_DIR" >&2
  exit 1
fi

GIT_HEAD="$(git rev-parse HEAD)"
if [[ -n "${OTRYX_RPC_BENCHMARK_COMMIT:-}" && "$OTRYX_RPC_BENCHMARK_COMMIT" != "$GIT_HEAD" ]]; then
  echo "OTRYX_RPC_BENCHMARK_COMMIT does not match git HEAD" >&2
  exit 1
fi

export OTRYX_RPC_EVIDENCE_CLASS=controlled
export OTRYX_RPC_BENCHMARK_COMMIT="$GIT_HEAD"
export OTRYX_RPC_EVIDENCE_RUN_ID="$RUN_ID"
export OTRYX_RPC_SOAK_CONCURRENCY="$CONCURRENCY"
export OTRYX_RPC_SOAK_PAYLOAD_SIZE="$PAYLOAD_SIZE"
export OTRYX_RPC_SOAK_CONNECTIONS="$CONNECTIONS"
export OTRYX_RPC_SOAK_WARMUP_SECONDS="$WARMUP_SECONDS"
export OTRYX_RPC_SOAK_DURATION_SECONDS="$DURATION_SECONDS"
export OTRYX_RPC_SOAK_CLIENT_TIMEOUT_MS="$CLIENT_TIMEOUT_MS"
export OTRYX_RPC_SOAK_MAX_ERROR_RATE="$MAX_ERROR_RATE"
export OTRYX_RPC_SOAK_MIN_OBSERVED_INFLIGHT="$MIN_OBSERVED_INFLIGHT"
export OTRYX_RPC_SOAK_MAX_P99_MICROS="$MAX_P99_MICROS"

mkdir -p "$OUTPUT_DIR"

bash scripts/preflight_v2d2_runner.sh "$OUTPUT_DIR"
bash scripts/capture_v2d2_environment.sh "$OUTPUT_DIR"
OTRYX_RPC_HOST_FINGERPRINT_SHA256="$(awk -F= '$1 == "host_fingerprint_sha256" {print $2; exit}' "$OUTPUT_DIR/environment.properties")"
if [[ ! "$OTRYX_RPC_HOST_FINGERPRINT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid controlled runner fingerprint in environment capture" >&2
  exit 1
fi
export OTRYX_RPC_HOST_FINGERPRINT_SHA256

if [[ -n "$BASELINE" ]]; then
  python3 scripts/check_v2d2_runner_baseline.py     --environment "$OUTPUT_DIR/environment.properties"     --baseline "$BASELINE"     --initialize-if-missing
fi

./mvnw -B -ntp -pl otryx-benchmarks -am clean package -DskipTests

bash scripts/run_v2d2_benchmark_matrix.sh   full   "$OUTPUT_DIR/matrix"

OTRYX_RPC_SOAK_CONCURRENCY="$CONCURRENCY" OTRYX_RPC_SOAK_DURATION_SECONDS="$DURATION_SECONDS" OTRYX_RPC_SOAK_PAYLOAD_SIZE="$PAYLOAD_SIZE" OTRYX_RPC_SOAK_CONNECTIONS="$CONNECTIONS" OTRYX_RPC_SOAK_WARMUP_SECONDS="$WARMUP_SECONDS" OTRYX_RPC_SOAK_CLIENT_TIMEOUT_MS="$CLIENT_TIMEOUT_MS" OTRYX_RPC_SOAK_OUTPUT="$OUTPUT_DIR/soak.json"   bash scripts/run_v2d2_soak.sh

POLICY_ARGS=(
  --soak "$OUTPUT_DIR/soak.json"
  --mode controlled
  --max-error-rate "$MAX_ERROR_RATE"
  --min-observed-inflight "$MIN_OBSERVED_INFLIGHT"
  --output-dir "$OUTPUT_DIR/soak-policy"
)
if [[ -n "$MAX_P99_MICROS" ]]; then
  POLICY_ARGS+=(--max-p99-micros "$MAX_P99_MICROS")
fi
python3 scripts/check_soak_quality.py "${POLICY_ARGS[@]}"

python3 scripts/validate_v2d2_evidence.py   --environment "$OUTPUT_DIR/environment.properties"   --matrix-dir "$OUTPUT_DIR/matrix"   --soak "$OUTPUT_DIR/soak.json"   --profile full   --require-matrix   --require-soak   --require-controlled   --min-soak-seconds 3600   --min-concurrency 10000   --output-dir "$OUTPUT_DIR"

python3 scripts/generate_v2d2_decision_report.py   --environment "$OUTPUT_DIR/environment.properties"   --matrix-summary "$OUTPUT_DIR/matrix/summary.csv"   --soak "$OUTPUT_DIR/soak.json"   --output-dir "$OUTPUT_DIR"

python3 scripts/manage_v2d2_evidence_manifest.py   create   --bundle "$OUTPUT_DIR"
python3 scripts/manage_v2d2_evidence_manifest.py   verify   --bundle "$OUTPUT_DIR"

echo "V2-D.2 controlled evidence bundle completed: $OUTPUT_DIR"
