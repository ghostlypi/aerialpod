"""AdDetector: work through the queue, one episode at a time, in a subprocess.

Lives in the hub (so in the daemon), on its main thread: QProcess does the
waiting, so nothing here blocks. The heavy lifting is pipeline.py under the
detector venv, which exits after every episode — the models' memory, GPU
included, goes back to the system between runs rather than being held by a
daemon that is otherwise asleep.

Detection needs the audio on disk, and the queue is longer than download-ahead
keeps. So the detector asks DownloadManager for the episode it is about to
analyse and holds it against eviction until it is done; the next policy pass
then evicts it as usual. The whole queue gets analysed without the whole queue
staying downloaded.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shutil
import time
from pathlib import Path

from PySide6.QtCore import QObject, QProcess, QTimer, Signal

from .. import db
from ..config import cache_dir
from ..db import repo
from ..db.models import Episode
from . import spans
from .sleepguard import SleepGuard

log = logging.getLogger(__name__)

SCHEDULE_DEBOUNCE_MS = 3_000
# On battery: look again this often. Only a fallback — UPower's OnBattery
# signal (via SleepGuard.powerChanged) is what notices the charger promptly.
POWER_RECHECK_MS = 300_000
RETRY_FAILED_S = 1_800           # a failed episode gets another go after this
FAILURE_MARK = "⚠"                # starts each failure note in the status
RESUME_SETTLE_MS = 30_000        # after waking, let the GPU driver come back first

# What pipeline.py's PROGRESS phases mean, for the status line.
PHASES = {"transcribing": "transcribing", "scanning": "scanning for ads",
          "checking": "double-checking ad breaks", "detecting": "finding ads"}
TRANSCRIPT_MAX_AGE = 30 * 86400


def toolchain_python() -> Path | None:
    """The detector venv's interpreter, if `install.sh ai` has been run here."""
    venv = Path(os.environ.get("AERIALPOD_AI_VENV")
                or Path.home() / ".local/opt/aerialpod-ai")
    python = venv / "bin/python"
    return python if python.exists() else None


def enabled() -> bool:
    setting = repo.get_state("ads_detect_enabled")
    if toolchain_python() is None:
        return False
    return True if setting is None else bool(setting)


def on_battery() -> bool:
    """True only when there is a mains supply and it is unplugged — a desktop,
    which has no power_supply entries of type Mains, always reads as on AC."""
    mains = [
        p for p in Path("/sys/class/power_supply").glob("*")
        if (p / "type").exists() and (p / "type").read_text().strip() == "Mains"
    ]
    return bool(mains) and not any(
        (p / "online").read_text().strip() == "1" for p in mains if (p / "online").exists()
    )


# What the pipeline's last words usually mean, for someone who isn't going to
# read a CUDA traceback. First match wins; the raw error stays in the log.
_EXPLANATIONS = [
    (r"out of memory|OutOfMemoryError|CUBLAS_STATUS_(ALLOC_FAILED|INTERNAL_ERROR)"
     r"|CUDNN_STATUS_(ALLOC_FAILED|INTERNAL_ERROR)",
     "The GPU ran out of memory. Something else was probably using it at the "
     "same time: a game, a video call, another AI tool."),
    (r"no CUDA GPUs are available|CUDA driver|cudaErrorNoDevice|CUDA error: unknown error"
     r"|NVML|cudaErrorDevicesUnavailable",
     "The detector couldn't use the GPU. If this follows a suspend, restarting "
     "the computer usually brings it back."),
    (r"Invalid data found|EOFError|av\.error|could not (open|read)|No such file",
     "The episode's audio couldn't be read. The download may be incomplete or "
     "damaged; it will be fetched again."),
    (r"ModuleNotFoundError|ImportError",
     "The detector's install is missing a package. Run ./install.sh ai again."),
    (r"ConnectionError|TimeoutError|HTTPError|LocalEntryNotFoundError",
     "A model download failed. Check the network connection."),
]
_EXCEPTION_LINE = re.compile(r"^(?:[A-Za-z_][\w.]*\.)?[A-Za-z_]\w*(?:Error|Exception|Exit)\b.*")


