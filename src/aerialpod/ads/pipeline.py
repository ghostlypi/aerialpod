"""Find the ads in one audio file. Runs under the detector venv, not the app's.

    python pipeline.py --audio EP.mp3 --podcast "Show" --episode "Title" \
        [--model qwen3.5-4b] [--transcript CACHE.json]

Prints one JSON object on stdout:
    {"duration_ms": int, "spans": [[start_ms, end_ms, confidence], ...],
     "anchors": [{"pre": [...], "post": [...]}, ...],
     "audio": {"bytes": int, "sha256": str}, "model": str}
(anchors.py explains the anchors: they carry the spans to other copies.)
and "PROGRESS <0..1> <phase>" lines on stderr for whoever is watching.

Two models, run one after the other so they never share the GPU: Whisper for a
timed transcript, then SemIf for one ad/content decision per transcript line.
The transcript is cached when --transcript is given, because it is the slow,
model-independent half and a re-run with another detector model can reuse it.

Imports nothing from aerialpod: this venv has torch and SemIf but not PySide6.
spans.py, next to this file, is stdlib-only for exactly that reason.
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import anchors
import spans

WHISPER_MODEL = "large-v3-turbo"
_quiet_llama = None  # see load_detector()
MAX_TOKENS = 4096


_last_progress = -1.0


def progress(fraction: float, phase: str) -> None:
    global _last_progress
    if fraction - _last_progress < 0.01 and fraction < 1:
        return
    _last_progress = fraction
    print(f"PROGRESS {fraction:.3f} {phase}", file=sys.stderr, flush=True)


def _ensure_cuda_libs() -> None:
    """faster-whisper's CTranslate2 dlopens cuBLAS and cuDNN by soname, and
    finds them only on LD_LIBRARY_PATH. The pip wheels torch already pulled in
    carry both, so point at those rather than requiring a system CUDA. The
    loader reads the variable once at startup, hence the re-exec."""
    if os.environ.get("AERIALPOD_ADS_LIBS"):
        return
    import site

    dirs = [
        str(lib)
        for base in site.getsitepackages()
        for lib in sorted(Path(base, "nvidia").glob("*/lib"))
    ]
    env = dict(os.environ, AERIALPOD_ADS_LIBS="1")
    env["LD_LIBRARY_PATH"] = os.pathsep.join(
        dirs + [p for p in os.environ.get("LD_LIBRARY_PATH", "").split(os.pathsep) if p]
    )
    os.execve(sys.executable, [sys.executable, *sys.argv], env)


# ---------------------------------------------------------------- transcribe


def transcribe(audio: str, cache: Path | None) -> tuple[list, int]:
    """[(start_s, end_s, text, words)], duration_ms — from the cache when it has them.

    A cache from before word timing was recorded is redone when there is real
    audio to redo it from: the word times are what let an ad's edge land
    inside a long Whisper segment rather than at one of its ends.
    """
    if cache is not None and cache.exists():
        data = json.loads(cache.read_text())
        has_words = any(len(s) > 3 for s in data["segments"])
        if has_words or not Path(audio).is_file() or Path(audio).stat().st_size == 0:
            return [tuple(s) for s in data["segments"]], int(data["duration_ms"])

    # The GPU is shared with whatever else the user is doing — a game, another
    # CUDA app — so it can run out partway, not only at load. Either way the
    # CPU still gets there, just slower.
    try:
        segments, duration_ms = _whisper(audio, "cuda", "int8_float16")
    except Exception as exc:  # noqa: BLE001 — CTranslate2 raises plain RuntimeErrors
        print(f"whisper on CUDA failed ({exc}); using CPU", file=sys.stderr)
        gc.collect()
        segments, duration_ms = _whisper(audio, "cpu", "int8")

    if cache is not None:
        cache.parent.mkdir(parents=True, exist_ok=True)
        tmp = cache.with_suffix(".part")
        tmp.write_text(json.dumps({"duration_ms": duration_ms, "segments": segments}))
        tmp.replace(cache)
    return segments, duration_ms


def _whisper(audio: str, device: str, compute_type: str) -> tuple[list, int]:
    from faster_whisper import WhisperModel

    model = WhisperModel(WHISPER_MODEL, device=device, compute_type=compute_type)
    # VAD drops music beds and silence, which otherwise come back as
    # hallucinated text ("Thank you for watching") that the detector then has
    # to have an opinion about.
    pieces, info = model.transcribe(audio, vad_filter=True, condition_on_previous_text=False,
                                    word_timestamps=True)
    segments = []
    for seg in pieces:  # the transcription runs as this is consumed
        words = [[round(w.start, 2), round(w.end, 2), w.word] for w in seg.words or []]
        segments.append((round(seg.start, 2), round(seg.end, 2), seg.text, words))
        progress(0.5 * seg.end / max(info.duration, 1), "transcribing")
    del model
    gc.collect()
    return segments, int(info.duration * 1000)


# ---------------------------------------------------------------- detect


def load_detector(key: str):
    """(model, tokenizer, metadata, score_shared) for one entry of spans.MODELS."""
    spec = spans.MODELS[key]
    if spec["backend"] == "llamacpp":
        import llama_cpp
        from huggingface_hub import hf_hub_download
        from semif_phase1 import llamacpp_backend

        # llama.cpp logs a line per branch restore — thousands an episode, all
        # of it landing in the daemon's journal. Keep the callback referenced:
        # it is called from C, and a collected one crashes the process.
        global _quiet_llama
        _quiet_llama = llama_cpp.llama_log_callback(lambda _level, _text, _data: None)
        llama_cpp.llama_log_set(_quiet_llama, None)

        gguf = hf_hub_download(spec["gguf_repo"], spec["gguf_file"],
                               revision=spec["gguf_revision"])
        _offload(llamacpp_backend, llama_cpp, spec.get("gpu_layers", 0))
        model, tokenizer, metadata = llamacpp_backend.load_model(
            spec["source"], spec["revision"], gguf, context_tokens=MAX_TOKENS,
        )
        return model, tokenizer, metadata, llamacpp_backend.score_shared

    from semif_phase1.core import load_causal_model
    from semif_phase1.shared import score_shared

    if spec.get("quantize"):
        model, tokenizer, metadata = _load_quantized(spec)
    else:
        model, tokenizer, metadata = load_causal_model(spec["source"], spec["revision"], "cuda")
    return model, tokenizer, metadata, score_shared


def _offload(backend, llama_cpp, layers: int) -> None:
    """Put `layers` of a GGUF model on the GPU, when llama.cpp was built with CUDA.

    SemIf's llama.cpp backend is CPU-only by design (it pins n_gpu_layers to 0).
    A CUDA build does far more than hold those layers, though: it runs every
    multi-token batch on the GPU, streaming the CPU-resident weights over PCIe
    as it goes — and every SemIf decision is a ~100-token batch. For the 27B
    that is the difference between a night and a week.
    """
    if not layers or not llama_cpp.llama_supports_gpu_offload():
        return
    original = backend._cpu_model_params

    def with_gpu(library):
        params = original(library)
        params.n_gpu_layers = layers
        return params

    backend._cpu_model_params = with_gpu


def _quantization(kind: str):
    """int8 for the 4B (8 GB in BF16); nf4 for the 9B, which is ~5 GB at 4 bits."""
    import torch
    import transformers

    if kind == "nf4":
        return transformers.BitsAndBytesConfig(
            load_in_4bit=True, bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16,
            # The 9B's output layer is untied from its input embeddings and, with
            # a 248k vocabulary, 2 GB in BF16 — which bitsandbytes leaves alone
            # by default and an 8 GB card then cannot spare. Quantize it too: the
            # readout only compares two answer-letter logits, and both move together.
            # (The list only has to be non-empty and not name lm_head.)
            llm_int8_skip_modules=["visual"],
        )
    return transformers.BitsAndBytesConfig(load_in_8bit=True)


def _load_quantized(spec: dict):
    """SemIf's load_causal_model(), plus a bitsandbytes config.

    Qwen3.5-4B is ~8 GB of BF16 weights and the 9B ~18 GB, neither of which an
    8 GB laptop GPU can hold; in int8 and nf4 they are about half and a quarter. SemIf's scorers only need the forward pass and the
    native cache, so they run on the quantized model unchanged — but its loader
    has no quantization switch, hence this copy of it.
    """
    import torch
    import transformers

    common = {"revision": spec["revision"], "trust_remote_code": False}
    config = transformers.AutoConfig.from_pretrained(spec["source"], **common)
    tokenizer = transformers.AutoTokenizer.from_pretrained(spec["source"], **common)
    cls = transformers.AutoModelForCausalLM
    if config.model_type in {"qwen3_5", "qwen3_5_text"}:
        # Text only: the checkpoint also carries a vision tower SemIf never uses.
        cls = transformers.Qwen3_5ForCausalLM
        config = config.get_text_config()
    model = cls.from_pretrained(
        spec["source"],
        config=config,
        dtype=torch.bfloat16,
        device_map={"": "cuda:0"},
        quantization_config=_quantization(spec["quantize"]),
        low_cpu_mem_usage=True,
        **common,
    )
    model.eval()
    metadata = {
        "source": spec["source"],
        "revision": spec["revision"],
        "dtype": f"{spec['quantize']} (bitsandbytes)",
        "device": "cuda:0",
        "torch_version": torch.__version__,
        "transformers_version": transformers.__version__,
    }
    return model, tokenizer, metadata


def _usable(key: str) -> str:
    """The GPU model, or the CPU one where there is no CUDA device to run it."""
    if spans.MODELS[key]["backend"] not in ("torch", "cascade"):
        return key
    import torch

    if torch.cuda.is_available():
        return key
    print(f"no CUDA device; using {spans.CPU_MODEL} instead of {key}", file=sys.stderr)
    return spans.CPU_MODEL


def detect(lines: list, podcast: str, episode: str, key: str) -> list[float]:
    return score_lines(load_detector(key), key, lines, podcast, episode)


def cascade(segments: list, lines: list, podcast: str, episode: str,
            spec: dict) -> tuple[list, list[float]]:
    """Scan every line with one model, then re-check the stretches it flagged,
    in shorter lines, with a bigger one (spans.candidate_regions)."""
    scan, check = spec["scan"], spec["check"]
    probs = score_lines(load_detector(scan), scan, lines, podcast, episode,
                        (0.5, 0.65), "scanning")
    _free_gpu()  # the scan model is gone once score_lines returns; let its memory go too

    regions = spans.candidate_regions(lines, probs)
    to_check = [spans.refine_lines(segments, region) for region in regions]
    total = sum(map(len, to_check))
    print(f"scan: {len(regions)} region(s), "
          f"{sum(b - a for a, b in regions) / 60000:.1f} min, {total} lines to check",
          file=sys.stderr, flush=True)
    if not total:
        return lines, probs
    try:
        detector = load_detector(check)
    except Exception as exc:  # noqa: BLE001 — a missing or broken checker must not lose the scan
        print(f"{check} unavailable ({exc}); keeping the scan's result",
              file=sys.stderr, flush=True)
        return lines, probs

    fine, fine_probs, done = [], [], 0
    for region_lines in to_check:
        lo = 0.65 + 0.35 * done / total
        done += len(region_lines)
        fine += region_lines
        fine_probs += score_lines(detector, check, region_lines, podcast, episode,
                                  (lo, 0.65 + 0.35 * done / total), "checking")
    return spans.combine(lines, probs, fine, fine_probs, regions)


def score_lines(detector, key: str, lines: list, podcast: str, episode: str,
                span: tuple[float, float] = (0.5, 1.0),
                phase: str = "detecting") -> list[float]:
    """P(ad) for every line, with an already-loaded detector."""
    model, tokenizer, metadata, score_shared = detector
    on_gpu = spans.MODELS[key]["backend"] == "torch"
    # On the GPU, shared mode branches every question off one copy of the
    # block's KV cache per question — 40-odd copies of a 2k-token cache does
    # not fit 8 GB. Questions go in chunks instead; re-prefilling the block per
    # chunk costs a fraction of a second there. The CPU backend restores
    # branches one at a time and needs none of this.
    chunk = 16 if on_gpu else 10_000
    if on_gpu:
        import torch

        out_of_memory: tuple = (torch.OutOfMemoryError,)
    else:
        out_of_memory = ()
    probs: list[float] = [0.0] * len(lines)
    todo = spans.blocks(lines)
    for i, block in enumerate(todo):
        rows = spans.decisions(lines, block, podcast, episode)
        started = time.monotonic()
        at = 0
        while at < len(rows):
            try:
                results, _timing = score_shared(model, tokenizer, rows[at:at + chunk],
                                                metadata, MAX_TOKENS)
            except out_of_memory:
                if chunk == 1:
                    raise
                chunk //= 2
                _free_gpu()
                continue
            for result in results:
                probs[int(result["id"])] = spans.ad_probability(result)
            at += len(results)
        print(f"block {i + 1}/{len(todo)}: {len(rows)} lines in chunks of "
              f"{min(chunk, len(rows))}, {time.monotonic() - started:.1f}s",
              file=sys.stderr, flush=True)
        progress(span[0] + (span[1] - span[0]) * (i + 1) / len(todo), phase)
    return probs


def _free_gpu() -> None:
    import torch

    gc.collect()
    torch.cuda.empty_cache()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--audio", required=True)
    parser.add_argument("--podcast", default="")
    parser.add_argument("--episode", default="")
    parser.add_argument("--model", default=spans.DEFAULT_MODEL, choices=sorted(spans.MODELS))
    parser.add_argument("--transcript", type=Path, help="cache file for the transcript")
    parser.add_argument("--dump-lines", action="store_true",
                        help="also print every line with its P(ad), for tuning")
    parser.add_argument("--dump-probs", type=Path,
                        help="write every line's P(ad) as JSON, for ads/evaluate.py")
    args = parser.parse_args()
    _ensure_cuda_libs()

    started = time.monotonic()
    progress(0.0, "transcribing")
    segments, duration_ms = transcribe(args.audio, args.transcript)
    lines = spans.lines_from_segments(segments)
    print(f"transcript: {len(segments)} segments, {len(lines)} lines, "
          f"{time.monotonic() - started:.0f}s", file=sys.stderr, flush=True)

    model = _usable(args.model)
    spec = spans.MODELS[model]
    try:
        if not lines:
            probs = []
        elif spec["backend"] == "cascade":
            lines, probs = cascade(segments, lines, args.podcast, args.episode, spec)
        else:
            probs = detect(lines, args.podcast, args.episode, model)
    except Exception as exc:  # torch's OutOfMemoryError at load, among others
        if model == spans.CPU_MODEL or "out of memory" not in str(exc).lower():
            raise
        print(f"{model} does not fit the GPU right now; using {spans.CPU_MODEL}",
              file=sys.stderr)
        _free_gpu()
        model = spans.CPU_MODEL
        lines = spans.lines_from_segments(segments)
        probs = detect(lines, args.podcast, args.episode, model)
    found = spans.spans_from_probabilities(lines, probs)
    if args.dump_probs:
        args.dump_probs.parent.mkdir(parents=True, exist_ok=True)
        args.dump_probs.write_text(json.dumps({
            "model": model,
            "lines": [[ln.start_ms, ln.end_ms, round(p, 4)] for ln, p in zip(lines, probs)],
        }))
    if args.dump_lines:
        for line, p in zip(lines, probs):
            print(f"{p:.2f} {line.start_ms / 1000:7.1f} {line.text[:100]}", file=sys.stderr)
    print(f"done: {len(found)} span(s) in {time.monotonic() - started:.0f}s",
          file=sys.stderr, flush=True)
    marks = anchors.make_anchors(anchors.Audio(args.audio, duration_ms), found, duration_ms)
    print(json.dumps({"duration_ms": duration_ms, "spans": found, "anchors": marks,
                      "audio": file_identity(args.audio),
                      # Stamped with what was asked for, not a fallback that ran
                      # instead: the daemon redoes results whose stamp differs
                      # from its setting, and would redo this one every pass.
                      "model": spans.model_tag(args.model)}))


def file_identity(path: str) -> dict:
    """Size and SHA-256 of the analysed file, which a peer that is handed this
    exact file checks it against (lan/files.py)."""
    digest, size = hashlib.sha256(), 0
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
            size += len(block)
    return {"bytes": size, "sha256": digest.hexdigest()}


if __name__ == "__main__":
    main()
