"""AerialPod entry point: QApplication, single-instance guard, wiring.

The window is a front end. Sync, the peer mesh, feed refresh, the queue and
downloads normally live in a daemon that starts with your session; this process
connects to it, reads SQLite directly, and sends commands for anything that
changes. If no daemon can be reached — no session bus, not installed, not
running — it falls back to running those services here, which is what the app
did before the split and remains the macOS story.
"""

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


def _make_backend(args):
    """Pick a backend, preferring the daemon.

    The probe is a real, synchronous D-Bus round trip, which also gets the
    ordering right: activation starts the daemon, and the daemon migrates the
    database before it takes the bus name, so by the time this returns the
    schema is current and this process must not migrate it again.
    """
    from .ipc.inprocess import InProcessBackend

    if args.no_daemon or not sys.platform.startswith("linux"):
        db.init()
        return InProcessBackend(dry_run_sync=args.dry_run_sync)

    from .ipc.dbusclient import DBusBackend, probe_daemon

    info = probe_daemon()
    if info is not None:
        app_version, schema = info
        db.init(migrate=False)
        if schema > len(db.migrations.MIGRATIONS):
            log.error(
                "the background service (v%s) uses database schema %d, newer than "
                "this window understands (%d) — restart AerialPod after updating",
                app_version, schema, len(db.migrations.MIGRATIONS),
            )
            return None
        log.info("connected to the AerialPod service (v%s, schema %d)", app_version, schema)
        return DBusBackend()

    log.info("no background service reachable — running sync in this window")
    db.init()
    return InProcessBackend(dry_run_sync=args.dry_run_sync)


def main() -> int:
    parser = argparse.ArgumentParser(prog=APP_NAME)
    parser.add_argument("--dry-run-sync", action="store_true",
                        help="log gpodder POSTs instead of sending them "
                             "(in-process only; the daemon has its own flag)")
    parser.add_argument("--no-daemon", action="store_true",
                        help="run sync in this process instead of connecting to the service")
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

    backend = _make_backend(args)
    if backend is None:
        return 1

    from .ipc.client import DaemonClient
    from .ui.mainwindow import MainWindow

    client = DaemonClient(backend)
    win = MainWindow(client)

    QLocalServer.removeServer(_SOCKET_NAME)
    server = QLocalServer()
    server.listen(_SOCKET_NAME)
    server.newConnection.connect(lambda: (win.show(), win.raise_(), win.activateWindow()))

    win.show()
    client.start()
    rc = app.exec()

    if getattr(backend, "threads_running", False):
        # A sync is blocked on the network. Destroying a running QThread
        # aborts the process with a core dump — exit cleanly instead. All
        # state was already persisted in closeEvent.
        logging.shutdown()
        os._exit(rc)
    return rc


if __name__ == "__main__":
    sys.exit(main())
