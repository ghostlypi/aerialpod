"""Feed refresh scheduling: startup + hourly, bounded concurrency,
debounced completion signal so QueueManager can reconcile once per burst.
"""

from __future__ import annotations

import logging
import os

from PySide6.QtCore import QObject, QTimer, Signal

from ..db import repo
from ..workers import run_in_pool
from . import fetcher

log = logging.getLogger(__name__)

# Network+parse bound; scale with the machine (8+ logical cores expected).
MAX_CONCURRENT = min(8, max(2, (os.cpu_count() or 4) - 2))
REFRESH_INTERVAL_MS = 60 * 60 * 1000  # hourly


class Refresher(QObject):
    refreshStarted = Signal()
    podcastRefreshed = Signal(int)      # podcast_id
    refreshFinished = Signal(int)       # total new episodes in this run
    refreshError = Signal(int, str)     # podcast_id, message

    def __init__(self, parent: QObject | None = None):
        super().__init__(parent)
        self._pending: list[int] = []
        self._active = 0
        self._new_total = 0
        self._timer = QTimer(self)
        self._timer.setInterval(REFRESH_INTERVAL_MS)
        self._timer.timeout.connect(self.refresh_all)
        self._timer.start()

    @property
    def running(self) -> bool:
        return self._active > 0 or bool(self._pending)

    def refresh_all(self) -> None:
        if self.running:
            return
        ids = [p.id for p in repo.all_podcasts()]
        if not ids:
            return
        self._pending = ids
        self._new_total = 0
        self.refreshStarted.emit()
        for _ in range(min(MAX_CONCURRENT, len(self._pending))):
            self._start_next()

    def refresh_one(self, podcast_id: int) -> None:
        if podcast_id in self._pending:
            return
        self._pending.append(podcast_id)
        if self._active < MAX_CONCURRENT:
            self._start_next()

    def _start_next(self) -> None:
        if not self._pending:
            if self._active == 0:
                self.refreshFinished.emit(self._new_total)
            return
        pid = self._pending.pop(0)
        self._active += 1
        run_in_pool(
            lambda pid=pid: fetcher.fetch_and_store(pid),
            on_done=lambda n, pid=pid: self._on_done(pid, n),
            on_error=lambda e, pid=pid: self._on_error(pid, e),
        )

    def _on_done(self, pid: int, new_count: int) -> None:
        self._active -= 1
        self._new_total += new_count
        self.podcastRefreshed.emit(pid)
        self._start_next()

    def _on_error(self, pid: int, exc: Exception) -> None:
        self._active -= 1
        log.warning("refresh failed for podcast %d: %s", pid, exc)
        self.refreshError.emit(pid, str(exc))
        self._start_next()
