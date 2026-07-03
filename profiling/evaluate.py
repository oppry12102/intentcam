"""
Evaluate the LLM intent classifier against the test fixtures in
`profiling/ground_truth.json`.

Usage:
    python3 profiling/evaluate.py \\
        [--system profiling/system.txt] \\
        [--user   profiling/user.txt] \\
        [--temperature 0] \\
        [--no-stream]              # default is non-streaming; skip SSE overhead
        [--images IMG1.jpg IMG2.jpg]

The script prints a per-image scorecard:
  - top-1 type matches expected?
  - top-1 title contains at least one "acceptable keyword group"
  - scene / observation contain any must_have key?
  - raw model output for debugging

A summary table at the end helps compare prompt rounds.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_GROUND_TRUTH = ROOT / "profiling" / "ground_truth.json"

# ----- LLM defaults: match the LlmClient constants in app source code -----
BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "https://api.minimaxi.com/anthropic")
MODEL = os.environ.get("ANTHROPIC_MODEL", "MiniMax-M3")
MAX_TOKENS = 320

DEFAULT_SYSTEM = (
    "你是手机端实时视觉意图助手。从摄像头画面准确推断用户意图。"
    "先用 observation 字段描述所见，再给 scene、intents。严格只输出 JSON。"
)

DEFAULT_USER = """分析摄像头画面，必须分三步思考：
1. observation: 描述画面中物体/文字/数字/场景（≤40字）
2. scene: 用户视角的画面描述（≤20字）
3. intents: 用户最可能的意图列表

- 位置: 未知