def explain_failure(stderr: str, crashed: bool = False) -> str:
    """One readable sentence for the settings page, from the pipeline's
    stderr: a known cause in plain words, or else the exception line itself."""
    if crashed:
        return ("The detector was killed. The computer most likely ran out of "
                "memory (RAM, not the GPU's).")
    lines = [ln.strip() for ln in stderr.strip().splitlines() if ln.strip()]
    exception = next((ln for ln in reversed(lines) if _EXCEPTION_LINE.match(ln)), None)
    # Matched against the error itself, not the traceback above it, whose
    # file paths (huggingface_hub, av/...) would match nearly anything.
    detail = exception or (lines[-1] if lines else "")
    for pattern, sentence in _EXPLANATIONS:
        if re.search(pattern, detail):
            return sentence
    return f"The detector stopped with an error: {detail[:200] or 'it gave no reason'}"


def _local_file(ep: Episode) -> str | None:
    if ep.download_state == "done" and ep.downloaded_path and Path(ep.downloaded_path).exists():
        return ep.downloaded_path
    return None


class AdDetector(QObject):
    statusChanged = Signal(str)
    detected = Signal(int)           # episode_id — its ad_segments row was written

    def __init__(self, queue, downloads, parent: QObject | None = None):
        super().__init__(parent)
        self.queue = queue
        self.downloads = downloads
        self._proc: QProcess | None = None
        self._current: Episode | None = None
        self._stderr = b""
        # episode id → when it failed. Retried after RETRY_FAILED_S: most
        # failures are transient (the GPU busy with a game, a download that
        # timed out), and one bad episode must not stall the rest of the queue.
        self._failed: dict[int, float] = {}
        # episode id → (title, what went wrong, wall-clock time) for the status
        self._reasons: dict[int, tuple[str, str, float]] = {}
        self._forced: list[int] = []        # ads_detect: re-run even if analysed
        self._status = ""
        self._asleep = False            # between PrepareForSleep and resume
        self._interrupted = False       # the running pipeline was killed on purpose
        self._activity = ""             # the status line, before the failure notes

        self._debounce = QTimer(self)
        self._debounce.setSingleShot(True)
        self._debounce.setInterval(SCHEDULE_DEBOUNCE_MS)
        self._debounce.timeout.connect(self._next)

        self._power = QTimer(self)
        self._power.setInterval(POWER_RECHECK_MS)
        self._power.timeout.connect(self.schedule)

        self._retry = QTimer(self)
        self._retry.setSingleShot(True)
        self._retry.setInterval(RETRY_FAILED_S * 1000)
        self._retry.timeout.connect(self.schedule)

        self._settle = QTimer(self)
        self._settle.setSingleShot(True)
        self._settle.setInterval(RESUME_SETTLE_MS)
        self._settle.timeout.connect(self._on_settled)

        self.sleep_guard = SleepGuard(self)
        self.sleep_guard.aboutToSleep.connect(self._on_about_to_sleep)
        self.sleep_guard.resumed.connect(self._on_resumed)
        self.sleep_guard.powerChanged.connect(self.schedule)

        queue.queueChanged.connect(self.schedule)
        downloads.downloadFinished.connect(self._on_download_finished)
        downloads.downloadFailed.connect(self._on_download_failed)

    # ------------------------------------------------------------ public

    def start(self) -> None:
        self._prune_transcripts()
        self.schedule()

    def shutdown(self) -> None:
        if self._proc is not None:
            self._proc.kill()
            self._proc.waitForFinished(2000)
        self.sleep_guard.stop()

    def schedule(self) -> None:
        self._debounce.start()

    def detect_now(self, episode_id: int) -> None:
        self._failed.pop(episode_id, None)
        self._reasons.pop(episode_id, None)
        if episode_id not in self._forced:
            self._forced.insert(0, episode_id)
        self.schedule()

    def clear(self, episode_id: int) -> None:
        """The user says this episode has no ads. Recorded as an empty result,
        which replicates and supersedes whatever any peer found."""
        ep = repo.episode_by_id(episode_id)
        if ep is None:
            return
        existing = repo.ad_segments(episode_id)
        duration = (existing or {}).get("duration_ms") or max(ep.total_secs, 1) * 1000
        with db.transaction() as conn:
            repo.set_ad_segments(conn, episode_id, duration, [], model="user")
        self.detected.emit(episode_id)

    def announce(self) -> None:
        self.statusChanged.emit(self._status)

    def paused(self) -> bool:
        return bool(repo.get_state("ads_paused"))

    def set_paused(self, paused: bool) -> None:
        """The user's pause button. Stops the run in progress, which is not
        held against the episode: it starts over on resume. Resuming also
        retries failed episodes straight away; whatever was in the GPU's way
        has likely gone by the time someone presses it."""
        if paused:
            self._interrupt()
            self._set_status("Ad detection is paused.")
            self.schedule()
        else:
            self.retry_failed()

    def retry_failed(self) -> None:
        """Give every failed episode another go now, not in half an hour."""
        self._failed.clear()
        self._reasons.clear()
        self._retry.stop()
        self._set_status(self._activity)
        self.schedule()

    # ------------------------------------------------------------ scheduling

    def _pending(self) -> list[Episode]:
        # Our own results from an older detector are redone; a peer's, or the
        # user's "no ads here" (model "user"), never are.
        analysed = repo.analysed_episode_ids(
            redo_own_except={spans.model_tag(self._model()), "user"})
        queued = self.queue.episodes()
        by_id = {ep.id: ep for ep in queued}
        forced = [by_id.get(i) or repo.episode_by_id(i) for i in self._forced]
        rest = [ep for ep in queued if ep.id not in analysed and ep.id not in self._forced]
        cutoff = time.monotonic() - RETRY_FAILED_S
        return [
            ep for ep in forced + rest
            if ep is not None and self._failed.get(ep.id, cutoff) <= cutoff
        ]

    def _next(self) -> None:
        if self._proc is not None or self._asleep or self._settle.isActive():
            return
        if self.paused():
            self._set_status("Ad detection is paused.")
            return
        if not enabled():
            self._set_status("Ad detection is off." if toolchain_python()
                             else "Ad detection isn't installed on this device.")
            return
        if on_battery() and not repo.get_state("ads_on_battery"):
            self._set_status("Ad detection is waiting for AC power.")
            self._power.start()
            return
        self._power.stop()

        pending = self._pending()
        if not pending:
            self.downloads.hold.clear()
            self.downloads.apply_policy()
            if any(ep.id in self._failed for ep in self.queue.episodes()):
                self._set_status("Ad detection: nothing else to analyse for now.")
                self._retry.start()
            else:
                self._set_status("Ad detection: every queued episode has been analysed.")
            return

        ep = pending[0]
        self._hold(ep)
        path = _local_file(ep)
        if path is None:
            self._set_status(f"Ad detection: downloading “{ep.title}” to analyse it…")
            return
        # Have the next one arrive while this one runs.
        if len(pending) > 1:
            self._hold(pending[1])
        self._run(ep, path, len(pending))

    def _hold(self, ep: Episode) -> None:
        """Keep it on disk until analysed — downloading it if it isn't there.
        Held even when already downloaded: a file the policy fetched for
        listening is evicted the moment it drops out of download-ahead."""
        self.downloads.hold.add(ep.id)
        if _local_file(ep) is None:
            self.downloads.fetch(ep)

    def _on_download_finished(self, episode_id: int) -> None:
        if episode_id in self.downloads.hold:
            self.schedule()

    def _on_download_failed(self, episode_id: int, message: str) -> None:
        if episode_id in self.downloads.hold:
            self.downloads.hold.discard(episode_id)
            log.warning("ad detection: download failed for episode %d: %s", episode_id, message)
            ep = repo.episode_by_id(episode_id)
            self._record_failure(episode_id, ep.title if ep else f"episode {episode_id}",
                                 f"The episode couldn't be downloaded: {message}")
            self.schedule()

    # ------------------------------------------------------------ the subprocess

    def _run(self, ep: Episode, path: str, waiting: int) -> None:
        python = toolchain_python()
        assert python is not None
        podcast = repo.podcast_by_id(ep.podcast_id)
        args = [
            str(Path(__file__).with_name("pipeline.py")),
            "--audio", path,
            "--podcast", repo.display_title(podcast) if podcast else "",
            "--episode", ep.title or "",
            "--model", self._model(),
            "--transcript", str(self._transcript_path(ep, podcast.feed_url if podcast else "")),
        ]
        # Background work on someone's laptop: never ahead of what they are doing.
        program, args = (("nice", ["-n", "15", str(python), *args]) if shutil.which("nice")
                         else (str(python), args))

        self._current = ep
        self._stderr = b""
        self._started = time.monotonic()
        self._waiting = waiting
        proc = QProcess(self)
        proc.readyReadStandardError.connect(self._on_stderr)
        proc.finished.connect(self._on_finished)
        proc.errorOccurred.connect(self._on_error)
        self._proc = proc
        self._progress(0.0)
        log.info("detecting ads in %s", ep.title)
        self.sleep_guard.hold()
        proc.start(program, args)

    def _model(self) -> str:
        key = repo.get_state("ads_model")
        return key if key in spans.MODELS else spans.DEFAULT_MODEL

    def _transcript_path(self, ep: Episode, feed_url: str) -> Path:
        ident = hashlib.sha1(f"{feed_url}\0{ep.guid or ep.media_url}".encode()).hexdigest()
        return cache_dir() / "transcripts" / f"{ident}.json"

    def _prune_transcripts(self) -> None:
        cutoff = time.time() - TRANSCRIPT_MAX_AGE
        for f in (cache_dir() / "transcripts").glob("*.json"):
            if f.stat().st_mtime < cutoff:
                f.unlink(missing_ok=True)

    def _on_stderr(self) -> None:
        assert self._proc is not None
        chunk = bytes(self._proc.readAllStandardError().data())
        self._stderr = (self._stderr + chunk)[-16_384:]
        for line in chunk.decode(errors="replace").splitlines():
            if line.startswith("PROGRESS "):
                parts = line.split()
                try:
                    self._progress(float(parts[1]), parts[2] if len(parts) > 2 else "")
                except ValueError:
                    pass
            elif line.strip():
                log.debug("pipeline: %s", line)

    def _progress(self, fraction: float, phase: str = "") -> None:
        ep = self._current
        if ep is None:
            return
        doing = PHASES.get(phase, "finding ads")
        more = f" · {self._waiting - 1} more waiting" if self._waiting > 1 else ""
        self._set_status(f"“{ep.title}”: {doing}, {fraction:.0%}{more}")

    def _on_error(self, error) -> None:
        if error == QProcess.ProcessError.FailedToStart:
            self._finish(failure="The detector failed to start. Run ./install.sh ai again.")

    def _on_finished(self, code: int, status) -> None:
        assert self._proc is not None
        crashed = status == QProcess.ExitStatus.CrashExit
        if code != 0 or crashed:
            stderr = self._stderr.decode(errors="replace")
            if not self._interrupted:
                # The whole tail, so the log has the traceback, not a piece of it.
                log.warning("ad detector %s; its last output:\n%s",
                            "crashed" if crashed else f"exited with {code}",
                            "\n".join(stderr.strip().splitlines()[-40:]))
            self._finish(failure=explain_failure(stderr, crashed))
            return
        out = bytes(self._proc.readAllStandardOutput().data()).decode().strip()
        try:
            result = json.loads(out.splitlines()[-1])
            spans_found = result["spans"]
            duration = int(result["duration_ms"])
        except (ValueError, KeyError, IndexError) as exc:
            self._finish(failure=f"unreadable result ({exc})")
            return
        self._finish(spans_found=spans_found, duration=duration, model=result.get("model"),
                     anchors=result.get("anchors"), audio=result.get("audio"))

    def _finish(self, *, failure: str | None = None, spans_found=None, duration=0,
                model=None, anchors=None, audio=None) -> None:
        ep = self._current
        proc, self._proc, self._current = self._proc, None, None
        if proc is not None:
            proc.deleteLater()
        self.sleep_guard.release()
        if ep is None:
            return
        if self._interrupted:
            # Stopped on purpose (suspend, pause), not broken: keep its hold
            # and its place in line, and start it over later.
            self._interrupted = False
            log.info("ad detection for %s stopped; will redo", ep.title)
            return
        if ep.id in self._forced:
            self._forced.remove(ep.id)
        self.downloads.hold.discard(ep.id)

        if failure is not None:
            log.warning("ad detection failed for %s: %s", ep.title, failure)
            self._record_failure(ep.id, ep.title, failure)
        else:
            self._failed.pop(ep.id, None)
            self._reasons.pop(ep.id, None)
            with db.transaction() as conn:
                repo.set_ad_segments(conn, ep.id, duration, spans_found,
                                     anchors=anchors, audio=audio, model=model)
            log.info("ads in %s: %d span(s), %.0fs", ep.title, len(spans_found),
                     time.monotonic() - self._started)
            self.detected.emit(ep.id)
        self.downloads.apply_policy()
        self.schedule()

    # ------------------------------------------------------------ suspend

    def _interrupt(self) -> None:
        """Kill the run in progress and wait for it to be gone, without
        counting it as a failure."""
        if self._proc is not None:
            self._interrupted = True
            self._proc.kill()
            if not self._proc.waitForFinished(4000):
                log.warning("ad detector still running after being stopped")
        self.sleep_guard.release()

    def _on_about_to_sleep(self) -> None:
        """The GPU must be idle before the machine sleeps. Releasing the
        inhibitor once the run is gone is what lets the suspend go ahead."""
        self._asleep = True
        self._settle.stop()
        self._interrupt()
        self._set_status("Ad detection paused for suspend.")

    def _on_resumed(self) -> None:
        self._asleep = False
        self._settle.start()

    def _on_settled(self) -> None:
        self.schedule()

    # ------------------------------------------------------------ status

    def _record_failure(self, episode_id: int, title: str, reason: str) -> None:
        self._failed[episode_id] = time.monotonic()
        self._reasons[episode_id] = (title, reason, time.time())
        self._set_status(self._activity)

    def _failure_notes(self) -> str:
        """A line per failed episode still in the queue: what went wrong, in
        words, and when it gets another go."""
        queued = {ep.id for ep in self.queue.episodes()} | set(self._forced)
        notes = []
        for eid, (title, reason, when) in self._reasons.items():
            if eid not in queued or eid not in self._failed:
                continue
            at = time.strftime("%H:%M", time.localtime(when))
            again = time.strftime("%H:%M", time.localtime(when + RETRY_FAILED_S))
            notes.append(f"{FAILURE_MARK} “{title}” failed at {at}. {reason} "
                         f"It will be tried again at {again}.")
        return "\n\n".join(notes)

    def _set_status(self, activity: str) -> None:
        self._activity = activity
        notes = self._failure_notes()
        message = f"{activity}\n\n{notes}" if notes else activity
        if message != self._status:
            self._status = message
            self.statusChanged.emit(message)

