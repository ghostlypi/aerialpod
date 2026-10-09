"""Transcript → SemIf decisions → ad spans. Pure, stdlib-only logic.

Imported by both sides of the process boundary: by the daemon (via the app's
package) and by pipeline.py (by path, under the detector venv), which is why it
must not import anything from aerialpod or beyond the standard library.

Granularity is a trade. SemIf scores whatever text it is handed, and a short
line ("…and we'll be right back") is ambiguous on its own, so the model is
always shown a whole block of the transcript and asked about one numbered line
within it. Lines are a few Whisper segments long: short enough that an ad's
edges land within seconds of where they really are, long enough to say
something.
"""

from __future__ import annotations

from dataclasses import dataclass

# Detector models, by the key stored in app_state["ads_model"].
#
# Measured on 19 hand-labelled ad breaks across 8 episodes (ads/evaluate.py):
#
#                    recall   show cut   false spans   breaks missed
#   4B alone           90%      134 s          0            3
#   9B alone           96%      149 s          3            1
#   9B + 27B           98%       64 s          0            0
#
# The 4B and 9B scan Qwen3.5 in 8- and 4-bit on the GPU. The 27B runs as a
# 4-bit GGUF through SemIf's llama.cpp backend with a third of its layers on
# the GPU: 1.3 s a line on an RTX 5070 Laptop, against 44 s on the CPU alone.
# The cascade takes ~10 minutes for a two-hour episode, transcription aside.
# qwen3.5-4b-cpu is for machines without a CUDA GPU, which pipeline.py falls
# back to on its own.
#
# MiniCPM5-2B was tried and dropped: fast, but on a two-hour tech podcast it
# scored most lines of conversation above 0.6 and could not be thresholded.
MODELS: dict[str, dict] = {
    # The default, for a machine that can leave it running overnight: the 9B
    # scans every line, then the 27B — SemIf's best-scoring model, 0.96 on its
    # authored decisions against the 4B's 0.81 — re-checks only the stretches
    # the scan flagged, in short lines, which is what puts the edges right.
    "cascade-9b-27b": {
        "label": "Qwen3.5 9B scan + Qwen3.8 27B check (slow, most accurate)",
        "backend": "cascade",
        "scan": "qwen3.5-9b-gpu",
        "check": "qwen3.8-27b",
    },
    "qwen3.5-9b-gpu": {
        "label": "Qwen3.5 9B, 4-bit on the GPU",
        "backend": "torch",
        "quantize": "nf4",
        "source": "Qwen/Qwen3.5-9B",
        "revision": "c202236235762e1c871ad0ccb60c8ee5ba337b9a",
    },
    "qwen3.8-27b": {
        "label": "Qwen3.8 27B, 4-bit, part on the GPU (slow)",
        "backend": "llamacpp",
        "source": "Qwen/Qwen3.8-27B",
        "revision": "1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0",
        "gguf_repo": "unsloth/Qwen3.8-27B-GGUF",
        "gguf_revision": "4ca720788d1e01f1bff70c033e0d0028fd02e502",
        "gguf_file": "Qwen3.8-27B-UD-Q4_K_M.gguf",
        # Of 64 layers (~0.25 GB each): what an 8 GB card holds once the scan
        # has let go of it. Ignored by a CPU-only llama.cpp build.
        "gpu_layers": 24,
    },
    "qwen3.5-4b-gpu": {
        "label": "Qwen3.5 4B, 8-bit on the GPU",
        "backend": "torch",
        "quantize": "int8",
        "source": "Qwen/Qwen3.5-4B",
        "revision": "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a",
    },
    "qwen3.5-4b-cpu": {
        "label": "Qwen3.5 4B, 4-bit on the CPU (slow)",
        "backend": "llamacpp",
        "source": "Qwen/Qwen3.5-4B",
        "revision": "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a",
        "gguf_repo": "bartowski/Qwen_Qwen3.5-4B-GGUF",
        "gguf_revision": "4168f45a16a1290d65a4ec0fa312ae917a4c15d6",
        "gguf_file": "Qwen_Qwen3.5-4B-Q4_K_M.gguf",
    },
}
DEFAULT_MODEL = "cascade-9b-27b"
CPU_MODEL = "qwen3.5-4b-cpu"

