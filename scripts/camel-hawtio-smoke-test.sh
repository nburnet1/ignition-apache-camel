#!/usr/bin/env bash
# Dedicated opt-in fixture; never recreates the acceptance or developer Gateway containers.
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi
signing_dir="$project_dir/docker/signing"
alias="gateway-apache-camel-dev"
mkdir -p "$signing_dir" "$project_dir/build/hawtio-modules"
if ! "$JAVA_HOME/bin/keytool" -list -alias "$alias" -keystore "$signing_dir/dev-keystore.p12" -storetype PKCS12 -storepass devpassword >/dev/null 2>&1; then
  "$JAVA_HOME/bin/keytool" -genkeypair -alias "$alias" -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=Ignition Apache Camel Development, O=Local Development, C=US" \
    -keystore "$signing_dir/dev-keystore.p12" -storetype PKCS12 -storepass devpassword
fi
"$JAVA_HOME/bin/keytool" -exportcert -alias "$alias" -keystore "$signing_dir/dev-keystore.p12" \
  -storetype PKCS12 -storepass devpassword -rfc -file "$signing_dir/dev-cert.pem"
"$project_dir/gradlew" build \
  -Pignition.signing.keystoreFile="$signing_dir/dev-keystore.p12" \
  -Pignition.signing.keystorePassword=devpassword \
  -Pignition.signing.certFile="$signing_dir/dev-cert.pem" \
  -Pignition.signing.certAlias="$alias" -Pignition.signing.certPassword=devpassword
cp "$project_dir/build/Ignition-Apache-Camel.modl" "$project_dir/build/hawtio-modules/"
compose=(docker compose -f "$project_dir/docker/camel-hawtio.yml")
existing="$("${compose[@]}" ps -q gateway)"
if [[ -n "$existing" ]]; then
  # Never let a changed Compose command recreate a configured container: its data
  # is container-local. Existing fixtures need new JVM options in ignition.conf.
  "${compose[@]}" restart gateway
else
  "${compose[@]}" up -d gateway
fi
python3 "$project_dir/scripts/camel-hawtio-acceptance.py"
printf 'Dedicated Hawtio Gateway left running at http://localhost:9488\n'
