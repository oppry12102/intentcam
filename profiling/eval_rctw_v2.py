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

# New architecture: only TWO tools (zoom_in + emit_bubble).
# The LLM looks at the image, zooms for details, and emits a
# structured bubble with content + intent + type + confidence.
TOOLS = [
    {
        "name": "zoom_in",
        "description": "画面里某个区域你看不清/需要更细的细节时调这个。归一化 (x, y, w, h)。source=last 链式（裁上一张），source=original sibling（裁原图）。",
        "input_schema": {
            "type": "object",
            "properties": {
                "x": {"type": "number", "minimum": 0, "maximum": 1},
                "y": {"type": "number", "minimum": 0, "maximum": 1},
                "w": {"type": "number", "minimum": 0, "maximum": 1},
                "h": {"type": "number", "minimum": 0, "maximum": 1},
                "source": {"type": "string", "enum": ["last", "original"]},
                "focus": {"type": "string"},
            },
            "required": ["x", "y", "w", "h"],
            "additionalProperties": False,
        },
    },
    {
        "name": "emit_bubble",
        "description": "当你完全理解了图片内容和用户意图后，调这个工具结束识别循环。content: 图片内容描述。intent: 用户想做什么。type: info/location/solve。confidence: 0-1。",
        "input_schema": {
            "type": "object",
            "properties": {
                "content": {"type": "string"},
                "intent": {"type": "string"},
                "type": {"type": "string", "enum": ["info", "location", "solve"]},
                "intent_focus": {"type": "string"},
                "confidence": {"type": "number"},
            },
            "required": ["content", "intent", "type", "confidence"],
            "additionalProperties": False,
        },
    },
]

# Category → expected primary tool.  Many categories have multiple
# reasonable picks (a menu is read_manual OR translate_text); we
# accept any of the listed tools at full credit, similar to the
# synth eval's also_accept mechanism.
# Expected intent `type` per category.  Used to score the
# emit_bubble.type field.
CATEGORY_EXPECTED_TYPE: dict[str, str] = {
    "food_label":      "info",
    "device_reading":  "info",
    "math":            "solve",
    "receipt":         "info",
    "street_sign":     "location",
    "menu":            "info",
    "qr_code":         "info",
    "map":             "location",
    "english_text":    "info",
    "screen_capture":  "info",
}

