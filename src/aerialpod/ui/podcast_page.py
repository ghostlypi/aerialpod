"""Single-podcast page: header with cover/description + episode list."""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from ..db import repo
from . import images
from .episode_list import EpisodeListWidget

COVER = 120


class PodcastPage(QWidget):
    backRequested = Signal()
    playRequested = Signal(int)
    queueToggled = Signal(int)
    markPlayedRequested = Signal(int)
    markUnplayedRequested = Signal(int)
    refreshRequested = Signal(int)      # podcast_id
    unsubscribeRequested = Signal(int)  # podcast_id
    settingsRequested = Signal(int)     # podcast_id

    def __init__(self, parent=None):
        super().__init__(parent)
        self.podcast_id: int | None = None

        lay = QVBoxLayout(self)

        top = QHBoxLayout()
        back = QPushButton("← Back")
        back.setObjectName("BackButton")
        back.clicked.connect(self.backRequested)
        top.addWidget(back)
        top.addStretch(1)
        self.refresh_btn = QPushButton("Refresh")
        self.refresh_btn.clicked.connect(
            lambda: self.podcast_id and self.refreshRequested.emit(self.podcast_id)
        )
        top.addWidget(self.refresh_btn)
        self.settings_btn = QPushButton("Settings…")
        self.settings_btn.clicked.connect(
            lambda: self.podcast_id and self.settingsRequested.emit(self.podcast_id)
        )
        top.addWidget(self.settings_btn)
        self.unsub_btn = QPushButton("Unsubscribe")
        self.unsub_btn.setObjectName("DangerButton")
        self.unsub_btn.clicked.connect(
            lambda: self.podcast_id and self.unsubscribeRequested.emit(self.podcast_id)
        )
        top.addWidget(self.unsub_btn)
        lay.addLayout(top)

        header = QHBoxLayout()
        self.cover = QLabel()
        self.cover.setFixedSize(COVER, COVER)
        self.cover.setScaledContents(True)
        header.addWidget(self.cover, alignment=Qt.AlignmentFlag.AlignTop)

        info = QVBoxLayout()
        self.title = QLabel()
        self.title.setObjectName("PageTitle")
        self.title.setWordWrap(True)
        info.addWidget(self.title)
        self.desc = QLabel()
        self.desc.setObjectName("PodcastDescription")
        self.desc.setWordWrap(True)
        self.desc.setTextFormat(Qt.TextFormat.RichText)
        self.desc.setMaximumHeight(80)
        info.addWidget(self.desc)
        info.addStretch(1)
        header.addLayout(info, 1)
        lay.addLayout(header)

        self.episodes = EpisodeListWidget()
        self.episodes.playRequested.connect(self.playRequested)
        self.episodes.queueToggled.connect(self.queueToggled)
        self.episodes.markPlayedRequested.connect(self.markPlayedRequested)
        self.episodes.markUnplayedRequested.connect(self.markUnplayedRequested)
        lay.addWidget(self.episodes, 1)

        images.loader().loaded.connect(self._on_cover_loaded)
        self._cover_url: str | None = None

    def show_podcast(self, podcast_id: int) -> None:
        self.podcast_id = podcast_id
        self.reload()

    def reload(self) -> None:
        if self.podcast_id is None:
            return
        p = repo.podcast_by_id(self.podcast_id)
        if p is None:
            return
        self.title.setText(repo.display_title(p))
        # Feed descriptions can be huge HTML blobs — cap what the label parses.
        desc = (p.description or "").strip()
        if len(desc) > 600:
            desc = desc[:600] + "…"
        self.desc.setText(desc)
        self._cover_url = p.image_url
        pm = images.loader().get(p.image_url, COVER)
        self.cover.setPixmap(pm if pm is not None else QPixmap())
        self.episodes.set_episodes(repo.episodes_for_podcast(p.id))

    def _on_cover_loaded(self, url: str, pm: QPixmap) -> None:
        if url == self._cover_url:
            self.cover.setPixmap(pm)
