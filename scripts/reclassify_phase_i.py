#!/usr/bin/env python3
"""reclassify_phase_i.py — move institution-sign fixtures from phone_60
and pii20_60 ground truth into service_institution_60, so the new
13th intent's classifier can score them correctly.

Moved fixtures (v3 regression analysis):
  phone_60   → service_institution_60:
    - image_2905 (萌乐园少儿托管中心 / 学盟教育) — TRUE institution
  pii20_60   → service_institution_60:
    - image_2372 (朱铁生西医外科诊所) — TRUE clinic
    - image_2540 (彭春阳西医内科诊所) — TRUE clinic

Other v3 drops (image_2267 / image_171 / image_1841 etc.) are pure
LLM variance, not institution-related — kept in their original suites.
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

PROFILING = Path(__file__).resolve().parent.parent / "profiling"

MOVES = [
    # (from_file, image_id, new_category, new_actions)
    ("ground_truth_phone_60.json",       "image_2905",
     "school_childcare_institution", ["open_in_maps"]),
    ("ground_truth_pii20_60.json",       "image_2372",
     "clinic_institution",            ["open_in_maps"]),
    ("ground_truth_pii20_60.json",       "image_2540",
     "clinic_institution",            ["open_in_maps"]),
]


def main() -> int:
    si_path = PROFILING / "ground_truth_service_institution_60.json"
    si_gt = json.loads(si_path.read_text())
    moved_in: list[dict] = []

    for src_file, image_id, new_cat, new_actions in MOVES:
        src_path = PROFILING / src_file
        src_gt = json.loads(src_path.read_text())
        scene = next((s for s in src_gt["scenes"] if s["id"] == image_id), None)
        if not scene:
            print(f"  WARN: {image_id} not found in {src_file}", file=sys.stderr)
            continue

        # Verify it's not already in service_institution_60
        if any(s["id"] == image_id for s in si_gt["scenes"]):
            print(f"  {image_id}: already in service_institution_60; skipping")
            continue

        # Remove from source
        src_gt["scenes"] = [s for s in src_gt["scenes"] if s["id"] != image_id]
        src_path.write_text(json.dumps(src_gt, ensure_ascii=False, indent=2))

        # Update scene for new home
        scene = dict(scene)  # shallow copy
        scene["category"] = new_cat
        scene["expected_top_intent_type"] = "service_institution"
        scene["expected_actions"] = new_actions
        scene["src"] = f"reclassify_phase_i.py moved from {src_file}"
        moved_in.append(scene)

        print(f"  moved {image_id}: {src_file} → service_institution_60 "
              f"(category={new_cat!r})")

    if not moved_in:
        print("No fixtures moved.")
        return 0

    # Append moved-in fixtures to service_institution_60
    si_gt["scenes"].extend(moved_in)

    # Update meta
    meta = si_gt.get("_meta", {})
    meta.setdefault("reclassified_from", [])
    meta["reclassified_from"].extend(
        {f"id": s["id"], "from": s.get("src", "?")} for s in moved_in
    )
    meta["reclassified_count"] = meta.get("reclassified_count", 0) + len(moved_in)
    meta["last_reclassified"] = "2026-07-12"
    si_gt["_meta"] = meta

    si_path.write_text(json.dumps(si_gt, ensure_ascii=False, indent=2))
    print(f"\n{service_institution_60_path_str()} now has "
          f"{len(si_gt['scenes'])} scenes ({len(moved_in)} added).")
    return 0


def service_institution_60_path_str() -> str:
    return "ground_truth_service_institution_60.json"


if __name__ == "__main__":
    sys.exit(main())