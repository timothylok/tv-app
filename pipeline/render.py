"""Step 4: ffmpeg templated motion graphics. Text cards + animated background only
(no logos, no stock images). Output: H.264 720p, AAC, faststart, about -16 LUFS."""
import shutil
import textwrap
from pathlib import Path

from common import clean_text, duration, ffmpeg, log

FPS = 25
QR_PX = 240   # about this many pixels square on the 1280x720 card (big enough to scan from a sofa)
PAD = 0.8  # seconds of breathing room after each narrated segment
FONTS = [
    ("C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/arial.ttf"),
    ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
     "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
]
V_ENC = ["-c:v", "libx264", "-profile:v", "high", "-level", "4.1", "-pix_fmt", "yuv420p",
         "-preset", "medium", "-b:v", "2500k", "-maxrate", "3000k", "-bufsize", "5000k",
         "-g", str(FPS * 2), "-keyint_min", str(FPS * 2), "-sc_threshold", "0", "-r", str(FPS)]


def _wrap(text, width):
    return textwrap.wrap(clean_text(text), width) or [""]


def _lh(size):
    """Line height for a font size."""
    return int(size * 1.3)


def _pick_fonts():
    for bold, reg in FONTS:
        if Path(bold).is_file() and Path(reg).is_file():
            return bold, reg
    raise RuntimeError("no usable TTF font found; add one to FONTS in render.py")


def _hex(c):
    return "0x" + c.lstrip("#")


