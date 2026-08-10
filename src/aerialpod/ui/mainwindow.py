"""Main window: sidebar navigation + stacked pages + bottom player bar.

The window owns playback and nothing else. Everything that changes stored state
goes through DaemonClient — usually to a background daemon, sometimes to
services running in this process (see aerialpod.ipc). Reads go straight to
SQLite, so pages query `repo` exactly as they always did.
"""

from __future__ import annotations

import logging

from PySide6.QtCore import QSettings, Qt
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

from ..core.player import PlayerService
from ..core.queue import QueueReader
from ..core.sleeptimer import SleepTimer
from ..db import repo
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
    def __init__(self, client):
        super().__init__()
        self.client = client
        self.queue = QueueReader()  # reads only; writes go through the client
        self.setWindowTitle("AerialPod")
        self.setObjectName("MainWindow")
        self.resize(1100, 720)
        self._restore_geometry()

        # ---- playback (the one thing that stays in this process)
        self.player = PlayerService(client, self)
        self.player.episodeChanged.connect(self._on_episode_changed)
        self.player.episodeFinished.connect(self._on_episode_finished)
        self.player.errorOccurred.connect(lambda msg: self._status(msg, 8000))

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

        # ---- daemon signals. Bound methods only: with the D-Bus backend these
        # arrive from another thread, and a lambda would run there.
        client.queueChanged.connect(self._on_queue_changed)
        client.syncStarted.connect(self._on_sync_started)
        client.syncFinished.connect(self._on_sync_finished)
        client.syncFailed.connect(self._on_sync_failed)
        client.subscriptionsChanged.connect(self._on_new_subscriptions)
        client.refreshStarted.connect(self._on_refresh_started)
        client.refreshFinished.connect(self._on_refresh_finished)
        client.refreshError.connect(self._on_refresh_error)
        client.podcastRefreshed.connect(self._on_podcast_refreshed)
        client.downloadFinished.connect(self._on_download_finished)
        client.peersChanged.connect(self._on_lan_peers)
        client.lanStatus.connect(self._on_lan_status)
        client.stateMerged.connect(self._on_lan_merged)
        client.availabilityChanged.connect(self._on_daemon_availability)

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
        self.podcast_page.queueToggled.connect(client.queue_toggle)
        self.podcast_page.refreshRequested.connect(client.refresh_one)
        self.podcast_page.unsubscribeRequested.connect(self.unsubscribe)
        self.podcast_page.settingsRequested.connect(self._podcast_settings)
        self.podcast_page.markPlayedRequested.connect(self._mark_played)
        self.podcast_page.markUnplayedRequested.connect(client.mark_unplayed)

        self.settings_page = SettingsPage(client)
        self.settings_page.themeChanged.connect(self.theme.apply)

        self.home_page = HomePage(self.queue, client)
        self.home_page.playRequested.connect(self.play_episode)
        self.home_page.queueToggled.connect(client.queue_toggle)
        self.home_page.markPlayedRequested.connect(self._mark_played)
        self.home_page.markUnplayedRequested.connect(client.mark_unplayed)
        self.home_page.navigateRequested.connect(self._nav_to)
        self.home_page.podcastOpened.connect(self.open_podcast)

        self.queue_page = QueuePage(self.queue, client)
        self.queue_page.playRequested.connect(self.play_episode)

        self.inbox_page = InboxPage()
        self.inbox_page.playRequested.connect(self.play_episode)
        self.inbox_page.queueToggled.connect(client.queue_toggle)
        self.inbox_page.markPlayedRequested.connect(self._mark_played)
        self.inbox_page.markUnplayedRequested.connect(client.mark_unplayed)

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

        self._setup_shortcuts()

    def _setup_shortcuts(self) -> None:
        from PySide6.QtGui import QKeySequence, QShortcut

        def sc(key: str, handler) -> None:
            QShortcut(QKeySequence(key), self, activated=handler)

        sc("Space", self.player.toggle_play_pause)
        sc("Right", lambda: self.player.seek_relative(int(repo.get_state("skip_fwd_secs"))))
        sc("Left", lambda: self.player.seek_relative(-int(repo.get_state("skip_back_secs"))))
        sc("Ctrl+N", self._play_next_in_queue)
        sc("Ctrl+R", self.client.refresh_all)
        sc("Ctrl+S", self.client.sync_now)
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
        dlg = PodcastSettingsDialog(podcast_id, self.client, self)
        if dlg.exec():
            self.podcast_page.reload()

    def _status(self, message: str, msecs: int = 4000) -> None:
        self.statusBar().showMessage(message, msecs)

    # ------------------------------------------------------------- actions

    def subscribe(self, feed_url: str) -> None:
        self.client.subscribe(feed_url)
        self._status(f"Subscribed — fetching {feed_url}")

    def unsubscribe(self, podcast_id: int) -> None:
        self.client.unsubscribe(podcast_id)
        self._status("Unsubscribed")
        self._show_page("subscriptions")

    def open_podcast(self, podcast_id: int) -> None:
        self.podcast_page.show_podcast(podcast_id)
        self._show_page("podcast")

    def play_episode(self, episode_id: int) -> None:
        self.player.play_episode(episode_id)

    def _on_episode_changed(self, ep) -> None:
        self.client.set_playing(ep.id if ep else None)

    def _on_episode_finished(self, episode_id: int) -> None:
        # Read the next episode before asking the daemon to retire this one:
        # reads are local, so this needs no round trip and no return value.
        nxt = self.queue.next_after(episode_id)
        self.client.mark_played(episode_id)
        if nxt is not None and nxt.id != episode_id:
            self.player.play_episode(nxt.id)

    def _mark_played(self, episode_id: int) -> None:
        self.client.mark_played(episode_id)

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

    def _on_refresh_started(self) -> None:
        self._status("Refreshing feeds…")

    def _on_refresh_finished(self, new_total: int) -> None:
        self._status(f"Feeds refreshed — {new_total} new episode(s)")

    def _on_refresh_error(self, _podcast_id: int, message: str) -> None:
        self._status(f"Feed refresh failed: {message}", 8000)

    def _on_download_finished(self, _episode_id: int) -> None:
        self._status("Episode downloaded", 3000)

    def _on_queue_changed(self) -> None:
        # Refresh whatever page is visible so +/− buttons stay accurate.
        current = self.pages.currentIndex()
        if current == self._page_index["podcast"]:
            self.podcast_page.reload()
        elif current == self._page_index["inbox"]:
            self.inbox_page.reload()
        elif current == self._page_index["queue"]:
            self.queue_page.reload()
        elif current == self._page_index["home"]:
            self.home_page.reload()

    def _on_sync_started(self) -> None:
        self._status("Syncing with gpodder.net…")

    def _on_sync_finished(self, message: str) -> None:
        self._status(message, 6000)
        self.settings_page.show_sync_status(message)

    def _on_sync_failed(self, message: str) -> None:
        self._status(f"Sync failed: {message}", 8000)
        self.settings_page.show_sync_status(f"Sync failed: {message}")

    def _on_new_subscriptions(self, _podcast_ids: list) -> None:
        self.subscriptions_page.reload()

    def _on_lan_merged(self, counts: dict) -> None:
        self._on_queue_changed()
        if counts.get("intents") or counts.get("settings"):
            self._status("Synced with a device on your network", 4000)

    def _on_lan_peers(self, peers: list) -> None:
        self.settings_page.show_lan_peers(peers)
        if peers:
            names = ", ".join(p["caption"] for p in peers)
            self._status(f"Device sync connected: {names}", 4000)

    def _on_lan_status(self, message: str) -> None:
        self.settings_page.show_lan_status(message)

    def _on_daemon_availability(self, available: bool) -> None:
        if available:
            # Connected peers and the sync status live in the daemon's memory,
            # so opening this window is the one moment we have to ask.
            self.client.announce_state()
            self._on_queue_changed()
            self.subscriptions_page.reload()
        else:
            self._status("Background service went away — reconnecting…", 6000)

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
        self.client.shutdown()
        super().closeEvent(event)
