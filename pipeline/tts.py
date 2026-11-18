"""Step 3: narration -> audio files. Provider is a config switch (edge | piper)."""
import asyncio
import shutil
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
    exe = shutil.which("piper")
    model = cfg.get("piper_model")
    if not exe or not model:
        raise RuntimeError("piper not installed or tts.piper_model not set")
    p = __import__("subprocess").run([exe, "--model", model, "--output_file", str(out_path)],
                                     input=text, text=True, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr[-300:])
