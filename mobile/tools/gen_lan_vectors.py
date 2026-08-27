#!/usr/bin/env python3
"""Generate LAN-protocol interop vectors from the desktop's own implementation.

The Android/iOS peer has to agree with `lan/crypto.py` and `lan/protocol.py`
byte for byte, and every constant in that agreement — HKDF salts, HMAC labels,
the nonce prefixes, the frame header — is invisible in a passing unit test that
only checks Kotlin against Kotlin. So the vectors come from the Python side,
and the Kotlin tests are pinned to them.

Emits a Kotlin source file rather than a resource bundle: commonTest resource
loading differs per platform, and a generated `object` needs no plumbing at all.

    python3 mobile/tools/gen_lan_vectors.py

Re-run it whenever the wire protocol changes; the diff on the generated file is
then the honest record of what moved.
"""

from __future__ import annotations

import json
import sys
from itertools import count
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

from aerialpod.lan import crypto, pairing, protocol  # noqa: E402
from aerialpod.lan import state  # noqa: E402

OUT_KT = ROOT / "mobile/shared/src/commonTest/kotlin/org/aerialpod/core/lan/LanVectors.kt"
OUT_JSON = ROOT / "mobile/tools/lan-vectors.json"

# Fixed inputs. Arbitrary but stable — regenerating must not churn the file.
SECRETS = [
    bytes(range(20)),
    bytes.fromhex("00" * 20),
    bytes.fromhex("ff" * 20),
    bytes.fromhex("8f2b1c94ae5507d3611aa0c47e9d38b26f4c5501"),
]
FEED = "https://example.com/feed.xml"
CN = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
SN = bytes.fromhex("f0e0d0c0b0a09080706050403020100f")

FRAME_PLAINTEXTS = [
    b'{"type":"ping"}',
    b'{"type":"ident","device_id":"abc123","caption":"pixel","port":47741}',
    b"",
    bytes(range(256)) * 8,
]


def transcript() -> dict:
    """A complete client/server handshake, with the nonces pinned.

    Runs the real Channel pair against each other, so the recorded bytes are
    exactly what a desktop would put on the wire.
    """
    nonces = iter([CN, SN])
    original = crypto.new_nonce
    crypto.new_nonce = lambda: next(nonces)
    try:
        key = crypto.channel_key(SECRETS[0])
        client = protocol.Channel("client", key)
        server = protocol.Channel("server", key)

        steps = []
        client.start()
        data = client.take_output()
        steps.append({"from": "client", "bytes": data.hex()})
        server.feed(data)
        data = server.take_output()
        steps.append({"from": "server", "bytes": data.hex()})
        client.feed(data)
        data = client.take_output()
        steps.append({"from": "client", "bytes": data.hex()})
        server.feed(data)
        data = server.take_output()
        steps.append({"from": "server", "bytes": data.hex()})
        client.feed(data)

        assert client.established and server.established, "handshake did not establish"

        # Application frames, both directions, so the counter/prefix split for
        # each role is covered rather than assumed symmetric.
        exchanges = []
        for i, sender, receiver in zip(count(), (client, server, client, server),
                                       (server, client, server, client)):
            message = {"type": "ping", "seq": i}
            sender.send(message)
            frame = sender.take_output()
            got = receiver.feed(frame)
            assert got == [message], f"round trip {i} mismatch: {got}"
            exchanges.append({
                "sender": sender.role,
                "message": message,
                "frame": frame.hex(),
            })
        return {"steps": steps, "exchanges": exchanges}
    finally:
        crypto.new_nonce = original


