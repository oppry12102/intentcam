"""
One-shot smoke test: does MiniMax-M3 actually support Anthropic's
`tools[]` parameter?

Sends IMG1.jpg + a small toolset (default_describe + identify_animal_or_plant
+ identify_product) and prints the raw response.  If the model emits a
`tool_use` content block with valid JSON `input`, the tool-use protocol
works as designed.  If the model ignores `tools[]` and just produces plain
text, we know we need to fall back to prompt-embedded JSON instead.

Usage:
    ANTHROPIC_AUTH_TOKEN=... python3 profiling/test_tooluse.py
"""

from __future__ import annotations

import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "https://api.minimaxi.com/anthropic")
MODEL = os.environ.get("ANTHROPIC_MODEL", "MiniMax-M3")
MAX_TOKENS = 320


TOOLS = [
    {
        "name": "default_describe",
        "description": "无法确定用户意图时回退到这。它会用三轮 CoT 描述画面并给出 1-4 个候选意图。",
        "input_schema": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    },
    {
        "name": "identify_animal_or_plant",
        "description": "画面里是一只动物或一株植物（宠物、花卉、树木、昆虫），想识别种类、了解习性或养护方法时选这个。",
        "input_schema": {
            "type": "object",
            "properties": {
                "species_hint": {"type": "string", "description": "用户可能想知道的子问题，如品种/毒性/养护频率/花期"},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "identify_product",
        "description": "画面是商品/食品/包装/瓶身/标签，想看配料、保质期、价格、购买链接或对比同类时选这个。",
        "input_schema": {
            "type": "object",
            "properties": {
                "focus": {
                    "type": "string",
                    "enum": ["ingredients", "expiry", "price", "link", "compare"],
                    "description": "用户最关心的子问题",
                },
            },
            "additionalProperties": False,
        },
    },
]


SYSTEM = (
    "你是 IntentCam 的工具调用助手。看到画面后，你必须调用一个工具来处理它。"
    "不要直接用文字描述画面内容（那是 default_describe 的工作）。"
    "如果拿不准选哪个工具，就调 default_describe 让旧逻辑接管。"
    "回复必须是中文。纯文本和 tool_use 可同回合出现，但第一回合必须调用工具。"
)


def call_model(messages: list, system: str, max_tokens: int = MAX_TOKENS) -> dict:
    body = {
        "model": MODEL,
        "max_tokens": max_tokens,
        "temperature": 0.0,
        "system": system,
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


def summarize(label: str, resp: dict) -> None:
    print(f"\n=== {label} ===")
    if "_error" in resp:
        print(f"ERROR: {resp['_error']}")
        print(resp.get("_body", ""))
        return
    print(f"stop_reason: {resp.get('stop_reason')}")
    print(f"usage: {resp.get('usage')}")
    for i, block in enumerate(resp.get("content", [])):
        btype = block.get("type")
        if btype == "text":
            print(f"[{i}] text: {block.get('text', '')[:300]}")
        elif btype == "tool_use":
            print(f"[{i}] tool_use: name={block.get('name')!r} id={block.get('id')!r}")
            print(f"    input: {json.dumps(block.get('input', {}), ensure_ascii=False)}")
        else:
            print(f"[{i}] {btype}: {json.dumps(block, ensure_ascii=False)[:300]}")


def main() -> int:
    img_path = ROOT / "IMG1.jpg"
    if not img_path.exists():
        sys.exit(f"missing {img_path}")
    img_bytes = img_path.read_bytes()

    # Round 1a: short max_tokens to force a single tool call instead of a
    # long free-form description.  If the model supports tools, it must
    # respond with a tool_use block — text doesn't fit in 64 tokens.
    user_msg = {
        "role": "user",
        "content": [
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": base64.b64encode(img_bytes).decode(),
                },
            },
            {"type": "text", "text": "调用工具。"},
        ],
    }
    r1 = call_model([user_msg], SYSTEM, max_tokens=64)
    summarize("round 1a (max_tokens=64, prompt=调用工具)", r1)

    # If model returned a tool_use, send tool_result + ask for final answer.
    tool_uses = [
        b for b in r1.get("content", []) if b.get("type") == "tool_use"
    ]
    if not tool_uses:
        print("\nModel did NOT emit any tool_use block — protocol unsupported or model refused.")
        print("Falling back to prompt-embedded JSON approach would be required.")
        return 1

    # Round 2: feed tool_result back.
    call = tool_uses[0]
    assistant_msg = {"role": "assistant", "content": r1["content"]}
    tool_result_block = {
        "type": "tool_result",
        "tool_use_id": call["id"],
        "content": json.dumps({"ok": True, "note": "已记录"}, ensure_ascii=False),
    }
    user_msg_2 = {
        "role": "user",
        "content": [tool_result_block, {"type": "text", "text": "请继续。"}],
    }
    r2 = call_model([user_msg, assistant_msg, user_msg_2], SYSTEM, max_tokens=640)
    summarize("round 2 (after tool_result)", r2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())