# Bumped whenever a change here would change what is found — the prompt, the
# thresholds, the line shaping — or what a result records. Results are stamped
# "<model>@<version>", and a device re-analyses its own older ones
# (ads.detector). 4: records the analysed file's size and hash, so the file
# can be handed to peers (lan/files.py). 5: the 9B + 27B cascade, with word
# timing at ad edges.
VERSION = 5


def model_tag(key: str) -> str:
    return f"{key}@{VERSION}"

LINE_MIN_MS = 6_000      # merge Whisper segments until a line is this long…
LINE_MAX_MS = 15_000     # …unless that would make it longer than this
BLOCK_CHARS = 6_000      # transcript text per SemIf state (~1.5k tokens, ~4 min)
CONTEXT_LINES = 2        # unscored lines shown either side of a block

# Swept on the labelled set with the cascade (ads/evaluate.py --sweep): this
# pair keeps every break and every second of recall it can without false
# spans. Lower bars catch a little more of the TV-promo spots that are all
# trailer soundbite, and start skipping conversation to do it.
ENTER_P = 0.80           # P(ad) that opens a span
STAY_P = 0.40            # P(ad) that keeps one open
MERGE_GAP_MS = 5_000     # spans closer than this are one ad break
MIN_SPAN_MS = 8_000      # anything shorter is not worth a seek

# Worded against the false positives that matter most. A tech or movie show
# talking *about* products reads a lot like an ad, and an earlier wording that
# asked about "a promotion for another product" skipped minutes of real review.
# The line is quoted, not just numbered: a 4B model is unreliable at looking a
# line up by number in a long block, and judged the block instead — so the
# conversation either side of an ad went with it.
QUESTION = (
    'Is line [{n}], "{text}", part of an advertisement that interrupts the show, '
    "rather than the show itself?"
)
OPTIONS = [
    {"id": "ad", "description": (
        "An advertisement: a sponsor message read by a host or an announcer, an "
        "inserted commercial, or a promo for a different podcast."
    )},
    {"id": "content", "description": (
        "The show itself: its conversation, including discussion or reviews of "
        "products and companies, and its own intro, outro and credits."
    )},
]


@dataclass
class Line:
    start_ms: int
    end_ms: int
    text: str


def lines_from_segments(segments: list[tuple],
                        min_ms: int = LINE_MIN_MS, max_ms: int = LINE_MAX_MS) -> list[Line]:
    """Group Whisper (start_s, end_s, text[, words]) segments into scoring lines."""
    lines: list[Line] = []
    current: Line | None = None
    for start_s, end_s, text, *_words in segments:
        start, end, text = int(start_s * 1000), int(end_s * 1000), text.strip()
        if not text:
            continue
        if current is not None and (
            current.end_ms - current.start_ms >= min_ms
            or end - current.start_ms > max_ms
        ):
            lines.append(current)
            current = None
        if current is None:
            current = Line(start, end, text)
        else:
            current.end_ms = end
            current.text = f"{current.text} {text}"
    if current is not None:
        lines.append(current)
    return lines


# ---------------------------------------------------------------- the cascade

REGION_P = 0.30          # a scan line this likely to be an ad gets a second look…
REGION_PAD_MS = 45_000   # …along with this much either side, where its edges might be
FINE_MIN_MS = 3_000      # second-look lines: a sentence or two, not a paragraph
FINE_MAX_MS = 8_000


def candidate_regions(lines: list[Line], probs: list[float]) -> list[tuple[int, int]]:
    """Stretches worth re-checking: every line the scan gave even a modest
    P(ad), padded and merged. The bar is low on purpose — this decides where
    to look, not what to skip."""
    regions: list[list[int]] = []
    for line, p in zip(lines, probs):
        if p < REGION_P:
            continue
        start, end = max(0, line.start_ms - REGION_PAD_MS), line.end_ms + REGION_PAD_MS
        if regions and start <= regions[-1][1]:
            regions[-1][1] = max(regions[-1][1], end)
        else:
            regions.append([start, end])
    return [(a, b) for a, b in regions]


def refine_lines(segments: list[tuple[float, float, str]],
                 region: tuple[int, int]) -> list[Line]:
    """The region's transcript as short lines, so an ad's edge falls between
    two of them rather than inside one that is half ad, half show."""
    inside = [piece for seg in segments
              if region[0] <= (seg[0] + seg[1]) * 500 < region[1]
              for piece in _split_long(seg)]
    return lines_from_segments(inside, FINE_MIN_MS, FINE_MAX_MS)


