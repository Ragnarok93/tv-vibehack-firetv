#!/usr/bin/env bash
# Re-enable only packages disabled by the last successful apply-fireos6-extras.sh run.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to your Fire TV IP address}"
PY=tools/venv/bin/python
STATE=backup/fireos6-extra-disabled.txt
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
[ -s "$STATE" ] || { echo "No recorded package changes at $STATE; refusing to enable packages blindly." >&2; exit 1; }
COMMANDS=$(mktemp)
trap 'rm -f "$COMMANDS"' EXIT
while IFS= read -r pkg || [[ -n "$pkg" ]]; do
  pkg=$(printf '%s' "$pkg" | tr -d '[:space:]')
  [[ -z "$pkg" ]] || printf 'pm enable --user 0 %s\n' "$pkg" >> "$COMMANDS"
done < "$STATE"
"$PY" -I tools/tv.py --batch < "$COMMANDS"
rm -f "$STATE"
echo "Re-enabled only packages recorded as changed by the optional apply script."
