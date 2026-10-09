"""Ad spans crossing the wire: last-writer-wins, guarded against bad input."""

from __future__ import annotations

from aerialpod import db
from aerialpod.db import repo

from . import test_lan_merge
from .conftest import now

devices = test_lan_merge.devices  # the two-install fixture, shared

T = now() - 3600
SPANS = [[60_000, 120_000, 0.93], [1_800_000, 1_860_000, 0.81]]


def detect(eid: int, spans, at: int, by: str = "laptop", duration_ms: int = 3_600_000):
    with db.transaction() as conn:
        repo.set_ad_segments(conn, eid, duration_ms, spans, model="test",
                             updated_at=at, updated_by=by)


def test_spans_reach_a_peer_that_has_none(devices):
    devices.use("a")
    detect(1, SPANS, at=T)

    counts = devices.merge_into("b", devices.snapshot_of("a"))

    assert counts["ads"] == 1
    row = repo.ad_segments(1)
    assert row["spans"] == SPANS
    assert row["duration_ms"] == 3_600_000
    assert row["updated_by"] == "laptop"


def test_newer_local_row_wins(devices):
    devices.use("a")
    detect(1, SPANS, at=T)
    devices.use("b")
    detect(1, [], at=T + 1, by="phone")  # the user said "no ads here", later

    assert devices.merge_into("b", devices.snapshot_of("a"))["ads"] == 0
    assert repo.ad_segments(1)["spans"] == []

    # …and the cleared row travels back the other way.
    assert devices.merge_into("a", devices.snapshot_of("b"))["ads"] == 1
    assert repo.ad_segments(1)["spans"] == []


def test_an_empty_result_still_replicates(devices):
    """Analysed-and-clean is information: it stops the peer re-detecting."""
    devices.use("a")
    detect(2, [], at=T)
    devices.merge_into("b", devices.snapshot_of("a"))
    assert 2 in repo.analysed_episode_ids()


def test_old_rows_for_unqueued_episodes_stay_home(devices):
    devices.use("a")
    detect(1, SPANS, at=now() - 60 * 86400)
    assert devices.snapshot_of("a")["ads"] == []


def test_old_rows_for_queued_episodes_still_travel(devices):
    devices.use("a")
    detect(1, SPANS, at=now() - 60 * 86400)
    with db.transaction() as conn:
        conn.execute("INSERT INTO queue(episode_id, position, added_at) VALUES(1, 1024, 0)")
    assert len(devices.snapshot_of("a")["ads"]) == 1


def test_malformed_spans_are_rejected_whole(devices):
    snapshot = devices.snapshot_of("a")
    feed = "https://example.com/feed.xml"
    snapshot["ads"] = [
        {"feed": feed, "guid": "guid-1-1", "media": "", "duration_ms": 1000,
         "spans": [[0, 100, 0.9], [500, 400, 0.9]], "updated_at": 5, "updated_by": "x"},
        {"feed": feed, "guid": "guid-1-2", "media": "", "duration_ms": 1000,
         "spans": [[0, 100, 7]], "updated_at": 5, "updated_by": "x"},
        {"feed": feed, "guid": "guid-1-3", "media": "", "duration_ms": 0,
         "spans": [], "updated_at": 5, "updated_by": "x"},
    ]
    assert devices.merge_into("b", snapshot)["ads"] == 0
    assert repo.analysed_episode_ids() == set()


def test_snapshot_from_a_peer_without_ads_section(devices):
    devices.use("b")
    detect(1, SPANS, at=T)
    snapshot = devices.snapshot_of("a")
    del snapshot["ads"]

    assert devices.merge_into("b", snapshot)["ads"] == 0
    assert repo.ad_segments(1)["spans"] == SPANS


def test_ad_rows_move_the_replicated_version(devices):
    from aerialpod.lan import state

    devices.use("a")
    future = now() + 3600  # newer than anything the seed data wrote
    detect(1, SPANS, at=future)
    assert state.replicated_version(db.connection()) == future


def test_own_results_from_an_older_detector_run_again(devices):
    devices.use("a")
    me = repo.lan_device_id()
    with db.transaction() as conn:
        repo.set_ad_segments(conn, 1, 1000, [], model="m@2", updated_by=me)
        repo.set_ad_segments(conn, 2, 1000, [], model="m@3", updated_by=me)
        repo.set_ad_segments(conn, 3, 1000, [], model="user", updated_by=me)
        repo.set_ad_segments(conn, 4, 1000, [], model="m@1", updated_by="a-peer")
    assert repo.analysed_episode_ids(redo_own_except={"m@3", "user"}) == {2, 3, 4}
    assert repo.analysed_episode_ids() == {1, 2, 3, 4}
