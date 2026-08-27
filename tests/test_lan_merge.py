"""Snapshot exchange between two installs — the merge rules, on real DBs.

Each test runs two independent databases with identical libraries, exactly
like two desktops signed into one gpodder.net account, and checks what
crossing the wire does (and doesn't) change.
"""

from __future__ import annotations

import pytest

from aerialpod import db
from aerialpod.core.queue import QueueManager
from aerialpod.db import repo
from aerialpod.lan import state

from .conftest import make_episode

FEED = "https://example.com/feed.xml"


class Devices:
    """Two installs, one thread. use() switches which one is 'this' machine."""

    def __init__(self, tmp_path):
        self.paths = {"a": tmp_path / "a.db", "b": tmp_path / "b.db"}
        for name in self.paths:
            self.use(name)
            self._seed()

    def use(self, name: str) -> None:
        db.close_thread_connection()
        db.init(self.paths[name])

    def _seed(self) -> None:
        pid = repo.upsert_podcast(FEED, sync_state="clean")
        repo.update_podcast_meta(pid, title="Test Podcast")
        for n in range(1, 6):
            make_episode(pid, n)

    def snapshot_of(self, name: str) -> dict:
        self.use(name)
        return state.build_snapshot()

    def merge_into(self, name: str, snapshot: dict) -> dict:
        self.use(name)
        return state.merge_snapshot(snapshot)


@pytest.fixture()
def devices(tmp_path, qapp):
    pair = Devices(tmp_path)
    yield pair
    db.close_thread_connection()
    db._db_path = None


def queue_ids() -> list[int]:
    return [q.episode_id for q in repo.queue_items()]


def set_intent(eid: int, intent: str, at: int, by: str = "peer", **kwargs) -> None:
    """Write an intent with an explicit timestamp, and apply it locally the way
    QueueManager would — so a device set up this way looks like one where the
    user really did press the button."""
    with db.transaction() as conn:
        repo.record_intent(conn, eid, intent, updated_at=at, updated_by=by, **kwargs)
        if intent == "excluded":
            conn.execute("DELETE FROM queue WHERE episode_id=?", (eid,))
            conn.execute(
                "INSERT OR REPLACE INTO queue_exclusions(episode_id, removed_at) "
                "VALUES(?,?)", (eid, at)
            )
        else:
            conn.execute("DELETE FROM queue_exclusions WHERE episode_id=?", (eid,))
            conn.execute(
                "INSERT OR REPLACE INTO queue(episode_id, position, origin, pinned, "
                "added_at) VALUES(?,?,?,?,?)",
                (eid, kwargs.get("position", 0), kwargs.get("origin", "manual"),
                 kwargs.get("pinned", 0), at),
            )


def age_settings(at: int) -> None:
    """Backdate this device's podcast settings, to make it the older writer."""
    db.connection().execute("UPDATE podcast_settings SET updated_at=?", (at,))
    db.connection().commit()


# ---------------------------------------------------------------- queue order


def test_queue_order_travels(devices):
    """The whole point of the feature: gpodder.net cannot carry this."""
    devices.use("a")
    qm = QueueManager()
    qm.add(5)
    qm.add(3)
    qm.add(1)
    qm.move(1, 0)  # drag ep1 to the top
    expected = queue_ids()

    devices.merge_into("b", devices.snapshot_of("a"))
    assert queue_ids() == expected


def test_a_pin_travels(devices):
    devices.use("a")
    QueueManager().add(4)

    devices.merge_into("b", devices.snapshot_of("a"))
    row = db.connection().execute(
        "SELECT pinned, origin FROM queue WHERE episode_id=4"
    ).fetchone()
    assert row["pinned"] == 1
    assert row["origin"] == "manual"


def test_manual_removal_travels(devices):
    devices.use("a")
    qm = QueueManager()
    qm.add(2)
    qm.remove(2)

    devices.merge_into("b", devices.snapshot_of("a"))
    assert 2 not in queue_ids()
    assert repo.is_excluded(2)


