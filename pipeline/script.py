"""Step 2: turn fresh stories into a structured episode script (own words, cited)."""
import time

import auditlog

from common import clean_text, log
from llm import generate_json
from usage import BudgetExceeded

WORDS_PER_MIN = 150
MAX_SEGMENTS = 10
MAX_MINUTES = 6   # hard ceiling on spoken length (about 900 words)

SYSTEM = (
    "You are the scriptwriter for a TV news channel about AI. Write in plain, friendly "
    "spoken English for a general audience. Use ONLY facts in the supplied stories; never "
    "invent numbers, quotes or names. Paraphrase in your own words; never copy sentences. "
    "Report only what the story says. Do NOT add analysis, opinion, speculation, motives, "
    "or reasons that the story does not state (no 'this suggests', 'this highlights', "
    "'raises questions'). Use every detail the story gives, and aim for the requested length. "
    "Reply with JSON only."
)


def _prompt(channel, stories, n_segments, words_each):
    lines = []
    for i, s in enumerate(stories):
        lines.append(f"[{i}] {s['title']} ({s['source']})\n    {s['summary']}")
    return (
        f"Channel: {channel['name']}.\n"
        f"Pick the {n_segments} most newsworthy stories below and write one segment for each.\n"
        "Each narration must reach its word count: use every fact the story gives, in several full "
        "sentences (what happened, who is involved, the details and numbers stated). Never pad with "
        "opinion or guesses.\n"
        "Return JSON of this shape:\n"
        '{"title": "short episode title", "segments": [{"story": <story number>, '
        '"headline": "max 8 words", "narration": "about '
        f'{words_each} words (never fewer than {int(words_each * 0.8)}), spoken style, no markdown", '
        '"bullets": ["max 3 short complete on-screen points, 4 to 8 words each, no trailing dots"]}]}\n\n'
        "Stories:\n" + "\n".join(lines)
    )


def _words(result):
    return sum(len(sg["narration"].split()) for sg in result[1]["segments"])


def _cap_words(text, n):
    words = text.split()
    if len(words) <= n:
        return text
    cut = " ".join(words[:n])
    end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
    return cut[:end + 1] if end > len(cut) // 2 else cut.rstrip(",;:") + "."


def _trim(text, n):
    text = clean_text(text)
    return text if len(text) <= n else text[:n].rsplit(" ", 1)[0]


def build_script(channel, stories):
    if not stories:
        raise RuntimeError("no fresh stories to write about")
    minutes = channel.get("episode_minutes", 4)
    n_segments = max(1, min(len(stories), round(minutes * 60 / 35)))  # ~35 s per segment: more, shorter segments reach the target length more reliably
    words_each = int(minutes * WORDS_PER_MIN / n_segments)
    log("script", f"asking {channel['llm'].get('model', 'ollama')} for {n_segments} segments")
    best = None
    tries = []
    for attempt in range(1, 4):  # small local models are flaky: retry, keep the best try
        try:
            raw = generate_json(channel["llm"], SYSTEM,
                                _prompt(channel, stories, n_segments, words_each))
            result = _clean_segments(raw, stories)
        except BudgetExceeded:
            raise  # retrying cannot help
        except Exception as e:  # bad JSON, timeout, connection: try again
            log("script", f"attempt {attempt} failed: {e}")
            continue
        log("script", f"attempt {attempt}: {len(result[1]['segments'])}/{n_segments} usable segments, {_words(result)} words")
        tries.append(_words(result))
        target = minutes * WORDS_PER_MIN
        if best is None or abs(_words(result) - target) < abs(_words(best) - target):
            best = result   # the try closest to the target length (models both under- and overshoot)
        if len(best[1]["segments"]) >= max(1, n_segments - 1) and 0.75 * target <= _words(best) <= 1.25 * target:
            break
    log("script", f"best try: {_words(best) if best else 0} words (~{(_words(best) if best else 0) / WORDS_PER_MIN:.1f} min) for a {minutes} min target")
    if best is None or not best[1]["segments"]:
        raise RuntimeError("LLM returned no usable segments")
    auditlog.event("ai", event="script", stories=len(stories), asked_segments=n_segments, asked_words=int(minutes * WORDS_PER_MIN),
                   words_per_try=tries, chosen_words=_words(best))   # so a short episode can be explained afterwards
    best[1]["segments"] = best[1]["segments"][:MAX_SEGMENTS]  # some models ignore the count
    total = _words(best)
    if total > MAX_MINUTES * WORDS_PER_MIN:   # hard ceiling: shrink every segment in proportion, cutting at a sentence end
        ratio = MAX_MINUTES * WORDS_PER_MIN / total
        for sg in best[1]["segments"]:
            sg["narration"] = _cap_words(sg["narration"], max(20, int(len(sg["narration"].split()) * ratio)))
        log("script", f"over the {MAX_MINUTES} min ceiling ({total} words): trimmed to {_words(best)}")
    return best[1]


def _clean_segments(raw, stories):
    segments, used = [], set()
    for seg in raw.get("segments", []):
        try:
            idx = int(seg["story"])
            story = stories[idx]
        except (KeyError, ValueError, TypeError, IndexError):
            continue
        if idx in used or len(str(seg.get("narration", "")).split()) < 15:
            continue
        used.add(idx)
        bullets = [_trim(b, 80) for b in (seg.get("bullets") or [])[:3] if str(b).strip()]
        segments.append({
            "headline": _trim(seg.get("headline") or story["title"], 64),
            "narration": _trim(seg["narration"], 1400),
            "bullets": bullets,
            # citation comes from the feed, never from the LLM
            "source": story["source"],
            "link": story["link"],
            "story_id": story["id"],
        })
    return raw, {
        "title": _trim(raw.get("title") or "AI news update", 80),
        "date": time.strftime("%d %B %Y"),
        "segments": segments,
    }
