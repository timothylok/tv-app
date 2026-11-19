"""Step 1: fetch RSS feeds, drop stories already used, return fresh ones."""
import hashlib
import html
import re
import time

import feedparser

from common import STATE_DIR, load_json, log, save_json

SEEN_FILE = STATE_DIR / "seen.json"
MAX_AGE_DAYS = 7


def _clean(text):
    text = re.sub(r"<[^>]+>", " ", text or "")
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def _body(e):
    """The fullest text the feed itself carries: full content when present, else the summary."""
    best = _clean(e.get("summary"))
    for c in e.get("content") or []:
        t = _clean(c.get("value"))
        if len(t) > len(best):
            best = t
    return best


def story_id(link, title):
    return hashlib.sha1((link or title).encode("utf-8")).hexdigest()[:16]


def fetch_stories(channel, limit=12):
    """Return up to `limit` newest unseen stories: dicts of id/title/summary/source/link."""
    seen = set(load_json(SEEN_FILE, []))
    cutoff = time.time() - MAX_AGE_DAYS * 86400
    stories = []
    cap = channel.get("max_per_source", 3)
    for entry in channel["feeds"]:
        url = entry["url"] if isinstance(entry, dict) else entry
        try:
            feed = feedparser.parse(url, agent="ai-tv-pipeline/0.1")
        except Exception as e:  # fail soft: one bad feed must not stop the run
            log("ingest", f"skip {url}: {e}")
            continue
        if not feed.entries:
            log("ingest", f"no entries from {url} (status {feed.get('status', '?')})")
            continue
        source = (entry.get("name") if isinstance(entry, dict) else None) \
            or _clean(feed.feed.get("title", "")) or url
        for e in feed.entries[:15]:
            ts = e.get("published_parsed") or e.get("updated_parsed")
            when = time.mktime(ts) if ts else time.time()
            if when < cutoff:
                continue
            title = _clean(e.get("title"))
            link = e.get("link", "")
            sid = story_id(link, title)
            if not title or sid in seen:
                continue
            stories.append({"id": sid, "title": title, "source": source, "link": link,
                            "summary": _body(e)[:1800], "when": when})
        log("ingest", f"{source}: {len(feed.entries)} entries")
    stories.sort(key=lambda s: s["when"], reverse=True)
    # de-dupe near-identical titles across feeds
    out, titles, per_source = [], set(), {}
    for s in stories:
        key = re.sub(r"\W+", "", s["title"].lower())[:40]
        if key in titles or per_source.get(s["source"], 0) >= cap:
            continue
        titles.add(key)
        per_source[s["source"]] = per_source.get(s["source"], 0) + 1
        out.append(s)
    log("ingest", f"{len(out)} fresh stories, using {min(limit, len(out))}")
    return out[:limit]


def mark_seen(story_ids):
    seen = load_json(SEEN_FILE, [])
    save_json(SEEN_FILE, sorted(set(seen) | set(story_ids))[-2000:])
