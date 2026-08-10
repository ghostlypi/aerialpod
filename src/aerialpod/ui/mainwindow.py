"""Main window: sidebar navigation + stacked pages + bottom player bar."""

from __future__ import annotations

import logging

from PySide6.QtCore import QSettings, Qt, QTimer
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QStackedWidget,
    QStatusBar,
    QVBoxLayout,
    QWidget,
)

from ..core.downloads import DownloadManager
from ..core.player import PlayerService
from ..core.queue import QueueManager
from ..db import repo
from ..feeds.refresher import Refresher
from ..core.sleeptimer import SleepTimer
from ..gpodder.sync import SyncScheduler, start_sync_service
from ..lan.service import LanScheduler, start_lan_service
from ..mpris.service import MprisBridge
from .home_page import HomePage
from .inbox_page import InboxPage
from .player_bar import PlayerBar
from .podcast_page import PodcastPage
from .podcast_settings_dialog import PodcastSettingsDialog
from .queue_view import QueuePage
from .settings_page import SettingsPage
from .subscriptions_page import SubscriptionsPage
from .theming import ThemeManager

log = logging.getLogger(__name__)

NAV = [
    ("home", "Home"),
    ("queue", "Queue"),
    ("inbox", "Inbox"),
    ("subscriptions", "Subscriptions"),
    ("settings", "Settings"),
]


