#!/usr/bin/env bash
#
# Build the AerialPod Android app into <repo>/build/android/.
#
# Release by default, because the reason to run this is to put the app on a
# real phone. Debug builds install alongside release ones (different
# applicationId), so having both on a device at once is deliberate.
#
#   scripts/build-android.sh              # signed release APK
#   scripts/build-android.sh --debug      # debug APK
#   scripts/build-android.sh --both
#   scripts/build-android.sh --bundle     # signed .aab for the Play Console
#   scripts/build-android.sh --install    # also adb install to one device
#   scripts/build-android.sh --clean
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOBILE="$REPO/mobile"
OUT="$REPO/build/android"

VARIANTS=()
INSTALL=0
CLEAN=0
BUNDLE=0
for arg in "$@"; do
    case "$arg" in
        --debug)   VARIANTS+=("debug") ;;
        --release) VARIANTS+=("release") ;;
        --both)    VARIANTS+=("debug" "release") ;;
        --bundle)  BUNDLE=1 ;;
        --install) INSTALL=1 ;;
        --clean)   CLEAN=1 ;;
        -h|--help) sed -n '3,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
    esac
done
# --bundle alone means "the thing I upload to Play", not "that plus an APK".
# An explicit --debug/--release/--both still adds the APKs on top.
if [ ${#VARIANTS[@]} -eq 0 ] && [ "$BUNDLE" -eq 0 ]; then
    VARIANTS=("release")
fi

die() { echo "error: $*" >&2; exit 1; }

[ -x "$MOBILE/gradlew" ] || die "no gradle wrapper at $MOBILE/gradlew"

# A release APK that is not signed cannot be installed, and Gradle will build
# one anyway rather than fail — so the absence of the keystore is caught here,
# while there is still something useful to say about it.
needs_keystore=0
[ "$BUNDLE" -eq 1 ] && needs_keystore=1
for v in "${VARIANTS[@]}"; do
    [ "$v" = "release" ] && needs_keystore=1
done
if [ "$needs_keystore" -eq 1 ] && [ ! -f "$MOBILE/release.keystore" ]; then
    die "release.keystore is missing from $MOBILE.
  It is deliberately not in git. Without the original, a rebuilt keystore
  produces an app Android refuses to install over the existing one — the
  only way back is to uninstall first, which erases the app's library."
fi

mkdir -p "$OUT"
cd "$MOBILE"

if [ "$CLEAN" -eq 1 ]; then
    echo "==> clean"
    ./gradlew clean -q
fi

# From version.properties, which is what Gradle itself reads — so the filename
# cannot disagree with what is inside the artefact.
VERSION="$(sed -n 's/^versionName=//p' version.properties | tr -d '[:space:]')"
CODE="$(sed -n 's/^versionCode=//p' version.properties | tr -d '[:space:]')"
[ -n "$VERSION" ] || VERSION="unknown"
[ -n "$CODE" ] || CODE="0"
echo "==> version $VERSION (code $CODE)"
STAMP="$(date +%Y%m%d-%H%M)"

built=()

if [ "$BUNDLE" -eq 1 ]; then
    echo "==> bundleRelease"
    ./gradlew ":androidApp:bundleRelease" -q
    src="androidApp/build/outputs/bundle/release/androidApp-release.aab"
    [ -f "$src" ] || die "expected a bundle at $src and found none"
    dest="$OUT/aerialpod-${VERSION}-${CODE}-release.aab"
    cp "$src" "$dest"
    ln -sfn "$(basename "$dest")" "$OUT/aerialpod-release.aab"
    built+=("$dest")
fi
for variant in "${VARIANTS[@]}"; do
    Variant="$(tr '[:lower:]' '[:upper:]' <<< "${variant:0:1}")${variant:1}"
    echo "==> assemble$Variant"
    ./gradlew ":androidApp:assemble$Variant" -q

    src="androidApp/build/outputs/apk/$variant/androidApp-$variant.apk"
    [ -f "$src" ] || die "expected an APK at $src and found none"

    dest="$OUT/aerialpod-${VERSION}-${variant}-${STAMP}.apk"
    cp "$src" "$dest"
    # A stable name as well, so a phone-side bookmark or a scp command does not
    # have to be retyped after every build.
    ln -sfn "$(basename "$dest")" "$OUT/aerialpod-$variant.apk"
    built+=("$dest")
done

# apksigner lives in build-tools, which is not on PATH by default. Highest
# version wins; any of them can verify.
APKSIGNER="$(command -v apksigner 2>/dev/null || true)"
if [ -z "$APKSIGNER" ]; then
    APKSIGNER="$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2>/dev/null \
        | sort -V | tail -1 || true)"
fi

echo
for apk in "${built[@]}"; do
    size="$(du -h "$apk" | cut -f1)"
    printf "  %-56s %6s\n" "${apk#$REPO/}" "$size"
    if [[ "$apk" == *.aab ]]; then
        # A bundle is a signed JAR, not an APK — apksigner cannot read it, and
        # reporting "unsigned" for a correctly signed upload is worse than
        # saying nothing.
        if command -v jarsigner >/dev/null 2>&1 && jarsigner -verify "$apk" >/dev/null 2>&1; then
            who="$(keytool -printcert -jarfile "$apk" 2>/dev/null \
                   | sed -n 's/^Owner: .*CN=\([^,]*\).*/\1/p' | head -1)"
            printf "      signed by %s  (upload key; Play re-signs for devices)\n" "${who:-?}"
        else
            printf "      NOT SIGNED — the Play Console will reject this\n"
        fi
    elif [ -n "$APKSIGNER" ] && certs="$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null)"; then
        # The signer, not merely "signed": an app signed with a different key
        # than the installed one cannot be updated over it, and the only way
        # out is an uninstall that erases the library. Printing the fingerprint
        # every time is what makes that visible before it reaches a phone.
        who="$(sed -n 's/^.*certificate DN: .*CN=\([^,]*\).*/\1/p' <<< "$certs" | head -1)"
        sha="$(sed -n 's/^.*certificate SHA-256 digest: \(.*\)$/\1/p' <<< "$certs" | head -1)"
        printf "      signed by %s  sha256:%s\n" "${who:-?}" "${sha:0:16}…"
    elif [ -n "$APKSIGNER" ]; then
        printf "      NOT SIGNED — this will not install\n"
    else
        printf "      (apksigner not found; signature unverified)\n"
    fi
done
# A debug APK is ~77 MB, so a few days of builds is gigabytes. Keep a short
# history — enough to go back to "the one from this morning that worked" —
# and drop the rest. The symlinks always point at the newest.
KEEP=5
for variant in "${VARIANTS[@]}"; do
    mapfile -t old_apks < <(ls -1t "$OUT"/aerialpod-*-"$variant"-*.apk 2>/dev/null | tail -n +$((KEEP + 1)))
    if [ ${#old_apks[@]} -gt 0 ]; then
        printf "  pruned %d old %s build(s)\n" "${#old_apks[@]}" "$variant"
        rm -f "${old_apks[@]}"
    fi
done

echo
[ "$BUNDLE" -eq 1 ] && echo "  upload: build/android/aerialpod-release.aab (symlink)"
[ ${#VARIANTS[@]} -gt 0 ] && echo "  latest: build/android/aerialpod-<variant>.apk (symlink)"

if [ "$INSTALL" -eq 1 ]; then
    command -v adb >/dev/null 2>&1 || die "--install needs adb on PATH"
    count="$(adb devices | tail -n +2 | grep -cw device || true)"
    [ "$count" -eq 1 ] || die "--install needs exactly one attached device (found $count).
  Install by hand with: adb -s <serial> install -r <apk>"
    for apk in "${built[@]}"; do
        echo "==> installing $(basename "$apk")"
        adb install -r -d "$apk"
    done
fi
