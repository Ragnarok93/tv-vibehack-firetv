#!/usr/bin/env bash
# Install and configure personalDNSfilter with an Amazon-only blocklist.
# No root; uses the repository's Python ADB client. Review the warning before running.
# Usage: TV_HOST=192.168.0.108 privacy/install-personaldnsfilter.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to your Fire TV IP address}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v sha256sum >/dev/null || command -v shasum >/dev/null || { echo "sha256sum or shasum is required" >&2; exit 1; }

SDK=$("$PY" -I tools/tv.py getprop ro.build.version.sdk | tr -d '\r')
BUILD=$("$PY" -I tools/tv.py getprop ro.build.display.id | tr -d '\r')
MODEL=$("$PY" -I tools/tv.py getprop ro.product.model | tr -d '\r')
[[ "$SDK" == "25" && "$BUILD" == "NS6711" ]] || {
  echo "Refusing: this setup targets Fire OS 6 / API 25 build NS6711; got model=$MODEL SDK=$SDK build=$BUILD" >&2
  exit 1
}
echo "Target: $MODEL / $BUILD / API $SDK"
cat <<'WARN'
WARNING:
- This installs a separate third-party VPN-based DNS filter; it is not embedded in the launcher APK.
- The Amazon-only list can block Fire TV update and telemetry hosts. Keep the Appstore and verify
  that your apps still work. DNS filtering cannot reliably remove YouTube ads.
- The filter is configured as always-on where Fire OS permits. Use disable-personaldnsfilter.sh to
  turn it off. The setup does not reboot the TV automatically.
WARN
read -r -p "Install personalDNSfilter and enable the Amazon-only blocklist? [y/N] " reply
case "$reply" in y|Y|yes|YES) ;; *) echo "Cancelled."; exit 0 ;; esac

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
PKG=dnsfilter.android
VERSION=1506103
APK_URL="https://f-droid.org/repo/dnsfilter.android_${VERSION}.apk"
APK_SHA256=3ddd0db322d1ca5fe950ea29dc1788af6e6581bb5f34694085fb4aa2e77b68ad
CONF_URL=https://raw.githubusercontent.com/IngoZenz/personaldnsfilter/master/app/src/main/assets/dnsfilter.conf
LIST_URL=https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.amazon-onlydomains.txt
curl -fsSL --retry 3 --connect-timeout 20 "$APK_URL" -o "$WORK/dnsfilter.apk"
if command -v sha256sum >/dev/null; then GOT=$(sha256sum "$WORK/dnsfilter.apk" | awk '{print $1}')
else GOT=$(shasum -a 256 "$WORK/dnsfilter.apk" | awk '{print $1}'); fi
[[ "$GOT" == "$APK_SHA256" ]] || { echo "APK checksum mismatch; refusing to install." >&2; exit 1; }
curl -fsSL --retry 3 --connect-timeout 20 "$CONF_URL" -o "$WORK/dnsfilter.conf"
curl -fsSL --retry 3 --connect-timeout 20 "$LIST_URL" -o "$WORK/amazon-source.txt"
# HaGeZi's native-domain list is the primary source. Add any reviewed local entries.
{
  grep -v '^#' "$WORK/amazon-source.txt" | tr -d '\r' | sed '/^[[:space:]]*$/d'
  grep -vE '^[[:space:]]*(#|$)' privacy/amazon-extra-hosts.txt | tr -d '\r'
} | sort -u > "$WORK/FILTERHOSTS.TXT"
[[ -s "$WORK/FILTERHOSTS.TXT" ]] || { echo "Blocklist is empty; refusing to continue." >&2; exit 1; }
GW=$("$PY" -I tools/tv.py 'ip route' | awk '/^default / {print $3; exit}' | tr -d '\r')
[[ -n "$GW" ]] || { echo "Could not determine the TV's default gateway." >&2; exit 1; }

