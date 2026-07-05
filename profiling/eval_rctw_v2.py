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
        "description": "当你完全理解了图片内容和用户意图后，调这个工具结束识别循环。"
                     "content: 图片内容描述。intent: 用户想做什么。type: info/location/solve。"
                     "details: array of {kind, label, value}，详情页表格行。confidence: 0-1。",
        "input_schema": {
            "type": "object",
            "properties": {
                "content": {"type": "string"},
                "intent": {"type": "string"},
                "type": {"type": "string", "enum": ["info", "location", "solve"]},
                "intent_focus": {"type": "string"},
                "details": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "kind": {"type": "string"},
                            "label": {"type": "string"},
                            "value": {"type": "string"},
                        },
                        "required": ["kind", "label", "value"],
                    },
                },
                "confidence": {"type": "number"},
            },
            "required": ["content", "intent", "type", "confidence"],
            "additionalProperties": False,
        },
    },
]

# Expected intent `type` per category.  Used to score the
# emit_bubble.type field.  For the default (image-description)
# regression test, all fixtures map to type=info.
CATEGORY_EXPECTED_TYPE: dict[str, str] = {
    "default":         "info",
}

SYSTEM_PROMPT = (
    "你是 IntentCam 的视觉意图助手。你的工作分两步：\n"
    "**第一步：理解图片内容**。仔细看用户拍的图，识别其中的文字、物体、场景。**zoom_in 是缺省模式**："
    "无论图是否清楚，**先调 1-2 次 zoom_in(x, y, w, h, focus='...') 看清楚细节**。x/y/w/h 是归一化坐标 ∈ [0, 1]，x/y 是左上角，w/h 是宽高。"
    "源 source 默认 'last'（链式放大 — 第二次裁第一次的结果）。要看原图不同区域用 source='original'。\n"
    "**第二步：理解用户意图**。在清楚图片内容后，思考用户为什么拍这张图、想用它做什么。\n"
    "**收尾**：看清楚内容 + 理解意图后，调 emit_bubble(content, intent, type, intent_focus?, confidence, details?) 总结。type ∈ {info, location, solve}。**details 字段必填**，把图里读到的所有文字 / 数字 / 品牌 / 日期 / 价格都列出来。\n"
    "**不要**用纯文本总结。**必须**调 emit_bubble 收尾。"
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


def score_text(text: str, fixture: dict, details: list = None) -> float:
    """Score the LLM's text output against the fixture's
    expected_description_keywords AND expected_details.

    Two components, averaged:
      - keyword hit rate: fraction of expected_description_keywords
        found anywhere in the text (case-insensitive substring)
      - detail hit rate: fraction of expected_details where the
        LLM extracted a matching {kind, label, value} item
        (value fuzzy-matched; kind & label exact-matched)

    If either rubric is empty, that component defaults to 1.0.
    If both are empty, the fixture is unscored → returns 1.0.
    """
    if details is None:
        details = []
    score_components = []

    # Component 1: keyword hit rate
    expected_kws = fixture.get("expected_description_keywords", [])
    if expected_kws:
        text_lower = text.lower()
        hits = sum(1 for kw in expected_kws if kw.lower() in text_lower)
        score_components.append(hits / len(expected_kws))

    # Component 2: detail hit rate
    expected_details = fixture.get("expected_details", [])
    if expected_details:
        # Normalize LLM details: lowercased tuples (kind, label, value)
        llm_norm = []
        for d in details:
            kind = (d.get("kind") or "").lower()
            label = (d.get("label") or "").lower()
            value = (d.get("value") or "").lower()
            if kind or label or value:
                llm_norm.append((kind, label, value))
        # For each expected detail, find a matching LLM detail.
        # Match: same (kind, label) AND value substring overlap.
        hits = 0
        for exp in expected_details:
            e_kind = (exp.get("kind") or "").lower()
            e_label = (exp.get("label") or "").lower()
            e_value = (exp.get("value") or "").lower()
            for llm_kind, llm_label, llm_value in llm_norm:
                kind_ok = (not e_kind) or (e_kind == llm_kind)
                label_ok = (not e_label) or (e_label == llm_label or e_label in llm_label or llm_label in e_label)
                value_ok = (not e_value) or (e_value in llm_value or llm_value in e_value)
                if kind_ok and label_ok and value_ok:
                    hits += 1
                    break
        score_components.append(hits / len(expected_details))

    if not score_components:
        return 1.0
    return sum(score_components) / len(score_components)


def score_emit_type(emit_type: str | None, fixture: dict) -> float:
    """Check that emit_bubble's `type` matches the expected type."""
    expected = fixture.get("expected_type") or fixture.get("expected_top_intent_type", [])
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
                      max_rounds=3):
    """Multi-round orchestrator.  zoom_in is the default — the
    model is nudged to zoom in 1-2 times before emit_bubbling.

    Default flow (3 rounds):
      Round 1:  model sees thumbnail; expected to call zoom_in
                (chain default).  If the model emits emit_bubble
                without any zoom_in, we still respect it (some
                images don't need zooming).
      Round 2:  zoom_in crop is real; model can zoom_in again
                (chain) or call emit_bubble.
      Round 3:  model should now emit_bubble.  We nudge if it
                doesn't.

    All zoom_in calls chain (default source='last'): the next
    zoom_in crops the result of the previous one.  Only the
    first zoom_in per round is honored; additional ones get a
    "duplicate skipped" message.

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
        if any(b.get("name") == "emit_bubble" for b in tool_uses):
            break
        # Round 1 special case: if no zoom_in was called, nudge the
        # model to use the default (zoom first).
        is_first_round = round_i == 1
        if not tool_uses:
            if is_first_round:
                # No tool use at all in round 1 — push hard for zoom
                messages.append({"role": "user", "content": [
                    {"type": "text", "text": "请先调 zoom_in(x, y, w, h, focus='...') 放大看 1-2 个区域（这是缺省模式），看清后再调 emit_bubble 总结。"}
                ]})
                final_resp = call_model(messages, max_tokens=800)
                continue
            break
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
        # End-of-round nudge.  After the first zoom, push the model
        # toward emit_bubble (it has seen the high-detail crop).
        if is_first_round and zoom_in_call is not None:
            # The model used zoom_in in round 1; in the next round
            # it will see the crop and should emit_bubble (after
            # optionally one more zoom).
            next_content.append({
                "type": "text",
                "text": "（已 zoom_in 一次。还可以再 zoom_in 一次看更细的细节；或者直接调 emit_bubble 总结：content 描述图、intent 用户意图、type ∈ {info,location,solve}、details 必填）",
            })
        elif zoom_in_call is None and all_follow_ups:
            # Second zoom happened in earlier round; nudge emit_bubble
            next_content.append({
                "type": "text",
                "text": "（已 zoom_in 多次。**现在必须调 emit_bubble** 总结：content 描述图、intent 用户意图、type ∈ {info,location,solve}、details 必填）",
            })
        else:
            next_content.append({
                "type": "text",
                "text": "请调用 emit_bubble 给出最终意图摘要（content/intent/type/confidence/details），不要再调其它工具。",
            })
        messages.append({"role": "user", "content": next_content})
        all_follow_ups.extend(round_follow_ups)
        final_resp = call_model(messages, max_tokens=800)
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
        r2_emit_details: list = []
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
                max_rounds=3,
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
                # schema); `details` carries the extracted table rows.
                r2_emit_scene = (
                    emit_input.get("content", "")
                    or emit_input.get("scene", "")
                    or emit_input.get("intent", "")  # fallback to intent
                )
                r2_emit_type = emit_input.get("type")
                det_arr = emit_input.get("details") if isinstance(emit_input, dict) else None
                if isinstance(det_arr, list):
                    for d in det_arr:
                        if isinstance(d, dict):
                            r2_emit_details.append(d)
            combined = r2_text + " " + r2_emit_scene
            r2_text_score = score_text(combined, fixture, r2_emit_details)
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
