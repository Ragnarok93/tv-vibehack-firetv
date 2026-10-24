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
RELEASE_API=https://api.github.com/repos/IngoZenz/personaldnsfilter/releases/latest
CONF_URL=https://raw.githubusercontent.com/IngoZenz/personaldnsfilter/master/app/src/main/assets/dnsfilter.conf
LIST_URL=https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.amazon-onlydomains.txt

# Resolve the latest APK from the official upstream GitHub release; never hardcode a version.
curl -fsSL --retry 3 --connect-timeout 20 \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "$RELEASE_API" -o "$WORK/release.json"
python3 - "$WORK/release.json" "$WORK/apk-url.txt" "$WORK/apk-digest.txt" <<'PY'
import json, sys
release_path, url_path, digest_path = sys.argv[1:]
with open(release_path, encoding="utf-8") as f:
    release = json.load(f)
assets = [a for a in release.get("assets", [])
          if a.get("name", "").lower().endswith(".apk")]
if not assets:
    raise SystemExit("Latest official GitHub release contains no APK asset; refusing to install.")
# Prefer a plain APK, not an Android App Bundle or other archive.
assets.sort(key=lambda a: (("bundle" in a.get("name", "").lower()), a.get("name", "").lower()))
asset = assets[0]
url = asset.get("browser_download_url", "")
if not url.startswith("https://github.com/IngoZenz/personaldnsfilter/releases/download/"):
    raise SystemExit("APK asset URL is not from the official upstream release host.")
with open(url_path, "w", encoding="utf-8") as f:
    f.write(url)
with open(digest_path, "w", encoding="utf-8") as f:
    f.write(asset.get("digest") or "")
print("Latest upstream release:", release.get("tag_name", "(untagged)"))
print("APK asset:", asset.get("name"))
if not asset.get("digest"):
    print("Note: GitHub did not publish an asset digest; HTTPS and official release URL will be used.")
PY
APK_URL=$(cat "$WORK/apk-url.txt")
APK_DIGEST=$(cat "$WORK/apk-digest.txt")
curl -fsSL --retry 3 --connect-timeout 20 "$APK_URL" -o "$WORK/dnsfilter.apk"
if [[ "$APK_DIGEST" == sha256:* ]]; then
  EXPECTED="${APK_DIGEST#sha256:}"
  if command -v sha256sum >/dev/null; then GOT=$(sha256sum "$WORK/dnsfilter.apk" | awk '{print $1}')
  else GOT=$(shasum -a 256 "$WORK/dnsfilter.apk" | awk '{print $1}'); fi
  [[ "$GOT" == "$EXPECTED" ]] || { echo "APK checksum mismatch; refusing to install." >&2; exit 1; }
elif [[ -n "$APK_DIGEST" ]]; then
  echo "Unsupported upstream asset digest format '$APK_DIGEST'; refusing to install." >&2
  exit 1
fi
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
# Leave the filter activity visible so any VPN consent prompt can be accepted with the remote.
echo
echo "Configured $(( $(grep -c . "$WORK/FILTERHOSTS.TXT") )) Amazon-related domain rules."
echo "Next: run privacy/verify-personaldnsfilter.sh after the VPN is active."
echo "If Android displays a VPN confirmation, approve it with the TV remote."
echo "Fire OS 6 may forget always_on_vpn_app after reboot; the app's boot start is relied on."
echo "This filter blocks update hosts as well as telemetry. Disable it before deliberately updating Fire OS."
