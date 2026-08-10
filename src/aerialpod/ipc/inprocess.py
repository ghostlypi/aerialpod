"""InProcessBackend: run the services here, in this process.

This is what the app did before the split, kept as a first-class path rather
than a transitional one — it is how AerialPod runs where there is no session
bus (macOS), and how it keeps running if the daemon can't be reached.
"""

from __future__ import annotations

import logging

from .hub import ServiceHub

log = logging.getLogger(__name__)


class InProcessBackend:
    name = "in-process"

    def __init__(self, dry_run_sync: bool = False):
        self.hub = ServiceHub(dry_run_sync=dry_run_sync)
        self.client = None

    def attach(self, client) -> None:
        self.client = client
        # Direct signal-to-signal connections: same thread, no marshalling.
        for name in client.SIGNALS:
            getattr(self.hub, name).connect(getattr(client, name))

    def send(self, name: str, args: tuple) -> None:
        self.hub.execute(name, args)

    def start(self) -> None:
        if self.client is not None:
            self.client.availabilityChanged.emit(True)
        self.hub.start()

    def shutdown(self) -> None:
        self.hub.shutdown()

    @property
    def threads_running(self) -> bool:
        return self.hub.threads_running()
