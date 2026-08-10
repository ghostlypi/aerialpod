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
DAEMON="$APP-daemon"
BIN_DIR="$HOME/.local/bin"
APPS_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"
VENV_DIR="$HOME/.local/opt/$APP"
UNIT_DIR="$HOME/.config/systemd/user"
DBUS_DIR="$HOME/.local/share/dbus-1/services"

uninstall() {
    echo "Removing AerialPod…"
    if [[ "$(uname)" == "Linux" ]] && command -v systemctl >/dev/null; then
        systemctl --user disable --now "$DAEMON.service" 2>/dev/null || true
    fi
    rm -f "$UNIT_DIR/$DAEMON.service" "$DBUS_DIR/org.aerialpod.Daemon.service"
    command -v systemctl >/dev/null && systemctl --user daemon-reload 2>/dev/null || true
    pipx uninstall "$APP" 2>/dev/null || true
    rm -rf "$VENV_DIR"
    rm -f "$BIN_DIR/$APP" "$BIN_DIR/$DAEMON" "$APPS_DIR/$APP.desktop" \
          "$ICON_DIR/$APP.svg" \
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
    ln -sf "$VENV_DIR/bin/$DAEMON" "$BIN_DIR/$DAEMON"
fi

# --- GNOME integration (Linux only) ---------------------------------------
if [[ "$(uname)" == "Linux" ]]; then
    PNG_ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
    mkdir -p "$APPS_DIR" "$ICON_DIR" "$PNG_ICON_DIR"

    # Icon: a PNG at data/icons/aerialpod.png (256×256 or 512×512, square)
    # takes priority over the bundled SVG — drop your own there and re-run.
    # GNOME's icon loader stretches non-square PNGs to fit the square slot
    # (a squished/oval logo shipped this way once — see git history), so
    # verify squareness from the PNG's own IHDR chunk before installing it.
    if [[ -f data/icons/$APP.png ]]; then
        read -r png_w < <(od -An -tu4 --endian=big -j 16 -N 4 "data/icons/$APP.png")
        read -r png_h < <(od -An -tu4 --endian=big -j 20 -N 4 "data/icons/$APP.png")
        if [[ "${png_w// /}" != "${png_h// /}" ]]; then
            echo "error: data/icons/$APP.png is ${png_w// /}x${png_h// /}, not square." >&2
            echo "       A non-square icon gets stretched into a squished oval by GNOME." >&2
            echo "       Crop it to a square (matching its actual artwork bounds) and re-run." >&2
            exit 1
        fi
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

    # --- background service --------------------------------------------
    # Sync, the peer mesh and feed refresh live in a daemon so they keep
    # working with the window closed. The D-Bus service file also makes it
    # start on demand, so the window never has to wait for you to start it.
    mkdir -p "$UNIT_DIR" "$DBUS_DIR"
    sed "s|__BIN__|$BIN_DIR|g" data/systemd/$DAEMON.service > "$UNIT_DIR/$DAEMON.service"
    sed "s|__BIN__|$BIN_DIR|g" data/dbus-1/org.aerialpod.Daemon.service \
        > "$DBUS_DIR/org.aerialpod.Daemon.service"

    if command -v systemctl >/dev/null; then
        systemctl --user daemon-reload 2>/dev/null || true
        # Restart rather than start: an update should replace the running one.
        if systemctl --user enable "$DAEMON.service" 2>/dev/null; then
            systemctl --user restart "$DAEMON.service" 2>/dev/null || true
            echo "Background sync service enabled (starts with your session)."
        else
            echo "note: could not enable the user service — AerialPod will run" >&2
            echo "      sync inside the window instead, which still works." >&2
        fi
    fi
fi

echo
echo "Installed. Launch 'AerialPod' from the GNOME overview, or run: $BIN_DIR/$APP"
if [[ "$(uname)" == "Linux" ]]; then
    echo "Background service: systemctl --user status $DAEMON"
fi
