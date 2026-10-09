"""Typed query functions. All reads use the calling thread's connection;
multi-statement writes happen inside db.transaction() at the call site or here.
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

from . import connection, transaction
from .models import Episode, Podcast, QueueItem, from_row

# ---------------------------------------------------------------- app_state

DEFAULTS: dict[str, Any] = {
    "global_speed": 1.0,
    "volume": 1.0,  # linear QAudioOutput volume, app-level (PipeWire stream)
    "skip_fwd_secs": 30,
    "skip_back_secs": 10,
    "download_ahead_n": 1,  # older integer form of download_ahead, still written
    "download_ahead": None,  # '0'|'1'|'5'|'1/4'|'1/3'|'1/2'|'all'; None = download_ahead_n
    "auto_add_to_queue": True,
    "auto_queue_position": "back",  # 'front' puts new episodes under the playing one
    "theme_mode": "system",  # 'system'|'light'|'dark'
    "accent": "#3584e4",
    "home_sections": ["queue", "continue", "inbox", "subscriptions"],
    "speed_presets": [1.0, 1.25, 1.5, 1.75, 2.0],
    "sync_interval_mins": 30,
    "media_next_action": "seek",  # keyboard/MPRIS ⏭⏮: 'seek' (ad-skip) | 'episode'
    "audio_device_mode": "follow_default",  # or 'pinned'
    "audio_device_id": None,
    "audio_device_description": None,
    "lan_sync_enabled": True,
    "lan_port": 47722,
    "lan_scan_subnets": True,  # unicast sweep of our own subnets (see lan.discovery)
    # None = on wherever the detector toolchain is installed (ads.detector)
    "ads_detect_enabled": None,
    "ads_skip_enabled": True,  # skip detected ads during playback, on this device
    "ads_paused": False,  # the settings page's pause button; this device only
    "ads_model": None,  # key into ads.spans.MODELS; None = its DEFAULT_MODEL
}


def lan_device_id() -> str:
    """Stable identity for this install on the LAN mesh.

    Deliberately not the gpodder device id: that one is user-visible on
    gpodder.net and can be renamed there, while this must stay stable for
    peer bookkeeping and last-writer-wins tie-breaks.
    """
    did = get_state("lan_device_id")
    if not did:
        import uuid

        did = uuid.uuid4().hex
        set_state("lan_device_id", did)
    return did


def get_state(key: str, default: Any = None) -> Any:
    row = connection().execute("SELECT value FROM app_state WHERE key=?", (key,)).fetchone()
    if row is None:
        return DEFAULTS.get(key, default) if default is None else default
    return json.loads(row["value"])


def set_state(key: str, value: Any) -> None:
    connection().execute(
        "INSERT INTO app_state(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, json.dumps(value)),
    )
    connection().commit()


# ---------------------------------------------------------------- podcasts


def all_podcasts(subscribed_only: bool = True) -> list[Podcast]:
    sql = "SELECT * FROM podcasts"
    if subscribed_only:
        sql += " WHERE subscribed=1"
    sql += " ORDER BY title COLLATE NOCASE"
    return [from_row(Podcast, r) for r in connection().execute(sql)]


def podcast_by_id(pid: int) -> Podcast | None:
    row = connection().execute("SELECT * FROM podcasts WHERE id=?", (pid,)).fetchone()
    return from_row(Podcast, row) if row else None


def podcast_by_feed_url(url: str) -> Podcast | None:
    row = connection().execute("SELECT * FROM podcasts WHERE feed_url=?", (url,)).fetchone()
    return from_row(Podcast, row) if row else None


def upsert_podcast(feed_url: str, *, sync_state: str = "add_pending", subscribed: int = 1) -> int:
    """Insert (or resubscribe) a podcast; returns its id."""
    with transaction() as conn:
        row = conn.execute("SELECT id FROM podcasts WHERE feed_url=?", (feed_url,)).fetchone()
        if row:
            conn.execute(
                "UPDATE podcasts SET subscribed=?, sync_state=? WHERE id=?",
                (subscribed, sync_state, row["id"]),
            )
            return row["id"]
        cur = conn.execute(
            "INSERT INTO podcasts(feed_url, sync_state, subscribed, added_at) VALUES(?,?,?,?)",
            (feed_url, sync_state, subscribed, int(time.time())),
        )
        return cur.lastrowid


def update_podcast_meta(pid: int, **cols: Any) -> None:
    if not cols:
        return
    sets = ", ".join(f"{k}=?" for k in cols)
    connection().execute(f"UPDATE podcasts SET {sets} WHERE id=?", (*cols.values(), pid))
    connection().commit()


def unsubscribe_podcast(pid: int) -> None:
    with transaction() as conn:
        conn.execute(
            "UPDATE podcasts SET subscribed=0, sync_state='remove_pending' WHERE id=?", (pid,)
        )
        conn.execute(
            "DELETE FROM queue WHERE episode_id IN (SELECT id FROM episodes WHERE podcast_id=?)",
            (pid,),
        )


def rewrite_feed_url(old: str, new: str) -> None:
    """Apply a gpodder update_urls rewrite. Keeps action matching working."""
    connection().execute("UPDATE podcasts SET feed_url=? WHERE feed_url=?", (new, old))
    connection().commit()


# ---------------------------------------------------------------- podcast settings


def podcast_settings(pid: int) -> dict[str, Any]:
    row = connection().execute(
        "SELECT * FROM podcast_settings WHERE podcast_id=?", (pid,)
    ).fetchone()
    return dict(row) if row else {}


SETTING_KEYS = (
    "custom_title",
    "playback_speed",
    "skip_intro_secs",
    "skip_outro_secs",
    "auto_add_to_queue",
    "auto_queue_position",
)


def set_podcast_setting(pid: int, key: str, value: Any) -> None:
    assert key in SETTING_KEYS
    connection().execute(
        f"INSERT INTO podcast_settings(podcast_id, {key}, updated_at, updated_by) "
        f"VALUES(?, ?, ?, ?) "
        f"ON CONFLICT(podcast_id) DO UPDATE SET {key}=excluded.{key}, "
        f"updated_at=excluded.updated_at, updated_by=excluded.updated_by",
        (pid, value, int(time.time()), lan_device_id()),
    )
    connection().commit()


def effective_auto_add(pid: int) -> bool:
    s = podcast_settings(pid).get("auto_add_to_queue")
    if s is None:
        return bool(get_state("auto_add_to_queue"))
    return bool(s)


def effective_queue_position(pid: int) -> str:
    """'front' or 'back' — where this podcast's new episodes enter the queue."""
    value = podcast_settings(pid).get("auto_queue_position")
    if value not in ("front", "back"):
        value = get_state("auto_queue_position")
    return "front" if value == "front" else "back"


