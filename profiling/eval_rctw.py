"""
Real-photo benchmark.  50 fixtures in `profiling/ground_truth_rctw.json`
covering 10 categories (food_label, device_reading, math, receipt,
street_sign, menu, qr_code, map, english_text, screen_capture).  Each
fixture is a Picsum photo with a category-relevant text overlay
(produced by `fetch_real_imgs.py`).

Scoring per fixture:
  composite = 0.50 * round1_score + 0.50 * round2_score
  round1_score = 0.70 * (tool-pick) + 0.30 * (input-valid)
  round2_score = average of:
    - must_have_in_scene_or_observation AND across substring groups
    - acceptable_intent_keywords   AND across OR-groups
  must_have_action_chip_tool bonus (+0.20, capped at 1.0)

The real GT uses a category-level `expected_top_intent_type` ("info"
vs "location" vs "solve") plus acceptable-keyword groups.  We
derive an `expected_tool` from `category` for round-1 scoring (the
tool-routing signal) and use the type + keywords for round-2.

Usage:
  python3 profiling/eval_real.py --resize 768 --quality 80
  python3 profiling/eval_real.py --resize 256 --quality 50

Without --resize, raw fixture bytes are sent to the LLM (synthetic-
quality + much larger payload than the app actually produces).
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
DEFAULT_GROUND_TRUTH = ROOT / "profiling" / "ground_truth_rctw.json"
IMG_DIR = ROOT / "img" / "rctw"

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "https://api.minimaxi.com/anthropic")
MODEL = os.environ.get("ANTHROPIC_MODEL", "MiniMax-M3")

# Reuse the same toolset as the synth eval.  Kept inline so this
# script is self-contained.
TOOLS = [
    {"name": "default_describe",
     "description": "无法确定用户意图时回退到这里。看完画面后直接调 emit_bubble 给出一段中文 JSON 摘要。",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "identify_animal_or_plant",
     "description": "画面主体是一只动物或一株植物（宠物、花卉、树木、昆虫）。",
     "input_schema": {"type": "object",
                     "properties": {"species_hint": {"type": "string"}}, "additionalProperties": False}},
    {"name": "identify_product",
     "description": "画面是带品牌名/包装设计/价格标签的完整商品。",
     "input_schema": {"type": "object",
                     "properties": {"focus": {"type": "string", "enum": ["ingredients", "expiry", "price", "link", "compare"]}},
                     "additionalProperties": False}},
    {"name": "navigate_to_block",
     "description": "画面是路牌、地图、街道场景。",
     "input_schema": {"type": "object",
                     "properties": {"destination": {"type": "string"},
                                    "mode": {"type": "string", "enum": ["where_am_i", "directions", "nearby"]}},
                     "additionalProperties": False}},
    {"name": "scan_qr_code",
     "description": "画面里有二维码（QR）。",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "translate_text",
     "description": "画面主体是外语文字内容（英文/日文等）。",
     "input_schema": {"type": "object",
                     "properties": {"target_lang": {"type": "string"}}, "additionalProperties": False}},
    {"name": "solve_problem",
     "description": "画面里有数学题、方程式或逻辑题。",
     "input_schema": {"type": "object",
                     "properties": {"operation": {"type": "string", "enum": ["solve", "verify", "factor", "show_work"]}},
                     "additionalProperties": False}},
    {"name": "read_screen",
     "description": "画面是手机/电脑屏幕截图。",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "read_manual",
     "description": "画面是说明书/菜谱/合同等多栏文档。",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "read_device_reading",
     "description": "画面是医疗/健康设备的数值读数。",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "ask_user",
     "description": "需要用户进一步澄清时调这个。question 字段是给用户看的问题。",
     "input_schema": {"type": "object",
                     "properties": {"question": {"type": "string"}},
                     "required": ["question"], "additionalProperties": False}},
    {"name": "zoom_in",
     "description": "画面里某个区域看不清/需要更细的细节时调这个。归一化 (x, y, w, h)。",
     "input_schema": {"type": "object",
                     "properties": {
                         "x": {"type": "number", "minimum": 0, "maximum": 1},
                         "y": {"type": "number", "minimum": 0, "maximum": 1},
                         "w": {"type": "number", "minimum": 0, "maximum": 1},
                         "h": {"type": "number", "minimum": 0, "maximum": 1},
                         "source": {"type": "string", "enum": ["last", "original"]},
                         "focus": {"type": "string"},
                     },
                     "required": ["x", "y", "w", "h"], "additionalProperties": False}},
    {"name": "emit_bubble",
     "description": "最终把用户意图的摘要填到这里。",
     "input_schema": {"type": "object",
                     "properties": {
                         "scene": {"type": "string"},
                         "intent": {"type": "string"},
                         "type": {"type": "string", "enum": ["info", "location", "solve"]},
                         "confidence": {"type": "number"},
                         "action_chips": {"type": "array", "items": {"type": "object"}},
                     },
                     "required": ["scene", "intent", "type", "confidence"], "additionalProperties": False}},
]

# Category → expected primary tool.  Many categories have multiple
# reasonable picks (a menu is read_manual OR translate_text); we
# accept any of the listed tools at full credit, similar to the
# synth eval's also_accept mechanism.
CATEGORY_TOOL_MAP: dict[str, list[str]] = {
    "food_label":      ["identify_product", "read_manual", "translate_text"],
    "device_reading":  ["read_device_reading", "read_manual"],
    "math":            ["solve_problem"],
    "receipt":         ["read_manual", "read_device_reading"],
    "street_sign":     ["navigate_to_block", "translate_text", "read_manual"],
    "menu":            ["read_manual", "translate_text"],
    "qr_code":         ["scan_qr_code", "translate_text"],
    "map":             ["navigate_to_block", "read_manual"],
    "english_text":    ["translate_text", "read_manual"],
    "screen_capture":  ["read_screen", "read_manual"],
}

SYSTEM_PROMPT = (
    "你是 IntentCam 的工具调用助手。看到画面后必须调一个工具来处理它。"
    "不要直接用文字描述画面内容。最终必须调 emit_bubble 给出意图摘要。"
)

RESIZE_MAX_DIM = 0
RESIZE_QUALITY = 75


def call_model(messages: list, max_tokens: int = 640) -> dict:
    body = {
        "model": MODEL,
        "max_tokens": max_tokens,
        "temperature": 0.0,
        "system": SYSTEM_PROMPT,
        "messages": messages,
        "tools": TOOLS,
    }
    token = os.environ.get("ANTHROPIC_AUTH_TOKEN", "")
    if not token:
        sys.exit("ANTHROPIC_AUTH_TOKEN not set in env")
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
        return {"_error": f"HTTP {e.code}", "_body": e.read().decode(errors="replace")[:600]}


def make_user_msg(image_path: Path) -> dict:
    """Optionally simulate FrameAnalyzer's resize+re-encode so the
    fixture bytes match what the app actually produces."""
    if RESIZE_MAX_DIM > 0:
        from PIL import Image
        import io
        with Image.open(image_path) as img:
            if max(img.size) > RESIZE_MAX_DIM:
                scale = RESIZE_MAX_DIM / max(img.size)
                new_w = max(1, int(img.size[0] * scale))
                new_h = max(1, int(img.size[1] * scale))
                img = img.resize((new_w, new_h), Image.LANCZOS)
            buf = io.BytesIO()
            img.convert("RGB").save(buf, "JPEG", quality=RESIZE_QUALITY)
            jpeg_bytes = buf.getvalue()
    else:
        jpeg_bytes = image_path.read_bytes()
    return {
        "role": "user",
        "content": [
            {"type": "image",
             "source": {"type": "base64", "media_type": "image/jpeg",
                        "data": base64.b64encode(jpeg_bytes).decode()}},
            {"type": "text", "text": "调用工具。"},
        ],
    }


def score_round1(response: dict, fixture: dict) -> tuple[float, dict]:
    """Tool pick: any of the category-mapped tools counts as correct."""
    tool_uses = [b for b in response.get("content", []) if b.get("type") == "tool_use"]
    if not tool_uses:
        return 0.0, {"picked_tool": None, "reason": "no tool_use"}

    picked = tool_uses[0].get("name")
    acceptable = CATEGORY_TOOL_MAP.get(fixture["category"], [])
    pick_score = 1.0 if picked in acceptable else 0.0
    input_ok = 1.0
    input_detail: dict = {}
    if pick_score == 1.0:
        # Validate enum fields against the schema
        tool_input = picked[0] if isinstance(picked, tuple) else tool_uses[0].get("input", {}) or {}
        if not isinstance(tool_input, dict):
            tool_input = {}
        focus = tool_input.get("focus")
        if focus is not None and focus not in [
            "ingredients", "expiry", "price", "link", "compare",
            "solve", "verify", "factor", "show_work",
            "where_am_i", "directions", "nearby",
        ]:
            input_ok = 0.0
        input_detail["input"] = tool_input

    composite = 0.70 * pick_score + 0.30 * input_ok
    return composite, {
        "picked_tool": picked,
        "acceptable": acceptable,
        "tool_pick_ok": pick_score == 1.0,
        "input_ok": input_ok == 1.0,
        **input_detail,
    }


def score_text(text: str, fixture: dict) -> float:
    """Mirror evaluate.py's must_have + acceptable_intent_keywords
    scoring: each OR-group counts; the average across all AND-groups
    is the score."""
    if not text:
        return 0.0
    score_components = []

    must_have = fixture.get("must_have_in_scene_or_observation", [])
    if must_have:
        hits = 0
        for group in must_have:
            candidates = group if isinstance(group, list) else [group]
            if any(c.lower() in text.lower() for c in candidates):
                hits += 1
        score_components.append(hits / len(must_have))

    keyword_groups = fixture.get("acceptable_intent_keywords", [])
    if keyword_groups:
        # OR within group (at least one keyword), AND across groups.
        passed = 0
        for group in keyword_groups:
            inner = group if isinstance(group, list) else [group]
            if any(kw.lower() in text.lower() for kw in inner):
                passed += 1
        score_components.append(passed / len(keyword_groups))

    if not score_components:
        return 1.0
    return sum(score_components) / len(score_components)


def score_emit_type(emit_type: str | None, fixture: dict) -> float:
    """Check that emit_bubble's `type` matches expected_top_intent_type."""
    expected = fixture.get("expected_top_intent_type", [])
    if not expected or not emit_type:
        return 1.0  # no rubric
    return 1.0 if emit_type in expected else 0.0


