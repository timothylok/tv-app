"""Daily housekeeping: tidy the bucket, measure what we use, write the daily audit summaries.

    python housekeeping.py --dry-run     show what would be deleted; changes nothing remote
    python housekeeping.py               do it (this is what the daily workflow runs)

1. Schedules: drop items that ended more than KEEP_HOURS ago (the app never needs them).
2. Orphans: delete episode files that no schedule item points to (failed runs, pruned items)
   and are older than 2 hours (so an upload in progress is never touched).
3. Measure: bucket storage (exact, from a listing), month-to-date operations, and Workers AI
   spend, each against its free tier; write a daily-summary line to logs/r2 and logs/ai.
4. Warn (GitHub warning annotation) when anything is over 70% of its free tier; the publishing
   runs themselves stop at 80% (r2usage.STOP_AT, usage.DEFAULT_STOP_AT).
The audit logs themselves are kept (a few KB a day); nothing here deletes history.
"""
import argparse
import sys
import time

import auditlog
import r2usage
import usage
from common import log
from publish import KEEP_HOURS, Publisher, prune_items, pull_state, push_state

ORPHAN_MIN_AGE_S = 2 * 3600
WARN_AT = 0.7


def plan_cleanup(objs, schedules, now):
    """Pure function: decide what to delete. Returns (new_schedules, keys_to_delete).

    objs: list of {"Key", "LastModified" (aware datetime)}; schedules: {channel_id: schedule dict}.
    """
    now_ms = int(now * 1000)
    new, referenced, dele = {}, set(), []
    for cid, sched in schedules.items():
        kept, old = prune_items(sched.get("items", []), now_ms)
        for i in kept:
            referenced.add(f"channels/{cid}/episodes/{i['id']}.mp4")
        for i in old:
            dele.append(f"channels/{cid}/episodes/{i['id']}.mp4")
        if old:
            new[cid] = {**sched, "items": kept, "updated": now_ms}
    for o in objs:
        k = o["Key"]
        if "/episodes/" not in k or not k.endswith(".mp4") or k in referenced or k in dele:
            continue
        cid = k.split("/")[1]
        if cid not in schedules:  # unknown channel: leave it alone, a human should look
            continue
        if now - o["LastModified"].timestamp() > ORPHAN_MIN_AGE_S:
            dele.append(k)
    return new, sorted(set(dele))


def main(argv=None):
    p = argparse.ArgumentParser(description="Daily bucket housekeeping and usage summary")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args(argv)
    now = time.time()

    pub = Publisher()
    if not args.dry_run:
        pull_state()  # the AI ledger, for the summary
    objs = pub.list_all("channels/")
    schedules = {}
    for o in objs:
        parts = o["Key"].split("/")
        if len(parts) == 3 and parts[2] == "schedule.json":
            schedules[parts[1]] = pub.get_json(o["Key"], {"items": []})

    new_scheds, to_delete = plan_cleanup(objs, schedules, now)
    for k in to_delete:
        log("housekeeping", f"{'would delete' if args.dry_run else 'delete'} {k}")
    if not args.dry_run:
        for cid, sched in new_scheds.items():
            pub.put_json(f"channels/{cid}/schedule.json", sched)
        for k in to_delete:
            pub.delete(k)
            auditlog.event("r2", event="housekeeping-delete", key=k)

    size, n, _ = r2usage.measure_storage(pub)
    _, month = r2usage.month_counts(pub)
    a = month.get("A", 0) + r2usage.tally["A"]
    b = month.get("B", 0) + r2usage.tally["B"]
    r2 = {"storage_bytes": size, "objects": n,
          "storage_pct": round(size / r2usage.FREE_STORAGE_BYTES * 100, 3),
          "class_a_month": a, "class_a_pct": round(a / r2usage.FREE_CLASS_A * 100, 3),
          "class_b_month_ours": b,
          "class_b_note": "TV reads are not visible to us; see r2usage.py for the estimate",
          "deleted": len(to_delete), "keep_hours": KEEP_HOURS}
    auditlog.event("r2", event="daily-summary", **r2)

    spent = usage.used(now)
    rows = [r for r in usage._rows() if r.get("at", 0) > now - usage.WINDOW_S]
    ai = {"neurons_24h": round(spent, 1), "calls_24h": len(rows),
          "budget": usage.DEFAULT_BUDGET, "pct": round(spent / usage.DEFAULT_BUDGET * 100, 2)}
    theirs = usage.provider_24h(now)  # Cloudflare account-wide (includes other projects); None = no token
    if theirs is not None:
        ai["cloudflare_24h"] = round(theirs, 1)
        ai["cloudflare_pct"] = round(theirs / usage.DEFAULT_BUDGET * 100, 2)
    auditlog.event("ai", event="daily-summary", **ai)

    log("housekeeping", f"R2: {size / 1024 ** 2:.1f} MB ({r2['storage_pct']}% of 10 GB), "
                        f"{a} Class A ops this month ({r2['class_a_pct']}% of 1M)")
    log("housekeeping", f"AI: {spent:.0f} neurons in 24 h ({ai['pct']}% of {usage.DEFAULT_BUDGET})")
    if theirs is None:
        log("housekeeping", "Cloudflare account-wide AI figure: not available (no analytics token or "
                            "analytics error); ledger only")
    else:
        log("housekeeping", f"Cloudflare account-wide AI (all projects): {theirs:.0f} neurons in 24 h "
                            f"({ai['cloudflare_pct']}% of {usage.DEFAULT_BUDGET})")
    for label, pct in (("R2 storage", r2["storage_pct"]), ("R2 Class A", r2["class_a_pct"]),
                       ("Workers AI (ours)", ai["pct"]),
                       ("Workers AI (account-wide)", ai.get("cloudflare_pct", 0))):
        if pct > WARN_AT * 100:
            print(f"::warning::{label} is at {pct}% of its free tier")

    if not args.dry_run:
        push_state()
    return 0


if __name__ == "__main__":
    sys.exit(main())
