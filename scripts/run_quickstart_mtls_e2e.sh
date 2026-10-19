#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: run_quickstart_mtls_e2e.sh (built JARs, OpenSSL and a running Nacos are required)" >&2
  exit 2
fi

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/quickstart_e2e_common.sh"
if ! command -v openssl >/dev/null; then
  echo "OpenSSL is required to generate isolated Quickstart certificates." >&2
  exit 1
fi
quickstart_prepare mtls
PROVIDER_APP=otryx-quickstart-mtls-provider
CONSUMER_APP=otryx-quickstart-mtls-consumer
CERT_DIR="$QUICKSTART_WORK_DIR/pki"
mkdir -m 700 "$CERT_DIR"
umask 077

# Test-only PKI stays outside target and is removed by the owned-process cleanup trap.
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 \
  -subj '/CN=OTRYX Quickstart Test CA' \
  -keyout "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" \
  >"$QUICKSTART_WORK_DIR/pki.log" 2>&1
create_certificate() {
  local name="$1" usage="$2" serial="$3"
  openssl req -new -newkey rsa:2048 -sha256 -nodes \
    -subj "/CN=otryx-quickstart-$name" \
    -keyout "$CERT_DIR/$name.key" -out "$CERT_DIR/$name.csr" \
    >>"$QUICKSTART_WORK_DIR/pki.log" 2>&1
  printf '%s\n' 'basicConstraints=critical,CA:FALSE' \
    'keyUsage=critical,digitalSignature,keyEncipherment' \
    "extendedKeyUsage=$usage" 'subjectAltName=IP:127.0.0.1,DNS:localhost' \
    >"$CERT_DIR/$name.ext"
  openssl x509 -req -sha256 -days 2 -in "$CERT_DIR/$name.csr" \
    -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -set_serial "$serial" \
    -extfile "$CERT_DIR/$name.ext" -out "$CERT_DIR/$name.crt" \
    >>"$QUICKSTART_WORK_DIR/pki.log" 2>&1
}
create_certificate provider serverAuth 1
create_certificate allowed clientAuth 2
create_certificate denied clientAuth 3
ALLOWED_SHA256="$(openssl x509 -in "$CERT_DIR/allowed.crt" -outform DER \
  | openssl dgst -sha256 -r | awk '{print $1}')"
if [[ ! "$ALLOWED_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Could not calculate the verified leaf certificate fingerprint." >&2
  exit 1
fi

quickstart_bootstrap
OTRYX_QUICKSTART_TLS_CERT="$CERT_DIR/provider.crt" \
OTRYX_QUICKSTART_TLS_KEY="$CERT_DIR/provider.key" \
OTRYX_QUICKSTART_TLS_CA="$CERT_DIR/ca.crt" \
java -jar "$PROVIDER_JAR" \
  --spring.profiles.active=mtls \
  --spring.application.name="$PROVIDER_APP" \
  --otryx.rpc.registry.application-name="$PROVIDER_APP" \
  --otryx.rpc.registry.instance-id=provider-main \
  --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
  --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
  --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
  --otryx.rpc.quickstart.role=leaf \
  --otryx.rpc.quickstart.details-enabled=true \
  --otryx.rpc.quickstart.mtls.allowed-client-sha256="$ALLOWED_SHA256" \
  --otryx.rpc.server.port=19143 \
  --otryx.rpc.server.advertised-port=19143 >"$QUICKSTART_WORK_DIR/provider.log" 2>&1 &
PROVIDER_PID=$!
quickstart_track_pid "$PROVIDER_PID"
quickstart_wait_provider "$PROVIDER_PID" "$QUICKSTART_WORK_DIR/provider.log" 19143

run_client() {
  local stage="$1" certificate="$2" details="$3" denied="$4" marker
  local log="$QUICKSTART_WORK_DIR/$stage.log"
  if [[ "$denied" == true ]]; then
    marker='RPC authorization denial verified.'
  else
    marker='RPC quickstart completed successfully: Hello, OTRYX RPC!'
  fi
  OTRYX_QUICKSTART_TLS_CERT="$CERT_DIR/$certificate.crt" \
  OTRYX_QUICKSTART_TLS_KEY="$CERT_DIR/$certificate.key" \
  OTRYX_QUICKSTART_TLS_CA="$CERT_DIR/ca.crt" \
  java -jar "$CONSUMER_JAR" \
    --spring.profiles.active=mtls \
    --spring.application.name="$CONSUMER_APP" \
    --otryx.rpc.registry.application-name="$CONSUMER_APP" \
    --otryx.rpc.registry.instance-id=consumer-main \
    --otryx.rpc.registry.endpoints="$NACOS_ENDPOINT" \
    --otryx.rpc.registry.namespace="$NACOS_NAMESPACE" \
    --otryx.rpc.registry.nacos.group="$NACOS_GROUP" \
    --otryx.rpc.quickstart.use-relay=false \
    --otryx.rpc.quickstart.use-details="$details" \
    --otryx.rpc.quickstart.expect-authorization-denied="$denied" \
    --otryx.rpc.quickstart.repeat-interval-millis=0 >"$log" 2>&1 &
  local pid=$!
  quickstart_track_pid "$pid"
  # The Java probe accepts only the exact remote authorization rejection.
  # TLS negotiation failure, discovery failure, or another status cannot emit this marker.
  quickstart_wait_log "$pid" "$log" "$marker"
  quickstart_snapshot mtls "$stage" "" "$PROVIDER_APP" "$CONSUMER_APP"
  quickstart_stop_pid "$pid"
  quickstart_record "mTLS outcome verified: stage=$stage marker=$marker"
}

run_client allowed-initial allowed false false
run_client identity-denied denied false true
run_client allowed-repeat allowed false false
run_client service-denied allowed true true
kill -0 "$PROVIDER_PID"
quickstart_record "mTLS verified identity and service authorization: PASS"
