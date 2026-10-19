#!/usr/bin/env bash
set -euo pipefail

# These controls belong exclusively to the independent development-process probe.
unset OTRYX_RPC_E2E_RESTART_NACOS OTRYX_RPC_E2E_MAX_STALENESS
SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIRECTORY/run_quickstart_mtls_e2e.py" "$@"
