#!/usr/bin/env python3
"""reclassify_phase_i_v2.py — second pass of institution fixture
reclassification, building on reclassify_phase_i.py.

Moved fixtures (v4 per-fixture analysis):
  phone_60   → service_institution_60:
    - image_1882 (王俊平个体诊所 — medical)
    - image_6636 (翰艺书法 — training institution)
    - image_7296 (如家托管中心 — childcare)
    - image_7376 (政务服务中心 listing — government services)

pii20_60 has no additional institution fixtures beyond what was
already moved in v1 (image_2372 / image_2540 / image_334 was
considered but its GT expected_top_intent_type=id_document so it
doesn't belong in service_institution_60).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

PROFILING = Path(__file__).resolve().parent.parent / "profiling"

MOVES = [
    ("ground_truth_phone_60.json", "image_1882", "clinic_institution", ["open_in_maps"]),
    ("ground_truth_phone_60.json", "image_6636", "training_institution", ["open_in_maps"]),
    ("ground_truth_phone_60.json", "image_7296", "childcare_institution", ["open_in_maps"]),
    ("ground_truth_phone_60.json", "image_7376", "government_service_institution", ["open_in_maps"]),
]


def main() -> int:
    si_path = PROFILING / "ground_truth_service_institution_60.json"
    si_gt = json.loads(si_path.read_text())
    moved_in = []

    for src_file, image_id, new_cat, new_actions in MOVES:
        src_path = PROFILING / src_file
        src_gt = json.loads(src_path.read_text())
        scene = next((s for s in src_gt["scenes"] if s["id"] == image_id), None)
        if not scene:
            print(f"  WARN: {image_id} not found in {src_file}", file=sys.stderr)
            continue
        if any(s["id"] == image_id for s in si_gt["scenes"]):
            print(f"  {image_id}: already in service_institution_60; skipping")
            continue

        src_gt["scenes"] = [s for s in src_gt["scenes"] if s["id"] != image_id]
        src_path.write_text(json.dumps(src_gt, ensure_ascii=False, indent=2))

        scene = dict(scene)
        scene["category"] = new_cat
        scene["expected_top_intent_type"] = "service_institution"
        scene["expected_actions"] = new_actions
        scene["src"] = f"reclassify_phase_i_v2.py moved from {src_file}"
        moved_in.append(scene)
        print(f"  moved {image_id}: {src_file} → service_institution_60 "
              f"(category={new_cat!r})")

    if not moved_in:
        return 0

    si_gt["scenes"].extend(moved_in)
    meta = si_gt.get("_meta", {})
    meta["reclassified_v2_count"] = meta.get("reclassified_v2_count", 0) + len(moved_in)
    meta["last_reclassified_v2"] = "2026-07-13"
    si_gt["_meta"] = meta
    si_path.write_text(json.dumps(si_gt, ensure_ascii=False, indent=2))

    print(f"\nground_truth_service_institution_60.json now has "
          f"{len(si_gt['scenes'])} scenes (+{len(moved_in)} added).")
    return 0


if __name__ == "__main__":
    sys.exit(main())