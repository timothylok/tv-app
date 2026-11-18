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
    python usage.py --reconcile   compare with Cloudflare analytics (see reconcile.py, --probe there)
"""
import argparse
import os
import sys
import time

import auditlog
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
DEFAULT_STOP_AT = 0.7   # shared with D:\AI\voice-ecosystem, see pipeline.md
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


_provider_cache = {}


def provider_24h(now=None):
    """Cloudflare's own count for the trailing 24 h (hour buckets, rounded up), or None.

    Needs CLOUDFLARE_ANALYTICS_TOKEN. It catches spend our ledger never saw (calls from another
    machine, runs that crashed before saving). Cached for a minute; analytics lag a few minutes."""
    now = time.time() if now is None else now
    hit = _provider_cache.get("v")
    if hit and now - hit[0] < 60:
        return hit[1]
    try:
        import reconcile
        if not reconcile.available():
            return None
        val = reconcile.ai_totals(now - WINDOW_S)[0]
    except Exception as e:  # noqa: BLE001  analytics down: fall back to the ledger, say so
        log("usage", f"WARNING could not read Cloudflare analytics ({e}); using the ledger only")
        return None
    _provider_cache["v"] = (now, val)
    return val


def used_effective(now=None):
    """The larger of our ledger and Cloudflare's own figure: the safe number to stop on."""
    ours = used(now)
    theirs = provider_24h(now)
    if theirs is not None and theirs > ours * 1.05 + 25:
        auditlog.event("ai", event="ledger-low", ledger=round(ours, 1), cloudflare=round(theirs, 1))
        log("usage", f"Cloudflare reports {theirs:.0f} neurons vs ledger {ours:.0f}; using the higher")
    return max(ours, theirs or 0.0)


def limits(cfg):
    budget = float(cfg.get("daily_neuron_budget", DEFAULT_BUDGET))
    stop = float(cfg.get("stop_at", DEFAULT_STOP_AT))
    return budget, stop, float(cfg.get("call_reserve", DEFAULT_RESERVE))


def check(cfg, now=None):
    """Call before every Workers AI request. Raises BudgetExceeded when close to the daily limit."""
    budget, stop, reserve = limits(cfg)
    spent = used_effective(now)
    if spent + reserve > budget * stop:
        auditlog.event("ai", event="blocked", used_24h=round(spent, 1), budget=budget,
                       stop_line=budget * stop, reserve=reserve)
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
    auditlog.event("ai", event="call", model=model, neurons=round(neurons, 4), exact=exact,
                   used_24h=round(used(now), 1))
    log("usage", f"{neurons:.1f} neurons{'' if exact else ' (estimated)'}; "
                 f"{used(now):.0f} used in the last 24 h")
    return neurons


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
        import reconcile
        return reconcile.report()
    spent = used()
    print(f"{spent:.0f} neurons used in the last 24 h of {args.budget:.0f} "
          f"({spent / args.budget * 100:.1f}%); stop line {args.budget * DEFAULT_STOP_AT:.0f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
