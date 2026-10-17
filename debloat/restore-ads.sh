#!/usr/bin/env bash
# Re-enable every package in debloat-ads.txt and turn interest-based ads back on.
# Does not change the home screen.
#
# Usage: TV_HOST=192.168.1.50 debloat/restore-ads.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
{
  grep -vE '^\s*(#|$)' "$HERE/debloat-ads.txt" | sed -E 's/\s+#.*$//' | awk '{print "pm enable --user 0 " $1}'
  echo "settings put secure amz_limit_ad_tracking 0"
  echo "settings put secure limit_ad_tracking 0"
  echo "settings put secure non_amz_limit_ad_tracking 0"
  echo "settings put secure advertisingIdApp:interestBasedAds:value 1"
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
