#!/usr/bin/env python3
"""Compare two `eval_rctw_v2.py` JSON dumps side-by-side.

Usage:
    python3 profiling/diff_eval.py <baseline.json> <new.json> [--top N]

Reports:
  - overall composite delta + per-component delta (r1, r2_text, r2_text_fuzzy, r2_type)
  - per-fixture composite delta (sorted)
  - biggest wins / biggest losses (default top 10 each)
  - aggregate counts (emit_bubble_emitted rate, picked_tool distribution)
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path


SCORE_KEYS = ("r1", "r2_text", "r2_text_fuzzy", "r2_type", "composite")


def load_dump(path: str) -> dict:
    data = json.loads(Path(path).read_text())
    if "fixtures" in data:
        return data
    if isinstance(data, list):
        return {"fixtures": data, "overall": None, "per_category": {}}
    sys.exit(f"unrecognized JSON shape in {path}")


def per_fixture_delta(base: dict, new: dict) -> list[tuple[str, float, float, float]]:
    """Return list of (fixture_id, base_comp, new_comp, delta) sorted by delta desc."""
    base_by_id = {f["id"]: f for f in base["fixtures"]}
    new_by_id = {f["id"]: f for f in new["fixtures"]}
    common = sorted(set(base_by_id) & set(new_by_id))
    rows = []
    for fid in common:
        b = base_by_id[fid].get("composite") or 0
        n = new_by_id[fid].get("composite") or 0
        rows.append((fid, b, n, n - b))
    rows.sort(key=lambda r: r[3], reverse=True)
    return rows


def aggregate(fixtures: list[dict]) -> dict:
    n = len(fixtures)
    agg = {k: sum((f.get(k) or 0) for f in fixtures) / max(n, 1) for k in SCORE_KEYS}
    agg["emit_bubble_rate"] = sum(1 for f in fixtures if f.get("emit_bubble_emitted")) / max(n, 1)
    agg["read_text_total"] = sum(f.get("read_text_calls") or 0 for f in fixtures)
    agg["compare_text_total"] = sum(f.get("compare_text_calls") or 0 for f in fixtures)
    agg["zoom_in_total"] = sum(f.get("zoom_in_calls") or 0 for f in fixtures)
    agg["errors_total"] = sum(f.get("errors") or 0 for f in fixtures)
    return agg


def picked_dist(fixtures: list[dict]) -> Counter:
    return Counter(f.get("picked_tool_r1") for f in fixtures)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("baseline", help="Path to baseline JSON dump (typically aligned3)")
    ap.add_argument("new", help="Path to new JSON dump to compare")
    ap.add_argument("--top", type=int, default=10, help="Show top N wins/losses")
    ap.add_argument("--limit", type=int, default=0,
                    help="If >0, restrict comparison to first N fixtures (use --limit 20 for "
                         "fast iteration).")
    args = ap.parse_args()

    base = load_dump(args.baseline)
    new = load_dump(args.new)

    # If --limit set, slice both to first N fixtures.
    if args.limit > 0:
        base["fixtures"] = base["fixtures"][:args.limit]
        new["fixtures"] = new["fixtures"][:args.limit]

    base_agg = aggregate(base["fixtures"])
    new_agg = aggregate(new["fixtures"])

    print(f"baseline: {args.baseline}  ({len(base['fixtures'])} fixtures)")
    print(f"new:      {args.new}  ({len(new['fixtures'])} fixtures)")
    if args.limit > 0:
        print(f"(restricted to first {args.limit})")
    print()

    print("=== Overall ===")
    print(f"{'metric':<22} {'baseline':>9} {'new':>9} {'Δ':>9}")
    for k in SCORE_KEYS:
        b, n = base_agg[k], new_agg[k]
        print(f"{k:<22} {b:>9.4f} {n:>9.4f} {n - b:>+9.4f}")
    print()
    print(f"{'emit_bubble_rate':<22} {base_agg['emit_bubble_rate']:>9.4f} "
          f"{new_agg['emit_bubble_rate']:>9.4f} "
          f"{new_agg['emit_bubble_rate'] - base_agg['emit_bubble_rate']:>+9.4f}")
    print(f"{'read_text_total':<22} {base_agg['read_text_total']:>9} "
          f"{new_agg['read_text_total']:>9} "
          f"{new_agg['read_text_total'] - base_agg['read_text_total']:>+9}")
    print(f"{'compare_text_total':<22} {base_agg['compare_text_total']:>9} "
          f"{new_agg['compare_text_total']:>9} "
          f"{new_agg['compare_text_total'] - base_agg['compare_text_total']:>+9}")
    print(f"{'zoom_in_total':<22} {base_agg['zoom_in_total']:>9} "
          f"{new_agg['zoom_in_total']:>9} "
          f"{new_agg['zoom_in_total'] - base_agg['zoom_in_total']:>+9}")
    print(f"{'errors_total':<22} {base_agg['errors_total']:>9} "
          f"{new_agg['errors_total']:>9} "
          f"{new_agg['errors_total'] - base_agg['errors_total']:>+9}")

    # Strict-fuzzy gap convergence (alignment goal of this iteration)
    base_gap = base_agg["r2_text_fuzzy"] - base_agg["r2_text"]
    new_gap = new_agg["r2_text_fuzzy"] - new_agg["r2_text"]
    print()
    print(f"{'r2_text fuzzy-strict':<22} {base_gap:>9.4f} "
          f"{new_gap:>9.4f} {new_gap - base_gap:>+9.4f}  (target: ↓ toward 0)")

    print()
    print("=== Picked tool distribution ===")
    base_picks = picked_dist(base["fixtures"])
    new_picks = picked_dist(new["fixtures"])
    all_tools = sorted(set(base_picks) | set(new_picks), key=lambda x: (x is None, x))
    for tool in all_tools:
        b = base_picks.get(tool, 0)
        n = new_picks.get(tool, 0)
        print(f"  {str(tool):<14} baseline={b:>3}  new={n:>3}  Δ={n - b:+d}")

    # Per-fixture deltas
    deltas = per_fixture_delta(base, new)
    print()
    print(f"=== Per-fixture composite delta (top {args.top} wins / losses) ===")
    print(f"{'id':<22} {'baseline':>9} {'new':>9} {'Δ':>8}")
    for fid, b, n, d in deltas[:args.top]:
        print(f"{fid:<22} {b:>9.4f} {n:>9.4f} {d:>+8.4f}")
    print("  ...")
    for fid, b, n, d in deltas[-args.top:]:
        print(f"{fid:<22} {b:>9.4f} {n:>9.4f} {d:>+8.4f}")

    # Summary stats
    n = len(deltas)
    wins = sum(1 for _, _, _, d in deltas if d > 0)
    losses = sum(1 for _, _, _, d in deltas if d < 0)
    ties = n - wins - losses
    mean_delta = sum(d for _, _, _, d in deltas) / max(n, 1)
    print()
    print(f"=== Summary ({n} fixtures compared) ===")
    print(f"  wins:   {wins}")
    print(f"  losses: {losses}")
    print(f"  ties:   {ties}")
    print(f"  mean Δ: {mean_delta:+.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())