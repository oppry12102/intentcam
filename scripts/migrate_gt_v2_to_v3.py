#!/usr/bin/env python3
"""migrate_gt_v2_to_v3.py — populate `expected_inputs` on the v3.0
ground-truth fixtures so ScorerV2's `r_inputs_complete` component can
finally lift off its 1.0 floor.

For each scene, walks `expected_actions` (already present) and emits
a parallel `expected_inputs` array.  Each entry has:
  - `action`: the action id
  - `key`:    the requiredInput key (e.g. `phone_number`)
  - `label`:  the human-readable label (e.g. `手机号`)

The action → inputs map mirrors `app/.../ActionDecl.kt`'s registered
`ActionDef.requiredInputs` (mirrored here because the script doesn't
have access to Kotlin).  When the two drift, eval-side
`r_inputs_complete` becomes a soft signal (false 0s); the canonical
fix is to update this table to match the Kotlin ActionDecl.

Usage:
    python3 scripts/migrate_gt_v2_to_v3.py            # writes in place
    python3 scripts/migrate_gt_v2_to_v3.py --dry-run  # diff to stdout
    python3 scripts/migrate_gt_v2_to_v3.py path1.json path2.json

Idempotent: re-running is safe (overwrites cleanly).  Bumps the root
`version` field from 2/3 → 4 to signal v3 fixture format; suites that
already say `version: 4` are no-ops.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable

REPO = Path(__file__).resolve().parent.parent
PROFILING = REPO / "profiling"

# Target suite GT files for v3.0 (5 actionable 20-fixture suites +
# service_institution_60 + direction_arrow_60 since they're part of
# the regression net per plan).
DEFAULT_TARGETS: tuple[str, ...] = (
    "ground_truth_phone_20.json",
    "ground_truth_pii20.json",
    "ground_truth_direction_arrow_20.json",
    "ground_truth_phaseG_15.json",
    "ground_truth_shopping_promo_20.json",
    "ground_truth_service_institution_60.json",
)

# Canonical action id → requiredInput map.  MUST mirror
# app/.../ActionDecl.kt's `registerDefaultActions` for v3.0.  Keys
# without requiredInputs (scan_to_pay / redact_id) map to empty
# list → no `expected_inputs` entries emitted.
ACTION_REQUIRED_INPUTS: dict[str, list[dict[str, str]]] = {
    "dial_number":   [{"key": "phone_number", "label": "手机号"}],
    "open_in_maps":  [{"key": "query",        "label": "地点或地址"}],
    # [2026-07-15] Unified share-text action (was copy_listing /
    # save_posting / copy_warning / copy_menu / copy_hours / copy_promo).
    "share":         [{"key": "text",         "label": "正文"}],
    # [2026-07-19] Label action — the LLM transcribes the full label
    # into emit_bubble.label_markdown; that field IS the required input.
    "view_label":    [{"key": "label_markdown", "label": "标签内容"}],
    # [2026-07-20] Ad action — emit_bubble.ad_markdown is the required
    # input; ad_bbox (framing quad) is optional by design.
    "view_ad":       [{"key": "ad_markdown",    "label": "广告内容"}],
    # Actions without requiredInputs — emit nothing.
    "scan_to_pay":   [],
    "redact_id":     [],
}

V3_VERSION = 4  # bumped from 2/3 → 4 to mark v3-format fixtures


def derive_expected_inputs(expected_actions: list[str]) -> list[dict[str, str]]:
    """For one scene, build `expected_inputs` from `expected_actions`.

    Dedups by (action, key) — same action listed twice in
    `expected_actions` (rare, but happens when the verifier added
    a canonical action on top of an LLM proposal) produces one
    `expected_inputs` entry.
    """
    seen: set[tuple[str, str]] = set()
    out: list[dict[str, str]] = []
    for action_id in expected_actions:
        specs = ACTION_REQUIRED_INPUTS.get(action_id, [])
        for spec in specs:
            dedup_key = (action_id, spec["key"])
            if dedup_key in seen:
                continue
            seen.add(dedup_key)
            out.append({
                "action": action_id,
                "key": spec["key"],
                "label": spec["label"],
            })
    return out


def migrate_scene(scene: dict) -> bool:
    """Mutate one scene in place.  Returns True iff something changed.

    No-op when the scene already has `expected_inputs` matching the
    derived value AND the root version is already v3 — i.e. the
    migration is idempotent.  Drift between the Kotlin registry and
    this Python table still updates `expected_inputs` (we trust the
    Python table as the v3 source of truth).
    """
    actions = scene.get("expected_actions") or []
    derived = derive_expected_inputs(actions)
    if scene.get("expected_inputs") == derived:
        return False
    scene["expected_inputs"] = derived
    return True


def migrate_file(path: Path, *, dry_run: bool) -> tuple[int, int]:
    """Migrate one GT file.  Returns (scenes_changed, total_scenes)."""
    data = json.loads(path.read_text())
    scenes = data.get("scenes") or []
    if not scenes:
        return 0, 0
    changed = sum(1 for s in scenes if migrate_scene(s))
    if data.get("version") != V3_VERSION:
        data["version"] = V3_VERSION
    if dry_run:
        # Don't write; print the diff size.
        print(f"[DRY-RUN] {path.name}: {changed}/{len(scenes)} scenes changed, "
              f"version → {V3_VERSION}")
    else:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
        print(f"[WROTE]   {path.name}: {changed}/{len(scenes)} scenes updated, "
              f"version → {V3_VERSION}")
    return changed, len(scenes)


def resolve_targets(paths: Iterable[str]) -> list[Path]:
    """CLI path args override defaults; bare names look in profiling/."""
    out: list[Path] = []
    for p in paths:
        cand = Path(p)
        if not cand.is_absolute() and not cand.exists():
            cand = PROFILING / p
        out.append(cand)
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Populate expected_inputs on v3.0 GT fixtures.")
    parser.add_argument(
        "paths", nargs="*", default=list(DEFAULT_TARGETS),
        help="GT file paths (relative to profiling/ or absolute). "
             "Default: the 5 actionable 20-fixture suites + service_institution_60.")
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Print diff summary without writing.")
    args = parser.parse_args(argv)

    targets = resolve_targets(args.paths)
    missing = [t for t in targets if not t.exists()]
    if missing:
        print(f"ERROR: missing files: {missing}", file=sys.stderr)
        return 1

    total_changed = total_scenes = 0
    for path in targets:
        c, n = migrate_file(path, dry_run=args.dry_run)
        total_changed += c
        total_scenes += n

    verb = "would update" if args.dry_run else "updated"
    print(f"\n{verb} {total_changed}/{total_scenes} scenes across {len(targets)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))