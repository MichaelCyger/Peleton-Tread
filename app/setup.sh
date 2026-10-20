#!/bin/bash
# Build the app, install it, and apply the home-screen setup.
# Refuses to run if adb cannot see a tablet that has the Tread controller.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"

"$ROOT/build.sh"
"$ADB" install -r "$ROOT/build/treadcover.apk"
"$ROOT/apply.sh"
