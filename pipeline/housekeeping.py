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
5. Health pass (check_health): the newest episode must be under STALE_HOURS old and the newest
   ai-news run must not have failed, else the job exits 1 (GitHub emails the owner); a recovered
   failure or an episode over 6 min is only a warning. Logged as a `health` event in logs/ai.
The audit logs themselves are kept (a few KB a day); nothing here deletes history.
"""
import argparse
import calendar
import os
import sys
import time

import auditlog
import r2usage
import usage
from common import log
from publish import KEEP_HOURS, Publisher, prune_items, pull_state, push_state

ORPHAN_MIN_AGE_S = 2 * 3600
WARN_AT = 0.7
STALE_HOURS = 20      # episodes come at 8am and 2pm NZ: the longest normal gap is 18 h (2pm -> 8am)
MAX_EPISODE_S = 360   # the 6 min ceiling


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


def recent_runs(repo, workflow="ai-news.yml", n=8):
    """Latest workflow runs from the public GitHub API ([] when it cannot be read: fail soft)."""
    import requests

    headers = {"Accept": "application/vnd.github+json", "User-Agent": "ai-tv-housekeeping"}
    token = os.environ.get("GITHUB_TOKEN")  # optional: only raises the rate limit
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        r = requests.get(f"https://api.github.com/repos/{repo}/actions/workflows/{workflow}/runs",
                         params={"per_page": n}, headers=headers, timeout=20)
        r.raise_for_status()
        return [{"conclusion": x.get("conclusion"), "status": x.get("status"),
                 "created": x.get("created_at", "")} for x in r.json().get("workflow_runs", [])]
    except Exception as e:  # noqa: BLE001 - a health check must never break the housekeeping
        log("housekeeping", f"could not read GitHub runs: {e}")
        return []


def check_health(schedules, runs, now):
    """Pure function: returns (problems, warnings) as lists of strings.

    problems (job fails, GitHub emails the owner): no new episode for STALE_HOURS, or the newest
    finished ai-news run failed. warnings: a failed run in the last 24 h that was followed by a
    success, or an episode longer than MAX_EPISODE_S.
    runs: newest first, each {"conclusion", "status", "created"}.
    """
    problems, warnings = [], []
    for cid, sched in schedules.items():
        items = sched.get("items", [])
        if not items:
            problems.append(f"{cid}: schedule is empty")
            continue
        newest = max(i.get("start", 0) for i in items) / 1000
        age_h = (now - newest) / 3600
        if age_h > STALE_HOURS:
            problems.append(f"{cid}: newest episode started {age_h:.0f} h ago (limit {STALE_HOURS} h)")
        for i in items:
            if i.get("start", 0) / 1000 > now - 24 * 3600 and i.get("duration", 0) > MAX_EPISODE_S:
                warnings.append(f"{cid}: episode {i.get('id')} is {i['duration']:.0f} s "
                                f"(over {MAX_EPISODE_S} s)")
    done = [r for r in runs if r.get("status") == "completed"]
    if done and done[0].get("conclusion") == "failure":
        problems.append(f"newest ai-news run failed ({done[0].get('created')})")
    else:
        for r in done:
            if r.get("conclusion") == "failure" and _age_s(r.get("created"), now) < 24 * 3600:
                warnings.append(f"an ai-news run failed at {r.get('created')} (later runs passed)")
    return problems, warnings


def _age_s(iso, now):
    try:
        return now - calendar.timegm(time.strptime(iso, "%Y-%m-%dT%H:%M:%SZ"))
    except (TypeError, ValueError):
        return 1e12  # unreadable time: treat as old


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

    # Health pass: after the cleanup and summaries (never skipped), before push_state (so the
    # event reaches the bucket log). It only sets the exit code; it cannot break the tidy-up.
    problems, warnings = check_health(schedules, recent_runs(os.environ.get("GITHUB_REPOSITORY",
                                                                            "timothylok/tv-app")), now)
    for w in warnings:
        print(f"::warning::health: {w}")
    for pr in problems:
        print(f"::error::health: {pr}")
    log("housekeeping", f"health: {len(problems)} problem(s), {len(warnings)} warning(s)")
    auditlog.event("ai", event="health", problems=problems, warnings=warnings)

    if not args.dry_run:
        push_state()
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
