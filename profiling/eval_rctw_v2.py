"""
Real-photo benchmark.  100 fixtures in `profiling/ground_truth_rctw.json`,
all in the `default` category (image-description + text-transcription
regression test).  Each fixture is a Picsum photo with a category-
relevant text overlay (produced by `fetch_real_imgs.py`).

Scoring per fixture (mirrors `shared/.../eval/EvalRunner.kt`):
  composite = 0.50 * round1_score + 0.50 * round2_score
  round1_score = 0.70 * (tool-pick) + 0.30 * (input-valid)
    pick_score = 1.0 (zoom_in / read_text / compare_text first pick)
               = 0.85 (emit_bubble first / no tool_use, when fixture has text)
               = 1.0  (same, when no text expected)
               = 0.7  (unknown tool, legacy fallback)
  round2_score = 0.50 * (text-keyword hit rate, NFKC + fuzzy) + 0.50 * (emit-type, 3-way partial)
  where text-keyword hit rate = avg of:
    - fraction of expected_description_keywords found in model's text (fuzzy)
    - fraction of expected_details matched in emit_bubble.details (value-only fuzzy)

This script mirrors the app's 4-tool architecture (zoom_in +
read_text + compare_text + emit_bubble), 1-only round-1 (no
quadrant crops; model relies on the round-1 OCR hint + zoom_in
chain).  The system prompt is the verbatim Kotlin const
`LlmClient.TOOL_USE_SYSTEM` (shared/.../LlmClient.kt:415-479).
Orchestrator mirrors `ToolUseLoop.runCycle()` — MAX_ROUNDS=30,
compare_text handler does pure on-device string diff against the
round-1 OCR cache, call_model errors fall back to a synthesized
Bubble from lastRound.text (mirrors `ToolUseLoop.kt:459-490`).

OCR backend is Huawei Cloud `RecognizeGeneralText` since the eval
machine has no Android device.  Production uses HMS ML Kit
on-device.  Both expose the same normalized {text, confidence,
bbox[4]} shape so compare_text's diff is backend-agnostic.

Usage:
  python3 profiling/eval_rctw_v2.py --resize 768 --quality 80 --limit 100
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

# OCR backend.  In the app this is on-device HMS ML Kit, but the
# eval machine has no Android device — so we substitute the
# `profiling/ocr_huaweicloud.py` wrapper which calls Huawei Cloud
# `RecognizeGeneralText` (same SDK the demo uses).  When env vars
# HUAWEICLOUD_SDK_AK / SK / PROJECT_ID are set, read_text returns
# real OCR text; otherwise it falls back to the old stub and
# prints a one-line warning.
import ocr_huaweicloud as ocr_hw
_OCR_BACKEND_LOGGED = False


def _ocr_read_text_region(jpeg_bytes: bytes, x: float, y: float,
                          w: float, h: float) -> str:
    """Run OCR on a normalized region of `jpeg_bytes`.  Returns a
    short one-line string formatted like the on-device tool result:
    "OCR (云端 ML Kit, N字): \"verbatim text\"".
    Falls back to the eval stub if the SDK/env isn't available.
    """
    global _OCR_BACKEND_LOGGED
    if not (ocr_hw.sdk_available() and ocr_hw.env_available()):
        if not _OCR_BACKEND_LOGGED:
            print(
                "  [ocr] Huawei Cloud OCR not configured "
                "(huaweicloudsdkocr missing or HUAWEICLOUD_SDK_* unset) — "
                "read_text uses stub.  export HUAWEICLOUD_SDK_AK / SK / "
                "PROJECT_ID and `pip install huaweicloudsdkocr` to enable."
            )
            _OCR_BACKEND_LOGGED = True
        return (
            "OCR (eval stub, 0字): [read_text unavailable in eval — "
            "real on-device ML Kit returns the verbatim string here]"
        )
    try:
        blocks = ocr_hw.crop_then_recognize(jpeg_bytes, x, y, w, h)
    except Exception as e:
        return f"OCR (云端 ML Kit 错误): {type(e).__name__}: {str(e)[:120]}"
    if not blocks:
        return "OCR (云端 ML Kit, 0字): [未识别到文字]"
    text = ocr_hw.blocks_to_text(blocks, sep=" | ")
    return f"OCR (云端 ML Kit, {len(blocks)}行 / {len(text)}字): \"{text}\""


# ----- Round-1 OCR hint (mirrors endcloud OcrEngine.formatHint) -----
HINT_MAX_LINES = 30
HINT_LOW_THRESHOLD = 0.5


def format_hint(blocks: list[dict],
                max_lines: int = HINT_MAX_LINES,
                low_threshold: float = HINT_LOW_THRESHOLD) -> str:
    """Format an OCR block list as the round-1 hint string.

    **VERBATIM mirror** of `OcrResult.formatHint()` in
    `shared/.../OcrEngine.kt:101-135` — the production code path.
    Keep lock-step: any drift here changes what the model sees in
    round 1 and shifts the eval away from prod by more than just
    OCR backend quality.

    Differences from the prior eval version:
      - Per-line prefix is `line N:` (production) not `  N.` (old eval).
      - Bbox format: `[(x,y),(x,y),(x,y),(x,y)]` (prod) not `[(x, y), ...]`.
      - Trailing summary block: `总共识别 N 行；其中 N 行 [LOW]（<0.5），
        可能是模糊/艺术字/手写。这些行不要直接 verbatim 复制——可以调
        zoom_in (用上面的 bbox) 看细节，或直接放弃（宁可不写也别编）。`
      - Header: `on-device OCR 已扫过整张图` (prod framing) so the
        model doesn't second-guess the OCR backend in eval.
    """
    if not blocks:
        return ""
    sorted_blocks = sorted(
        [b for b in blocks if b.get("text")],
        key=lambda b: -(b.get("confidence") or 0),
    )[:max_lines]
    parts = [
        "【read_text 全图扫描结果】on-device OCR 已扫过整张图，"
        "下面按行给出字符+坐标+置信度（坐标归一化 [0,1]，顺序: 左上→右上→右下→左下）。"
    ]
    for i, b in enumerate(sorted_blocks, 1):
        text = b.get("text", "")
        conf = b.get("confidence")
        bbox = b.get("bbox", []) or []
        conf_str = f"{conf:.2f}" if isinstance(conf, (int, float)) else "?"
        marker = " [LOW]" if isinstance(conf, (int, float)) and conf < low_threshold else ""
        if len(bbox) >= 4:
            rounded = [(round(float(p[0]), 2), round(float(p[1]), 2)) for p in bbox[:4]]
            bbox_str = "[" + ",".join(f"({x},{y})" for x, y in rounded) + "]"
        else:
            bbox_str = "[]"
        parts.append(f"  line {i}: {text!r} | bbox={bbox_str} | conf={conf_str}{marker}")
    low_count = sum(
        1 for b in sorted_blocks
        if isinstance(b.get("confidence"), (int, float)) and b["confidence"] < low_threshold
    )
    parts.append("------------------")
    parts.append(f"总共识别 {len(sorted_blocks)} 行（按可信度排序）")
    if low_count > 0:
        parts.append(
            f"；其中 {low_count} 行 [LOW]（<{low_threshold}），可能是模糊/艺术字/手写。"
        )
        parts.append(
            "这些行不要直接 verbatim 复制——可以调 zoom_in (用上面的 bbox) 看细节，"
            "或直接放弃（宁可不写也别编）。"
        )
    return "\n".join(parts)


def build_round1_ocr_hint(jpeg_bytes: bytes) -> str:
    """Top-level wrapper: try Huawei Cloud OCR, fall back to placeholder.
    Reuses the one-time warning flag from `_ocr_read_text_region` so
    we don't double-log on the same eval run.
    """
    hint, _blocks = build_round1_ocr_hint_with_blocks(jpeg_bytes)
    return hint


def build_round1_ocr_hint_with_blocks(jpeg_bytes: bytes) -> tuple[str, list]:
    """Returns (hint_text, ocr_blocks).  The blocks list mirrors the
    on-device `OcrResult.blocks` (Huawei Cloud's words_block_list
    normalized to {text, confidence, bbox} dicts) so the orchestrator
    can pass them to compare_text for end-side diff.  The hint text is
    what gets injected into the round-1 user message.
    """
    global _OCR_BACKEND_LOGGED
    if not (ocr_hw.sdk_available() and ocr_hw.env_available()):
        if not _OCR_BACKEND_LOGGED:
            print(
                "[ocr] Huawei Cloud OCR not configured (env or SDK missing) "
                "— round-1 hint will be a placeholder."
            )
            _OCR_BACKEND_LOGGED = True
        return "OCR 全图 hint 不可用,请直接读图。", []
    try:
        blocks = ocr_hw.recognize(jpeg_bytes) or []
    except Exception as e:
        return f"OCR 全图扫描失败 ({type(e).__name__}): {str(e)[:120]},请直接读图。", []
    hint = format_hint(blocks)
    return (hint or "OCR 全图 hint 未识别到文字,请直接读图。"), blocks

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
        "description": "局部 OCR 重扫。**仅在以下两种情况调**：\n"
                     "(a) round 1 OCR hint 里有 [LOW] 行（conf<0.5）想用 OCR 再确认一遍;"
                     "调用时直接用 hint 给的 bbox 作为 x/y/w/h。\n"
                     "(b) 你看到文字但 hint 完全没识别到（被遮挡的下半行、菜单极小字）。\n"
                     "**不要**在 hint 已经很清晰的印刷体上重复调 read_text——浪费 round-trip。"
                     "**不要**在书法 / 手写 / 模糊上调——OCR 不可靠。"
                     "参数：x, y, w, h 是归一化坐标 ∈ [0,1]；source 默认 'last'（链式），要看原图不同区用 'original'。",
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
        "name": "compare_text",
        "description": "把**你**从图上读到的字符 vs 第 1 轮 OCR hint 里的字符做端侧 diff（不调云端，省 round-trip）。\n"
                     "**什么时候调**：你看完图后发现 OCR hint 的某些行和你自己读的不一致——OCR 漏字 / 错字 / 编字 / 你对某行不确定。\n"
                     "**参数**：\n"
                     "  - claim: 你从图上**自己读到的**字符串（一段文字，整段或部分）\n"
                     "  - ocr_text: （可选）你想对比的 OCR hint 某行 / 某几行文字。如果不传，默认对**全部** OCR hint 做 diff。\n"
                     "**返回值**：每行的 conflict 标记 + 推荐动作 —\n"
                     "  - agreed: 你俩读的字一致 → trust_ocr（直接 verbatim 用 OCR 字符）\n"
                     "  - ocr_only: OCR 有但你没提到 → zoom_in_required（可能 OCR 错或你漏看）\n"
                     "  - llm_only: 你有但 OCR 没有 → zoom_in_required（可能你幻觉或 OCR 漏）\n"
                     "  - disagree: 你俩都有但内容不一样 → trust_llm（你图上看到的优先）或 zoom_in_required\n"
                     "**用法**：调完一次拿到的 diff 结果决定哪些行要 zoom_in / read_text 重扫 / 直接 drop。",
        "input_schema": {
            "type": "object",
            "properties": {
                "claim": {"type": "string",
                          "description": "你从图上读到的字符串（整段或部分）"},
                "ocr_text": {"type": "string",
                             "description": "（可选）OCR hint 的某行 / 某几行文字。不传则对全部 OCR hint 做 diff。"},
            },
            "required": ["claim"],
            "additionalProperties": False,
        },
    },
    {
        "name": "emit_bubble",
        "description": "当你完全理解了图片内容和用户意图后，调这个工具结束识别循环。\n"
                     "**content 字段关键**：必须把图里所有可见文字 / 数字 / 品牌 / 日期 / 价格 / 联系方式原样写出来"
                     "——能 verbatim 引用 round 1 OCR hint 的字符就直接照抄,不要意译。\n"
                     "intent: 用户想做什么。type: info/location/solve。\n"
                     "details: array of {kind, label, value, bbox?},详情页表格行——把图里读出的关键文字逐行列出。"
                     "**bbox 字段是可选的**:如果该行的字符你从 OCR hint 里 verbatim 复制,可附 4 点归一化坐标"
                     "(顺序左上→右上→右下→左下)供详情页高亮;无 hint 或非 verbatim 时可省略。"
                     "confidence: 0-1。",
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
                            "bbox": {
                                "type": "array",
                                "description": "（可选）4 个角点坐标 [(x1,y1),(x2,y2),(x3,y3),(x4,y4)] 归一化 [0,1]，"
                                                "顺序: 左上→右上→右下→左下。直接 verbatim 复制 OCR hint 给的 bbox，"
                                                "详情页可高亮该行在原图的位置。",
                                "items": {
                                    "type": "array",
                                    "minItems": 2,
                                    "maxItems": 2,
                                    "items": {"type": "number", "minimum": 0, "maximum": 1},
                                },
                                "minItems": 4,
                                "maxItems": 4,
                            },
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
    # ── Verbatim mirror of LlmClient.TOOL_USE_SYSTEM (shared/.../LlmClient.kt:415-479) ──
    # Production system prompt, copied character-for-character so the eval
    # sees the same prompt the production app ships.  Any drift here is
    # the #1 source of "eval says one thing, prod behaves differently"
    # bugs — keep in lock-step with the Kotlin const.
    "你是 IntentCam 的视觉意图助手。你有四个工具：\n"
    "\n"
    "## 关键原则：OCR 是「第一意见」，不是「兜底」\n"
    "第 1 轮你的 user message 已经被注入一份 **【read_text 全图扫描结果】**：on-device OCR（中英离线，HMS ML Kit）扫过整张图，按行返回字符 + 4 点坐标 + 可信度（按 conf 降序，最多 30 行）。"
    "这是你**直接可用**的字符基准——**verbatim 引用到 emit_bubble.content 和 details[]**，不要自己重新组织、意译、概括。\n"
    "\n"
    "## 工具 1: zoom_in —— 看清细节\n"
    "把图里某区域裁出来放大，返回裁剪后的图供你下一轮查看。\n"
    "参数：x, y, w, h 是归一化坐标 ∈ [0, 1]；x/y 是左上角，w/h 是宽高。"
    "source 默认 'last'（链式放大 — 第二次裁第一次的结果，坐标相对）。要看原图不同区域用 source='original'（绝对坐标，兄弟视图）。\n"
    "**用途**：OCR hint 里 [LOW] 行的 bbox，你想自己看清确认；或对**不在 OCR hint 里**的视觉区域（比如招牌图案、产品外观）你看不到的细节。\n"
    "\n"
    "## 工具 2: read_text —— 局部 OCR 重扫\n"
    "对图里某区域重新跑 on-device OCR（**仅在以下场景调**）：\n"
    "  1. **OCR hint 里 [LOW] 的行**（conf<0.5）你想验证，调用前直接用 OCR hint 给的 bbox 作为 x/y/w/h。\n"
    "  2. **OCR hint 没识别到的区域**，但你在图上看到有文字（菜单上小字、被遮挡的下半行），用 bbox 重扫。\n"
    "参数：x, y, w, h, source（和 zoom_in 一样）。\n"
    "**不要**在 OCR hint 已经很清晰的印刷体上重复调 read_text——浪费 round-trip。\n"
    "**不要**在书法 / 手写 / 模糊图上调 read_text——OCR 不可靠，会喂噪声进你的答案。\n"
    "\n"
    "## 工具 3: compare_text —— 端云 diff\n"
    "纯端侧 diff：**你**读的字符 vs OCR hint 给的字符。结果告诉哪些行「同意 / OCR-only / 你-only / 冲突」。\n"
    "**调用场景**：当你读完图后发现 OCR hint 的某些行和你自己读的不一致（比如 OCR 漏字 / 编字 / 错字），调一次 compare_text(claim=你读的字符) 让端侧告诉你差异。\n"
    "**好处**：纯 Kotlin 字符串 diff，不调云端，省 round-trip。\n"
    "\n"
    "## 工具 4: emit_bubble —— 收尾\n"
    "看清楚内容 + 理解意图后，调 emit_bubble(content, intent, type, intent_focus?, confidence, details?) 总结。\n"
    "type ∈ {info, location, solve}。\n"
    "\n"
    "## 工作流程\n"
    "1. 读 user message 里的 OCR 全图扫描结果——这是文字基准，直接 verbatim 引用。\n"
    "2. 看图，确认场景 / 结构 / 布局（OCR 不会告诉你图里**非文字**的东西）。\n"
    "3. **冲突检查**：OCR hint 里的字和你图上看到的字对得上吗？"
    "对不上 → 调 compare_text 让端侧告诉你差异，对 [LOW] / 冲突行 → 调 zoom_in 用 bbox 看细节 → 如果是高保真印刷体可考虑 read_text 重扫。\n"
    "4. 思考用户为什么拍这张图（意图）。\n"
    "5. 调 emit_bubble：content 写原样 OCR 字符（不要意译、不要重写），details[] 填每一行 OCR 高亮（带 bbox 字段供详情页高亮）。\n"
    "\n"
    "## content 字段要求（**最严格**）\n"
    "content 必须包含图里**所有可见**文字 / 数字 / 品牌 / 日期 / 价格 / 联系方式，**原样**写出来（直接 verbatim 复制 OCR hint 的字符）：\n"
    "  - 茶叶包装 → content 写\"包装文字：'品名: 工夫红茶', '净含量: 250g', '生产日期: 2020-12-01'\"\n"
    "  - 路牌 → \"建国路 100号\"\n"
    "  - 收据 → \"合计 ¥168.50, 微信支付\"\n"
    "  - 菜单 → \"宫保鸡丁 ¥38, 鱼香肉丝 ¥42\"\n"
    "  - 门牌 → \"1203\"\n"
    "\n"
    "## details 字段要求（**和 content 同等重要**）\n"
    "**图里每一处独立的文字 / 数字 / 品牌 / 日期 / 价格，都要在 details 里对应一行**，"
    "value 写**逐字原文**（直接 verbatim 复制 OCR hint 的字符，不要意译、不要概括），"
    "**bbox 字段填 OCR hint 给的 4 点坐标**（让详情页能高亮该行在原图的位置）：\n"
    "  - {kind:'brand', label:'品名', value:'工夫红茶', bbox:[(0.10,0.20),(0.30,0.20),(0.30,0.25),(0.10,0.25)]}\n"
    "  - {kind:'number', label:'净含量', value:'250g', bbox:[(0.10,0.26),(0.30,0.26),(0.30,0.31),(0.10,0.31)]}\n"
    "  - {kind:'price', label:'合计', value:'¥168.50', bbox:[(0.40,0.50),(0.60,0.50),(0.60,0.55),(0.40,0.55)]}\n"
    "**OCR hint 没识别到 / [LOW] 的行**别写进 details（宁可不写也别编），可以放在 content 里写 \"其它文字无法辨认\"。\n"
    "**能看清多少文字就写多少行**——但**有上限**：场景上文字 > 8 处时，"
    "按重要性把 details 裁到 **最值得高亮的 5-8 行**（品牌、价格、日期、地址、电话、关键警示），"
    "其余的合并到 content 里。这能避免 answer 过长被 token 截断 / round-trip 撞超时。\n"
    "\n"
    "## 反幻觉（**关键**）\n"
    "**看不清的字宁可不写也别瞎猜**。content 漏一个字符比写错一个好——用户会按你写的内容去做事，错字比漏字危险得多。"
    "对不确定的字可以写 '?' 占位（比如'??路 100号'），但**绝不要发明文字**。\n"
    "**OCR hint [LOW] 行不要 verbatim 复制**——这是 OCR 自己都不确定的行，复制等于把噪声喂给用户；要么 zoom_in 确认后再写，要么直接 drop。\n"
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


# ── compare_text impl (mirrors ToolImplementations.compareClaimAgainstBlocks) ──

COMPARE_LOW_CONFIDENCE_THRESHOLD = 0.5


def _compare_normalize(s: str) -> str:
    """Light normalize for substring matching in compare_text.  Intentionally
    NOT unicode-folded — we want to catch verbatim differences, not paper
    over them.  Mirrors Kotlin's `normalizeForMatch`."""
    import re
    return re.sub(r"\s+", " ", s.strip().lower())