class MainWindow(QMainWindow):
    def __init__(self, dry_run_sync: bool = False):
        super().__init__()
        self.dry_run_sync = dry_run_sync
        self.setWindowTitle("AerialPod")
        self.setObjectName("MainWindow")
        self.resize(1100, 720)
        self._restore_geometry()

        # ---- services
        self.queue = QueueManager(self)
        self.player = PlayerService(self)
        self.player.episodeChanged.connect(
            lambda ep: setattr(self.queue, "playing_episode_id", ep.id if ep else None)
        )
        self.player.episodeFinished.connect(self._on_episode_finished)
        self.player.errorOccurred.connect(lambda msg: self._status(msg, 8000))
        self.refresher = Refresher(self)
        self.refresher.refreshStarted.connect(lambda: self._status("Refreshing feeds…"))
        self.refresher.podcastRefreshed.connect(self._on_podcast_refreshed)
        self.refresher.refreshFinished.connect(self._on_refresh_finished)
        self.refresher.refreshError.connect(
            lambda pid, msg: self._status(f"Feed refresh failed: {msg}", 8000)
        )
        self.queue.queueChanged.connect(self._on_queue_changed)
        self.downloads = DownloadManager(self.queue, self)
        self.downloads.downloadFinished.connect(
            lambda eid: self._status("Episode downloaded", 3000)
        )
        # MPRIS (media keys, shell media widget) is a Linux/D-Bus thing;
        # on macOS the app runs without it.
        import sys as _sys

        self.mpris = None
        if _sys.platform.startswith("linux"):
            self.mpris = MprisBridge(self.player, self)
            self.mpris.nextRequested.connect(self._on_mpris_next)
            self.mpris.previousRequested.connect(self._on_mpris_previous)
        self.sleep_timer = SleepTimer(self.player, self)

        from PySide6.QtWidgets import QApplication

        self.theme = ThemeManager(QApplication.instance(), self)
        self.theme.apply()

        self.sync_service, self.sync_thread = start_sync_service(dry_run=dry_run_sync)
        self.sync_scheduler = SyncScheduler(self.sync_service, self)
        # SyncService lives on another thread: connect ONLY to bound methods of
        # main-thread QObjects (lambdas/free functions would run in the sync
        # thread and touch widgets there — crash).
        self.sync_service.syncStarted.connect(self._on_sync_started)
        self.sync_service.syncFinished.connect(self._on_sync_finished)
        self.sync_service.syncFailed.connect(self._on_sync_failed)
        self.sync_service.actionsApplied.connect(self.queue.reconcile)
        self.sync_service.subscriptionsChanged.connect(self._on_new_subscriptions)
        self.player.playbackStateChanged.connect(
            lambda _s: self.sync_scheduler.trigger_debounced()
        )
        # push mark-played (and future outbox-writing queue ops) promptly
        self.queue.syncNeeded.connect(self.sync_scheduler.trigger_debounced)

        # ---- LAN sync (other AerialPod installs on this network / VPN)
        self.lan_service, self.lan_thread = start_lan_service()
        self.lan = LanScheduler(self.lan_service, self)
        # Same rule as the sync service: bound methods of main-thread objects
        # only, never lambdas — a lambda would run in the LAN thread.
        self.lan_service.stateMerged.connect(self._on_lan_merged)
        self.lan_service.peersChanged.connect(self._on_lan_peers)
        self.lan_service.statusChanged.connect(self._on_lan_status)
        self.queue.intentChanged.connect(self.lan.push_snapshot_soon)
        self.player.positionChanged.connect(self._on_player_position)
        self.player.playbackStateChanged.connect(self._on_lan_playback_state)
        self.player.seeked.connect(self._on_lan_seeked)

        # ---- layout
        central = QWidget()
        outer = QVBoxLayout(central)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)

        body = QHBoxLayout()
        body.setContentsMargins(0, 0, 0, 0)
        body.setSpacing(0)

        self.nav = QListWidget()
        self.nav.setObjectName("NavList")
        self.nav.setFixedWidth(180)
        for key, label in NAV:
            item = QListWidgetItem(label)
            item.setData(Qt.ItemDataRole.UserRole, key)
            self.nav.addItem(item)
        self.nav.currentRowChanged.connect(self._on_nav)

        self.pages = QStackedWidget()
        self._page_index: dict[str, int] = {}

        self.subscriptions_page = SubscriptionsPage()
        self.subscriptions_page.podcastOpened.connect(self.open_podcast)
        self.subscriptions_page.subscribeRequested.connect(self.subscribe)

        self.podcast_page = PodcastPage()
        self.podcast_page.backRequested.connect(lambda: self._show_page("subscriptions"))
        self.podcast_page.playRequested.connect(self.play_episode)
        self.podcast_page.queueToggled.connect(self.queue.toggle)
        self.podcast_page.refreshRequested.connect(self.refresher.refresh_one)
        self.podcast_page.unsubscribeRequested.connect(self.unsubscribe)
        self.podcast_page.settingsRequested.connect(self._podcast_settings)
        self.podcast_page.markPlayedRequested.connect(self._mark_played)
        self.podcast_page.markUnplayedRequested.connect(self.queue.mark_unplayed)

        self.settings_page = SettingsPage()
        self.settings_page.syncRequested.connect(self.sync_scheduler.trigger)
        self.settings_page.themeChanged.connect(self.theme.apply)
        self.settings_page.opmlImported.connect(self._on_new_subscriptions)
        self.settings_page.lanSettingsChanged.connect(self.lan.restart)
        self.settings_page.lanPairingChanged.connect(self.lan.restart)
        self.settings_page.lanPeerAdded.connect(self.lan.add_peer)
        self.settings_page.lanDiscoverRequested.connect(self.lan.discover)

        self.home_page = HomePage(self.queue)
        self.home_page.playRequested.connect(self.play_episode)
        self.home_page.queueToggled.connect(self.queue.toggle)
        self.home_page.markPlayedRequested.connect(self._mark_played)
        self.home_page.markUnplayedRequested.connect(self.queue.mark_unplayed)
        self.home_page.navigateRequested.connect(self._nav_to)
        self.home_page.podcastOpened.connect(self.open_podcast)

        self.queue_page = QueuePage(self.queue)
        self.queue_page.playRequested.connect(self.play_episode)

        self.inbox_page = InboxPage()
        self.inbox_page.playRequested.connect(self.play_episode)
        self.inbox_page.queueToggled.connect(self.queue.toggle)
        self.inbox_page.markPlayedRequested.connect(self._mark_played)
        self.inbox_page.markUnplayedRequested.connect(self.queue.mark_unplayed)

        page_map: dict[str, QWidget] = {
            "home": self.home_page,
            "queue": self.queue_page,
            "inbox": self.inbox_page,
            "subscriptions": self.subscriptions_page,
            "settings": self.settings_page,
        }
        for key, label in NAV:
            widget = page_map.get(key) or self._placeholder(label)
            self._page_index[key] = self.pages.addWidget(widget)
        self._page_index["podcast"] = self.pages.addWidget(self.podcast_page)

        body.addWidget(self.nav)
        body.addWidget(self.pages, 1)
        outer.addLayout(body, 1)

        self.player_bar = PlayerBar(self.player)
        self.player_bar.attach_sleep_timer(self.sleep_timer)
        outer.addWidget(self.player_bar)

        self.setCentralWidget(central)
        self.setStatusBar(QStatusBar())

        self.nav.setCurrentRow(0)
        self.subscriptions_page.reload()
        self.home_page.reload()
        self.queue.queueChanged.connect(self._maybe_reload_home)

        self._setup_shortcuts()

        # initial refresh + sync shortly after startup
        QTimer.singleShot(1500, self.refresher.refresh_all)
        QTimer.singleShot(3000, self.sync_scheduler.trigger)
        QTimer.singleShot(2000, self.lan.start)

    def _setup_shortcuts(self) -> None:
        from PySide6.QtGui import QKeySequence, QShortcut

        def sc(key: str, handler) -> None:
            QShortcut(QKeySequence(key), self, activated=handler)

        sc("Space", self.player.toggle_play_pause)
        sc("Right", lambda: self.player.seek_relative(int(repo.get_state("skip_fwd_secs"))))
        sc("Left", lambda: self.player.seek_relative(-int(repo.get_state("skip_back_secs"))))
        sc("Ctrl+N", self._play_next_in_queue)
        sc("Ctrl+R", self.refresher.refresh_all)
        sc("Ctrl+S", self.sync_scheduler.trigger)
        for i, (key, _label) in enumerate(NAV, start=1):
            sc(f"Ctrl+{i}", lambda k=key: self._nav_to(k))

    # ------------------------------------------------------------- helpers

    def _placeholder(self, label: str) -> QWidget:
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.addWidget(QLabel(f"{label} — coming soon"), alignment=Qt.AlignmentFlag.AlignCenter)
        return page

    def _show_page(self, key: str) -> None:
        self.pages.setCurrentIndex(self._page_index[key])

    def _on_nav(self, row: int) -> None:
        if row < 0:
            return
        key = self.nav.item(row).data(Qt.ItemDataRole.UserRole)
        self._show_page(key)
        if key == "subscriptions":
            self.subscriptions_page.reload()
        elif key == "queue":
            self.queue_page.reload()
        elif key == "inbox":
            self.inbox_page.reload()
        elif key == "home":
            self.home_page.reload()

    def _nav_to(self, key: str) -> None:
        for row in range(self.nav.count()):
            if self.nav.item(row).data(Qt.ItemDataRole.UserRole) == key:
                self.nav.setCurrentRow(row)
                return

    def _maybe_reload_home(self) -> None:
        if self.pages.currentIndex() == self._page_index["home"]:
            self.home_page.reload()

    def _podcast_settings(self, podcast_id: int) -> None:
        dlg = PodcastSettingsDialog(podcast_id, self)
        if dlg.exec():
            self.podcast_page.reload()
            self.queue.reconcile()

    def _status(self, message: str, msecs: int = 4000) -> None:
        self.statusBar().showMessage(message, msecs)

    # ------------------------------------------------------------- actions

    def subscribe(self, feed_url: str) -> None:
        pid = repo.upsert_podcast(feed_url)
        self._status(f"Subscribed — fetching {feed_url}")
        self.subscriptions_page.reload()
        self.refresher.refresh_one(pid)

    def unsubscribe(self, podcast_id: int) -> None:
        repo.unsubscribe_podcast(podcast_id)
        self._status("Unsubscribed")
        self._show_page("subscriptions")
        self.subscriptions_page.reload()
        self.queue.reconcile()

    def open_podcast(self, podcast_id: int) -> None:
        self.podcast_page.show_podcast(podcast_id)
        self._show_page("podcast")

    def play_episode(self, episode_id: int) -> None:
        self.player.play_episode(episode_id)

    def _on_episode_finished(self, episode_id: int) -> None:
        nxt = self.queue.mark_played_and_advance(episode_id)
        if nxt is not None:
            self.player.play_episode(nxt.id)

    def _mark_played(self, episode_id: int) -> None:
        self.queue.mark_played_and_advance(episode_id)

    def _play_next_in_queue(self) -> None:
        current = self.player.episode
        nxt = self.queue.next_after(current.id) if current else self.queue.head()
        if nxt is not None:
            self.player.play_episode(nxt.id)

    def _on_mpris_next(self) -> None:
        # Keyboard ⏭ / shell widget Next: podcast-first default is an ad-skip
        # seek; 'episode' mode jumps the queue instead (Settings → Playback).
        if repo.get_state("media_next_action") == "episode":
            self._play_next_in_queue()
        else:
            self.player.seek_forward_default()

    def _on_mpris_previous(self) -> None:
        self.player.seek_back_default()

    # ------------------------------------------------------------- signals

    def _on_podcast_refreshed(self, podcast_id: int) -> None:
        if self.pages.currentIndex() == self._page_index["podcast"] and (
            self.podcast_page.podcast_id == podcast_id
        ):
            self.podcast_page.reload()
        if self.pages.currentIndex() == self._page_index["subscriptions"]:
            self.subscriptions_page.reload()

    def _on_refresh_finished(self, new_total: int) -> None:
        self._status(f"Feeds refreshed — {new_total} new episode(s)")
        self.queue.reconcile()

    def _on_queue_changed(self) -> None:
        # Refresh whatever page is visible so +/− buttons stay accurate.
        if self.pages.currentIndex() == self._page_index["podcast"]:
            self.podcast_page.reload()
        elif self.pages.currentIndex() == self._page_index["inbox"]:
            self.inbox_page.reload()

    def _on_sync_started(self) -> None:
        self._status("Syncing with gpodder.net…")

    def _on_sync_finished(self, message: str) -> None:
        self._status(message, 6000)
        self.settings_page.show_sync_status(message)

    def _on_sync_failed(self, message: str) -> None:
        self._status(f"Sync failed: {message}", 8000)
        self.settings_page.show_sync_status(f"Sync failed: {message}")

    # ------------------------------------------------------------- LAN sync

    def _on_lan_merged(self, counts: dict) -> None:
        """A peer's state landed — re-derive the queue and refresh the view."""
        self.queue.reconcile()
        self._maybe_reload_home()
        self._on_queue_changed()
        if self.pages.currentIndex() == self._page_index["queue"]:
            self.queue_page.reload()
        if counts.get("intents") or counts.get("settings"):
            self._status("Synced with a device on your network", 4000)

    def _on_lan_peers(self, peers: list) -> None:
        self.settings_page.show_lan_peers(peers)
        if peers:
            names = ", ".join(p["caption"] for p in peers)
            self._status(f"Device sync connected: {names}", 4000)

    def _on_lan_status(self, message: str) -> None:
        self.settings_page.show_lan_status(message)

    def _on_player_position(self, _secs: int, _total: int) -> None:
        ep = self.player.episode
        self.lan.note_position(ep.id if ep else None)

    def _on_lan_playback_state(self, _state) -> None:
        self.lan.flush_now()

    def _on_lan_seeked(self, _secs: int) -> None:
        self.lan.flush_now()

    def _on_new_subscriptions(self, podcast_ids: list) -> None:
        self.subscriptions_page.reload()
        for pid in podcast_ids:
            self.refresher.refresh_one(pid)

    # ------------------------------------------------------------- geometry

    def _restore_geometry(self) -> None:
        geo = QSettings().value("mainwindow/geometry")
        if geo is not None:
            self.restoreGeometry(geo)

    def closeEvent(self, event) -> None:  # noqa: N802 (Qt naming)
        QSettings().setValue("mainwindow/geometry", self.saveGeometry())
        self.player.shutdown()
        if self.mpris is not None:
            self.mpris.shutdown()
        self.lan.stop()
        self.lan_thread.quit()
        if not self.lan_thread.wait(2000):
            log.warning("LAN sync thread still busy at close")
        self.sync_service.request_abort()
        self.sync_thread.quit()
        if not self.sync_thread.wait(4000):
            # A sync is stuck in a network call. main() hard-exits the process
            # rather than letting a running QThread be destructed (→ abort).
            log.warning("sync thread still busy at close; will hard-exit")
        super().closeEvent(event)