def _qr(work, name, link):
    """PNG of a QR code for `link` (the story's own page), or None. Fail-soft: a missing library or a
    bad link only means no QR on that card; it never stops an episode."""
    if not link or not link.startswith(("https://", "http://")) or len(link) > 400:
        return None
    try:
        import segno
        code = segno.make(link, error="l", micro=False)
        modules = code.symbol_size(scale=1, border=0)[0]
        scale = max(2, QR_PX // (modules + 6))
        out = f"{name}_qr.png"
        code.save(str(work / out), kind="png", scale=scale, border=3, dark="black", light="white")
        return out
    except Exception as e:   # noqa: BLE001
        log("render", f"WARNING no QR for {name}: {e}")
        return None


def _clip(work, name, dur, style, items, source=None, link=None):
    """Render one silent clip. items: dicts with text,size,x,y,start,bold,color,width."""
    parts = [
        f"drawbox=x=0:y=0:w={style['width']}:h=8:color={_hex(style['accent'])}:t=fill",
        f"drawbox=x=0:y=0:w=16:h={style['height']}:color={_hex(style['accent'])}:t=fill",
        # slow sweeping highlight so the screen is never static (burn-in, boredom)
        f"drawbox=x='mod(t*60,{style['width'] + 400})-400':y=700:w=400:h=4:"
        f"color={_hex(style['accent'])}@0.6:t=fill",
    ]
    for i, it in enumerate(items):
        font = "bold.ttf" if it.get("bold") else "reg.ttf"
        s = it.get("start", 0)
        # one drawtext per wrapped line: this ffmpeg build draws a newline in a textfile as a box
        for k, line in enumerate(_wrap(it["text"], it.get("width", 40))):
            txt = work / f"{name}_{i}_{k}.txt"
            txt.write_bytes(line.encode("utf-8"))
            parts.append(
                f"drawtext=fontfile={font}:textfile={txt.name}:fontsize={it['size']}:"
                f"fontcolor={it.get('color', 'white')}:x={it.get('x', 100)}:"
                f"y={it['y'] + k * _lh(it['size'])}:alpha='min(1,max(0,(t-{s})/0.5))'"
            )
    if source:
        sf = work / f"{name}_src.txt"
        sf.write_bytes(f"Source: {clean_text(source)}".encode("utf-8"))
        parts.append("drawbox=x=0:y=620:w=iw:h=70:color=black@0.55:t=fill")
        parts.append(f"drawtext=fontfile=reg.ttf:textfile={sf.name}:fontsize=28:"
                     "fontcolor=white:x=100:y=640")
    out = f"{name}.mp4"
    base = ["-f", "lavfi", "-i",
            f"color=c={_hex(style['background'])}:s={style['width']}x{style['height']}:r={FPS}:d={dur:.3f}"]
    qr = _qr(work, name, link) if source else None
    if qr:   # scan to read the story: a white-bordered QR at the bottom right, with a short label above it
        lab = work / f"{name}_qrlabel.txt"
        lab.write_bytes(b"Scan to read the story")
        parts.append(f"drawtext=fontfile=reg.ttf:textfile={lab.name}:fontsize=22:fontcolor=white:"
                     f"x={style["width"] - 70 - QR_PX}:y={style["height"] - 24 - QR_PX - 32}")
        ffmpeg([*base, "-i", qr, "-filter_complex",
                f"[0:v]{','.join(parts)}[bg];[bg][1:v]overlay=x=W-w-70:y=H-h-24:format=auto[v]",
                "-map", "[v]", *V_ENC, "-an", out], cwd=work)
    else:
        ffmpeg([*base, "-vf", ",".join(parts), *V_ENC, "-an", out], cwd=work)
    return out


def _wav(work, name, dur, mp3=None):
    out = f"{name}.wav"
    if mp3:
        ffmpeg(["-i", mp3, "-ar", "44100", "-ac", "2", "-af", f"apad=whole_dur={dur:.3f}",
                "-t", f"{dur:.3f}", out], cwd=work)
    else:
        ffmpeg(["-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-t", f"{dur:.3f}", out],
               cwd=work)
    return out


def _concat(work, names, listfile):
    (work / listfile).write_text("".join(f"file '{n}'\n" for n in names), encoding="utf-8")


def render_episode(channel, script, audio_files, work, out_path):
    """audio_files[i] is the narration mp3 for script['segments'][i] (inside `work`)."""
    style = channel["style"]
    bold, reg = _pick_fonts()
    shutil.copy(bold, work / "bold.ttf")
    shutil.copy(reg, work / "reg.ttf")

    clips, wavs = [], []

    def add(name, dur, items, source=None, mp3=None, link=None):
        clips.append(_clip(work, name, dur, style, items, source, link))
        wavs.append(_wav(work, name, dur, mp3))

    add("intro", 4.0, [
        {"text": channel["name"], "size": 84, "y": 220, "bold": True, "width": 24},
        {"text": script["title"], "size": 44, "y": 380, "start": 0.8, "width": 44},
        {"text": script["date"], "size": 30, "y": 520, "start": 1.4,
         "color": style["accent"]},
    ])
    for i, (seg, mp3) in enumerate(zip(script["segments"], audio_files)):
        dur = duration(work / mp3) + PAD
        head_lines = len(_wrap(seg["headline"], 34))
        items = [{"text": seg["headline"], "size": 54, "y": 90, "bold": True, "width": 34}]
        y = 90 + head_lines * _lh(54) + 40  # stack bullets below however many lines the headline uses
        for j, b in enumerate(seg["bullets"]):
            n = len(textwrap.wrap("\u2022 " + clean_text(b), 42))
            items.append({"text": "\u2022 " + b, "size": 38, "y": y,
                          "start": 1.5 + j * 2.0, "width": 42})
            y += n * _lh(38) + 26
        add(f"seg{i}", dur, items, source=seg["source"], mp3=mp3, link=seg.get("link"))
        log("render", f"segment {i + 1}/{len(script['segments'])}: {dur:.1f}s")
    add("outro", 4.0, [
        {"text": "Thanks for watching", "size": 64, "y": 260, "bold": True, "width": 30},
        {"text": channel["name"] + " \u2022 more coming up", "size": 34, "y": 400,
         "start": 0.8, "color": style["accent"], "width": 50},
    ])

    _concat(work, clips, "video.txt")
    _concat(work, wavs, "audio.txt")
    ffmpeg(["-f", "concat", "-safe", "0", "-i", "video.txt", "-c", "copy", "video.mp4"], cwd=work)
    ffmpeg(["-f", "concat", "-safe", "0", "-i", "audio.txt", "-af",
            "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", "44100", "audio.wav"], cwd=work)
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg(["-i", "video.mp4", "-i", "audio.wav", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
            "-shortest", "-movflags", "+faststart", str(out_path.resolve())], cwd=work)
    dur = duration(out_path)
    log("render", f"{out_path.name}: {dur:.1f}s, {out_path.stat().st_size / 1e6:.1f} MB")
    return dur


def render_filler(channel, work, out_path, seconds=30):
    """Looping ident shown when nothing is scheduled: channel name + 'back shortly'."""
    style = channel["style"]
    bold, reg = _pick_fonts()
    work.mkdir(parents=True, exist_ok=True)
    shutil.copy(bold, work / "bold.ttf")
    shutil.copy(reg, work / "reg.ttf")
    clip = _clip(work, "filler", seconds, style, [
        {"text": channel["name"], "size": 84, "y": 250, "bold": True, "width": 24},
        {"text": "Next episode coming up shortly", "size": 40, "y": 400, "start": 0.8,
         "color": style["accent"], "width": 44},
    ])
    wav = _wav(work, "filler", seconds)
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg(["-i", clip, "-i", wav, "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
            "-shortest", "-movflags", "+faststart", str(out_path.resolve())], cwd=work)
    return seconds
