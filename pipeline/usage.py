"""Meter Cloudflare Workers AI spend and stop before the free daily allocation runs out.

Cloudflare budget alerts do not cap spend, so the only real stop is here, in our own code.
Rules learned the hard way in D:\\AI\\CFComputer (CLAUDE.md section 7 and 10):
- meter from the provider's own `usage.neurons`, never from guesses;
- unknown is not zero: a call we cannot price is logged loudly and charged a pessimistic amount;
- the free allocation is a rolling window, so sum the trailing 24 h, not a calendar day;
- a guard checked only against its own number always passes: `--reconcile` compares it with
  Cloudflare's analytics.

State: state/ai-usage.json (one row per call). CI runners start empty, so publish.py syncs the
file to and from the bucket together with the seen-story list.

    python usage.py               what the local ledger says
    python usage.py --remote      pull the ledger from the bucket first
    python usage.py --reconcile   compare with Cloudflare analytics (needs CLOUDFLARE_ANALYTICS_TOKEN)
"""
import argparse
import os
import sys
import time

from common import STATE_DIR, load_env, load_json, log, save_json

USAGE_FILE = STATE_DIR / "ai-usage.json"
WINDOW_S = 24 * 3600
KEEP_S = 48 * 3600
# neurons per million tokens (input, output), from Cloudflare's price list
RATES = {
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast": (26668, 204805),
    "@cf/meta/llama-3.1-8b-instruct": (25608, 75147),
}
UNKNOWN_CALL_COST = 1000  # charged when a call cannot be priced at all: too high beats too low
DEFAULT_BUDGET = 10000
DEFAULT_STOP_AT = 0.8
DEFAULT_RESERVE = 500     # headroom for the call we are about to make


class BudgetExceeded(RuntimeError):
    """Raised before a Workers AI call that could push us past the stop line."""


def _rows():
    rows = load_json(USAGE_FILE, [])
    return rows if isinstance(rows, list) else []


def used(now=None):
    """Neurons spent in the trailing 24 hours."""
    now = time.time() if now is None else now
    return sum(float(r.get("neurons", 0)) for r in _rows() if r.get("at", 0) > now - WINDOW_S)


def limits(cfg):
    budget = float(cfg.get("daily_neuron_budget", DEFAULT_BUDGET))
    stop = float(cfg.get("stop_at", DEFAULT_STOP_AT))
    return budget, stop, float(cfg.get("call_reserve", DEFAULT_RESERVE))


def check(cfg, now=None):
    """Call before every Workers AI request. Raises BudgetExceeded when close to the daily limit."""
    budget, stop, reserve = limits(cfg)
    spent = used(now)
    if spent + reserve > budget * stop:
        raise BudgetExceeded(
            f"Workers AI budget: {spent:.0f} neurons used in the last 24 h, stop line is "
            f"{budget * stop:.0f} of {budget:.0f} (next call reserves {reserve:.0f}); skipping")
    return spent


def price(model, usage):
    """Return (neurons, exact). Prefer the provider's own figure."""
    usage = usage or {}
    n = usage.get("neurons")
    if isinstance(n, (int, float)) and n >= 0:
        return float(n), True
    rate = RATES.get(model)
    tin, tout = usage.get("prompt_tokens"), usage.get("completion_tokens")
    if rate and isinstance(tin, (int, float)) and isinstance(tout, (int, float)):
        return (tin * rate[0] + tout * rate[1]) / 1e6, False
    log("usage", f"ERROR cannot price a call to {model} (usage={usage}); charging "
                 f"{UNKNOWN_CALL_COST} neurons")
    return float(UNKNOWN_CALL_COST), False


def record(model, usage, now=None):
    """Add one call to the ledger. Call as soon as the provider answers, before parsing."""
    now = time.time() if now is None else now
    neurons, exact = price(model, usage)
    rows = [r for r in _rows() if r.get("at", 0) > now - KEEP_S]
    rows.append({"at": round(now, 1), "model": model, "neurons": round(neurons, 4),
                 "exact": exact})
    save_json(USAGE_FILE, rows)
    log("usage", f"{neurons:.1f} neurons{'' if exact else ' (estimated)'}; "
                 f"{used(now):.0f} used in the last 24 h")
    return neurons


def _reconcile():
    """Compare today's local total with Cloudflare's own analytics (UTC day)."""
    import requests
    from urllib.parse import urlparse
    load_env()
    token = os.environ.get("CLOUDFLARE_ANALYTICS_TOKEN")
    account = os.environ.get("CLOUDFLARE_ACCOUNT_ID") or \
        (urlparse(os.environ.get("R2_ENDPOINT", "")).hostname or "").split(".")[0]
    if not token or not account:
        print("set CLOUDFLARE_ANALYTICS_TOKEN (a token with Account Analytics: Read) to reconcile")
        return 2
    today = time.strftime("%Y-%m-%d", time.gmtime())
    q = ('query{viewer{accounts(filter:{accountTag:"%s"}){aiInferenceAdaptiveGroups(limit:50,'
         'filter:{date_geq:"%s"}){dimensions{date modelId}sum{totalNeurons}count}}}}' % (account, today))
    r = requests.post("https://api.cloudflare.com/client/v4/graphql", json={"query": q},
                      headers={"Authorization": f"Bearer {token}"}, timeout=30)
    data = r.json()
    if data.get("errors"):
        print("analytics error:", data["errors"][0].get("message"))
        return 2
    groups = data["data"]["viewer"]["accounts"][0]["aiInferenceAdaptiveGroups"]
    theirs = sum(g["sum"]["totalNeurons"] for g in groups)
    midnight = time.mktime(time.strptime(today, "%Y-%m-%d")) - time.timezone
    ours = sum(r["neurons"] for r in _rows() if r["at"] >= midnight)
    print(f"Cloudflare today (UTC): {theirs:.1f} neurons over {sum(g['count'] for g in groups)} calls")
    print(f"Local ledger today (UTC): {ours:.1f} neurons")
    print("MISMATCH: the ledger reads low, fix it" if ours < theirs * 0.95 else "ledger agrees (within 5%)")
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description="Workers AI spend ledger")
    p.add_argument("--remote", action="store_true", help="pull the ledger from the bucket first")
    p.add_argument("--reconcile", action="store_true", help="compare with Cloudflare analytics")
    p.add_argument("--budget", type=float, default=DEFAULT_BUDGET)
    args = p.parse_args(argv)
    if args.remote:
        from publish import pull_state
        pull_state()
    if args.reconcile:
        return _reconcile()
    spent = used()
    print(f"{spent:.0f} neurons used in the last 24 h of {args.budget:.0f} "
          f"({spent / args.budget * 100:.1f}%); stop line {args.budget * DEFAULT_STOP_AT:.0f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
