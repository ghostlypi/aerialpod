"""Async cover-art loader.

All heavy work happens in the thread pool: network fetch, disk read, image
decode, and smooth scaling (QImage is safe off the main thread). The main
thread only converts the finished QImage to a QPixmap (must happen there)
and caches it. UI code calls get() and, on a miss, listens for loaded().
"""

from __future__ import annotations

import hashlib
import logging

import requests
from PySide6.QtCore import QObject, Qt, Signal
from PySide6.QtGui import QImage, QPixmap

from ..config import image_cache_dir
from ..workers import run_in_pool

log = logging.getLogger(__name__)

_memory: dict[str, QPixmap] = {}


def _fetch_and_decode(url: str, path, size: int) -> QImage:
    """Worker thread: ensure the bytes are on disk, decode, scale."""
    if not path.exists():
        resp = requests.get(url, timeout=20)
        resp.raise_for_status()
        path.write_bytes(resp.content)
    img = QImage(str(path))
    if img.isNull():
        path.unlink(missing_ok=True)  # corrupt cache entry — refetch next time
        raise ValueError(f"undecodable image: {url}")
    return img.scaled(
        size, size,
        Qt.AspectRatioMode.KeepAspectRatio,
        Qt.TransformationMode.SmoothTransformation,
    )


class ImageLoader(QObject):
    """loaded(url, pixmap) fires on the main thread when a cover is ready."""

    loaded = Signal(str, QPixmap)

    def __init__(self, parent: QObject | None = None):
        super().__init__(parent)
        self._inflight: set[str] = set()

    def get(self, url: str | None, size: int = 160) -> QPixmap | None:
        """Return the cached pixmap, or start an async load and return None."""
        if not url:
            return None
        key = f"{url}@{size}"
        cached = _memory.get(key)
        if cached is not None:
            return cached
        if key in self._inflight:
            return None
        self._inflight.add(key)

        path = image_cache_dir() / hashlib.sha1(url.encode()).hexdigest()
        run_in_pool(
            lambda url=url, path=path, size=size: _fetch_and_decode(url, path, size),
            on_done=lambda img, key=key, url=url: self._on_decoded(key, url, img),
            on_error=lambda exc, key=key, url=url: self._on_error(key, url, exc),
        )
        return None

    def _on_decoded(self, key: str, url: str, img: QImage) -> None:
        self._inflight.discard(key)
        pm = QPixmap.fromImage(img)  # main thread only
        _memory[key] = pm
        self.loaded.emit(url, pm)

    def _on_error(self, key: str, url: str, exc: Exception) -> None:
        self._inflight.discard(key)
        log.debug("cover load failed %s: %s", url, exc)


# One shared instance, created lazily after QApplication exists.
_loader: ImageLoader | None = None


def loader() -> ImageLoader:
    global _loader
    if _loader is None:
        _loader = ImageLoader()
    return _loader