def run_follow_up(user_msg: dict, response1: dict) -> dict:
    tool_uses = [b for b in response1.get("content", []) if b.get("type") == "tool_use"]
    if not tool_uses:
        return response1
    call = tool_uses[0]
    tool_result_content = json.dumps(
        {"ok": True, "tool": call.get("name"), "note": "已记录，请继续"},
        ensure_ascii=False,
    )
    follow_up_msg = {
        "role": "user",
        "content": [
            {"type": "tool_result", "tool_use_id": call.get("id"),
             "content": tool_result_content},
            {"type": "text", "text": "请继续回答。"},
        ],
    }
    return call_model(
        [user_msg,
         {"role": "assistant", "content": response1.get("content", [])},
         follow_up_msg],
        max_tokens=640,
    )


def main() -> int:
    global RESIZE_MAX_DIM, RESIZE_QUALITY
    parser = argparse.ArgumentParser()
    parser.add_argument("--ground-truth", default=str(DEFAULT_GROUND_TRUTH))
    parser.add_argument("--resize", type=int, default=0,
                        help="Max-dim cap to simulate FrameAnalyzer "
                             "(0 = no resize, send raw fixture).")
    parser.add_argument("--quality", type=int, default=75,
                        help="JPEG quality when --resize is set.")
    parser.add_argument("--limit", type=int, default=0,
                        help="Optional cap on number of fixtures (0 = all).")
    args = parser.parse_args()
    RESIZE_MAX_DIM = args.resize
    RESIZE_QUALITY = args.quality

    gt_path = Path(args.ground_truth)
    if not gt_path.exists():
        sys.exit(f"missing ground truth: {gt_path}")
    gt = json.loads(gt_path.read_text())
    scenes = gt.get("scenes", [])
    if not scenes:
        sys.exit("no scenes in ground truth")
    if args.limit > 0:
        scenes = scenes[:args.limit]
    print(f"Loaded {len(scenes)} real-photo fixtures from {gt_path.name}")
    print(f"FrameAnalyzer simulation: --resize {RESIZE_MAX_DIM} --quality {RESIZE_QUALITY}")

    per_category: dict[str, list[float]] = {}
    all_results: list[dict] = []

    for i, scene in enumerate(scenes, 1):
        scene_id = scene.get("id", "?")
        category = scene.get("category", "?")
        img_name = scene.get("file")
        if not img_name:
            continue
        img_path = IMG_DIR / img_name
        if not img_path.exists():
            print(f"  SKIP {scene_id}: missing {img_path}")
            continue
        fixture = dict(scene, expected_tool=CATEGORY_TOOL_MAP.get(category, ["?"])[0])
        user_msg = make_user_msg(img_path)
        r1 = call_model([user_msg])
        r1_score, r1_detail = score_round1(r1, fixture)

        r2_text = ""
        r2_emit_scene = ""
        r2_emit_type = None
        r2_emit_chips: list = []
        r2_text_score = 0.0
        r2_type_score = 1.0  # default if no rubric
        tool_uses = [b for b in r1.get("content", []) if b.get("type") == "tool_use"]
        if tool_uses:
            r2 = run_follow_up(user_msg, r1)
            r2_text = " ".join(
                b.get("text", "")
                for b in r2.get("content", [])
                if b.get("type") == "text"
            )
            r2_emit_blocks = [
                b for b in r2.get("content", [])
                if b.get("type") == "tool_use" and b.get("name") == "emit_bubble"
            ]
            if r2_emit_blocks:
                emit_input = r2_emit_blocks[0].get("input", {}) or {}
                r2_emit_scene = emit_input.get("scene", "") or ""
                r2_emit_type = emit_input.get("type")
                r2_emit_chips = emit_input.get("action_chips", []) or []
            combined = r2_text + " " + r2_emit_scene
            r2_text_score = score_text(combined, fixture)
            r2_type_score = score_emit_type(r2_emit_type, fixture)
        # round2_score is 50/50 text-match + emit-type
        r2_score = 0.5 * r2_text_score + 0.5 * r2_type_score

        composite = 0.50 * r1_score + 0.50 * r2_score
        all_results.append({
            "id": scene_id,
            "category": category,
            "composite": composite,
            "r1": r1_score,
            "r2_text": r2_text_score,
            "r2_type": r2_type_score,
        })
        per_category.setdefault(category, []).append(composite)

        if i <= 5 or i == len(scenes) or i % 10 == 0:
            chips_summary = "[" + ",".join(c.get("tool", "?") for c in r2_emit_chips) + "]" if r2_emit_chips else "[]"
            print(
                f"  [{i:2d}/{len(scenes)}] {scene_id:30s} "
                f"cat={category:15s} picked={r1_detail.get('picked_tool')!s:18s} "
                f"r1={r1_score:.2f} r2_text={r2_text_score:.2f} r2_type={r2_type_score:.2f} "
                f"composite={composite:.2f} chips={chips_summary}"
            )

    # Per-category + overall summary
    print()
    print("=" * 60)
    print(f"fixtures: {len(all_results)}")
    overall = sum(r["composite"] for r in all_results) / max(1, len(all_results))
    print(f"average composite: {overall:.3f}")
    print()
    print(f"{'category':18s} {'n':>3s} {'avg':>6s}")
    for cat, scores in sorted(per_category.items()):
        avg = sum(scores) / len(scores)
        print(f"{cat:18s} {len(scores):>3d} {avg:>6.3f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
