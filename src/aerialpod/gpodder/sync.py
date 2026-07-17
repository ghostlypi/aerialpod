"""SyncService: orchestrates gpodder.net sync on a dedicated QThread.

Flow per sync: login → push outbox actions → pull episode actions (aggregated)
→ push pending subscription changes → pull subscription changes → signal the
main thread (QueueManager.reconcile + feed refresh for new podcasts).

Subscriptions on gpodder.net are per-device; episode actions are per-user.
On first sync we pull the user-level merged subscription list (so the phone's
subscriptions appear), upload our list to our own device id, and best-effort
group all devices with /api/2/sync-devices so the server propagates
subscription changes between AntennaPod's device and ours.
"""

from __future__ import annotations

import json
import logging
import socket
import sqlite3
from datetime import datetime, timezone

from PySide6.QtCore import QObject, QThread, QTimer, Signal, Slot

from .. import db
from ..db import repo
from . import credentials, matching
from .client import GpodderClient, GpodderError

log = logging.getLogger(__name__)


def _iso_to_epoch(ts: str) -> int:
    try:
        return int(
            datetime.fromisoformat(ts.replace("Z", "+00:00"))
            .replace(tzinfo=timezone.utc)
            .timestamp()
        )
    except ValueError:
        return 0


class SyncService(QObject):
    """Lives on its own QThread; slot invocations queue up and serialize."""

    syncStarted = Signal()
    syncFinished = Signal(str)      # summary message
    syncFailed = Signal(str)
    subscriptionsChanged = Signal(list)  # podcast_ids added (need feed refresh)
    actionsApplied = Signal()            # main thread should reconcile

    def __init__(self, dry_run: bool = False):
        super().__init__()  # no parent — moveToThread requires it
        self.dry_run = dry_run
        self._client: GpodderClient | None = None
        self._busy = False
        self._abort = False

    def request_abort(self) -> None:
        """Thread-safe: makes an in-flight sync bail at the next retry point."""
        self._abort = True

    # ------------------------------------------------------------ setup

    def configured(self) -> bool:
        return credentials.load() is not None

    def _get_client(self) -> GpodderClient:
        creds = credentials.load()
        if creds is None:
            raise GpodderError("gpodder.net account not configured")
        if self._client is None or self._client.username != creds[0]:
            self._client = GpodderClient(creds[0], creds[1], dry_run=self.dry_run,
                                         should_abort=lambda: self._abort)
        self._client.password = creds[1]
        return self._client

    def _device_id(self) -> str:
        did = repo.get_state("device_id")
        if not did:
            did = f"aerialpod-{socket.gethostname().lower()[:24]}"
            repo.set_state("device_id", did)
        return did

    # ------------------------------------------------------------ main slot

    @Slot()
    def sync_now(self) -> None:
        if self._busy or not self.configured():
            return
        self._busy = True
        self.syncStarted.emit()
        try:
            client = self._get_client()
            client.login()

            device_id = self._device_id()
            if not repo.get_state("device_registered", False):
                client.register_device(device_id, f"AerialPod on {socket.gethostname()}")
                repo.set_state("device_registered", True)

            pushed = self._push_actions(client)
            pulled, unmatched = self._pull_actions(client)
            subs_msg = self._sync_subscriptions(client, device_id)

            self.actionsApplied.emit()
            bits = [f"{pushed} action(s) sent", f"{pulled} applied"]
            if unmatched:
                bits.append(f"{unmatched} unmatched")
            if subs_msg:
                bits.append(subs_msg)
            self.syncFinished.emit("Sync done: " + ", ".join(bits))
        except GpodderError as exc:
            log.warning("sync failed: %s", exc)
            self.syncFailed.emit(str(exc))
        except sqlite3.OperationalError as exc:
            # transient write contention (e.g. first sync races the feed
            # refresh burst) — applied actions are safe, since didn't advance,
            # the next scheduled sync picks up where this one stopped
            log.warning("sync hit a busy database (%s) — will retry on next sync", exc)
            self.actionsApplied.emit()  # reconcile what DID land
            self.syncFailed.emit("Database busy during sync — will retry automatically")
        except Exception as exc:  # noqa: BLE001 — never kill the sync thread
            log.exception("unexpected sync failure")
            self.syncFailed.emit(f"Sync failed: {exc}")
        finally:
            self._busy = False

    # ------------------------------------------------------------ actions

    def _push_actions(self, client: GpodderClient) -> int:
        rows = repo.outbox_actions()
        if not rows:
            return 0
        payload = []
        for r in rows:
            a = {
                "podcast": r["podcast_url"],
                "episode": r["episode_url"],
                "action": r["action"],
                "timestamp": r["timestamp"],
            }
            if r["action"] == "play":
                if r["started"] is not None:
                    a["started"] = r["started"]
                if r["position"] is not None:
                    a["position"] = r["position"]
                if r["total"]:
                    a["total"] = r["total"]
            payload.append(a)
        client.upload_episode_actions(payload)
        if not self.dry_run:
            repo.clear_outbox(rows[-1]["id"])
        return len(payload)

    def _pull_actions(self, client: GpodderClient) -> tuple[int, int]:
        since = int(repo.get_state("actions_since", 0) or 0)
        data = client.get_episode_actions(since, aggregated=True)
        applied = unmatched = 0
        for action in data.get("actions", []):
            if self._apply_action(action):
                applied += 1
            else:
                unmatched += 1
        # advance only on success (we got here without raising)
        repo.set_state("actions_since", data.get("timestamp", since))
        return applied, unmatched

    def _apply_action(self, action: dict) -> bool:
        podcast_url = action.get("podcast", "")
        episode_url = action.get("episode", "")
        kind = action.get("action", "").lower()
        ts = action.get("timestamp", "")

        podcast = matching.match_podcast(podcast_url)
        episode = matching.match_episode(podcast, episode_url) if podcast else None
        if episode is None:
            # Only log play/delete for *known* podcasts as unmatched — actions
            # for podcasts we don't carry aren't interesting.
            if podcast is not None and kind in ("play", "delete"):
                repo.log_unmatched(podcast_url, episode_url, kind, ts, json.dumps(action))
                return False
            return True  # irrelevant, treat as handled

        epoch = _iso_to_epoch(ts)
        if kind == "play":
            if epoch <= episode.position_updated_at:
                return True  # local state is newer — last-writer-wins
            position = int(action.get("position") or 0)
            total = int(action.get("total") or 0)
            updates: dict = {}
            if position > 0:
                updates["position_secs"] = position
                updates["position_updated_at"] = epoch
            if total > 0:
                updates["total_secs"] = total
                # near-total ⇒ finished elsewhere
                if position >= total - 30:
                    updates["state"] = "played"
            if updates:
                repo.update_episode(episode.id, **updates)
        elif kind == "delete":
            repo.update_episode(episode.id, state="played")
        elif kind == "download":
            # The phone downloaded it ⇒ it's (almost certainly) in the phone's
            # queue. Promote to 'inbox' so reconcile picks it up — including
            # archived back-catalog episodes.
            if episode.state in ("new", "archived"):
                repo.update_episode(episode.id, state="inbox")
        elif kind == "new":
            repo.update_episode(episode.id, state="new", position_secs=0,
                                position_updated_at=epoch)
        return True

    # ------------------------------------------------------------ subscriptions

    def _sync_subscriptions(self, client: GpodderClient, device_id: str) -> str:
        since = int(repo.get_state("subs_since", 0) or 0)
        added_ids: list[int] = []
        msg_bits: list[str] = []

        # push local pending changes
        add = [p.feed_url for p in repo.all_podcasts(subscribed_only=False)
               if p.sync_state == "add_pending"]
        remove = [p.feed_url for p in repo.all_podcasts(subscribed_only=False)
                  if p.sync_state == "remove_pending"]
        if add or remove:
            result = client.upload_subscription_changes(device_id, add, remove)
            for old, new in result.get("update_urls", []):
                if new:
                    repo.rewrite_feed_url(old, new)
            if not self.dry_run:
                with db.transaction() as conn:
                    conn.execute(
                        "UPDATE podcasts SET sync_state='clean' "
                        "WHERE sync_state IN ('add_pending','remove_pending')"
                    )
            msg_bits.append(f"{len(add)}+/{len(remove)}− subs pushed")

        if since == 0:
            # First sync: pull the user-level merged list so the phone's
            # subscriptions appear, then group devices server-side.
            try:
                urls = client._request(
                    "GET", f"/subscriptions/{client.username}.json"
                ).json()
            except GpodderError:
                urls = []
            fresh = 0
            for url in urls:
                if isinstance(url, dict):  # some servers return objects
                    url = url.get("url") or ""
                if url and matching.match_podcast(url) is None:
                    added_ids.append(repo.upsert_podcast(url, sync_state="clean"))
                    fresh += 1
            if fresh:
                msg_bits.append(f"{fresh} podcast(s) from server")
            self._link_devices(client, device_id)
            repo.set_state("subs_since", int(datetime.now(timezone.utc).timestamp()))
        else:
            data = client.get_subscription_changes(device_id, since)
            for url in data.get("add", []):
                p = matching.match_podcast(url)
                if p is None:
                    added_ids.append(repo.upsert_podcast(url, sync_state="clean"))
                elif not p.subscribed:
                    repo.upsert_podcast(url, sync_state="clean", subscribed=1)
            for url in data.get("remove", []):
                p = matching.match_podcast(url)
                if p is not None and p.subscribed:
                    with db.transaction() as conn:
                        conn.execute(
                            "UPDATE podcasts SET subscribed=0, sync_state='clean' "
                            "WHERE id=?", (p.id,)
                        )
            repo.set_state("subs_since", data.get("timestamp", since))
            n = len(data.get("add", [])) + len(data.get("remove", []))
            if n:
                msg_bits.append(f"{n} sub change(s) pulled")

        if added_ids:
            self.subscriptionsChanged.emit(added_ids)
        return ", ".join(msg_bits)

    def _link_devices(self, client: GpodderClient, device_id: str) -> None:
        """Best-effort: group all of the user's devices so gpodder.net
        propagates subscription changes between them (phone ↔ desktop)."""
        try:
            devices = client._request(
                "GET", f"/api/2/devices/{client.username}.json"
            ).json()
            others = [d["id"] for d in devices if d.get("id") and d["id"] != device_id]
            if others:
                client._request(
                    "POST",
                    f"/api/2/sync-devices/{client.username}.json",
                    json_body={"synchronize": [[device_id, *others]]},
                )
                log.info("linked devices for server-side sub sync: %s", others)
        except (GpodderError, KeyError, TypeError, ValueError) as exc:
            log.info("device linking skipped: %s", exc)


