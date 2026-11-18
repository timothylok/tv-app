"""Step 5: upload an episode to R2 and append it to the channel's static schedule.

Bucket layout (all public-read through R2_PUBLIC_BASE_URL):
  channels.json                          list of channels (number, name, schedule url)
  channels/<id>/schedule.json            append-only items with absolute epoch-ms times
  channels/<id>/episodes/<ep>.mp4
  channels/<id>/filler.mp4               looping ident for gaps
Secrets come only from the environment / git-ignored .env, and are never logged.
"""
import os
import time
from pathlib import Path

from common import duration, load_env, log

KEEP_HOURS = 48          # items that ended longer ago than this are pruned
LEAD_SECONDS = 90        # a new item never starts sooner than this after publishing
MP4_CACHE = "public, max-age=31536000, immutable"
JSON_CACHE = "max-age=30"


class Publisher:
    def __init__(self):
        load_env()
        need = ["R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY",
                "R2_BUCKET", "R2_PUBLIC_BASE_URL"]
        missing = [k for k in need if not os.environ.get(k)]
        if missing:
            raise RuntimeError("missing settings: " + ", ".join(missing))
        import boto3
        from botocore.config import Config
        self.bucket = os.environ["R2_BUCKET"]
        self.base = os.environ["R2_PUBLIC_BASE_URL"].rstrip("/")
        self.s3 = boto3.client(
            "s3", endpoint_url=os.environ["R2_ENDPOINT"], region_name="auto",
            aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
            aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
            config=Config(retries={"max_attempts": 5, "mode": "standard"}))

    def url(self, key):
        return f"{self.base}/{key}"

    def put_file(self, path, key, content_type, cache):
        self.s3.upload_file(str(path), self.bucket, key,
                            ExtraArgs={"ContentType": content_type, "CacheControl": cache})

    def get_json(self, key, default):
        import json
        from botocore.exceptions import ClientError
        try:
            body = self.s3.get_object(Bucket=self.bucket, Key=key)["Body"].read()
            return json.loads(body.decode("utf-8"))
        except ClientError as e:
            if e.response["Error"]["Code"] in ("NoSuchKey", "404"):
                return default
            raise

    def put_json(self, key, data):
        import json
        self.s3.put_object(Bucket=self.bucket, Key=key, ContentType="application/json",
                           CacheControl=JSON_CACHE,
                           Body=json.dumps(data, ensure_ascii=False, indent=1).encode("utf-8"))

    def delete(self, key):
        self.s3.delete_object(Bucket=self.bucket, Key=key)


def ensure_filler(pub, channel, work):
    key = f"channels/{channel['id']}/filler.mp4"
    from botocore.exceptions import ClientError
    try:
        pub.s3.head_object(Bucket=pub.bucket, Key=key)
    except ClientError:
        from render import render_filler
        local = Path(work) / "filler_out.mp4"
        render_filler(channel, Path(work), local)
        pub.put_file(local, key, "video/mp4", MP4_CACHE)
        log("publish", "uploaded filler.mp4")
    return pub.url(key)


def publish_episode(channel, ep_id, title, mp4_path, work, now=None):
    """Upload one episode and append it to the schedule. Returns the schedule item."""
    pub = Publisher()
    cid = channel["id"]
    now_ms = int((now if now is not None else time.time()) * 1000)
    dur = duration(mp4_path)
    key = f"channels/{cid}/episodes/{ep_id}.mp4"
    pub.put_file(mp4_path, key, "video/mp4", MP4_CACHE)  # media first, schedule second
    log("publish", f"uploaded {key} ({Path(mp4_path).stat().st_size // 1024} KB)")

    filler = ensure_filler(pub, channel, work)
    skey = f"channels/{cid}/schedule.json"
    sched = pub.get_json(skey, {"channel": cid, "items": []})
    items = sched.get("items", [])

    if any(i.get("id") == ep_id for i in items):  # re-run of the same episode: no duplicate
        log("publish", f"{ep_id} already scheduled")
        return next(i for i in items if i.get("id") == ep_id)

    tail = max([i["end"] for i in items], default=0)
    start = max(tail, now_ms + LEAD_SECONDS * 1000)
    item = {"id": ep_id, "title": title, "url": pub.url(key),
            "start": start, "end": start + int(dur * 1000), "duration": round(dur, 2)}
    items.append(item)

    cutoff = now_ms - KEEP_HOURS * 3600 * 1000
    old = [i for i in items if i["end"] < cutoff]
    items = [i for i in items if i["end"] >= cutoff]
    sched = {"channel": cid, "name": channel["name"], "filler": filler,
             "updated": now_ms, "items": items}
    pub.put_json(skey, sched)
    log("publish", f"schedule: {len(items)} items, new one starts in "
                   f"{(start - now_ms) // 1000}s")

    for i in old:  # best effort: the schedule no longer points to them
        try:
            pub.delete(f"channels/{cid}/episodes/{i['id']}.mp4")
        except Exception as e:  # noqa: BLE001
            log("publish", f"could not delete old episode {i['id']}: {e}")

    update_channels(pub, channel, skey)
    return item


def update_channels(pub, channel, schedule_key):
    data = pub.get_json("channels.json", {"channels": []})
    chans = [c for c in data.get("channels", []) if c.get("id") != channel["id"]]
    chans.append({"id": channel["id"], "number": channel["number"],
                  "name": channel["name"], "schedule": pub.url(schedule_key)})
    chans.sort(key=lambda c: c["number"])
    pub.put_json("channels.json", {"channels": chans})


STATE_KEY = "state/seen.json"
USAGE_KEY = "state/ai-usage.json"


def pull_state():
    """CI runners start empty: fetch the seen-story list from the bucket so stories are not repeated."""
    from common import STATE_DIR, save_json
    data = Publisher().get_json(STATE_KEY, None)
    if isinstance(data, list):
        save_json(STATE_DIR / "seen.json", data)
        log("publish", f"state: {len(data)} seen stories")
    usage = Publisher().get_json(USAGE_KEY, None)
    if isinstance(usage, list):
        save_json(STATE_DIR / "ai-usage.json", usage)


def push_state():
    from common import STATE_DIR, load_json
    pub = Publisher()
    pub.put_json(STATE_KEY, load_json(STATE_DIR / "seen.json", []))
    pub.put_json(USAGE_KEY, load_json(STATE_DIR / "ai-usage.json", []))
