"""Commands, end to end through the client and the in-process backend.

This is the path the window actually uses when there is no daemon, and the same
hub code the daemon runs — so it covers the seam and the service behaviour in
one go, without a bus.
"""

from __future__ import annotations

import pytest

from aerialpod.db import repo
from aerialpod.ipc.client import DaemonClient
from aerialpod.ipc.inprocess import InProcessBackend

from .conftest import make_episode


@pytest.fixture()
def client(fresh_db, podcast, monkeypatch):
    """A live hub with everything that would touch the network disarmed."""
    monkeypatch.setattr("aerialpod.gpodder.credentials.load", lambda: None)
    monkeypatch.setattr("aerialpod.feeds.fetcher.fetch_and_store", lambda pid: 0)
    repo.set_state("download_ahead_n", 0)   # no downloads from a test
    repo.set_state("lan_sync_enabled", False)
    repo.set_state("lan_scan_subnets", False)
    backend = InProcessBackend(dry_run_sync=True)
    client = DaemonClient(backend)
    yield client
    client.shutdown()


@pytest.fixture()
def episodes(podcast):
    return [make_episode(podcast, n) for n in range(1, 6)]


def queue_ids() -> list[int]:
    return [q.episode_id for q in repo.queue_items()]


# ---------------------------------------------------------------- queue


def test_queue_add_and_remove(client, episodes):
    client.queue_add(episodes[2])
    assert episodes[2] in queue_ids()

    client.queue_remove(episodes[2])
    assert episodes[2] not in queue_ids()
    assert repo.is_excluded(episodes[2])


def test_queue_toggle_flips_membership(client, episodes):
    client.queue_toggle(episodes[0])
    assert episodes[0] in queue_ids()
    client.queue_toggle(episodes[0])
    assert episodes[0] not in queue_ids()


def test_queue_move_reorders(client, episodes):
    for eid in episodes[:3]:
        client.queue_add(eid)
    client.queue_move(episodes[2], 0)
    assert queue_ids()[0] == episodes[2]


def test_queue_pin_records_intent(client, episodes):
    """Pinning used to be raw SQL in the queue page, so it never reached peers."""
    client.queue_add(episodes[1])
    client.queue_release_to_auto(episodes[1])
    assert repo.intent_for(episodes[1])["pinned"] == 0

    client.queue_pin(episodes[1])
    intent = repo.intent_for(episodes[1])
    assert intent["pinned"] == 1
    assert intent["origin"] == "manual"


def test_mark_played_drops_it_from_the_queue(client, episodes):
    client.queue_add(episodes[0])
    client.mark_played(episodes[0])
    assert repo.episode_by_id(episodes[0]).state == "played"
    assert episodes[0] not in queue_ids()


def test_mark_unplayed_restores_it(client, episodes):
    client.queue_add(episodes[0])
    client.mark_played(episodes[0])
    client.mark_unplayed(episodes[0])
    assert repo.episode_by_id(episodes[0]).state != "played"


def test_set_playing_protects_the_current_episode(client, episodes):
    client.queue_add(episodes[0])
    client.set_playing(episodes[0])
    # Finished episodes are normally dropped; the playing one never is.
    repo.update_episode(episodes[0], position_secs=3000, total_secs=3000)
    client.reconcile()
    assert episodes[0] in queue_ids()

    client.set_playing(None)
    client.reconcile()
    assert episodes[0] not in queue_ids()


# ---------------------------------------------------------------- settings


def test_set_state_round_trips_a_scalar(client):
    client.set_state("skip_fwd_secs", 45)
    assert repo.get_state("skip_fwd_secs") == 45


def test_set_state_round_trips_a_list(client):
    client.set_state("home_sections", ["queue", "inbox"])
    assert repo.get_state("home_sections") == ["queue", "inbox"]


def test_set_podcast_setting(client, podcast):
    client.set_podcast_setting(podcast, "auto_queue_position", "front")
    assert repo.effective_queue_position(podcast) == "front"


def test_subscribe_adds_the_podcast(client):
    client.subscribe("https://example.com/new.xml")
    assert repo.podcast_by_feed_url("https://example.com/new.xml") is not None


def test_unsubscribe_marks_it_pending(client, podcast):
    client.unsubscribe(podcast)
    assert repo.podcast_by_id(podcast).subscribed == 0


# ---------------------------------------------------------------- playback


def test_report_position_persists(client, episodes):
    client.report_position(episodes[0], 640, 3600, False)
    episode = repo.episode_by_id(episodes[0])
    assert episode.position_secs == 640
    assert episode.total_secs == 3600


def test_only_a_final_report_queues_a_gpodder_action(client, episodes):
    client.report_position(episodes[0], 100, 3600, False)
    assert repo.outbox_actions() == []

    client.report_position(episodes[0], 200, 3600, True)
    actions = repo.outbox_actions()
    assert len(actions) == 1
    assert actions[0]["action"] == "play"
    assert actions[0]["position"] == 200


def test_report_position_for_an_unknown_episode_is_ignored(client):
    client.report_position(9999, 10, 20, True)
    assert repo.outbox_actions() == []


# ---------------------------------------------------------------- robustness


def test_an_unknown_command_is_ignored(client):
    client.backend.hub.execute("definitely_not_a_command", ())


def test_a_failing_command_does_not_take_the_hub_down(client, episodes):
    client.backend.hub.execute("queue_add", ("not-an-episode-id", False))
    client.queue_add(episodes[0])
    assert episodes[0] in queue_ids()


# ---------------------------------------------------------------- signals


def test_queue_changes_are_announced(client, episodes, qapp):
    seen = []
    client.queueChanged.connect(lambda: seen.append(1))
    client.queue_add(episodes[0])
    assert seen, "the window would never refresh"
