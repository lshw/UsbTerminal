#!/bin/bash
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SIGNING_PROPS="$SCRIPT_DIR/signing.properties"
RELEASE_DIR="$SCRIPT_DIR/app/build/outputs/apk/release"
SIGNED_APK="$RELEASE_DIR/app-release.apk"
UNSIGNED_APK="$RELEASE_DIR/app-release-unsigned.apk"
FINAL_APK="$SCRIPT_DIR/app-release-signed.apk"

if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/android-studio/jbr/bin/javac ]; then
  export JAVA_HOME=/opt/android-studio/jbr
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -z "${GRADLE_USER_HOME:-}" ]; then
  export GRADLE_USER_HOME="$SCRIPT_DIR/.gradle"
fi

./gradlew build

if [ ! -f "$SIGNING_PROPS" ]; then
  if [ -f "$UNSIGNED_APK" ]; then
    echo "missing signing.properties, unsigned release APK is available at app/build/outputs/apk/release/app-release-unsigned.apk"
    exit 0
  fi

  if [ -f "$SIGNED_APK" ]; then
    echo "missing signing.properties, release APK is available at app/build/outputs/apk/release/app-release.apk"
    exit 0
  fi

  echo "missing signing.properties and no release APK was produced"
  exit 1
fi

store_file="$(sed -n 's/^storeFile=//p' "$SIGNING_PROPS")"
store_password="$(sed -n 's/^storePassword=//p' "$SIGNING_PROPS")"
key_alias="$(sed -n 's/^keyAlias=//p' "$SIGNING_PROPS")"
key_password="$(sed -n 's/^keyPassword=//p' "$SIGNING_PROPS")"

if [ -f "$SIGNED_APK" ]; then
  cp "$SIGNED_APK" "$FINAL_APK"
  echo "signed release APK is available at app-release-signed.apk"
  exit 0
fi

if [ ! -f "$UNSIGNED_APK" ]; then
  echo "release build completed but no APK was found in $RELEASE_DIR"
  exit 1
fi

/opt/android-sdk/build-tools/36.0.0/apksigner sign \
  --ks "$store_file" \
  --ks-key-alias "$key_alias" \
  --ks-pass "pass:$store_password" \
  --key-pass "pass:$key_password" \
  --out "$FINAL_APK" \
  "$UNSIGNED_APK"
