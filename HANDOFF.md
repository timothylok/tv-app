# HANDOFF - AI TV channel platform

Last updated 2026-11-20. Read `pipeline.md` for how everything works; this file is state, open items and gotchas.

## State

Working end to end in the cloud, with no dependence on the user's PC:

- Cloudflare Worker `ai-tv-scheduler` (cron) starts the GitHub workflow `ai-news` (8am and 2pm NZ; the 8pm run was dropped); the workflow ingests feeds, writes a script with Workers AI, voices it with edge-tts, renders an MP4 with ffmpeg and publishes it to the R2 bucket `ai-tv-channels` with an append-only `schedule.json`.
- The Android TV app (`app/`, package `com.superghosttim.aichannels`, `TvActivity`) opens on a home menu and plays episodes on demand (Play latest, Continue watching, All episodes with New/Watched/% labels, saved positions in `WatchState`, pause menu, Back = Leave episode?, end-of-episode menu). Channel up/down or Watch live now gives live TV (server-time seek-in). Always-on top line: episode, last update (NZ), free AI %. Captions use per-element transparency (20% / 35% / 5%). Not yet checked on the Android 10 box. See `usermanual.md`.
- Repo: https://github.com/timothylok/tv-app (public), branch `main`. Public bucket URL: `https://pub-31476484de9245329a3f35502c44e692.r2.dev` (r2.dev dev URL: MVP only, move to a custom domain, e.g. a subdomain of fittertrack.com, before a Play release).
- Verified on 2026-11-18: three GitHub runs passed (one started by the Worker cron: dispatch HTTP 204), an episode built on GitHub played on the Google TV, the Workers AI guard ledger reached the bucket with exact neuron figures.

## Latest (2026-11-20, commits up to cb237a3)

- **In the video:** each story card has a QR code (top right, level with the headline, label "Scan to read the story" under it) linking to that story's page (`segno`, fail-soft). Verified by decoding frames of the real cloud episode `20261120-0400`; never scanned with a phone from a TV yet. Headline wraps at 26 chars and bullets at 36 so text stays clear of the QR.
- **App:** idle clock (black screen, NZ time, new random spot every minute, after 5 min with no key and no video; any key wakes). Week > day > AM/PM episode browser, top-line auto-hide setting, NZ-time episode names, home menu / on-demand / live. 30-day retention; episode titles are the first two headlines (older items keep the generic title, hidden in the app).
- **Episode length:** the model writes only ~55-60% of the words it is asked for (2pm 20 Nov: 426 of 675 words, 192 s; manual run: 650 words, 266 s by luck). `ASK_FACTOR = 1.7` in `script.py` (commit 4aafce7) asks for more; unproven. The `script` audit event in `logs/ai/<date>.jsonl` shows `asked_words`, `target_words`, `words_per_try`, `chosen_words`.
- **Pending check:** a one-shot reminder was set for 8:37am NZ 21 Nov (session-only) to verify the 8am episode: run success, length vs 4 min, `script` event, QR position. If the session closed, do this by hand.
- **AI allowance:** account-wide use was ~96% on 19-20 Nov (stop line 0.95, shared with voice-ecosystem); the 2pm 20 Nov run was not blocked. Watch for skipped runs.
- GitHub Actions runs take 10-22 min end to end (apt-get ffmpeg install is the slow part). Runs can be read via the unauthenticated GitHub API (public repo); starting one needs the user (browser is signed out, `gh` not authenticated).

## Added earlier (verify before relying on it)

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
6. Episode length: check the 21 Nov 8am run against the 4 min target (ASK_FACTOR 1.7 just added).
7. Test on the Android 10 box (192.168.3.79:5555) when it is connected.

## Open items and assumptions

- **Test devices:** the Google_TV at 192.168.3.101 (was .97; wireless debugging port changes: `adb mdns services`) is the one used and verified (API 34); the user chose it over the Android 10 box for now. Not checked: network loss, multi-hour soak, back-to-back gapless handover.
- **Expiring tokens:** the Worker's GitHub token (`GITHUB_TOKEN`, fine-grained, Actions read/write, this repo only) expires 2026-12-18; the analytics token was created with a 90-day expiry (about 2027-02-16). Renew both: the Worker stops starting runs silently otherwise.
- **Secrets** live only in `.env` (git-ignored; also `.env.bak`, `.env.*` ignored), GitHub Actions secrets and the Worker secret. Names: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ANALYTICS_TOKEN`, `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_PUBLIC_BASE_URL`. Never paste them in chat.
- The bucket is public, so `state/` and `logs/` are readable by anyone (counts and names only).
- The bucket still holds old test items (`Test episode`, `Live test episode`); they age out after 30 days.
- Feeds in `channels/ai-news.yaml` verified 2026-11-18 (Anthropic has no public RSS; Microsoft is the general feed, not AI-only).
- The commit history was rewritten once to remove the user's personal email from the author field (now the GitHub no-reply address `9313302+timothylok@users.noreply.github.com`, set in the local git config). Do not commit with the personal address again.

## Known issues

- Local small models can add mild commentary; the Workers AI Llama 70B output looked grounded in spot checks, not fully audited.
- Episode length varies about 3 to 6 min (6 min ceiling); the model undershoots the requested words.
- This ffmpeg build draws a newline in a drawtext text file as a box, so `render.py` draws one line per drawtext; never write text files with `write_text` on Windows (CRLF).
- GitHub's runner has no Arial, so cloud episodes use DejaVu (look slightly different).
- The info banner on the TV briefly covers the episode's source line.

## Gotchas

- App build tooling is borrowed from `D:\ai\setupbox\tools` (see CLAUDE.md working notes). Git Bash needs `MSYS_NO_PATHCONV=1`; the `!` prompt runs in Git Bash, so use forward slashes (`cd /d/ai/tv-app/worker`).
- In Python heredocs through bash, do not put backslashes in strings (`\A` etc.); use the Edit/Write tools for files with Windows paths.
- Run the pipeline from `pipeline/` with `..\.venv\Scripts\python run.py --channel ai-news [--dry-run | --local]`. Without `--local` it publishes to the real bucket.
- Do not run bucket operations while `reconcile.py --probe` is waiting: they pollute the deltas it compares.
- The Android 10 box and Google TV may be playing karaoke (`com.setupbox.launcher`); check `dumpsys activity` before launching the app.
