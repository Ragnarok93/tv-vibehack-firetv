#!/usr/bin/env bash
# Build the launcher and install it on a Fire TV (Fire OS 6/7) next to Amazon's home screen.
# Amazon's launcher is protected and stays enabled; HomeKeyService brings this one forward instead.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    [ -d "$j" ] && JAVA_HOME="$j" && break
  done
fi
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"

# Do not silently let Gradle create a new debug keystore: that would change the
# signing certificate and make the APK impossible to install as an update.
DEBUG_KEYSTORE="${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore"
if [ ! -f "$DEBUG_KEYSTORE" ] && [ "${ALLOW_NEW_DEBUG_KEYSTORE:-0}" != "1" ]; then
  echo "Missing persistent Android debug keystore: $DEBUG_KEYSTORE" >&2
  echo "Restore the keystore used for prior installs, or explicitly set ALLOW_NEW_DEBUG_KEYSTORE=1 for a first install." >&2
  exit 1
fi

[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/tvhome.apk" \
  "rm -f /data/local/tmp/tvhome.apk" \
  "pm grant com.gathin.tvhome android.permission.READ_LOGS" \
  "appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow" \
  "am start -n com.gathin.tvhome/.MainActivity" \
  | "$PY" -I ../tools/tv.py --batch
../tools/fast-home.sh
echo "Installed. Press Home on the remote to see it."
echo "After a reboot of the TV, run tools/fast-home.sh again (Home takes 5 s until you do)."
