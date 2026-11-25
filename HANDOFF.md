# HANDOFF - AI TV channel platform

Last updated 2026-11-20. Read `pipeline.md` for how everything works; this file is state, open items and gotchas.

## State

Working end to end in the cloud, with no dependence on the user's PC:

- Cloudflare Worker `ai-tv-scheduler` (cron) starts the GitHub workflow `ai-news` (8am and 2pm NZ; the 8pm run was dropped); the workflow ingests feeds, writes a script with Workers AI, voices it with edge-tts, renders an MP4 with ffmpeg and publishes it to the R2 bucket `ai-tv-channels` with an append-only `schedule.json`.
- The Android TV app (`app/`, package `com.superghosttim.aichannels`, `TvActivity`) opens on a home menu and plays episodes on demand (Play latest, Continue watching, All episodes with New/Watched/% labels, saved positions in `WatchState`, pause menu, Back = Leave episode?, end-of-episode menu). Channel up/down or Watch live now gives live TV (server-time seek-in). Always-on top line: episode, last update (NZ), free AI %. Captions use per-element transparency (20% / 35% / 5%). Not yet checked on the Android 10 box. See `usermanual.md`.
- Repo: https://github.com/timothylok/tv-app (public), branch `main`. Public bucket URL: `https://pub-31476484de9245329a3f35502c44e692.r2.dev` (r2.dev dev URL: MVP only, move to a custom domain, e.g. a subdomain of fittertrack.com, before a Play release).
- Verified on 2026-11-18: three GitHub runs passed (one started by the Worker cron: dispatch HTTP 204), an episode built on GitHub played on the Google TV, the Workers AI guard ledger reached the bucket with exact neuron figures.

## Latest (2026-11-20, commits up to 2b06c5d)

- **In the video:** each story card has a QR code (top right, level with the headline, label "Scan to read the story" under it) linking to that story's page (`segno`, fail-soft). Verified by decoding frames of the real cloud episode `20261120-0400`; scanned with a phone from the TV on 2026-11-21 and it worked (user report). Headline wraps at 26 chars and bullets at 36 so text stays clear of the QR.
- **App:** idle clock (black screen, NZ time, new random spot every minute, after 5 min with no key and no video; any key wakes). Week > day > AM/PM episode browser, top-line auto-hide setting, NZ-time episode names, home menu / on-demand / live. 30-day retention; episode titles are the first two headlines (older items keep the generic title, hidden in the app).
- **Episode length:** the model writes only ~55-60% of the words it is asked for (2pm 20 Nov: 426 of 675 words, 192 s; manual run: 650 words, 266 s by luck). `ASK_FACTOR = 1.7` in `script.py` (commit 4aafce7) asks for more; unproven. The `script` audit event in `logs/ai/<date>.jsonl` shows `asked_words`, `target_words`, `words_per_try`, `chosen_words`.
- **End menu:** "Play next episode" means the one made after the episode just watched; when the newest just ended, the menu offers "Play earlier episode (time)" instead (2b06c5d; checked on the Google TV; the case with a newer episode is not checked on a device).
- **Health pass (2026-11-21):** last six GitHub runs all succeeded (ai-news and housekeeping). 8am NZ 21 Nov episode `20261120-1905` published on time, 281.8 s (about 4:42; previous two 192 s and 266 s), so ASK_FACTOR 1.7 looks good (3 episodes only). Not checked: `script` audit event (bucket log), QR position in that episode.
- **AI allowance (2026-11-21):** our ledger 1,729 neurons rolling 24 h (17%), but Cloudflare account-wide rolling 24 h was 4,859 (48.6%; stop line 7,000, shared with voice-ecosystem), so the guard uses the higher figure. Plain `reconcile.py`: Cloudflare 1,744 vs ledger 1,729 today (UTC). R2 storage 91 MB / 26 objects.
- **Reconcile probe (2026-11-21):** AI half matched exactly (2.46 neurons, 1 call, both sides). R2 half inconclusive: Cloudflare R2 analytics had not caught up in 8 min (deltas +0); run again later. Use `..\.venv\Scripts\python`, the system Python lacks requests/yaml.
- GitHub Actions runs take 10-22 min end to end (apt-get ffmpeg install is the slow part). Runs can be read via the unauthenticated GitHub API (public repo); starting one needs the user (browser is signed out, `gh` not authenticated).

## Session 2026-11-23

