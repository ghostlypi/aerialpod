"""aerialpod-daemon: the part that keeps working when the window is closed.

Owns the database and every write to it — gpodder.net sync, the LAN peer mesh,
feed refresh, the queue, downloads. Starts with your session (see the systemd
unit installed by install.sh) or on demand via D-Bus activation the first time
the window asks for it.

Deliberately headless but still a Qt application: the services are QObjects on
timers and worker threads, so they need an event loop, not a window.
"""

from __future__ import annotations

import argparse
import logging
import os
import signal
import sys

from PySide6.QtCore import QCoreApplication, QTimer

from . import db
from .config import APP_NAME

log = logging.getLogger(__name__)

VERSION = "0.1.0"


def main() -> int:
    parser = argparse.ArgumentParser(prog=f"{APP_NAME}-daemon")
    parser.add_argument("--dry-run-sync", action="store_true",
                        help="log gpodder POSTs instead of sending them")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    app = QCoreApplication(sys.argv)
    app.setApplicationName(f"{APP_NAME}-daemon")
    signal.signal(signal.SIGINT, signal.SIG_DFL)
    signal.signal(signal.SIGTERM, signal.SIG_DFL)

    # This process owns migrations. It takes the bus name only after they have
    # run, so a window that reached us through activation can trust the schema.
    db.init()
    schema = db.schema_version()

    from .ipc.dbusservice import DaemonBus
    from .ipc.hub import ServiceHub

    hub = ServiceHub(dry_run_sync=args.dry_run_sync)
    bus = DaemonBus(hub, VERSION, schema)
    if not bus.wait_until_ready():
        log.error("could not take the bus name — is another daemon running?")
        hub.shutdown()
        return 1

    def stop() -> None:
        log.info("shutting down")
        hub.shutdown()
        bus.shutdown()
        app.quit()

    app.aboutToQuit.connect(hub.shutdown)
    QTimer.singleShot(0, hub.start)

    log.info("aerialpod-daemon %s ready (schema %d)", VERSION, schema)
    rc = app.exec()
    stop()
    if hub.threads_running():
        # A sync is blocked on the network; destroying a running QThread aborts
        # the process. Everything is already persisted.
        logging.shutdown()
        os._exit(rc)
    return rc


if __name__ == "__main__":
    sys.exit(main())
