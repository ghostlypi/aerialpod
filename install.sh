#!/usr/bin/env bash
# AerialPod user install: app into an isolated venv (or pipx), plus GNOME
# integration (.desktop + icon) under ~/.local. No root needed.
#
#   ./install.sh            install or update
#   ./install.sh uninstall  remove everything it installed
#
# Works on any Fedora/GNOME machine with python3 >= 3.11. On macOS it
# installs the venv + launcher only (no .desktop/icon; MPRIS is Linux-only).
set -euo pipefail
cd "$(dirname "$0")"

APP=aerialpod
BIN_DIR="$HOME/.local/bin"
APPS_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"
VENV_DIR="$HOME/.local/opt/$APP"

uninstall() {
    echo "Removing AerialPod…"
    pipx uninstall "$APP" 2>/dev/null || true
    rm -rf "$VENV_DIR"
    rm -f "$BIN_DIR/$APP" "$APPS_DIR/$APP.desktop" "$ICON_DIR/$APP.svg" \
          "$HOME/.local/share/icons/hicolor/256x256/apps/$APP.png"
    command -v update-desktop-database >/dev/null && update-desktop-database "$APPS_DIR" || true
    echo "Done. (Data in ~/.local/share/$APP and the keyring entry were kept;"
    echo " remove them manually if you want a clean slate.)"
    exit 0
}
[[ "${1:-}" == "uninstall" ]] && uninstall

# --- app install: pipx if available, else a dedicated venv ---------------
if command -v pipx >/dev/null 2>&1; then
    echo "Installing with pipx…"
    pipx install --force .
else
    echo "pipx not found — installing into $VENV_DIR"
    # Use the system python explicitly: conda/miniforge pythons work too, but
    # the system one is guaranteed present on every Fedora machine.
    PY=/usr/bin/python3
    [[ -x $PY ]] || PY=python3
    "$PY" -m venv --clear "$VENV_DIR"
    "$VENV_DIR/bin/pip" install --quiet --upgrade pip
    "$VENV_DIR/bin/pip" install --quiet .
    mkdir -p "$BIN_DIR"
    ln -sf "$VENV_DIR/bin/$APP" "$BIN_DIR/$APP"
fi

# --- GNOME integration (Linux only) ---------------------------------------
if [[ "$(uname)" == "Linux" ]]; then
    PNG_ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
    mkdir -p "$APPS_DIR" "$ICON_DIR" "$PNG_ICON_DIR"

    # Icon: a PNG at data/icons/aerialpod.png (256×256 or 512×512, square)
    # takes priority over the bundled SVG — drop your own there and re-run.
    if [[ -f data/icons/$APP.png ]]; then
        cp data/icons/$APP.png "$PNG_ICON_DIR/$APP.png"
        rm -f "$ICON_DIR/$APP.svg"   # PNG wins; don't leave a competing SVG
        echo "Using custom PNG icon."
    else
        cp data/icons/$APP.svg "$ICON_DIR/$APP.svg"
        rm -f "$PNG_ICON_DIR/$APP.png"
    fi

    # Desktop entry — absolute Exec path: GNOME's launcher environment may
    # not have ~/.local/bin on PATH
    sed "s|^Exec=.*|Exec=$BIN_DIR/$APP|" data/$APP.desktop > "$APPS_DIR/$APP.desktop"
    command -v update-desktop-database >/dev/null && update-desktop-database "$APPS_DIR" || true
    command -v gtk-update-icon-cache >/dev/null && \
        gtk-update-icon-cache -q -t "$HOME/.local/share/icons/hicolor" || true
fi

echo
echo "Installed. Launch 'AerialPod' from the GNOME overview, or run: $BIN_DIR/$APP"
