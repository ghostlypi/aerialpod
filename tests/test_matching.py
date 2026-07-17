from __future__ import annotations

from aerialpod.db import repo
from aerialpod.gpodder import matching

from .conftest import make_episode

MGLN = ("https://mgln.ai/e/2/dts.podtrac.com/redirect.mp3/"
        "stitcher.simplecastaudio.com/3bb687b0/episodes/abc-123/audio/128/default.mp3"
        "?aid=rss_feed&feed=BqbsxVfO")
INNER = ("https://stitcher.simplecastaudio.com/3bb687b0/episodes/abc-123/audio/128/default.mp3"
         "?aid=rss_feed&feed=BqbsxVfO")


def test_strip_trackers_chained():
    assert matching.strip_trackers(MGLN) == INNER


def test_strip_trackers_untouched():
    url = "https://atp.fm/audio/xyz/atp700.mp3"
    assert matching.strip_trackers(url) == url


def test_normalize_scheme_and_host_case():
    assert matching.normalize("HTTP://CDN.Example.com/Ep1.mp3") == \
        "https://cdn.example.com/Ep1.mp3"


def test_normalize_keeps_query():
    assert "?aid=rss_feed" in matching.normalize(INNER)


def test_match_podcast_scheme_tolerant(podcast):
    assert matching.match_podcast("http://example.com/feed.xml").id == podcast


def test_match_episode_exact(podcast):
    eid = make_episode(podcast, 1)
    p = repo.podcast_by_id(podcast)
    ep = matching.match_episode(p, "https://cdn.example.com/ep001.mp3")
    assert ep is not None and ep.id == eid


def test_match_episode_via_tracker_prefix(podcast):
    """AntennaPod reports a tracker-wrapped URL for an episode we stored bare."""
    eid = make_episode(podcast, 2)
    p = repo.podcast_by_id(podcast)
    wrapped = "https://dts.podtrac.com/redirect.mp3/cdn.example.com/ep002.mp3"
    ep = matching.match_episode(p, wrapped)
    assert ep is not None and ep.id == eid
    # alias recorded → next match is exact
    ep2 = matching.match_episode(p, wrapped)
    assert ep2 is not None and ep2.id == eid


def test_match_episode_basename_fallback(podcast):
    eid = make_episode(podcast, 3)
    p = repo.podcast_by_id(podcast)
    ep = matching.match_episode(p, "https://other-cdn.example.net/media/ep003.mp3")
    assert ep is not None and ep.id == eid


def test_match_episode_no_match(podcast):
    make_episode(podcast, 4)
    p = repo.podcast_by_id(podcast)
    assert matching.match_episode(p, "https://cdn.example.com/nonexistent.mp3") is None


def test_match_percent_encoded_inner_url(podcast):
    """anchor.fm-style action URLs embed the CDN URL percent-encoded."""
    from aerialpod import db
    conn = db.connection()
    cur = conn.execute(
        "INSERT INTO episodes(podcast_id, guid, media_url, title) VALUES(?,?,?,?)",
        (podcast, "g-anchor", "https://anchor.fm/s/xyz/podcast/play/118917422/"
         "https%3A%2F%2Fd3ctxlq1ktw2nl.cloudfront.net%2Fstaging%2Fep5.mp3", "Anchor Ep"),
    )
    conn.commit()
    p = repo.podcast_by_id(podcast)
    # phone reports the bare decoded CDN URL
    ep = matching.match_episode(p, "https://d3ctxlq1ktw2nl.cloudfront.net/staging/ep5.mp3")
    assert ep is not None and ep.id == cur.lastrowid
