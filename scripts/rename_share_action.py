#!/usr/bin/env python3
"""One-off (2026-07-15): collapse the six former per-intent share
actions into the single unified `share` action across every
ground-truth fixture.

Old ids  copy_listing / save_posting / copy_warning / copy_menu /
copy_hours / copy_promo  →  share.

For each scene:
  * `expected_actions[]`  — remap ids, dedup preserving order.
  * `expected_inputs[]`   — only when the field already exists (v3
    fixtures); regenerate from the remapped `expected_actions` via
    the canonical ACTION_REQUIRED_INPUTS table so labels normalize
    to the new "正文" (and stay in lockstep with the migrate script).

v2 fixtures (no `expected_inputs`) only get `expected_actions`
remapped. Idempotent: re-running is a no-op once migrated.
"""
from __future__ import annotations

import glob
import json
import os

from migrate_gt_v2_to_v3 import ACTION_REQUIRED_INPUTS, derive_expected_inputs

OLD_TO_NEW = {
    "copy_listing": "share",
    "save_posting": "share",
    "copy_warning": "share",
    "copy_menu": "share",
    "copy_hours": "share",
    "copy_promo": "share",
}

# Sanity: the target id must exist in the canonical input table.
assert "share" in ACTION_REQUIRED_INPUTS, "update migrate_gt_v2_to_v3 first"


def remap_actions(actions: list[str]) -> list[str]:
    out: list[str] = []
    for a in actions:
        mapped = OLD_TO_NEW.get(a, a)
        if mapped not in out:
            out.append(mapped)
    return out


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    files = sorted(glob.glob(os.path.join(root, "profiling", "ground_truth_*.json")))
    total_files = 0
    for path in files:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        scenes = data.get("scenes")
        if not isinstance(scenes, list):
            continue
        touched = False
        # Suite-level default `expected_actions` (some mini-suites
        # carry one at the top level alongside per-scene lists).
        top_ea = data.get("expected_actions")
        if isinstance(top_ea, list) and any(a in OLD_TO_NEW for a in top_ea):
            data["expected_actions"] = remap_actions(top_ea)
            touched = True
        for scene in scenes:
            ea = scene.get("expected_actions")
            if isinstance(ea, list) and any(a in OLD_TO_NEW for a in ea):
                scene["expected_actions"] = remap_actions(ea)
                touched = True
            # Regenerate expected_inputs only if the fixture already
            # carries the field (v3 format); leave v2 fixtures alone.
            if "expected_inputs" in scene and isinstance(scene.get("expected_actions"), list):
                scene["expected_inputs"] = derive_expected_inputs(scene["expected_actions"])
        if touched:
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False, indent=2)
                fh.write("\n")
            total_files += 1
            print(f"rewrote {os.path.basename(path)}")
    print(f"done: {total_files} file(s) touched")


if __name__ == "__main__":
    main()
