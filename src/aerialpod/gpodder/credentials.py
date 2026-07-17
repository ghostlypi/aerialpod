"""gpodder.net credentials in the Secret Service (GNOME Keyring),
with a plaintext-file fallback (warned) for keyring-less setups.
"""

from __future__ import annotations

import json
import logging
import stat

from ..config import data_dir

log = logging.getLogger(__name__)

_ATTRS = {"application": "aerialpod", "purpose": "gpodder"}
_FALLBACK = data_dir() / "credentials.json"


def save(username: str, password: str) -> None:
    try:
        import secretstorage

        conn = secretstorage.dbus_init()
        coll = secretstorage.get_default_collection(conn)
        if coll.is_locked():
            coll.unlock()
        for item in coll.search_items(_ATTRS):
            item.delete()
        coll.create_item(
            "AerialPod gpodder.net", _ATTRS,
            json.dumps({"username": username, "password": password}).encode(),
        )
        _FALLBACK.unlink(missing_ok=True)
        return
    except Exception as exc:  # noqa: BLE001 — any keyring failure falls back
        log.warning("keyring unavailable (%s); storing credentials in %s", exc, _FALLBACK)
    _FALLBACK.write_text(json.dumps({"username": username, "password": password}))
    _FALLBACK.chmod(stat.S_IRUSR | stat.S_IWUSR)


def load() -> tuple[str, str] | None:
    try:
        import secretstorage

        conn = secretstorage.dbus_init()
        coll = secretstorage.get_default_collection(conn)
        if coll.is_locked():
            coll.unlock()
        for item in coll.search_items(_ATTRS):
            data = json.loads(item.get_secret().decode())
            return data["username"], data["password"]
    except Exception as exc:  # noqa: BLE001
        log.debug("keyring load failed: %s", exc)
    if _FALLBACK.exists():
        data = json.loads(_FALLBACK.read_text())
        return data["username"], data["password"]
    return None


def clear() -> None:
    try:
        import secretstorage

        conn = secretstorage.dbus_init()
        coll = secretstorage.get_default_collection(conn)
        for item in coll.search_items(_ATTRS):
            item.delete()
    except Exception:  # noqa: BLE001
        pass
    _FALLBACK.unlink(missing_ok=True)
