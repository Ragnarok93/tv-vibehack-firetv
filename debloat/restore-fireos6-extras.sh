#!/usr/bin/env bash
# Re-enable packages disabled by apply-fireos6-extras.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to your Fire TV IP address}"
PY=tools/venv/bin/python
LIST=debloat/fireos6-extra-packages.txt
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
{
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    pkg=$(printf '%s' "$line" | tr -d '[:space:]')
    [[ -z "$pkg" ]] || printf 'pm enable --user 0 %s\n' "$pkg"
  done < "$LIST"
  echo 'pm list packages -d'
} | "$PY" -I tools/tv.py --batch
