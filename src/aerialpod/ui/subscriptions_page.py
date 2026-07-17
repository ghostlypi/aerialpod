"""Subscriptions page: cover grid + add-podcast flow."""

from __future__ import annotations

import logging

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import (
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QListView,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from ..db import repo
from . import images

log = logging.getLogger(__name__)

COVER = 140


class SubscriptionsPage(QWidget):
    podcastOpened = Signal(int)           # podcast_id
    subscribeRequested = Signal(str)      # feed_url

    def __init__(self, parent=None):
        super().__init__(parent)
        lay = QVBoxLayout(self)

        header = QHBoxLayout()
        title = QLabel("Subscriptions")
        title.setObjectName("PageTitle")
        header.addWidget(title)
        header.addStretch(1)
        add_btn = QPushButton("Add podcast…")
        add_btn.setObjectName("PrimaryButton")
        add_btn.clicked.connect(self._on_add)
        header.addWidget(add_btn)
        lay.addLayout(header)

        self.grid = QListWidget()
        self.grid.setObjectName("SubscriptionsGrid")
        self.grid.setViewMode(QListView.ViewMode.IconMode)
        self.grid.setIconSize(QSize(COVER, COVER))
        self.grid.setGridSize(QSize(COVER + 24, COVER + 52))
        self.grid.setResizeMode(QListView.ResizeMode.Adjust)
        self.grid.setMovement(QListView.Movement.Static)
        self.grid.setWordWrap(True)
        self.grid.setUniformItemSizes(True)
        self.grid.itemActivated.connect(self._on_open)
        self.grid.itemClicked.connect(self._on_open)
        lay.addWidget(self.grid, 1)

        # Targeted icon fill-in — never rebuild the whole grid per image.
        self._items_by_url: dict[str, QListWidgetItem] = {}
        images.loader().loaded.connect(self._on_cover_loaded)

    def reload(self) -> None:
        self.grid.clear()
        self._items_by_url.clear()
        pinfo = repo.podcast_display_info()  # one query, no per-podcast lookups
        self.grid.setUpdatesEnabled(False)
        try:
            for p in repo.all_podcasts():
                title, image_url = pinfo.get(p.id, (p.feed_url, None))
                item = QListWidgetItem(title)
                item.setData(Qt.ItemDataRole.UserRole, p.id)
                item.setTextAlignment(
                    Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop)
                pm = images.loader().get(image_url, COVER)
                if pm is not None:
                    item.setIcon(QIcon(pm))
                elif image_url:
                    self._items_by_url[image_url] = item
                self.grid.addItem(item)
        finally:
            self.grid.setUpdatesEnabled(True)

    def _on_cover_loaded(self, url: str, pm) -> None:
        item = self._items_by_url.pop(url, None)
        if item is not None:
            item.setIcon(QIcon(pm))

    def _on_open(self, item: QListWidgetItem) -> None:
        self.podcastOpened.emit(item.data(Qt.ItemDataRole.UserRole))

    def _on_add(self) -> None:
        url, ok = QInputDialog.getText(self, "Add podcast", "RSS feed URL:")
        url = (url or "").strip()
        if not ok or not url:
            return
        if not url.startswith(("http://", "https://")):
            QMessageBox.warning(self, "Add podcast", "Please enter an http(s) feed URL.")
            return
        self.subscribeRequested.emit(url)
