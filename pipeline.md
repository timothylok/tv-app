# The pipeline, end to end

How an RSS headline becomes a scheduled episode on the TV, what runs where, what each part
costs, and how the free tiers are protected. Facts here were checked against real runs on
2026-11-18 unless marked otherwise.

```
Cloudflare Worker (cron) ──► GitHub Actions run ──► Cloudflare R2 (static files) ──► Android TV app
  8am / 2pm NZ                ingest → script →         schedule.json, episodes,        reads the schedule,
  + daily housekeeping        voice → render → publish   state, daily logs               plays "what is on now"
```

Nothing runs on a personal computer. There is no server code anywhere: the bucket holds static
files and the app reads them over HTTPS.

## 1. Trigger: the Cloudflare Worker

`worker/` holds `ai-tv-scheduler`, a Worker with two Cron Triggers (UTC, see `wrangler.toml`):

| Cron | NZ summer time | Starts |
|---|---|---|
| `0 19,1 * * *` | 8am, 2pm | `ai-news.yml` (make and publish an episode) |
| `30 11 * * *` | 00:30 | `housekeeping.yml` (tidy and measure) |

Each tick calls GitHub's `workflow_dispatch` API; GitHub answers `204` when the run is queued.
The Worker has no public URL. Its only secret is `GITHUB_TOKEN`, a fine-grained token limited to
this repo with Actions read/write. **The token expires on 2026-12-18: renew it** (GitHub, Settings,
Developer settings, Fine-grained tokens), then update the Worker secret (Workers & Pages,
`ai-tv-scheduler`, Settings, Variables and Secrets). A public repo has unlimited free Actions
minutes, and a Worker cron is more reliable than GitHub's own `schedule:`, which runs late and is
switched off after 60 days without repo activity.

Both workflows share one concurrency group, so a housekeeping run never overlaps an episode run
(two writers of `schedule.json` would lose an update).

## 2. The episode run (`pipeline/run.py`, about 2 to 3 minutes of work)

A fresh Ubuntu machine each time, so memory between runs lives in the bucket.

1. **Pull state** (`publish.pull_state`): `state/seen.json` (stories already covered) and
   `state/ai-usage.json` (Workers AI spend ledger).
2. **R2 guard** (`r2usage.check`): if bucket storage is over 80% of 10 GB or this month's Class A
   operations are over 80% of 1,000,000, skip the run with a warning (section 5).
3. **Ingest** (`ingest.py`): reads the feeds in `channels/ai-news.yaml`; drops items older than 7
   days, already seen, or near-duplicate in title; at most 3 per source; newest first.
4. **AI guard** (`usage.check`): if Workers AI use is near 95% of the free 10,000 neurons, skip the
   run with a warning (section 4).
5. **Script** (`script.py`, `llm.py`): the stories go to Cloudflare Workers AI
   (`@cf/meta/llama-3.3-70b-instruct-fp8-fast`, free tier). It returns JSON: an episode title and
   about 5 segments, each with headline, narration and 3 short bullets. The prompt forbids opinion,
   speculation and invented facts; the source credit comes from the feed, never from the model.
   Up to 3 attempts, keeping the best. A local Ollama model (`provider: ollama`) is the fallback
   for development.
6. **Voice** (`tts.py`): edge-tts speaks each segment's narration to an MP3. A Piper fallback is
   planned but not built: if edge-tts is blocked on a runner, the run fails.
7. **Render** (`render.py`): ffmpeg draws text cards on a plain animated background (no logos, no
   stock images): intro, one card per segment (headline, bullets that appear one by one, a
   "Source: ..." line), outro. Audio is normalised to about -16 LUFS. Output: H.264 High 720p,
   AAC, `+faststart` (index at the front so streaming starts at once). About 4 minutes, 8 to 10 MB.
   One `drawtext` per wrapped line, because this ffmpeg build draws a newline as a box.
8. **Publish** (`publish.py`): uploads the MP4 first, then appends the episode to
   `channels/ai-news/schedule.json` with absolute start and end times (after the last scheduled
   item, or at least 90 seconds from now). Updates `channels.json`, makes the looping filler clip
   if missing, and drops items that ended over 48 hours ago together with their MP4s.
