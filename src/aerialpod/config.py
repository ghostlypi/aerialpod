"""Paths and settings accessors.

Data-relevant settings live in the SQLite app_state table (see db.repo);
pure-UI state (window geometry) lives in QSettings. This module only knows
where files go.
"""

from __future__ import annotations

import os
from pathlib import Path

from platformdirs import user_cache_dir, user_data_dir

APP_NAME = "aerialpod"


def data_dir() -> Path:
    d = Path(os.environ.get("AERIALPOD_DATA_DIR") or user_data_dir(APP_NAME))
    d.mkdir(parents=True, exist_ok=True)
    return d


def cache_dir() -> Path:
    d = Path(user_cache_dir(APP_NAME))
    d.mkdir(parents=True, exist_ok=True)
    return d


def db_path() -> Path:
    return data_dir() / "aerialpod.db"


def media_dir() -> Path:
    d = data_dir() / "media"
    d.mkdir(parents=True, exist_ok=True)
    return d


def image_cache_dir() -> Path:
    d = cache_dir() / "images"
    d.mkdir(parents=True, exist_ok=True)
    return d
