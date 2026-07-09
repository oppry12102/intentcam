#!/usr/bin/env python3
"""Compare an eval_autoocr_*.json against the @20 baseline (aligned4_20.json).

Prints composite, r2_text, r2_text_fuzzy, r2_type, error counts, and per-fixture
regressions (composite dropped by ≥0.1 vs baseline aligned4_20 on the same fixture
ID).
"""
import json, os, sys
from collections import defaultdict

BASELINE = "profiling/eval_rctw_v2_aligned4_20.json"

def load(path):
    with open(path) as f:
        return json.load(f)

def stats(data):
    fs = data.get("fixtures", [])
    if not fs:
        return None
    n = len(fs)
    rt = sum(x.get("read_text_calls", 0) for x in fs)
    zi = sum(x.get("zoom_in_calls", 0) for x in fs)
    eb = sum(1 for x in fs if x.get("emit_bubble_emitted"))
    errs = sum(x.get("errors", 0) for x in fs)
    rounds = sum(x.get("rounds_run", 0) for x in fs) / max(n, 1)
    details = sum(x.get("details_count", x.get("r1_details", 0) and len(x.get("r1_details", [])) or 0) for x in fs) / max(n, 1)
    return {
        "n": n,
        "composite": data.get("overall", 0),
        "r2_text": sum(x.get("r2_text", 0) for x in fs) / max(n, 1),
        "r2_text_fuzzy": sum(x.get("r2_text_fuzzy", 0) for x in fs) / max(n, 1),
        "r2_type": sum(x.get("r2_type", 0) for x in fs) / max(n, 1),
        "read_text": rt,
        "zoom_in": zi,
        "emit_bubble": eb,
        "errors": errs,
        "rounds_avg": rounds,
    }

def per_fixture_composite(data):
    return {x["id"]: x.get("composite", 0) for x in data.get("fixtures", [])}

def main():
    new_path = sys.argv[1] if len(sys.argv) > 1 else "profiling/eval_autoocr_20.json"
    base_path = sys.argv[2] if len(sys.argv) > 2 else BASELINE

    if not os.path.exists(new_path):
        print(f"NEW: {new_path} not found")
        sys.exit(1)
    if not os.path.exists(base_path):
        print(f"BASE: {base_path} not found")
        sys.exit(1)

    base = load(base_path)
    new = load(new_path)
    bs, ns = stats(base), stats(new)
    if bs is None or ns is None:
        print("empty fixtures")
        return

    print(f"{'metric':<22}{'baseline':>12}{'new':>12}{'delta':>10}")
    print("-" * 60)
    for k in ("composite", "r2_text", "r2_text_fuzzy", "r2_type", "read_text", "zoom_in", "errors"):
        delta = ns[k] - bs[k]
        sign = "+" if delta >= 0 else ""
        print(f"{k:<22}{bs[k]:>12.3f}{ns[k]:>12.3f}{sign}{delta:>9.3f}")
    print(f"{'emit_bubble':<22}{bs['emit_bubble']:>12d}{ns['emit_bubble']:>12d}")
    print(f"{'rounds_avg':<22}{bs['rounds_avg']:>12.2f}{ns['rounds_avg']:>12.2f}")

    # Per-fixture regressions (composite drop >= 0.1)
    base_per = per_fixture_composite(base)
    new_per = per_fixture_composite(new)
    common = set(base_per) & set(new_per)
    regressions = []
    for fid in sorted(common):
        d = new_per[fid] - base_per[fid]
        if d <= -0.10:
            regressions.append((fid, base_per[fid], new_per[fid], d))
    improvements = []
    for fid in sorted(common):
        d = new_per[fid] - base_per[fid]
        if d >= 0.10:
            improvements.append((fid, base_per[fid], new_per[fid], d))

    print()
    if regressions:
        print(f"REGRESSIONS (composite drop >= 0.10): {len(regressions)}")
        for fid, b, n, d in regressions:
            print(f"  {fid:<30} {b:.2f} -> {n:.2f} ({d:+.2f})")
    if improvements:
        print(f"IMPROVEMENTS (composite gain >= 0.10): {len(improvements)}")
        for fid, b, n, d in improvements:
            print(f"  {fid:<30} {b:.2f} -> {n:.2f} ({d:+.2f})")
    if not regressions and not improvements:
        print("No fixture-level regression or improvement >= 0.10.")

if __name__ == "__main__":
    main()