"""The pure half of the detector: lines, blocks, decisions, smoothing."""

from __future__ import annotations

from aerialpod.ads import spans
from aerialpod.ads.spans import Line


def lines_every_10s(n: int) -> list[Line]:
    return [Line(i * 10_000, (i + 1) * 10_000, f"line {i}") for i in range(n)]


def test_segments_group_into_lines_of_bounded_length():
    segs = [(i * 2.0, i * 2.0 + 2.0, f"s{i}") for i in range(20)]  # 2 s each
    lines = spans.lines_from_segments(segs)
    assert all(spans.LINE_MIN_MS <= ln.end_ms - ln.start_ms <= spans.LINE_MAX_MS
               for ln in lines[:-1])
    assert lines[0].text == "s0 s1 s2"
    assert lines[-1].end_ms == 40_000


def test_one_long_segment_is_its_own_line():
    lines = spans.lines_from_segments([(0, 3, "a"), (3, 40, "long"), (40, 41, "b")])
    assert [ln.text for ln in lines] == ["a", "long", "b"]


def test_every_line_is_scored_exactly_once():
    lines = [Line(i, i + 1, "x" * 900) for i in range(23)]
    cores = [range(b[1], b[2]) for b in spans.blocks(lines)]
    assert sorted(n for core in cores for n in core) == list(range(23))
    second = spans.blocks(lines)[1]
    assert second[0] == second[1] - spans.CONTEXT_LINES  # leading context


def test_decisions_share_one_state():
    lines = lines_every_10s(5)
    rows = spans.decisions(lines, (0, 0, 5, 5), "Pod", "Ep")
    assert len({r["state"] for r in rows}) == 1
    assert [r["id"] for r in rows] == ["0", "1", "2", "3", "4"]
    assert "[3] (0:00:30) line 3" in rows[0]["state"]


def test_ad_probability_reads_by_option_id():
    result = {"option_ids": ["content", "ad"], "probabilities": [0.2, 0.8]}
    assert spans.ad_probability(result) == 0.8


def test_hysteresis_keeps_an_ad_open_through_its_ambiguous_middle():
    probs = [0.1, 0.9, 0.5, 0.45, 0.8, 0.1, 0.1]
    assert spans.spans_from_probabilities(lines_every_10s(7), probs) == [
        [10_000, 50_000, round((0.9 + 0.5 + 0.45 + 0.8) / 4, 3)]
    ]


def test_lukewarm_lines_never_open_a_span():
    assert spans.spans_from_probabilities(lines_every_10s(4), [0.6, 0.65, 0.6, 0.5]) == []


def test_close_spans_merge_and_short_ones_drop():
    lines = [Line(0, 20_000, ""), Line(20_000, 22_000, ""), Line(22_000, 40_000, ""),
             Line(40_000, 100_000, ""), Line(100_000, 105_000, "")]
    probs = [0.9, 0.1, 0.9, 0.1, 0.95]
    # 0-20 s and 22-40 s are one break (2 s gap); 100-105 s is too short alone.
    assert spans.spans_from_probabilities(lines, probs) == [[0, 40_000, 0.9]]


def test_candidate_regions_pad_and_merge():
    lines = lines_every_10s(40)                  # 0–400 s
    probs = [0.0] * 40
    probs[10] = 0.35                             # 100–110 s: worth a look
    probs[13] = 0.9                              # 130–140 s: close enough to merge
    probs[35] = 0.5                              # 350–360 s: its own region
    assert spans.candidate_regions(lines, probs) == [
        (100_000 - spans.REGION_PAD_MS, 140_000 + spans.REGION_PAD_MS),
        (350_000 - spans.REGION_PAD_MS, 360_000 + spans.REGION_PAD_MS),
    ]
    assert spans.candidate_regions(lines, [0.29] * 40) == []


def test_refine_lines_are_short_and_stay_inside_the_region():
    segs = [(i * 2.0, i * 2.0 + 2.0, f"s{i}") for i in range(100)]   # 2 s each, 0–200 s
    fine = spans.refine_lines(segs, (50_000, 100_000))
    assert fine[0].start_ms >= 49_000 and fine[-1].end_ms <= 101_000
    assert all(ln.end_ms - ln.start_ms <= spans.FINE_MAX_MS for ln in fine)
    assert fine[0].text == "s25 s26"             # 3 s minimum: two 2 s segments


def test_combine_takes_the_check_inside_regions_and_the_scan_outside():
    coarse = lines_every_10s(6)                  # 0–60 s
    fine = [Line(20_000, 25_000, "f1"), Line(25_000, 30_000, "f2")]
    lines, probs = spans.combine(coarse, [0.1, 0.2, 0.9, 0.3, 0.4, 0.5],
                                 fine, [0.95, 0.05], [(20_000, 30_000)])
    assert [ln.start_ms for ln in lines] == [0, 10_000, 20_000, 25_000, 30_000, 40_000, 50_000]
    assert [ln.text for ln in lines][2:4] == ["f1", "f2"]
    assert probs == [0.1, 0.2, 0.95, 0.05, 0.3, 0.4, 0.5]


def test_a_long_segment_is_split_by_its_words_where_the_edge_is_checked():
    words = [[i * 1.0, i * 1.0 + 0.9, f" w{i}"] for i in range(15)]   # 15 s, one segment
    lines = spans.refine_lines([(0.0, 15.0, "all of it", words)], (0, 20_000))
    assert len(lines) > 1
    assert all(ln.end_ms - ln.start_ms <= spans.FINE_MAX_MS for ln in lines)
    assert lines[0].text.startswith("w0")


def test_segments_without_word_times_still_work():
    assert spans.lines_from_segments([(0.0, 7.0, "a", []), (7.0, 9.0, "b")])[0].text == "a"
