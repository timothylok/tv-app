"""Cloudflare's own figures (GraphQL analytics) for Workers AI and R2, and a check of our ledgers.

A guard that is only checked against the number it computes itself always passes, so this module
asks Cloudflare. Needs CLOUDFLARE_ANALYTICS_TOKEN (Account Analytics: Read, this account only).

    python reconcile.py            report: Cloudflare today vs our ledgers (nothing is changed)
    python reconcile.py --probe    do a known amount of work (1 tiny AI call, 3 PUT, 2 GET, 1 HEAD,
                                   3 DELETE on a throw-away key) and compare the DELTAS with Cloudflare

Analytics lag by a few minutes, so recent activity may not show yet; the probe waits for it.
"""
import argparse
import json
import os
import sys
import time
from urllib.parse import urlparse

import requests

from common import load_env, log

API = "https://api.cloudflare.com/client/v4/graphql"


def available():
    load_env()
    return bool(os.environ.get("CLOUDFLARE_ANALYTICS_TOKEN"))


def _account():
    return os.environ.get("CLOUDFLARE_ACCOUNT_ID") or \
        (urlparse(os.environ.get("R2_ENDPOINT", "")).hostname or "").split(".")[0]


def _gql(inner):
    load_env()
    r = requests.post(API, json={"query": 'query{viewer{accounts(filter:{accountTag:"%s"}){%s}}}'
                                 % (_account(), inner)},
                      headers={"Authorization": "Bearer " + os.environ["CLOUDFLARE_ANALYTICS_TOKEN"]},
                      timeout=30)
    j = r.json()
    if j.get("errors"):
        raise RuntimeError("analytics: " + str(j["errors"][0].get("message")))
    return j["data"]["viewer"]["accounts"][0]


def iso(ts):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))


def ai_totals(since_ts):
    """(neurons, calls) Cloudflare billed from the hour containing since_ts until now."""
    hour = since_ts - since_ts % 3600  # buckets are hourly: start at the hour boundary (errs high)
    g = _gql('aiInferenceAdaptiveGroups(limit:200,filter:{datetime_geq:"%s"}){sum{totalNeurons}count}'
             % iso(hour))["aiInferenceAdaptiveGroups"]
    return sum(x["sum"]["totalNeurons"] for x in g), sum(x["count"] for x in g)


def op_class(action):
    """Class of an R2 operation by its S3 action name (Cloudflare's pricing page)."""
    if action.startswith(("Get", "Head")) or action == "UsageSummary":
        return "B"
    if action.startswith("Delete") or action == "AbortMultipartUpload":
        return "free"
    return "A"


def r2_ops(since_ts):
    """({"A","B","free"} totals, {action: requests}) since since_ts (analytics are per 5 minutes)."""
    g = _gql('r2OperationsAdaptiveGroups(limit:200,filter:{datetime_geq:"%s"}){dimensions{actionType}'
             'sum{requests}}' % iso(since_ts))["r2OperationsAdaptiveGroups"]
    by, tot = {}, {"A": 0, "B": 0, "free": 0}
    for x in g:
        a, n = x["dimensions"]["actionType"], x["sum"]["requests"]
        by[a] = by.get(a, 0) + n
        tot[op_class(a)] += n
    return tot, by


def r2_storage(bucket=None):
    """(bytes, objects, datetime) of the newest storage sample for our bucket."""
    bucket = bucket or os.environ.get("R2_BUCKET")
    g = _gql('r2StorageAdaptiveGroups(limit:1,filter:{datetime_geq:"%s",bucketName:"%s"},'
             'orderBy:[datetime_DESC]){dimensions{datetime}max{payloadSize metadataSize objectCount}}'
             % (iso(time.time() - 86400), bucket))["r2StorageAdaptiveGroups"]
    if not g:
        return None
    m = g[0]["max"]
    return m["payloadSize"] + m["metadataSize"], m["objectCount"], g[0]["dimensions"]["datetime"]


def midnight_utc(now=None):
    now = time.time() if now is None else now
    return now - now % 86400


