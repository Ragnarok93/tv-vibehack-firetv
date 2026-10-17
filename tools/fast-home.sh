#!/usr/bin/env bash
# Start the launcher's fast-Home helper (ShellHome) on the TV. It runs as the adb shell user, which
# Android lets skip the 5 s hold on activity starts after Home. Fire OS refuses adb connections from the
# TV itself, so this has to be run from a computer again after every reboot of the TV.
#
# Usage: TV_HOST=192.168.1.50 tools/fast-home.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
# The trailing sleep keeps the session open until setsid has detached the helper; adbd kills it otherwise.
printf '%s\n' \
  'kill $(pidof tvhome-shell) 2>/dev/null; true' \
  'p=$(pm path com.gathin.tvhome); CLASSPATH=${p#package:} setsid nohup app_process /system/bin --nice-name=tvhome-shell com.gathin.tvhome.ShellHome </dev/null >/dev/null 2>&1 & sleep 1' \
  'sleep 2; echo ping | toybox nc -w 2 127.0.0.1 38275' \
  | "$PY" -I tools/tv.py --batch
