#!/bin/bash
# Reapply the on-device setup for the Tread cover.
# Everything here is reversible with revert.sh. None of it touches belt safety:
# person-present auto-stop and the hardware safety key live in the controller
# (com.onepeloton.affernetservice), which is never modified.
set -euo pipefail
ADB="${ADB:-adb}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
STATE="$ROOT/device-state"
mkdir -p "$STATE"

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "No tablet visible to adb. Plug in a data USB-C cable and accept the debugging prompt on the screen." >&2
  exit 1
fi

# A Bike, or a tablet without the tread controller, must not be modified.
if ! "$ADB" shell pm path com.onepeloton.affernetservice | tr -d '\r' | grep -q '^package:'; then
  echo "This tablet does not have the Tread controller (com.onepeloton.affernetservice). Nothing was changed." >&2
  exit 1
fi

# Remember the original Home screen so revert.sh can put that exact screen back.
if [ ! -s "$STATE/home-activity.txt" ]; then
  "$ADB" shell cmd package resolve-activity --brief -c android.intent.category.HOME \
    | tr -d '\r' | tail -1 > "$STATE/home-activity.txt" || true
fi

# 1. Our overlay can draw over other apps.
"$ADB" shell appops set com.treadcover SYSTEM_ALERT_WINDOW allow

# 2. Kiosk enforcer that force-kills Netflix/Prime/NYT. Also drew Peloton's top status bar.
"$ADB" shell pm disable-user --user 0 com.onepeloton.systempluginui

# 3. Tread Lock passcode keypad drawer (UI only; safety stays in the controller).
"$ADB" shell pm disable-user --user 0 com.onepeloton.sensorstateindicator

# 4. Disable the Peloton member app entirely. It draws the "Welcome / Activate your Tread"
#    screen and the subscription nag banner (a SECURE_SYSTEM_OVERLAY the appop can't block).
#    The belt runs through com.onepeloton.affernetservice, which is untouched.
"$ADB" shell pm disable-user --user 0 com.peloton.activity

# 5. Enable the pin-lock-owner service so our app's registered owner is honored.
#    With this on + our owner registered, affernet won't schedule its auto (rogue) re-lock,
#    so the belt stays unlocked after a one-time PIN unlock. (0 would force a re-lock.)
"$ADB" shell settings put global lock_service 1

# 6. Make our launcher the default Home (replaces Peloton's home/welcome).
"$ADB" shell cmd package set-home-activity com.treadcover/.HomeActivity

# 7. Start the metrics overlay + controller client (registers the lock owner, reads the belt).
"$ADB" shell am start-foreground-service -n com.treadcover/.CoverService

echo "applied"
echo "Original Home saved in app/device-state/home-activity.txt (that folder is not part of the repo)."
echo "Before a real run: pull the safety key once and confirm the belt stops."
