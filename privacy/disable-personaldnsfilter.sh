#!/usr/bin/env bash
# Stop DNS filtering without uninstalling the app or deleting its blocklist/config.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to your Fire TV IP address}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
"$PY" -I tools/tv.py 'settings delete secure always_on_vpn_app' >/dev/null 2>&1 || true
"$PY" -I tools/tv.py 'am force-stop dnsfilter.android' >/dev/null 2>&1 || true
"$PY" -I tools/tv.py 'pm disable-user --user 0 dnsfilter.android'
echo "personalDNSfilter disabled; its config and blocklist remain installed."
echo "To use it again: enable the app in Settings > Applications, open it, then check VPN status."