def test_merged_queue_keeps_the_gap_scheme(devices):
    devices.use("a")
    qm = QueueManager()
    qm.add(5)
    qm.add(3)

    devices.merge_into("b", devices.snapshot_of("a"))
    positions = [q.position for q in repo.queue_items()]
    assert positions == [state.GAP, state.GAP * 2]


# ---------------------------------------------------------------- last writer wins


def test_newer_intent_wins(devices):
    devices.use("b")
    set_intent(2, "excluded", at=1000)
    devices.use("a")
    set_intent(2, "queued", at=2000, position=1024)

    counts = devices.merge_into("b", devices.snapshot_of("a"))
    assert counts["intents"] == 1
    assert 2 in queue_ids()


def test_older_intent_loses(devices):
    devices.use("b")
    set_intent(2, "queued", at=2000, position=1024)
    devices.use("a")
    set_intent(2, "excluded", at=1000)

    counts = devices.merge_into("b", devices.snapshot_of("a"))
    assert counts["intents"] == 0
    assert 2 in queue_ids()
    assert not repo.is_excluded(2)


def test_restoring_an_episode_beats_a_peers_older_exclusion(devices):
    """The reason intent is recorded rather than inferred: 'mark unplayed'
    clears an exclusion, and a peer still holding the old one must not throw
    the episode straight back out."""
    devices.use("b")
    set_intent(3, "excluded", at=1000)

    devices.use("a")
    set_intent(3, "excluded", at=1000)
    QueueManager().mark_unplayed(3)  # user restores it here, later

    devices.merge_into("b", devices.snapshot_of("a"))
    assert not repo.is_excluded(3)
    assert 3 in queue_ids()

    # …and the stale exclusion travelling the other way changes nothing.
    counts = devices.merge_into("a", devices.snapshot_of("b"))
    assert counts["intents"] == 0
    assert not repo.is_excluded(3)


def test_ties_break_the_same_way_on_both_sides(devices):
    """Two devices resolving one conflict must never disagree, or they ping-pong."""
    devices.use("a")
    set_intent(2, "queued", at=5000, by="aaaa", position=1024)
    devices.use("b")
    set_intent(2, "excluded", at=5000, by="zzzz")

    devices.merge_into("b", devices.snapshot_of("a"))
    b_queued = 2 in queue_ids()
    devices.merge_into("a", devices.snapshot_of("b"))
    a_queued = 2 in queue_ids()
    assert a_queued == b_queued  # both landed on 'zzzz', whichever way it went
    assert not a_queued


def test_merge_is_idempotent(devices):
    devices.use("a")
    QueueManager().add(5)
    snapshot = devices.snapshot_of("a")

    first = devices.merge_into("b", snapshot)
    second = devices.merge_into("b", snapshot)
    assert first["intents"] == 1
    assert second["intents"] == 0


# ---------------------------------------------------------------- positions


def test_newer_position_wins(devices):
    devices.use("b")
    repo.update_episode(1, position_secs=100, position_updated_at=1000)
    devices.use("a")
    repo.update_episode(1, position_secs=1800, total_secs=3600,
                        position_updated_at=2000)

    devices.merge_into("b", devices.snapshot_of("a"))
    episode = repo.episode_by_id(1)
    assert episode.position_secs == 1800
    assert episode.total_secs == 3600


def test_older_position_ignored(devices):
    devices.use("b")
    repo.update_episode(1, position_secs=1800, position_updated_at=9000)
    devices.use("a")
    repo.update_episode(1, position_secs=30, position_updated_at=1000)

    counts = devices.merge_into("b", devices.snapshot_of("a"))
    assert counts["positions"] == 0
    assert repo.episode_by_id(1).position_secs == 1800


