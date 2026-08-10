"""Home page: configurable sections (Queue, Continue Listening, Inbox,
Subscriptions), reorderable/hideable — order stored in app_state.home_sections.
"""

from __future__ import annotations

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QHBoxLayout,
    QLabel,
    QListView,
    QListWidget,
    QListWidgetItem,
    QPushButton,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from ..db import repo
from . import images
from .episode_list import EpisodeListWidget

SECTION_TITLES = {
    "queue": "Queue",
    "continue": "Continue Listening",
    "inbox": "Inbox",
    "subscriptions": "Subscriptions",
}
PREVIEW_LIMIT = 5


class _Section(QWidget):
    moreClicked = Signal(str)

    def __init__(self, key: str, parent=None):
        super().__init__(parent)
        self.key = key
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 8)
        header = QHBoxLayout()
        title = QLabel(SECTION_TITLES[key])
        title.setObjectName("SectionHeader")
        header.addWidget(title)
        header.addStretch(1)
        more = QPushButton("See all →")
        more.setObjectName("SectionMore")
        more.setCursor(Qt.CursorShape.PointingHandCursor)
        more.clicked.connect(lambda: self.moreClicked.emit(self.key))
        header.addWidget(more)
        lay.addLayout(header)
        self.body = QVBoxLayout()
        lay.addLayout(self.body)


class HomePage(QWidget):
    playRequested = Signal(int)
    queueToggled = Signal(int)
    markPlayedRequested = Signal(int)
    markUnplayedRequested = Signal(int)
    navigateRequested = Signal(str)   # nav key
    podcastOpened = Signal(int)

    def __init__(self, queue, client, parent=None):
        super().__init__(parent)
        self.queue = queue
        self.client = client

        outer = QVBoxLayout(self)
        header = QHBoxLayout()
        title = QLabel("Home")
        title.setObjectName("PageTitle")
        header.addWidget(title)
        header.addStretch(1)
        customize = QPushButton("Customize…")
        customize.clicked.connect(self._customize)
        header.addWidget(customize)
        outer.addLayout(header)

        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        self.container = QWidget()
        self.sections_layout = QVBoxLayout(self.container)
        self.sections_layout.addStretch(1)
        self.scroll.setWidget(self.container)
        outer.addWidget(self.scroll, 1)

        images.loader().loaded.connect(lambda *_: None)

    # ------------------------------------------------------------ build

    def reload(self) -> None:
        # clear old sections
        while self.sections_layout.count() > 1:
            item = self.sections_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        order = repo.get_state("home_sections")
        for i, key in enumerate(k for k in order if k in SECTION_TITLES):
            section = _Section(key)
            section.moreClicked.connect(self.navigateRequested)
            self._fill(section)
            self.sections_layout.insertWidget(i, section)

    def _fill(self, section: _Section) -> None:
        key = section.key
        if key == "queue":
            eps = self.queue.episodes()[:PREVIEW_LIMIT]
            self._episode_list(section, eps, empty="Queue is empty — sync or add episodes.")
        elif key == "continue":
            eps = repo.in_progress_episodes(PREVIEW_LIMIT)
            self._episode_list(section, eps, empty="Nothing in progress.")
        elif key == "inbox":
            eps = repo.inbox_episodes(PREVIEW_LIMIT)
            self._episode_list(section, eps, empty="No new episodes.")
        elif key == "subscriptions":
            grid = QListWidget()
            grid.setObjectName("SubscriptionsGrid")
            grid.setViewMode(QListView.ViewMode.IconMode)
            grid.setIconSize(QSize(96, 96))
            grid.setGridSize(QSize(116, 140))
            grid.setFixedHeight(150)
            grid.setResizeMode(QListView.ResizeMode.Adjust)
            grid.setMovement(QListView.Movement.Static)
            grid.setHorizontalScrollMode(QListWidget.ScrollMode.ScrollPerPixel)
            grid.setFlow(QListView.Flow.TopToBottom)
            grid.setWrapping(False)
            pinfo = repo.podcast_display_info()
            for p in repo.all_podcasts():
                title, image_url = pinfo.get(p.id, (p.feed_url, None))
                item = QListWidgetItem(title[:22])
                item.setData(Qt.ItemDataRole.UserRole, p.id)
                pm = images.loader().get(image_url, 96)
                if pm is not None:
                    item.setIcon(QIcon(pm))
                grid.addItem(item)
            grid.itemClicked.connect(
                lambda it: self.podcastOpened.emit(it.data(Qt.ItemDataRole.UserRole))
            )
            section.body.addWidget(grid)

    def _episode_list(self, section: _Section, eps, empty: str) -> None:
        if not eps:
            hint = QLabel(empty)
            hint.setObjectName("QueueHint")
            section.body.addWidget(hint)
            return
        lst = EpisodeListWidget()
        lst.set_episodes(eps, show_podcast=True)
        lst.playRequested.connect(self.playRequested)
        lst.queueToggled.connect(self.queueToggled)
        lst.markPlayedRequested.connect(self.markPlayedRequested)
        lst.markUnplayedRequested.connect(self.markUnplayedRequested)
        lst.setFixedHeight(76 * len(eps) + 8)
        lst.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        section.body.addWidget(lst)

    # ------------------------------------------------------------ customize

    def _customize(self) -> None:
        dlg = SectionsDialog(self)
        if dlg.exec() == QDialog.DialogCode.Accepted:
            self.client.set_state("home_sections", dlg.result_order())
            self.reload()


class SectionsDialog(QDialog):
    """Drag to reorder; check to show. Hidden sections drop out of the list."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Customize home")
        lay = QVBoxLayout(self)
        lay.addWidget(QLabel("Drag to reorder; uncheck to hide."))

        self.list = QListWidget()
        self.list.setDragDropMode(QListWidget.DragDropMode.InternalMove)
        current = [k for k in repo.get_state("home_sections") if k in SECTION_TITLES]
        for key in current + [k for k in SECTION_TITLES if k not in current]:
            item = QListWidgetItem(SECTION_TITLES[key])
            item.setData(Qt.ItemDataRole.UserRole, key)
            item.setFlags(item.flags() | Qt.ItemFlag.ItemIsUserCheckable)
            item.setCheckState(
                Qt.CheckState.Checked if key in current else Qt.CheckState.Unchecked
            )
            self.list.addItem(item)
        lay.addWidget(self.list)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        lay.addWidget(buttons)

    def result_order(self) -> list[str]:
        return [
            self.list.item(i).data(Qt.ItemDataRole.UserRole)
            for i in range(self.list.count())
            if self.list.item(i).checkState() == Qt.CheckState.Checked
        ]
