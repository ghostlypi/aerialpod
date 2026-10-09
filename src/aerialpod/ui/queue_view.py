"""Queue page: drag-to-reorder list backed by QueueManager.

Drag semantics: dropping a row pins it ("the user placed this here").
Scrolling up past the top of the list refreshes every feed, like a phone's
pull-to-refresh.
Context menu: play, remove (→ exclusion), pin/release-to-auto, mark played.
"""

from __future__ import annotations

import logging

from PySide6.QtCore import QSize, Qt, QTimer, Signal
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMenu,
    QProgressBar,
    QVBoxLayout,
    QWidget,
)

from ..core.queue import QueueReader
from ..ads import detector
from ..db import repo
from .episode_list import EpisodeRow

log = logging.getLogger(__name__)


# How far to keep scrolling up once already at the top: three wheel notches.
PULL_THRESHOLD = 3 * 120
# A pause this long ends the pull; the next one starts from zero.
PULL_RESET_MS = 600


class QueueList(QListWidget):
    reordered = Signal(int, int)  # episode_id, new_index
    pullProgress = Signal(float)  # 0 = idle, 1 = refresh triggered
    pullRefresh = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self._pull = 0
        self._pull_fired = False
        self._pull_reset = QTimer(self, singleShot=True, interval=PULL_RESET_MS)
        self._pull_reset.timeout.connect(self._end_pull)
        self.setObjectName("QueueList")
        self.setVerticalScrollMode(QListWidget.ScrollMode.ScrollPerPixel)
        self.setSelectionMode(QListWidget.SelectionMode.SingleSelection)
        self.setDragDropMode(QListWidget.DragDropMode.InternalMove)
        self.setDefaultDropAction(Qt.DropAction.MoveAction)

    def wheelEvent(self, event) -> None:  # noqa: N802 (Qt naming)
        bar = self.verticalScrollBar()
        at_top = bar.value() == bar.minimum()
        dy = event.angleDelta().y()
        # Touchpad momentum after a flick is not the user still pushing, and
        # must not turn "scrolled back to the top" into a refresh.
        momentum = event.phase() == Qt.ScrollPhase.ScrollMomentum
        if not (at_top and dy > 0) or momentum:
            if dy < 0:
                self._end_pull()
            super().wheelEvent(event)
            return
        event.accept()
        self._pull_reset.start()
        if self._pull_fired:
            return  # one refresh per pull; pause to arm another
        self._pull += dy
        if self._pull >= PULL_THRESHOLD:
            self._pull_fired = True
            self.pullProgress.emit(1.0)
            self.pullRefresh.emit()
        else:
            self.pullProgress.emit(self._pull / PULL_THRESHOLD)

    def _end_pull(self) -> None:
        self._pull_reset.stop()
        if self._pull or self._pull_fired:
            self._pull = 0
            self._pull_fired = False
            self.pullProgress.emit(0.0)

    def dropEvent(self, event) -> None:  # noqa: N802 (Qt naming)
        item = self.currentItem()
        eid = item.data(Qt.ItemDataRole.UserRole) if item else None
        super().dropEvent(event)
        if eid is None:
            return
        for i in range(self.count()):
            if self.item(i).data(Qt.ItemDataRole.UserRole) == eid:
                self.reordered.emit(eid, i)
                return


