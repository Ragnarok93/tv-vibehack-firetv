#!/usr/bin/env bash
# Verify that personalDNSfilter is answering DNS queries on the Fire TV.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to your Fire TV IP address}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
SDK=$("$PY" -I tools/tv.py getprop ro.build.version.sdk | tr -d '\r')
[[ "$SDK" == "25" ]] || { echo "Expected Fire OS 6 / API 25, got $SDK" >&2; exit 1; }
echo "Filter package:"
"$PY" -I tools/tv.py 'dumpsys package dnsfilter.android' | grep -m1 -E 'versionName=|versionCode=' || true
echo
fail=0
for host in softwareupdates.amazon.com; do
  line=$("$PY" -I tools/tv.py "ping -c1 -W2 $host" 2>&1 | head -1 || true)
  case "$line" in
    *127.0.0.1*|*0.0.0.0*) echo "BLOCKED  $host ($line)" ;;
    *) echo "NOT BLOCKED $host ($line)"; fail=1 ;;
  esac
done
line=$("$PY" -I tools/tv.py 'ping -c1 -W2 www.youtube.com' 2>&1 | head -1 || true)
case "$line" in
  *127.0.0.1*|*0.0.0.0*) echo "FAIL: YouTube DNS was blocked ($line)"; fail=1 ;;
  *) echo "ALLOWED  www.youtube.com ($line)" ;;
esac
if [[ $fail == 1 ]]; then
  echo "Filter verification failed. Open personalDNSfilter on the TV and check VPN/status and its loaded list." >&2
  exit 1
fi
echo "Basic DNS checks passed. This does not guarantee every app or ad stream works."
