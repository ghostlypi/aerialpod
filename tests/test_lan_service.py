"""End-to-end over a real socket: LanService listening, a peer dialling in.

The unit tests cover the handshake and the merge separately; this one checks
they are actually wired to each other — that a client holding the account key
gets through Qt's socket layer, is identified, and is handed a snapshot.
"""

from __future__ import annotations

import socket
import time

import pytest

from aerialpod.db import repo
from aerialpod.lan import crypto, pairing
from aerialpod.lan.protocol import Channel
from aerialpod.lan.service import LanService

PORT = 47999
SECRET = b"a-shared-pairing-secret"
DEADLINE_SECS = 5


@pytest.fixture()
def service(fresh_db, qapp, monkeypatch):
    """A listening service holding a known pairing secret — no keyring touched."""
    monkeypatch.setattr(pairing, "secret", lambda: SECRET)
    repo.set_state("lan_port", PORT)
    repo.set_state("lan_scan_subnets", False)  # no sweeping from a test
    svc = LanService()
    svc.start_service()
    if svc._server is None:
        pytest.skip(f"port {PORT} unavailable in this environment")
    yield svc
    svc.stop_service()


PEER_ID = "0123456789abcdef0123456789abcdef"
PEER_IDENT = {"type": "ident", "device_id": PEER_ID, "caption": "pytest-peer"}


def converse(sock: socket.socket, channel: Channel, qapp, want: str) -> dict:
    """Drive both sides — Qt's loop and ours — until `want` arrives.

    Sends our ident the moment the channel comes up, the way a real peer does:
    the service stays quiet until it knows who it is talking to.
    """
    sock.settimeout(0.05)
    introduced = False
    deadline = time.monotonic() + DEADLINE_SECS
    while time.monotonic() < deadline:
        qapp.processEvents()
        if channel.established and not introduced:
            introduced = True
            channel.send(PEER_IDENT)
        out = channel.take_output()
        if out:
            sock.sendall(out)
        try:
            data = sock.recv(65536)
        except TimeoutError:
            continue
        if not data:
            raise AssertionError("peer closed the connection")
        for message in channel.feed(data):
            if message.get("type") == want:
                return message
    raise AssertionError(f"timed out waiting for {want!r}")


def dial(key: bytes) -> tuple[socket.socket, Channel]:
    channel = Channel("client", key)
    sock = socket.create_connection(("127.0.0.1", PORT), timeout=DEADLINE_SECS)
    channel.start()
    sock.sendall(channel.take_output())
    return sock, channel


def test_authenticated_peer_is_identified_and_sent_a_snapshot(service, qapp):
    sock, channel = dial(crypto.channel_key(SECRET))
    try:
        ident = converse(sock, channel, qapp, "ident")
        assert ident["device_id"] == repo.lan_device_id()
        assert ident["caption"]

        snapshot = converse(sock, channel, qapp, "snapshot")
        assert snapshot["v"] == 1
        assert {"intents", "settings", "positions"} <= set(snapshot)
    finally:
        sock.close()


def test_the_peer_is_remembered_for_next_time(service, qapp):
    """Discovery is expensive; a peer that has authenticated once should be
    reachable by a direct dial afterwards."""
    sock, channel = dial(crypto.channel_key(SECRET))
    try:
        converse(sock, channel, qapp, "snapshot")
        peers = repo.known_peers()
        assert [p["device_id"] for p in peers] == [PEER_ID]
        assert peers[0]["caption"] == "pytest-peer"
        assert peers[0]["port"] == PORT
    finally:
        sock.close()


def test_an_unpaired_device_gets_nowhere(service, qapp):
    """Wrong key: the server must hang up rather than serve any state."""
    sock, channel = dial(crypto.channel_key(b"not-the-pairing-secret"))
    try:
        with pytest.raises(AssertionError, match="closed the connection|timed out"):
            converse(sock, channel, qapp, "ident")
        assert not channel.established
    finally:
        sock.close()
