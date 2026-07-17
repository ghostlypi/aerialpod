"""Thin gpodder.net API v2 client on requests. Blocking — call from workers.

Endpoints used (https://gpoddernet.readthedocs.io/en/latest/api/):
  POST /api/2/auth/{user}/login.json                     — session cookie
  POST /api/2/devices/{user}/{device}.json               — register/rename device
  GET/POST /api/2/subscriptions/{user}/{device}.json     — subscription diff
  GET/POST /api/2/episodes/{user}.json                   — episode actions
"""

from __future__ import annotations

import logging
import socket
import time
from typing import Any

import requests

log = logging.getLogger(__name__)

DEFAULT_SERVER = "https://gpodder.net"
TIMEOUT = 30
RETRIES = 3
USER_AGENT = "AerialPod/0.1"


class GpodderError(Exception):
    """Sync-layer error with a user-presentable message."""


class GpodderAuthError(GpodderError):
    pass


class GpodderClient:
    def __init__(self, username: str, password: str, server: str = DEFAULT_SERVER,
                 dry_run: bool = False, should_abort=None):
        self.username = username
        self.password = password
        self.server = server.rstrip("/")
        self.dry_run = dry_run
        self.should_abort = should_abort or (lambda: False)
        self.session = requests.Session()
        self.session.headers["User-Agent"] = USER_AGENT
        self._logged_in = False

    # ------------------------------------------------------------ plumbing

    def _request(self, method: str, path: str, *, json_body: Any = None,
                 params: dict | None = None, _retry_auth: bool = True) -> requests.Response:
        url = f"{self.server}{path}"
        if self.dry_run and method == "POST" and "/auth/" not in path:
            log.info("[dry-run] %s %s body=%s", method, url, json_body)
            resp = requests.Response()
            resp.status_code = 200
            resp._content = b'{"timestamp": %d, "update_urls": []}' % int(time.time())
            return resp

        last_exc: Exception | None = None
        for attempt in range(RETRIES):
            if self.should_abort():
                raise GpodderError("sync aborted (app closing)")
            try:
                # Send basic auth everywhere; the session cookie from login
                # rides along too. Belt and suspenders against cookie expiry.
                resp = self.session.request(
                    method, url, json=json_body, params=params, timeout=TIMEOUT,
                    auth=(self.username, self.password),
                )
            except (requests.ConnectionError, requests.Timeout, socket.error) as exc:
                last_exc = exc
                log.warning("gpodder %s %s failed (%s), attempt %d", method, path, exc, attempt + 1)
                time.sleep(2**attempt)
                continue

            if resp.status_code == 401 and _retry_auth and "/auth/" not in path:
                # session expired — re-login once and retry
                self._logged_in = False
                self.login()
                return self._request(method, path, json_body=json_body, params=params,
                                     _retry_auth=False)
            if resp.status_code >= 500:
                log.warning("gpodder %s %s -> %d, attempt %d", method, path,
                            resp.status_code, attempt + 1)
                time.sleep(2**attempt)
                continue
            if resp.status_code == 401:
                raise GpodderAuthError("gpodder.net login failed — check username/password")
            if resp.status_code >= 400:
                raise GpodderError(f"gpodder.net error {resp.status_code} for {path}")
            return resp

        raise GpodderError(
            f"gpodder.net unreachable after {RETRIES} tries"
            + (f" ({last_exc})" if last_exc else "")
        )

    # ------------------------------------------------------------ auth & devices

    def login(self) -> None:
        if self._logged_in:
            return
        self._request("POST", f"/api/2/auth/{self.username}/login.json")
        self._logged_in = True
        log.info("gpodder.net login ok (%s)", self.username)

    def register_device(self, device_id: str, caption: str) -> None:
        self._request(
            "POST",
            f"/api/2/devices/{self.username}/{device_id}.json",
            json_body={"caption": caption, "type": "desktop"},
        )

    # ------------------------------------------------------------ subscriptions

    def get_subscription_changes(self, device_id: str, since: int) -> dict:
        """{'add': [urls], 'remove': [urls], 'timestamp': int}"""
        resp = self._request(
            "GET",
            f"/api/2/subscriptions/{self.username}/{device_id}.json",
            params={"since": since},
        )
        return resp.json()

    def upload_subscription_changes(self, device_id: str, add: list[str],
                                    remove: list[str]) -> dict:
        """Returns {'timestamp': int, 'update_urls': [[old, new], ...]}"""
        resp = self._request(
            "POST",
            f"/api/2/subscriptions/{self.username}/{device_id}.json",
            json_body={"add": add, "remove": remove},
        )
        return resp.json()

    def get_all_subscriptions(self, device_id: str) -> list[str]:
        """Full subscription list for first sync (simple API)."""
        resp = self._request(
            "GET", f"/subscriptions/{self.username}/{device_id}.json"
        )
        return resp.json()

    # ------------------------------------------------------------ episode actions

    def get_episode_actions(self, since: int, aggregated: bool = True) -> dict:
        """{'actions': [...], 'timestamp': int}"""
        params: dict[str, Any] = {"since": since}
        if aggregated:
            params["aggregated"] = "true"
        resp = self._request("GET", f"/api/2/episodes/{self.username}.json", params=params)
        return resp.json()

    def upload_episode_actions(self, actions: list[dict]) -> dict:
        """actions: [{'podcast', 'episode', 'action', 'timestamp',
        'started'?, 'position'?, 'total'?}, ...]  Returns {'timestamp': ...}"""
        resp = self._request("POST", f"/api/2/episodes/{self.username}.json",
                             json_body=actions)
        return resp.json()
