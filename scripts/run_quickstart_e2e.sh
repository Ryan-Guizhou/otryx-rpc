#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: run_quickstart_e2e.sh (built Quickstart JARs and a running Nacos are required)" >&2
  exit 2
fi
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for executable in java python3 timeout openssl; do
  if ! command -v "$executable" >/dev/null; then
    echo "Required Quickstart command is missing: $executable" >&2
    exit 1
  fi
done

# Every Nacos scenario initializes its own isolated group before starting applications.
for scenario in run_quickstart_memory.sh run_quickstart_process_e2e.sh \
    run_three_process_deadline_e2e.sh run_quickstart_revision_e2e.sh run_quickstart_mtls_e2e.sh; do
  printf 'Running Quickstart scenario: %s\n' "$scenario"
  bash "$SCRIPT_DIR/$scenario"
done
echo "Complete application-level Quickstart suite: PASS"
