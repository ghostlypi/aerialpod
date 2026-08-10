"""Numbered SQL migrations, tracked via PRAGMA user_version."""

from __future__ import annotations

import sqlite3

MIGRATIONS: list[str] = [
    # 1 — initial schema
    """
    CREATE TABLE podcasts (
      id INTEGER PRIMARY KEY,
      feed_url TEXT NOT NULL UNIQUE,
      title TEXT,
      description TEXT,
      image_url TEXT,
      website TEXT,
      subscribed INTEGER NOT NULL DEFAULT 1,
      sync_state TEXT NOT NULL DEFAULT 'clean',  -- 'clean'|'add_pending'|'remove_pending'
      etag TEXT,
      http_last_modified TEXT,
      last_refresh INTEGER,
      added_at INTEGER
    );

    CREATE TABLE podcast_settings (
      podcast_id INTEGER PRIMARY KEY REFERENCES podcasts(id) ON DELETE CASCADE,
      custom_title TEXT,
      playback_speed REAL,
      skip_intro_secs INTEGER,
      skip_outro_secs INTEGER,
      auto_add_to_queue INTEGER  -- NULL = inherit global default
    );

    CREATE TABLE episodes (
      id INTEGER PRIMARY KEY,
      podcast_id INTEGER NOT NULL REFERENCES podcasts(id) ON DELETE CASCADE,
      guid TEXT,
      media_url TEXT NOT NULL,
      title TEXT,
      description TEXT,
      pub_date INTEGER,
      duration_secs INTEGER,
      mime TEXT,
      file_size INTEGER,
      image_url TEXT,
      state TEXT NOT NULL DEFAULT 'new',  -- 'new'|'inbox'|'played'|'archived'
      position_secs INTEGER NOT NULL DEFAULT 0,
      total_secs INTEGER NOT NULL DEFAULT 0,
      position_updated_at INTEGER NOT NULL DEFAULT 0,  -- action timestamp, UTC epoch
      downloaded_path TEXT,
      download_state TEXT NOT NULL DEFAULT 'none',  -- 'none'|'downloading'|'done'
      keep_download INTEGER NOT NULL DEFAULT 0,
      UNIQUE(podcast_id, guid)
    );
    CREATE INDEX idx_episodes_media_url ON episodes(media_url);
    CREATE INDEX idx_episodes_podcast ON episodes(podcast_id, pub_date);

    CREATE TABLE episode_url_aliases (
      episode_id INTEGER NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
      url TEXT NOT NULL PRIMARY KEY
    );

    CREATE TABLE action_outbox (
      id INTEGER PRIMARY KEY,
      podcast_url TEXT NOT NULL,
      episode_url TEXT NOT NULL,
      action TEXT NOT NULL,  -- 'play'|'download'|'delete'|'new'
      timestamp TEXT NOT NULL,  -- ISO8601 UTC
      started INTEGER,
      position INTEGER,
      total INTEGER
    );

    CREATE TABLE queue (
      episode_id INTEGER PRIMARY KEY REFERENCES episodes(id) ON DELETE CASCADE,
      position INTEGER NOT NULL,  -- 1024-gap scheme
      origin TEXT NOT NULL DEFAULT 'auto',  -- 'auto'|'manual'
      pinned INTEGER NOT NULL DEFAULT 0,
      added_at INTEGER NOT NULL
    );

    CREATE TABLE queue_exclusions (
      episode_id INTEGER PRIMARY KEY REFERENCES episodes(id) ON DELETE CASCADE,
      removed_at INTEGER NOT NULL
    );

    CREATE TABLE unmatched_actions (
      id INTEGER PRIMARY KEY,
      podcast_url TEXT NOT NULL,
      episode_url TEXT NOT NULL,
      action TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      payload TEXT,  -- raw action JSON for the inspector
      received_at INTEGER NOT NULL
    );

    CREATE TABLE app_state (
      key TEXT PRIMARY KEY,
      value TEXT
    );
    """,
    # 2 — LAN sync: replicated user intent + peer bookkeeping
    #
    # `queue` and `queue_exclusions` stay exactly what they are: local derived
    # truth, rebuilt by QueueManager.reconcile() from episode state. What
    # actually travels between peers is the *user's decisions* — "I queued
    # this", "I threw this out", "this is the order I want" — because that is
    # precisely what gpodder.net's protocol has no way to express.
    #
    # Keeping intent in its own table (rather than stamping the queue rows)
    # buys two things: a derived reshuffle never looks like an opinion worth
    # pushing at a peer, and un-excluding an episode is a *record* rather than
    # a deletion — otherwise a peer's older exclusion would win the merge and
    # silently re-exclude what the user just restored.
    """
    CREATE TABLE queue_intent (
      episode_id INTEGER PRIMARY KEY REFERENCES episodes(id) ON DELETE CASCADE,
      intent TEXT NOT NULL,                    -- 'queued' | 'excluded'
      position INTEGER NOT NULL DEFAULT 0,
      pinned INTEGER NOT NULL DEFAULT 0,
      origin TEXT NOT NULL DEFAULT 'manual',
      updated_at INTEGER NOT NULL,
      updated_by TEXT NOT NULL
    );

    ALTER TABLE podcast_settings ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;
    ALTER TABLE podcast_settings ADD COLUMN updated_by TEXT;

    -- Peers we have successfully authenticated with at least once. Keyed by
    -- the peer's LAN device id, which we only learn *after* the handshake.
    CREATE TABLE lan_peers (
      device_id TEXT PRIMARY KEY,
      caption TEXT,
      address TEXT,
      port INTEGER NOT NULL,
      last_seen INTEGER NOT NULL DEFAULT 0
    );

    -- Addresses the user pinned by hand. Separate table because a manual
    -- entry exists before any device id is known — this is the escape hatch
    -- for WireGuard /32 setups where there is no subnet to sweep.
    CREATE TABLE lan_manual_peers (
      address TEXT NOT NULL,
      port INTEGER NOT NULL,
      PRIMARY KEY (address, port)
    );
    """,
    # 3 — where a podcast's new episodes enter the queue.
    #
    # For a daily show the bottom of the queue is the wrong end: yesterday's
    # news is not what you want to hear first. NULL inherits the global
    # default; 'front' lands new episodes just under whatever is playing.
    """
    ALTER TABLE podcast_settings ADD COLUMN auto_queue_position TEXT;
    """,
]


def migrate(conn: sqlite3.Connection) -> None:
    version = conn.execute("PRAGMA user_version").fetchone()[0]
    for i, sql in enumerate(MIGRATIONS[version:], start=version + 1):
        conn.executescript(sql)
        conn.execute(f"PRAGMA user_version = {i}")
        conn.commit()
