#!/usr/bin/env bash
set -euo pipefail

if (( $# < 7 || $# > 22 )); then
  echo "Usage: observe_quickstart_nacos.sh <provider-exec.jar> <endpoint> <nacos-namespace> <group> <application-namespace> <new-output.json> <application-name>... (maximum 16)" >&2
  exit 2
fi
PROVIDER_JAR="$1"
shift
if [[ ! -f "$PROVIDER_JAR" ]]; then
  echo "Executable Quickstart Provider JAR was not found. Build the reactor first." >&2
  exit 1
fi
if [[ -e "$5" || -L "$5" ]]; then
  echo "The registry snapshot output must be a new file." >&2
  exit 2
fi

# This launcher only reads the registry. It neither starts Spring nor creates membership.
exec timeout --kill-after=5s 45s java \
  -Dloader.main=com.peachsoft.otryx.quickstart.provider.QuickstartRegistrySnapshot \
  -cp "$PROVIDER_JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
