"""QueueManager: the derived-queue-with-manual-override algorithm.

Rules (see plan):
- reconcile() only removes and inserts; it NEVER re-sorts surviving rows.
- The currently-playing episode is never removed.
- pinned rows are kept unless finished or unsubscribed.
- Removed-by-user episodes live in queue_exclusions and are never auto re-added.
- In-progress episodes insert after the playing item + leading pinned block;
  fresh episodes append at the end in pub_date order.
"""

from __future__ import annotations

import logging
import time

from PySide6.QtCore import QObject, Signal

from .. import db
from ..db import repo
from ..db.models import Episode

log = logging.getLogger(__name__)

GAP = 1024
FINISHED_SLACK_SECS = 30


def is_finished(ep: Episode) -> bool:
    if ep.state == "played":
        return True
    # Guard: AntennaPod sometimes reports total as 0 / -1.
    if ep.total_secs and ep.total_secs > 0:
        return ep.position_secs >= ep.total_secs - FINISHED_SLACK_SECS
    return False


class QueueManager(QObject):
    queueChanged = Signal()
    syncNeeded = Signal()  # an action was enqueued that the phone should see soon

    def __init__(self, parent: QObject | None = None):
        super().__init__(parent)
        self.playing_episode_id: int | None = None  # kept current by PlayerService

    # ------------------------------------------------------------ queries

    def episodes(self) -> list[Episode]:
        return repo.queue_episodes()

    def head(self) -> Episode | None:
        eps = self.episodes()
        return eps[0] if eps else None

    def next_after(self, episode_id: int) -> Episode | None:
        eps = self.episodes()
        for i, ep in enumerate(eps):
            if ep.id == episode_id:
                return eps[i + 1] if i + 1 < len(eps) else None
        return eps[0] if eps else None

    def contains(self, episode_id: int) -> bool:
        return any(q.episode_id == episode_id for q in repo.queue_items())

    # ------------------------------------------------------------ user ops

    def add(self, episode_id: int, to_front: bool = False) -> None:
        """Manual add: pinned, exclusion cleared."""
        with db.transaction() as conn:
            conn.execute("DELETE FROM queue_exclusions WHERE episode_id=?", (episode_id,))
            if conn.execute(
                "SELECT 1 FROM queue WHERE episode_id=?", (episode_id,)
            ).fetchone():
                return
            if to_front:
                first = conn.execute("SELECT MIN(position) FROM queue").fetchone()[0]
                pos = (first or GAP) - GAP
            else:
                last = conn.execute("SELECT MAX(position) FROM queue").fetchone()[0]
                pos = (last or 0) + GAP
            conn.execute(
                "INSERT INTO queue(episode_id, position, origin, pinned, added_at) "
                "VALUES(?,?,?,?,?)",
                (episode_id, pos, "manual", 1, int(time.time())),
            )
        self.queueChanged.emit()

    def remove(self, episode_id: int, exclude: bool = True) -> None:
        """User removal: never auto re-add (exclusion)."""
        with db.transaction() as conn:
            conn.execute("DELETE FROM queue WHERE episode_id=?", (episode_id,))
            if exclude:
                conn.execute(
                    "INSERT OR REPLACE INTO queue_exclusions(episode_id, removed_at) "
                    "VALUES(?,?)",
                    (episode_id, int(time.time())),
                )
        self.queueChanged.emit()

    def toggle(self, episode_id: int) -> None:
        if self.contains(episode_id):
            self.remove(episode_id)
        else:
            self.add(episode_id)

    def move(self, episode_id: int, new_index: int) -> None:
        """Drag-drop reorder → the moved row becomes pinned/manual."""
        items = repo.queue_items()
        ids = [q.episode_id for q in items if q.episode_id != episode_id]
        new_index = max(0, min(new_index, len(ids)))
        ids.insert(new_index, episode_id)
        with db.transaction() as conn:
            for i, eid in enumerate(ids):
                conn.execute(
                    "UPDATE queue SET position=? WHERE episode_id=?", ((i + 1) * GAP, eid)
                )
            conn.execute(
                "UPDATE queue SET pinned=1, origin='manual' WHERE episode_id=?",
                (episode_id,),
            )
        self.queueChanged.emit()

    def release_to_auto(self, episode_id: int) -> None:
        with db.transaction() as conn:
            conn.execute(
                "UPDATE queue SET pinned=0, origin='auto' WHERE episode_id=?", (episode_id,)
            )
        self.queueChanged.emit()

    def mark_played_and_advance(self, episode_id: int) -> Episode | None:
        """Episode finished (played out or user marked it): mark played, drop
        from queue, tell gpodder (so AntennaPod dequeues it too), return next."""
        nxt = self.next_after(episode_id)
        ep = repo.episode_by_id(episode_id)
        with db.transaction() as conn:
            conn.execute("UPDATE episodes SET state='played' WHERE id=?", (episode_id,))
            conn.execute("DELETE FROM queue WHERE episode_id=?", (episode_id,))

        # If playback already reported completion (PlayerService), the episode
        # was 'played' before this call — don't enqueue a duplicate action.
        if ep is not None and ep.state != "played":
            p = repo.podcast_by_id(ep.podcast_id)
            total = ep.total_secs or ep.duration_secs or 0
            if p is not None and total > 0:
                from datetime import datetime, timezone

                repo.enqueue_action(
                    p.feed_url, ep.media_url, "play",
                    datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"),
                    started=total, position=total, total=total,
                )
                self.syncNeeded.emit()
        self.queueChanged.emit()
        return nxt

    def mark_unplayed(self, episode_id: int) -> None:
        """Reset progress/played state; tells gpodder via a 'new' action so
        the phone resets too. Reconcile decides whether it re-enters the queue."""
        ep = repo.episode_by_id(episode_id)
        if ep is None:
            return
        repo.update_episode(
            episode_id, state="inbox", position_secs=0,
            position_updated_at=int(time.time()),
        )
        with db.transaction() as conn:
            conn.execute("DELETE FROM queue_exclusions WHERE episode_id=?", (episode_id,))
        p = repo.podcast_by_id(ep.podcast_id)
        if p is not None:
            from datetime import datetime, timezone

            repo.enqueue_action(
                p.feed_url, ep.media_url, "new",
                datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"),
            )
            self.syncNeeded.emit()
        self.reconcile()
        self.queueChanged.emit()

    # ------------------------------------------------------------ reconcile

    def _qualifies(self, ep: Episode) -> bool:
        p = repo.podcast_by_id(ep.podcast_id)
        if p is None or not p.subscribed:
            return False
        if is_finished(ep):
            return False
        if ep.position_secs > 0:
            return True  # in progress somewhere — even archived back-catalog
        # 'inbox' = a gpodder download action, i.e. the phone (almost always)
        # queued it — the closest thing the protocol has to a queue-add signal.
        if ep.state == "inbox":
            return True
        return ep.state == "new" and repo.effective_auto_add(ep.podcast_id)

    def reconcile(self) -> None:
        """Rebuild queue membership from episode state. Remove + insert only."""
        changed = False
        with db.transaction() as conn:
            items = repo.queue_items()

            # --- removal pass (order preserved) ---
            survivors = []
            for q in items:
                ep = repo.episode_by_id(q.episode_id)
                if ep is None:
                    conn.execute("DELETE FROM queue WHERE episode_id=?", (q.episode_id,))
                    changed = True
                    continue
                if q.episode_id == self.playing_episode_id:
                    survivors.append(q)  # never remove the playing episode
                    continue
                if q.pinned:
                    p = repo.podcast_by_id(ep.podcast_id)
                    if is_finished(ep) or p is None or not p.subscribed:
                        conn.execute("DELETE FROM queue WHERE episode_id=?", (q.episode_id,))
                        changed = True
                    else:
                        survivors.append(q)
                    continue
                if not self._qualifies(ep):
                    conn.execute("DELETE FROM queue WHERE episode_id=?", (q.episode_id,))
                    changed = True
                else:
                    survivors.append(q)

            # --- insertion / float pass ---
            # head block: leading contiguous run of (playing | pinned) rows —
            # these never move.
            head: list[int] = []
            rest: list[int] = []
            for q in survivors:
                if not rest and (q.episode_id == self.playing_episode_id or q.pinned):
                    head.append(q.episode_id)
                else:
                    rest.append(q.episode_id)

            # floaters: in-progress episodes surface right after the head
            # block, most recently touched (on any device) first. Unpinned
            # in-queue rows float too — "started it on the phone" moves it up.
            by_id = {q.episode_id: q for q in survivors}
            in_queue = set(by_id)
            candidates = [
                ep
                for ep in self._candidate_episodes()
                if ep.id not in in_queue and not repo.is_excluded(ep.id)
            ]
            float_ids: dict[int, int] = {}  # episode_id -> position_updated_at
            for eid in rest:
                ep = repo.episode_by_id(eid)
                if ep and ep.position_secs > 0:
                    float_ids[eid] = ep.position_updated_at
            for ep in candidates:
                if ep.position_secs > 0:
                    float_ids[ep.id] = ep.position_updated_at
            floaters = sorted(float_ids, key=float_ids.get, reverse=True)

            fresh = sorted(
                (ep for ep in candidates if ep.position_secs == 0),
                key=lambda e: e.pub_date or 0,
            )

            order = (
                head
                + floaters
                + [eid for eid in rest if eid not in float_ids]
                + [ep.id for ep in fresh]
            )
            old_order = [q.episode_id for q in survivors]
            if order != old_order:
                new_ids = {ep.id for ep in candidates} | {ep.id for ep in fresh}
                self._write_order(conn, order, new_ids=new_ids)
                changed = True

        if changed:
            self.queueChanged.emit()

    def _candidate_episodes(self) -> list[Episode]:
        # In-progress episodes qualify regardless of state (a phone-started
        # back-catalog episode is locally 'archived'); untouched ones only
        # via 'new'/'inbox'.
        rows = db.connection().execute(
            "SELECT e.* FROM episodes e JOIN podcasts p ON p.id=e.podcast_id "
            "WHERE p.subscribed=1 AND e.state != 'played' "
            "AND (e.position_secs > 0 OR e.state IN ('new','inbox'))"
        )
        from ..db.models import from_row

        eps = [from_row(Episode, r) for r in rows]
        return [ep for ep in eps if self._qualifies(ep)]

    def _write_order(self, conn, order: list[int], new_ids: set[int]) -> None:
        """Renumber everything to the given order, inserting new rows as auto."""
        now = int(time.time())
        for i, eid in enumerate(order, start=1):
            if eid in new_ids:
                conn.execute(
                    "INSERT OR IGNORE INTO queue(episode_id, position, origin, pinned, added_at) "
                    "VALUES(?,?,?,?,?)",
                    (eid, i * GAP, "auto", 0, now),
                )
                conn.execute(
                    "UPDATE queue SET position=? WHERE episode_id=?", (i * GAP, eid)
                )
            else:
                conn.execute(
                    "UPDATE queue SET position=? WHERE episode_id=?", (i * GAP, eid)
                )