def report():
    import auditlog  # noqa: F401  (keeps log files consistent when run from CI)
    from publish import Publisher, pull_state
    import usage
    pull_state()
    pub = Publisher()
    mid = midnight_utc()
    code = 0

    n_cf, c_cf = ai_totals(mid)
    rows = [r for r in usage._rows() if r.get("at", 0) >= mid]
    n_us = sum(r["neurons"] for r in rows)
    print(f"Workers AI today (UTC): Cloudflare {n_cf:.1f} neurons / {c_cf} calls; "
          f"ledger {n_us:.1f} neurons / {len(rows)} calls")
    if n_us < n_cf * 0.95:
        print("  ledger is LOWER than Cloudflare: calls made before the guard existed, or runs that "
              "never saved the ledger. The guard now takes the larger of the two (usage.used_effective).")
        code = 1

    tot, by = r2_ops(mid)
    print(f"R2 operations today (UTC), Cloudflare: Class A {tot['A']}, Class B {tot['B']} "
          f"(includes TV and browser reads), free {tot['free']}")
    print("  by action:", ", ".join(f"{k} {v}" for k, v in sorted(by.items(), key=lambda kv: -kv[1])[:8]))

    st = r2_storage()
    mine = sum(o["Size"] for o in pub.list_all())
    if st:
        print(f"R2 storage: Cloudflare {st[0]} bytes / {st[1]} objects (sample {st[2]}); "
              f"our listing {mine} bytes")
        if abs(st[0] - mine) > max(1024, mine * 0.01):
            print("  storage differs by more than 1% (a recent upload or delete may not be sampled yet)")
    return code


def probe():
    """Known work, then compare the deltas with what Cloudflare reports."""
    import usage
    from llm import generate_json
    from publish import Publisher
    import r2usage
    t0 = time.time()
    n0, c0 = ai_totals(t0)
    r0, by0 = r2_ops(t0 - 3600)
    log("probe", f"baseline: AI {n0:.2f} neurons / {c0} calls this hour; "
                 f"PutObject {by0.get('PutObject', 0)}, DeleteObject {by0.get('DeleteObject', 0)}")

    before = usage.used()
    generate_json({"provider": "workers-ai", "max_tokens": 30},
                  "Reply with JSON only.", 'Return {"ok": true}')
    ours_ai = usage.used() - before

    pub = Publisher()
    keys = [f"state/probe-{int(t0)}-{i}.txt" for i in range(3)]
    for k in keys:
        pub.put_bytes(k, b"probe", "text/plain", "no-store")
    pub.get_bytes(keys[0])
    pub.get_bytes(keys[1])
    pub.head(keys[2])
    for k in keys:
        pub.delete(k)
    print(f"ours: AI {ours_ai:.2f} neurons / 1 call; R2 PUT 3, GET 2, HEAD 1, DELETE 3 "
          f"(tally {r2usage.tally})")

    log("probe", "waiting for Cloudflare analytics (up to 8 minutes)...")
    ok = False
    for _ in range(32):
        time.sleep(15)
        try:
            n1, c1 = ai_totals(t0)
            _, by1 = r2_ops(t0 - 3600)
        except RuntimeError as e:
            log("probe", str(e))
            continue
        dput = by1.get("PutObject", 0) - by0.get("PutObject", 0)
        ddel = by1.get("DeleteObject", 0) - by0.get("DeleteObject", 0)
        if c1 - c0 >= 1 and dput >= 3 and ddel >= 3:
            ok = True
            break
    dget = by1.get("GetObject", 0) - by0.get("GetObject", 0)
    dhead = by1.get("HeadObject", 0) - by0.get("HeadObject", 0)
    print(f"Cloudflare delta: AI {n1 - n0:.2f} neurons / {c1 - c0} calls; "
          f"PutObject +{dput}, GetObject +{dget}, HeadObject +{dhead}, DeleteObject +{ddel}")
    if not ok:
        print("analytics had not caught up within 8 minutes: inconclusive, run again later")
        return 2
    ai_ok = abs((n1 - n0) - ours_ai) <= max(0.05, 0.02 * ours_ai)
    r2_ok = dput == 3 and ddel == 3 and dget >= 2 and dhead >= 1
    print(f"AI neurons agree: {'YES' if ai_ok else 'NO'}   "
          f"R2 operation counts agree: {'YES' if r2_ok else 'NO (extra GET/HEAD can be real TV reads)'}")
    return 0 if ai_ok and r2_ok else 1


def main(argv=None):
    p = argparse.ArgumentParser(description="Reconcile our ledgers with Cloudflare analytics")
    p.add_argument("--probe", action="store_true")
    args = p.parse_args(argv)
    if not available():
        print("set CLOUDFLARE_ANALYTICS_TOKEN (Account Analytics: Read) in .env")
        return 2
    try:
        return probe() if args.probe else report()
    finally:
        if args.probe:
            from publish import push_state
            push_state()


if __name__ == "__main__":
    sys.exit(main())
