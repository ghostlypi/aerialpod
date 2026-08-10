"""DaemonClient: the only way the UI changes anything.

Method names mirror the ServiceHub commands they invoke, and the signals mirror
the ones the services already emitted — so a page connects to
`client.queueChanged` exactly as it used to connect to `queue.queueChanged`,
and never has to know which process did the work.
"""

from __future__ import annotations

import logging

from PySide6.QtCore import QObject, Signal

from .hub import COMMANDS

log = logging.getLogger(__name__)


class DaemonClient(QObject):
    # ---- mirrored from ServiceHub
    queueChanged = Signal()
    syncStarted = Signal()
    syncFinished = Signal(str)
    syncFailed = Signal(str)
    subscriptionsChanged = Signal(list)
    refreshStarted = Signal()
    podcastRefreshed = Signal(int)
    refreshFinished = Signal(int)
    refreshError = Signal(int, str)
    peersChanged = Signal(list)
    lanStatus = Signal(str)
    pairingChanged = Signal()
    stateMerged = Signal(dict)
    downloadStarted = Signal(int)
    downloadFinished = Signal(int)
    downloadFailed = Signal(int, str)

    # ---- about the connection itself
    availabilityChanged = Signal(bool)   # daemon reachable / not

    SIGNALS = (
        "queueChanged", "syncStarted", "syncFinished", "syncFailed",
        "subscriptionsChanged", "refreshStarted", "podcastRefreshed",
        "refreshFinished", "refreshError", "peersChanged", "lanStatus",
        "pairingChanged", "stateMerged", "downloadStarted", "downloadFinished",
        "downloadFailed",
    )

    def __init__(self, backend, parent: QObject | None = None):
        super().__init__(parent)
        self.backend = backend
        backend.attach(self)

    # ------------------------------------------------------------ commands

    def _send(self, name: str, *args) -> None:
        self.backend.send(name, args)

    def sync_now(self) -> None:
        self._send("sync_now")

    def refresh_all(self) -> None:
        self._send("refresh_all")

    def refresh_one(self, podcast_id: int) -> None:
        self._send("refresh_one", int(podcast_id))

    def subscribe(self, feed_url: str) -> None:
        self._send("subscribe", feed_url)

    def unsubscribe(self, podcast_id: int) -> None:
        self._send("unsubscribe", int(podcast_id))

    def import_opml(self, path: str) -> None:
        self._send("import_opml", path)

    def set_account(self, username: str, password: str) -> None:
        self._send("set_account", username, password)

    def forget_account(self) -> None:
        self._send("forget_account")

    def queue_add(self, episode_id: int, to_front: bool = False) -> None:
        self._send("queue_add", int(episode_id), bool(to_front))

    def queue_remove(self, episode_id: int, exclude: bool = True) -> None:
        self._send("queue_remove", int(episode_id), bool(exclude))

    def queue_toggle(self, episode_id: int) -> None:
        self._send("queue_toggle", int(episode_id))

    def queue_move(self, episode_id: int, new_index: int) -> None:
        self._send("queue_move", int(episode_id), int(new_index))

    def queue_pin(self, episode_id: int) -> None:
        self._send("queue_pin", int(episode_id))

    def queue_release_to_auto(self, episode_id: int) -> None:
        self._send("queue_release_to_auto", int(episode_id))

    def mark_played(self, episode_id: int) -> None:
        self._send("mark_played", int(episode_id))

    def mark_unplayed(self, episode_id: int) -> None:
        self._send("mark_unplayed", int(episode_id))

    def reconcile(self) -> None:
        self._send("reconcile")

    def set_playing(self, episode_id: int | None) -> None:
        self._send("set_playing", int(episode_id or 0))

    def report_position(self, episode_id: int, position: int, total: int,
                        final: bool = False) -> None:
        self._send("report_position", int(episode_id), int(position), int(total),
                   bool(final))

    def set_podcast_setting(self, podcast_id: int, key: str, value) -> None:
        self._send("set_podcast_setting", int(podcast_id), key, value)

    def set_state(self, key: str, value) -> None:
        self._send("set_state", key, value)

    def lan_pair(self, code: str) -> None:
        self._send("lan_pair", code)

    def lan_new_code(self) -> None:
        self._send("lan_new_code")

    def lan_add_peer(self, address: str, port: int) -> None:
        self._send("lan_add_peer", address, int(port))

    def lan_remove_peer(self, address: str, port: int) -> None:
        self._send("lan_remove_peer", address, int(port))

    def lan_discover(self) -> None:
        self._send("lan_discover")

    def announce_state(self) -> None:
        """Ask the daemon to re-emit live state we cannot read from the database."""
        self._send("announce_state")

    # ------------------------------------------------------------ lifecycle

    def start(self) -> None:
        self.backend.start()

    def shutdown(self) -> None:
        self.backend.shutdown()


# Every command method on the client must correspond to a hub command; the
# contract test asserts this, so a typo can't quietly become a no-op.
CLIENT_COMMANDS = tuple(
    name for name in COMMANDS if callable(getattr(DaemonClient, name, None))
)
