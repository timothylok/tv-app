"""Shared helpers: paths, channel config, logging, ffmpeg/ffprobe runners."""
import json
import os
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CHANNELS_DIR = ROOT / "channels"
OUT_DIR = ROOT / "out"
STATE_DIR = ROOT / "state"


_PUNCT = {"‘": "'", "’": "'", "“": '"', "”": '"', "–": "-",
          "—": " - ", "…": "...", " ": " "}


def clean_text(text):
    """Make text safe for on-screen fonts: ASCII punctuation, no invisible/format chars."""
    import unicodedata
    text = "".join(_PUNCT.get(c, c) for c in str(text))
    text = "".join(c if unicodedata.category(c)[0] not in "CZ" or c == " " else " "
                   for c in text)
    return " ".join(text.split())


def log(step, msg):
    print(f"[{step}] {msg}", flush=True)


def load_channel(channel_id):
    path = CHANNELS_DIR / f"{channel_id}.yaml"
    if not path.is_file():
        sys.exit(f"unknown channel '{channel_id}': {path} not found")
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(path, data):
    """Write to a temp file then rename, so a crash never leaves a half file."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def run(cmd, cwd=None):
    """Run a command; raise with its stderr tail on failure."""
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise RuntimeError(f"{cmd[0]} failed ({p.returncode}): {p.stderr[-1500:]}")
    return p


def ffmpeg(args, cwd=None):
    return run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *args], cwd=cwd)


def duration(path):
    p = run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=nw=1:nk=1", str(path)])
    return float(p.stdout.strip())


def load_env():
    """Load KEY=VALUE lines from the git-ignored .env (real environment variables win)."""
    path = ROOT / ".env"
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            if v.strip():
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
