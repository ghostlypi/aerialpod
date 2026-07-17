"""SyncService._apply_action semantics (last-writer-wins, finished detection,
unmatched logging) — pure DB, no network.
"""

from __future__ import annotations

import pytest

from aerialpod.db import repo
from aerialpod.gpodder.sync import SyncService

from .conftest import make_episode

FEED = "https://example.com/feed.xml"


@pytest.fixture()
def svc(fresh_db):
    return SyncService(dry_run=True)


def play_action(url: str, position: int, total: int = 3000,
                ts: str = "2026-07-16T10:00:00") -> dict:
    return {"podcast": FEED, "episode": url, "action": "play",
            "timestamp": ts, "started": position, "position": position,
            "total": total}


def test_play_updates_position(svc, podcast):
    eid = make_episode(podcast, 1)
    assert svc._apply_action(play_action("https://cdn.example.com/ep001.mp3", 600))
    ep = repo.episode_by_id(eid)
    assert ep.position_secs == 600
    assert ep.total_secs == 3000
    assert ep.state != "played"


def test_play_near_total_marks_played(svc, podcast):
    eid = make_episode(podcast, 1)
    svc._apply_action(play_action("https://cdn.example.com/ep001.mp3", 2985))
    assert repo.episode_by_id(eid).state == "played"


def test_last_writer_wins_older_action_ignored(svc, podcast):
    eid = make_episode(podcast, 1, position=900, updated_at=1789000000)
    # action timestamp 2026-07-16T10:00:00 ≈ 1784282400 < 1789000000
    svc._apply_action(play_action("https://cdn.example.com/ep001.mp3", 100))
    assert repo.episode_by_id(eid).position_secs == 900


def test_newer_action_wins(svc, podcast):
    eid = make_episode(podcast, 1, position=100, updated_at=100)
    svc._apply_action(play_action("https://cdn.example.com/ep001.mp3", 1200))
    assert repo.episode_by_id(eid).position_secs == 1200


def test_delete_marks_played(svc, podcast):
    eid = make_episode(podcast, 1)
    svc._apply_action({"podcast": FEED, "episode": "https://cdn.example.com/ep001.mp3",
                       "action": "delete", "timestamp": "2026-07-16T10:00:00"})
    assert repo.episode_by_id(eid).state == "played"


def test_unmatched_known_podcast_logged(svc, podcast):
    make_episode(podcast, 1)
    ok = svc._apply_action(play_action("https://cdn.example.com/who-is-this.mp3", 5))
    assert not ok
    assert repo.unmatched_count() == 1


def test_unknown_podcast_not_logged(svc, podcast):
    ok = svc._apply_action({"podcast": "https://elsewhere.com/feed", "action": "play",
                            "episode": "https://elsewhere.com/e.mp3",
                            "timestamp": "2026-07-16T10:00:00", "position": 5})
    assert ok
    assert repo.unmatched_count() == 0


def test_zero_total_action_does_not_finish(svc, podcast):
    """AntennaPod total=0 guard."""
    eid = make_episode(podcast, 1)
    svc._apply_action(play_action("https://cdn.example.com/ep001.mp3", 2980, total=0))
    ep = repo.episode_by_id(eid)
    assert ep.position_secs == 2980
    assert ep.state != "played"


def test_download_action_promotes_archived(svc, podcast):
    """Phone queued a back-catalog episode → download action → must become
    'inbox' so reconcile queues it."""
    eid = make_episode(podcast, 2, state="archived")
    svc._apply_action({"podcast": FEED, "episode": "https://cdn.example.com/ep002.mp3",
                       "action": "download", "timestamp": "2026-07-16T10:00:00"})
    assert repo.episode_by_id(eid).state == "inbox"