- Worker redeployed: both crons live (`0 19,1 * * *` and `30 11 * * *`). `housekeeping.py` dry run and real run done locally: nothing to delete (R2 145 MB, 1.4%). The **housekeeping workflow has not been run on GitHub** (gh logged out; dispatching with the .env token was blocked): the user runs it from the Actions tab, then checks `logs/` in the bucket.
- **Piper fallback built** (`tts.py`, `piper-tts` in requirements): tested locally with edge forced to fail (valid mp3). Not yet seen on a GitHub runner; a full episode through the fallback was not rendered.
- Reconcile probe re-run: inconclusive again (analytics 0 after 8 min, AI half too). Try again later.
- `script` events 21-22 Nov: asked 1147 words, got 700-1350. Durations 337, 351, 374, 297 s: **374 s exceeded the 6 min ceiling** (ceiling not enforced in seconds); watch it.
- **Google TV checked (2026-11-23):** Google_TV at 192.168.3.110:2025 (API 34; address changes, use `adb mdns services`). App (unchanged since 20 Nov) installed and launched after karaoke finished. Home menu OK (Play latest episode, All episodes, top-line setting); OK on "Play latest" played the cloud episode `20261122-1902` ("AI Music Removed · Vibe Coding"), progress bar running, audio focus held, no FATAL/ANR in logcat. The `Bluetooth audio disconnected` media-session error belongs to `com.android.bluetooth`, not the app; `buffer descriptor with invalid usage bits` warnings look like decoder noise (unconfirmed). The top line showed 56% free AI, matching Cloudflare's account-wide 43.7% used.
- **QR in a cloud episode verified on screen:** top right, "Scan to read the story" under it, "Source: The Verge" line at the bottom, text clear of the QR (screenshot from the TV; the QR was not scanned this time, the 21 Nov phone scan worked).
- Still not checked on a device: end menu with a newer episode, network loss, multi-hour soak, Android 10 box.

## Health pass 2026-11-25 (public reads only)

- **Housekeeping workflow confirmed in the cloud:** the second Worker cron started it on 23 and 24 Nov (11:30 UTC), both succeeded; daily-summary events reach `logs/ai/` (so item 3 is done, and the cloud use of `CLOUDFLARE_ANALYTICS_TOKEN` works: Cloudflare 24 h figure present, 34% on 23 Nov).
- **One failed ai-news run:** 2026-11-23 01:00 UTC (2pm NZ), the `Run python pipeline/run.py` step failed after about 7 min; no events for it in the bucket logs, so it died before the first logged AI call (cause unknown: the run log needs a signed-in GitHub). The next runs (23 Nov 19:00, 24 Nov 01:00 and 19:00, 25 Nov 01:00 UTC) all succeeded; that slot has no episode. Ask the user to read the log in the Actions tab if it recurs.
- Latest episodes (s): 287, 316, 256, 300 (all under 6 min); newest `20261125-0104`.
- **Health pass added to `housekeeping.py` (uncommitted until the user says so):** `check_health` + `recent_runs`, see `pipeline.md` section 6. It runs with the daily housekeeping (11:30 UTC, 00:30 NZ). Problems exit 1 so GitHub emails the owner; recovered failures and episodes over 360 s only warn. Tested: scratch unit checks (8 cases) and a `--dry-run` against live data (0 problems, 0 warnings). Not yet seen in a real workflow run; confirm the first cron run shows `health: 0 problem(s)` and that GitHub's failed-workflow emails are on.

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

1. (Done) `CLOUDFLARE_ANALYTICS_TOKEN` secret added. 2. (Done) Worker deployed.
3. (Done: cron-started housekeeping workflow ran and succeeded 23 and 24 Nov.)
4. Re-run the reconcile probe (`reconcile.py --probe`; AI agreed 2026-11-21, R2 analytics lagged, inconclusive); until R2 agrees, the "ledger matches Cloudflare" claim is unproven. The first plain `reconcile.py` report showed the ledger below Cloudflare (890 vs 280 neurons today) because calls before the guard, and dev-machine runs, were never in the bucket ledger.
5. Confirm the Piper fallback on a GitHub runner (e.g. a temporary `provider: piper` run, then revert).
6. Episode length: enforce the 6 min ceiling in seconds (374 s seen); keep watching.
7. (Done 2026-11-23: launch, playback and QR checked on the Google TV.) Remaining device checks: end menu with a newer episode, network loss, soak; the Android 10 box stays unchecked (user chose the Google TV).

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
