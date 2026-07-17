"""Inbox page: recent new/inbox episodes across all subscriptions."""

from __future__ import annotations

from PySide6.QtCore import Signal
from PySide6.QtWidgets import QHBoxLayout, QLabel, QVBoxLayout, QWidget

from ..db import repo
from .episode_list import EpisodeListWidget


class InboxPage(QWidget):
    playRequested = Signal(int)
    queueToggled = Signal(int)
    markPlayedRequested = Signal(int)
    markUnplayedRequested = Signal(int)

    def __init__(self, parent=None):
        super().__init__(parent)
        lay = QVBoxLayout(self)
        header = QHBoxLayout()
        title = QLabel("Inbox")
        title.setObjectName("PageTitle")
        header.addWidget(title)
        header.addStretch(1)
        lay.addLayout(header)

        self.list = EpisodeListWidget()
        self.list.playRequested.connect(self.playRequested)
        self.list.queueToggled.connect(self.queueToggled)
        self.list.markPlayedRequested.connect(self.markPlayedRequested)
        self.list.markUnplayedRequested.connect(self.markUnplayedRequested)
        lay.addWidget(self.list, 1)

    def reload(self) -> None:
        self.list.set_episodes(repo.inbox_episodes(), show_podcast=True)