def effective_speed(pid: int) -> float:
    s = podcast_settings(pid).get("playback_speed")
    return float(s) if s else float(get_state("global_speed"))


def display_title(p: Podcast) -> str:
    custom = podcast_settings(p.id).get("custom_title")
    return custom or p.title or p.feed_url


# ---------------------------------------------------------------- episodes


def episode_by_id(eid: int) -> Episode | None:
    row = connection().execute("SELECT * FROM episodes WHERE id=?", (eid,)).fetchone()
    return from_row(Episode, row) if row else None


def episodes_for_podcast(pid: int, limit: int = 500) -> list[Episode]:
    rows = connection().execute(
        "SELECT * FROM episodes WHERE podcast_id=? ORDER BY pub_date DESC LIMIT ?",
        (pid, limit),
    )
    return [from_row(Episode, r) for r in rows]


def update_episode(eid: int, **cols: Any) -> None:
    if not cols:
        return
    sets = ", ".join(f"{k}=?" for k in cols)
    connection().execute(f"UPDATE episodes SET {sets} WHERE id=?", (*cols.values(), eid))
    connection().commit()


def add_alias(eid: int, url: str) -> None:
    connection().execute(
        "INSERT OR IGNORE INTO episode_url_aliases(episode_id, url) VALUES(?, ?)", (eid, url)
    )
    connection().commit()


def in_progress_episodes(limit: int = 50) -> list[Episode]:
    rows = connection().execute(
        "SELECT e.* FROM episodes e JOIN podcasts p ON p.id=e.podcast_id "
        "WHERE p.subscribed=1 AND e.state != 'played' AND e.position_secs > 0 "
        "ORDER BY e.position_updated_at DESC LIMIT ?",
        (limit,),
    )
    return [from_row(Episode, r) for r in rows]


def inbox_episodes(limit: int = 100) -> list[Episode]:
    rows = connection().execute(
        "SELECT e.* FROM episodes e JOIN podcasts p ON p.id=e.podcast_id "
        "WHERE p.subscribed=1 AND e.state IN ('new','inbox') "
        "ORDER BY e.pub_date DESC LIMIT ?",
        (limit,),
    )
    return [from_row(Episode, r) for r in rows]


# ---------------------------------------------------------------- queue

GAP = 1024


def queue_episode_ids() -> set[int]:
    """One query for 'is this episode queued?' checks across a whole list."""
    return {r[0] for r in connection().execute("SELECT episode_id FROM queue")}


def podcast_display_info() -> dict[int, tuple[str, str | None]]:
    """Bulk {podcast_id: (display_title, image_url)} — avoids per-row queries
    when rendering episode lists."""
    rows = connection().execute(
        "SELECT p.id, COALESCE(s.custom_title, p.title, p.feed_url) AS title, p.image_url "
        "FROM podcasts p LEFT JOIN podcast_settings s ON s.podcast_id = p.id"
    )
    return {r["id"]: (r["title"], r["image_url"]) for r in rows}


