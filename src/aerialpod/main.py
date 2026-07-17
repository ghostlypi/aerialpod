"""AerialPod entry point: QApplication, single-instance guard, wiring."""

from __future__ import annotations

import argparse
import ctypes
import logging
import os
import signal
import sys

from PySide6.QtGui import QGuiApplication
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtWidgets import QApplication

from . import db
from .config import APP_NAME

log = logging.getLogger(__name__)

_SOCKET_NAME = f"{APP_NAME}-single-instance"


def _preload_openssl() -> None:
    """Load libssl matching the already-loaded libcrypto before Qt FFmpeg
    resolves TLS symbols. Under a conda/miniforge Python, the interpreter has
    already loaded conda's libcrypto.so.3; when Qt later dlopens the *system*
    libssl.so.3 its symbol versions (e.g. OPENSSL_3.0.1) may not resolve
    against the older loaded libcrypto and https streaming breaks entirely.
    Loading "libssl.so.3" from Python instead follows the interpreter's own
    search path, pulling in the copy that matches its libcrypto.
    """
    try:
        ctypes.CDLL("libssl.so.3", mode=ctypes.RTLD_GLOBAL)
    except OSError:  # no OpenSSL 3 at all — let Qt try its own resolution
        pass


def _already_running() -> bool:
    probe = QLocalSocket()
    probe.connectToServer(_SOCKET_NAME)
    if probe.waitForConnected(200):
        probe.write(b"raise\n")
        probe.waitForBytesWritten(200)
        probe.disconnectFromServer()
        return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(prog=APP_NAME)
    parser.add_argument("--dry-run-sync", action="store_true",
                        help="log gpodder POSTs instead of sending them")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    _preload_openssl()

    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setOrganizationName(APP_NAME)
    QGuiApplication.setDesktopFileName(APP_NAME)  # Wayland app_id ↔ .desktop file

    if _already_running():
        log.info("another instance is running; asked it to raise itself")
        return 0

    # ctrl-c works in a terminal
    signal.signal(signal.SIGINT, signal.SIG_DFL)

    db.init()

    from .ui.mainwindow import MainWindow

    win = MainWindow(dry_run_sync=args.dry_run_sync)

    QLocalServer.removeServer(_SOCKET_NAME)
    server = QLocalServer()
    server.listen(_SOCKET_NAME)
    server.newConnection.connect(lambda: (win.show(), win.raise_(), win.activateWindow()))

    win.show()
    rc = app.exec()
    if win.sync_thread.isRunning():
        # A sync is blocked on the network. Destroying a running QThread
        # aborts the process with a core dump — exit cleanly instead. All
        # state was already persisted in closeEvent.
        logging.shutdown()
        os._exit(rc)
    return rc


if __name__ == "__main__":
    sys.exit(main())
