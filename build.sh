#!/bin/bash
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SIGNING_PROPS="$SCRIPT_DIR/signing.properties"

if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/android-studio/jbr/bin/javac ]; then
  export JAVA_HOME=/opt/android-studio/jbr
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ ! -f "$SIGNING_PROPS" ]; then
  echo "missing signing.properties"
  exit 1
fi

store_file="$(sed -n 's/^storeFile=//p' "$SIGNING_PROPS")"
store_password="$(sed -n 's/^storePassword=//p' "$SIGNING_PROPS")"
key_alias="$(sed -n 's/^keyAlias=//p' "$SIGNING_PROPS")"
key_password="$(sed -n 's/^keyPassword=//p' "$SIGNING_PROPS")"

./gradlew build
/opt/android-sdk/build-tools/36.0.0/apksigner sign \
  --ks "$store_file" \
  --ks-key-alias "$key_alias" \
  --ks-pass "pass:$store_password" \
  --key-pass "pass:$key_password" \
  --out app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
