#!/usr/bin/env bash
#
# Check a release build against the things Google Play rejects for.
#
#   scripts/build-android.sh --bundle --release && scripts/preflight-play.sh
#
# Reads the APK for manifest facts (versionCode, target/min SDK, permissions,
# debuggable) because those come from the same build as the bundle, and reading
# an .aab's protobuf manifest needs bundletool, which is one more thing to have
# installed. The .aab itself is checked for existence and signature.
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/build/android"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
AAPT="$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"

fail=0
ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad()  { printf "  \033[31m✗\033[0m %s\n" "$1"; fail=1; }
warn() { printf "  \033[33m!\033[0m %s\n" "$1"; }

AAB="$OUT/aerialpod-release.aab"
APK="$OUT/aerialpod-release.apk"
[ -e "$AAB" ] || { echo "no bundle at $AAB — run: scripts/build-android.sh --bundle" >&2; exit 1; }
[ -e "$APK" ] || { echo "no release APK at $APK — run: scripts/build-android.sh --release" >&2; exit 1; }
[ -n "$AAPT" ] || { echo "aapt2 not found under $SDK/build-tools" >&2; exit 1; }

echo "Play preflight"
echo

badging="$($AAPT dump badging "$APK" 2>/dev/null)"
get() { sed -n "s/.*$1='\([^']*\)'.*/\1/p" <<< "$badging" | head -1; }

# Anchored, because aapt also prints compileSdkVersionCodename and
# platformBuildVersionName — an unanchored greedy match picks those up and
# reports the SDK codename as the package name.
pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging" | head -1)"
code="$(get versionCode)"; name="$(get versionName)"
target="$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" <<< "$badging" | head -1)"
minsdk="$(sed -n "s/^minSdkVersion:'\([^']*\)'.*/\1/p" <<< "$badging" | head -1)"

echo "  package $pkg   version $name (code $code)   min $minsdk / target $target"
echo

# Play's target-API rule tracks the latest release; 35 is the floor that has
# been enforced longest. Anything below is a certain rejection.
[ -n "$target" ] && [ "$target" -ge 35 ] \
    && ok "targetSdk $target meets Play's minimum" \
    || bad "targetSdk $target is below Play's requirement — check the current floor"

[[ "$code" =~ ^[0-9]+$ ]] && [ "$code" -ge 1 ] \
    && ok "versionCode $code is a positive integer" \
    || bad "versionCode '$code' is not usable"

# A debuggable release is an automatic rejection and a real security problem.
if grep -q "application-debuggable" <<< "$badging"; then
    bad "the release build is DEBUGGABLE — do not upload it"
else
    ok "not debuggable"
fi

if command -v jarsigner >/dev/null 2>&1 && jarsigner -verify "$AAB" >/dev/null 2>&1; then
    who="$(keytool -printcert -jarfile "$AAB" 2>/dev/null \
           | sed -n 's/^Owner: .*CN=\([^,]*\).*/\1/p' | head -1)"
    ok "bundle is signed (${who:-unknown key})"
else
    bad "bundle is not signed"
fi

size="$(stat -c%s "$(readlink -f "$AAB")")"
if [ "$size" -lt 209715200 ]; then
    ok "bundle is $(numfmt --to=iec "$size"), under the 200 MB limit"
else
    bad "bundle is $(numfmt --to=iec "$size"), over Play's 200 MB limit"
fi

echo
echo "  Permissions — each of these is visible to users, and some need a"
echo "  written justification in the Console:"
sed -n "s/uses-permission: name='\([^']*\)'.*/    \1/p" <<< "$badging"

echo
grep -q "ACCESS_LOCAL_NETWORK" <<< "$badging" && warn \
  "ACCESS_LOCAL_NETWORK is shown to users as finding 'nearby devices'.
    Be ready to explain it is only used to reach the user's own desktop."
grep -q "FOREGROUND_SERVICE_MEDIA_PLAYBACK" <<< "$badging" && warn \
  "Declare the foreground service type as media playback, and be ready to
    show playback continuing with the app in the background."

echo
echo "  Not checkable from the build — confirm by hand:"
echo "    · privacy policy URL is live and reachable"
echo "    · Data safety form matches what the app actually sends"
echo "    · store listing: 512×512 icon, 1024×500 feature graphic, screenshots"
echo "    · content rating questionnaire completed"
echo
[ "$fail" -eq 0 ] && echo "  ready to upload" || { echo "  FIX THE ✗ ITEMS FIRST"; exit 1; }
