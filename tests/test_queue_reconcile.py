"""The most valuable tests in the codebase: QueueManager.reconcile() scenarios."""

from __future__ import annotations

import pytest

from aerialpod.core.queue import QueueManager
from aerialpod.db import repo

from .conftest import make_episode


@pytest.fixture()
def qm(fresh_db):
    return QueueManager()


def queue_ids() -> list[int]:
    return [q.episode_id for q in repo.queue_items()]


def test_fresh_episodes_append_chronologically(qm, podcast, episodes):
    qm.reconcile()
    assert queue_ids() == episodes  # ep1 (oldest) first


def test_in_progress_inserts_at_top(qm, podcast, episodes):
    qm.reconcile()
    # phone started ep5 (newest, currently last in queue)
    repo.update_episode(episodes[4], position_secs=300, total_secs=3000,
                        position_updated_at=1700999999)
    qm.reconcile()
    assert queue_ids()[0] == episodes[4]
    # rest keeps relative order
    assert queue_ids()[1:] == episodes[:4]


def test_finished_on_phone_drops_without_resort(qm, podcast, episodes):
    qm.reconcile()
    # user manually rearranged: move ep3 to front (pins it)
    qm.move(episodes[2], 0)
    assert queue_ids()[0] == episodes[2]
    # phone finishes ep1
    repo.update_episode(episodes[0], state="played", position_secs=2970,
                        total_secs=3000)
    qm.reconcile()
    ids = queue_ids()
    assert episodes[0] not in ids
    assert ids[0] == episodes[2]          # manual arrangement preserved
    assert ids[1:] == [episodes[1], episodes[3], episodes[4]]


def test_near_total_counts_as_finished(qm, podcast, episodes):
    qm.reconcile()
    repo.update_episode(episodes[1], position_secs=2980, total_secs=3000)
    qm.reconcile()
    assert episodes[1] not in queue_ids()


def test_zero_total_never_finishes(qm, podcast, episodes):
    """AntennaPod sometimes reports total=0 — must not mark finished."""
    qm.reconcile()
    repo.update_episode(episodes[1], position_secs=2980, total_secs=0)
    qm.reconcile()
    assert episodes[1] in queue_ids()


def test_user_removal_is_permanent(qm, podcast, episodes):
    qm.reconcile()
    qm.remove(episodes[2])
    qm.reconcile()
    qm.reconcile()
    assert episodes[2] not in queue_ids()


def test_manual_readd_clears_exclusion(qm, podcast, episodes):
    qm.reconcile()
    qm.remove(episodes[2])
    qm.add(episodes[2])
    assert episodes[2] in queue_ids()
    qm.reconcile()
    assert episodes[2] in queue_ids()


def test_playing_episode_never_removed(qm, podcast, episodes):
    qm.reconcile()
    qm.playing_episode_id = episodes[0]
    # even if marked played remotely (e.g., stale action), the playing row stays
    repo.update_episode(episodes[0], state="played")
    qm.reconcile()
    assert episodes[0] in queue_ids()


def test_pinned_removed_when_finished(qm, podcast, episodes):
    qm.reconcile()
    qm.move(episodes[1], 0)  # pins ep2
    repo.update_episode(episodes[1], state="played")
    qm.reconcile()
    assert episodes[1] not in queue_ids()


def test_in_progress_inserts_after_pinned_block(qm, podcast, episodes):
    qm.reconcile()
    qm.move(episodes[0], 0)  # pinned head
    # phone starts ep5
    repo.update_episode(episodes[4], position_secs=60, total_secs=3000,
                        position_updated_at=1700999999)
    qm.reconcile()
    ids = queue_ids()
    assert ids[0] == episodes[0]      # pinned stays first
    assert ids[1] == episodes[4]      # in-progress right after pinned block


def test_auto_add_off_keeps_fresh_out(qm, podcast):
    repo.set_podcast_setting(podcast, "auto_add_to_queue", 0)
    make_episode(podcast, 10)
    qm.reconcile()
    assert queue_ids() == []


def test_auto_add_off_still_adds_in_progress(qm, podcast):
    repo.set_podcast_setting(podcast, "auto_add_to_queue", 0)
    eid = make_episode(podcast, 11, position=120, total=3000, updated_at=1700999999)
    qm.reconcile()
    assert queue_ids() == [eid]


def test_unsubscribe_clears_queue(qm, podcast, episodes):
    qm.reconcile()
    repo.unsubscribe_podcast(podcast)
    qm.reconcile()
    assert queue_ids() == []


def test_reconcile_idempotent(qm, podcast, episodes):
    qm.reconcile()
    first = queue_ids()
    qm.reconcile()
    qm.reconcile()
    assert queue_ids() == first


def test_drag_then_reconcile_stable(qm, podcast, episodes):
    qm.reconcile()
    qm.move(episodes[3], 1)
    arranged = queue_ids()
    qm.reconcile()
    qm.reconcile()
    assert queue_ids() == arranged


def test_mark_played_and_advance(qm, podcast, episodes):
    qm.reconcile()
    nxt = qm.mark_played_and_advance(episodes[0])
    assert nxt.id == episodes[1]
    assert episodes[0] not in queue_ids()
    assert repo.episode_by_id(episodes[0]).state == "played"


def test_archived_in_progress_from_phone_queues(qm, podcast):
    """The user's real bug: back-catalog episode (archived) started on the
    phone — position synced but the episode never entered the queue."""
    eid = make_episode(podcast, 20, state="archived", position=847, total=3555,
                       updated_at=1789000000)
    qm.reconcile()
    assert eid in queue_ids()


