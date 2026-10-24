#!/usr/bin/env bash
# One-command setup: latest official personalDNSfilter + optional dumbtv-derived privacy packages.
# Usage:
#   TV_HOST=192.168.0.108 bash privacy/setup-firetv-privacy.sh --dry-run
#   TV_HOST=192.168.0.108 bash privacy/setup-firetv-privacy.sh
#   TV_HOST=192.168.0.108 bash privacy/setup-firetv-privacy.sh --dns-only
#   TV_HOST=192.168.0.108 bash privacy/setup-firetv-privacy.sh --packages-only
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?Set TV_HOST to your Fire TV IP, e.g. TV_HOST=192.168.0.108}"
MODE=all
case "${1:-}" in
  "") ;;
  --dry-run) MODE=dry-run ;;
  --dns-only) MODE=dns-only ;;
  --packages-only) MODE=packages-only ;;
  *) echo "Usage: $0 [--dry-run|--dns-only|--packages-only]" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { echo "Usage: $0 [--dry-run|--dns-only|--packages-only]" >&2; exit 2; }

PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "Missing ADB environment. Run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
SDK=$("$PY" -I tools/tv.py getprop ro.build.version.sdk | tr -d '\r')
BUILD=$("$PY" -I tools/tv.py getprop ro.build.display.id | tr -d '\r')
MODEL=$("$PY" -I tools/tv.py getprop ro.product.model | tr -d '\r')
[[ "$SDK" == "25" && "$BUILD" == "NS6711" ]] || {
  echo "Refusing: expected Toshiba Fire OS 6 / API 25 / NS6711; got model=$MODEL SDK=$SDK build=$BUILD" >&2
  exit 1
}
echo "Target TV: $MODEL / $BUILD / API $SDK"

if [[ "$MODE" == dry-run ]]; then
  echo "Dry-run mode only previews package candidates; it does not download or install anything."
  TV_HOST="$TV_HOST" bash debloat/apply-fireos6-extras.sh --dry-run
  exit 0
fi

if [[ "$MODE" == all || "$MODE" == packages-only ]]; then
  echo
  echo "Step 1: review and optionally disable additional Amazon privacy packages."
  TV_HOST="$TV_HOST" bash debloat/apply-fireos6-extras.sh
fi

if [[ "$MODE" == all || "$MODE" == dns-only ]]; then
  echo
  echo "Step 2: install and configure the latest official personalDNSfilter release."
  TV_HOST="$TV_HOST" bash privacy/install-personaldnsfilter.sh
fi

echo
echo "Setup steps finished. Reboot the TV manually if needed, then test Home, Settings, Wi-Fi,"
echo "Appstore, YouTube, Tubi and Pluto TV. Confirm personalDNSfilter shows its filter/VPN as active."
echo "DNS filtering can block Amazon update hosts; disable the filter before intentionally updating Fire OS."
echo "To disable DNS filtering: TV_HOST=$TV_HOST bash privacy/disable-personaldnsfilter.sh"
echo "To restore only extra packages changed by this script: TV_HOST=$TV_HOST bash debloat/restore-fireos6-extras.sh"
