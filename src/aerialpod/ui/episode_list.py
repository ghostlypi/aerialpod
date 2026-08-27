"""Reusable episode list widget: cover, title, meta line, progress, actions.

Performance rules (clicking a podcast used to hang the UI):
- Rows never query the DB — all context (queue membership, podcast title,
  fallback cover) is fetched ONCE per list and passed in.
- Long lists render in pages of PAGE_SIZE with a "Show more" row instead of
  building hundreds of widget trees synchronously.
"""

from __future__ import annotations

from datetime import datetime, timezone

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMenu,
    QProgressBar,
    QPushButton,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)

from ..db import repo
from ..db.models import Episode
from . import images

PAGE_SIZE = 50


def fmt_duration(secs: int | None) -> str:
    if not secs:
        return ""
    h, rem = divmod(int(secs), 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def fmt_date(epoch: int | None) -> str:
    if not epoch:
        return ""
    return datetime.fromtimestamp(epoch, tz=timezone.utc).astimezone().strftime("%b %-d, %Y")


class EpisodeRow(QWidget):
    playRequested = Signal(int)     # episode_id
    queueToggled = Signal(int)      # episode_id

    COVER = 56

    def __init__(self, ep: Episode, *, podcast_title: str | None = None,
                 fallback_cover: str | None = None, in_queue: bool = False,
                 parent=None):
        super().__init__(parent)
        self.episode_id = ep.id
        self.setObjectName("EpisodeRow")

        lay = QHBoxLayout(self)
        lay.setContentsMargins(8, 6, 8, 6)

        self.cover = QLabel()
        self.cover.setFixedSize(self.COVER, self.COVER)
        self.cover.setScaledContents(True)
        lay.addWidget(self.cover)

        mid = QVBoxLayout()
        mid.setSpacing(2)
        title = QLabel(ep.title or "(untitled)")
        title.setObjectName("EpisodeTitle")
        title.setWordWrap(True)
        mid.addWidget(title)

        meta_bits = [fmt_date(ep.pub_date), fmt_duration(ep.duration_secs or ep.total_secs)]
        if podcast_title:
            meta_bits.insert(0, podcast_title)
        meta = QLabel("  ·  ".join(b for b in meta_bits if b))
        meta.setObjectName("EpisodeMeta")
        mid.addWidget(meta)

        if ep.position_secs > 0 and (ep.total_secs or ep.duration_secs):
            total = ep.total_secs or ep.duration_secs or 1
            bar = QProgressBar()
            bar.setObjectName("EpisodeProgress")
            bar.setMaximum(total)
            bar.setValue(min(ep.position_secs, total))
            bar.setTextVisible(False)
            bar.setFixedHeight(4)
            mid.addWidget(bar)

        lay.addLayout(mid, 1)

        self.play_btn = QPushButton("▶")
        self.play_btn.setObjectName("RowPlayButton")
        self.play_btn.setFixedSize(34, 34)
        self.play_btn.setToolTip("Play")
        self.play_btn.clicked.connect(lambda: self.playRequested.emit(self.episode_id))
        lay.addWidget(self.play_btn)

        self.queue_btn = QPushButton("−" if in_queue else "+")
        self.queue_btn.setObjectName("RowQueueButton")
        self.queue_btn.setFixedSize(34, 34)
        self.queue_btn.setToolTip("Remove from queue" if in_queue else "Add to queue")
        self.queue_btn.clicked.connect(lambda: self.queueToggled.emit(self.episode_id))
        lay.addWidget(self.queue_btn)

        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)

        # Cover: memory/disk cache hit is set immediately; otherwise the fetch
        # is async and _on_cover_loaded fills it in.
        self._cover_url = ep.image_url or fallback_cover
        pm = images.loader().get(self._cover_url, self.COVER)
        if pm is not None:
            self.cover.setPixmap(pm)
        elif self._cover_url:
            images.loader().loaded.connect(self._on_cover_loaded)

    def _on_cover_loaded(self, url: str, pm: QPixmap) -> None:
        if url == self._cover_url:
            self.cover.setPixmap(pm)
            try:
                images.loader().loaded.disconnect(self._on_cover_loaded)
            except RuntimeError:
                pass


def resolved_drop_index(here: int, target: int, count: int) -> int:
    """Final index of a row dragged from `here` to insertion point `target`.

    `target` is an insertion point in the list as displayed (0..count), while
    QueueManager.move() inserts into the list with the dragged row already
    taken out. Dragging downwards therefore loses one place to that removal,
    and dragging upwards does not — the off-by-one that makes a drag look
    correct in one direction and land one short in the other.
    """
    index = target - 1 if here < target else target
    return max(0, min(index, count - 1))


