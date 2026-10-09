"""Handing a peer the analysed audio file, over a real socket (lan/files.py)."""

from __future__ import annotations

import base64
import hashlib
import os
import time

from aerialpod import db
from aerialpod.db import repo
from aerialpod.lan import crypto

from . import test_lan_service
from .conftest import make_episode
from .test_lan_service import DEADLINE_SECS, PEER_IDENT, SECRET, dial

service = test_lan_service.service  # the listening-service fixture, shared

FILE_IDENT = {**PEER_IDENT, "purpose": "file"}
FEED = "https://example.com/feed.xml"


def analysed_episode(tmp_path, by_us=True, size=3 * 1024 * 1024 + 123) -> bytes:
    pid = repo.upsert_podcast(FEED, sync_state="clean")
    eid = make_episode(pid, 1)
    data = os.urandom(size)
    path = tmp_path / "ep.mp3"
    path.write_bytes(data)
    repo.update_episode(eid, download_state="done", downloaded_path=str(path))
    with db.transaction() as conn:
        repo.set_ad_segments(
            conn, eid, 60_000, [[0, 1000, 0.9]],
            audio={"bytes": size, "sha256": hashlib.sha256(data).hexdigest()},
            updated_by=None if by_us else "someone-else",
        )
    return data


class FileLink:
    """The phone's side of a file link, driven by hand alongside Qt's loop."""

    def __init__(self, qapp):
        self.qapp = qapp
        self.sock, self.channel = dial(crypto.channel_key(SECRET))
        self.sock.settimeout(0.02)
        self.inbox: list[dict] = []
        self.introduced = False

    def pump(self) -> None:
        self.qapp.processEvents()
        try:
            data = self.sock.recv(1 << 20)
        except TimeoutError:
            data = b""
        if data:
            self.inbox.extend(self.channel.feed(data))
        # Introduce ourselves the moment the channel is up — before anything
        # else is queued, or the service sees a request from a stranger.
        if self.channel.established and not self.introduced:
            self.introduced = True
            self.channel.send(FILE_IDENT)
        out = self.channel.take_output()
        if out:
            self.sock.sendall(out)

    def wait_for(self, kind: str) -> dict:
        deadline = time.monotonic() + DEADLINE_SECS
        while time.monotonic() < deadline:
            for i, message in enumerate(self.inbox):
                if message["type"] == kind:
                    return self.inbox.pop(i)
            self.pump()
        raise AssertionError(f"timed out waiting for {kind!r}; got {self.inbox}")

    def get(self, offset: int = 0) -> None:
        self.wait_for("ident")
        self.channel.send({"type": "file_get", "feed": FEED, "guid": "guid-1-1",
                           "media": "", "offset": offset})

    def receive(self) -> tuple[bytes, list[str]]:
        got, seen = bytearray(), []
        deadline = time.monotonic() + DEADLINE_SECS
        while time.monotonic() < deadline:
            self.pump()
            while self.inbox:
                message = self.inbox.pop(0)
                seen.append(message["type"])
                if message["type"] == "file_chunk":
                    got += base64.b64decode(message["data"])
                elif message["type"] == "file_end":
                    return bytes(got), seen
        raise AssertionError("transfer did not finish")

    def close(self) -> None:
        self.sock.close()


def test_hands_over_the_exact_analysed_file(service, qapp, tmp_path):
    data = analysed_episode(tmp_path)
    link = FileLink(qapp)
    try:
        link.get()
        head = link.wait_for("file_head")
        assert head == {"type": "file_head", "bytes": len(data),
                        "sha256": hashlib.sha256(data).hexdigest()}
        got, seen = link.receive()
        assert got == data
        assert "snapshot" not in seen     # a file link is not a sync peer…
        assert service._by_peer == {}     # …and is not counted as one
    finally:
        link.close()


def test_resumes_from_an_offset(service, qapp, tmp_path):
    data = analysed_episode(tmp_path)
    link = FileLink(qapp)
    try:
        link.get(offset=1_000_000)
        link.wait_for("file_head")
        got, _ = link.receive()
        assert got == data[1_000_000:]
    finally:
        link.close()


def test_only_its_own_analysed_copy_is_served(service, qapp, tmp_path):
    analysed_episode(tmp_path, by_us=False)
    link = FileLink(qapp)
    try:
        link.get()
        assert "no analysed copy" in link.wait_for("file_err")["reason"]
    finally:
        link.close()


def test_a_replaced_download_is_not_served(service, qapp, tmp_path):
    analysed_episode(tmp_path)
    (tmp_path / "ep.mp3").write_bytes(b"a fresh download, with different ads")
    link = FileLink(qapp)
    try:
        link.get()
        assert "no longer on this device" in link.wait_for("file_err")["reason"]
    finally:
        link.close()


def test_analysed_copies_are_kept_while_queued(fresh_db, tmp_path):
    analysed_episode(tmp_path)
    with db.transaction() as conn:
        conn.execute("INSERT INTO queue(episode_id, position, added_at) VALUES(1, 1024, 0)")
    assert repo.kept_analysed_ids() == {1}

    (tmp_path / "ep.mp3").write_bytes(b"replaced by a fresh download")
    assert repo.kept_analysed_ids() == set()   # not the analysed copy any more


def test_analysed_copies_go_once_out_of_the_queue(fresh_db, tmp_path):
    analysed_episode(tmp_path)
    assert repo.kept_analysed_ids() == set()
