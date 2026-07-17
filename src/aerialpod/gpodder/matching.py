"""Match gpodder episode actions — keyed by (feed URL, enclosure URL) — to
local podcasts/episodes. The #1 interop failure mode: dynamic-ad CDNs rotate
enclosure URLs, so we match through a ladder and record aliases on success.
"""

from __future__ import annotations

import logging
import re
from urllib.parse import unquote, urlparse

from .. import db
from ..db import repo
from ..db.models import Episode, Podcast, from_row

log = logging.getLogger(__name__)

# Known tracking/redirect prefixes: strip repeatedly, compare the innermost URL.
# Do NOT blindly strip query strings — some CDNs require them.
_TRACKER_PATTERNS = [
    re.compile(r"^https?://(?:www\.)?podtrac\.com/pts/redirect\.[a-z0-9]+/", re.I),
    re.compile(r"^https?://dts\.podtrac\.com/redirect\.[a-z0-9]+/", re.I),
    re.compile(r"^https?://chtbl\.com/track/[^/]+/", re.I),
    re.compile(r"^https?://pdst\.fm/e/", re.I),
    re.compile(r"^https?://mgln\.ai/e/[^/]+/", re.I),
    re.compile(r"^https?://pfx\.vpixl\.com/[^/]+/", re.I),
    re.compile(r"^https?://claritaspod\.com/measure/", re.I),
    re.compile(r"^https?://pscrb\.fm/rss/p/", re.I),
    re.compile(r"^https?://prfx\.byspotify\.com/e/", re.I),
    re.compile(r"^https?://arttrk\.com/p/[^/]+/", re.I),
]


def strip_trackers(url: str) -> str:
    """Repeatedly unwrap tracking prefixes; returns the innermost URL."""
    for _ in range(6):  # trackers chain; bound the loop
        stripped = url
        for pat in _TRACKER_PATTERNS:
            m = pat.match(stripped)
            if m:
                rest = stripped[m.end():]
                # Re-add a scheme if the inner URL lost it (host/path form).
                if not rest.startswith(("http://", "https://")):
                    rest = "https://" + rest
                stripped = rest
        if stripped == url:
            return url
        url = stripped
    return url


def normalize(url: str) -> str:
    """Lowercase scheme+host, force https, strip fragment. Keep query."""
    url = strip_trackers(url)
    try:
        p = urlparse(url)
    except ValueError:
        return url
    host = (p.hostname or "").lower()
    port = f":{p.port}" if p.port and p.port not in (80, 443) else ""
    query = f"?{p.query}" if p.query else ""
    return f"https://{host}{port}{p.path}{query}"


def variants(url: str) -> set[str]:
    """All normalized identities of a URL, including a percent-encoded inner
    URL embedded in the path (anchor.fm style:
    .../play/123/https%3A%2F%2Fcdn.example.com%2Fep.mp3)."""
    out = {normalize(url)}
    m = re.search(r"https?%3A%2F%2F.+$", url, re.I)
    if m:
        out.add(normalize(unquote(m.group(0))))
    return out


def basename(url: str) -> str:
    path = urlparse(strip_trackers(url)).path
    return path.rsplit("/", 1)[-1]


# ---------------------------------------------------------------- podcasts


def match_podcast(feed_url: str) -> Podcast | None:
    p = repo.podcast_by_feed_url(feed_url)
    if p is not None:
        return p
    # scheme/case tolerance
    norm = normalize(feed_url)
    for cand in repo.all_podcasts(subscribed_only=False):
        if normalize(cand.feed_url) == norm:
            return cand
    return None


# ---------------------------------------------------------------- episodes


def match_episode(podcast: Podcast, episode_url: str) -> Episode | None:
    conn = db.connection()

    # 1. exact media_url within podcast
    row = conn.execute(
        "SELECT * FROM episodes WHERE podcast_id=? AND media_url=?",
        (podcast.id, episode_url),
    ).fetchone()
    if row:
        return from_row(Episode, row)

    # 2. alias table
    row = conn.execute(
        "SELECT e.* FROM episode_url_aliases a JOIN episodes e ON e.id=a.episode_id "
        "WHERE a.url=? AND e.podcast_id=?",
        (episode_url, podcast.id),
    ).fetchone()
    if row:
        return from_row(Episode, row)

    # 3. normalized comparison (tracker-stripped, case/scheme-tolerant,
    # including percent-encoded inner URLs)
    wanted = variants(episode_url)
    for row in conn.execute(
        "SELECT * FROM episodes WHERE podcast_id=?", (podcast.id,)
    ):
        if variants(row["media_url"]) & wanted:
            ep = from_row(Episode, row)
            repo.add_alias(ep.id, episode_url)  # exact next time
            return ep

    # 4. last resort: URL path basename within the podcast
    base = basename(episode_url)
    if base and len(base) > 5:  # avoid matching 'ep.mp3'-style stubs too eagerly
        rows = conn.execute(
            "SELECT * FROM episodes WHERE podcast_id=?", (podcast.id,)
        ).fetchall()
        hits = [r for r in rows if basename(r["media_url"]) == base]
        if len(hits) == 1:
            ep = from_row(Episode, hits[0])
            repo.add_alias(ep.id, episode_url)
            return ep

    return None