def test_inbox_from_download_action_queues(qm, podcast):
    """A gpodder 'download' action (phone queued it) must surface here."""
    eid = make_episode(podcast, 21, state="inbox")
    qm.reconcile()
    assert eid in queue_ids()


def test_archived_untouched_stays_out(qm, podcast):
    eid = make_episode(podcast, 22, state="archived")
    qm.reconcile()
    assert eid not in queue_ids()


def test_mark_played_enqueues_gpodder_action(qm, podcast):
    """Marking played on desktop must reach the phone: a play action with
    position == total goes to the outbox."""
    eid = make_episode(podcast, 30, total=3000)
    qm.reconcile()
    qm.mark_played_and_advance(eid)
    actions = repo.outbox_actions()
    assert any(a["action"] == "play" and a["position"] == a["total"] == 3000
               for a in actions), actions


def test_playback_finish_no_duplicate_action(qm, podcast):
    """PlayerService marks state='played' and enqueues its own action before
    mark_played_and_advance runs — no second action from the queue side."""
    eid = make_episode(podcast, 31, state="played", total=3000)
    qm.mark_played_and_advance(eid)
    assert repo.outbox_actions() == []


def test_mark_unplayed_resets_and_requeues(qm, podcast):
    """Right-click 'Mark unplayed': progress reset, back in rotation, and a
    gpodder 'new' action queued so the phone resets too."""
    eid = make_episode(podcast, 40, state="played", position=3000, total=3000)
    qm.mark_unplayed(eid)
    ep = repo.episode_by_id(eid)
    assert ep.state == "inbox"
    assert ep.position_secs == 0
    assert eid in queue_ids()  # inbox qualifies for the queue
    assert any(a["action"] == "new" for a in repo.outbox_actions())


def test_mark_unplayed_clears_exclusion(qm, podcast, episodes):
    qm.reconcile()
    qm.remove(episodes[0])           # user excluded it
    repo.update_episode(episodes[0], state="played")
    qm.mark_unplayed(episodes[0])    # explicit reset overrides the exclusion
    assert episodes[0] in queue_ids()


# ------------------------------------------------- front-of-queue podcasts
#
# The daily-show case: a podcast set to 'front' should own the top slot each
# time it publishes, without shoving aside what is playing or pinned.


@pytest.fixture()
def daily(fresh_db):
    """A second podcast whose new episodes go to the top."""
    pid = repo.upsert_podcast("https://example.com/daily.xml", sync_state="clean")
    repo.update_podcast_meta(pid, title="Daily News")
    repo.set_podcast_setting(pid, "auto_add_to_queue", 1)
    repo.set_podcast_setting(pid, "auto_queue_position", "front")
    return pid


def test_front_podcast_takes_the_top_slot(qm, podcast, episodes, daily):
    qm.reconcile()
    assert queue_ids() == episodes

    monday = make_episode(daily, 1, pub_date=1800000000)
    qm.reconcile()
    assert queue_ids() == [monday, *episodes]


def test_each_morning_episode_lands_above_the_last(qm, podcast, episodes, daily):
    monday = make_episode(daily, 1, pub_date=1800000000)
    qm.reconcile()
    tuesday = make_episode(daily, 2, pub_date=1800086400)
    qm.reconcile()
    # yesterday's unplayed episode stays, but today's is what you see first
    assert queue_ids()[:2] == [tuesday, monday]


def test_front_episodes_do_not_displace_the_playing_one(qm, podcast, episodes, daily):
    qm.reconcile()
    qm.playing_episode_id = episodes[0]
    today = make_episode(daily, 1, pub_date=1800000000)
    qm.reconcile()
    assert queue_ids()[0] == episodes[0]   # still playing
    assert queue_ids()[1] == today         # up next


def test_front_episodes_do_not_displace_a_pin(qm, podcast, episodes, daily):
    qm.reconcile()
    qm.move(episodes[3], 0)                # user pinned ep4 to the top
    today = make_episode(daily, 1, pub_date=1800000000)
    qm.reconcile()
    assert queue_ids()[0] == episodes[3]
    assert queue_ids()[1] == today


def test_front_beats_an_in_progress_episode(qm, podcast, episodes, daily):
    """A half-listened episode floats to the top; this morning's news outranks it."""
    qm.reconcile()
    repo.update_episode(episodes[2], position_secs=300, total_secs=3000,
                        position_updated_at=1799999999)
    today = make_episode(daily, 1, pub_date=1800000000)
    qm.reconcile()
    assert queue_ids()[:2] == [today, episodes[2]]


def test_other_podcasts_still_append(qm, podcast, episodes, daily):
    make_episode(daily, 1, pub_date=1800000000)
    qm.reconcile()
    newest = make_episode(podcast, 9, pub_date=1900000000)
    qm.reconcile()
    assert queue_ids()[-1] == newest


def test_the_global_default_is_the_bottom(qm, podcast, episodes):
    extra = make_episode(podcast, 9, pub_date=1900000000)
    qm.reconcile()
    assert queue_ids()[-1] == extra


def test_a_front_podcast_can_be_set_back_to_the_bottom(qm, podcast, episodes, daily):
    qm.reconcile()
    repo.set_podcast_setting(daily, "auto_queue_position", "back")
    today = make_episode(daily, 1, pub_date=1800000000)
    qm.reconcile()
    assert queue_ids()[-1] == today