def start_sync_service(dry_run: bool = False) -> tuple[SyncService, QThread]:
    """Create the service on its own thread. Caller keeps both references."""
    thread = QThread()
    thread.setObjectName("gpodder-sync")
    service = SyncService(dry_run=dry_run)
    service.moveToThread(thread)
    thread.finished.connect(lambda: db.close_thread_connection())
    thread.start()
    return service, thread


class SyncScheduler(QObject):
    """Main-thread scheduler: periodic + debounced post-playback syncs."""

    def __init__(self, service: SyncService, parent: QObject | None = None):
        super().__init__(parent)
        self.service = service
        self._interval = QTimer(self)
        mins = int(repo.get_state("sync_interval_mins"))
        self._interval.setInterval(mins * 60 * 1000)
        self._interval.timeout.connect(self.trigger)
        self._interval.start()

        self._debounce = QTimer(self)
        self._debounce.setSingleShot(True)
        self._debounce.setInterval(10_000)
        self._debounce.timeout.connect(self.trigger)

    def trigger(self) -> None:
        # queued invocation onto the sync thread
        from PySide6.QtCore import QMetaObject, Qt

        QMetaObject.invokeMethod(self.service, "sync_now", Qt.ConnectionType.QueuedConnection)

    def trigger_debounced(self) -> None:
        self._debounce.start()
