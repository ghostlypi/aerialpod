"""Feed fetching and parsing. Blocking — run via workers.run_in_pool.

Conditional GET with ETag/Last-Modified; parse with feedparser; upsert
episodes with guid → media_url → hash identity, aliasing changed enclosure URLs.
"""

from __future__ import annotations

import calendar
import hashlib
import logging
import time

import feedparser
import requests

from .. import db
from ..db import repo

log = logging.getLogger(__name__)

USER_AGENT = "AerialPod/0.1 (+https://github.com/aerialpod)"


class FeedError(Exception):
    pass


def _parse_duration(value: str | None) -> int | None:
    """itunes:duration is either seconds or HH:MM:SS / MM:SS."""
    if not value:
        return None
    value = value.strip()
    try:
        if ":" in value:
            parts = [int(p) for p in value.split(":")]
            secs = 0
            for p in parts:
                secs = secs * 60 + p
            return secs
        return int(float(value))
    except ValueError:
        return None


def _entry_identity(entry) -> tuple[str | None, str | None]:
    """(guid, media_url) for an entry; enclosure picked as first audio-ish link."""
    guid = entry.get("id") or None
    media_url = None
    mime = None
    for enc in entry.get("enclosures", []):
        href = enc.get("href")
        if not href:
            continue
        media_url = href
        mime = enc.get("type")
        if (mime or "").startswith("audio/"):
            break
    return guid, media_url


def fetch_and_store(podcast_id: int) -> int:
    """Fetch one podcast's feed; returns number of new episodes. Blocking."""
    p = repo.podcast_by_id(podcast_id)
    if p is None:
        return 0

    headers = {"User-Agent": USER_AGENT}
    if p.etag:
        headers["If-None-Match"] = p.etag
    if p.http_last_modified:
        headers["If-Modified-Since"] = p.http_last_modified

    # First fetch of a podcast: the back catalog is NOT "new" — only episodes
    # appearing in later refreshes are (matches AntennaPod). The single most
    # recent back-catalog episode is left 'new' so fresh subs surface once.
    first_fetch = p.last_refresh is None

    resp = requests.get(p.feed_url, headers=headers, timeout=30, allow_redirects=True)
    if resp.status_code == 304:
        repo.update_podcast_meta(p.id, last_refresh=int(time.time()))
        return 0
    if resp.status_code >= 400:
        raise FeedError(f"{p.feed_url}: HTTP {resp.status_code}")

    parsed = feedparser.parse(resp.content)
    if parsed.bozo and not parsed.entries:
        raise FeedError(f"{p.feed_url}: unparseable feed ({parsed.bozo_exception})")

    feed = parsed.feed
    image_url = None
    if feed.get("image"):
        image_url = feed.image.get("href")
    repo.update_podcast_meta(
        p.id,
        title=feed.get("title") or p.title,
        description=feed.get("subtitle") or feed.get("summary") or p.description,
        image_url=image_url or p.image_url,
        website=feed.get("link") or p.website,
        etag=resp.headers.get("ETag"),
        http_last_modified=resp.headers.get("Last-Modified"),
        last_refresh=int(time.time()),
    )

    new_count = 0
    with db.transaction() as conn:
        for entry in parsed.entries:
            guid, media_url = _entry_identity(entry)
            if not media_url:
                continue  # not a playable episode
            pub_date = None
            if entry.get("published_parsed"):
                pub_date = calendar.timegm(entry.published_parsed)
            if not guid:
                guid = media_url or hashlib.sha1(
                    f"{entry.get('title', '')}|{pub_date}".encode()
                ).hexdigest()

            episode_image = None
            if entry.get("image"):
                episode_image = entry.image.get("href")

            desc = entry.get("summary") or ""
            duration = _parse_duration(entry.get("itunes_duration"))
            mime = None
            size = None
            for enc in entry.get("enclosures", []):
                if enc.get("href") == media_url:
                    mime = enc.get("type")
                    try:
                        size = int(enc.get("length") or 0) or None
                    except (TypeError, ValueError):
                        size = None

            row = conn.execute(
                "SELECT id, media_url FROM episodes WHERE podcast_id=? AND guid=?",
                (p.id, guid),
            ).fetchone()
            if row is None:
                state = "archived" if first_fetch else "new"
                conn.execute(
                    "INSERT INTO episodes(podcast_id, guid, media_url, title, description, "
                    "pub_date, duration_secs, mime, file_size, image_url, state) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    (p.id, guid, media_url, entry.get("title"), desc, pub_date,
                     duration, mime, size, episode_image, state),
                )
                if not first_fetch:
                    new_count += 1
            else:
                # Known episode: refresh metadata; alias a changed enclosure URL.
                if row["media_url"] != media_url:
                    conn.execute(
                        "INSERT OR IGNORE INTO episode_url_aliases(episode_id, url) VALUES(?,?)",
                        (row["id"], row["media_url"]),
                    )
                conn.execute(
                    "UPDATE episodes SET media_url=?, title=?, description=?, pub_date=?, "
                    "duration_secs=COALESCE(?, duration_secs), mime=COALESCE(?, mime), "
                    "file_size=COALESCE(?, file_size), image_url=COALESCE(?, image_url) "
                    "WHERE id=?",
                    (media_url, entry.get("title"), desc, pub_date, duration, mime,
                     size, episode_image, row["id"]),
                )

        if first_fetch:
            # surface the single latest episode of a fresh subscription
            conn.execute(
                "UPDATE episodes SET state='new' WHERE id = ("
                "  SELECT id FROM episodes WHERE podcast_id=? AND state='archived' "
                "  ORDER BY pub_date DESC LIMIT 1)",
                (p.id,),
            )

    log.info("refreshed %s: %d new episodes", p.feed_url, new_count)
    return new_count
