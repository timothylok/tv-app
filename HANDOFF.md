# HANDOFF - AI TV channel platform

## State
Build step 2 is working: `run.py --channel ai-news --local` makes a real ~2 min 720p episode MP4 in `out/ai-news/episodes/` (plus a `.json` with the script) from live feeds, using local Ollama (`gemma3:4b`, chosen after a 4-model benchmark) and edge-tts. Verified: H.264 High / AAC, faststart (ftyp, moov, mdat), about -16.3 LUFS, text readable, no tofu glyphs. Build step 3 also works: `run.py --channel ai-news` (no `--local`) uploads the MP4 to R2 and appends to `channels/ai-news/schedule.json` (epoch-ms start/end, 48 h prune), writes `channels.json` and a filler clip; verified over the public URL (200 with `Date`, 206 on Range). The bucket holds one test item (20261118-1802). Build step 5 works: the Android app in `app/` (package com.superghosttim.aichannels, `TvActivity`) plays the schedule live: server-time seek-in, filler in gaps, CH+/CH-, D-pad up/down, number keys, OK banner, Back channel list. 7 JVM tests pass (`gradle testDebugUnitTest`); checked on the Google_TV (API 34): filler to live switch, relaunch seeks to the live position, no crashes in logcat. Test device: the Google_TV at 192.168.3.97 (wireless debugging port changes; find it with `adb mdns services`); the user said to use it instead of the Android 10 box for now. Channel switching verified with a temporary 2nd channel (CH+, number key, D-pad down wrap, Back list + OK; the test channel was then removed from the bucket). NOT yet checked: multi-hour soak, network loss. Cloud LLM works: `provider: workers-ai` (Cloudflare free tier, `@cf/meta/llama-3.3-70b-instruct-fp8-fast`, needs CLOUDFLARE_API_TOKEN; first try gave 5/5 segments). A full run with it published episode 20261118-2119. The seen-story list now lives in the bucket (`state/seen.json`) so CI runs do not repeat stories. Workflow file updated (secrets list, concurrency) but NOT yet run on GitHub: no repo/remote exists. Scheduler: Cloudflare Worker `ai-tv-scheduler` (worker/, cron 0 19,1,7 UTC, secret GITHUB_TOKEN = fine-grained token, Actions read/write, this repo only, expires 2026-12-18 - renew it) starts the workflow; deployed, dispatch not yet confirmed. Not built: Piper TTS fallback (edge-tts may be blocked from CI IPs), sleep/burn-in mode, number-key banner polish.

Run it (PowerShell or Git Bash, from `pipeline/`): `..\.venv\Scripts\python run.py --channel ai-news --dry-run` (script only) or `--local` (full episode). `--model` overrides the Ollama model; `--keep-work` keeps temp files.

Modules: `ingest.py` (RSS, dedupe via `state/seen.json`, `max_per_source` cap), `llm.py` (Ollama; cloud providers raise NotImplementedError), `script.py` (JSON script, 3 retries, citation comes from the feed not the LLM), `tts.py` (edge, piper fallback is untested), `render.py` (ffmpeg drawtext cards), `common.py`.

## Build order
1. ~~Rewrite CLAUDE.md, scaffold repo~~ (done)
2. ~~Pipeline locally end to end~~ (done; `publish.py` still to come in step 3).
3. ~~R2 bucket, publish step, schedule append, filler clip~~ (done; `publish.py`).
4. Workflow: manual dispatch, then the 3/day cron.
5. ~~Android app: play the schedule on one channel with a live seek (`app/`)~~ (done on Google_TV; Android 10 box still to check).
6. Multi-channel switching, info banner, sleep/burn-in.
7. Measure Actions minutes and cost, tune cadence.

## Open items / assumptions
- Storage: Cloudflare R2 bucket `ai-tv-channels` (Standard, Oceania) created 2026-11-18, free plan, public via the r2.dev development URL `https://pub-31476484de9245329a3f35502c44e692.r2.dev` (rate-limited, MVP only; move to a custom domain, e.g. a subdomain of fittertrack.com, before Play release). API token is in local `.env`; the same names must exist as GitHub secrets (R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET, R2_PUBLIC_BASE_URL). Swappable provider. Repo visibility (private/public) decides the Actions minute quota.
- Feed URLs in `channels/ai-news.yaml` were checked on 2026-11-18 (all return entries).
- Accounts and secrets still to create: LLM keys, R2 keys (GitHub secrets).

## Known issues
- Local models can still add mild commentary; the prompt now forbids analysis/speculation (gemma3:4b output looked grounded on 2026-11-18, not fully audited). Ollama cloud models (deepseek-v3.1, qwen3-vl 235b) were not tested: need `ollama signin`. Gemma ignores the segment count, so episodes are capped at 8 segments.
- Narration is short (about 45 words a segment, episodes ~2 min vs the 4 min target). Tune the prompt or model.
- Feeds: Anthropic has no RSS; Microsoft blog is the general feed, not AI-only.
- This ffmpeg build draws a newline in a drawtext textfile as a box, so render.py draws one line per drawtext. Do not write text files with `write_text` on Windows (CRLF).
- Piper fallback and the cloud LLM providers are unimplemented.

## Gotchas
- App build tooling is borrowed from `D:\ai\setupbox\tools` (see CLAUDE.md working notes).
- The Android 10 box and Google TV may be playing karaoke; do not interrupt it.
