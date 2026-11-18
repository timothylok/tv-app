"""Daily audit logs, one stream per resource: logs/ai/YYYY-MM-DD.jsonl and logs/r2/YYYY-MM-DD.jsonl.

One JSON object per line: {"ts": UTC ISO time, "run": GitHub run id or "local", ...fields}.
Events are written to the local logs/ folder at once (git-ignored) and, on publishing runs,
merged into the same-named files in the bucket by flush_remote(). R2 has no append, so the
day's file is read, extended and written back; runs never overlap (shared concurrency group).
Logs hold counts, sizes and object names only: no keys, tokens or story text.
"""
import json
import os
import time

from common import ROOT, log

LOG_DIR = ROOT / "logs"
_pending = {}  # (stream, day) -> [json lines] not yet written to the bucket


def event(stream, **fields):
    row = {"ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
           "run": os.environ.get("GITHUB_RUN_ID", "local"), **fields}
    line = json.dumps(row, ensure_ascii=False)
    day = row["ts"][:10]
    _pending.setdefault((stream, day), []).append(line)
    try:
        d = LOG_DIR / stream
        d.mkdir(parents=True, exist_ok=True)
        with open(d / f"{day}.jsonl", "a", encoding="utf-8", newline="\n") as f:
            f.write(line + "\n")
    except OSError as e:  # a full disk must not stop the channel
        log("audit", f"could not write local log: {e}")


def pending_streams():
    return {s for (s, _d), lines in _pending.items() if lines}


def flush_remote(pub):
    """Merge this process's events into the bucket's daily files. Safe to call twice."""
    for (stream, day), lines in list(_pending.items()):
        if not lines:
            continue
        key = f"logs/{stream}/{day}.jsonl"
        old = pub.get_bytes(key, quiet=True) or b""
        body = old + ("\n".join(lines) + "\n").encode("utf-8")
        pub.put_bytes(key, body, "application/x-ndjson", "no-cache", quiet=True)
        _pending[(stream, day)] = []
