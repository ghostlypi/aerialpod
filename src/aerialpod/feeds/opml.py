"""OPML import/export for subscriptions."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

from ..db import repo


def import_opml(path: str | Path) -> list[int]:
    """Subscribe to every feed in the OPML; returns new podcast ids."""
    tree = ET.parse(path)
    added = []
    for outline in tree.iter("outline"):
        url = outline.get("xmlUrl")
        if url and repo.podcast_by_feed_url(url) is None:
            pid = repo.upsert_podcast(url)
            title = outline.get("title") or outline.get("text")
            if title:
                repo.update_podcast_meta(pid, title=title)
            added.append(pid)
    return added


def export_opml(path: str | Path) -> int:
    root = ET.Element("opml", version="2.0")
    head = ET.SubElement(root, "head")
    ET.SubElement(head, "title").text = "AerialPod subscriptions"
    ET.SubElement(head, "dateCreated").text = datetime.now(timezone.utc).isoformat()
    body = ET.SubElement(root, "body")
    podcasts = repo.all_podcasts()
    for p in podcasts:
        ET.SubElement(
            body, "outline", type="rss",
            text=repo.display_title(p), title=repo.display_title(p),
            xmlUrl=p.feed_url, **({"htmlUrl": p.website} if p.website else {}),
        )
    ET.indent(root)
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)
    return len(podcasts)
