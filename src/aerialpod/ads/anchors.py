"""Carry ad spans from the analysed copy of an episode to another copy.

Dynamic ad insertion means two downloads of "the same" episode differ: each
gets whatever ads were in rotation when it was fetched, of whatever length.
What they share is the show itself — the servers splice pre-encoded ad audio
between untouched stretches of the episode, so outside the breaks the two
files hold the same bytes, just shifted. (Measured on a Waveform episode: two
copies fetched days apart matched byte for byte to the second ad break, then
ran 7.76 s apart for the rest of the file.)

So alongside each span the detector records anchors: a few short runs of the
analysed file's bytes from the show's audio just before the span starts and
just after it ends. Another device finds those bytes in its own copy and moves
each edge by however far its anchor moved. Several anchors per edge, a few
seconds apart, because a detected edge is approximate — the nearest one may
turn out to lie inside the ad, which the other copy does not have.

Offsets are counted from the start of the audio (after any ID3v2 tag), so a
different tag — new artwork, a rewritten title — does not move them.

Imported by pipeline.py under the detector venv too: stdlib only.
"""

from __future__ import annotations

import base64
from pathlib import Path

NEEDLE_BYTES = 48
OFFSETS_S = (1, 5, 15)        # seconds of show audio between an edge and an anchor
SEARCH_WINDOW_S = 180         # how far an anchor may have moved, either way
# A span this close to either end of the file runs to that end. Whisper stops
# at the last word, so a post-roll "ends" a few seconds before the file does.
EDGE_MS = 10_000


def audio_start(head: bytes) -> int:
    """Byte offset where the audio begins: after the ID3v2 tag, if any."""
    if len(head) < 10 or head[:3] != b"ID3":
        return 0
    size = (head[6] & 0x7F) << 21 | (head[7] & 0x7F) << 14 | (head[8] & 0x7F) << 7 | (head[9] & 0x7F)
    footer = 10 if head[5] & 0x10 else 0
    return 10 + size + footer


class Audio:
    """One local copy: where its audio starts and how many bytes a second takes."""

    def __init__(self, path: str | Path, duration_ms: int):
        self.path = Path(path)
        self.size = self.path.stat().st_size
        with self.path.open("rb") as f:
            self.start = audio_start(f.read(10))
        self.rate = (self.size - self.start) / max(duration_ms / 1000, 1)

    def read(self, offset: int, length: int) -> bytes:
        """`offset` is relative to the audio start."""
        with self.path.open("rb") as f:
            f.seek(self.start + max(0, offset))
            return f.read(length)


def make_anchors(audio: Audio, spans: list, duration_ms: int) -> list[dict]:
    """Anchors for each span, parallel to `spans`: {"pre": [...], "post": [...]},
    each a list of {"at": offset, "b": base64 bytes}, nearest the edge first."""
    out = []
    for start, end, _ in spans:
        pre = [] if start <= EDGE_MS else [
            _needle(audio, int((start / 1000 - s) * audio.rate) - NEEDLE_BYTES)
            for s in OFFSETS_S if start / 1000 - s > 0
        ]
        post = [] if end >= duration_ms - EDGE_MS else [
            _needle(audio, int((end / 1000 + s) * audio.rate))
            for s in OFFSETS_S if end / 1000 + s < duration_ms / 1000
        ]
        out.append({"pre": pre, "post": post})
    return out


def _needle(audio: Audio, at: int) -> dict:
    return {"at": at, "b": base64.b64encode(audio.read(at, NEEDLE_BYTES)).decode()}


def map_spans(spans: list, anchors: list, analysed_ms: int, audio: Audio,
              local_ms: int) -> list[list]:
    """The spans, moved onto this copy. A span with an edge whose anchors are
    all missing is dropped: skipping the wrong stretch is worse than not
    skipping at all.

    Shifts accumulate through an episode (each break may differ), so each
    search starts from where the previous edge landed.
    """
    out = []
    shift = 0  # bytes, this copy relative to the analysed one
    for (start, end, confidence), anchor in zip(spans, anchors):
        if start <= EDGE_MS:
            new_start = start
        else:
            found = _find_shift(audio, anchor.get("pre") or [], shift)
            if found is None:
                continue
            shift = found
            new_start = start + round(shift / audio.rate * 1000)
        if end >= analysed_ms - EDGE_MS:
            new_end = local_ms
        else:
            found = _find_shift(audio, anchor.get("post") or [], shift)
            if found is None:
                continue
            shift = found
            new_end = end + round(shift / audio.rate * 1000)
        if new_end > new_start:
            out.append([new_start, new_end, confidence])
    return out


def _find_shift(audio: Audio, needles: list, guess: int) -> int | None:
    window = int(SEARCH_WINDOW_S * audio.rate)
    for needle in needles:
        data = base64.b64decode(needle["b"])
        if len(data) < NEEDLE_BYTES:
            continue
        lo = max(0, needle["at"] + guess - window)
        hay = audio.read(lo, 2 * window + len(data))
        i = hay.find(data)
        if i >= 0:
            return lo + i - needle["at"]
    return None
