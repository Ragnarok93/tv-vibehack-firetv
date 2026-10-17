#!/usr/bin/env bash
# Apply both debloat lists, turn off interest-based ads and halve animation lengths on a Fire TV
# Edition set. Packages are disabled for user 0, never uninstalled. Undo with restore-ads.sh and
# restore-speed.sh in this folder.
#
# Usage: TV_HOST=192.168.1.50 debloat/apply.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
{
  cat "$HERE/debloat-ads.txt" "$HERE/debloat-speed.txt" | grep -vE '^\s*(#|$)' | sed -E 's/\s+#.*$//' \
    | awk '{print "pm disable-user --user 0 " $1}'
  echo "settings put secure amz_limit_ad_tracking 1"
  echo "settings put secure limit_ad_tracking 1"
  echo "settings put secure non_amz_limit_ad_tracking 1"
  echo "settings put secure advertisingIdApp:interestBasedAds:value 0"
  echo "settings put global window_animation_scale 0.5"
  echo "settings put global transition_animation_scale 0.5"
  echo "settings put global animator_duration_scale 0.5"
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
echo "Done. Reboot the TV to stop the disabled services that are still running."
