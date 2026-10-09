"""Handing a peer the exact audio file this device found ads in.

Dynamic ad insertion gives every download its own ads, so a phone fetching an
episode from the podcast's server gets a copy the spans do not quite fit
(ads/anchors.py carries them across as best it can). Fetching the analysed file
itself from the machine that analysed it removes the guesswork.

It rides its own connection, not the sync link: a phone dials again with
"purpose": "file" in its ident, and that link carries one transfer and nothing
else — no snapshot, and not counted as a peer. A hundred-odd megabytes queued on
the sync link would hold up every position push behind it.

    phone → file_get   {"feed", "guid", "media", "offset"}
    here  → file_head  {"bytes", "sha256"}      or file_err {"reason"}
            file_chunk {"offset", "data": base64} …
            file_end   {}

Chunks are written as the socket drains, never all at once: QTcpSocket buffers
whatever it is given, and a whole episode would sit in this process's memory.
"""

from __future__ import annotations

import base64
import logging
from pathlib import Path

from PySide6.QtCore import QObject

from ..db import repo
from . import state

log = logging.getLogger(__name__)

CHUNK = 512 * 1024
HIGH_WATER = 4 * 1024 * 1024   # bytes waiting in the socket before we stop adding


def servable(record: dict) -> tuple[Path, dict] | str:
    """The analysed file a peer is asking for, or why it can't have it."""
    episode = state.resolve_episode(record)
    if episode is None:
        return "unknown episode"
    row = repo.ad_segments(episode.id)
    if not row or not row["audio"] or row["updated_by"] != repo.lan_device_id():
        return "no analysed copy on this device"
    path = Path(episode.downloaded_path or "")
    if (episode.download_state != "done" or not path.is_file()
            or path.stat().st_size != row["audio"]["bytes"]):
        return "the analysed copy is no longer on this device"
    return path, row["audio"]


class FileSender(QObject):
    """Streams one file down one link, paced by the socket draining."""

    def __init__(self, link, path: Path, offset: int, parent: QObject | None = None):
        super().__init__(parent)
        self.link = link
        self.path = path
        self._file = path.open("rb")
        self._file.seek(max(0, offset))
        self._offset = self._file.tell()
        self._done = False
        link.socket.bytesWritten.connect(self._pump)
        link.closed.connect(self._stop)

    def start(self, audio: dict) -> None:
        log.info("sending %s to %s from byte %d", self.path.name, self.link.caption,
                 self._offset)
        self.link.send({"type": "file_head", "bytes": audio["bytes"],
                        "sha256": audio["sha256"]})
        self._pump()

    def _pump(self, *_args) -> None:
        while not self._done and self.link.socket.bytesToWrite() < HIGH_WATER:
            data = self._file.read(CHUNK)
            if not data:
                self.link.send({"type": "file_end"})
                self._stop()
                return
            self.link.send({"type": "file_chunk", "offset": self._offset,
                            "data": base64.b64encode(data).decode("ascii")})
            self._offset += len(data)

    def _stop(self, *_args) -> None:
        if self._done:
            return
        self._done = True
        self._file.close()
        self.deleteLater()