9. **Push state** (`publish.push_state`): writes the seen list and AI ledger back, folds this run's
   R2 operation counts into the month counters, and merges this run's events into the daily audit
   logs. This also runs if publishing failed, so spend is never lost.

## 3. What is in the bucket

Public read through the `r2.dev` address, no server code.

| Key | Content |
|---|---|
| `channels.json` | the channel line-up |
| `channels/<id>/schedule.json` | items `{id, title, url, start, end, duration}` in epoch milliseconds, plus the filler URL |
| `channels/<id>/episodes/<id>.mp4` | episodes (about 48 hours of them) |
| `channels/<id>/filler.mp4` | the "next episode coming up" loop |
| `state/seen.json`, `state/ai-usage.json`, `state/r2-ops.json` | memory between runs |
| `logs/ai/<date>.jsonl`, `logs/r2/<date>.jsonl` | daily audit logs (section 6) |

The bucket is public, so state and logs are readable by anyone. They contain counts, sizes and
object names only: no keys, tokens or story text. If that ever changes, move them to a private
bucket.

## 4. Workers AI spend guard (`usage.py`)

Free allowance: 10,000 neurons per 24 hours. Cloudflare budget alerts do not cap spending, so the
only real stop is our own code.

- Every call goes through `usage.check` (before) and `usage.record` (after, as soon as Cloudflare
  answers, before parsing the reply).
- The cost is Cloudflare's own `usage.neurons` from the response. If that is missing, it is priced
  from tokens at the published rate and marked `exact: false`; if it cannot be priced, 1,000
  neurons are charged and an error is logged. **Unknown is never zero.**
- The window is the trailing 24 hours (the allowance is not a UTC calendar day).
- The stop line is `stop_at` (95%, so 9,500) of `daily_neuron_budget` (10,000), set in the channel yaml,
  with 500 neurons reserved for the call about to be made. The allowance is shared with the active
  project voice-ecosystem (about 3,000 neurons a day, peak 5,100, measured 2026-11-10 to 17). It was
  70% for a day, but account-wide use reached 7,300 on 2026-11-18 (mostly other projects), which would
  have skipped episodes, so it was raised to 90%, then 95% on 2026-11-19 so a day of testing did not skip an episode. The cost: at the very top, tv-app plus voice-ecosystem can
  hit 10,000 and voice-ecosystem is the one that then fails. The figure the guard compares is
  account-wide (Cloudflare analytics), so it counts voice-ecosystem's spend; it cannot slow
  voice-ecosystem down.
- `used_effective` takes the larger of our ledger and Cloudflare's own analytics figure when
  `CLOUDFLARE_ANALYTICS_TOKEN` is set. This covers spend our ledger never saw (calls from a
  laptop, a run that crashed before saving).
- Measured: one script is about 110 to 160 neurons, so 3 runs a day is under 500 of 10,000.
- On a stop the run exits 0 with a GitHub warning and publishes nothing.

## 5. R2 free-tier guard (`r2usage.py`)

