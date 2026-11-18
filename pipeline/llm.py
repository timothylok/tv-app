"""LLM provider switch: ollama (local) or workers-ai (Cloudflare free tier, runs on CI)."""
import json
import os

import requests

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")


def generate_json(llm_cfg, system, prompt):
    """Return a parsed JSON object from the configured provider."""
    provider = llm_cfg.get("provider", "ollama")
    if provider == "ollama":
        return _ollama(llm_cfg, system, prompt)
    if provider == "workers-ai":
        return _workers_ai(llm_cfg, system, prompt)
    raise NotImplementedError(f"LLM provider '{provider}' is not implemented yet")


def _parse_json(text):
    """Models sometimes wrap JSON in ``` fences or add a sentence around it."""
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`").split(chr(10), 1)[-1]
    start, end = text.find("{"), text.rfind("}")
    return json.loads(text[start:end + 1] if start >= 0 and end > start else text)


def _workers_ai(cfg, system, prompt):
    """Cloudflare Workers AI REST API. Needs CLOUDFLARE_API_TOKEN (Workers AI permission);
    the account id comes from CLOUDFLARE_ACCOUNT_ID or from the R2 endpoint host."""
    from urllib.parse import urlparse
    from common import load_env
    load_env()
    token = os.environ.get("CLOUDFLARE_API_TOKEN")
    account = os.environ.get("CLOUDFLARE_ACCOUNT_ID") or         (urlparse(os.environ.get("R2_ENDPOINT", "")).hostname or "").split(".")[0]
    if not token or not account:
        raise RuntimeError("workers-ai needs CLOUDFLARE_API_TOKEN (and an account id)")
    model = cfg.get("model", "@cf/meta/llama-3.3-70b-instruct-fp8-fast")
    body = {
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": prompt}],
        "response_format": {"type": "json_object"},
        "max_tokens": cfg.get("max_tokens", 4096),
        "temperature": cfg.get("temperature", 0.4),
    }
    r = requests.post(
        f"https://api.cloudflare.com/client/v4/accounts/{account}/ai/run/{model}",
        headers={"Authorization": f"Bearer {token}"}, json=body, timeout=cfg.get("timeout", 120))
    if r.status_code != 200:  # never echo headers; the body holds Cloudflare's error text only
        raise RuntimeError(f"workers-ai HTTP {r.status_code}: {r.text[:300]}")
    out = r.json().get("result", {}).get("response")
    return out if isinstance(out, dict) else _parse_json(str(out))


def _ollama(cfg, system, prompt):
    body = {
        "model": cfg.get("model", "llama3.1:8b"),
        "stream": False,
        "format": "json",
        "think": False,
        "options": {"temperature": cfg.get("temperature", 0.4), "num_ctx": 8192},
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": prompt}],
    }
    r = requests.post(f"{OLLAMA_URL}/api/chat", json=body, timeout=cfg.get("timeout", 600))
    r.raise_for_status()
    return json.loads(r.json()["message"]["content"])