def test_merge_replicates_played(devices):
    """A finished episode carries its state, not just its position.

    This used to leave played/new to gpodder.net alone, on the reasoning that
    two paths writing one field would race. The cost turned out to be worse
    than the race: a peer that has never seen an episode cannot tell "finished"
    from "not started", so it queues everything the other device has already
    listened to. On a fresh phone that is the whole back catalogue, and no
    amount of syncing clears it, because there is nothing left to send.

    Only `finished=True` is acted on — see PositionRecord.finished — so this
    can mark an episode played but never un-mark one, and a peer too old to
    send the flag simply omits it.
    """
    devices.use("b")
    repo.update_episode(1, state="new")
    devices.use("a")
    repo.update_episode(1, state="played", position_secs=3590, total_secs=3600,
                        position_updated_at=2000)

    devices.merge_into("b", devices.snapshot_of("a"))
    episode = repo.episode_by_id(1)
    assert episode.state == "played"
    assert episode.position_secs == 3590


def test_merge_never_unmarks_played(devices):
    """The flag is one-way: an unfinished record must not undo a played one."""
    devices.use("b")
    repo.update_episode(1, state="played", position_secs=1200, total_secs=3600,
                        position_updated_at=1000)
    devices.use("a")
    repo.update_episode(1, state="new", position_secs=1800, total_secs=3600,
                        position_updated_at=2000)

    devices.merge_into("b", devices.snapshot_of("a"))
    episode = repo.episode_by_id(1)
    assert episode.state == "played", "a newer unfinished record must not un-mark"
    assert episode.position_secs == 1800, "the position is still the newer one"


def test_live_position_push_applies(devices):
    devices.use("a")
    repo.update_episode(2, position_secs=640, total_secs=3600, position_updated_at=4242)
    episode = repo.episode_by_id(2)
    message = state.position_message(episode)

    devices.use("b")
    assert state.apply_position_message(message)
    assert repo.episode_by_id(2).position_secs == 640
    assert not state.apply_position_message(message)  # replaying it is a no-op


# ---------------------------------------------------------------- settings


def test_podcast_settings_travel(devices):
    devices.use("a")
    repo.set_podcast_setting(1, "playback_speed", 1.75)
    repo.set_podcast_setting(1, "skip_intro_secs", 45)

    counts = devices.merge_into("b", devices.snapshot_of("a"))
    assert counts["settings"] == 1
    settings = repo.podcast_settings(1)
    assert settings["playback_speed"] == 1.75
    assert settings["skip_intro_secs"] == 45


def test_newer_settings_win(devices):
    devices.use("b")
    repo.set_podcast_setting(1, "playback_speed", 2.0)  # stamped now
    devices.use("a")
    repo.set_podcast_setting(1, "playback_speed", 1.25)
    age_settings(1000)  # …but A changed it long ago

    counts = devices.merge_into("b", devices.snapshot_of("a"))
    assert counts["settings"] == 0
    assert repo.podcast_settings(1)["playback_speed"] == 2.0


# ---------------------------------------------------------------- matching


def test_episodes_match_by_guid_when_the_cdn_rotates_the_url(devices):
    """Ad-injecting CDNs hand each device a different enclosure URL, so the
    GUID is what actually identifies an episode across installs."""
    devices.use("b")
    db.connection().execute(
        "UPDATE episodes SET media_url='https://cdn.example.com/tok-XYZ/ep002.mp3' "
        "WHERE id=2"
    )
    db.connection().commit()

    devices.use("a")
    repo.update_episode(2, position_secs=900, position_updated_at=7000)

    counts = devices.merge_into("b", devices.snapshot_of("a"))
    assert counts["positions"] == 1
    assert repo.episode_by_id(2).position_secs == 900


def test_records_for_unknown_episodes_are_skipped(devices):
    """A peer that has already fetched a feed we haven't must not break the
    merge — the next snapshot carries the record again."""
    devices.use("a")
    pid = repo.upsert_podcast("https://other.example/feed.xml", sync_state="clean")
    eid = make_episode(pid, 99)
    repo.update_episode(eid, position_secs=120, position_updated_at=5000)
    QueueManager().add(eid)
    snapshot = devices.snapshot_of("a")

    counts = devices.merge_into("b", snapshot)
    assert counts["positions"] == 0
    assert counts["intents"] == 0