class EpisodeListWidget(QListWidget):
    playRequested = Signal(int)
    queueToggled = Signal(int)
    markPlayedRequested = Signal(int)
    markUnplayedRequested = Signal(int)
    reordered = Signal(int, int)  # episode_id, new_index — only when reorderable

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("EpisodeList")
        self.setVerticalScrollMode(QListWidget.ScrollMode.ScrollPerPixel)
        self.setSelectionMode(QListWidget.SelectionMode.NoSelection)
        self.setUniformItemSizes(False)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.customContextMenuRequested.connect(self._context_menu)
        self._episodes: list[Episode] = []
        self._show_podcast = False
        self._rendered = 0
        self._pinfo: dict = {}
        self._queued: set[int] = set()
        self._reorderable = False

    # ------------------------------------------------------------- reordering

    def set_reorderable(self, enabled: bool) -> None:
        """Allow dragging rows to reorder them. Off by default.

        Only meaningful for a list whose order is the user's to choose — the
        queue. Inbox and Continue Listening are orderings the app derives, so
        dragging a row there would promise something the next reload undoes.
        """
        self._reorderable = enabled
        if enabled:
            self.setDragDropMode(QListWidget.DragDropMode.InternalMove)
            self.setDefaultDropAction(Qt.DropAction.MoveAction)
            # startDrag() builds its payload out of the selection, so a list
            # that cannot select anything can never begin a drag.
            self.setSelectionMode(QListWidget.SelectionMode.SingleSelection)
        else:
            self.setDragDropMode(QListWidget.DragDropMode.NoDragDrop)
            self.setSelectionMode(QListWidget.SelectionMode.NoSelection)

    def _episode_rows(self) -> list[int]:
        """Row numbers holding an episode — everything but the "Show more" row."""
        return [i for i in range(self.count()) if self.item(i).data(32) != "more"]

    def _episode_id_at(self, row: int) -> int | None:
        item = self.item(row)
        if item is None or item.data(32) == "more":
            return None
        return getattr(self.itemWidget(item), "episode_id", None)

    def _drop_position(self, event) -> int:
        """Where the drop lands, counted in episodes, as an insertion point."""
        rows = self._episode_rows()
        item = self.itemAt(event.position().toPoint())
        if item is None:
            return len(rows)
        row = self.row(item)
        if row not in rows:  # dropped onto "Show more"
            return len(rows)
        at = rows.index(row)
        below = (
            self.dropIndicatorPosition()
            == QListWidget.DropIndicatorPosition.BelowItem
        )
        return at + 1 if below else at

    def dropEvent(self, event) -> None:  # noqa: N802 (Qt naming)
        """Report the move; never perform it.

        Rows are widgets installed with setItemWidget, and Qt's InternalMove
        takes the item out and puts it back *without* its widget — the row
        would come back blank. Nothing here writes to the queue either: the
        daemon owns queue writes, and the reload that follows queueChanged
        redraws this list from the order that actually won.
        """
        if not self._reorderable:
            return super().dropEvent(event)

        rows = self._episode_rows()
        source = self.currentRow()
        episode_id = self._episode_id_at(source)
        target = self._drop_position(event)

        event.setDropAction(Qt.DropAction.IgnoreAction)
        event.accept()

        if episode_id is None or source not in rows:
            return
        here = rows.index(source)
        # Same index the queue page reports, which is what makes a drag here
        # and a drag there mean the same thing.
        new_index = resolved_drop_index(here, target, len(rows))
        if new_index != here:
            self.reordered.emit(episode_id, new_index)

    def _context_menu(self, pos) -> None:
        item = self.itemAt(pos)
        if item is None or item.data(32) == "more":
            return
        row = self.itemWidget(item)
        eid = getattr(row, "episode_id", None)
        if eid is None:
            return
        ep = next((e for e in self._episodes if e.id == eid), None)
        in_queue = eid in repo.queue_episode_ids()  # fresh — one small query

        menu = QMenu(self)
        menu.addAction("Play", lambda: self.playRequested.emit(eid))
        menu.addAction("Remove from queue" if in_queue else "Add to queue",
                       lambda: self.queueToggled.emit(eid))
        menu.addSeparator()
        if ep is not None and ep.state == "played":
            menu.addAction("Mark unplayed", lambda: self.markUnplayedRequested.emit(eid))
        else:
            menu.addAction("Mark played", lambda: self.markPlayedRequested.emit(eid))
            if ep is not None and ep.position_secs > 0:
                menu.addAction("Mark unplayed (reset progress)",
                               lambda: self.markUnplayedRequested.emit(eid))
        menu.exec(self.mapToGlobal(pos))

    def set_episodes(self, episodes: list[Episode], show_podcast: bool = False) -> None:
        self.clear()
        self._episodes = episodes
        self._show_podcast = show_podcast
        self._rendered = 0
        # ONE query each for context shared by every row
        self._pinfo = repo.podcast_display_info()
        self._queued = repo.queue_episode_ids()
        self._render_page()

    def _render_page(self) -> None:
        # remove a trailing "Show more" row if present
        if self.count() and self.item(self.count() - 1).data(32) == "more":
            self.takeItem(self.count() - 1)

        batch = self._episodes[self._rendered:self._rendered + PAGE_SIZE]
        self.setUpdatesEnabled(False)
        try:
            for ep in batch:
                title, image = self._pinfo.get(ep.podcast_id, (None, None))
                row = EpisodeRow(
                    ep,
                    podcast_title=title if self._show_podcast else None,
                    fallback_cover=image,
                    in_queue=ep.id in self._queued,
                )
                row.playRequested.connect(self.playRequested)
                row.queueToggled.connect(self.queueToggled)
                item = QListWidgetItem()
                item.setSizeHint(QSize(0, max(row.sizeHint().height(), 72)))
                self.addItem(item)
                self.setItemWidget(item, row)
            self._rendered += len(batch)

            remaining = len(self._episodes) - self._rendered
            if remaining > 0:
                more_item = QListWidgetItem()
                more_item.setData(32, "more")
                more_btn = QPushButton(f"Show {min(remaining, PAGE_SIZE)} more "
                                       f"({remaining} remaining)")
                more_btn.setObjectName("ShowMoreButton")
                more_btn.clicked.connect(self._render_page)
                more_item.setSizeHint(QSize(0, 44))
                self.addItem(more_item)
                self.setItemWidget(more_item, more_btn)
        finally:
            self.setUpdatesEnabled(True)
