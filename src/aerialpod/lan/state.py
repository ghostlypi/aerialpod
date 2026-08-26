"""What peers exchange, and how a peer's version of the truth is merged in.

Every record carries its own timestamp and the device that wrote it, and the
newer one wins — the same last-writer-wins rule the gpodder path already uses
for playback positions, extended to the state gpodder cannot carry. Ties break
on device id so that two peers resolving the same conflict never disagree.

Snapshots are *complete*, not deltas: every exchange carries the whole
replicated state. That buys self-healing — a peer that was offline, or that
hadn't yet fetched an episode when a record about it arrived, needs no cursor
and no catch-up protocol to converge. The cost stays reasonable because none of
this is podcast data and most of it is bounded by what one person can actually
listen to: 26 KiB for a typical library, 45 KiB for a heavy one, against a LAN
that moves it in single-digit milliseconds.

The one part that grows without that bound is exclusions — dismissing an
episode takes a second, not an hour, and prune_intents() only reaps intents
whose episode was *played*. Five years at ten dismissals a day reaches roughly
4.8 MB and 0.7s to merge, which is still fine, and is why they are left alone.

What is not fine is paying that on a timer for a snapshot nobody needs: every
record costs the receiver a resolve_episode() lookup whether or not it turns
out to be newer, so a no-op merge costs nearly as much as a real one. Hence
replicated_version().

Episodes are addressed the way peers can both resolve them: feed URL plus
GUID, falling back to the enclosure URL through the same matching ladder the
gpodder sync uses, since ad-injecting CDNs rotate enclosure URLs per device.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from .. import db
from ..db import repo
from ..db.models import Episode, from_row
from ..gpodder import matching

log = logging.getLogger(__name__)

SNAPSHOT_VERSION = 1
GAP = 1024
POSITION_LIMIT = 1000  # most recently touched in-progress episodes


# ---------------------------------------------------------------- building


def build_snapshot() -> dict[str, Any]:
    conn = db.connection()
    return {
        "type": "snapshot",
        "v": SNAPSHOT_VERSION,
        "intents": _build_intents(conn),
        "settings": _build_settings(conn),
        "positions": _build_positions(conn),
    }


def replicated_version(conn) -> int:
    """Newest timestamp anywhere in the state a snapshot carries.

    Lets a caller tell "nothing has changed since I last sent one" from "there
    is news", without building the snapshot to find out. Reads the same three
    sources build_snapshot() does, so a section that starts replicating is
    covered here without having to remember to come back and add itself.

    Seconds resolution, so two writes in the same second read as one version.
    That is why only the periodic re-broadcast consults this — the change-driven
    push sends unconditionally and cannot miss the second write.
    """
    row = conn.execute(
        "SELECT MAX(v) FROM ("
        "  SELECT MAX(updated_at) AS v FROM queue_intent"
        "  UNION ALL SELECT MAX(updated_at) FROM podcast_settings"
        "  UNION ALL SELECT MAX(position_updated_at) FROM episodes"
        ")"
    ).fetchone()
    return int(row[0] or 0)


def _build_intents(conn) -> list[dict[str, Any]]:
    rows = conn.execute(
        "SELECT i.*, e.guid, e.media_url, p.feed_url FROM queue_intent i "
        "JOIN episodes e ON e.id = i.episode_id "
        "JOIN podcasts p ON p.id = e.podcast_id "
        "ORDER BY i.position"
    )
    return [
        {
            "feed": r["feed_url"],
            "guid": r["guid"],
            "media": r["media_url"],
            "intent": r["intent"],
            "position": r["position"],
            "pinned": r["pinned"],
            "origin": r["origin"],
            "updated_at": r["updated_at"],
            "updated_by": r["updated_by"],
        }
        for r in rows
    ]


def _build_settings(conn) -> list[dict[str, Any]]:
    rows = conn.execute(
        "SELECT s.*, p.feed_url FROM podcast_settings s "
        "JOIN podcasts p ON p.id = s.podcast_id WHERE s.updated_at > 0"
    )
    # Driven off SETTING_KEYS rather than a hand-written list: a new per-podcast
    # setting must not have to remember to add itself here to be replicated.
    return [
        {
            "feed": r["feed_url"],
            **{key: r[key] for key in repo.SETTING_KEYS},
            "updated_at": r["updated_at"],
            "updated_by": r["updated_by"],
        }
        for r in rows
    ]


def _build_positions(conn) -> list[dict[str, Any]]:
    rows = conn.execute(
        "SELECT e.guid, e.media_url, e.position_secs, e.total_secs, "
        "e.position_updated_at, p.feed_url FROM episodes e "
        "JOIN podcasts p ON p.id = e.podcast_id "
        "WHERE e.position_secs > 0 AND e.position_updated_at > 0 "
        "ORDER BY e.position_updated_at DESC LIMIT ?",
        (POSITION_LIMIT,),
    )
    return [
        {
            "feed": r["feed_url"],
            "guid": r["guid"],
            "media": r["media_url"],
            "position": r["position_secs"],
            "total": r["total_secs"],
            "updated_at": r["position_updated_at"],
        }
        for r in rows
    ]


def position_message(episode: Episode) -> dict[str, Any] | None:
    """A single live position push — the same record shape as a snapshot's,
    so the receiving side has only one code path to maintain."""
    podcast = repo.podcast_by_id(episode.podcast_id)
    if podcast is None:
        return None
    return {
        "type": "position",
        "v": SNAPSHOT_VERSION,
        "record": {
            "feed": podcast.feed_url,
            "guid": episode.guid,
            "media": episode.media_url,
            "position": episode.position_secs,
            "total": episode.total_secs,
            "updated_at": episode.position_updated_at or int(time.time()),
        },
    }


# ---------------------------------------------------------------- resolving


def resolve_episode(record: dict[str, Any]) -> Episode | None:
    """Find the local row for a peer's episode reference.

    GUID first: it is stable across devices, while enclosure URLs are rewritten
    per-listener by ad-injecting CDNs. The URL ladder is the fallback for feeds
    that ship no usable GUID.
    """
    podcast = matching.match_podcast(record.get("feed") or "")
    if podcast is None:
        return None
    guid = record.get("guid")
    if guid:
        row = db.connection().execute(
            "SELECT * FROM episodes WHERE podcast_id=? AND guid=?", (podcast.id, guid)
        ).fetchone()
        if row:
            return from_row(Episode, row)
    media = record.get("media") or ""
    return matching.match_episode(podcast, media) if media else None


def _newer(record: dict[str, Any], local_at: int, local_by: str | None) -> bool:
    """Last-writer-wins, with a deterministic tie-break so both ends of a
    conflict reach the same answer without another round trip."""
    remote_at = int(record.get("updated_at") or 0)
    if remote_at != local_at:
        return remote_at > local_at
    return (record.get("updated_by") or "") > (local_by or "")


# ---------------------------------------------------------------- merging


def merge_snapshot(snapshot: dict[str, Any]) -> dict[str, int]:
    """Apply a peer's snapshot. Returns per-section counts of what changed.

    Resolution happens before the transaction opens: the URL matching ladder
    records aliases as a side effect, and those writes must not ride inside —
    or be rolled back with — the merge itself.
    """
    if int(snapshot.get("v") or 0) != SNAPSHOT_VERSION:
        raise ValueError(f"unsupported snapshot version {snapshot.get('v')!r}")

    positions = [
        (rec, ep)
        for rec in snapshot.get("positions") or []
        if (ep := resolve_episode(rec)) is not None
    ]
    intents = [
        (rec, ep)
        for rec in snapshot.get("intents") or []
        if (ep := resolve_episode(rec)) is not None
    ]
    settings = [
        (rec, p)
        for rec in snapshot.get("settings") or []
        if (p := matching.match_podcast(rec.get("feed") or "")) is not None
    ]

    counts = {"positions": 0, "intents": 0, "settings": 0}
    with db.transaction() as conn:
        for rec, ep in positions:
            counts["positions"] += _apply_position(conn, rec, ep)
        for rec, ep in intents:
            counts["intents"] += _apply_intent(conn, rec, ep)
        for rec, podcast in settings:
            counts["settings"] += _apply_settings(conn, rec, podcast.id)
        if counts["intents"]:
            _renumber_queue(conn)
    return counts


def apply_position_message(message: dict[str, Any]) -> bool:
    """Handle a live position push. True if it moved our copy forward."""
    record = message.get("record") or {}
    episode = resolve_episode(record)
    if episode is None:
        return False
    with db.transaction() as conn:
        return bool(_apply_position(conn, record, episode))


def _apply_position(conn, record: dict[str, Any], episode: Episode) -> int:
    remote_at = int(record.get("updated_at") or 0)
    if remote_at <= episode.position_updated_at:
        return 0
    position = int(record.get("position") or 0)
    total = int(record.get("total") or 0)
    if position <= 0:
        return 0
    # Episode *state* is left alone on purpose: gpodder.net already carries
    # played/new between devices, and this path must not race it. A position
    # at the end of the file is enough for reconcile() to drop the episode
    # from the queue on its own.
    if total > 0:
        conn.execute(
            "UPDATE episodes SET position_secs=?, total_secs=?, position_updated_at=? "
            "WHERE id=?",
            (position, total, remote_at, episode.id),
        )
    else:
        conn.execute(
            "UPDATE episodes SET position_secs=?, position_updated_at=? WHERE id=?",
            (position, remote_at, episode.id),
        )
    return 1


def _apply_intent(conn, record: dict[str, Any], episode: Episode) -> int:
    intent = record.get("intent")
    if intent not in ("queued", "excluded"):
        return 0
    local = conn.execute(
        "SELECT intent, updated_at, updated_by FROM queue_intent WHERE episode_id=?",
        (episode.id,),
    ).fetchone()
    if local is not None and not _newer(record, local["updated_at"], local["updated_by"]):
        return 0

    repo.record_intent(
        conn,
        episode.id,
        intent,
        position=int(record.get("position") or 0),
        pinned=int(record.get("pinned") or 0),
        origin=str(record.get("origin") or "manual"),
        updated_at=int(record.get("updated_at") or 0),
        updated_by=str(record.get("updated_by") or ""),
    )

    if intent == "queued":
        conn.execute("DELETE FROM queue_exclusions WHERE episode_id=?", (episode.id,))
        conn.execute(
            "INSERT INTO queue(episode_id, position, origin, pinned, added_at) "
            "VALUES(?,?,?,?,?) ON CONFLICT(episode_id) DO UPDATE SET "
            "position=excluded.position, origin=excluded.origin, pinned=excluded.pinned",
            (
                episode.id,
                int(record.get("position") or 0),
                str(record.get("origin") or "manual"),
                int(record.get("pinned") or 0),
                int(time.time()),
            ),
        )
    else:
        conn.execute("DELETE FROM queue WHERE episode_id=?", (episode.id,))
        conn.execute(
            "INSERT OR REPLACE INTO queue_exclusions(episode_id, removed_at) VALUES(?,?)",
            (episode.id, int(record.get("updated_at") or int(time.time()))),
        )
    return 1


def _apply_settings(conn, record: dict[str, Any], podcast_id: int) -> int:
    local = conn.execute(
        "SELECT updated_at, updated_by FROM podcast_settings WHERE podcast_id=?",
        (podcast_id,),
    ).fetchone()
    if local is not None and not _newer(record, local["updated_at"], local["updated_by"]):
        return 0
    values = [record.get(k) for k in repo.SETTING_KEYS]
    assignments = ", ".join(f"{k}=excluded.{k}" for k in repo.SETTING_KEYS)
    conn.execute(
        f"INSERT INTO podcast_settings(podcast_id, {', '.join(repo.SETTING_KEYS)}, "
        f"updated_at, updated_by) VALUES({', '.join('?' * (len(repo.SETTING_KEYS) + 3))}) "
        f"ON CONFLICT(podcast_id) DO UPDATE SET {assignments}, "
        f"updated_at=excluded.updated_at, updated_by=excluded.updated_by",
        (
            podcast_id,
            *values,
            int(record.get("updated_at") or 0),
            str(record.get("updated_by") or ""),
        ),
    )
    return 1


def _renumber_queue(conn) -> None:
    """Restore the 1024-gap scheme after a merge.

    Positions arrive from two devices that numbered independently, so they can
    collide. The tie-break must be a value both ends agree on: episode_id is a
    local rowid, assigned in feed-fetch order, so two devices holding the same
    episode break the same tie differently and diverge for good. (feed, guid)
    is the same identity resolve_episode() addresses peers by, so it is stable
    everywhere; media_url is the fallback for feeds with no usable GUID.
    """
    ids = [
        r["episode_id"]
        for r in conn.execute(
            "SELECT q.episode_id FROM queue q "
            "JOIN episodes e ON e.id = q.episode_id "
            "JOIN podcasts p ON p.id = e.podcast_id "
            "ORDER BY q.position, p.feed_url, COALESCE(e.guid, e.media_url)"
        )
    ]
    for i, eid in enumerate(ids, start=1):
        conn.execute("UPDATE queue SET position=? WHERE episode_id=?", (i * GAP, eid))
