"""Carrying spans to another copy with different ads spliced in (ads/anchors.py).

The synthetic files are built from fill(), a formula AdAnchorsTest.kt repeats
exactly, so the anchors made here (exported by gen_lan_vectors.py) can be
checked against the Kotlin port byte for byte.
"""

from __future__ import annotations

import pytest

from aerialpod.ads import anchors

RATE = 1000  # bytes per second of "audio"


def fill(seed: int, seconds: int) -> bytes:
    """Distinct, non-repeating bytes per seed: a 32-bit integer hash of
    (seed, index). Linear formulas repeat, and a needle then matches anywhere."""
    out = bytearray(seconds * RATE)
    for i in range(len(out)):
        h = (seed * 1_000_003 + i) & 0xFFFFFFFF
        h = (h * 0x9E3779B1) & 0xFFFFFFFF
        h ^= h >> 15
        h = (h * 0x85EBCA77) & 0xFFFFFFFF
        h ^= h >> 13
        out[i] = h >> 24
    return bytes(out)


def episode(ad_seconds: tuple[int, int, int], tag: bytes = b"") -> bytes:
    """Show, pre-roll-less: content 300 s, ad, content 400 s, ad, content 200 s, post-roll."""
    a, b, c = ad_seconds
    return tag + b"".join([
        fill(1, 300), fill(100 + a, a),
        fill(2, 400), fill(200 + b, b),
        fill(3, 200), fill(300 + c, c),
    ])


def id3(payload: int) -> bytes:
    size = bytes([(payload >> 21) & 0x7F, (payload >> 14) & 0x7F, (payload >> 7) & 0x7F,
                  payload & 0x7F])
    return b"ID3\x04\x00\x00" + size + b"\x00" * payload


def write(tmp_path, name, data):
    path = tmp_path / name
    path.write_bytes(data)
    return path


ANALYSED_ADS = (30, 60, 45)
ANALYSED_MS = (300 + 30 + 400 + 60 + 200 + 45) * 1000
SPANS = [[300_000, 330_000, 0.9], [730_000, 790_000, 0.8], [990_000, ANALYSED_MS - 2_000, 0.85]]


def analysed(tmp_path):
    return anchors.Audio(write(tmp_path, "a.mp3", episode(ANALYSED_ADS)), ANALYSED_MS)


def test_audio_starts_after_the_id3_tag(tmp_path):
    audio = anchors.Audio(write(tmp_path, "t.mp3", id3(5000) + fill(1, 10)), 10_000)
    assert audio.start == 5010
    assert audio.rate == pytest.approx(RATE)


def test_spans_follow_different_ads_into_another_copy(tmp_path):
    marks = anchors.make_anchors(analysed(tmp_path), SPANS, ANALYSED_MS)

    other_ads = (20, 75, 10)  # every break a different length
    other_ms = (300 + 20 + 400 + 75 + 200 + 10) * 1000
    other = anchors.Audio(write(tmp_path, "b.mp3", episode(other_ads, id3(777))), other_ms)

    assert anchors.map_spans(SPANS, marks, ANALYSED_MS, other, other_ms) == [
        [300_000, 320_000, 0.9],            # end moved: this break's ad is 10 s shorter
        [720_000, 795_000, 0.8],            # both edges moved by what came before
        [995_000, other_ms, 0.85],          # a post-roll runs to this copy's end
    ]


def test_mapping_a_copy_onto_itself_changes_nothing_but_the_tail(tmp_path):
    audio = analysed(tmp_path)
    marks = anchors.make_anchors(audio, SPANS, ANALYSED_MS)
    assert anchors.map_spans(SPANS, marks, ANALYSED_MS, audio, ANALYSED_MS) == [
        SPANS[0], SPANS[1], [990_000, ANALYSED_MS, 0.85],
    ]


def test_a_span_whose_anchors_are_gone_is_dropped(tmp_path):
    marks = anchors.make_anchors(analysed(tmp_path), SPANS, ANALYSED_MS)
    # A different edit of the show: the middle stretch was re-cut.
    other = b"".join([fill(1, 300), fill(130, 30), fill(9, 400), fill(260, 60),
                      fill(3, 200), fill(345, 45)])
    audio = anchors.Audio(write(tmp_path, "c.mp3", other), ANALYSED_MS)
    # Both spans with an edge in the re-cut stretch go; the one past it stays.
    assert anchors.map_spans(SPANS, marks, ANALYSED_MS, audio, ANALYSED_MS) == [
        [990_000, ANALYSED_MS, 0.85],
    ]
