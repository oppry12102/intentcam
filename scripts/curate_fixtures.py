#!/usr/bin/env python3
"""curate_fixtures.py — hand-curate 5-10 auto-scaled fixtures per scaled
suite. Refines category from placeholder + fills must_have_in_scene_or_observation
with concrete identifiable strings (phone numbers, address fragments,
directional text).

Usage:
    python3 scripts/curate_fixtures.py
"""
from __future__ import annotations

import json
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PROFILING = REPO / "profiling"


# (gt_file, image_id, refined_category, must_have_list)
# Each must_have item is a literal substring the OCR / verifier must
# observe for r2_text credit.
CURATIONS: list[tuple[str, str, str, list[str]]] = [
    # ── phone_60 (7 picks) ───────────────────────────────────────────
    ("ground_truth_phone_60.json", "image_1882",
     "clinic_sign",
     ["王俊平", "13407272442", "仙桃市复州大道"]),
    ("ground_truth_phone_60.json", "image_2372",
     "clinic_sign",
     ["朱铁生", "135542274"]),
    ("ground_truth_phone_60.json", "image_1583",
     "service_ads_with_phone",
     ["全友", "3201778", "仙桃"]),
    ("ground_truth_phone_60.json", "image_2458",
     "restaurant_with_phone",
     ["京味香", "13085136276", "13385229849"]),
    ("ground_truth_phone_60.json", "image_2173",
     "restaurant_with_phone",
     ["三川", "027-87878057", "光谷步行街"]),
    ("ground_truth_phone_60.json", "image_1755",
     "service_ads_with_phone",
     ["城市之佳", "15907162019", "13307167387"]),
    ("ground_truth_phone_60.json", "image_1653",
     "classified_ads_with_phone",
     ["13706258255", "屯村西路"]),

    # ── pii20_60 (7 picks) ───────────────────────────────────────────
    ("ground_truth_pii20_60.json", "image_3285",
     "residential_development_billboard",
     ["吉房急售", "15607", "复州花园"]),
    ("ground_truth_pii20_60.json", "image_572",
     "residential_development_billboard",
     ["世界城", "027-85393898", "70平米"]),
    ("ground_truth_pii20_60.json", "image_6214",
     "residential_development_billboard",
     ["116-125平米精品住宅", "8880505"]),
    ("ground_truth_pii20_60.json", "image_7215",
     "residential_development_billboard",
     ["2000元抵20000元", "0374-6086666"]),
    ("ground_truth_pii20_60.json", "image_2562",
     "residential_development_billboard",
     ["珍稀江景豪宅", "2905888", "110-137平米"]),
    ("ground_truth_pii20_60.json", "image_3406",
     "residential_development_billboard",
     ["御景天成", "027-87518566"]),
    ("ground_truth_pii20_60.json", "image_5024",
     "building_rental_banner",
     ["急售"]),

    # ── direction_arrow_60 (7 picks) ─────────────────────────────────
    ("ground_truth_direction_arrow_60.json", "image_111",
     "phase_h_dw_dist",
     ["向前50米", "2905888", "曹家坪路"]),
    ("ground_truth_direction_arrow_60.json", "image_478",
     "phase_h_dw_dist",
     ["前行50米", "87568110"]),
    ("ground_truth_direction_arrow_60.json", "image_4932",
     "phase_h_dw_only",
     ["由此前行", "光谷世界城"]),
    ("ground_truth_direction_arrow_60.json", "image_6374",
     "phase_h_dw_dist",
     ["向前50米", "退思园"]),
    ("ground_truth_direction_arrow_60.json", "image_6613",
     "phase_h_dw_only",
     ["左转上楼", "德意风情街"]),
    ("ground_truth_direction_arrow_60.json", "image_6423",
     "phase_h_dw_only",
     ["请直走", "佰乐星"]),
    ("ground_truth_direction_arrow_60.json", "image_6753",
     "phase_h_dw_dist",
     ["右转200米", "027-87531777"]),
]


def main() -> int:
    by_file: dict[str, list[tuple[str, str, list[str]]]] = {}
    for gt_file, image_id, cat, must in CURATIONS:
        by_file.setdefault(gt_file, []).append((image_id, cat, must))

    for gt_file, items in by_file.items():
        path = PROFILING / gt_file
        gt = json.loads(path.read_text())
        scenes = gt["scenes"]
        updated = 0
        for image_id, cat, must in items:
            for s in scenes:
                if s["id"] == image_id:
                    old_cat = s["category"]
                    old_must = s.get("must_have_in_scene_or_observation", [])
                    s["category"] = cat
                    s["must_have_in_scene_or_observation"] = must
                    updated += 1
                    print(f"  {gt_file:42s} {image_id:14s} "
                          f"cat: {old_cat:30s} → {cat}")
                    print(f"    must_have: {old_must} → {must}")
                    break
            else:
                print(f"  WARN: {image_id} not found in {gt_file}",
                      file=__import__("sys").stderr)

        # Update meta: track hand-curated count
        meta = gt.get("_meta", {})
        meta["hand_curated_count"] = meta.get("hand_curated_count", 0) + updated
        meta["last_curated"] = "2026-07-12"
        gt["_meta"] = meta

        path.write_text(json.dumps(gt, ensure_ascii=False, indent=2))
        print(f"\n{gt_file}: curated {updated}/{len(items)} scenes\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())