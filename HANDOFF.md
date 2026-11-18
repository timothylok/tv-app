# HANDOFF - AI TV channel platform

Last updated 2026-11-18. Read `pipeline.md` for how everything works; this file is state, open items and gotchas.

## State

Working end to end in the cloud, with no dependence on the user's PC:

- Cloudflare Worker `ai-tv-scheduler` (cron) starts the GitHub workflow `ai-news` (8am, 2pm, 8pm NZ); the workflow ingests feeds, writes a script with Workers AI, voices it with edge-tts, renders an MP4 with ffmpeg and publishes it to the R2 bucket `ai-tv-channels` with an append-only `schedule.json`.
- The Android TV app (`app/`, package `com.superghosttim.aichannels`, `TvActivity`) plays the schedule live: server-time seek-in, filler in gaps, channel up/down, number keys, OK banner, Back channel list.
- Repo: https://github.com/timothylok/tv-app (public), branch `main`. Public bucket URL: `https://pub-31476484de9245329a3f35502c44e692.r2.dev` (r2.dev dev URL: MVP only, move to a custom domain, e.g. a subdomain of fittertrack.com, before a Play release).
- Verified on 2026-11-18: three GitHub runs passed (one started by the Worker cron: dispatch HTTP 204), an episode built on GitHub played on the Google TV, the Workers AI guard ledger reached the bucket with exact neuron figures.

## Added in the latest work (verify before relying on it)

- **Workers AI guard** (`usage.py`): rolling 24 h ledger from `usage.neurons`, stops at `stop_at` 0.9 of 10,000 (yaml `daily_neuron_budget`, `stop_at`). It also takes Cloudflare's own account-wide figure when `CLOUDFLARE_ANALYTICS_TOKEN` is set (confirmed working on the GitHub runner 2026-11-19). `D:\AI\voice-ecosystem` (active) shares the allowance (about 3,000 neurons a day, peak 5,100; see memory `shared-cloudflare-ai-account`). It was 0.7 for a day, but account-wide use hit 7,300 on 2026-11-18 and would have skipped episodes, so it is 0.9 now; at the top, voice-ecosystem is the one that would run out. Housekeeping logs the account-wide figure and warns at 70%.
- Workflows use actions/checkout@v7 and setup-python@v7 and are pinned to `ubuntu-24.04` (ubuntu-latest moves to 26.04; try `ubuntu-26.04` deliberately).
- **R2 guard** (`r2usage.py`): counts every bucket call (`publish.Publisher`), measures storage by listing, stops at 80% of 10 GB or 1M Class A a month. Month counters in `state/r2-ops.json`.
- **Daily audit logs** (`auditlog.py`): `logs/ai/<date>.jsonl`, `logs/r2/<date>.jsonl`, local (git-ignored) and in the bucket.
- **Housekeeping** (`housekeeping.py`, `.github/workflows/housekeeping.yml`): prunes old schedule items, deletes orphan episodes, writes daily summaries. Second Worker cron `30 11 * * *`.
- **Reconcile** (`reconcile.py`, `usage.py --reconcile`): compares our ledgers with Cloudflare analytics; `--probe` does a known amount of work and compares deltas.
- `pipeline.md` documents all of it.
- Unit checks of cleanup planning and both guards passed locally (scratch scripts, not in the repo). Not yet run for real: `housekeeping.py` against the bucket, the housekeeping workflow, the second Worker cron, and the cloud use of `CLOUDFLARE_ANALYTICS_TOKEN`.

## Do next (in this order)

1. Add GitHub Actions secret `CLOUDFLARE_ANALYTICS_TOKEN` (the user does this: value is in `.env`).
2. Deploy the Worker (`cd worker && npx wrangler deploy`) so the housekeeping cron exists (wrangler is logged in via OAuth on this machine).
3. Run `python pipeline/housekeeping.py --dry-run`, then the real one; run the `housekeeping` workflow once from the Actions tab; check `logs/` in the bucket.
4. Check the reconcile probe result (`python pipeline/reconcile.py --probe`); until it agrees, the "ledger matches Cloudflare" claim is unproven. The first plain `reconcile.py` report showed the ledger below Cloudflare (890 vs 280 neurons today) because calls before the guard, and dev-machine runs, were never in the bucket ledger.
5. Piper fallback voice (edge-tts may be blocked from a runner some day; today it works on GitHub).
6. Lengthen episodes toward the 4-minute target (fetch article text; RSS summaries are short, episodes are about 2 minutes).
7. Test on the Android 10 box (192.168.3.79:5555) when it is connected.

## Open items and assumptions

- **Test devices:** the Google_TV at 192.168.3.97 (wireless debugging port changes: `adb mdns services`) is the one used and verified (API 34); the user chose it over the Android 10 box for now. Not checked: network loss, multi-hour soak, back-to-back gapless handover.
- **Expiring tokens:** the Worker's GitHub token (`GITHUB_TOKEN`, fine-grained, Actions read/write, this repo only) expires 2026-12-18; the analytics token was created with a 90-day expiry (about 2027-02-16). Renew both: the Worker stops starting runs silently otherwise.
- **Secrets** live only in `.env` (git-ignored; also `.env.bak`, `.env.*` ignored), GitHub Actions secrets and the Worker secret. Names: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ANALYTICS_TOKEN`, `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_PUBLIC_BASE_URL`. Never paste them in chat.
- The bucket is public, so `state/` and `logs/` are readable by anyone (counts and names only).
- The bucket still holds old test items (`Test episode`, `Live test episode`); they age out after 48 hours.
- Feeds in `channels/ai-news.yaml` verified 2026-11-18 (Anthropic has no public RSS; Microsoft is the general feed, not AI-only).
- The commit history was rewritten once to remove the user's personal email from the author field (now the GitHub no-reply address `9313302+timothylok@users.noreply.github.com`, set in the local git config). Do not commit with the personal address again.

## Known issues

- Local small models can add mild commentary; the Workers AI Llama 70B output looked grounded in spot checks, not fully audited.
- Episodes are about 2 minutes, not 4.
- This ffmpeg build draws a newline in a drawtext text file as a box, so `render.py` draws one line per drawtext; never write text files with `write_text` on Windows (CRLF).
- GitHub's runner has no Arial, so cloud episodes use DejaVu (look slightly different).
- The info banner on the TV briefly covers the episode's source line.

## Gotchas

- App build tooling is borrowed from `D:\ai\setupbox\tools` (see CLAUDE.md working notes). Git Bash needs `MSYS_NO_PATHCONV=1`; the `!` prompt runs in Git Bash, so use forward slashes (`cd /d/ai/tv-app/worker`).
- In Python heredocs through bash, do not put backslashes in strings (`\A` etc.); use the Edit/Write tools for files with Windows paths.
- Run the pipeline from `pipeline/` with `..\.venv\Scripts\python run.py --channel ai-news [--dry-run | --local]`. Without `--local` it publishes to the real bucket.
- Do not run bucket operations while `reconcile.py --probe` is waiting: they pollute the deltas it compares.
- The Android 10 box and Google TV may be playing karaoke (`com.setupbox.launcher`); check `dumpsys activity` before launching the app.