def desktop_snapshot() -> dict:
    """A snapshot the desktop actually built, plus a live position push.

    Seeded to match the Kotlin `Device` harness exactly — same feed URL, same
    `guid-1-N`, same enclosure URLs — so the mobile side can merge this and be
    checked against known expectations. This is what pins the snapshot's *field
    names*: every merge test on either side would pass just as happily with a
    key spelled wrong, because each side would only ever be talking to itself.
    """
    import tempfile

    from aerialpod import db
    from aerialpod.db import repo

    with tempfile.TemporaryDirectory() as tmp:
        db.close_thread_connection()
        db.init(Path(tmp) / "vectors.db")
        try:
            pid = repo.upsert_podcast(FEED, sync_state="clean")
            repo.update_podcast_meta(pid, title="Test Podcast")
            conn = db.connection()
            for n in range(1, 6):
                conn.execute(
                    "INSERT INTO episodes(podcast_id, guid, media_url, title, pub_date, "
                    "state, position_secs, total_secs, position_updated_at) "
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                    (pid, f"guid-{pid}-{n}", f"https://cdn.example.com/ep{n:03d}.mp3",
                     f"Episode {n}", 1700000000 + n * 86400, "new", 0, 0, 0),
                )
            conn.commit()

            # One of each replicated section, with fixed stamps.
            with db.transaction() as c:
                repo.record_intent(c, 1, "queued", position=1024, pinned=1,
                                   origin="manual", updated_at=4100, updated_by="desktop9")
                c.execute(
                    "INSERT INTO queue(episode_id, position, origin, pinned, added_at) "
                    "VALUES(1, 1024, 'manual', 1, 4100)"
                )
                repo.record_intent(c, 3, "excluded", updated_at=4200,
                                   updated_by="desktop9")
                c.execute(
                    "INSERT INTO queue_exclusions(episode_id, removed_at) VALUES(3, 4200)"
                )
            conn.execute(
                "INSERT INTO podcast_settings(podcast_id, custom_title, playback_speed, "
                "skip_intro_secs, skip_outro_secs, auto_add_to_queue, auto_queue_position, "
                "updated_at, updated_by) VALUES(?,?,?,?,?,?,?,?,?)",
                (pid, "Desktop Title", 1.5, 12, 30, 1, "front", 4300, "desktop9"),
            )
            conn.execute(
                "UPDATE episodes SET position_secs=?, total_secs=?, position_updated_at=? "
                "WHERE guid=?", (742, 3600, 4400, f"guid-{pid}-2"),
            )
            conn.commit()

            snapshot = state.build_snapshot()
            episode = repo.episode_by_id(
                conn.execute("SELECT id FROM episodes WHERE guid=?",
                             (f"guid-{pid}-2",)).fetchone()["id"]
            )
            position = state.position_message(episode)
            return {"snapshot": snapshot, "position": position}
        finally:
            db.close_thread_connection()
            db._db_path = None


def build() -> dict:
    key0 = crypto.channel_key(SECRETS[0])
    c2s, s2c = crypto.session_keys(key0, CN, SN)

    def sealed(role: str) -> list[dict]:
        sealer = crypto.Sealer(role, c2s, s2c)
        return [
            {"plaintext": p.hex(), "ciphertext": sealer.seal(p).hex()}
            for p in FRAME_PLAINTEXTS
        ]

    return {
        "cn": CN.hex(),
        "sn": SN.hex(),
        "channel_keys": [
            {"secret": s.hex(), "key": crypto.channel_key(s).hex()} for s in SECRETS
        ],
        "proofs": [
            {
                "secret": s.hex(),
                "client": crypto.client_proof(crypto.channel_key(s), CN, SN).hex(),
                "server": crypto.server_proof(crypto.channel_key(s), CN, SN).hex(),
            }
            for s in SECRETS
        ],
        "session_keys": {"c2s": c2s.hex(), "s2c": s2c.hex()},
        "sealed_client": sealed("client"),
        "sealed_server": sealed("server"),
        "codes": [
            {"secret": s.hex(), "code": pairing.format_code(s)} for s in SECRETS
        ],
        "code_parse_ok": [
            # Transcription mangling the desktop accepts, and must keep accepting.
            {"input": pairing.format_code(SECRETS[0]), "secret": SECRETS[0].hex()},
            {"input": pairing.format_code(SECRETS[0]).lower(), "secret": SECRETS[0].hex()},
            {"input": pairing.format_code(SECRETS[0]).replace("-", ""), "secret": SECRETS[0].hex()},
            {"input": pairing.format_code(SECRETS[0]).replace("-", " "), "secret": SECRETS[0].hex()},
            {
                "input": pairing.format_code(SECRETS[1]).replace("O", "0").replace("I", "1").replace("B", "8"),
                "secret": SECRETS[1].hex(),
            },
        ],
        "code_parse_fail": ["", "AAAA", "not a code at all", "9" * 32],
        "transcript": transcript(),
        "desktop": desktop_snapshot(),
    }


