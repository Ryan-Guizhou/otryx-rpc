#!/usr/bin/env bash
set -euo pipefail

if (( $# != 4 )); then
  echo "Usage: bootstrap_quickstart_nacos.sh <provider-exec.jar> <endpoint> <namespace> <group>" >&2
  exit 2
fi

PROVIDER_JAR="$1"
NACOS_ENDPOINT="$2"
NACOS_NAMESPACE="$3"
NACOS_GROUP="$4"
BOOTSTRAP_TIMEOUT_SECONDS=120
BOOTSTRAP_KILL_AFTER_SECONDS=5

if [[ ! -f "$PROVIDER_JAR" ]]; then
  echo "Executable Quickstart Provider JAR was not found. Build the reactor first." >&2
  exit 1
fi
for value in "$NACOS_ENDPOINT" "$NACOS_NAMESPACE" "$NACOS_GROUP"; do
  if [[ -z "${value//[[:space:]]/}" ]]; then
    echo "Nacos bootstrap endpoint, namespace and group must be explicit." >&2
    exit 2
  fi
done

# Run once in an exclusive scope before any application starts. Never bootstrap
# from a Provider restart or Nacos recovery callback while applications are live.
exec timeout --kill-after="${BOOTSTRAP_KILL_AFTER_SECONDS}s" "${BOOTSTRAP_TIMEOUT_SECONDS}s" java \
  -Dloader.main=com.peachsoft.otryx.registry.nacos.NacosV2MetadataBootstrap \
  -cp "$PROVIDER_JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  "$NACOS_ENDPOINT" "$NACOS_NAMESPACE" "$NACOS_GROUP"
