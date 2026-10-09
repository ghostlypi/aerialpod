"""The skip rule both players share (mirrored in AdSkip.kt)."""

from __future__ import annotations

from aerialpod.ads.skip import applies, span_to_skip

SPANS = [[60_000, 120_000, 0.9], [300_000, 330_000, 0.5]]


def test_spans_only_apply_to_the_copy_that_was_analysed():
    assert applies(3_600_000, 3_601_500)
    assert not applies(3_600_000, 3_630_000)  # another copy: a 30 s ad was inserted
    assert not applies(0, 3_600_000)


def test_playing_into_an_ad_skips_it():
    assert span_to_skip(SPANS, set(), 59_500, 60_400) == 0


def test_resuming_inside_an_ad_skips_it():
    assert span_to_skip(SPANS, set(), None, 90_000) == 0


def test_seeking_forward_into_an_ad_skips_it():
    assert span_to_skip(SPANS, set(), 10_000, 90_000) == 0


def test_seeking_back_into_an_ad_does_not():
    assert span_to_skip(SPANS, set(), 200_000, 90_000) is None


def test_undo_lets_the_ad_play():
    assert span_to_skip(SPANS, {0}, 59_500, 60_400) is None


def test_low_confidence_spans_are_never_skipped():
    assert span_to_skip(SPANS, set(), 299_500, 300_200) is None