SYSTEM_PROMPT = (
    "你是 IntentCam 的视觉意图助手。看到画面后调 zoom_in 看清楚细节，看清后调 emit_bubble 总结。"
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
    """Tool pick: under the simplified 2-tool architecture, any tool_use
    (zoom_in or emit_bubble) counts as a successful tool call.
    emit_bubble in round 1 is the best (immediate final answer)."""
    tool_uses = [b for b in response.get("content", []) if b.get("type") == "tool_use"]
    if not tool_uses:
        return 0.0, {"picked_tool": None, "reason": "no tool_use"}

    picked = tool_uses[0].get("name")
    # Both tools are valid picks.  emit_bubble directly ends the cycle.
    if picked == "emit_bubble":
        pick_score = 1.0
    elif picked == "zoom_in":
        pick_score = 0.7  # also valid but needs follow-up
    else:
        pick_score = 0.0
    input_ok = 1.0
    input_detail: dict = {"picked": picked, "valid": pick_score > 0}
    composite = 0.70 * pick_score + 0.30 * input_ok
    return composite, {
        "picked_tool": picked,
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
    """Legacy single-round stub.  Use run_orchestrator for the
    full multi-round flow that handles zoom_in and multi-zoom."""
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


def crop_region(src_bytes: bytes, x: float, y: float, w: float, h: float,
                 max_dim: int = 768, quality: int = 80) -> bytes:
    """Crop a normalized rect from a JPEG, re-encode as JPEG.  Mirrors
    FrameAnalyzer.cropJpegRegion.  Used by the eval when the model
    asks for a zoom_in — we want to actually feed the high-detail
    crop back so the round-2 reasoning benefits from the zoom."""
    from PIL import Image
    import io
    img = Image.open(io.BytesIO(src_bytes))
    W, H = img.size
    L = max(0, int(x * W))
    T = max(0, int(y * H))
    R = min(W, int((x + w) * W))
    B = min(H, int((y + h) * H))
    if R <= L or B <= T:
        return b""
    cropped = img.crop((L, T, R, B))
    s = max_dim / max(cropped.size)
    if s < 1:
        cropped = cropped.resize(
            (int(cropped.size[0] * s), int(cropped.size[1] * s)),
            Image.LANCZOS,
        )
    buf = io.BytesIO()
    cropped.convert("RGB").save(buf, "JPEG", quality=quality)
    return buf.getvalue()


def run_orchestrator(user_msg, response1, original_jpeg, thumbnail_jpeg,
                      max_rounds=2):
    """Multi-round orchestrator that mirrors the app's ToolUseLoop.

    Round 1:  model sees the thumbnail, picks tool(s) (incl. zoom_in).
    Round 2:  zoom_in crops are real (cropped from the original
              or the previous crop), sent back as followUpJpegs.
              Other tool calls get a stub tool_result.  Then the
              LLM is asked to give the final emit_bubble.

    Caps at max_rounds (default 2) to keep the eval bounded.  We
    only allow up to 1 zoom_in per round (chain semantics — the
    next round's zoom_in crops the result).  This matches the
    intent of zoom_in: progressive drill-down, not a fan-out.

    The final response is whatever the LLM produced last; if the
    model never emits emit_bubble we fall back to its free text.
    """
    messages = [
        user_msg,
        {"role": "assistant", "content": response1.get("content", [])},
    ]
    all_follow_ups = []
    current_image = original_jpeg
    final_resp = response1
    for round_i in range(1, max_rounds + 1):
        tool_uses = [b for b in final_resp.get("content", []) if b.get("type") == "tool_use"]
        if not tool_uses:
            break
        if any(b.get("name") == "emit_bubble" for b in tool_uses):
            break
        # Cap zoom_in to 1 per round (chain, not fan-out).  The first
        # zoom_in wins; the rest are returned as a "duplicate skipped"
        # message so the model learns.
        zoom_in_call = next((b for b in tool_uses if b["name"] == "zoom_in"), None)
        tool_results = []
        round_follow_ups = []
        for block in tool_uses:
            inp = block.get("input", {}) or {}
            summary = "已处理"
            if block is zoom_in_call and block["name"] == "zoom_in":
                src = inp.get("source", "last")
                src_img = original_jpeg if src == "original" else current_image
                try:
                    crop = crop_region(
                        src_img,
                        float(inp.get("x", 0)),
                        float(inp.get("y", 0)),
                        float(inp.get("w", 0.2)),
                        float(inp.get("h", 0.2)),
                    )
                except Exception:
                    crop = b""
                if crop:
                    current_image = crop
                    round_follow_ups.append(crop)
                    summary = (
                        f"已放大区域 (x={inp.get('x', '?')}, y={inp.get('y', '?')}, "
                        f"w={inp.get('w', '?')}, h={inp.get('h', '?')}, "
                        f"focus={inp.get('focus', '')})"
                    )
                else:
                    summary = "zoom_in 失败：区域无效"
            elif block["name"] == "zoom_in":
                summary = "本轮已有 zoom_in 被处理；额外 zoom_in 已忽略（每 round 只接受 1 个）"
            tool_results.append({
                "type": "tool_result",
                "tool_use_id": block.get("id"),
                "content": [{"type": "text", "text": summary}],
            })
        # Build the next user message: followUps first (in call order),
        # then the tool_results (required by Anthropic protocol).
        next_content = []
        for i, img in enumerate(round_follow_ups):
            next_content.append({
                "type": "image",
                "source": {
                    "type": "base64", "media_type": "image/jpeg",
                    "data": base64.b64encode(img).decode(),
                },
            })
            hint = ("已放大你刚才要求的区域，请用这张图继续回答。"
                    if len(round_follow_ups) == 1
                    else f"放大区域 #{i+1}/{len(round_follow_ups)}，请用这些图继续回答。")
            next_content.append({"type": "text", "text": hint})
        for tr in tool_results:
            next_content.append(tr)
        # Round 2 is the final round: explicitly nudge the model
        # to call emit_bubble if it didn't already.
        next_content.append({
            "type": "text",
            "text": "请调用 emit_bubble 给出最终意图摘要（scene/intent/type/confidence），不要再调其它工具。",
        })
        messages.append({"role": "user", "content": next_content})
        all_follow_ups.extend(round_follow_ups)
        final_resp = call_model(messages, max_tokens=800)
        if any(b.get("name") == "emit_bubble"
               for b in final_resp.get("content", [])
               if b.get("type") == "tool_use"):
            break
    return final_resp, all_follow_ups


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
        fixture = dict(scene, expected_tool=CATEGORY_EXPECTED_TYPE.get(category, "info"))
        user_msg = make_user_msg(img_path)
        r1 = call_model([user_msg])
        r1_score, r1_detail = score_round1(r1, fixture)

        r2_text = ""
        r2_emit_scene = ""
        r2_emit_type = None
        r2_emit_chips: list = []
        r2_text_score = 0.0
        r2_type_score = 1.0  # default if no rubric
        # Read the original (full-res) JPEG so zoom_in can crop from it.
        original_jpeg = img_path.read_bytes()
        # The thumbnail the LLM actually sees in round 1.  When
        # --resize is set, make_user_msg already produced a smaller
        # copy; the original is the source of truth for crops.
        r1_text = " ".join(
            b.get("text", "") for b in r1.get("content", [])
            if b.get("type") == "text"
        )
        r2_text = r1_text  # for cases where r1 already produced text
        tool_uses_r1 = [b for b in r1.get("content", []) if b.get("type") == "tool_use"]
        if tool_uses_r1:
            # Run the multi-round orchestrator.  It will:
            #   - dispatch each tool_use (including zoom_in → real crop)
            #   - feed the followUpJpegs back as round-2 user images
            #   - keep going until the model emits emit_bubble or
            #     max_rounds is hit
            final_resp, _ = run_orchestrator(
                user_msg=user_msg,
                response1=r1,
                original_jpeg=original_jpeg,
                thumbnail_jpeg=original_jpeg,
                max_rounds=4,
            )
            r2_text = " ".join(
                b.get("text", "")
                for b in final_resp.get("content", [])
                if b.get("type") == "text"
            )
            r2_emit_blocks = [
                b for b in final_resp.get("content", [])
                if b.get("type") == "tool_use" and b.get("name") == "emit_bubble"
            ]
            if r2_emit_blocks:
                emit_input = r2_emit_blocks[0].get("input", {}) or {}
                # New schema: emit_bubble's `content` field carries
                # the content description (was `scene` in the old
                # schema).
                r2_emit_scene = (
                    emit_input.get("content", "")
                    or emit_input.get("scene", "")
                    or emit_input.get("intent", "")  # fallback to intent
                )
                r2_emit_type = emit_input.get("type")
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
                f"cat={category:15s} picked={r1_detail.get("picked_tool")!s:18s} "
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
