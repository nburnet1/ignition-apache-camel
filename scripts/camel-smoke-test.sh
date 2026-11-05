#!/usr/bin/env bash
# Real Ignition acceptance in an isolated Gateway; never mounts live ignition/ state.
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
signing_dir="$project_dir/docker/signing"
alias="gateway-apache-camel-dev"
mkdir -p "$signing_dir" "$project_dir/docker/camel-modules"
if ! "$JAVA_HOME/bin/keytool" -list -alias "$alias" \
    -keystore "$signing_dir/dev-keystore.p12" -storetype PKCS12 -storepass devpassword >/dev/null 2>&1; then
  "$JAVA_HOME/bin/keytool" -genkeypair -alias "$alias" -keyalg RSA -keysize 2048 \
    -validity 3650 -dname "CN=Ignition Apache Camel Development, O=Local Development, C=US" \
    -keystore "$signing_dir/dev-keystore.p12" -storetype PKCS12 -storepass devpassword
fi
"$JAVA_HOME/bin/keytool" -exportcert -alias "$alias" \
  -keystore "$signing_dir/dev-keystore.p12" -storetype PKCS12 -storepass devpassword \
  -rfc -file "$signing_dir/dev-cert.pem"
"$project_dir/gradlew" build \
  -Pignition.signing.keystoreFile="$signing_dir/dev-keystore.p12" \
  -Pignition.signing.keystorePassword=devpassword \
  -Pignition.signing.certFile="$signing_dir/dev-cert.pem" \
  -Pignition.signing.certAlias="$alias" \
  -Pignition.signing.certPassword=devpassword
cp "$project_dir/build/Ignition-Apache-Camel.modl" "$project_dir/docker/camel-modules/"
compose=(docker compose -f "$project_dir/docker/camel-acceptance.yml")
"${compose[@]}" up -d --wait mqtt postgres
if [[ -n "$("${compose[@]}" ps -q gateway)" ]]; then
  # Keep Gateway service configuration unchanged: its resource state is container-local.
  "${compose[@]}" up -d --no-recreate gateway
  "${compose[@]}" restart gateway
else
  "${compose[@]}" up -d --no-recreate gateway
fi
python3 "$project_dir/scripts/camel-acceptance.py"
python3 "$project_dir/scripts/camel-http-api-key-acceptance.py"
python3 "$project_dir/scripts/camel-tag-glob-acceptance.py"
python3 "$project_dir/scripts/camel-configuration-acceptance.py"
python3 "$project_dir/scripts/camel-messaging-acceptance.py"
if [[ "${CAMEL_MESSAGING_NETWORK:-0}" == "1" ]]; then
  "${compose[@]}" --profile messaging up -d message-peer
  python3 "$project_dir/scripts/camel-messaging-network-acceptance.py"
fi
python3 "$project_dir/scripts/camel-examples.py"
python3 "$project_dir/scripts/camel-kamelet-example.py" --install
python3 "$project_dir/scripts/camel-kamelet-ui-acceptance.py"
python3 "$project_dir/scripts/camel-operations-acceptance.py"
python3 "$project_dir/scripts/camel-controlbus-example.py"
python3 "$project_dir/scripts/camel-idempotent-example.py"
python3 "$project_dir/scripts/camel-extension-acceptance.py"
python3 "$project_dir/scripts/camel-cron-acceptance.py" --stage
printf 'Restarting isolated Gateway to verify persisted routes and module lifecycle...\n'
"${compose[@]}" restart gateway
python3 "$project_dir/scripts/camel-acceptance.py" --verify-restart
python3 "$project_dir/scripts/camel-configuration-acceptance.py" --verify-restart
python3 "$project_dir/scripts/camel-examples.py" --verify-restart
python3 "$project_dir/scripts/camel-kamelet-example.py" --verify
python3 "$project_dir/scripts/camel-controlbus-example.py" --verify-restart
python3 "$project_dir/scripts/camel-extension-acceptance.py" --verify-restart
python3 "$project_dir/scripts/camel-cron-acceptance.py" --verify
python3 "$project_dir/scripts/camel-dependency-impact-acceptance.py"
python3 "$project_dir/scripts/camel-managed-components-acceptance.py" --remove
"${compose[@]}" restart gateway
python3 "$project_dir/scripts/camel-managed-components-acceptance.py" --verify-removed
python3 "$project_dir/scripts/camel-managed-components-acceptance.py" --restore
"${compose[@]}" restart gateway
python3 "$project_dir/scripts/camel-managed-components-acceptance.py" --verify-restored
printf 'Camel acceptance passed. Isolated Gateway left running at http://localhost:9288\n'
