#!/usr/bin/env bash
# Opt-in extra Amazon telemetry/advertising package disables for Fire OS 6.
# Packages are disabled for user 0, never uninstalled. Undo with restore-fireos6-extras.sh.
# Usage: TV_HOST=192.168.0.108 debloat/apply-fireos6-extras.sh [--dry-run]
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to your Fire TV IP address}"
PY=tools/venv/bin/python
LIST=debloat/fireos6-extra-packages.txt
DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1
[[ $# -le 1 ]] || { echo "usage: $0 [--dry-run]" >&2; exit 2; }
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
SDK=$("$PY" -I tools/tv.py getprop ro.build.version.sdk | tr -d '\r')
BUILD=$("$PY" -I tools/tv.py getprop ro.build.display.id | tr -d '\r')
MODEL=$("$PY" -I tools/tv.py getprop ro.product.model | tr -d '\r')
[[ "$SDK" == "25" ]] || { echo "Refusing: expected Fire OS 6 / API 25, got API $SDK" >&2; exit 1; }
[[ "$BUILD" == "NS6711" ]] || { echo "Refusing: expected NS6711 build, got '$BUILD'" >&2; exit 1; }
echo "Target: model=$MODEL build=$BUILD API=$SDK"
echo "This list is opt-in and not validated package-by-package on this exact TV."
PACKAGES=$("$PY" -I tools/tv.py 'pm list packages')
COMMANDS=$(mktemp)
trap 'rm -f "$COMMANDS"' EXIT
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%%#*}"
  pkg=$(printf '%s' "$line" | tr -d '[:space:]')
  [[ -z "$pkg" ]] && continue
  if ! printf '%s\n' "$PACKAGES" | grep -Fqx "package:$pkg"; then
    printf 'SKIP (not installed): %s\n' "$pkg"
    continue
  fi
  if printf '%s\n' "$PACKAGES" | grep -Fqx "package:$pkg" && [[ $DRY_RUN == 1 ]]; then
    printf 'WOULD DISABLE: %s\n' "$pkg"
  else
    printf 'pm disable-user --user 0 %s\n' "$pkg" >> "$COMMANDS"
  fi
done < "$LIST"
if [[ $DRY_RUN == 1 ]]; then
  echo "Dry run only; no changes made."
  exit 0
fi
[[ -s "$COMMANDS" ]] || { echo "No installed candidate packages found; nothing changed."; exit 0; }
echo
echo "The following installed packages will be disabled (not uninstalled):"
cat "$COMMANDS"
read -r -p "Apply these reversible changes? [y/N] " reply
case "$reply" in y|Y|yes|YES) ;; *) echo "Cancelled."; exit 0 ;; esac
"$PY" -I tools/tv.py --batch < "$COMMANDS"
echo
echo "Done. Reboot and test Home, Settings, Wi-Fi, Appstore, YouTube, Tubi and Pluto TV."
echo "To undo, run: TV_HOST=$TV_HOST debloat/restore-fireos6-extras.sh"
