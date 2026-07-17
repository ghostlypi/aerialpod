"""SQLite access: one connection per thread, WAL mode, explicit transactions.

Rule (see plan): every thread gets its own connection via connection();
multi-statement mutations always run inside `with transaction():`.
"""

from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path

from .. import config
from . import migrations

_local = threading.local()
_db_path: Path | None = None


def init(path: Path | None = None) -> None:
    """Set the database path and run migrations. Call once at startup."""
    global _db_path
    _db_path = path or config.db_path()
    migrations.migrate(connection())


def connection() -> sqlite3.Connection:
    """The calling thread's connection (created on first use)."""
    conn = getattr(_local, "conn", None)
    if conn is None:
        assert _db_path is not None, "db.init() must be called first"
        # 30s busy timeout: the sync thread's action-apply writes contend with
        # the concurrent feed-refresh burst on first sync; 5s proved too short.
        conn = sqlite3.connect(_db_path, timeout=30.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=30000")
        conn.execute("PRAGMA foreign_keys=ON")
        _local.conn = conn
    return conn


@contextmanager
def transaction():
    """Explicit transaction on this thread's connection."""
    conn = connection()
    try:
        conn.execute("BEGIN IMMEDIATE")
        yield conn
        conn.commit()
    except BaseException:
        conn.rollback()
        raise


def close_thread_connection() -> None:
    conn = getattr(_local, "conn", None)
    if conn is not None:
        conn.close()
        _local.conn = None
