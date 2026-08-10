"""The seam between the front end and whatever owns the data.

AerialPod normally runs as two processes: a daemon that starts with your
session and owns every write — gpodder sync, the LAN peer mesh, feed refresh,
the queue, downloads — and a UI that reads SQLite directly and sends commands
for anything that changes state.

Reads stay direct on purpose. WAL gives multiple processes concurrent readers
that never block the writer, so rendering a page costs no round trip and there
is no query API to build or keep in sync. Only mutations cross the boundary,
which keeps the interface small enough to hold in your head.

Everything goes through DaemonClient, which has two interchangeable backends:

  InProcessBackend  constructs the services in this process and calls them
                    directly. This is what runs where there is no session bus
                    (macOS), and what runs if the daemon can't be reached.
  DBusBackend       forwards to the daemon over org.aerialpod.Daemon.

Every command is one-way. Anything the UI needs an answer to — what's next in
the queue, the pairing code, whether an episode is queued — it reads for
itself, so no call ever has to block on a round trip.
"""

from .client import DaemonClient
from .hub import ServiceHub

__all__ = ["DaemonClient", "ServiceHub"]