def queue_items() -> list[QueueItem]:
    rows = connection().execute("SELECT * FROM queue ORDER BY position")
    return [from_row(QueueItem, r) for r in rows]


def queue_episodes() -> list[Episode]:
    rows = connection().execute(
        "SELECT e.* FROM queue q JOIN episodes e ON e.id=q.episode_id ORDER BY q.position"
    )
    return [from_row(Episode, r) for r in rows]


# ---------------------------------------------------------------- queue intent
#
# The replicated half of the queue: what the *user* decided, as opposed to
# what reconcile() derived. Written by QueueManager's user-facing ops only.


def record_intent(
    conn,
    episode_id: int,
    intent: str,
    *,
    position: int = 0,
    pinned: int = 0,
    origin: str = "manual",
    updated_at: int | None = None,
    updated_by: str | None = None,
) -> None:
    """Upsert one intent row. Takes an explicit connection so callers can fold
    it into the transaction that changes the queue itself."""
    assert intent in ("queued", "excluded")
    conn.execute(
        "INSERT INTO queue_intent(episode_id, intent, position, pinned, origin, "
        "updated_at, updated_by) VALUES(?,?,?,?,?,?,?) "
        "ON CONFLICT(episode_id) DO UPDATE SET intent=excluded.intent, "
        "position=excluded.position, pinned=excluded.pinned, origin=excluded.origin, "
        "updated_at=excluded.updated_at, updated_by=excluded.updated_by",
        (
            episode_id,
            intent,
            position,
            pinned,
            origin,
            updated_at if updated_at is not None else int(time.time()),
            updated_by or lan_device_id(),
        ),
    )


def queue_intents() -> list[dict[str, Any]]:
    rows = connection().execute("SELECT * FROM queue_intent ORDER BY position")
    return [dict(r) for r in rows]


def intent_for(eid: int) -> dict[str, Any] | None:
    row = connection().execute(
        "SELECT * FROM queue_intent WHERE episode_id=?", (eid,)
    ).fetchone()
    return dict(row) if row else None


def drop_intent(conn, episode_id: int) -> None:
    conn.execute("DELETE FROM queue_intent WHERE episode_id=?", (episode_id,))


def prune_intents(max_age_secs: int = 90 * 86400) -> int:
    """Drop intents that can no longer change anything: the episode is played
    and the decision is old enough that no peer is still catching up."""
    cutoff = int(time.time()) - max_age_secs
    with transaction() as conn:
        cur = conn.execute(
            "DELETE FROM queue_intent WHERE updated_at < ? AND episode_id IN "
            "(SELECT id FROM episodes WHERE state='played')",
            (cutoff,),
        )
        return cur.rowcount


def is_excluded(eid: int) -> bool:
    return (
        connection()
        .execute("SELECT 1 FROM queue_exclusions WHERE episode_id=?", (eid,))
        .fetchone()
        is not None
    )


# ---------------------------------------------------------------- ad segments
#
# Spans are [start_ms, end_ms, confidence] lists. The whole row is replaced at
# once, by a detection run here or a newer row from a peer (lan.state).


def ad_segments(eid: int) -> dict[str, Any] | None:
    row = connection().execute(
        "SELECT * FROM ad_segments WHERE episode_id=?", (eid,)
    ).fetchone()
    if row is None:
        return None
    return {
        **dict(row),
        "spans": json.loads(row["spans"]),
        "anchors": json.loads(row["anchors"]) if row["anchors"] else None,
        "audio": ({"bytes": row["audio_bytes"], "sha256": row["audio_sha256"]}
                  if row["audio_sha256"] else None),
    }


def set_ad_segments(
    conn,
    episode_id: int,
    duration_ms: int,
    spans: list[list[float]],
    *,
    anchors: list[dict] | None = None,
    audio: dict | None = None,
    model: str | None = None,
    updated_at: int | None = None,
    updated_by: str | None = None,
) -> None:
    conn.execute(
        "INSERT INTO ad_segments(episode_id, duration_ms, spans, anchors, audio_bytes, "
        "audio_sha256, model, updated_at, updated_by) VALUES(?,?,?,?,?,?,?,?,?) "
        "ON CONFLICT(episode_id) DO UPDATE "
        "SET duration_ms=excluded.duration_ms, spans=excluded.spans, "
        "anchors=excluded.anchors, audio_bytes=excluded.audio_bytes, "
        "audio_sha256=excluded.audio_sha256, model=excluded.model, "
        "updated_at=excluded.updated_at, updated_by=excluded.updated_by",
        (
            episode_id,
            int(duration_ms),
            json.dumps(spans),
            json.dumps(anchors) if anchors is not None else None,
            (audio or {}).get("bytes"),
            (audio or {}).get("sha256"),
            model,
            updated_at if updated_at is not None else int(time.time()),
            updated_by or lan_device_id(),
        ),
    )


