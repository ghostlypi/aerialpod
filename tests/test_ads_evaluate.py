"""The scorer that tuning is judged by (ads/evaluate.py)."""

from __future__ import annotations

import pytest

from aerialpod.ads.evaluate import score

SEGS = [(0, 10, "ad"), (10, 20, "ad"), (20, 30, "show"), (30, 40, "show")]
LABELS = [[0, 20]]


def test_an_exact_skip_has_full_recall_and_no_overcut():
    s = score(SEGS, LABELS, [[0, 20_000, 0.9]])
    assert s.recall == pytest.approx(1.0)
    assert s.overcut == pytest.approx(0)
    assert (s.false_spans, s.missed) == (0, 0)


def test_overcut_counts_show_speech_only():
    s = score(SEGS, LABELS, [[0, 25_000, 0.9]])
    assert s.overcut == pytest.approx(5)


def test_silence_costs_nothing():
    gappy = [(0, 10, "ad"), (30, 40, "show")]      # 10–30 s is silence
    assert score(gappy, [[0, 10]], [[0, 30_000, 0.9]]).overcut == pytest.approx(0)


def test_a_skip_with_no_ad_in_it_is_a_false_span_and_a_short_one_is_missed():
    s = score(SEGS, LABELS, [[2_000, 6_000, 0.9], [25_000, 40_000, 0.9]])
    assert s.false_spans == 1
    assert s.missed == 1                           # 4 s of a 20 s break
    assert s.recall == pytest.approx(0.2)
