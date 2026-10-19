#!/usr/bin/env bash
set -euo pipefail

if (( $# < 8 || $# > 23 )); then
  echo "Usage: observe_quickstart_registry.sh <provider-exec.jar> <nacos|etcd|consul|eureka> <endpoints> <namespace> <nacos-group|-> <consul-metadata-endpoints|-> <new-output.json> <application-name>... (maximum 16)" >&2
  exit 2
fi
PROVIDER_JAR="$1"
shift
if [[ ! -f "$PROVIDER_JAR" ]]; then
  echo "Executable Quickstart Provider JAR was not found. Build the reactor first." >&2
  exit 1
fi
if [[ -e "$6" || -L "$6" ]]; then
  echo "The registry snapshot output must be a new file." >&2
  exit 2
fi

# This launcher only reads the selected backend and never creates membership.
exec timeout --kill-after=5s 45s java \
  -Dloader.main=com.peachsoft.otryx.quickstart.provider.QuickstartRegistrySnapshot \
  -cp "$PROVIDER_JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
