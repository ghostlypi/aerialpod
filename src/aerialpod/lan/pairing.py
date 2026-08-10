"""The shared secret that decides which devices are yours.

Peers used to be keyed off the gpodder.net password. That made the handshake
transcript — which is plaintext, by necessity, before any session key exists —
a verifier anyone could grind offline, and the prize was the account password
itself. A one-time pairing step removes the problem rather than pricing it up:
the secret here is 160 random bits, so a captured transcript is worth nothing
to guess against.

Each install generates its own secret on first use and shows it as a pairing
code. Entering another device's code adopts that device's secret, so both ends
converge on one key; pair a third device against either of them and all three
share it.

The code is base32 (RFC 4648: A–Z and 2–7, so no 0/O or 1/I ambiguity to begin
with) in groups of four — 160 bits lands on exactly 32 characters, which
divides evenly and reads back cleanly over the phone.
"""

from __future__ import annotations

import base64
import logging
import os
from pathlib import Path

from .. import secretstore
from ..config import data_dir
from . import crypto

log = logging.getLogger(__name__)

SECRET_LEN = 20  # 160 bits → exactly 32 base32 characters, no padding
GROUP = 4

_ATTRS = {"application": "aerialpod", "purpose": "lan-pairing"}
_LABEL = "AerialPod device pairing key"

# 0, 1, 8 and 9 are not in the base32 alphabet, so a digit that appears where a
# letter belongs is unambiguously a misread of one.
_CONFUSIONS = str.maketrans({"0": "O", "1": "I", "8": "B"})


def _fallback() -> Path:
    return data_dir() / "lan-pairing.json"


def secret() -> bytes:
    """This device's pairing secret, generated on first use."""
    stored = secretstore.load(_ATTRS, _fallback())
    if stored:
        raw = stored.get("secret")
        if isinstance(raw, str):
            try:
                value = bytes.fromhex(raw)
            except ValueError:
                log.warning("stored pairing secret is unreadable — generating a new one")
            else:
                if len(value) == SECRET_LEN:
                    return value
    return reset()


def reset() -> bytes:
    """Generate and store a fresh secret. Existing peers stop matching until
    they are paired again — which is the point of a 'forget my devices' button."""
    value = os.urandom(SECRET_LEN)
    _store(value)
    log.info("generated a new device pairing key")
    return value


def _store(value: bytes) -> None:
    secretstore.save(_ATTRS, _LABEL, _fallback(), {"secret": value.hex()})


def channel_key() -> bytes:
    """The root key the peer handshake runs on."""
    return crypto.channel_key(secret())


# ---------------------------------------------------------------- codes


def format_code(value: bytes) -> str:
    text = base64.b32encode(value).decode("ascii").rstrip("=")
    return "-".join(text[i : i + GROUP] for i in range(0, len(text), GROUP))


def parse_code(code: str) -> bytes:
    """Decode a pairing code a human typed. Raises ValueError if it isn't one.

    Deliberately forgiving about how it was transcribed — case, spacing and the
    usual digit-for-letter misreads — because the alternative is a user staring
    at 'invalid code' with no idea which character is wrong.
    """
    cleaned = "".join(ch for ch in code if ch.isalnum()).upper().translate(_CONFUSIONS)
    if not cleaned:
        raise ValueError("Enter the pairing code shown on your other device.")
    expected = len(base64.b32encode(b"\x00" * SECRET_LEN).decode().rstrip("="))
    if len(cleaned) != expected:
        raise ValueError(
            f"That code is {len(cleaned)} characters; a pairing code has {expected}."
        )
    try:
        value = base64.b32decode(cleaned)
    except ValueError as exc:
        raise ValueError("That doesn't look like a pairing code — check for typos.") from exc
    if len(value) != SECRET_LEN:
        raise ValueError("That doesn't look like a pairing code — check for typos.")
    return value


def pairing_code() -> str:
    """What to read out to the other device."""
    return format_code(secret())


def pair_with_code(code: str) -> None:
    """Adopt another device's secret. Raises ValueError on a bad code."""
    _store(parse_code(code))
    log.info("adopted a pairing key from another device")
