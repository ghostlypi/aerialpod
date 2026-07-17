"""Lightweight row wrappers (plain dataclasses, no ORM)."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, fields


@dataclass(slots=True)
class Podcast:
    id: int
    feed_url: str
    title: str | None
    description: str | None
    image_url: str | None
    website: str | None
    subscribed: int
    sync_state: str
    etag: str | None
    http_last_modified: str | None
    last_refresh: int | None
    added_at: int | None


@dataclass(slots=True)
class Episode:
    id: int
    podcast_id: int
    guid: str | None
    media_url: str
    title: str | None
    description: str | None
    pub_date: int | None
    duration_secs: int | None
    mime: str | None
    file_size: int | None
    image_url: str | None
    state: str
    position_secs: int
    total_secs: int
    position_updated_at: int
    downloaded_path: str | None
    download_state: str
    keep_download: int


@dataclass(slots=True)
class QueueItem:
    episode_id: int
    position: int
    origin: str
    pinned: int
    added_at: int


def from_row(cls, row: sqlite3.Row):
    names = {f.name for f in fields(cls)}
    return cls(**{k: row[k] for k in row.keys() if k in names})
