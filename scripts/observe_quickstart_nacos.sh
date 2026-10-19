#!/usr/bin/env bash
set -euo pipefail

if (( $# < 7 || $# > 22 )); then
  echo "Usage: observe_quickstart_nacos.sh <provider-exec.jar> <endpoint> <nacos-namespace> <group> <application-namespace> <new-output.json> <application-name>... (maximum 16)" >&2
  exit 2
fi
if [[ "$3" != "$5" ]]; then
  echo "The Starter uses one shared registry/application namespace; both arguments must match." >&2
  exit 2
fi
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROVIDER_JAR="$1"
ENDPOINT="$2"
NAMESPACE="$3"
GROUP="$4"
OUTPUT="$6"
shift 6
exec bash "$SCRIPT_DIR/observe_quickstart_registry.sh" \
  "$PROVIDER_JAR" nacos "$ENDPOINT" "$NAMESPACE" "$GROUP" - "$OUTPUT" "$@"
