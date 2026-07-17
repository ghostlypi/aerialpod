from __future__ import annotations

import time

import pytest
from PySide6.QtCore import QCoreApplication

from aerialpod import db


@pytest.fixture(scope="session")
def qapp():
    app = QCoreApplication.instance() or QCoreApplication([])
    yield app


@pytest.fixture()
def fresh_db(tmp_path, qapp):
    """A fresh migrated database for each test."""
    db.close_thread_connection()
    db.init(tmp_path / "test.db")
    yield db
    db.close_thread_connection()
    db._db_path = None


@pytest.fixture()
def podcast(fresh_db):
    """One subscribed podcast, sync_state clean."""
    from aerialpod.db import repo

    pid = repo.upsert_podcast("https://example.com/feed.xml", sync_state="clean")
    repo.update_podcast_meta(pid, title="Test Podcast")
    return pid


def make_episode(pid: int, n: int, *, state="new", position=0, total=0,
                 updated_at=0, pub_date=None) -> int:
    conn = db.connection()
    cur = conn.execute(
        "INSERT INTO episodes(podcast_id, guid, media_url, title, pub_date, "
        "state, position_secs, total_secs, position_updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?)",
        (pid, f"guid-{pid}-{n}", f"https://cdn.example.com/ep{n:03d}.mp3",
         f"Episode {n}", pub_date or (1700000000 + n * 86400),
         state, position, total, updated_at),
    )
    conn.commit()
    return cur.lastrowid


@pytest.fixture()
def episodes(podcast):
    """Five fresh episodes, ep1 oldest … ep5 newest."""
    return [make_episode(podcast, n) for n in range(1, 6)]


def now() -> int:
    return int(time.time())
