#!/usr/bin/env python3
"""check_regression.py — compare the most recent regression summary against
the baselines manifest and emit a per-suite pass/fail line.

Usage:
    ./scripts/check_regression.py                          # latest summary
    ./scripts/check_regression.py regression/summary_X.json # specific file

Exit code:
    0  every suite |Δ| < threshold
    1  one or more suites |Δ| ≥ threshold (regression alert)
    2  no summary found / bad input
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
REGRESSION_DIR = PROJECT_ROOT / "profiling" / "regression"
BASELINES = PROJECT_ROOT / "profiling" / "baselines.json"


def latest_summary() -> Path | None:
    if not REGRESSION_DIR.exists():
        return None
    summaries = sorted(REGRESSION_DIR.glob("summary_*.json"))
    return summaries[-1] if summaries else None


def load_summary(path: Path) -> dict:
    return json.loads(path.read_text())


def main() -> int:
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else latest_summary()
    if target is None or not target.exists():
        print(f"ERROR: no summary file found in {REGRESSION_DIR}", file=sys.stderr)
        return 2

    summary = load_summary(target)
    threshold = summary.get("threshold", 0.05)
    suites = summary.get("suites", [])
    if not suites:
        print(f"ERROR: summary {target} has no suites", file=sys.stderr)
        return 2

    print(f"== Regression check @ {summary['timestamp']} ==")
    print(f"   source: {target.name}")
    print(f"   threshold: |Δ| ≥ {threshold}")
    print()
    # [2026-07-15 redesign] Per-component check.  Each suite in
    #  baselines.json can carry `v3_type` / `v3_text` / `v3_actions` /
    #  `v3_inputs` baselines (null until re-measured post-redesign).  We
    #  threshold-check ONLY when both the baseline AND the measured
    #  value are present; missing either → skip.
    header = f"{'suite':<24} {'baseline':>9} {'now':>9} {'Δ':>8} {'status':>7}  {'errors':>6}  {'sec':>6}"
    print(header)
    print("-" * len(header))

    # Load baselines once so we can cross-reference per-component numbers.
    baselines = json.loads(BASELINES.read_text())
    baseline_by_name = {s["name"]: s for s in baselines.get("suites", [])}

    fail = 0
    comp_fail_lines = []
    for s in suites:
        delta = s["delta"]
        status = s["status"]
        marker = "⚠️" if status == "FAIL" else "✓"
        print(
            f"{s['name']:<24} {s['baseline']:>9.4f} {s['composite_v2']:>9.4f} "
            f"{delta:>+8.4f} {marker} {status:<5}  {s.get('errors', 0):>6}  "
            f"{s.get('elapsed_sec', 0):>6.1f}"
        )
        if status == "FAIL":
            fail += 1
        # Per-component check: v2_inputs is the headline signal for
        # the orchestrator gate.
        bl = baseline_by_name.get(s["name"], {})
        for comp_key, comp_label in (
            ("v2_type", "v3_type"),
            ("v2_text", "v3_text"),
            ("v2_actions", "v3_actions"),
            ("v2_inputs", "v3_inputs"),
        ):
            bl_val = bl.get(comp_label)
            now_val = s.get(comp_key)
            if bl_val is None or now_val is None:
                continue  # not measured yet
            comp_delta = now_val - bl_val
            comp_flagged = abs(comp_delta) >= threshold
            comp_status = "FAIL" if comp_flagged else "PASS"
            comp_marker = "⚠️" if comp_flagged else "✓"
            print(
                f"    └ {comp_label:<22} {bl_val:>9.4f} {now_val:>9.4f} "
                f"{comp_delta:>+8.4f} {comp_marker} {comp_status:<5}"
            )
            if comp_flagged:
                comp_fail_lines.append(
                    f"  - {s['name']} {comp_label}: baseline={bl_val:.4f} "
                    f"now={now_val:.4f} Δ={comp_delta:+.4f}"
                )
                fail += 1

    print()
    if fail:
        print(f"❌ {fail} regression(s) by ≥ {threshold}.")
        if comp_fail_lines:
            print(f"   Per-component failures:")
            for line in comp_fail_lines:
                print(line)
        print(f"   Per [[feedback-investigate-before-revert]] rule:")
        print(f"   1) Check profiling/_*_<tag>.log for Outcome.Error / 529 / overload")
        print(f"   2) Inspect per-fixture signal in {target.name}")
        print(f"   3) Bug audit before single-var fix attempt")
        return 1
    print(f"✅ All {len(suites)} suite(s) within threshold.")
    return 0


if __name__ == "__main__":
    sys.exit(main())