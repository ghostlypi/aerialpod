"""ServiceHub: everything that owns state, and the commands that change it.

This is what the daemon runs. It is also what the in-process backend runs, so
there is exactly one implementation of "what happens when the user queues an
episode" regardless of how the request arrived.

Every command is void. Commands that would naturally return something were
deliberately reshaped so they don't: the caller can read the answer out of
SQLite itself, and a one-way command needs no blocking round trip and can be
marshalled onto this thread with an ordinary queued signal.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime, timezone
from typing import Any

from PySide6.QtCore import QObject, Signal, Slot

from ..core.downloads import DownloadManager
from ..core.queue import QueueManager
from ..db import repo
from ..feeds.refresher import Refresher
from ..gpodder import credentials
from ..gpodder.sync import SyncScheduler, start_sync_service
from ..lan import pairing
from ..lan.service import LanScheduler, start_lan_service

log = logging.getLogger(__name__)

COMMANDS = (
    "sync_now",
    "refresh_all",
    "refresh_one",
    "subscribe",
    "unsubscribe",
    "import_opml",
    "set_account",
    "forget_account",
    "queue_add",
    "queue_remove",
    "queue_toggle",
    "queue_move",
    "queue_pin",
    "queue_release_to_auto",
    "mark_played",
    "mark_unplayed",
    "reconcile",
    "report_position",
    "set_playing",
    "set_podcast_setting",
    "set_state",
    "lan_pair",
    "lan_new_code",
    "lan_add_peer",
    "lan_remove_peer",
    "lan_discover",
    "announce_state",
)


class ServiceHub(QObject):
    """Owns the services. Lives on the main thread of whichever process runs it."""

    queueChanged = Signal()
    syncStarted = Signal()
    syncFinished = Signal(str)
    syncFailed = Signal(str)
    subscriptionsChanged = Signal(list)     # podcast ids
    refreshStarted = Signal()
    podcastRefreshed = Signal(int)
    refreshFinished = Signal(int)
    refreshError = Signal(int, str)
    peersChanged = Signal(list)             # [{'device_id','caption','address'}]
    lanStatus = Signal(str)
    pairingChanged = Signal()               # the pairing key was replaced
    stateMerged = Signal(dict)
    downloadStarted = Signal(int)
    downloadFinished = Signal(int)
    downloadFailed = Signal(int, str)

    def __init__(self, dry_run_sync: bool = False, parent: QObject | None = None):
        super().__init__(parent)

        self.queue = QueueManager(self)
        self.queue.queueChanged.connect(self.queueChanged)

        self.refresher = Refresher(self)
        self.refresher.refreshStarted.connect(self.refreshStarted)
        self.refresher.podcastRefreshed.connect(self.podcastRefreshed)
        self.refresher.refreshFinished.connect(self._on_refresh_finished)
        self.refresher.refreshError.connect(self.refreshError)

        self.downloads = DownloadManager(self.queue, self)
        self.downloads.downloadStarted.connect(self.downloadStarted)
        self.downloads.downloadFinished.connect(self.downloadFinished)
        self.downloads.downloadFailed.connect(self.downloadFailed)

        self.sync_service, self.sync_thread = start_sync_service(dry_run=dry_run_sync)
        self.sync_scheduler = SyncScheduler(self.sync_service, self)
        # The sync service lives on another thread: only bound methods of
        # main-thread QObjects may be connected, never lambdas.
        self.sync_service.syncStarted.connect(self.syncStarted)
        self.sync_service.syncFinished.connect(self.syncFinished)
        self.sync_service.syncFailed.connect(self.syncFailed)
        self.sync_service.actionsApplied.connect(self.queue.reconcile)
        self.sync_service.subscriptionsChanged.connect(self._on_new_subscriptions)
        self.queue.syncNeeded.connect(self.sync_scheduler.trigger_debounced)

        self.lan_service, self.lan_thread = start_lan_service()
        self.lan = LanScheduler(self.lan_service, self)
        self.lan_service.stateMerged.connect(self._on_lan_merged)
        self.lan_service.peersChanged.connect(self.peersChanged)
        self.lan_service.statusChanged.connect(self.lanStatus)
        self.queue.intentChanged.connect(self.lan.push_snapshot_soon)

    def start(self) -> None:
        """Kick off the work that shouldn't happen during construction."""
        self.lan.start()
        self.sync_scheduler.trigger()
        self.refresher.refresh_all()

    def shutdown(self) -> None:
        self.lan.stop()
        self.lan_thread.quit()
        if not self.lan_thread.wait(2000):
            log.warning("LAN sync thread still busy at shutdown")
        self.sync_service.request_abort()
        self.sync_thread.quit()
        if not self.sync_thread.wait(4000):
            log.warning("sync thread still busy at shutdown")

    def threads_running(self) -> bool:
        return self.sync_thread.isRunning() or self.lan_thread.isRunning()

    # ------------------------------------------------------------ dispatch

    @Slot(str, object)
    def execute(self, name: str, args: object) -> None:
        """Run one command by name. The entry point for anything arriving from
        another thread — a queued signal lands here, so the command body always
        runs on the hub's own thread."""
        if name not in COMMANDS:
            log.warning("ignoring unknown command %r", name)
            return
        try:
            getattr(self, name)(*(args or ()))
        except Exception:  # noqa: BLE001 — a bad command must not kill the daemon
            log.exception("command %s%r failed", name, args)

    # ------------------------------------------------------------ sync & feeds

    def sync_now(self) -> None:
        self.sync_scheduler.trigger()

    def refresh_all(self) -> None:
        self.refresher.refresh_all()

    def refresh_one(self, podcast_id: int) -> None:
        self.refresher.refresh_one(int(podcast_id))

    def subscribe(self, feed_url: str) -> None:
        pid = repo.upsert_podcast(feed_url)
        self.subscriptionsChanged.emit([pid])
        self.refresher.refresh_one(pid)

    def unsubscribe(self, podcast_id: int) -> None:
        repo.unsubscribe_podcast(int(podcast_id))
        self.subscriptionsChanged.emit([])
        self.queue.reconcile()

    def import_opml(self, path: str) -> None:
        from ..feeds import opml

        added = opml.import_opml(path)
        if added:
            self.subscriptionsChanged.emit(list(added))
            for pid in added:
                self.refresher.refresh_one(pid)

    # ------------------------------------------------------------ account

    def set_account(self, username: str, password: str) -> None:
        """Store the gpodder.net account and sync straight away. Routed through
        here rather than written from the window so the service that has to act
        on the change is the one making it."""
        credentials.save(username, password)
        self.sync_scheduler.trigger()

    def forget_account(self) -> None:
        credentials.clear()
        for key, value in (("device_registered", False), ("subs_since", 0),
                           ("actions_since", 0)):
            repo.set_state(key, value)

    def _on_new_subscriptions(self, podcast_ids: list) -> None:
        self.subscriptionsChanged.emit(list(podcast_ids))
        for pid in podcast_ids:
            self.refresher.refresh_one(pid)

    def _on_refresh_finished(self, new_total: int) -> None:
        self.queue.reconcile()
        self.refreshFinished.emit(int(new_total))

    def _on_lan_merged(self, counts: dict) -> None:
        self.queue.reconcile()
        self.stateMerged.emit(dict(counts))

    # ------------------------------------------------------------ queue

    def queue_add(self, episode_id: int, to_front: bool = False) -> None:
        self.queue.add(int(episode_id), bool(to_front))

    def queue_remove(self, episode_id: int, exclude: bool = True) -> None:
        self.queue.remove(int(episode_id), bool(exclude))

    def queue_toggle(self, episode_id: int) -> None:
        self.queue.toggle(int(episode_id))

    def queue_move(self, episode_id: int, new_index: int) -> None:
        self.queue.move(int(episode_id), int(new_index))

    def queue_pin(self, episode_id: int) -> None:
        self.queue.pin(int(episode_id))

    def queue_release_to_auto(self, episode_id: int) -> None:
        self.queue.release_to_auto(int(episode_id))

    def mark_played(self, episode_id: int) -> None:
        self.queue.mark_played_and_advance(int(episode_id))

    def mark_unplayed(self, episode_id: int) -> None:
        self.queue.mark_unplayed(int(episode_id))

    def reconcile(self) -> None:
        self.queue.reconcile()

    def set_playing(self, episode_id: int) -> None:
        """Which episode the front end is playing, so reconcile never drops it.
        0 means nothing is playing."""
        self.queue.playing_episode_id = int(episode_id) or None

    # ------------------------------------------------------------ playback

    def report_position(self, episode_id: int, position: int, total: int,
                        final: bool = False) -> None:
        """The front end's playback heartbeat: every few seconds while playing,
        and once more with final=True on pause, seek, stop and end-of-episode.

        Persisting happens every time; the gpodder action and the nudge to LAN
        peers only on final, which is what the two timers in PlayerService used
        to decide locally.
        """
        episode_id, position, total = int(episode_id), int(position), int(total)
        episode = repo.episode_by_id(episode_id)
        if episode is None:
            return

        updates: dict[str, Any] = {}
        if total > 0 and total != episode.total_secs:
            updates["total_secs"] = total
        if position > 0:
            updates["position_secs"] = position
            updates["position_updated_at"] = int(time.time())
        if updates:
            repo.update_episode(episode_id, **updates)

        if final and position > 0:
            podcast = repo.podcast_by_id(episode.podcast_id)
            if podcast is not None:
                repo.enqueue_action(
                    podcast.feed_url, episode.media_url, "play",
                    datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"),
                    started=position, position=position, total=total or None,
                )
                self.sync_scheduler.trigger_debounced()

        self.lan.note_position(episode_id)
        if final:
            self.lan.flush_now()

    # ------------------------------------------------------------ settings

    def set_podcast_setting(self, podcast_id: int, key: str, value: Any) -> None:
        repo.set_podcast_setting(int(podcast_id), key, value)
        self.queue.reconcile()

    def set_state(self, key: str, value: Any) -> None:
        repo.set_state(key, value)
        # A few keys are not just stored, they change what the services do.
        if key in ("auto_add_to_queue", "auto_queue_position"):
            self.queue.reconcile()
        elif key == "download_ahead_n":
            self.downloads.apply_policy()
        elif key in ("lan_sync_enabled", "lan_port", "lan_scan_subnets"):
            self.lan.restart()

    # ------------------------------------------------------------ LAN

    def lan_pair(self, code: str) -> None:
        pairing.pair_with_code(code)
        self.lan.restart()
        self.pairingChanged.emit()

    def lan_new_code(self) -> None:
        pairing.reset()
        self.lan.restart()
        self.pairingChanged.emit()

    def lan_add_peer(self, address: str, port: int) -> None:
        self.lan.add_peer(address, int(port))

    def lan_remove_peer(self, address: str, port: int) -> None:
        repo.remove_manual_peer(address, int(port))

    def lan_discover(self) -> None:
        self.lan.discover()

    def announce_state(self) -> None:
        """A front end just attached and knows nothing about live connections."""
        self.lan.announce()