class QueuePage(QWidget):
    playRequested = Signal(int)

    def __init__(self, queue: QueueReader, client, parent=None):
        super().__init__(parent)
        self.queue = queue
        self.client = client

        lay = QVBoxLayout(self)
        header = QHBoxLayout()
        title = QLabel("Queue")
        title.setObjectName("PageTitle")
        header.addWidget(title)
        self.count_label = QLabel("")
        self.count_label.setObjectName("QueueCount")
        header.addWidget(self.count_label)
        header.addStretch(1)
        lay.addLayout(header)

        self.hint = QLabel(
            "Your queue is built from your gpodder sync — episodes you start on "
            "your phone surface at the top. Drag to reorder (pins the item); "
            "removals here are never auto re-added. Scroll up past the top "
            "to refresh every feed."
        )
        self.hint.setObjectName("QueueHint")
        self.hint.setWordWrap(True)
        lay.addWidget(self.hint)

        self.list = QueueList()
        self.list.reordered.connect(self.client.queue_move)
        self.list.pullProgress.connect(self._on_pull_progress)
        self.list.pullRefresh.connect(self.client.refresh_all)
        self.list.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.list.customContextMenuRequested.connect(self._context_menu)
        self.list.itemDoubleClicked.connect(
            lambda item: self.playRequested.emit(item.data(Qt.ItemDataRole.UserRole))
        )
        # Sits above the list while pulling or refreshing; hidden otherwise.
        # The bar fills as you scroll past the top, then turns into a busy
        # indicator until the daemon reports the refresh finished.
        self.pull_box = QWidget()
        pull_lay = QVBoxLayout(self.pull_box)
        pull_lay.setContentsMargins(0, 4, 0, 4)
        self.pull_label = QLabel("")
        self.pull_label.setObjectName("QueueHint")
        self.pull_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        pull_lay.addWidget(self.pull_label)
        self.pull_bar = QProgressBar()
        self.pull_bar.setObjectName("PullProgress")
        self.pull_bar.setTextVisible(False)
        self.pull_bar.setFixedHeight(4)
        pull_lay.addWidget(self.pull_bar)
        self.pull_box.hide()
        lay.addWidget(self.pull_box)
        lay.addWidget(self.list, 1)
        self._refreshing = False
        client.refreshStarted.connect(lambda: self._set_refreshing(True))
        client.refreshFinished.connect(lambda _n: self._set_refreshing(False))

    def _on_pull_progress(self, progress: float) -> None:
        if self._refreshing:
            return
        if progress <= 0:
            self.pull_box.hide()
        elif progress < 1:
            self.pull_label.setText("↑ Keep scrolling to refresh")
            self.pull_bar.setRange(0, 100)
            self.pull_bar.setValue(int(progress * 100))
            self.pull_box.show()
        else:
            # The daemon answers with refreshStarted; show it before it does.
            self._show_busy()

    def _show_busy(self) -> None:
        self.pull_label.setText("Refreshing feeds…")
        self.pull_bar.setRange(0, 0)  # indeterminate: Qt animates it
        self.pull_box.show()

    def _set_refreshing(self, on: bool) -> None:
        self._refreshing = on
        if on:
            self._show_busy()
        else:
            self.pull_box.hide()

    def reload(self) -> None:
        self.list.clear()
        items = {q.episode_id: q for q in repo.queue_items()}
        episodes = self.queue.episodes()
        pinfo = repo.podcast_display_info()  # one query for all rows
        self.list.setUpdatesEnabled(False)
        try:
            for ep in episodes:
                q = items.get(ep.id)
                title, image = pinfo.get(ep.podcast_id, (None, None))
                row = EpisodeRow(ep, podcast_title=title, fallback_cover=image,
                                 in_queue=True)
                row.playRequested.connect(self.playRequested)
                row.queueToggled.connect(self.client.queue_remove)
                if q is not None and q.pinned:
                    row.play_btn.setToolTip("Play (pinned in place)")
                item = QListWidgetItem()
                item.setData(Qt.ItemDataRole.UserRole, ep.id)
                pin_note = " (pinned)" if (q is not None and q.pinned) else ""
                item.setToolTip(f"{ep.title}{pin_note}")
                item.setSizeHint(QSize(0, max(row.sizeHint().height(), 72)))
                self.list.addItem(item)
                self.list.setItemWidget(item, row)
        finally:
            self.list.setUpdatesEnabled(True)
        n = len(episodes)
        self.count_label.setText(f"· {n} episode{'s' if n != 1 else ''}")

    def _context_menu(self, pos) -> None:
        item = self.list.itemAt(pos)
        if item is None:
            return
        eid = item.data(Qt.ItemDataRole.UserRole)
        q = next((x for x in repo.queue_items() if x.episode_id == eid), None)
        menu = QMenu(self)
        menu.addAction("Play", lambda: self.playRequested.emit(eid))
        menu.addAction("Remove from queue", lambda: self.client.queue_remove(eid))
        if q is not None:
            if q.pinned:
                menu.addAction("Release to auto", lambda: self.client.queue_release_to_auto(eid))
            else:
                menu.addAction("Pin in place", lambda: self.client.queue_pin(eid))
        menu.addAction("Mark played", lambda: self.client.mark_played(eid))
        ads = repo.ad_segments(eid)
        if ads is not None or detector.toolchain_python() is not None:
            menu.addSeparator()
        if detector.toolchain_python() is not None:
            menu.addAction("Find ads again" if ads is not None else "Find ads now",
                           lambda: self.client.ads_detect(eid))
        if ads is not None and ads["spans"]:
            menu.addAction(f"No ads here (forget {len(ads['spans'])} found)",
                           lambda: self.client.ads_clear(eid))
        menu.exec(self.list.mapToGlobal(pos))
