#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-29/android.jar"
export JAVA_HOME="${JAVA_HOME:-/usr/local/opt/openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$ROOT/build"
mkdir -p "$ROOT/build/classes" "$ROOT/build/dex"
KEYSTORE="$ROOT/debug.keystore"
javac --release 8 -classpath "$PLATFORM" -d "$ROOT/build/classes" \
  $(find "$ROOT/src" -name '*.java')
d8 --lib "$PLATFORM" --min-api 29 --output "$ROOT/build/dex" \
  $(find "$ROOT/build/classes" -name '*.class')
AAPT_RES=""
if [ -d "$ROOT/res" ] && [ -n "$(find "$ROOT/res" -type f)" ]; then
  AAPT_RES="-S $ROOT/res"
fi
"$BUILD_TOOLS/aapt" package -f -M "$ROOT/AndroidManifest.xml" $AAPT_RES -I "$PLATFORM" \
  -F "$ROOT/build/treadcover-unsigned.apk"
(
  cd "$ROOT/build/dex"
  zip -q -j "$ROOT/build/treadcover-unsigned.apk" classes.dex
)
"$BUILD_TOOLS/zipalign" -f 4 \
  "$ROOT/build/treadcover-unsigned.apk" \
  "$ROOT/build/treadcover-aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Tread Cover"
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --out "$ROOT/build/treadcover.apk" \
  "$ROOT/build/treadcover-aligned.apk"
echo "$ROOT/build/treadcover.apk"
