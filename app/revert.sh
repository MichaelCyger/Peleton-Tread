#!/bin/bash
# Undo everything apply.sh changed and put the Tread back to stock behavior.
set -euo pipefail
ADB="${ADB:-adb}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
STATE="$ROOT/device-state/home-activity.txt"

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "No tablet visible to adb." >&2
  exit 1
fi

"$ADB" shell am stopservice -n com.treadcover/.CoverService || true
"$ADB" shell appops set com.peloton.activity SYSTEM_ALERT_WINDOW allow
"$ADB" shell settings put global lock_service 0
"$ADB" shell pm enable com.peloton.activity
"$ADB" shell pm enable com.onepeloton.sensorstateindicator
"$ADB" shell pm enable com.onepeloton.systempluginui

# Restore the Home component captured before the first apply. Fall back to the
# launcher name seen on the Android 10 Tread this project was exercised on.
HOME_ACT="com.peloton.launcher/.LauncherActivity"
if [ -s "$STATE" ]; then
  SAVED="$(tr -d '\r' < "$STATE")"
  case "$SAVED" in
    */*) HOME_ACT="$SAVED" ;;
  esac
fi
"$ADB" shell cmd package set-home-activity "$HOME_ACT" || true

echo "reverted (reboot the Tread to fully restore Peloton chrome)"
echo "The app is still installed. Remove it with: adb uninstall com.treadcover"
