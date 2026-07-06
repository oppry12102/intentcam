"""
Real-photo benchmark.  100 fixtures in `profiling/ground_truth_rctw.json`,
all in the `default` category (image-description + text-transcription
regression test).  Each fixture is a Picsum photo with a category-
relevant text overlay (produced by `fetch_real_imgs.py`).

Scoring per fixture:
  composite = 0.50 * round1_score + 0.50 * round2_score
  round1_score = 0.70 * (tool-pick) + 0.30 * (input-valid)
  round2_score = 0.50 * (text-keyword hit rate) + 0.50 * (emit-type)
  where text-keyword hit rate = avg of:
    - fraction of expected_description_keywords found in model's text
    - fraction of expected_details matched in emit_bubble.details

This script mirrors the app's 3-tool architecture (zoom_in +
read_text + emit_bubble).  The app now bundles 4 quadrant crops
into round 1 so the LLM can read small text directly — read_text
is registered but the prompt steers the model to NOT call it by
default (ML Kit OCR noise on calligraphy / handwritten text was
polluting emit_bubble.content during on-device testing).  read_text
is exposed here so eval and app see the same tool set; the eval's
read_text handler returns a stub since there's no on-device ML
Kit on the eval machine.

Usage:
  python3 profiling/eval_rctw_v2.py --resize 768 --quality 80
  python3 profiling/eval_rctw_v2.py --limit 50 --resize 256 --quality 50

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

# Mirrors the app's 3-tool architecture (LlmClient.TOOL_USE_SYSTEM +
# ToolImplementations.registerDefaultTools) so the eval and the app
# see the same tool surface and prompt.  read_text stays in TOOLS but
# the prompt steers the model to default-NOT-use it; the eval handler
# returns a stub since the eval machine has no on-device ML Kit.
# Keep these three in sync: TOOLS here, the orchestrator's read_text
# branch, and the tool description in this file MUST match the app.
TOOLS = [
    {
        "name": "zoom_in",
        "description": "画面里某个区域你看不清/需要更细的细节时调这个。"
                     "归一化坐标 (x, y, w, h) ∈ [0, 1]，x/y 是左上角，w/h 是宽高。"
                     "source 字段默认 'last'（链式放大 — 第二次裁第一次的结果）。要看原图不同区域用 source='original'。"
                     "focus 字段是你想在那个区域找什么。**不能**用它抄文字——它只放大，不读字。",
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
        "name": "read_text",
        "description": "[**默认不要用 — 本地 OCR 兜底**] 读取图像某区域的逐字文字内容（on-device 中英 OCR，完全离线）。"
                     "**什么时候调**：你已经 zoom_in 多次还看不清，且文字看起来像清晰的**印刷体**（菜单价签、收据数字、门牌号、说明书标题）。"
                     "**什么时候不要调**：书法、手写、艺术字、模糊图、远景——OCR 在这些场景不可靠，调了会把噪声喂进你的答案。"
                     "**默认靠你自己读**——round 1 已经附了 4 张四象限裁剪，你直接读图比 OCR 更可控、更准。"
                     "参数：x, y, w, h 是归一化坐标 ∈ [0, 1]；source 默认 'last'（链式），要扫原图不同区域用 'original'。",
        "input_schema": {
            "type": "object",
            "properties": {
                "x": {"type": "number", "minimum": 0, "maximum": 1},
                "y": {"type": "number", "minimum": 0, "maximum": 1},
                "w": {"type": "number", "minimum": 0, "maximum": 1},
                "h": {"type": "number", "minimum": 0, "maximum": 1},
                "source": {"type": "string", "enum": ["last", "original"]},
            },
            "required": ["x", "y", "w", "h"],
            "additionalProperties": False,
        },
    },
    {
        "name": "emit_bubble",
        "description": "当你完全理解了图片内容和用户意图后，调这个工具结束识别循环。"
                     "**content 字段关键**：必须把图里所有可见文字 / 数字 / 品牌 / 日期 / 价格 / 联系方式原样写出来（如\"包装文字：'品名 工夫红茶' 净含量 '250g'  生产日期 '2020-12-01'\"）。"
                     "intent: 用户想做什么。type: info/location/solve。"
                     "details: array of {kind, label, value}，详情页表格行——把图里读到的关键文字逐行列出。confidence: 0-1。",
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
    "你是 IntentCam 的视觉意图助手。你有三个工具（read_text 默认不要用）：\n"
    "\n"
    "## 第 1 步：读懂图（你最擅长这个）\n"
    "你一次会看到 5 张图：1 张全图概览 + 4 张四象限裁剪（左上 / 右上 / 左下 / 右下）。" +
    "四象限裁剪和原图是同一个像素预算下的不同区域——意味着你能直接看清每个角落的小字、细节、价格、电话号码，**不需要先调工具**。\n"
    "\n"
    "## 工具 1: zoom_in —— 定位（看清细节）\n"
    "把图里某区域裁出来放大，返回裁剪后的图供你下一轮查看。\n"
    "参数：x, y, w, h 是归一化坐标 ∈ [0, 1]；x/y 是左上角，w/h 是宽高。"
    "source 字段默认 \'last\'（链式放大 — 第二次裁第一次的结果，坐标相对）。要看原图不同区域用 source=\'original\'（绝对坐标，兄弟视图）。"
    "**用途**：当四象限裁剪还看不清楚某一块（极小字、远景、特定细节）时再调。\n"
    "\n"
    "## 工具 2: read_text —— 本地 OCR（**默认不要用 — 兜底**）\n"
    "对图里某区域跑 on-device OCR，**离线、完全在设备上**，返回**逐字字符串**。"
    "参数和 zoom_in 一样：x, y, w, h, source。\n"
    "**默认不要用**。OCR 在书法、手写、艺术字、模糊图上**不可靠**，调了反而把噪声喂进你的答案——大多数情况下你直接读四象限裁剪就够了，**更可控、更准**。"
    "**仅在以下情况考虑调**：已经 zoom_in 多次仍看不清、且文字看起来像清晰印刷体（菜单价格、收据数字、门牌号）时。\n"
    "\n"
    "## 工具 3: emit_bubble —— 收尾（结构化总结）\n"
    "看清楚内容 + 理解意图后，调 emit_bubble(content, intent, type, intent_focus?, confidence, details?) 总结。"
    "type ∈ {info, location, solve}。\n"
    "\n"
    "## 工作流程\n"
    "1. 一次看 5 张图（已附），定位大致内容 + 找到所有文字区域（通常四象限裁剪已经够清楚）。\n"
    "2. 如果四象限还看不清某块，调 zoom_in 放大。\n"
    "3. **文字靠 zoom_in 一遍遍看清**——印刷体一次能看清；小字 / 艺术字 / 模糊调多次，每次聚焦更小的子区域。\n"
    "4. 思考用户为什么拍这张图（意图）。\n"
    "5. 调 emit_bubble 收尾。\n"
    "\n"
    "## content 字段要求（**最严格**）\n"
    "content 必须包含图里**所有可见**文字 / 数字 / 品牌 / 日期 / 价格 / 联系方式，**原样**写出来：\n"
    "  - 茶叶包装 → content 写\"包装文字：\'品名: 工夫红茶\', \'净含量: 250g\', \'生产日期: 2020-12-01\'\"\n"
    "  - 路牌 → \"建国路 100号\"\n"
    "  - 收据 → \"合计 ¥168.50, 微信支付\"\n"
    "  - 菜单 → \"宫保鸡丁 ¥38, 鱼香肉丝 ¥42\"\n"
    "  - 门牌 → \"1203\"\n"
    "\n"
    "## 反幻觉（**关键**）\n"
    "**看不清的字宁可不写也别瞎猜**。content 漏一个字符比写错一个好——用户会按你写的内容去做事，错字比漏字危险得多。" +
    "对不确定的字可以写 \'?\' 占位（比如\'??路 100号\'），但**绝不要发明文字**。" +
    "书法 / 手写 / 模糊字宁可空着也别假装读出来。\n"
    "\n"
    "**不要**用纯文本总结。**必须**调 emit_bubble 收尾。"
)

RESIZE_MAX_DIM = 0
RESIZE_QUALITY = 75


def call_model(messages: list, max_tokens: int = 640) -> dict:
    """Single round-trip to the model.  Catches HTTPError, TimeoutError,
    ConnectionError, OSError (all the transient failures the upstream
    server throws when it's slow or overloaded) and returns them as
    a uniform `{"_error": ..., "_body": ...}` shape so callers can
    skip the fixture without crashing the whole run.  Adds one retry
    on TimeoutError — running 20 fixtures back-to-back occasionally
    hits the 60s default timeout."""
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
    last_exc = None
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return {"_error": f"HTTP {e.code}", "_body": e.read().decode(errors="replace")[:600]}
        except (TimeoutError, ConnectionError, OSError) as e:
            last_exc = e
            if attempt == 0:
                continue  # one retry on transient network/server slowness
            return {"_error": f"{type(e).__name__}", "_body": str(e)[:600]}
    return {"_error": "unknown", "_body": str(last_exc)[:600]}


def make_user_msg(image_path: Path) -> dict:
    """Build the round-1 user message: 1 thumbnail + 4 quadrant crops
    + a short text prompt.  Mirrors FrameAnalyzer.bufferToFrame so the
    eval sees what the app actually produces.  Optionally simulates
    the FrameAnalyzer's resize+re-encode (when --resize > 0)."""
    from PIL import Image
    import io

    with Image.open(image_path) as img:
        if RESIZE_MAX_DIM > 0 and max(img.size) > RESIZE_MAX_DIM:
            scale = RESIZE_MAX_DIM / max(img.size)
            img = img.resize(
                (max(1, int(img.size[0] * scale)),
                 max(1, int(img.size[1] * scale))),
                Image.LANCZOS,
            )
        thumbnail = _encode_jpeg(img, RESIZE_QUALITY)
        # Quadrant crops: each is 50% × 50% of the (possibly resized)
        # image, re-encoded at QUADRANT_QUALITY.  This mirrors
        # FrameAnalyzer.QUADRANT_MAX_DIM / QUADRANT_QUALITY.
        W, H = img.size
        quadrants = []
        for fx, fy, fw, fh in [
            (0.0, 0.0, 0.5, 0.5),  # top-left
            (0.5, 0.0, 0.5, 0.5),  # top-right
            (0.0, 0.5, 0.5, 0.5),  # bottom-left
            (0.5, 0.5, 0.5, 0.5),  # bottom-right
        ]:
            L = int(fx * W)
            T = int(fy * H)
            R = int((fx + fw) * W)
            B = int((fy + fh) * H)
            crop = img.crop((L, T, R, B))
            quadrants.append(_encode_jpeg(crop, RESIZE_QUALITY))

    def _image_block(jpeg_bytes: bytes) -> dict:
        return {
            "type": "image",
            "source": {
                "type": "base64",
                "media_type": "image/jpeg",
                "data": base64.b64encode(jpeg_bytes).decode(),
            },
        }

    return {
        "role": "user",
        "content": [
            _image_block(thumbnail),
            *(_image_block(q) for q in quadrants),
            {"type": "text", "text": "调用工具。"},
        ],
    }


def _encode_jpeg(img, quality: int) -> bytes:
    """Encode a PIL Image as JPEG bytes.  Mirrors
    FrameAnalyzer.encodeBitmap's scale+compress for in-process use."""
    from PIL import Image
    import io
    if max(img.size) > 768:
        scale = 768 / max(img.size)
        img = img.resize(
            (max(1, int(img.size[0] * scale)),
             max(1, int(img.size[1] * scale))),
            Image.LANCZOS,
        )
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=quality)
    return buf.getvalue()


