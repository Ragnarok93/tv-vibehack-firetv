#!/usr/bin/env bash
# Undo the speed pass: re-enable everything in debloat-speed.txt and reset the animation scales.
#
# Usage: TV_HOST=192.168.1.50 debloat/restore-speed.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
{
  grep -vE '^\s*(#|$)' "$HERE/debloat-speed.txt" | sed -E 's/\s+#.*$//' | awk '{print "pm enable --user 0 " $1}'
  echo "settings delete global window_animation_scale"
  echo "settings delete global transition_animation_scale"
  echo "settings delete global animator_duration_scale"
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
echo "Restored. Reboot the TV to bring the services back."
