"""Step 3: narration -> audio files. Provider is a config switch (edge | piper)."""
import asyncio
import subprocess
import sys
from pathlib import Path

from common import log, run


def synth(tts_cfg, text, out_path):
    """Write speech for `text` to out_path (mp3 or wav), trying provider then fallback."""
    errors = []
    for name in [tts_cfg.get("provider", "edge"), tts_cfg.get("fallback")]:
        if not name:
            continue
        try:
            {"edge": _edge, "piper": _piper}[name](tts_cfg, text, out_path)
            return
        except Exception as e:  # fail soft to the fallback provider
            errors.append(f"{name}: {e}")
            log("tts", f"{name} failed: {e}")
    raise RuntimeError("all TTS providers failed: " + "; ".join(errors))


def _edge(cfg, text, out_path):
    import edge_tts

    async def go():
        await edge_tts.Communicate(text, cfg.get("voice", "en-NZ-MitchellNeural")).save(str(out_path))

    asyncio.run(go())
    if not Path(out_path).is_file() or Path(out_path).stat().st_size < 1000:
        raise RuntimeError("edge-tts produced no audio")


def _piper(cfg, text, out_path):
    """CPU-only fallback voice (pip package piper-tts). The voice model is fetched on first use,
    so a normal edge-tts run never downloads it. Output is converted to a real mp3."""
    voice = cfg.get("piper_voice", "en_GB-alan-medium")
    cache = Path(cfg.get("piper_dir") or Path(__file__).resolve().parent.parent / "out" / "piper")
    cache.mkdir(parents=True, exist_ok=True)
    if not (cache / f"{voice}.onnx").is_file():
        p = subprocess.run([sys.executable, "-m", "piper.download_voices", voice, "--download-dir", str(cache)],
                           capture_output=True, text=True, timeout=600)
        if p.returncode != 0:
            raise RuntimeError("piper voice download failed: " + p.stderr[-300:])
    wav = Path(out_path).with_suffix(".piper.wav")
    p = subprocess.run([sys.executable, "-m", "piper", "-m", voice, "--data-dir", str(cache),
                        "-f", str(wav)], input=text, text=True, encoding="utf-8",
                       capture_output=True, timeout=600)
    if p.returncode != 0 or not wav.is_file():
        raise RuntimeError("piper failed: " + p.stderr[-300:])
    try:
        run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-codec:a", "libmp3lame",
             "-q:a", "4", str(out_path)])
    finally:
        wav.unlink(missing_ok=True)
    if not Path(out_path).is_file() or Path(out_path).stat().st_size < 1000:
        raise RuntimeError("piper produced no audio")
