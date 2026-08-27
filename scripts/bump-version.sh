#!/usr/bin/env bash
#
# Bump the Android app's version in mobile/version.properties.
#
#   scripts/bump-version.sh                 # versionCode +1
#   scripts/bump-version.sh --name 0.2.0    # versionCode +1, set versionName
#   scripts/bump-version.sh --set-name 0.2.0  # set versionName, keep the code
#   scripts/bump-version.sh --show          # print the current version
#
# versionCode is what Google Play uses to order releases. It must strictly
# increase, can never be reused, and can never be lowered — so this only ever
# counts up, and refuses anything that would move it the other way.
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILE="$REPO/mobile/version.properties"
[ -f "$FILE" ] || { echo "error: $FILE not found" >&2; exit 1; }

NAME=""
SHOW=0
KEEP_CODE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --name) NAME="${2:-}"; [ -n "$NAME" ] || { echo "error: --name needs a value" >&2; exit 2; }; shift 2 ;;
        # For a code that has been built but not yet accepted by Play: the
        # name is still free to change, and spending a versionCode to correct
        # it would leave a gap for no reason.
        --set-name) NAME="${2:-}"; KEEP_CODE=1
            [ -n "$NAME" ] || { echo "error: --set-name needs a value" >&2; exit 2; }; shift 2 ;;
        --show) SHOW=1; shift ;;
        -h|--help) sed -n '3,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
done

get() { sed -n "s/^$1=\(.*\)$/\1/p" "$FILE" | tail -1 | tr -d '[:space:]'; }
CODE="$(get versionCode)"
CURRENT_NAME="$(get versionName)"

if [ "$SHOW" -eq 1 ]; then
    echo "versionCode=$CODE  versionName=$CURRENT_NAME"
    exit 0
fi

[[ "$CODE" =~ ^[0-9]+$ ]] || { echo "error: versionCode '$CODE' is not a number" >&2; exit 1; }
if [ "$KEEP_CODE" -eq 1 ]; then
    NEW_CODE="$CODE"
else
    NEW_CODE=$((CODE + 1))
fi

# Play's hard ceiling. Hitting it is not recoverable within the same package
# name, so it is worth failing loudly a long way out.
if [ "$NEW_CODE" -gt 2100000000 ]; then
    echo "error: versionCode $NEW_CODE exceeds Google Play's maximum of 2100000000" >&2
    exit 1
fi

if [ -n "$NAME" ]; then
    # versionName is what users see. Play does not order by it, but shipping
    # two different builds under one name makes bug reports ambiguous.
    if [ "$NAME" = "$CURRENT_NAME" ]; then
        echo "error: versionName is already $NAME" >&2
        exit 1
    fi
    sed -i "s/^versionName=.*/versionName=$NAME/" "$FILE"
fi
[ "$KEEP_CODE" -eq 0 ] && sed -i "s/^versionCode=.*/versionCode=$NEW_CODE/" "$FILE"

FINAL_NAME="$(get versionName)"
if [ "$KEEP_CODE" -eq 1 ]; then
    echo "  versionCode  $CODE (unchanged)"
else
    echo "  versionCode  $CODE -> $NEW_CODE"
fi
[ -n "$NAME" ] && echo "  versionName  $CURRENT_NAME -> $FINAL_NAME"
echo
echo "  commit this before you build the upload:"
echo "    git add mobile/version.properties && git commit -m \"release $FINAL_NAME ($NEW_CODE)\""
