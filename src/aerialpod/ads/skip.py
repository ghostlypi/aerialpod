"""When playback should jump an ad. Pure, so both players can share one rule —
the Android port (AdSkip.kt) mirrors this function line for line.

Spans only apply to the copy that was analysed. A dynamically inserted ad
makes another download of the "same" episode a different length, and spans
from one copy land in the wrong places in the other, so a copy whose duration
is off by more than DURATION_TOLERANCE_MS gets no skipping at all.

Skipping is for running *into* an ad, the way SponsorBlock does it: crossing a
span's start in normal playback, resuming inside one, or a forward seek that
lands in one. Seeking back into an ad already passed reads as wanting to hear
it, and is left alone.
"""

from __future__ import annotations

DURATION_TOLERANCE_MS = 2_000
MIN_CONFIDENCE = 0.6
ENTRY_GRACE_MS = 2_000   # how far in the previous tick may be and still count as entering
END_MARGIN_MS = 1_000    # a span ending this close to the end finishes the episode


def applies(analysed_ms: int, actual_ms: int) -> bool:
    return analysed_ms > 0 and actual_ms > 0 and abs(analysed_ms - actual_ms) <= DURATION_TOLERANCE_MS


def span_to_skip(spans: list, allowed: set[int], prev_ms: int | None, pos_ms: int) -> int | None:
    """Index of the span to jump past now, if any.

    `allowed` holds spans the user chose to hear (an undo); `prev_ms` is the
    position at the previous tick, None right after loading.
    """
    for i, (start, end, confidence) in enumerate(spans):
        if i in allowed or confidence < MIN_CONFIDENCE:
            continue
        if start <= pos_ms < end and (prev_ms is None or prev_ms < start + ENTRY_GRACE_MS):
            return i
    return None
