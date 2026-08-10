"""Small secrets in the Secret Service (GNOME Keyring), or a chmod-600 file.

Two things need this — the gpodder.net account and the LAN pairing key — and
neither should carry its own copy of the keyring dance. Callers supply the
search attributes that identify their secret and the path to fall back to.
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)


def _collection():
    """The unlocked default collection, or None when there is no keyring.

    Every failure mode here — no D-Bus, no Secret Service, a locked collection
    the user declined to unlock — lands in the same place: the file fallback.
    """
    try:
        import secretstorage

        conn = secretstorage.dbus_init()
        collection = secretstorage.get_default_collection(conn)
        if collection.is_locked():
            collection.unlock()
        return collection
    except Exception as exc:  # noqa: BLE001 — any keyring failure falls back
        log.debug("keyring unavailable: %s", exc)
        return None


def save(attrs: dict[str, str], label: str, fallback: Path, payload: dict[str, Any]) -> None:
    collection = _collection()
    if collection is not None:
        try:
            for item in collection.search_items(attrs):
                item.delete()
            collection.create_item(label, attrs, json.dumps(payload).encode())
            fallback.unlink(missing_ok=True)
            return
        except Exception as exc:  # noqa: BLE001
            log.warning("keyring write failed (%s); storing in %s", exc, fallback)

    # Created 0600 rather than chmod'ed afterwards: a secret must never exist
    # on disk world-readable, not even for the moment in between.
    fd = os.open(fallback, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as handle:
        json.dump(payload, handle)


def load(attrs: dict[str, str], fallback: Path) -> dict[str, Any] | None:
    collection = _collection()
    if collection is not None:
        try:
            for item in collection.search_items(attrs):
                return json.loads(item.get_secret().decode())
        except Exception as exc:  # noqa: BLE001
            log.debug("keyring read failed: %s", exc)
    if fallback.exists():
        try:
            return json.loads(fallback.read_text())
        except ValueError:
            log.warning("%s is not readable as JSON — ignoring it", fallback)
    return None


def clear(attrs: dict[str, str], fallback: Path) -> None:
    collection = _collection()
    if collection is not None:
        try:
            for item in collection.search_items(attrs):
                item.delete()
        except Exception as exc:  # noqa: BLE001
            log.debug("keyring delete failed: %s", exc)
    fallback.unlink(missing_ok=True)
