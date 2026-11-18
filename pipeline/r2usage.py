"""Count our R2 operations, measure storage, and stop publishing before the free tier runs out.

Cloudflare R2 free tier (published pricing, re-check in the dashboard): 10 GB-month storage,
1 million Class A operations a month (PUT, LIST, COPY), 10 million Class B (GET, HEAD) a month,
no egress fees. DELETE is free.

What we can and cannot measure:
- storage: exact, by listing the bucket (a LIST is itself a Class A op and is counted);
- our own operations: exact, every call goes through publish.Publisher which calls count();
- TV reads (public GETs by the Android app): NOT visible to us. Estimate: a TV polls the schedule
  every 60 s = 1,440 Class B a day (about 43,000 a month) plus a few range reads per episode,
  so 10 million Class B covers roughly 200 always-on TVs. Raise the app's poll interval before that.

Month counters live in the bucket (state/r2-ops.json) because CI runners start empty.
"""
import time

import auditlog
from common import log

FREE_STORAGE_BYTES = 10 * 1024 ** 3
FREE_CLASS_A = 1_000_000
FREE_CLASS_B = 10_000_000
STOP_AT = 0.8
COUNTERS_KEY = "state/r2-ops.json"
CLASS = {"PUT": "A", "LIST": "A", "COPY": "A", "GET": "B", "HEAD": "B", "DELETE": "free"}

tally = {"A": 0, "B": 0, "free": 0}  # this process only; merged into the month counters by commit()


class R2BudgetExceeded(RuntimeError):
    """Raised before publishing when storage or Class A operations are near the free tier."""


def count(op, key="", nbytes=0):
    cls = CLASS[op]
    tally[cls] += 1
    auditlog.event("r2", event="op", op=op, cls=cls, key=key, bytes=nbytes)


def month_key(now=None):
    return time.strftime("%Y-%m", time.gmtime(now))


def month_counts(pub):
    data = pub.get_json(COUNTERS_KEY, {}) or {}
    return data, data.get(month_key(), {"A": 0, "B": 0})


def measure_storage(pub):
    """Return (total_bytes, object_count, objects). Exact: it is the bucket's own listing."""
    objs = pub.list_all()
    return sum(o["Size"] for o in objs), len(objs), objs


def check(pub):
    """Call before the expensive part of a publishing run."""
    size, n, _ = measure_storage(pub)
    _, month = month_counts(pub)
    a = month.get("A", 0) + tally["A"]
    reasons = []
    if size > FREE_STORAGE_BYTES * STOP_AT:
        reasons.append(f"storage {size / 1024 ** 2:.0f} MB is over {STOP_AT:.0%} of 10 GB")
    if a > FREE_CLASS_A * STOP_AT:
        reasons.append(f"{a} Class A operations this month is over {STOP_AT:.0%} of 1,000,000")
    if reasons:
        msg = "R2 free tier: " + "; ".join(reasons) + "; skipping"
        auditlog.event("r2", event="blocked", reason=msg, storage_bytes=size, class_a_month=a)
        raise R2BudgetExceeded(msg)
    log("r2", f"storage {size / 1024 ** 2:.1f} MB in {n} objects, {a} Class A ops this month")
    return size, n, a


def commit(pub):
    """Fold this run's operation counts into the month counters in the bucket."""
    data, month = month_counts(pub)
    count("PUT", COUNTERS_KEY)  # the write we are about to do
    # the daily-log merge that flush_remote() does next: one GET and one PUT per stream
    extra = len(auditlog.pending_streams())
    a = month.get("A", 0) + tally["A"] + extra
    b = month.get("B", 0) + tally["B"] + extra
    data[month_key()] = {"A": a, "B": b}
    # keep only the last 13 months
    for k in sorted(data)[:-13]:
        del data[k]
    pub.put_json(COUNTERS_KEY, data, quiet=True)
    for k in tally:
        tally[k] = 0
    return a, b