Free tier (Cloudflare's published pricing; confirm in the dashboard): 10 GB-month storage,
1,000,000 Class A operations a month (PUT, LIST, COPY), 10,000,000 Class B (GET, HEAD), no egress
fees, DELETE free.

- **Storage:** measured exactly by listing the bucket. About 5 MB per episode and about 48 hours
  kept means tens of MB, far below 10 GB. The run stops at 80%.
- **Our operations:** every bucket call goes through `publish.Publisher`, which counts it by class
  and writes an audit event. Month totals are kept in `state/r2-ops.json`. A normal run is about
  20 operations, so a month is a few thousand against 1,000,000.
- **TV reads:** the app polls the schedule every 60 seconds, about 1,440 Class B a day, 43,000 a
  month per always-on TV, so 10 million Class B covers roughly 200 TVs. These reads are not made
  by our pipeline, but Cloudflare's analytics include them (see section 7).

## 6. Daily housekeeping (`housekeeping.py`, `housekeeping.yml`)

Runs daily at 00:30 NZ. `--dry-run` shows what it would delete and changes nothing remote.

1. Drops schedule items that ended over 48 hours ago and deletes their MP4s.
2. Deletes orphan episode files: not referenced by any schedule item and older than 2 hours (so an
   upload in progress is safe). Unknown channels and the filler are left alone.
3. Measures storage and the month's operations; writes `daily-summary` lines to both logs.
4. Prints a GitHub warning when anything is over 70% of its free tier.

Audit logs are never deleted (a few KB a day).

## 7. Audit logs

`logs/ai/YYYY-MM-DD.jsonl` and `logs/r2/YYYY-MM-DD.jsonl`, one JSON object per line with `ts`
(UTC), `run` (GitHub run id or `local`) and event fields. Written locally under `logs/`
(git-ignored) and merged into the same names in the bucket at the end of each publishing run.

| Stream | Events |
|---|---|
| `ai` | `call` (model, neurons, exact, used_24h), `blocked`, `ledger-low`, `daily-summary` |
| `r2` | `op` (op, class, key, bytes), `blocked`, `housekeeping-delete`, `month-counters`, `daily-summary` |

## 8. Checking against Cloudflare (`reconcile.py`)

`python pipeline/reconcile.py` compares our ledgers with Cloudflare's analytics (Workers AI
neurons and calls, R2 operations by class and action, R2 storage). `--probe` does a known amount
of work (1 tiny AI call, 3 PUT, 2 GET, 1 HEAD, 3 DELETE on throw-away keys) and compares the
deltas with Cloudflare's, which needs a few minutes for analytics to catch up. Needs
`CLOUDFLARE_ANALYTICS_TOKEN` (Account Analytics: Read, this account only; created with a 90-day
expiry, so renew it).

## 9. The TV app (`app/`)

On launch it fetches `channels.json`, then the channel's schedule, and opens a **home menu**:
Play latest, Continue watching (only when something is part-watched), All episodes (everything
the schedule still holds, about 48 hours, newest first, labelled New / N% watched / Watched) and
Watch live now (only while an episode is on air). Episodes play **on demand**: resume positions
and watched state live in `WatchState` (plain Java, unit-tested), saved as JSON in
SharedPreferences every 10 s, on pause, on Back and on stop. OK shows the controls, Play/Pause
opens the pause menu, Back asks "Leave episode?", and the end of an episode shows a menu (play
next, play latest, replay, choose, return); nothing starts by itself and a new episode never
interrupts. Channel up/down still gives **live TV**: "now" is the server's HTTP `Date` header, it
plays the item with `start <= now < end` at the right offset, re-syncs every 2 seconds and queues
a following item for a seamless switch. The schedule is re-fetched every 60 seconds. A thin
top-left line is always on: episode id, the schedule's `updated` time in NZ time and
`ai.left_pct` (free Workers AI left, account-wide, written at each publish); it and the menus
drift a few pixels a minute against burn-in, and the screen-on flag is held only while video
plays. See `usermanual.md` for the keys.

## 10. Secrets and where they live

| Name | Used by | Stored in |
|---|---|---|
| `CLOUDFLARE_API_TOKEN` | Workers AI calls | `.env`, GitHub Actions secret |
| `CLOUDFLARE_ANALYTICS_TOKEN` | analytics (reconcile, guard) | `.env`, GitHub Actions secret |
| `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_PUBLIC_BASE_URL` | bucket access | `.env`, GitHub Actions secrets |
| `GITHUB_TOKEN` (Worker) | Worker starts workflows | Worker secret only |

Never commit `.env`; never put a token in chat or in a log.

## 11. Known gaps

- No Piper fallback voice yet.
- Episode length varies (about 3.5 to 6 min, 4 min on average): the model does not hit the word target exactly; the script picks the closest of 3 tries and trims to a hard 6 min ceiling.
- The Android 10 (API 29) box has not been tested.
- Dev-machine runs record to a local ledger only; the guard still sees that spend through
  Cloudflare's analytics, and `--probe`/`--reconcile` show any difference.
- Both tokens above expire (Worker GitHub token 2026-12-18; analytics token 90 days from creation).