返回 JSON（仅 JSON，不要任何其它文字）:
{"observation":"...","scene":"...","intents":[{"type":"info|location|solve","title":"≤8字","detail":"一句话","confidence":0.0}]}
最多 4 个意图，按 confidence 降序。
type:info=查信息/wifi/快递,location=我在哪/去哪,solve=解题/解决问题。
confidence 必须反映真实把握度（看不清的给低分）。"""


def call_model(image_bytes: bytes, system: str, user: str, temperature: float = 0.0) -> dict:
    """POST the image + prompt to /v1/messages, return parsed JSON response."""
    body = {
        "model": MODEL,
        "max_tokens": MAX_TOKENS,
        "temperature": temperature,
        "system": system,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image",
                 "source": {"type": "base64", "media_type": "image/jpeg", "data": base64.b64encode(image_bytes).decode()}},
                {"type": "text", "text": user},
            ],
        }],
    }
    token = os.environ.get("ANTHROPIC_AUTH_TOKEN", "")
    if not token:
        sys.exit("ANTHROPIC_AUTH_TOKEN is not set in env; export it before running")
    req = urllib.request.Request(
        f"{BASE_URL.rstrip('/')}/v1/messages",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "anthropic-version": "2023-06-01",
            "x-api-key": token,
            "authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")[:600]
        return {"_error": f"HTTP {e.code}", "_body": body}


def extract_assistant_text(response: dict) -> str:
    if "_error" in response:
        return ""
    parts = response.get("content") or []
    chunks = []
    for p in parts:
        if p.get("type") == "text":
            chunks.append(p.get("text", ""))
    return "\n".join(chunks).strip()


def parse_json_from_response(text: str) -> dict | None:
    s = text.find("{")
    e = text.rfind("}")
    if s < 0 or e <= s:
        return None
    try:
        return json.loads(text[s:e + 1])
    except json.JSONDecodeError:
        return None


def score(scene_gt: dict, parsed: dict | None) -> dict:
    """Score the model output against ground truth.  Returns dict of booleans/metrics."""
    if not parsed:
        return {"ok": False, "reason": "no_json", "score": 0}

    intents = parsed.get("intents") or []
    observation = parsed.get("observation", "")
    scene = parsed.get("scene", "")
    haystack_text = (observation + "\n" + scene).lower()

    # 1) Top-1 type matches expected?
    top = intents[0] if intents else None
    type_match = bool(top and top.get("type") == scene_gt["expected_top_intent_type"])

    # 2) Top-1 title contains any acceptable keyword group
    title_match = False
    matched_group = None
    if top:
        title_lc = (top.get("title") or "").lower()
        detail_lc = (top.get("detail") or "").lower()
        for i, keywords in enumerate(scene_gt["acceptable_intent_keywords"]):
            if any(kw.lower() in title_lc or kw.lower() in detail_lc for kw in keywords):
                title_match = True
                matched_group = i
                break

    # 3) Must-have keywords appear somewhere in observation/scene
    must_have = scene_gt.get("must_have_in_scene_or_observation", [])
    must_have_hits = [kw for kw in must_have if kw.lower() in haystack_text]
    must_have_pct = (len(must_have_hits) / len(must_have)) if must_have else 1.0

    # 4) Confidence calibration note
    confidence = top.get("confidence") if top else None

    # Composite score: weighted sum; weighted heavier on type & title match.
    composite = (
        (0.45 if type_match else 0.0)
        + (0.35 if title_match else 0.0)
        + (0.20 * must_have_pct)
    )

    return {
        "ok": True,
        "type_match": type_match,
        "title_match": title_match,
        "matched_keyword_group": matched_group,
        "must_have_hits": must_have_hits,
        "must_have_pct": must_have_pct,
        "top_intent": top,
        "confidence": confidence,
        "scene": scene,
        "observation": observation,
        "composite": round(composite, 3),
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ground-truth", default=str(DEFAULT_GROUND_TRUTH))
    p.add_argument("--images", nargs="+", default=None,
                   help="specific image paths (default: all in ground truth)")
    p.add_argument("--system", default=DEFAULT_SYSTEM)
    p.add_argument("--user", default=DEFAULT_USER)
    p.add_argument("--temperature", type=float, default=0.0)
    args = p.parse_args()

    gt = json.loads(Path(args.ground_truth).read_text(encoding="utf-8"))
    scenes = gt["scenes"]

    # Load prompts from files if paths
    if Path(args.system).exists():
        system = Path(args.system).read_text(encoding="utf-8")
    else:
        system = args.system
    if Path(args.user).exists():
        user = Path(args.user).read_text(encoding="utf-8")
    else:
        user = args.user

    print("=" * 70)
    print(f"model:  {MODEL}")
    print(f"base:   {BASE_URL}")
    print(f"temp:   {args.temperature}")
    print(f"system: ({len(system)} chars)")
    print(f"user:   ({len(user)} chars)")
    print("=" * 70)

    rows = []
    for scene_gt in scenes:
        if args.images and scene_gt["image"] not in [Path(i).name for i in args.images]:
            continue
        img_path = ROOT / scene_gt["image"]
        if not img_path.exists():
            print(f"missing: {img_path}")
            continue

        print(f"\n[ {scene_gt['id']} ]  {scene_gt['image']}  — {scene_gt['what_is_pictured']}")
        img_bytes = img_path.read_bytes()
        resp = call_model(img_bytes, system, user, temperature=args.temperature)

        if "_error" in resp:
            print(f"  HTTP error: {resp['_error']}")
            print(f"  body: {resp['_body'][:300]}")
            rows.append({"scene": scene_gt["id"], "composite": 0.0, "type_match": False, "title_match": False})
            continue

        text = extract_assistant_text(resp)
        parsed = parse_json_from_response(text)
        result = score(scene_gt, parsed)

        print(f"  observation: {(result.get('observation') or '—')[:80]}")
        print(f"  scene:       {(result.get('scene') or '—')[:80]}")
        top = result.get("top_intent")
        if top:
            print(f"  top intent:  type={top.get('type')} "
                  f"title='{top.get('title')}' "
                  f"confidence={top.get('confidence')} "
                  f"detail='{(top.get('detail') or '')[:60]}'")
        if parsed and (parsed.get("intents") or []):
            print(f"  full intents ({len(parsed['intents'])}):")
            for it in parsed["intents"]:
                print(f"    - [{it.get('confidence', 0):.2f}] {it.get('type')} "
                      f"'{it.get('title')}' — '{it.get('detail')}'")
        if not parsed:
            print(f"  RAW (unparseable): {text[:300]}")
        print(f"  score:        composite={result['composite']}  "
              f"type={'Y' if result['type_match'] else 'N'}  "
              f"title={'Y' if result['title_match'] else 'N'}(group={result.get('matched_keyword_group')})  "
              f"must_have={result.get('must_have_pct', 0):.2f}")
        rows.append({
            "scene": scene_gt["id"],
            "composite": result["composite"],
            "type_match": result["type_match"],
            "title_match": result["title_match"],
            "must_have_pct": result.get("must_have_pct", 0.0),
            "confidence": result.get("confidence"),
            "top_title": (top or {}).get("title", "—") if top else "—",
        })

    print("\n" + "=" * 70)
    print("Summary")
    print("-" * 70)
    print(f"{'scene':<8} {'composite':>9} {'type':>6} {'title':>6} "
          f"{'must':>6} {'conf':>6}  top_title")
    for r in rows:
        conf = r["confidence"]
        conf_s = f"{conf:.2f}" if conf is not None else "—"
        print(f"{r['scene']:<8} {r['composite']:>9.3f} "
              f"{'Y' if r['type_match'] else 'N':>6} "
              f"{'Y' if r['title_match'] else 'N':>6} "
              f"{r['must_have_pct']:>6.2f} "
              f"{conf_s:>6}  {r['top_title']}")
    avg = sum(r["composite"] for r in rows) / max(1, len(rows))
    print(f"\nAverage composite: {avg:.3f}")


if __name__ == "__main__":
    main()
