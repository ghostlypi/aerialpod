"""Score ad detection against hand-labelled ad breaks.

    python -m aerialpod.ads.evaluate --labels ~/.local/share/aerialpod/ad-labels.json \
        --probs DIR [--sweep]

DIR holds what `pipeline.py --dump-probs` wrote, one file per episode. Transcripts
come from the detector's cache, so speech is what gets counted: skipping the
silence around an ad costs nothing, and skipping ten seconds of the hosts costs
ten seconds.

    recall        share of ad speech that would be skipped
    overcut       seconds of show speech that would be skipped — the number to drive down
    false spans   skips with no ad in them at all
    missed        ad breaks less than half skipped

Stdlib only, like spans.py, so it runs under either venv.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

if __package__:
    from . import spans as sp
else:  # run by path, under the detector venv
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import spans as sp  # type: ignore[no-redef]

TRANSCRIPTS = Path.home() / ".cache/aerialpod/transcripts"


def episode_key(feed: str, guid: str | None, media: str) -> str:
    """The detector's transcript cache name (ads/detector.py:_transcript_path)."""
    return hashlib.sha1(f"{feed}\0{guid or media}".encode()).hexdigest()


def _overlap(a: tuple[float, float], b: tuple[float, float]) -> float:
    return max(0.0, min(a[1], b[1]) - max(a[0], b[0]))


@dataclass
class Score:
    ad_speech: float = 0.0        # seconds of speech inside the labels
    caught: float = 0.0           # …of which a span covers
    overcut: float = 0.0          # seconds of show speech a span covers
    false_spans: int = 0
    missed: int = 0
    breaks: int = 0
    rows: list = field(default_factory=list)

    @property
    def recall(self) -> float:
        return self.caught / self.ad_speech if self.ad_speech else 1.0

    def add(self, other: Score) -> None:
        for name in ("ad_speech", "caught", "overcut", "false_spans", "missed", "breaks"):
            setattr(self, name, getattr(self, name) + getattr(other, name))
        self.rows += other.rows


def score(segments: list, labels: list, found: list, title: str = "") -> Score:
    """`segments` [(start_s, end_s, text)], `labels` [[start_s, end_s]],
    `found` [[start_ms, end_ms, conf]]."""
    spans_s = [(s / 1000, e / 1000) for s, e, *_ in found]
    result = Score(breaks=len(labels))
    for start, end, _text, *_words in segments:
        seg = (start, end)
        in_ad = sum(_overlap(seg, tuple(lab)) for lab in labels)
        covered = sum(_overlap(seg, span) for span in spans_s)
        # The covered part of a segment is split in proportion: a segment
        # straddling an ad's edge counts partly as each.
        length = max(end - start, 1e-9)
        ad_part = min(in_ad, length)
        result.ad_speech += ad_part
        result.caught += min(covered, length) * ad_part / length
        result.overcut += min(covered, length) * (length - ad_part) / length
    for span in spans_s:
        if not any(_overlap(span, tuple(lab)) > 2 for lab in labels):
            result.false_spans += 1
    for lab in labels:
        got = sum(_overlap(tuple(lab), span) for span in spans_s)
        if got < 0.5 * (lab[1] - lab[0]):
            result.missed += 1
    result.rows.append((title, result.recall, result.overcut, result.false_spans,
                        result.missed, len(labels)))
    return result


def load(labels_path: Path, probs_dir: Path) -> list[dict]:
    """Episodes that have labels, a transcript and a probability dump."""
    out = []
    for ep in json.loads(labels_path.read_text())["episodes"]:
        key = episode_key(ep["feed"], ep["guid"], ep["media"])
        transcript = TRANSCRIPTS / f"{key}.json"
        dump = probs_dir / f"{key}.json"
        if not transcript.exists() or not dump.exists():
            print(f"skipping {ep['title'][:40]}: no transcript or no dump", file=sys.stderr)
            continue
        probs = json.loads(dump.read_text())
        out.append({
            "title": ep["title"],
            "labels": ep["ads"],
            "segments": json.loads(transcript.read_text())["segments"],
            "lines": [sp.Line(s, e, "") for s, e, _ in probs["lines"]],
            "probs": [p for _, _, p in probs["lines"]],
        })
    return out


def evaluate(episodes: list[dict]) -> Score:
    total = Score()
    for ep in episodes:
        found = sp.spans_from_probabilities(ep["lines"], ep["probs"])
        total.add(score(ep["segments"], ep["labels"], found, ep["title"]))
    return total


def report(total: Score) -> str:
    out = [f"{'episode':42} {'recall':>7} {'overcut':>8} {'false':>6} {'missed':>7}"]
    for title, recall, overcut, false, missed, breaks in total.rows:
        out.append(f"{title[:42]:42} {recall:7.0%} {overcut:7.0f}s {false:6d} {missed:4d}/{breaks}")
    out.append(f"{'TOTAL':42} {total.recall:7.0%} {total.overcut:7.0f}s "
               f"{total.false_spans:6d} {total.missed:4d}/{total.breaks}")
    return "\n".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--probs", type=Path, required=True)
    parser.add_argument("--sweep", action="store_true", help="try other thresholds too")
    args = parser.parse_args()
    episodes = load(args.labels, args.probs)
    print(f"thresholds: enter {sp.ENTER_P} stay {sp.STAY_P} gap {sp.MERGE_GAP_MS} "
          f"min {sp.MIN_SPAN_MS}")
    print(report(evaluate(episodes)))
    if args.sweep:
        print("\nenter  stay   recall  overcut  false  missed")
        for enter in (0.5, 0.6, 0.7, 0.8, 0.9, 0.95):
            for stay in (0.3, 0.4, 0.5, 0.6, 0.7):
                if stay >= enter:
                    continue
                sp.ENTER_P, sp.STAY_P = enter, stay
                t = evaluate(episodes)
                print(f"{enter:5.2f} {stay:5.2f} {t.recall:8.0%} {t.overcut:7.0f}s "
                      f"{t.false_spans:6d} {t.missed:4d}/{t.breaks}")


if __name__ == "__main__":
    main()