def kept_analysed_ids() -> set[int]:
    """Queued episodes whose analysed audio this device still holds.

    Kept past download-ahead so a peer can be handed the exact file the ads
    were found in (lan/files.py); evicted as usual once the episode leaves the
    queue. The size check catches a file that was evicted and later replaced
    by a fresh download — a different copy, with different ads.
    """
    rows = connection().execute(
        "SELECT a.episode_id, a.audio_bytes, e.downloaded_path FROM ad_segments a "
        "JOIN episodes e ON e.id = a.episode_id "
        "JOIN queue q ON q.episode_id = a.episode_id "
        "WHERE a.updated_by = ? AND a.audio_sha256 IS NOT NULL "
        "AND e.download_state = 'done'",
        (lan_device_id(),),
    )
    return {
        r["episode_id"] for r in rows
        if r["downloaded_path"] and Path(r["downloaded_path"]).is_file()
        and Path(r["downloaded_path"]).stat().st_size == r["audio_bytes"]
    }


def analysed_episode_ids(redo_own_except: set[str] | None = None) -> set[int]:
    """Episodes with a detection result. With `redo_own_except`, this device's
    own results stamped with any other model are left out, so they run again."""
    if redo_own_except is None:
        return {r[0] for r in connection().execute("SELECT episode_id FROM ad_segments")}
    return {
        r["episode_id"]
        for r in connection().execute("SELECT episode_id, model, updated_by FROM ad_segments")
        if r["updated_by"] != lan_device_id() or r["model"] in redo_own_except
    }


# ---------------------------------------------------------------- outbox


def enqueue_action(
    podcast_url: str,
    episode_url: str,
    action: str,
    timestamp: str,
    started: int | None = None,
    position: int | None = None,
    total: int | None = None,
) -> None:
    connection().execute(
        "INSERT INTO action_outbox(podcast_url, episode_url, action, timestamp, started, position, total) "
        "VALUES(?,?,?,?,?,?,?)",
        (podcast_url, episode_url, action, timestamp, started, position, total),
    )
    connection().commit()


def outbox_actions() -> list[dict[str, Any]]:
    rows = connection().execute("SELECT * FROM action_outbox ORDER BY id")
    return [dict(r) for r in rows]


def clear_outbox(upto_id: int) -> None:
    connection().execute("DELETE FROM action_outbox WHERE id<=?", (upto_id,))
    connection().commit()


# ---------------------------------------------------------------- unmatched


def log_unmatched(podcast_url: str, episode_url: str, action: str, timestamp: str, payload: str) -> None:
    connection().execute(
        "INSERT INTO unmatched_actions(podcast_url, episode_url, action, timestamp, payload, received_at) "
        "VALUES(?,?,?,?,?,?)",
        (podcast_url, episode_url, action, timestamp, payload, int(time.time())),
    )
    connection().commit()


def unmatched_count() -> int:
    return connection().execute("SELECT COUNT(*) FROM unmatched_actions").fetchone()[0]


# ---------------------------------------------------------------- LAN peers


def known_peers() -> list[dict[str, Any]]:
    """Peers we have authenticated with before, most recently seen first."""
    rows = connection().execute("SELECT * FROM lan_peers ORDER BY last_seen DESC")
    return [dict(r) for r in rows]


def remember_peer(device_id: str, caption: str, address: str, port: int) -> None:
    connection().execute(
        "INSERT INTO lan_peers(device_id, caption, address, port, last_seen) "
        "VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET "
        "caption=excluded.caption, address=excluded.address, port=excluded.port, "
        "last_seen=excluded.last_seen",
        (device_id, caption, address, port, int(time.time())),
    )
    connection().commit()


def forget_peer(device_id: str) -> None:
    connection().execute("DELETE FROM lan_peers WHERE device_id=?", (device_id,))
    connection().commit()


def manual_peers() -> list[tuple[str, int]]:
    rows = connection().execute(
        "SELECT address, port FROM lan_manual_peers ORDER BY address"
    )
    return [(r["address"], r["port"]) for r in rows]


def add_manual_peer(address: str, port: int) -> None:
    connection().execute(
        "INSERT OR IGNORE INTO lan_manual_peers(address, port) VALUES(?,?)",
        (address, port),
    )
    connection().commit()


def remove_manual_peer(address: str, port: int) -> None:
    connection().execute(
        "DELETE FROM lan_manual_peers WHERE address=? AND port=?", (address, port)
    )
    connection().commit()