def test_unsupported_snapshot_version_is_rejected(devices):
    devices.use("b")
    with pytest.raises(ValueError, match="unsupported snapshot version"):
        state.merge_snapshot({"type": "snapshot", "v": 99})


def test_a_new_setting_replicates_without_being_listed_twice(devices):
    """auto_queue_position was added after the sync code existed; the snapshot
    builder reads SETTING_KEYS so it travelled without further work."""
    devices.use("a")
    repo.set_podcast_setting(1, "auto_queue_position", "front")

    devices.merge_into("b", devices.snapshot_of("a"))
    assert repo.effective_queue_position(1) == "front"


def test_position_collisions_break_the_same_way_on_both_devices(tmp_path, qapp):
    """Two devices, same episodes, different local rowids — same queue order.

    Episode ids are per-install rowids handed out in feed-fetch order, so the
    same episode is id 4 here and id 9 there. Tie-breaking a position collision
    on that id makes each device flatten the merged queue its own way, and they
    stay disagreed: each then ships its order to the other as intent. The
    tie-break has to be (feed, guid), which both ends resolve identically.
    """
    def order_after_merge(insert_order: list[str]) -> list[str]:
        db.close_thread_connection()
        db.init(tmp_path / f"{'-'.join(insert_order)}.db")
        pid = repo.upsert_podcast(FEED, sync_state="clean")
        repo.update_podcast_meta(pid, title="Test Podcast")
        conn = db.connection()
        ids = {}
        for guid in insert_order:
            cur = conn.execute(
                "INSERT INTO episodes(podcast_id, guid, media_url, title, pub_date, "
                "state, position_secs, total_secs, position_updated_at) "
                "VALUES(?,?,?,?,?,'new',0,0,0)",
                (pid, guid, f"https://cdn.example.com/{guid}.mp3", guid, 1700000000),
            )
            ids[guid] = cur.lastrowid
        # Local decision already sitting at 1024; the peer's arrives at 1024 too.
        conn.execute(
            "INSERT INTO queue(episode_id, position, origin, pinned, added_at) "
            "VALUES(?,1024,'auto',0,1700000000)", (ids["guid-B"],))
        repo.record_intent(conn, ids["guid-B"], "queued", position=1024,
                           origin="auto", updated_at=100, updated_by="local")
        conn.commit()

        state.merge_snapshot({
            "type": "snapshot", "v": 1, "settings": [], "positions": [],
            "intents": [{
                "feed": FEED, "guid": "guid-A",
                "media": "https://cdn.example.com/guid-A.mp3",
                "intent": "queued", "position": 1024, "pinned": 0,
                "origin": "manual", "updated_at": 200, "updated_by": "peer",
            }],
        })
        rows = conn.execute(
            "SELECT e.guid FROM queue q JOIN episodes e ON e.id=q.episode_id "
            "ORDER BY q.position").fetchall()
        return [r["guid"] for r in rows]

    assert order_after_merge(["guid-A", "guid-B"]) == order_after_merge(["guid-B", "guid-A"])


def test_replicated_version_moves_for_every_replicated_section(devices):
    """The version has to notice a change in any section a snapshot carries,
    or the periodic re-broadcast that consults it will skip real news."""
    devices.use("a")
    conn = db.connection()
    before = state.replicated_version(conn)

    repo.update_episode(1, position_secs=90, position_updated_at=before + 10)
    after_position = state.replicated_version(conn)
    assert after_position > before

    repo.set_podcast_setting(1, "playback_speed", 1.5)
    conn.execute("UPDATE podcast_settings SET updated_at=? WHERE podcast_id=1",
                 (after_position + 10,))
    conn.commit()
    after_setting = state.replicated_version(conn)
    assert after_setting > after_position

    repo.record_intent(conn, 2, "queued", position=1024,
                       updated_at=after_setting + 10, updated_by="dev")
    conn.commit()
    assert state.replicated_version(conn) > after_setting


def test_replicated_version_is_zero_on_an_untouched_library(fresh_db):
    assert state.replicated_version(db.connection()) == 0