def score_round1(response: dict, fixture: dict) -> tuple[float, dict]:
    """Tool pick: under the 2-tool architecture (zoom_in + emit_bubble)
    both are valid first picks.  emit_bubble directly ends the cycle;
    zoom_in starts an inspection cycle.  The 4 quadrant crops in
    round 1 give the model enough detail to skip the read_text
    inspection step entirely on most fixtures.
    """
    tool_uses = [b for b in response.get("content", []) if b.get("type") == "tool_use"]
    if not tool_uses:
        return 0.0, {"picked_tool": None, "reason": "no tool_use"}

    picked = tool_uses[0].get("name")
    if picked == "emit_bubble":
        pick_score = 1.0
    elif picked in ("zoom_in", "read_text"):
        # Both are valid tool picks — the prompt steers toward
        # zoom_in first but read_text is exposed as a fallback for
        # printed text.  Same score for either: the tool routing
        # decision itself doesn't move the metric.
        pick_score = 0.7
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
    # Track cumulative text content too — the model may put useful
    # text in any round (not just emit_bubble).  We take the last
    # non-empty text as a fallback if the model never emits_bubble.
    last_text_content = ""
    for round_i in range(1, max_rounds + 1):
        tool_uses = [b for b in final_resp.get("content", []) if b.get("type") == "tool_use"]
        # Capture free text from this round (model might write a draft
        # before emit_bubble, or in lieu of it).
        round_text = " ".join(
            b.get("text", "") for b in final_resp.get("content", [])
            if b.get("type") == "text" and b.get("text", "").strip()
        )
        if round_text:
            last_text_content = round_text
        if any(b.get("name") == "emit_bubble" for b in tool_uses):
            break
        is_first_round = round_i == 1
        if not tool_uses:
            if is_first_round:
                messages.append({"role": "user", "content": [
                    {"type": "text", "text": "请先调 zoom_in(x, y, w, h, focus='...') 放大看 1-2 个区域（缺省模式），看清后再调 emit_bubble 总结。"}
                ]})
                final_resp = call_model(messages, max_tokens=800)
                continue
            # No tool_use in a non-first round.  Two sub-cases:
            #   a) model gave text — accept it, done.
            #   b) model gave an empty / malformed response (some model
            #      builds occasionally return `{"content": []}` after a
            #      read_text/zoom_in).  Don't overwrite the previous
            #      round's `final_resp` — that round is still the most
            #      recent meaningful answer we have.
            if not final_resp.get("content"):
                # Roll back to the previous round's response if we
                # have one in `messages`.
                prev = messages[-1] if messages else None
                if isinstance(prev, dict) and prev.get("role") == "assistant":
                    final_resp = prev
            break
        zoom_in_call = next((b for b in tool_uses if b["name"] == "zoom_in"), None)
        # Per-round counters — only the FIRST zoom_in emits a follow-up
        # image, the rest are answered with a "duplicate skipped" note so
        # the model's behaviour stays observable but the message stream
        # doesn't blow up.
        tool_results = []
        round_follow_ups = []
        for block in tool_uses:
            inp = block.get("input", {}) or {}
            name = block.get("name")
            summary = "已处理"
            if name == "zoom_in":
                if block is zoom_in_call:
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
                else:
                    summary = "本轮已有 zoom_in 被处理；额外 zoom_in 已忽略（每 round 只接受 1 个）"
            elif name == "read_text":
                # Stub: the eval machine has no on-device ML Kit (or
                # any other OCR).  We mirror the on-device behaviour by
                # appending the cropped region as a follow-up image so
                # the model can look at what it asked for, and return
                # a placeholder text in the tool_result content.  Real
                # on-device read_text returns the verbatim OCR string;
                # here the model is told OCR is unavailable so it
                # doesn't trust a fabricated string.
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
                    round_follow_ups.append(crop)
                    summary = (
                        "OCR (eval stub, 0字): [read_text unavailable in eval — "
                        "real on-device ML Kit returns the verbatim string here]"
                    )
                else:
                    summary = "read_text 失败：区域无效"
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
        # End-of-round nudge.  Push the model toward emit_bubble once
        # it has zoomed in at least once — by then it has the
        # high-detail crop it needs.
        cumulative_zooms = len(all_follow_ups) + len(round_follow_ups)
        if is_first_round and zoom_in_call is not None:
            next_content.append({
                "type": "text",
                "text": "（已 zoom_in 一次。还可以再调一轮 zoom_in 看更细的；或者直接调 emit_bubble 总结：content 原样写图里所有可见文字/数字，intent 用户意图，type=info，details 必填）",
            })
        elif cumulative_zooms >= 2:
            # Two or more zooms done — FORCE emit_bubble now.
            next_content.append({
                "type": "text",
                "text": "（已 zoom_in 多次，看清细节了。**必须调 emit_bubble** 总结：content 列出图里所有可见文字/数字/品牌/日期/价格，intent 用户意图，type=info，details 把每个读出的文字列成 {kind, label, value}）",
            })
        else:
            next_content.append({
                "type": "text",
                "text": "请调用 emit_bubble 给出最终意图摘要（content/intent/type/confidence/details），不要再调其它工具。",
            })
        messages.append({"role": "user", "content": next_content})
        all_follow_ups.extend(round_follow_ups)
        final_resp = call_model(messages, max_tokens=800)
    # Stash last_text_content on final_resp so the caller can read
    # it (the eval uses it as a content fallback).
    if last_text_content and final_resp is not None:
        final_resp["_last_free_text"] = last_text_content
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
    parser.add_argument("--limit", type=int, default=20,
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
        # If the first round-trip itself failed, log and skip the
        # rest of the orchestrator — the fixture scores 0 across
        # the board but the eval keeps going.
        if "_error" in r1:
            print(f"  [SKIP {scene_id}] r1 error: {r1['_error']} | {r1.get('_body','')[:120]}")
            all_results.append({
                "id": scene_id, "category": category,
                "composite": 0.0, "r1": 0.0, "r2_text": 0.0, "r2_type": 0.0,
            })
            continue
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
            # Fallback: if the model never emitted_bubble but did
            # write useful text in some round, include it.
            if not r2_emit_scene and final_resp.get("_last_free_text"):
                combined = final_resp["_last_free_text"]
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