def _compare_similarity(a: str, b: str) -> float:
    """1 - (levenshtein / max(len)).  Iterative DP; fine for the short
    strings compare_text deals with.  Mirrors Kotlin `similarity()`."""
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    if abs(len(a) - len(b)) > 200:  # cheap short-circuit
        return 0.0
    prev = list(range(len(b) + 1))
    curr = [0] * (len(b) + 1)
    for i in range(1, len(a) + 1):
        curr[0] = i
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            curr[j] = min(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        prev, curr = curr, prev
    dist = prev[len(b)]
    return 1.0 - dist / max(len(a), len(b))


def compare_text_impl(claim: str, ocr_text: str, ocr_blocks: list) -> str:
    """Mirror Kotlin `ToolImplementations.compareClaimAgainstBlocks`.
    Pure string-diff (no LLM round-trip).

    @param claim: the model's own reading of the image text.
    @param ocr_text: optional substring filter against OCR blocks
        (substring match on the OCR block's text).  Empty = all blocks.
    @param ocr_blocks: list of {text, confidence, bbox} dicts from
        the round-1 OCR pass (huaweicloud output).
    @return: a multi-line tool summary, formatted to mirror Kotlin.
    """
    if not claim.strip():
        return "compare_text: claim 为空，没有可对比的内容"
    if not ocr_blocks:
        return (
            f"compare_text: OCR cache 为空（OCR 后端未安装或图上无文字）。"
            f" 你读到的 claim 是：'{claim}'。"
        )
    selected = ocr_blocks
    if ocr_text.strip():
        needle = ocr_text.strip().lower()
        matched = [b for b in ocr_blocks if needle in (b.get("text", "")).lower()]
        if matched:
            selected = matched
    claim_norm = _compare_normalize(claim)
    if not claim_norm or not selected:
        return "compare_text diff: 没有可对比的内容"
    rows = []
    remaining = claim_norm
    for block in selected:
        b_norm = _compare_normalize(block.get("text", ""))
        if not b_norm:
            continue
        conf = block.get("confidence") or 0.0
        low = conf < COMPARE_LOW_CONFIDENCE_THRESHOLD
        if b_norm in remaining:
            rows.append(("agreed", block.get("text", ""), conf, low, "trust_ocr"))
            remaining = remaining.replace(b_norm, " ")
        elif _compare_similarity(b_norm, remaining) >= 0.5:
            rec = "zoom_in_required" if not low else "trust_llm"
            rows.append(("disagree", block.get("text", ""), conf, low, rec))
        else:
            rec = "zoom_in_required" if not low else "trust_llm"
            rows.append(("ocr_only", block.get("text", ""), conf, low, rec))
    remaining = " ".join(remaining.split())
    if remaining:
        rows.append(("llm_only", remaining, None, False, "zoom_in_required"))
    if not rows:
        return "compare_text diff: 没有可对比的内容"
    parts = ["compare_text diff:"]
    for conflict, text, conf, low, rec in rows:
        suffix = ""
        if conf is not None:
            suffix = f" | conf={conf:.2f}"
            if low:
                suffix += " [LOW]"
        parts.append(f"  • [{conflict}] {text}{suffix} → {rec}")
    summary = (
        f"agreed={sum(1 for r in rows if r[0] == 'agreed')} "
        f"ocr_only={sum(1 for r in rows if r[0] == 'ocr_only')} "
        f"llm_only={sum(1 for r in rows if r[0] == 'llm_only')} "
        f"disagree={sum(1 for r in rows if r[0] == 'disagree')}"
    )
    parts.append(f"summary: {summary}")
    return "\n".join(parts)


def make_user_msg(image_path: Path, ocr_hint: str = "") -> dict:
    """Build the round-1 user message: 1 thumbnail image + OCR hint
    text + a short text prompt.  Mirrors FrameAnalyzer.bufferToFrame so
    the eval sees what the app actually produces.  Optionally simulates
    the FrameAnalyzer's resize+re-encode (when --resize > 0).

    The 4 quadrant crops used in the prior eval were dropped because
    the endcloud path uses 1 image + OCR hint (text + bbox rows) to
    cover all four regions.  The LLM picks regions via zoom_in(source=
    'original') or read_text(hint-bbox) instead of relying on pre-cut
    quadrants.
    """
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
            {"type": "text", "text": ocr_hint},
            # NOTE: production (LlmClient.userImageMessage) sends no
            # trailing "调用工具" text in round 1 — the system prompt
            # already steers the model to call a tool.  Adding the
            # trailing text here was a small drift that pushed more
            # models into "text-only no tool_use" → r2 collapses to 0.
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
    """Round-1 scoring.  Mirrors `EvalRunner.scoreRound1()` in
    `shared/.../eval/EvalRunner.kt` exactly so eval and prod are on
    the same metric:

      - emit_bubble first / no tool_use → 0.85 if fixture has text
        expected (skipReconScore — small penalty for skipping
        reconnaissance on a text-heavy scene), 1.0 otherwise.
      - zoom_in / read_text / compare_text → 1.0  (valid recon pick).
      - unknown tool → 0.7  (legacy fallback; shouldn't fire now).
      - composite: 0.7 * pick + 0.3 * input_ok.
    """
    tool_uses = [b for b in (response.get("content") or []) if b.get("type") == "tool_use"]
    first_tool = tool_uses[0].get("name") if tool_uses else None
    has_text = bool(fixture.get("expected_description_keywords")) or bool(fixture.get("expected_details"))
    skip_recon = 0.85 if has_text else 1.0

    if first_tool == "emit_bubble":
        pick_score = skip_recon
        picked = "emit_bubble"
    elif first_tool == "zoom_in" or first_tool == "read_text" or first_tool == "compare_text":
        pick_score = 1.0
        picked = first_tool
    elif first_tool is None:
        pick_score = skip_recon
        picked = None
    else:
        pick_score = 0.7
        picked = first_tool
    input_ok = 1.0
    composite = 0.70 * pick_score + 0.30 * input_ok
    return composite, {
        "picked_tool": picked,
        "has_text": has_text,
        "tool_pick_ok": pick_score == 1.0,
        "input_ok": input_ok == 1.0,
        "skip_recon_score": skip_recon,
    }


def _normalize(s: str) -> str:
    """Score-scoring normalizer.  Mirrors `EvalRunner.normalize()` in
    the Kotlin 1-only benchmark.  Closes the gap between strict
    substring match and what a human would call "same text" by
    collapsing the noisiest Unicode variants:
      - NFKC folds fullwidth / compatibility forms (「（店）」 ↔ "(店)")
      - quote / colon variants → ASCII
      - all whitespace → single ASCII space
    Does NOT do synonyms, simplification, or traditional/simplified
    conversion — those would change what "correct" means.
    """
    if not s:
        return ""
    import re
    import unicodedata
    n = unicodedata.normalize("NFKC", s)
    n = n.replace("‘", "'").replace("’", "'")
    n = n.replace("“", "'").replace("”", "'")
    n = n.replace("「", "'").replace("」", "'")
    n = n.replace("『", "'").replace("』", "'")
    n = n.replace("：", ":")  # fullwidth colon
    n = re.sub(r"\s+", " ", n)
    return n.strip().lower()


def _fuzzy_match(haystack: str, needle: str) -> bool:
    """Bidirectional normalized contains.  Mirrors `EvalRunner.fuzzyMatch()`.
    Returns True iff `needle` (post-normalize) appears in `hay` OR
    `hay` appears in `needle` — comparing both with and without
    internal whitespace.  The no-whitespace pass catches
    "建国路 100号" vs "建国路100号" which the plain pass misses.
    The reverse direction matters when the model produces a value
    longer than the GT (e.g. model writes "品名: 工夫红茶 250g" and
    GT expects "工夫红茶 250g").  Guarded by length to avoid a
    single-char needle matching every long answer.
    """
    if not needle:
        return True
    if not haystack:
        return False
    n = _normalize(needle)
    h = _normalize(haystack)
    if n in h:
        return True
    if h in n and len(n) >= 2:
        return True
    nNoWs = n.replace(" ", "")
    hNoWs = h.replace(" ", "")
    if not nNoWs or not hNoWs:
        return False
    if nNoWs in hNoWs:
        return True
    if hNoWs in nNoWs and len(nNoWs) >= 2:
        return True
    return False


# Character-overlap threshold for the secondary fuzzy fallback.  Mirrors
# Kotlin's `scoreRound2TextFuzzy` (≥0.67 = hit).  Catches the case
# where a GT keyword like "建国路100号" is split into "建国路 100 号"
# in the model's output — pure substring match misses, but 100% of the
# characters still appear.
_CHAR_OVERLAP_THRESHOLD = 0.67


def _char_overlap_ratio(haystack: str, needle: str) -> float:
    """Fraction of needle's unique characters (post-NFKC, no-whitespace)
    that appear anywhere in haystack.  Used as the secondary fuzzy
    fallback when `_fuzzy_match` substring contains misses.

    Returns a float in [0, 1].  Single-char needles return 0 or 1
    (no partial credit on trivial matches)."""
    h = _normalize(haystack).replace(" ", "")
    n = _normalize(needle).replace(" ", "")
    if not n:
        return 0.0
    n_chars = set(n)
    if not n_chars:
        return 0.0
    if len(n_chars) == 1:
        return 1.0 if n_chars.pop() in h else 0.0
    present = sum(1 for c in n_chars if c in h)
    return present / len(n_chars)


def _hybrid_match(haystack: str, needle: str) -> float:
    """Two-stage match: `_fuzzy_match` first (1.0 on hit), then
    char-overlap fallback (1.0 if ratio >= _CHAR_OVERLAP_THRESHOLD,
    else 0.0).  Binary result — partial credit would over-credit
    noise (e.g. "A" overlap on a 50-char needle).

    Mirrors Kotlin's combined `scoreRound2Text` + `scoreRound2TextFuzzy`
    scorer path on the >=0.67 char-overlap branch only (not the
    continuous ratio path, which is kept as the diagnostic-only
    `score_text_fuzzy`)."""
    if _fuzzy_match(haystack, needle):
        return 1.0
    return 1.0 if _char_overlap_ratio(haystack, needle) >= _CHAR_OVERLAP_THRESHOLD else 0.0


def score_text(text: str, fixture: dict, details: list = None) -> float:
    """Score the LLM's text output against the fixture's
    expected_description_keywords AND expected_details.

    Mirrors Kotlin `EvalRunner.scoreRound2()`:
      - keyword hit rate: fraction of expected_description_keywords
        two-stage matched (NFKC + bidirectional contains OR
        char-overlap ≥ 0.67).  Mirrors Kotlin's combined
        `scoreRound2Text` + `scoreRound2TextFuzzy` scorer path on
        the ≥0.67 branch.
      - detail hit rate: fraction of expected_details where the LLM
        emitted a detail row whose VALUE two-stage matches.  Kind/label
        are deliberately NOT used (the GT uses positional labels
        like "区域1" while the model writes semantic ones like
        "品牌" / "价格"; matching on either would zero hits that
        are textually correct).

    The two components are averaged.  If either rubric is empty, that
    component defaults to 1.0.  If both are empty, the fixture is
    unscored → returns 1.0.
    """
    if details is None:
        details = []
    score_components = []

    # Component 1: keyword hit rate (two-stage fuzzy over the full content text)
    expected_kws = fixture.get("expected_description_keywords", [])
    if expected_kws:
        hits = sum(1 for kw in expected_kws if _hybrid_match(text, kw))
        score_components.append(hits / len(expected_kws))

    # Component 2: detail hit rate (value-only two-stage match)
    expected_details = fixture.get("expected_details", [])
    if expected_details:
        llm_values = [
            d.get("value", "")
            for d in details
            if isinstance(d, dict) and d.get("value")
        ]
        hits = 0
        for exp in expected_details:
            e_value = exp.get("value", "")
            if not e_value:
                continue
            if any(_hybrid_match(lv, e_value) for lv in llm_values):
                hits += 1
        score_components.append(hits / len(expected_details))

    if not score_components:
        return 1.0
    return sum(score_components) / len(score_components)


def score_text_fuzzy(text: str, fixture: dict, details: list = None) -> float:
    """Diagnostic-only fuzzy r2 text score.  Mirrors Kotlin's
    `scoreRound2TextFuzzy()`: for each expected keyword that doesn't
    substring-match, fall back to a character-overlap ratio
    (≥0.67 = hit).  This is NOT part of the composite — it tells
    us how much of the strict→fuzzy gap is scorer brittleness vs
    the model genuinely misreading.  Used for reporting only.
    """
    if details is None:
        details = []
    expected_kws = fixture.get("expected_description_keywords", [])
    if not expected_kws:
        return 1.0
    hay = text
    for d in details:
        if isinstance(d, dict) and d.get("value"):
            hay += " " + d["value"]
    hay_norm = _normalize(hay)
    hits = 0.0
    for kw in expected_kws:
        kw_norm = _normalize(kw)
        if not kw_norm:
            continue
        if kw_norm in hay_norm:
            hits += 1.0
            continue
        # Char-overlap fallback.
        kw_chars = set(kw_norm.replace(" ", ""))
        if not kw_chars:
            continue
        present = sum(1 for c in kw_chars if c in hay_norm.replace(" ", ""))
        ratio = present / len(kw_chars)
        if ratio >= 0.67:
            hits += ratio
    return hits / len(expected_kws)


def score_emit_type(emit_type: str | None, fixture: dict) -> float:
    """Three-way partial credit for emit_bubble.type.  Mirrors Kotlin
    `EvalRunner.scoreRound2()`:
      - right bucket    → 1.0
      - valid type, wrong bucket → 0.5  (model picked a real
        {info, location, solve} but fixture GT is hardcoded "info" —
        common on signs/storefronts where the user could be
        "reading" or "finding this place")
      - empty / unknown → 0.0
    """
    expected = fixture.get("expected_type") or fixture.get("expected_top_intent_type", [])
    if not expected:
        return 1.0  # no rubric
    if not emit_type:
        return 0.0
    expected_list = expected if isinstance(expected, list) else [expected]
    if emit_type in expected_list:
        return 1.0
    if emit_type in ("info", "location", "solve"):
        return 0.5
    return 0.0


def run_follow_up(user_msg: dict, response1: dict) -> dict:
    """Legacy single-round stub.  Use run_orchestrator for the
    full multi-round flow that handles zoom_in and multi-zoom."""
    tool_uses = [b for b in (response1.get("content") or []) if b.get("type") == "tool_use"]
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
                      ocr_blocks=None, max_rounds=30):
    """Multi-round orchestrator.  Mirrors `ToolUseLoop.runCycle()` so the
    eval exercises the same flow as production:

      - max_rounds = 30 (prod's MAX_ROUNDS).  3 was an eval-only hard cap
        that artificially forced emit_bubble; production lets the model
        converge naturally.
      - 4 tools: zoom_in, read_text, compare_text, emit_bubble.
        compare_text uses round-1 OCR cache (huaweicloud) instead of
        production's HMS — pure string diff, no LLM round-trip.
      - End-of-round nudge text matches `ToolUseLoop.kt:662-667`.
      - On any call_model error mid-cycle, falls back to a synthesized
        Bubble from lastRound.text (mirrors ToolUseLoop.kt:459-490).

    zoom_in chain semantics: source='last' crops the previous round's
    followUpJpeg; source='original' crops the raw fullRes.  Only the
    first zoom_in per round emits a follow-up image; additional ones
    get a "duplicate skipped" note.
    """
    ocr_blocks = ocr_blocks or []
    messages = [
        user_msg,
        {"role": "assistant", "content": response1.get("content", [])},
    ]
    all_follow_ups = []
    current_image = original_jpeg
    final_resp = response1
    last_text_content = ""
    tool_inventory = {"zoom_in": 0, "read_text": 0, "compare_text": 0,
                      "emit_bubble_emitted": False, "rounds_run": 0}
    for b in response1.get("content", []):
        if b.get("type") != "tool_use":
            continue
        nm = b.get("name")
        if nm == "zoom_in":
            tool_inventory["zoom_in"] += 1
        elif nm == "read_text":
            tool_inventory["read_text"] += 1
        elif nm == "compare_text":
            tool_inventory["compare_text"] += 1
        elif nm == "emit_bubble":
            tool_inventory["emit_bubble_emitted"] = True
    for round_i in range(1, max_rounds + 1):
        tool_inventory["rounds_run"] = round_i
        # Defensive: some LLM responses serialize `content: null` (esp.
        # when streaming cuts off).  Treat as empty so the loop falls
        # through to the "no tool_uses" branch instead of crashing.
        _content = final_resp.get("content") or []
        tool_uses = [b for b in _content if b.get("type") == "tool_use"]
        round_text = " ".join(
            b.get("text", "") for b in (final_resp.get("content") or [])
            if b.get("type") == "text" and b.get("text", "").strip()
        )
        if round_text:
            last_text_content = round_text
        if any(b.get("name") == "emit_bubble" for b in tool_uses):
            tool_inventory["emit_bubble_emitted"] = True
            break
        is_first_round = round_i == 1
        if not tool_uses:
            if is_first_round:
                messages.append({"role": "user", "content": [
                    {"type": "text", "text": "请先调 zoom_in(x, y, w, h, focus='...') 放大看 1-2 个区域（缺省模式），看清后再调 emit_bubble 总结。"}
                ]})
                final_resp = call_model(messages, max_tokens=800)
                continue
            if not final_resp.get("content"):
                prev = messages[-1] if messages else None
                if isinstance(prev, dict) and prev.get("role") == "assistant":
                    final_resp = prev
            break
        zoom_in_call = next((b for b in tool_uses if b["name"] == "zoom_in"), None)
        tool_results = []
        round_follow_ups = []
        for block in tool_uses:
            inp = block.get("input", {}) or {}
            name = block.get("name")
            summary = "已处理"
            if name == "zoom_in":
                tool_inventory["zoom_in"] += 1
            elif name == "read_text":
                tool_inventory["read_text"] += 1
            elif name == "compare_text":
                tool_inventory["compare_text"] += 1
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
                # OCR: replace on-device ML Kit with Huawei Cloud OCR
                # so the eval can run without an Android device.
                # Falls back to the legacy stub when the SDK/env is
                # missing (one-time warning logged at module level).
                src = inp.get("source", "last")
                src_img = original_jpeg if src == "original" else current_image
                rx = float(inp.get("x", 0))
                ry = float(inp.get("y", 0))
                rw = float(inp.get("w", 0.2))
                rh = float(inp.get("h", 0.2))
                try:
                    crop = crop_region(src_img, rx, ry, rw, rh)
                except Exception:
                    crop = b""
                if crop:
                    round_follow_ups.append(crop)
                    summary = _ocr_read_text_region(crop, 0.0, 0.0, 1.0, 1.0)
                else:
                    summary = "read_text 失败：区域无效"
            elif name == "compare_text":
                # Pure on-device diff against round-1 OCR cache.
                # Mirrors ToolImplementations.compare_text body.
                claim = (inp.get("claim") or "").strip()
                ocr_text = (inp.get("ocr_text") or "").strip()
                summary = compare_text_impl(claim, ocr_text, ocr_blocks)
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
            hint = ("已放大你刚才要求的区域（更高分辨率），请用这张图继续回答。"
                    if len(round_follow_ups) == 1
                    else f"放大区域 #{i+1}/{len(round_follow_ups)}，请用这些图继续回答。")
            next_content.append({"type": "text", "text": hint})
        for tr in tool_results:
            next_content.append(tr)
        # End-of-round nudge.  Mirrors ToolUseLoop.kt:662-667 wording.
        zoom_count_this_round = sum(1 for b in tool_uses if b.get("name") == "zoom_in")
        if zoom_count_this_round > 0:
            next_content.append({
                "type": "text",
                "text": (
                    f"你已经 zoom_in {zoom_count_this_round} 次。如果还有看不清的，可以再 zoom_in；"
                    "如果内容已经清楚，**必须**调 emit_bubble 总结 (content / intent / type / confidence)。"
                ),
            })
        else:
            next_content.append({
                "type": "text",
                "text": "请调用 emit_bubble 给出最终意图摘要（content/intent/type/confidence/details），不要再调其它工具。",
            })
        messages.append({"role": "user", "content": next_content})
        all_follow_ups.extend(round_follow_ups)
        # Production-shaped call: on error, fall back to a synthesized
        # Bubble from the last successful round's text instead of
        # dropping the whole fixture to a 0 composite.
        try:
            final_resp = call_model(messages, max_tokens=800)
        except Exception as e:
            tool_inventory.setdefault("errors", 0)
            tool_inventory["errors"] += 1
            partial = last_text_content
            if partial:
                final_resp = {
                    "_error": f"{type(e).__name__}: {str(e)[:120]}",
                    "_last_free_text": partial,
                    "content": [
                        {"type": "text", "text": partial},
                        {"type": "tool_use", "id": f"fallback-{round_i}",
                         "name": "emit_bubble",
                         "input": {
                             "content": partial[:200],
                             "intent": partial[:40] or "未识别",
                             "type": "info",
                             "confidence": 0.4,
                             "details": [],
                         }},
                    ],
                }
                tool_inventory["emit_bubble_emitted"] = True
                break
            final_resp = {"_error": f"{type(e).__name__}: {str(e)[:120]}", "content": []}
            break
    if last_text_content and final_resp is not None:
        final_resp["_last_free_text"] = last_text_content

    # MAX_ROUNDS兜底:如果模型在所有 round 都没调 emit_bubble,合成一个
    # 兜底 Bubble 跟生产 ToolUseLoop.kt:705-722 一致 — 从 lastRound
    # 的 text 抽 title + detail, type=info, confidence=0.5。否则
    # 0.5 * 0 + 0.5 * 0 = 0 把 r2 直接打零,Kotlin 不会这样因为
    # 那边有这个兜底。
    #
    # P1 (2026-07-10):触发条件放宽 — 只要 orchestrator 跑过(任意轮)
    # 且没 emit_bubble 就兜底。原因:zoom-only 模型(典型 rctw_default_75
    # / _79)只调 zoom_in 不写自由文本,之前要求 last_text_content
    # 非空导致兜底不触发,r2 直接锁死 0;另外要求 round_i+1 >= max_rounds
    # 也漏掉了 orchestrator 第 2-3 轮就 break 的多数情况(rounds_run < max)。
    # 无文本兜底时 content=intent=「未识别」+ confidence=0.3。
    if not tool_inventory.get("emit_bubble_emitted") and tool_inventory.get("rounds_run", 0) > 0:
        if last_text_content:
            partial = last_text_content.strip()
            content = partial[:200]
            intent = partial[:40]
            confidence = 0.5
        else:
            # zoom-only 跑满 N 轮,模型一句文本也没写 — 兜底还是要发
            content = "（模型未给出内容描述）"
            intent = "未识别"
            confidence = 0.3
        final_resp = {
            "content": [
                {"type": "text", "text": content},
                {"type": "tool_use", "id": f"maxrounds-fallback-rounds{tool_inventory['rounds_run']}",
                 "name": "emit_bubble",
                 "input": {
                     "content": content,
                     "intent": intent,
                     "type": "info",
                     "confidence": confidence,
                     "details": [],
                 }},
            ],
        }
        tool_inventory["emit_bubble_emitted"] = True
    return final_resp, all_follow_ups, tool_inventory


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
    parser.add_argument("--out", default=None,
                        help="Optional path to dump per-fixture results as JSON.")
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
        # Read the original (full-res) JPEG first — this is what we OCR
        # for the round-1 hint AND what zoom_in crops from.  Mirrors the
        # prod flow: prod OCRs fullRes (downscale to 1920px for HMS),
        # not the 768px thumbnail.  Eval substitutes Huawei Cloud OCR,
        # but the input resolution should match prod so [LOW] ratio
        # reflects the production ceiling.
        original_jpeg = img_path.read_bytes()
        round1_hint, ocr_blocks_for_cycle = build_round1_ocr_hint_with_blocks(original_jpeg)
        # Count hint blocks + [LOW] rows for telemetry.  When the hint
        # is a placeholder (OCR backend unavailable) the counts are 0.
        _hint_block_count = round1_hint.count("\n  ")  # numbered rows
        _hint_low_count = round1_hint.count(" [LOW]")
        user_msg = make_user_msg(img_path, round1_hint)
        r1 = call_model([user_msg])
        # If the first round-trip itself failed, log and skip the
        # rest of the orchestrator — the fixture scores 0 across
        # the board but the eval keeps going.
        if "_error" in r1:
            print(f"  [SKIP {scene_id}] r1 error: {r1['_error']} | {r1.get('_body','')[:120]}")
            all_results.append({
                "id": scene_id, "category": category,
                "composite": 0.0, "r1": 0.0, "r2_text": 0.0, "r2_text_fuzzy": 0.0, "r2_type": 0.0,
                "picked_tool_r1": None,
                "read_text_calls": 0, "zoom_in_calls": 0, "compare_text_calls": 0,
                "emit_bubble_emitted": False,
                "r1_hint_blocks": _hint_block_count,
                "r1_hint_low_count": _hint_low_count,
            })
            continue
        r1_score, r1_detail = score_round1(r1, fixture)

        r2_text = ""
        r2_emit_scene = ""
        r2_emit_type = None
        r2_emit_chips: list = []
        r2_emit_details: list = []
        r2_text_score = 0.0
        r2_text_fuzzy = 0.0
        r2_type_score = 1.0  # default if no rubric
        final_resp = r1  # default: use r1 as final if no orchestrator path runs
        tool_inventory = {
            "zoom_in": 0, "read_text": 0, "compare_text": 0,
            "emit_bubble_emitted": False, "rounds_run": 0, "errors": 0,
        }
        r1_text = " ".join(
            b.get("text", "") for b in (r1.get("content") or [])
            if b.get("type") == "text"
        )
        r2_text = r1_text  # for cases where r1 already produced text
        tool_uses_r1 = [b for b in (r1.get("content") or []) if b.get("type") == "tool_use"]
        # P2 (2026-07-10):round-1 纯文本自动包 emit_bubble — 模型在
        # 第一轮就直接写答案没调任何工具(典型 rctw_default_11/13/18/21/57/89,
        # 6/100 fixture)的时候,跳过 orchestrator,直接把文本合成为
        # emit_bubble.content,details=[],type=info,confidence=0.7。
        # 避免 r1 被压到 0.895 (skipReconScore) — 模型答对了应该给 1.0。
        if not tool_uses_r1 and len(r1_text.strip()) >= 20:
            wrapped_bubble = {
                "content": [
                    {"type": "text", "text": r1_text},
                    {"type": "tool_use", "id": "r1-autowrap",
                     "name": "emit_bubble",
                     "input": {
                         "content": r1_text[:500],
                         "intent": r1_text[:60],
                         "type": "info",
                         "confidence": 0.7,
                         "details": [],
                     }},
                ],
            }
            final_resp = wrapped_bubble
            tool_inventory = {
                "zoom_in": 0, "read_text": 0, "compare_text": 0,
                "emit_bubble_emitted": True, "rounds_run": 1, "errors": 0,
            }
        elif tool_uses_r1:
            # Run the multi-round orchestrator (mirror prod's
            # ToolUseLoop.runCycle — 30-round cap, compare_text
            # handler, fallback Bubble on timeout).
            final_resp, _, tool_inventory = run_orchestrator(
                user_msg=user_msg,
                response1=r1,
                original_jpeg=original_jpeg,
                thumbnail_jpeg=original_jpeg,
                ocr_blocks=ocr_blocks_for_cycle,
            )
            r2_text = " ".join(
                b.get("text", "")
                for b in (final_resp.get("content") or [])
                if b.get("type") == "text"
            )
            r2_emit_blocks = [
                b for b in (final_resp.get("content") or [])
                if b.get("type") == "tool_use" and b.get("name") == "emit_bubble"
            ]
            if r2_emit_blocks:
                emit_input = r2_emit_blocks[0].get("input", {}) or {}
                # New schema: emit_bubble's `content` field carries
                # the content description (was `scene` in the old
                # schema); `details` carries the extracted table rows.
                # Defensive coerce: occasionally the LLM emits
                # `content: {nested}` instead of `content: "string"`,
                # which would break the `+ " "` concat below.
                def _coerce_str(v, default=""):
                    if isinstance(v, str):
                        return v
                    if v is None:
                        return default
                    try:
                        return json.dumps(v, ensure_ascii=False)
                    except Exception:
                        return str(v)
                r2_emit_scene = (
                    _coerce_str(emit_input.get("content"))
                    or _coerce_str(emit_input.get("scene"))
                    or _coerce_str(emit_input.get("intent"))
                )
                r2_emit_type_v = emit_input.get("type")
                r2_emit_type = r2_emit_type_v if isinstance(r2_emit_type_v, str) else None
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
            r2_text_fuzzy = score_text_fuzzy(combined, fixture, r2_emit_details)
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
            "r2_text_fuzzy": r2_text_fuzzy,
            "r2_type": r2_type_score,
            "picked_tool_r1": r1_detail.get("picked_tool"),
            "read_text_calls": tool_inventory.get("read_text", 0),
            "zoom_in_calls": tool_inventory.get("zoom_in", 0),
            "compare_text_calls": tool_inventory.get("compare_text", 0),
            "emit_bubble_emitted": tool_inventory.get("emit_bubble_emitted", False),
            "rounds_run": tool_inventory.get("rounds_run", 0),
            "errors": tool_inventory.get("errors", 0),
            "r1_hint_blocks": _hint_block_count,
            "r1_hint_low_count": _hint_low_count,
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
    if args.out:
        Path(args.out).write_text(json.dumps({
            "fixtures": all_results,
            "overall": overall,
            "per_category": {k: sum(v)/len(v) for k, v in per_category.items()},
            "args": {"resize": RESIZE_MAX_DIM, "quality": RESIZE_QUALITY, "limit": args.limit},
        }, ensure_ascii=False, indent=2))
        print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
