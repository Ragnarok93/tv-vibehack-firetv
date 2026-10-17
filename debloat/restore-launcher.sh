#!/usr/bin/env bash
# Remove the tvOS-style launcher and go back to Amazon's home screen.
#
# Usage: TV_HOST=192.168.1.50 debloat/restore-launcher.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
printf '%s\n' \
  "am force-stop com.gathin.tvhome" \
  "pm uninstall com.gathin.tvhome" \
  "cmd package set-home-activity --user 0 com.amazon.tv.launcher/.ui.HomeActivity_vNext" \
  "am start -a android.intent.action.MAIN -c android.intent.category.HOME" \
  | tools/venv/bin/python -I tools/tv.py --batch
echo "Amazon home restored."
