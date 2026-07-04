"""
Tool-use eval.  Like `evaluate.py` but sends `tools[]` to the LLM and
scores based on which tool the model picks + whether its `input` is
well-formed.  For tools that ultimately produce a final answer bubble
(default_describe, identify_product, etc.), the model's free-text
follow-up is also scored against the fixture's `must_have` /
`acceptable_keywords` lists.

Each fixture in `ground_truth_tooluse.json` declares:
  - `image`: path relative to project root
  - `expected_tool`: which tool the model should pick
  - `required_input_keys`: keys that must appear in the tool_use input
  - `acceptable_input_enums`: optional map of key -> list of acceptable
    values for the input field (e.g. focus = "link" | "compare")
  - `acceptable_intent_keywords`: optional OR-of-AND groups for the
    follow-up text (same shape as `ground_truth.json`)
  - `must_have_in_final_text`: optional list of substrings (case
    insensitive) that should appear in the final assistant text

Per-fixture output:
  - `picked_tool`: tool name emitted in round 1
  - `tool_input_ok`: True when the input is parseable JSON with all
    `required_input_keys` present
  - `final_text_match`: 0..1 fraction of must-have substrings found
  - `composite`: 0.40*tool_pick + 0.20*input_ok + 0.40*text_match

Usage:
    python3 profiling/evaluate_tooluse.py
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

# Module-level globals for the optional resize pipeline.  Set by
# main() based on --resize / --quality CLI flags.  Default 0 means
# "send raw fixture bytes" (the historical behavior of evaluate_tooluse.py).
RESIZE_MAX_DIM = 0
RESIZE_QUALITY = 75

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_GROUND_TRUTH = ROOT / "profiling" / "ground_truth_tooluse.json"

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "https://api.minimaxi.com/anthropic")
MODEL = os.environ.get("ANTHROPIC_MODEL", "MiniMax-M3")

# Mirrors AppViewModel.SYSTEM_TOOL_USE — the model expects to be told
# to use a tool.  Without this directive the model skips tool_use and
# produces a free-form description.
SYSTEM_PROMPT = (
    "你是 IntentCam 的工具调用助手。看到画面后，你必须调用一个工具来处理它。"
    "不要直接用文字描述画面内容（那是 default_describe 的工作）。"
    "如果拿不准选哪个工具，就调 default_describe 让旧逻辑接管。"
    "回复必须是中文。纯文本和 tool_use 可同回合出现，但第一回合必须调用工具。"
)


# Toolset mirrors [registerDefaultTools] in AppViewModel.kt.  Only the
# fields the model actually reads matter; keep `description` terse to
# fit more context in the request.
TOOLS = [
    {
        "name": "default_describe",
        "description": "无法确定用户意图时回退到这。它会用三轮 CoT 描述画面并给出 1-4 个候选意图。",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "identify_animal_or_plant",
        "description": "画面里是一只动物或一株植物（宠物、花卉、树木、昆虫），想识别种类、了解习性或养护方法时选这个。",
        "input_schema": {
            "type": "object",
            "properties": {
                "species_hint": {"type": "string"},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "identify_product",
        "description": "画面是带品牌名/包装设计/价格标签的完整商品（整瓶、整盒、整袋食品饮料或日用品），用户想知道品牌、配料、保质期、价格、购买链接或对比同类时选这个。"
            "关键判别：画面必须是真正的『商品』（带品牌标识、瓶身/盒装设计、产品名清晰），而不是纯文字表格/面板。"
            "如果是纯英文 Nutrition Facts 表、英文说明书、英文合同、外文菜单等『文字内容为主』的画面，不要选这个 — 选 translate_text（要翻译）或 read_manual（要读懂）。",
        "input_schema": {
            "type": "object",
            "properties": {
                "focus": {"type": "string", "enum": ["ingredients", "expiry", "price", "link", "compare"]},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "navigate_to_block",
        "description": "画面是路牌、地图、街道场景，想知道『我在哪』『怎么去某地』或附近有什么时选这个。",
        "input_schema": {
            "type": "object",
            "properties": {
                "destination": {"type": "string"},
                "mode": {"type": "string", "enum": ["where_am_i", "directions", "nearby"]},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "ask_user",
        "description": "画面里有多种可能走向、用户意图不明确、或者你需要某个具体子问题才能继续时调这个。"
            "question 字段是给用户看的问题（中文，一句话，≤30字）。"
            "调完这个工具会弹出输入框；用户输入后会作为 userText 回到下一轮，那时再决定调哪个具体工具或直接 emit_bubble。"
            "**不要**用纯文本问问题 — 必须用 tool_use 让 UI 弹真正的输入框。",
        "input_schema": {
            "type": "object",
            "properties": {
                "question": {"type": "string"},
            },
            "required": ["question"],
            "additionalProperties": False,
        },
    },
    {
        "name": "scan_qr_code",
        "description": "画面是二维码/条码/数据矩阵码，想解码内容并按内容类型给出建议操作时选这个。",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "translate_text",
        "description": "画面主体是外语文字内容（英文/日文等），用户想把外文翻译成中文时选这个。"
            "适用：纯英文 Nutrition Facts 表、英文 UI 文字、外文菜单、英文歌词/字幕、外文海报标题等以『文字内容为主』的画面。"
            "不适用：如果是带品牌名/包装设计的完整商品（食品、饮料、日用品包装），选 identify_product。"
            "不适用：如果是说明书/合同/菜谱等多栏文档，选 read_manual。"
            "不适用：如果是医疗/健康设备读数（血压、血糖、体温），选 read_device_reading。",
        "input_schema": {
            "type": "object",
            "properties": {
                "target_language": {"type": "string"},
                "scope": {"type": "string", "enum": ["full", "lines"]},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "solve_problem",
        "description": "画面是数学题、公式、解题步骤、试卷，需要求解、化简、验证或类似题时选这个。",
        "input_schema": {
            "type": "object",
            "properties": {
                "operation": {"type": "string", "enum": ["solve", "verify", "similar", "factor"]},
                "show_steps": {"type": "boolean"},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "read_screen",
        "description": "画面是手机/电脑/平板的截屏或屏幕内容，想打开某个 App、调出设置、读邮件、翻译某段时选这个。",
        "input_schema": {
            "type": "object",
            "properties": {"intent_text": {"type": "string"}},
            "additionalProperties": False,
        },
    },
    {
        "name": "read_manual",
        "description": "画面是说明书、操作步骤文档、菜谱、合同、药品用法用量等多栏/段落型文档，用户想读懂内容时选这个。"
            "关键判别：内容以段落/编号步骤呈现（『Step 1: ...』『Warning: ...』），不是单个数值读数。"
            "不适用：如果是单个数值（如血压 128/82），那是 read_device_reading。"
            "不适用：如果是处方药标签（不是设备，是药品包装），选这个 read_manual。"
            "不适用：如果是纯英文 UI/菜单/歌词等单层文字，那是 translate_text。",
        "input_schema": {
            "type": "object",
            "properties": {"focus": {"type": "string"}},
            "additionalProperties": False,
        },
    },
    {
        "name": "read_device_reading",
        "description": "画面是医疗/健康设备显示屏的单个数值读数（血压计 128/82、体重秤 73.4kg、体温计 36.8°C、血糖仪、BMI 显示器等）。"
            "画面特征：一个大数字 + 单位 + 时间戳。关键判别：用户问的是『这个数值是什么意思』『是否正常』。"
            "不适用：如果是说明书/操作步骤文档（『Step 1: ...』），那是 read_manual。"
            "不适用：如果是处方药标签（不是设备，是药品包装），选 read_manual。",
        "input_schema": {
            "type": "object",
            "properties": {
                "reading_type": {"type": "string"},
                "normal_range_check": {"type": "boolean"},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "emit_bubble",
        "description": "最终把用户意图的摘要填到这里 —— 调这个工具来结束识别流程。"
            "scene: 画面看到了什么（一句话）; intent: 用户最可能的意图（动宾短语 ≤12 字）;"
            "type: info / location / solve; confidence: 0.0~1.0。"
            "可选 action_chips: 0-3 个 follow-up 操作建议，会作为可点 chip 出现在详情页。"
            "每个 chip = {label(≤8字), tool(已在 tools[] 里), tool_input(传给该 tool 的 JSON)}。"
            "chip tool 必须从 tools[] 里已有的工具里选，常见的：identify_product/translate_text/"
            "solve_problem/read_manual/read_device_reading/scan_qr_code/navigate_to_block/ask_user。"
            "可以同时输出纯文本回答（让用户看到详细解读），但 tool_use 让字段结构化、更可靠。",
        "input_schema": {
            "type": "object",
            "properties": {
                "scene": {"type": "string"},
                "intent": {"type": "string"},
                "type": {"type": "string", "enum": ["info", "location", "solve"]},
                "confidence": {"type": "number"},
                "action_chips": {
                    "type": "array",
                    "description": "0-3 个 follow-up 操作建议（用户可点的 chip）",
                    "items": {
                        "type": "object",
                        "properties": {
                            "label": {"type": "string"},
                            "tool": {"type": "string"},
                            "tool_input": {"type": "object"},
                        },
                        "required": ["label", "tool"],
                        "additionalProperties": False,
                    },
                    "maxItems": 3,
                },
            },
            "required": ["scene", "intent", "type", "confidence"],
            "additionalProperties": False,
        },
    },
]


def call_model(messages: list, max_tokens: int = 200) -> dict:
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
        body = e.read().decode(errors="replace")[:600]
        return {"_error": f"HTTP {e.code}", "_body": body}


def make_user_msg(image_path: Path, prompt: str) -> dict:
    # Optional: simulate the FrameAnalyzer pipeline by resizing +
    # re-encoding the fixture before sending.  Without this the raw
    # fixture bytes (often 1000+ px) are sent to the LLM, which is
    # NOT what the app does at runtime.  Pass --resize N (and optionally
    # --quality Q) to match a specific FrameAnalyzer config.
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
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": base64.b64encode(jpeg_bytes).decode(),
                },
            },
            {"type": "text", "text": prompt},
        ],
    }


def score_round1(response: dict, fixture: dict) -> tuple[float, dict]:
    """Score the round-1 tool selection + input validity.  Returns a
    tuple of (composite, detail)."""
    tool_uses = [b for b in response.get("content", []) if b.get("type") == "tool_use"]
    if not tool_uses:
        return 0.0, {"picked_tool": None, "reason": "no tool_use block"}

    picked = tool_uses[0]
    picked_tool = picked.get("name")
    expected_tool = fixture.get("expected_tool")
    also_accept = fixture.get("also_accept", [])

    pick_score = 1.0 if picked_tool in [expected_tool] + also_accept else 0.0
    input_ok = 1.0
    input_detail = {}
    if pick_score == 1.0:
        required_keys = fixture.get("required_input_keys", [])
        tool_input = picked.get("input", {}) or {}
        for key in required_keys:
            if key not in tool_input:
                input_ok = 0.0
        # Validate enum if specified
        for key, allowed in fixture.get("acceptable_input_enums", {}).items():
            if key in tool_input and tool_input[key] not in allowed:
                input_ok = 0.0
                input_detail[f"enum_mismatch_{key}"] = tool_input[key]
        input_detail["input"] = tool_input

    # Round-1 composite: heavy weight on tool pick, light on input shape.
    composite = 0.70 * pick_score + 0.30 * input_ok
    detail = {
        "picked_tool": picked_tool,
        "expected_tool": expected_tool,
        "tool_pick_ok": pick_score == 1.0,
        "input_ok": input_ok == 1.0,
        **input_detail,
    }
    return composite, detail


def score_text(text: str, fixture: dict) -> float:
    """Score the final-text against fixture's must-have list and
    acceptable-keyword groups.  Mirrors the scoring philosophy of
    evaluate.py:must_have_in_scene_or_observation is AND across
    substring groups, with each group being a string OR list of aliases."""
    if not text:
        return 0.0
    score_components = []

    must_have = fixture.get("must_have_in_final_text", [])
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
        return 1.0  # no rubric → give full credit
    return sum(score_components) / len(score_components)


def run_follow_up(user_msg: dict, response1: dict, fixture: dict) -> dict:
    """Send tool_result back and ask for final text.  Returns the
    second-turn response."""
    tool_uses = [b for b in response1.get("content", []) if b.get("type") == "tool_use"]
    if not tool_uses:
        return response1
    call = tool_uses[0]

    # Build a synthetic tool_result for the handler.  Real handlers run
    # locally in the app; for eval we use a stub that just acknowledges
    # the tool was selected.
    tool_result_content = json.dumps(
        {"ok": True, "tool": call.get("name"), "note": "已记录，请继续"},
        ensure_ascii=False,
    )
    follow_up_msg = {
        "role": "user",
        "content": [
            {"type": "tool_result", "tool_use_id": call.get("id"), "content": tool_result_content},
            {"type": "text", "text": "请继续回答。"},
        ],
    }
    return call_model(
        [
            user_msg,
            {"role": "assistant", "content": response1.get("content", [])},
            follow_up_msg,
        ],
        max_tokens=640,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ground-truth", default=str(DEFAULT_GROUND_TRUTH))
    parser.add_argument("--resize", type=int, default=0,
                        help="Max-dim cap to simulate FrameAnalyzer "
                             "(0 = no resize, send raw fixture).")
    parser.add_argument("--quality", type=int, default=75,
                        help="JPEG quality when --resize is set.")
    args = parser.parse_args()
    # Module-level globals read by make_user_msg.
    global RESIZE_MAX_DIM, RESIZE_QUALITY
    RESIZE_MAX_DIM = args.resize
    RESIZE_QUALITY = args.quality
    gt_path = Path(args.ground_truth)
    if not gt_path.exists():
        sys.exit(f"missing ground truth: {gt_path}")
    gt = json.loads(gt_path.read_text())

    fixtures = gt.get("fixtures", [])
    if not fixtures:
        sys.exit("no fixtures in ground truth")

    results = []
    for fx in fixtures:
        img_rel = fx.get("image")
        if not img_rel:
            continue
        img_path = ROOT / img_rel
        if not img_path.exists():
            print(f"SKIP {fx.get('id', img_rel)}: missing {img_path}")
            continue
        print(f"--- {fx.get('id', img_rel)} ---")
        user_msg = make_user_msg(img_path, "调用工具。")
        r1 = call_model([user_msg])
        round1_score, r1_detail = score_round1(r1, fx)

        # Round-2 only if round 1 was a tool call.
        r2_text = ""
        r2_emit_scene = ""
        r2_emit_chips = []
        r2_score = 0.0
        tool_uses = [b for b in r1.get("content", []) if b.get("type") == "tool_use"]
        if tool_uses:
            r2 = run_follow_up(user_msg, r1, fx)
            text_blocks = [b.get("text", "") for b in r2.get("content", []) if b.get("type") == "text"]
            r2_text = " ".join(text_blocks)
            # If the model emits emit_bubble in round 2, its `scene`
            # field is the structured equivalent of the free-form text.
            # Fall back to it when text is empty so emit_bubble-style
            # answers still get scored.
            r2_emit_blocks = [
                b for b in r2.get("content", [])
                if b.get("type") == "tool_use" and b.get("name") == "emit_bubble"
            ]
            if r2_emit_blocks:
                emit_input = r2_emit_blocks[0].get("input", {}) or {}
                r2_emit_scene = emit_input.get("scene", "") or ""
                r2_emit_chips = emit_input.get("action_chips", []) or []
            combined = r2_text + " " + r2_emit_scene
            r2_score = score_text(combined, fx)
            # Bonus credit if the fixture requires a specific action_chip
            # tool and emit_bubble provided one with that name.
            want_chip = fx.get("must_have_action_chip_tool")
            if want_chip:
                hit = any(c.get("tool") == want_chip for c in r2_emit_chips)
                if hit:
                    r2_score = min(1.0, r2_score + 0.20)
                r2_detail_chips = (
                    f"chip_match={hit} chip_tools={[c.get('tool') for c in r2_emit_chips]}"
                )
            else:
                r2_detail_chips = ""

        composite = 0.50 * round1_score + 0.50 * r2_score
        results.append(
            {
                "id": fx.get("id"),
                "expected_tool": fx.get("expected_tool"),
                "composite": composite,
                "round1": r1_detail,
                "round2_text_preview": r2_text[:120],
                "round2_text_score": r2_score,
                "round2_chips": [c.get("tool") for c in r2_emit_chips],
            }
        )
        print(f"  picked={r1_detail.get('picked_tool')} (want {fx.get('expected_tool')}) "
              f"input_ok={r1_detail.get('input_ok')} "
              f"text_score={r2_score:.2f} composite={composite:.2f} "
              f"chips={[c.get('tool') for c in r2_emit_chips] or '-'}")
        if r2_text:
            print(f"  text: {r2_text[:100]}...")

    print()
    print("=" * 60)
    print(f"fixtures: {len(results)}")
    avg = sum(r["composite"] for r in results) / max(1, len(results))
    print(f"average composite: {avg:.3f}")
    bad = [r for r in results if r["composite"] < 0.5]
    if bad:
        print(f"low-scoring ({len(bad)}):")
        for r in bad:
            print(f"  {r['id']}: composite={r['composite']:.2f} "
                  f"picked={r['round1'].get('picked_tool')!r} "
                  f"want={r['expected_tool']!r}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())