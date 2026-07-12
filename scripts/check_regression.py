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
    header = f"{'suite':<24} {'baseline':>9} {'now':>9} {'Δ':>8} {'status':>7}  {'errors':>6}  {'sec':>6}"
    print(header)
    print("-" * len(header))

    fail = 0
    for s in suites:
        delta = s["delta"]
        status = s["status"]
        marker = "⚠️" if status == "FAIL" else "✓"
        print(
            f"{s['name']:<24} {s['baseline']:>9.4f} {s['composite']:>9.4f} "
            f"{delta:>+8.4f} {marker} {status:<5}  {s.get('errors', 0):>6}  "
            f"{s.get('elapsed_sec', 0):>6.1f}"
        )
        if status == "FAIL":
            fail += 1

    print()
    if fail:
        print(f"❌ {fail}/{len(suites)} suite(s) regressed by ≥ {threshold}.")
        print(f"   Per [[feedback-investigate-before-revert]] rule:")
        print(f"   1) Check profiling/_*_<tag>.log for Outcome.Error / 529 / overload")
        print(f"   2) Inspect per-fixture signal in {target.name}")
        print(f"   3) Bug audit before single-var fix attempt")
        return 1
    print(f"✅ All {len(suites)} suite(s) within threshold.")
    return 0


if __name__ == "__main__":
    sys.exit(main())