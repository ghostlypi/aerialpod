"""The peer handshake and framing — no sockets, just the state machine.

What these lock down: only a device holding the pairing secret can complete a
handshake, an unauthenticated stranger learns nothing from our port, and a
captured frame cannot be replayed or edited.
"""

from __future__ import annotations

import json

import pytest

from aerialpod.lan import crypto
from aerialpod.lan.protocol import Channel, ProtocolError

KEY = crypto.channel_key(b"paired-devices-share-this")
OTHER_KEY = crypto.channel_key(b"some-other-households-key")


def pump(a: Channel, b: Channel, rounds: int = 4) -> None:
    """Shuttle bytes between two ends until the handshake settles."""
    for _ in range(rounds):
        for src, dst in ((a, b), (b, a)):
            data = src.take_output()
            if data:
                dst.feed(data)


def connected_pair(key=KEY, server_key=None) -> tuple[Channel, Channel]:
    client = Channel("client", key)
    server = Channel("server", server_key or key)
    client.start()
    pump(client, server)
    return client, server


# ---------------------------------------------------------------- handshake


def test_handshake_establishes_both_ends():
    client, server = connected_pair()
    assert client.established
    assert server.established


def test_server_reveals_nothing_before_the_client_proves():
    """Merely connecting must reveal nothing: everything the server says up
    front is a random nonce."""
    client = Channel("client", KEY)
    server = Channel("server", KEY)
    client.start()
    server.feed(client.take_output())

    reply = json.loads(server.take_output().strip())
    assert set(reply) == {"v", "sn"}
    assert "proof" not in reply
    assert not server.established


def test_an_unpaired_device_is_rejected_by_the_server():
    client = Channel("client", OTHER_KEY)
    server = Channel("server", KEY)
    client.start()
    server.feed(client.take_output())
    client.feed(server.take_output())
    with pytest.raises(ProtocolError, match="failed authentication"):
        server.feed(client.take_output())


def test_client_rejects_a_server_that_cannot_prove_itself():
    """The impersonation direction: something answering on a peer's address
    can't fake being us either."""
    client = Channel("client", KEY)
    impostor = Channel("server", OTHER_KEY)
    client.start()
    impostor.feed(client.take_output())
    client.feed(impostor.take_output())
    # The impostor cannot verify the real proof, so it never gets to reply —
    # feed it a forged one on its behalf and the client must still refuse.
    forged = json.dumps({"proof": crypto.server_proof(OTHER_KEY, b"x" * 16, b"y" * 16).hex()})
    with pytest.raises(ProtocolError, match="failed authentication"):
        client.feed(forged.encode() + b"\n")


def test_version_mismatch_is_refused():
    server = Channel("server", KEY)
    with pytest.raises(ProtocolError, match="unsupported protocol version"):
        server.feed(json.dumps({"v": 99, "cn": "00" * 16}).encode() + b"\n")


def test_garbage_is_refused():
    server = Channel("server", KEY)
    with pytest.raises(ProtocolError):
        server.feed(b"not json at all\n")


def test_oversized_handshake_line_is_refused():
    server = Channel("server", KEY)
    with pytest.raises(ProtocolError, match="too long"):
        server.feed(b"x" * 5000)


# ---------------------------------------------------------------- framing


def test_messages_survive_the_round_trip():
    client, server = connected_pair()
    client.send({"type": "snapshot", "v": 1, "intents": [{"feed": "https://x/f.xml"}]})
    received = server.feed(client.take_output())
    assert received == [{"type": "snapshot", "v": 1, "intents": [{"feed": "https://x/f.xml"}]}]


def test_messages_reassemble_across_packet_boundaries():
    client, server = connected_pair()
    client.send({"type": "ping"})
    data = client.take_output()
    assert server.feed(data[:3]) == []      # split mid-length-prefix
    assert server.feed(data[3:7]) == []
    assert server.feed(data[7:]) == [{"type": "ping"}]


def test_several_messages_in_one_read():
    client, server = connected_pair()
    client.send({"type": "ping"})
    client.send({"type": "position", "v": 1})
    assert len(server.feed(client.take_output())) == 2


def test_replayed_frame_is_rejected():
    client, server = connected_pair()
    client.send({"type": "ping"})
    frame = client.take_output()
    assert server.feed(frame) == [{"type": "ping"}]
    with pytest.raises(ProtocolError, match="failed authentication"):
        server.feed(frame)


def test_tampered_frame_is_rejected():
    client, server = connected_pair()
    client.send({"type": "ping"})
    frame = bytearray(client.take_output())
    frame[-1] ^= 0x01
    with pytest.raises(ProtocolError, match="failed authentication"):
        server.feed(bytes(frame))


def test_frames_from_a_different_session_are_rejected():
    """Session keys are bound to this connection's nonces, so yesterday's
    capture is useless against today's connection."""
    client_a, _ = connected_pair()
    _, server_b = connected_pair()
    client_a.send({"type": "ping"})
    with pytest.raises(ProtocolError, match="failed authentication"):
        server_b.feed(client_a.take_output())


def test_sending_before_establishment_is_refused():
    client = Channel("client", KEY)
    client.start()
    with pytest.raises(ProtocolError, match="not established"):
        client.send({"type": "ping"})
