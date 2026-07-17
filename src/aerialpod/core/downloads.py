"""DownloadManager: keep the first N queue items downloaded, evict the rest.

Policy runs on every queueChanged + download completion. Downloads stream via
requests in the thread pool with .part + Range resume.
"""

from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

import requests
from PySide6.QtCore import QObject, Signal

from ..config import media_dir
from ..db import repo
from ..workers import run_in_pool

log = logging.getLogger(__name__)

CHUNK = 256 * 1024


def _safe_name(text: str, limit: int = 80) -> str:
    return re.sub(r"[^\w.-]+", "_", text or "untitled")[:limit].strip("_")


def _target_path(ep) -> Path:
    p = repo.podcast_by_id(ep.podcast_id)
    pod_dir = media_dir() / _safe_name(p.title if p else str(ep.podcast_id))
    pod_dir.mkdir(parents=True, exist_ok=True)
    ext = Path(urlparse(ep.media_url).path).suffix or ".mp3"
    return pod_dir / f"{_safe_name(ep.title)}-{ep.id}{ext}"


def _download_file(url: str, dest: Path) -> None:
    """Blocking download with .part + Range resume."""
    part = dest.with_suffix(dest.suffix + ".part")
    headers = {"User-Agent": "AerialPod/0.1"}
    mode = "wb"
    if part.exists():
        headers["Range"] = f"bytes={part.stat().st_size}-"
        mode = "ab"
    with requests.get(url, headers=headers, stream=True, timeout=30,
                      allow_redirects=True) as resp:
        if resp.status_code == 416:  # range past end — file already complete
            pass
        elif resp.status_code == 200 and mode == "ab":
            # server ignored Range — restart from scratch
            mode = "wb"
            resp.raise_for_status()
            with part.open(mode) as f:
                for chunk in resp.iter_content(CHUNK):
                    f.write(chunk)
        else:
            resp.raise_for_status()
            with part.open(mode) as f:
                for chunk in resp.iter_content(CHUNK):
                    f.write(chunk)
    part.rename(dest)


class DownloadManager(QObject):
    downloadStarted = Signal(int)     # episode_id
    downloadFinished = Signal(int)    # episode_id
    downloadFailed = Signal(int, str)

    def __init__(self, queue, parent: QObject | None = None):
        super().__init__(parent)
        self.queue = queue
        self._active: set[int] = set()
        queue.queueChanged.connect(self.apply_policy)

    # ------------------------------------------------------------ policy

    def apply_policy(self) -> None:
        n = int(repo.get_state("download_ahead_n"))
        queue_eps = self.queue.episodes()
        want = {ep.id for ep in queue_eps[:n]} if n > 0 else set()

        # evict: policy-downloaded episodes no longer wanted
        for ep in self._policy_downloaded():
            if ep.id not in want and not ep.keep_download:
                self._evict(ep)

        # fetch: wanted episodes not yet downloaded/downloading
        for ep in queue_eps[:n]:
            if ep.download_state == "none" and ep.id not in self._active:
                self._start(ep)

    def _policy_downloaded(self):
        from .. import db
        from ..db.models import Episode, from_row

        rows = db.connection().execute(
            "SELECT * FROM episodes WHERE download_state IN ('done','downloading')"
        )
        return [from_row(Episode, r) for r in rows]

    # ------------------------------------------------------------ transfer

    def _start(self, ep) -> None:
        dest = _target_path(ep)
        self._active.add(ep.id)
        repo.update_episode(ep.id, download_state="downloading")
        self.downloadStarted.emit(ep.id)
        log.info("downloading %s", ep.title)
        run_in_pool(
            lambda url=ep.media_url, d=dest: _download_file(url, d),
            on_done=lambda _r, eid=ep.id, d=dest: self._on_done(eid, d),
            on_error=lambda exc, eid=ep.id: self._on_error(eid, exc),
        )

    def _on_done(self, episode_id: int, dest: Path) -> None:
        self._active.discard(episode_id)
        repo.update_episode(episode_id, download_state="done", downloaded_path=str(dest))
        ep = repo.episode_by_id(episode_id)
        p = repo.podcast_by_id(ep.podcast_id) if ep else None
        if ep and p:
            repo.enqueue_action(
                p.feed_url, ep.media_url, "download",
                datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"),
            )
        log.info("downloaded %s", dest.name)
        self.downloadFinished.emit(episode_id)

    def _on_error(self, episode_id: int, exc: Exception) -> None:
        self._active.discard(episode_id)
        repo.update_episode(episode_id, download_state="none")
        log.warning("download failed for episode %d: %s", episode_id, exc)
        self.downloadFailed.emit(episode_id, str(exc))

    def _evict(self, ep) -> None:
        if ep.id in self._active:
            return  # let the transfer finish; next policy pass evicts
        if ep.downloaded_path:
            Path(ep.downloaded_path).unlink(missing_ok=True)
            part = Path(ep.downloaded_path + ".part")
            part.unlink(missing_ok=True)
        repo.update_episode(ep.id, download_state="none", downloaded_path=None)
        log.info("evicted download for %s", ep.title)
