# CLAUDE.md - AI TV channel platform

A cloud pipeline (news feeds -> LLM script -> TTS -> templated MP4 -> bucket + static `schedule.json`) and a lean-back Android TV app that plays the channel in sync, with channel up/down. First channel: AI News TV. Plan: `C:\Users\timlo\.claude\plans\fluffy-conjuring-kernighan.md`. Read `HANDOFF.md` (state, outstanding tasks, gotchas) first once it exists.

Layout: `pipeline/` (Python 3.12), `channels/*.yaml` (one file per channel), `app/` (Android TV, Java), `.github/workflows/`.

## Project rules (from the user)

1. **Test before you commit and push.** Run the checks that apply and look at the result before `git commit`:
   - plain-Java tests for the app logic (`ScheduleModel`, `ChannelList`, `ServerClock`) when that code changed;
   - pipeline: `python -I -m py_compile pipeline/*.py` and `run.py --dry-run`; for render changes, make one episode with `run.py --local` and check it with `ffprobe` (h264/aac, faststart, duration);
   - app changes: build, install on the box, and check the behaviour on the TV (screenshot, `logcat`), not only that it compiles.
   Commit and push only when asked ("commit and push"); never push untested work. Say what was tested (and what could not be).
2. **Pipeline and content rules.**
   - Summaries are in our own words (no long quotes); every segment shows its source citation on screen.
   - No company logos, stock photos or other unlicensed images: text cards and generated abstract backgrounds only.
   - Secrets (LLM, TTS, R2 keys) live only in GitHub secrets and a git-ignored local `.env`; never commit or log them.
   - LLM and TTS providers are config switches, not hard-coded. The TTS fallback must work on a CPU-only CI runner (no GPU-only engines).
   - `schedule.json` is append-only with absolute start/end times; the app takes "now" from the server `Date` header, not the box clock.
   - Encode H.264 720p, AAC, `-movflags +faststart`, loudness about -16 LUFS so channel switches do not jump in volume.
3. **Android best practices (top 10) - follow them when changing the app in `app/`:**
   1. **Never block the main thread.** Disk, network and decoding run on background threads; post results back with `runOnUiThread`/`Handler`.
   2. **Release what you hold.** ExoPlayer, surfaces, handlers and threads are released/stopped in `onDestroy` (and `Handler` callbacks removed) so nothing leaks or restarts twice.
   3. **Survive lifecycle and process death.** Keep state in files/SharedPreferences (last channel), not only in memory; tolerate `onCreate` running again; keep the foreground service for background playback.
   4. **Fail soft.** Catch and log errors around network and parsing; a broken schedule entry, JSON field or video must never stop the channel (play the filler, retry, carry on).
   5. **Small, safe file I/O.** Write to a temp file then rename; tolerate missing/damaged files; keep paths and names UTF-8 safe.
   6. **Design for the TV.** D-pad/remote first (channel keys, number keys, focus, no touch-only actions), readable text at 10 feet, safe margins, no burn-in of static bright content, sleep mode for idle screens.
   7. **Mind the weak hardware** (32-bit ARM, 2 GB-class box): no large allocations or bitmaps without downsampling, avoid per-frame work, prefer hardware decoding, keep the UI refresh cheap.
   8. **Keep the network surface small.** The app runs no server and has no secrets: HTTPS only, read-only static JSON and video, validate everything parsed from JSON.
   9. **Honour Android limits.** Declare services/permissions in the manifest, request none that are not needed (no storage), keep the box (Android 10 / API 29) working, guard APIs newer than the minSdk, and target a current SDK (foreground-service types).
   10. **Test and log on a real device.** Use `Log` tags (no secrets/personal data in logs), check `logcat` for crashes/ANRs after every install, and keep unit-testable logic (schedule, channel list, clock) free of Android classes.

4. **Plan for Google Play.** The app will be published on Google Play once it is production grade. New work should not add anything that blocks that (raw /sdcard paths, bundled video, unlicensed images or logos, open endpoints, unofficial/unlicensed TTS as the production voice). Keep a privacy policy, TV banner and leanback launcher in mind.

5. **All code must work on both Android 10 (API 29) and Android 16 (API 36).** The two test devices are the Android 10 box (192.168.3.79:5555) and the Android 16 `Google_TV` box (wireless debugging, `adb mdns services` for the port). Before a change is called done, build it and check it on both (or say which one could not be checked and why). Rules of thumb: size text from the screen (height or dp-scale), never raw-px spans or the density alone; guard APIs by `Build.VERSION.SDK_INT`; never assume a system app exists; scripts and tests must take the device as a parameter (`--device`), not hard-code an address.

6. **Keep `usermanual.md` current.** Any change to what the user sees or does (remote keys, channel switching, overlays, settings, launcher) updates `usermanual.md` in the same piece of work, before the commit.

## Working notes

- `adb` is not on PATH: `D:\ai\setupbox\tools\sdk\platform-tools\adb.exe`. Git Bash needs `MSYS_NO_PATHCONV=1`.
- Build from PowerShell: `JAVA_HOME=C:\Program Files\Java\jdk-20`, `ANDROID_HOME=D:\ai\setupbox\tools\sdk`, `D:\ai\setupbox\tools\gradle-8.11.1\bin\gradle.bat assembleDebug` in `app/`.
- Reusable code lives in other projects: `D:\ai\voice-ecosystem\ops\ai_digest.py` (RSS digest), `D:\ai\Substack\Podcast\scripts\tts_assemble.py` (TTS + ffmpeg), `D:\ai\setupbox\karaoke\app` (ExoPlayer, KeepAliveService, burn-in drift). Copy and adapt; do not import across projects.
- `out/`, `.env` and `*.mp4` are git-ignored. Do not write Java/regex backslashes through bash heredocs; use the Edit/Write tools.
- Do not interrupt a channel that is playing on the TV (installing the app restarts it): wait or ask.
