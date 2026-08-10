"""Queue page: drag-to-reorder list backed by QueueManager.

Drag semantics: dropping a row pins it ("the user placed this here").
Context menu: play, remove (→ exclusion), pin/release-to-auto, mark played.
"""

from __future__ import annotations

import logging

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMenu,
    QVBoxLayout,
    QWidget,
)

from ..core.queue import QueueReader
from ..db import repo
from .episode_list import EpisodeRow

log = logging.getLogger(__name__)


class QueueList(QListWidget):
    reordered = Signal(int, int)  # episode_id, new_index

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("QueueList")
        self.setVerticalScrollMode(QListWidget.ScrollMode.ScrollPerPixel)
        self.setSelectionMode(QListWidget.SelectionMode.SingleSelection)
        self.setDragDropMode(QListWidget.DragDropMode.InternalMove)
        self.setDefaultDropAction(Qt.DropAction.MoveAction)

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
            "removals here are never auto re-added."
        )
        self.hint.setObjectName("QueueHint")
        self.hint.setWordWrap(True)
        lay.addWidget(self.hint)

        self.list = QueueList()
        self.list.reordered.connect(self.client.queue_move)
        self.list.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.list.customContextMenuRequested.connect(self._context_menu)
        self.list.itemDoubleClicked.connect(
            lambda item: self.playRequested.emit(item.data(Qt.ItemDataRole.UserRole))
        )
        lay.addWidget(self.list, 1)


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
        menu.exec(self.list.mapToGlobal(pos))
