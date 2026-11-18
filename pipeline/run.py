"""Entry point: python pipeline/run.py --channel ai-news [--dry-run] [--local]

Steps: ingest -> script -> tts -> render -> publish (R2 + schedule.json)
  --dry-run  ingest + script only; prints the script JSON; no TTS, render or state change
  --local    write the episode to out/<channel>/episodes/ only, no upload
  (neither)  also upload to R2 and append to the schedule
"""
import argparse
import json
import shutil
import sys
import time

from common import OUT_DIR, load_channel, log, save_json
from ingest import fetch_stories, mark_seen
from script import build_script
from r2usage import R2BudgetExceeded
from usage import BudgetExceeded


def main(argv=None):
    p = argparse.ArgumentParser(description="Generate episodes for a TV channel")
    p.add_argument("--channel", required=True, help="channel id, e.g. ai-news")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--local", action="store_true",
                   help="write to out/ only; without it the episode is uploaded to R2")
    p.add_argument("--keep-work", action="store_true", help="keep temp files in out/.../work")
    p.add_argument("--model", help="override the Ollama model from the channel YAML")
    args = p.parse_args(argv)

    channel = load_channel(args.channel)
    if args.model:
        channel["llm"]["model"] = args.model

    publishing = not args.local and not args.dry_run
    if publishing:
        from publish import Publisher, pull_state
        import r2usage
        pull_state()
        try:
            r2usage.check(Publisher())  # storage and Class A operations vs the R2 free tier
        except R2BudgetExceeded as e:
            print(f"::warning::{e}")
            log("run", str(e))
            from publish import push_state
            push_state()
            return 0
    stories = fetch_stories(channel)
    try:
        script = build_script(channel, stories)
    except BudgetExceeded as e:
        # not a failure: stop quietly, keep the ledger, publish nothing this time
        print(f"::warning::{e}")  # shows as a warning on the GitHub run
        log("run", str(e))
        if publishing:
            from publish import push_state
            push_state()
        return 0
    if publishing:  # save the spend now: a later TTS or render failure must not lose it
        from publish import push_state
        push_state()
    if args.dry_run:
        print(json.dumps(script, indent=2, ensure_ascii=False))
        return 0

    from render import render_episode  # imported late so --dry-run needs no fonts/ffmpeg
    from tts import synth

    ep_id = time.strftime("%Y%m%d-%H%M")
    base = OUT_DIR / channel["id"]
    work = base / "work" / ep_id
    work.mkdir(parents=True, exist_ok=True)
    audio = []
    for i, seg in enumerate(script["segments"]):
        name = f"narr{i}.mp3"
        synth(channel["tts"], seg["narration"], work / name)
        audio.append(name)
        log("tts", f"segment {i + 1}/{len(script['segments'])} done")

    mp4 = base / "episodes" / f"{ep_id}.mp4"
    dur = render_episode(channel, script, audio, work, mp4)
    save_json(base / "episodes" / f"{ep_id}.json",
              {"id": ep_id, "title": script["title"], "duration": round(dur, 2),
               "script": script})
    try:
        if not args.local:
            from publish import publish_episode
            publish_episode(channel, ep_id, script["title"], mp4, work)
        mark_seen([s["story_id"] for s in script["segments"]])
    finally:  # even if the upload failed, keep the audit trail and the counters
        if publishing:
            from publish import push_state
            push_state()
    if not args.keep_work:
        shutil.rmtree(work, ignore_errors=True)
    log("run", f"done: {mp4}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
