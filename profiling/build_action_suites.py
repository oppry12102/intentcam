#!/usr/bin/env python3
"""
Build ACTION-first eval suites from RCTW-171.

Replaces the old intent-named suites (phone_20 / recruit_hiring_11 / ...)
after the 2026-07-17 intent-taxonomy retirement. Actions are first-class;
intent is free-form LLM glue, not a scored taxonomy. This script scans the
already-OCR'd `intent_all.json` index for the 5 registered actions
(dial_number / open_in_maps / share / redact_id / scan_to_pay), buckets
images by which action(s) their text triggers, and writes one GT file per
action + a `none` bucket (no actionable content → over-fire check).

Source (no re-OCR needed):
  - profiling/intent_all.json   : {image, matched_text, ...} per image
  - /home/oppry/RCTW-171/train_gts/<id>.txt : ICDAR GT transcripts
    (line: x1,y1,...,x4,y4,0,"transcript")  → expected_description_keywords

Output: profiling/ground_truth_<action>.json (30 scenes each), schema:
  {version:5, description, rctw_root, img_dir, scenes:[{
    id, file, category, expected_actions, expected_inputs,
    expected_description_keywords}]}

A multi-action image (e.g. phone + address) counts toward EACH of its
actions' suites, carrying the full expected_actions list — so each suite
tests "does action X fire when it should", independently.

Usage:
  python3 profiling/build_action_suites.py [--rctw-root /home/oppry/RCTW-171]
                                          [--limit 30] [--seed 20260717]
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
INDEX = REPO / "profiling" / "intent_all.json"
OUT_DIR = REPO / "profiling"

# ── Action trigger table (single source of truth for the build) ─────────────
# Keyword/regex sets mirror prod InputParsers + the retired IntentDecl llmHints
# (recovered via scan_intents.py CLUSTERS). Each action's requiredInputs is
# verified against app/.../ActionDecl.kt registerDefaultActions.
ACTION_TRIGGERS: dict[str, re.Pattern] = {
    "dial_number": re.compile(
        r"1[3-9]\d{9}"                  # mobile
        r"|\d{3,4}-\d{7,8}"             # landline
        r"|电话|Tel|手机|热线|订购电话|咨询电话"
        r"|\b110\b|\b119\b|\b120\b|\b122\b"  # emergency
    ),
    "open_in_maps": re.compile(
        r"路|街|道|巷|弄|号|栋|楼|店|广场|中心|镇|区|村|大厦|大楼|花园|城"  # address
        r"|医院|学校|政府|银行|派出所|工商|法院|邮局"                        # institution
        r"|→|←|↑|↓|入口|出口|前方|左转|右转|直行"                            # direction
    ),
    "share": re.compile(
        r"特价|促销|优惠|打折|满减|秒杀|清仓|甩卖|红包|限时|抢购"  # promo
        r"|出租|出售|求租|租赁|买卖|二手房|楼盘|房源|房产|中介|户型|月租"  # real_estate
        r"|招聘|诚聘|招工|兼职|求职|高薪|急招"                      # recruit
        r"|菜单|菜品|套餐|招牌菜|特色菜"                            # menu
        r"|请勿|禁止|警告|危险|注意|严禁|小心"                      # warning
        r"|营业时间|营业中"                                          # hours
    ),
    "redact_id": re.compile(
        r"\d{17}[\dXx]"                          # 18-digit ID
        r"|身份证|营业执照|驾驶证|车牌|注册号|统一社会信用代码"
    ),
    "scan_to_pay": re.compile(
        r"扫一扫|扫码支付|收款码|付款码|支付宝|微信支付|转账"
    ),
    # [2026-07-19] view_label — label-vocabulary triggers.  Kept tight
    # (label-specific nouns only, no generic 规格/产地) because RCTW is
    # street-scene: a loose pattern would tag shop banners as labels.
    "view_label": re.compile(
        r"标签|价签|吊牌|合格证|生产日期|保质期|净含量|配料表|执行标准"
        r"|快递单|面单|铭牌|营养成分|条形码|制造商"
    ),
}

# action → expected_inputs (mirrors ActionDef.requiredInputs)
ACTION_INPUTS: dict[str, list[dict]] = {
    "dial_number":   [{"action": "dial_number",   "key": "phone_number", "label": "手机号"}],
    "open_in_maps":  [{"action": "open_in_maps",  "key": "query",        "label": "地点或地址"}],
    "share":         [{"action": "share",         "key": "text",         "label": "正文"}],
    "redact_id":     [],   # Toast stub — no required input
    "scan_to_pay":   [],   # Toast stub — no required input
    "view_label":    [{"action": "view_label",    "key": "label_markdown", "label": "标签内容"}],
}

# action → category label (metadata only)
ACTION_CATEGORY = {
    "dial_number": "phone", "open_in_maps": "address", "share": "share_text",
    "redact_id": "id_document", "scan_to_pay": "payment_qr",
    "view_label": "label",
}

ACTIONS = ["dial_number", "open_in_maps", "share", "redact_id", "scan_to_pay", "view_label"]


def load_rctw_keywords(gt_path: Path) -> list[str]:
    """Read an ICDAR train_gts .txt; return ≤6 longest non-trivial
    transcript strings (for r_text expected_description_keywords)."""
    if not gt_path.exists():
        return []
    vals: list[str] = []
    with gt_path.open(encoding="utf-8", errors="replace") as f:
        for row in csv.reader(f):
            if not row:
                continue
            # last field is the quoted transcript
            txt = row[-1].strip() if row[-1] else ""
            if len(txt) >= 2:
                vals.append(txt)
    # longest distinct, drop near-dupes
    seen: set[str] = set()
    picked: list[str] = []
    for v in sorted(vals, key=len, reverse=True):
        key = v.replace(" ", "")
        if key in seen:
            continue
        seen.add(key)
        picked.append(v)
        if len(picked) >= 6:
            break
    return picked


def actions_for(text: str) -> list[str]:
    return [a for a, rx in ACTION_TRIGGERS.items() if rx.search(text)]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rctw-root", default="/home/oppry/RCTW-171")
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--seed", type=int, default=20260717)
    ap.add_argument("--index", default=str(INDEX))
    ap.add_argument(
        "--only", default=None, metavar="ACTION",
        help="Write only this action's GT file (plus nothing else).  "
             "REQUIRED when adding a new action: a full run regenerates "
             "every suite from scratch and clobbers the hand-curated "
             "scene lists (e.g. scan_to_pay's 30→6 strict-payment "
             "curation) — learned the hard way on 2026-07-19.",
    )
    args = ap.parse_args()

    rctw = Path(args.rctw_root)
    gts_dir = rctw / "train_gts"
    img_dir = str(rctw / "train_images")

    data = json.loads(Path(args.index).read_text(encoding="utf-8"))
    items = data["items"]
    rng = random.Random(args.seed)

    # bucket: action -> list of scene dicts ; plus none bucket
    buckets: dict[str, list[dict]] = {a: [] for a in ACTIONS}
    buckets["none"] = []

    skipped = 0
    for it in items:
        img = it.get("image", "")            # "train_images/image_NNNN.jpg"
        if not img:
            continue
        stem = Path(img).stem                 # image_NNNN
        text = it.get("matched_text") or ""
        acts = actions_for(text)
        kws = load_rctw_keywords(gts_dir / f"{stem}.txt")
        scene = {
            "id": stem,
            "file": Path(img).name,
            "expected_actions": acts,
            "expected_inputs": [],
            "expected_description_keywords": kws,
        }
        if acts:
            # union of each action's required inputs
            scene["expected_inputs"] = [inp for a in acts for inp in ACTION_INPUTS[a]]
            for a in acts:
                scene_cat = scene.copy()
                scene_cat["category"] = ACTION_CATEGORY[a]
                buckets[a].append(scene_cat)
        else:
            scene["category"] = "none"
            buckets["none"].append(scene)

    # sample limit per bucket, deterministically
    for a in list(buckets):
        rng.shuffle(buckets[a])
        buckets[a] = buckets[a][: args.limit]

    total = 0
    for a in ACTIONS + ["none"]:
        if args.only and a != args.only:
            continue
        n = len(buckets[a])
        total += n
        gt = {
            "version": 5,
            "description": f"action-first suite — action={a} (built from RCTW-171 via build_action_suites.py)",
            "rctw_root": str(rctw),
            "img_dir": img_dir,
            "scenes": buckets[a],
        }
        out = OUT_DIR / f"ground_truth_{a}.json"
        out.write_text(json.dumps(gt, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  {a:<14} {n:>3} scenes  -> {out.name}")

    print(f"\nTotal scenes written: {total}")
    print("Bucket population (before sampling):")
    # recompute unsampled counts for visibility
    return 0


if __name__ == "__main__":
    sys.exit(main())
