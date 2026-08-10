"""Authentication and encryption for the peer channel.

Both ends hold the same pairing secret (see pairing.py) and stretch it into a
32-byte root key. A connection then runs a mutual challenge:

    client → server   {"v": 1, "cn": <16 random bytes>}
    server → client   {"v": 1, "sn": <16 random bytes>}
    client → server   {"proof": HMAC(K, "cli" | cn | sn)}      ← client first
    server → client   {"proof": HMAC(K, "srv" | cn | sn)}

This exchange is necessarily plaintext — no session key exists yet — so the
transcript is a verifier: anyone who captures one can test candidate keys
against it offline, without touching the network again. That is exactly why
the key comes from 160 random bits rather than from the user's gpodder.net
password: there is no candidate list to search. The client proving first is a
smaller, separate guard, so that merely connecting to our port yields nothing
but a random nonce.

After the handshake both sides derive per-direction session keys with HKDF and
seal every frame with AES-GCM under a strictly increasing counter nonce, so a
captured frame cannot be replayed — into this session or any later one, since
the keys are bound to nonces that will never recur.
"""

from __future__ import annotations

import hashlib
import hmac
import os

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

PROTOCOL_VERSION = 1
NONCE_LEN = 16
KEY_LEN = 32

_CLIENT_LABEL = b"aerialpod-lan-client"
_SERVER_LABEL = b"aerialpod-lan-server"
_C2S_PREFIX = b"c2s\x00"
_S2C_PREFIX = b"s2c\x00"


def channel_key(secret: bytes) -> bytes:
    """Stretch the shared pairing secret into the handshake root key.

    A plain KDF, not a password hash: the input is already full-entropy, so
    there is nothing for iteration count to defend against.
    """
    return HKDF(
        algorithm=hashes.SHA256(),
        length=KEY_LEN,
        salt=b"aerialpod-lan-pairing-v1",
        info=b"aerialpod-lan-root-key",
    ).derive(secret)


def new_nonce() -> bytes:
    return os.urandom(NONCE_LEN)


def client_proof(key: bytes, cn: bytes, sn: bytes) -> bytes:
    return hmac.new(key, _CLIENT_LABEL + cn + sn, hashlib.sha256).digest()


def server_proof(key: bytes, cn: bytes, sn: bytes) -> bytes:
    return hmac.new(key, _SERVER_LABEL + cn + sn, hashlib.sha256).digest()


def matches(expected: bytes, received: bytes) -> bool:
    return hmac.compare_digest(expected, received)


def session_keys(key: bytes, cn: bytes, sn: bytes) -> tuple[bytes, bytes]:
    """(client→server key, server→client key) for this connection only."""
    material = HKDF(
        algorithm=hashes.SHA256(),
        length=KEY_LEN * 2,
        salt=cn + sn,
        info=b"aerialpod-lan-session-v1",
    ).derive(key)
    return material[:KEY_LEN], material[KEY_LEN:]


class Sealer:
    """Both directions of an established session, from one end's point of view.

    Each direction has its own key and its own counter, and neither counter
    ever resets — so every frame gets a unique nonce under a key that exists
    only for this connection.
    """

    def __init__(self, role: str, c2s: bytes, s2c: bytes):
        assert role in ("client", "server")
        outbound, inbound = (c2s, s2c) if role == "client" else (s2c, c2s)
        self._out, self._in = AESGCM(outbound), AESGCM(inbound)
        self._out_prefix = _C2S_PREFIX if role == "client" else _S2C_PREFIX
        self._in_prefix = _S2C_PREFIX if role == "client" else _C2S_PREFIX
        self._out_counter = 0
        self._in_counter = 0

    def seal(self, plaintext: bytes) -> bytes:
        nonce = self._out_prefix + self._out_counter.to_bytes(8, "big")
        self._out_counter += 1
        return self._out.encrypt(nonce, plaintext, None)

    def open(self, ciphertext: bytes) -> bytes:
        """Raises cryptography.exceptions.InvalidTag on a forged, corrupted,
        reordered or replayed frame — the counter must match exactly."""
        nonce = self._in_prefix + self._in_counter.to_bytes(8, "big")
        self._in_counter += 1
        return self._in.decrypt(nonce, ciphertext, None)