# Patch only known settings; fail closed if the upstream template changes.
python3 - "$WORK/dnsfilter.conf" "$GW" <<'PY'
import sys
path, gateway = sys.argv[1:]
lines = open(path, encoding="utf-8").read().splitlines()
wanted = {
    "previousAutoUpdateURL": "",
    "detectDNS": "false",
    "fallbackDNS": r"\#router; [" + gateway + "]::53::UDP",
    "routeIPs": gateway,
    "routeUnderlyingDNS": "true",
    "showInitialInfoPopUp": "false",
    "reloadIntervalDays": "3650",
    "filterHostsFile": "FILTERHOSTS.TXT",
    "androidAppWhiteList": "<none>",
}
seen = set()
out = []
for line in lines:
    if line.startswith("filterAutoUpdateURL = "):
        out.append("#" + line)
        continue
    key = line.split(" = ", 1)[0] if " = " in line else ""
    if key in wanted:
        out.append(key + " = " + wanted[key])
        seen.add(key)
    else:
        out.append(line)
missing = set(wanted) - seen
if missing:
    raise SystemExit("Upstream config template changed; missing keys: " + ", ".join(sorted(missing)))
open(path, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
PY

echo "Installing personalDNSfilter APK (SHA-256 verified)…"
"$PY" -I tools/tv.py --push "$WORK/dnsfilter.apk" /data/local/tmp/dnsfilter.apk
INSTALL=$("$PY" -I tools/tv.py 'pm install -r /data/local/tmp/dnsfilter.apk' | tr -d '\r')
echo "$INSTALL"
printf '%s\n' "$INSTALL" | grep -q 'Success' || { echo "APK installation failed." >&2; exit 1; }
"$PY" -I tools/tv.py 'pm enable --user 0 dnsfilter.android' >/dev/null
"$PY" -I tools/tv.py 'appops set dnsfilter.android ACTIVATE_VPN allow' >/dev/null
"$PY" -I tools/tv.py 'dumpsys deviceidle whitelist +dnsfilter.android' >/dev/null
# Launch once so the app creates its private external-storage directory.
"$PY" -I tools/tv.py 'am start -n dnsfilter.android/.DNSProxyActivity' >/dev/null
sleep 5
DIR=/sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter
if ! "$PY" -I tools/tv.py "test -d $DIR" >/dev/null 2>&1; then
  echo "personalDNSfilter did not create $DIR. Open it on the TV once, then rerun this script." >&2
  exit 1
fi
"$PY" -I tools/tv.py 'am force-stop dnsfilter.android' >/dev/null
"$PY" -I tools/tv.py --push "$WORK/dnsfilter.conf" "$DIR/dnsfilter.conf"
"$PY" -I tools/tv.py 'rm -rf /sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter/FILTERHOSTS.TXT.idx; touch /sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter/IDX_OUTDATED'
"$PY" -I tools/tv.py --push "$WORK/FILTERHOSTS.TXT" "$DIR/FILTERHOSTS.TXT"
printf '%s\n%s000\n' "$(grep -c . "$WORK/FILTERHOSTS.TXT")" "$(date +%s)" > "$WORK/DLD_CNT"
"$PY" -I tools/tv.py --push "$WORK/DLD_CNT" "$DIR/FILTERHOSTS.TXT.DLD_CNT"
"$PY" -I tools/tv.py 'settings put secure always_on_vpn_app dnsfilter.android' >/dev/null
# Relaunch after the config/list have been replaced; keep it running in the background.
"$PY" -I tools/tv.py 'am start -n dnsfilter.android/.DNSProxyActivity' >/dev/null
sleep 3
"$PY" -I tools/tv.py 'input keyevent KEYCODE_HOME' >/dev/null || true
echo
echo "Configured $(( $(grep -c . "$WORK/FILTERHOSTS.TXT") )) Amazon-related domain rules."
echo "Next: run privacy/verify-personaldnsfilter.sh after the VPN is active."
echo "If Android displays a VPN confirmation, approve it with the TV remote."
echo "Fire OS 6 may forget always_on_vpn_app after reboot; the app's boot start is relied on."
echo "This filter blocks update hosts as well as telemetry. Disable it before deliberately updating Fire OS."