def kt_literal(value) -> str:
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    raise TypeError(value)


def emit(vectors: dict) -> str:
    lines = [
        "package org.aerialpod.core.lan",
        "",
        "// GENERATED by mobile/tools/gen_lan_vectors.py from the desktop's own",
        "// lan/crypto.py and lan/protocol.py. Do not edit by hand: these values are",
        "// the definition of 'the Kotlin peer still speaks what the desktop speaks'.",
        "",
        "internal object LanVectors {",
        f"    const val CN = {kt_literal(vectors['cn'])}",
        f"    const val SN = {kt_literal(vectors['sn'])}",
        "",
        "    class ChannelKey(val secret: String, val key: String)",
        "    val channelKeys = listOf(",
    ]
    for e in vectors["channel_keys"]:
        lines.append(f'        ChannelKey({kt_literal(e["secret"])}, {kt_literal(e["key"])}),')
    lines += [
        "    )",
        "",
        "    class Proof(val secret: String, val client: String, val server: String)",
        "    val proofs = listOf(",
    ]
    for e in vectors["proofs"]:
        lines.append(
            f'        Proof({kt_literal(e["secret"])}, {kt_literal(e["client"])}, '
            f'{kt_literal(e["server"])}),'
        )
    lines += [
        "    )",
        "",
        f'    const val SESSION_C2S = {kt_literal(vectors["session_keys"]["c2s"])}',
        f'    const val SESSION_S2C = {kt_literal(vectors["session_keys"]["s2c"])}',
        "",
        "    class Frame(val plaintext: String, val ciphertext: String)",
    ]
    for name, field in (("sealedClient", "sealed_client"), ("sealedServer", "sealed_server")):
        lines.append(f"    val {name} = listOf(")
        for e in vectors[field]:
            lines.append(
                f'        Frame({kt_literal(e["plaintext"])}, {kt_literal(e["ciphertext"])}),'
            )
        lines.append("    )")
        lines.append("")
    lines += [
        "    class Code(val secret: String, val code: String)",
        "    val codes = listOf(",
    ]
    for e in vectors["codes"]:
        lines.append(f'        Code({kt_literal(e["secret"])}, {kt_literal(e["code"])}),')
    lines += [
        "    )",
        "",
        "    val codeParseOk = listOf(",
    ]
    for e in vectors["code_parse_ok"]:
        lines.append(f'        Code({kt_literal(e["secret"])}, {kt_literal(e["input"])}),')
    lines += [
        "    )",
        "",
        "    val codeParseFail = listOf(",
    ]
    for e in vectors["code_parse_fail"]:
        lines.append(f"        {kt_literal(e)},")
    lines += [
        "    )",
        "",
        "    class Step(val from: String, val bytes: String)",
        "    val transcript = listOf(",
    ]
    for e in vectors["transcript"]["steps"]:
        lines.append(f'        Step({kt_literal(e["from"])}, {kt_literal(e["bytes"])}),')
    lines += [
        "    )",
        "",
        "    class Exchange(val sender: String, val seq: Int, val frame: String)",
        "    val exchanges = listOf(",
    ]
    for e in vectors["transcript"]["exchanges"]:
        lines.append(
            f'        Exchange({kt_literal(e["sender"])}, {e["message"]["seq"]}, '
            f'{kt_literal(e["frame"])}),'
        )
    lines += ["    )", ""]

    def kt_string(obj) -> str:
        # Double-encode: JSON string escaping is a subset of Kotlin's. The only
        # extra is '$', which Kotlin would read as template interpolation.
        return json.dumps(json.dumps(obj, separators=(",", ":"))).replace("$", "\\$")

    lines += [
        "    // A snapshot the desktop's own build_snapshot() produced, verbatim.",
        f'    const val DESKTOP_SNAPSHOT = {kt_string(vectors["desktop"]["snapshot"])}',
        "",
        f'    const val DESKTOP_POSITION = {kt_string(vectors["desktop"]["position"])}',
        "}",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    vectors = build()
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(vectors, indent=2) + "\n")
    OUT_KT.parent.mkdir(parents=True, exist_ok=True)
    OUT_KT.write_text(emit(vectors))
    print(f"wrote {OUT_JSON.relative_to(ROOT)}")
    print(f"wrote {OUT_KT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
