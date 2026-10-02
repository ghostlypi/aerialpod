#!/usr/bin/env bash
# Build the AerialPod flatpak.
#
#   packaging/flatpak/build.sh                     build + install for this user
#   packaging/flatpak/build.sh --bundle            ...and write aerialpod-<arch>.flatpak
#   packaging/flatpak/build.sh --arch aarch64 --bundle
#                                                  cross-build for ARM (Steam Frame)
#
# Cross-building needs qemu-user-static registered with binfmt
# (Fedora: sudo dnf install qemu-user-static). It is slow but works, since
# nothing here compiles — every native wheel is prebuilt per architecture.
#
# A .flatpak bundle installs on another machine with
#   flatpak install --user aerialpod-aarch64.flatpak
# and pulls the KDE runtime and PySide BaseApp from Flathub on its own.
set -euo pipefail
cd "$(dirname "$0")"

APP_ID=io.github.ghostlypi.AerialPod
ARCH=$(flatpak --default-arch)
BUNDLE=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --arch) ARCH=$2; shift 2 ;;
        --bundle) BUNDLE=1; shift ;;
        *) echo "usage: $0 [--arch x86_64|aarch64] [--bundle]" >&2; exit 2 ;;
    esac
done

if command -v flatpak-builder >/dev/null; then
    BUILDER=(flatpak-builder)
else
    flatpak info --user org.flatpak.Builder >/dev/null 2>&1 ||
        flatpak install --user -y flathub org.flatpak.Builder
    BUILDER=(flatpak run org.flatpak.Builder)
fi

flatpak remote-add --user --if-not-exists flathub \
    https://dl.flathub.org/repo/flathub.flatpakrepo

ARGS=(--user --arch="$ARCH" --force-clean --install-deps-from=flathub
      --state-dir=../../.flatpak-builder --repo=repo)
# Installing only makes sense for the architecture this machine runs.
[[ $ARCH == "$(flatpak --default-arch)" ]] && ARGS+=(--install)

"${BUILDER[@]}" "${ARGS[@]}" "build-dir-$ARCH" "$APP_ID.yml"

if (( BUNDLE )); then
    flatpak build-bundle --arch="$ARCH" \
        --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
        repo "../../aerialpod-$ARCH.flatpak" "$APP_ID"
    echo "Wrote aerialpod-$ARCH.flatpak"
fi