def _split_long(segment: tuple) -> list[tuple]:
    """A long Whisper segment as runs of its words, when it has word times.

    Whisper will happily make one 15-second segment of "…we'll be right back,
    bye. Support for the show comes from…", or lump an outro, a music bed and
    the start of an ad together. Scored whole, the ad's edge can only land at
    one end of it; split by word, it lands within a second of the real one.
    """
    start, end, text, *rest = segment
    words = rest[0] if rest else None
    if not words or (end - start) * 1000 <= FINE_MAX_MS:
        return [(start, end, text)]
    return [(ln.start_ms / 1000, ln.end_ms / 1000, ln.text)
            for ln in lines_from_segments(words, FINE_MIN_MS, FINE_MAX_MS)]


def combine(coarse: list[Line], coarse_probs: list[float],
            fine: list[Line], fine_probs: list[float],
            regions: list[tuple[int, int]]) -> tuple[list[Line], list[float]]:
    """The check's lines inside the regions, the scan's outside them."""
    def inside(line: Line) -> bool:
        mid = (line.start_ms + line.end_ms) // 2
        return any(a <= mid < b for a, b in regions)

    merged = [(ln, p) for ln, p in zip(coarse, coarse_probs) if not inside(ln)]
    merged += list(zip(fine, fine_probs))
    merged.sort(key=lambda pair: pair[0].start_ms)
    return [ln for ln, _ in merged], [p for _, p in merged]


def blocks(lines: list[Line]) -> list[tuple[int, int, int, int]]:
    """Split lines into blocks: (ctx_start, core_start, core_end, ctx_end) indices.

    Every line is in exactly one block's core, the part that gets scored. The
    context either side is there so the first line of a block is not judged
    without knowing what led into it.
    """
    out = []
    i = 0
    while i < len(lines):
        j, chars = i, 0
        while j < len(lines) and (j == i or chars + len(lines[j].text) <= BLOCK_CHARS):
            chars += len(lines[j].text)
            j += 1
        out.append((max(0, i - CONTEXT_LINES), i, j, min(len(lines), j + CONTEXT_LINES)))
        i = j
    return out


def _clock(ms: int) -> str:
    s = ms // 1000
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}"


def decisions(lines: list[Line], block: tuple[int, int, int, int],
              podcast: str, episode: str) -> list[dict]:
    """The SemIf rows for one block. They share one exact state, which is what
    lets shared mode prefill it once and branch every question off it."""
    ctx_start, core_start, core_end, ctx_end = block
    transcript = "\n".join(
        f"[{n}] ({_clock(lines[n].start_ms)}) {lines[n].text}"
        for n in range(ctx_start, ctx_end)
    )
    state = f"Podcast: {podcast}\nEpisode: {episode}\nTranscript:\n{transcript}"
    return [
        {"id": str(n), "state": state,
         "question": QUESTION.format(n=n, text=lines[n].text), "options": OPTIONS}
        for n in range(core_start, core_end)
    ]


def ad_probability(result: dict) -> float:
    """P(ad) out of one SemIf result row."""
    return float(result["probabilities"][result["option_ids"].index("ad")])


def spans_from_probabilities(lines: list[Line], probs: list[float]) -> list[list[float]]:
    """Hysteresis over per-line P(ad) → [[start_ms, end_ms, confidence], ...].

    A span opens on a confident line and stays open through less confident
    ones, because the middle of an ad read ("…use code PODCAST at checkout")
    is often more ambiguous than its first sentence. Confidence is the mean
    P(ad) of the lines inside, which is what the players' skip threshold reads.
    """
    assert len(lines) == len(probs)
    raw: list[list] = []  # [start, end, sum_p, n]
    open_span = None
    for line, p in zip(lines, probs):
        if open_span is None and p >= ENTER_P:
            open_span = [line.start_ms, line.end_ms, p, 1]
        elif open_span is not None and p >= STAY_P:
            open_span[1] = line.end_ms
            open_span[2] += p
            open_span[3] += 1
        elif open_span is not None:
            raw.append(open_span)
            open_span = None
    if open_span is not None:
        raw.append(open_span)

    merged: list[list] = []
    for span in raw:
        if merged and span[0] - merged[-1][1] < MERGE_GAP_MS:
            last = merged[-1]
            last[1] = span[1]
            last[2] += span[2]
            last[3] += span[3]
        else:
            merged.append(list(span))

    return [
        [start, end, round(total / n, 3)]
        for start, end, total, n in merged
        if end - start >= MIN_SPAN_MS
    ]
