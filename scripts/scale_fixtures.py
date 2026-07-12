#!/usr/bin/env python3
"""scale_fixtures.py — extend an existing 20-fixture GT suite to N
fixtures by selecting additional candidates from profiling/intent_all.json
whose matched_text matches the intent's keyword regex.

Usage:
    python3 scripts/scale_fixtures.py phone_20           # target 60
    python3 scripts/scale_fixtures.py phone_20 --target 80
    python3 scripts/scale_fixtures.py pii20 direction_arrow_20

How it picks:
  1. Read existing ground_truth_<name>.json's scene IDs (the seed)
  2. Read intent_all.json items, filter to those whose matched_text
     matches the intent's regex (mirrors IntentVerifier.kt constants)
  3. Exclude seed IDs and any image already used by other GT suites
     under profiling/ground_truth_*.json (cross-suite leakage guard)
  4. Sort by confidence desc, take the top (target − len(seed))
  5. Auto-tag category from a small keyword heuristic table
  6. Write profiling/ground_truth_<name>_<target>.json with a
     `_meta.auto_scaled` block so the curator can spot the new
     entries vs the manually curated seed.

Note: the auto-assigned category is a placeholder. Refine by hand
after running this script — it just gets you close.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PROFILING = REPO / "profiling"
INDEX = PROFILING / "intent_all.json"

# ── Intent regex + category heuristics ─────────────────────────────────
# Mirror IntentVerifier.kt constants so we pick the same fixtures the
# verifier would flip.

INTENTS: dict[str, dict] = {
    "phone_20": {
        "seed_gt": "ground_truth_phone_20.json",
        "regex": re.compile(r"1[3-9]\d{9}|0\d{2,3}[\s-]?\d{7,8}"),
        "type": "phone",
        "actions": ["dial_number"],
        # category: list of (keyword_regex, category_name)
        "categories": [
            (re.compile(r"招聘|诚聘|招工|兼职|应聘"), "recruit_ad_with_phone"),
            (re.compile(r"出售|求购|二手|旧货|调剂"), "classified_ads_with_phone"),
            (re.compile(r"医院|诊所|门诊|卫生"), "clinic_sign"),
            (re.compile(r"工厂|厂房|车间"), "factory_sign_with_phone"),
            (re.compile(r"学校|培训|辅导|招生"), "school_ad_with_phone"),
            (re.compile(r"餐饮|饭店|餐厅|酒店|宾馆|招待所"), "restaurant_with_phone"),
            (re.compile(r"修理|维修|服务|上门"), "service_ads_with_phone"),
            (re.compile(r"商场|百货|购物|超市"), "shopping_mall_with_phone"),
        ],
        "fallback_cat": "storefront_with_phone",
    },
    "pii20": {
        "seed_gt": "ground_truth_pii20.json",
        # REAL_ESTATE regex from IntentVerifier.kt
        "regex": re.compile(
            r"出租[房套房]|出售[房套]|二手房|二手房源|房源|楼盘出售|急售"
            r"|吉房|精装[房套]|户型|平米|押一付三"
        ),
        "type": "real_estate_rental",
        "actions": ["copy_listing"],
        "categories": [
            (re.compile(r"楼盘|花园|小区|苑|府"), "residential_development_billboard"),
            (re.compile(r"急售|吉房|急卖"), "building_rental_banner"),
            (re.compile(r"出售"), "building_rental_banner"),
            (re.compile(r"出租"), "building_rental_banner"),
            (re.compile(r"转让"), "shop_transfer_banner"),
        ],
        "fallback_cat": "building_rental_banner",
    },
    "direction_arrow_20": {
        "seed_gt": "ground_truth_direction_arrow_20.json",
        # DIRECTION_ARROW regex from IntentVerifier.kt
        "regex": re.compile(
            r"(?:[→←↑↓])|"
            r"(?:向左|向右|向前|向后)|"
            r"(?:左转|右转|直走|直行|左拐|右拐|前行)|"
            r"(?:步行.{0,8}(?:米|公里|分钟|分|步))|"
            r"(?:出口|入口).{0,6}(?:左|右|前)"
        ),
        "type": "route_to",
        "actions": ["open_in_maps"],
        "categories": [
            (re.compile(r"步行.{0,8}(?:米|公里|分钟|分|步)"), "phase_h_dw_dist"),
            (re.compile(r"(?:左转|右转|直走|直行|左拐|右拐|前行)"), "phase_h_dw_only"),
            (re.compile(r"(?:出口|入口)"), "phase_h_arrow_exit"),
        ],
        "fallback_cat": "phase_h_dw_only",
    },
}


def load_seed_ids(name: str, cfg: dict) -> set[str]:
    path = PROFILING / cfg["seed_gt"]
    if not path.exists():
        print(f"  WARN: seed GT not found at {path}", file=sys.stderr)
        return set()
    gt = json.loads(path.read_text())
    return {s["id"] for s in gt.get("scenes", [])}


def collect_cross_suite_ids() -> set[str]:
    """All scene IDs used by any profiling/ground_truth_*.json file.
    Prevents leakage — a candidate that's already annotated for another
    suite would double-count in a combined eval."""
    used: set[str] = set()
    for p in PROFILING.glob("ground_truth_*.json"):
        try:
            gt = json.loads(p.read_text())
            for s in gt.get("scenes", []):
                used.add(s.get("id", ""))
        except Exception:
            continue
    return used


def auto_category(text: str, cfg: dict) -> str:
    for pat, cat in cfg["categories"]:
        if pat.search(text):
            return cat
    return cfg["fallback_cat"]


def find_candidates(cfg: dict, exclude: set[str], need: int) -> list[dict]:
    idx = json.loads(INDEX.read_text())
    items = idx.get("items", [])
    rx = cfg["regex"]
    matches: list[tuple[float, str, str]] = []
    for it in items:
        if it["image"] in exclude:
            continue
        text = it.get("matched_text") or ""
        if not rx.search(text):
            continue
        matches.append((it["confidence"], it["image"], text))
    matches.sort(key=lambda x: (-x[0], x[1]))
    out = []
    for conf, img, text in matches:
        if need <= 0:
            break
        out.append({"conf": conf, "image": img, "text": text})
        need -= 1
    return out


def build_scene(c: dict, cfg: dict) -> dict:
    img_id = Path(c["image"]).stem  # e.g. "train_images/image_1177" → "image_1177"
    text = c["text"]
    return {
        "id": img_id,
        "file": Path(c["image"]).name,
        "src": f"scale_fixtures.py auto-scale conf={c['conf']:.3f}",
        "category": auto_category(text, cfg),
        "what_is_pictured": text[:120].replace("\n", " "),
        "must_have_in_scene_or_observation": [],
        "expected_top_intent_type": cfg["type"],
        "expected_actions": cfg["actions"],
    }


def scale(name: str, target: int) -> Path:
    cfg = INTENTS[name]
    seed_ids = load_seed_ids(name, cfg)
    print(f"[scale] {name}: seed={len(seed_ids)} target={target}")
    cross = collect_cross_suite_ids() - seed_ids
    need = max(0, target - len(seed_ids))
    print(f"[scale] cross-suite excluded: {len(cross)}, need to find: {need}")
    if need == 0:
        print(f"[scale] already at target; nothing to do")
        return PROFILING / cfg["seed_gt"]

    cands = find_candidates(cfg, exclude=seed_ids | cross, need=need)
    print(f"[scale] found {len(cands)} candidates (needed {need})")
    if len(cands) < need:
        print(f"[scale] WARN: only {len(cands)}/{need} candidates available",
              file=sys.stderr)

    seed_gt_path = PROFILING / cfg["seed_gt"]
    seed_gt = json.loads(seed_gt_path.read_text())
    seed_scenes = seed_gt.get("scenes", [])

    new_scenes = [build_scene(c, cfg) for c in cands]
    all_scenes = seed_scenes + new_scenes

    out_path = PROFILING / f"ground_truth_{name.replace('_20','')}_{target}.json"
    out = {
        **seed_gt,
        "scenes": all_scenes,
        "_meta": {
            "seed_gt": cfg["seed_gt"],
            "target": target,
            "seed_count": len(seed_scenes),
            "auto_scaled_count": len(new_scenes),
            "regex": cfg["regex"].pattern,
            "fallback_category": cfg["fallback_cat"],
            "note": (
                f"Auto-scaled from {len(seed_scenes)} → {target} fixtures. "
                "New entries have placeholder categories and empty "
                "must_have_in_scene_or_observation — refine by hand."
            ),
        },
    }
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2))
    print(f"[scale] wrote {out_path} ({len(all_scenes)} scenes)")
    return out_path


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("suite", nargs="+", help="suite name(s), e.g. phone_20")
    p.add_argument("--target", type=int, default=60,
                   help="target fixture count (default 60)")
    args = p.parse_args()

    for name in args.suite:
        if name not in INTENTS:
            print(f"  unknown suite: {name}; known: {list(INTENTS)}",
                  file=sys.stderr)
            continue
        scale(name, args.target)
    return 0


if __name__ == "__main__":
    sys.exit(main())