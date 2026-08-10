"""gpodder.net credentials in the Secret Service (GNOME Keyring),
with a plaintext-file fallback (warned) for keyring-less setups.
"""

from __future__ import annotations

import logging
from pathlib import Path

from .. import secretstore
from ..config import data_dir

log = logging.getLogger(__name__)

_ATTRS = {"application": "aerialpod", "purpose": "gpodder"}
_LABEL = "AerialPod gpodder.net"


def _fallback() -> Path:
    return data_dir() / "credentials.json"


def save(username: str, password: str) -> None:
    secretstore.save(_ATTRS, _LABEL, _fallback(),
                     {"username": username, "password": password})


def load() -> tuple[str, str] | None:
    data = secretstore.load(_ATTRS, _fallback())
    if not data:
        return None
    username, password = data.get("username"), data.get("password")
    if username is None or password is None:
        return None
    return username, password


def clear() -> None:
    secretstore.clear(_ATTRS, _fallback())
