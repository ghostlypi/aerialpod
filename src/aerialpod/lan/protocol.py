"""The peer wire protocol as a pure state machine — bytes in, messages out.

Deliberately knows nothing about sockets: feed it whatever arrived, drain
whatever it wants written. That keeps the handshake and framing testable
without opening a port, and keeps service.py free to drive it from Qt's event
loop.

Wire shape:
  handshake   newline-delimited JSON (plaintext, nonces and proofs only)
  afterwards  4-byte big-endian length + AES-GCM sealed JSON

Message types after the handshake:
  ident     {"type":"ident", "device_id", "caption"}   — always sent first
  snapshot  {"type":"snapshot", ...}                   — full replicated state
  position  {"type":"position", ...}                   — live playback push
  ping      {"type":"ping"}                            — keepalive
"""

from __future__ import annotations

import json
import logging

from cryptography.exceptions import InvalidTag

from . import crypto

log = logging.getLogger(__name__)

MAX_LINE = 4096              # a handshake line is ~120 bytes; refuse anything wild
MAX_FRAME = 16 * 1024 * 1024  # a snapshot of a large library, with headroom


class ProtocolError(Exception):
    """Malformed, unauthenticated or oversized traffic. Always fatal to the
    connection — there is no partial trust here."""


class Channel:
    """One end of a peer connection.

    Usage: create with a role, call start() on the client side, then feed()
    everything that arrives and write out whatever take_output() returns.
    """

    def __init__(self, role: str, key: bytes):
        assert role in ("client", "server")
        self.role = role
        self.established = False
        self._key = key
        self._buf = bytearray()
        self._out = bytearray()
        self._sealer: crypto.Sealer | None = None
        self._cn = b""
        self._sn = b""
        self._state = "hello"

    # ------------------------------------------------------------ output

    def take_output(self) -> bytes:
        """Drain everything queued for the socket."""
        data = bytes(self._out)
        self._out.clear()
        return data

    def start(self) -> None:
        """Client only: open with our nonce. Servers stay silent until spoken to."""
        if self.role != "client":
            return
        self._cn = crypto.new_nonce()
        self._send_line({"v": crypto.PROTOCOL_VERSION, "cn": self._cn.hex()})
        self._state = "await_server_nonce"

    def send(self, message: dict) -> None:
        """Queue an application message. Only valid once established."""
        if not self.established or self._sealer is None:
            raise ProtocolError("channel not established")
        payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
        sealed = self._sealer.seal(payload)
        self._out += len(sealed).to_bytes(4, "big") + sealed

    def _send_line(self, obj: dict) -> None:
        self._out += json.dumps(obj, separators=(",", ":")).encode("utf-8") + b"\n"

    # ------------------------------------------------------------ input

    def feed(self, data: bytes) -> list[dict]:
        """Consume received bytes; returns application messages now readable."""
        self._buf += data
        messages: list[dict] = []
        while True:
            if self.established:
                frame = self._take_frame()
                if frame is None:
                    return messages
                messages.append(frame)
            else:
                line = self._take_line()
                if line is None:
                    return messages
                self._handshake_step(line)

    def _take_line(self) -> dict | None:
        idx = self._buf.find(b"\n")
        if idx < 0:
            if len(self._buf) > MAX_LINE:
                raise ProtocolError("handshake line too long")
            return None
        raw = bytes(self._buf[:idx])
        del self._buf[: idx + 1]
        try:
            obj = json.loads(raw)
        except ValueError as exc:
            raise ProtocolError(f"malformed handshake line: {exc}") from exc
        if not isinstance(obj, dict):
            raise ProtocolError("handshake line is not an object")
        return obj

    def _take_frame(self) -> dict | None:
        if len(self._buf) < 4:
            return None
        size = int.from_bytes(self._buf[:4], "big")
        if size > MAX_FRAME:
            raise ProtocolError(f"frame of {size} bytes exceeds the limit")
        if len(self._buf) < 4 + size:
            return None
        sealed = bytes(self._buf[4 : 4 + size])
        del self._buf[: 4 + size]
        assert self._sealer is not None
        try:
            plaintext = self._sealer.open(sealed)
        except InvalidTag as exc:
            # Forged, corrupted, reordered or replayed — indistinguishable, and
            # all equally fatal.
            raise ProtocolError("frame failed authentication") from exc
        try:
            obj = json.loads(plaintext)
        except ValueError as exc:
            raise ProtocolError(f"malformed frame: {exc}") from exc
        if not isinstance(obj, dict):
            raise ProtocolError("frame is not an object")
        return obj

    # ------------------------------------------------------------ handshake

    def _handshake_step(self, msg: dict) -> None:
        if self.role == "server":
            self._server_step(msg)
        else:
            self._client_step(msg)

    def _server_step(self, msg: dict) -> None:
        if self._state == "hello":
            if msg.get("v") != crypto.PROTOCOL_VERSION:
                raise ProtocolError(f"unsupported protocol version {msg.get('v')!r}")
            self._cn = _nonce_from(msg, "cn")
            self._sn = crypto.new_nonce()
            # Nothing but a random nonce until they prove they hold the account:
            # an open port must not be an oracle for guessing the password.
            self._send_line({"v": crypto.PROTOCOL_VERSION, "sn": self._sn.hex()})
            self._state = "await_client_proof"
        elif self._state == "await_client_proof":
            got = _proof_from(msg)
            if not crypto.matches(crypto.client_proof(self._key, self._cn, self._sn), got):
                raise ProtocolError("peer failed authentication (different account?)")
            self._send_line(
                {"proof": crypto.server_proof(self._key, self._cn, self._sn).hex()}
            )
            self._establish()
        else:
            raise ProtocolError(f"unexpected handshake message in state {self._state}")

    def _client_step(self, msg: dict) -> None:
        if self._state == "await_server_nonce":
            if msg.get("v") != crypto.PROTOCOL_VERSION:
                raise ProtocolError(f"unsupported protocol version {msg.get('v')!r}")
            self._sn = _nonce_from(msg, "sn")
            self._send_line(
                {"proof": crypto.client_proof(self._key, self._cn, self._sn).hex()}
            )
            self._state = "await_server_proof"
        elif self._state == "await_server_proof":
            got = _proof_from(msg)
            if not crypto.matches(crypto.server_proof(self._key, self._cn, self._sn), got):
                raise ProtocolError("peer failed authentication (different account?)")
            self._establish()
        else:
            raise ProtocolError(f"unexpected handshake message in state {self._state}")

    def _establish(self) -> None:
        c2s, s2c = crypto.session_keys(self._key, self._cn, self._sn)
        self._sealer = crypto.Sealer(self.role, c2s, s2c)
        self.established = True
        self._state = "ready"


def _nonce_from(msg: dict, field: str) -> bytes:
    raw = msg.get(field)
    if not isinstance(raw, str):
        raise ProtocolError(f"handshake missing {field}")
    try:
        nonce = bytes.fromhex(raw)
    except ValueError as exc:
        raise ProtocolError(f"handshake {field} is not hex") from exc
    if len(nonce) != crypto.NONCE_LEN:
        raise ProtocolError(f"handshake {field} has wrong length")
    return nonce


def _proof_from(msg: dict) -> bytes:
    raw = msg.get("proof")
    if not isinstance(raw, str):
        raise ProtocolError("handshake missing proof")
    try:
        return bytes.fromhex(raw)
    except ValueError as exc:
        raise ProtocolError("handshake proof is not hex") from exc
