#!/usr/bin/env python3
"""Compare two eval-tier json reports head-to-head on per-fixture
metrics.  Used to analyse 1+4 vs 1-only image strategies.

Usage:
    python3 profiling/compare_1plus4.py A.json B.json
"""
from __future__ import annotations
import argparse, json, sys
from pathlib import Path
from collections import Counter


def load(p: Path) -> dict:
    return json.loads(p.read_text())


def summarize(name: str, rep: dict) -> dict:
    fixtures = rep["fixtures"]
    n = len(fixtures)
    if n == 0:
        return {"name": name, "n": 0}
    avg = lambda key: sum(f.get(key, 0) for f in fixtures) / n
    avg_int = lambda key: sum(f.get(key, 0) for f in fixtures) / n
    r2_buckets = Counter()
    for f in fixtures:
        r2 = f.get("r2_text", 0)
        if r2 < 0.1: r2_buckets['err'] += 1
        elif r2 < 0.4: r2_buckets['low(<0.4)'] += 1
        elif r2 < 0.7: r2_buckets['mid(0.4-0.7)'] += 1
        else: r2_buckets['high(≥0.7)'] += 1
    return {
        "name": name,
        "n": n,
        "composite": avg("composite"),
        "r2_text": avg("r2_text"),
        "r2_type": avg("r2_type"),
        "r2_text_fuzzy": avg("r2_text_fuzzy"),
        "details_count": avg_int("details_count"),
        "empty_details": sum(1 for f in fixtures if f.get("details_count", 0) == 0),
        "content_len": avg_int("content_len"),
        "r2_buckets": dict(r2_buckets),
    }


def per_fixture_diff(a: dict, b: dict) -> list[tuple[str, float, float, float, float]]:
    """Return [(id, a_r2, b_r2, a_comp, b_comp), ...] sorted by r2 delta b - a (best lifts first)."""
    a_d = {f["id"]: f for f in a["fixtures"]}
    b_d = {f["id"]: f for f in b["fixtures"]}
    common = sorted(set(a_d) & set(b_d))
    diffs = []
    for fid in common:
        a_r2 = a_d[fid].get("r2_text", 0)
        b_r2 = b_d[fid].get("r2_text", 0)
        a_c = a_d[fid].get("composite", 0)
        b_c = b_d[fid].get("composite", 0)
        diffs.append((fid, a_r2, b_r2, a_c, b_c))
    return diffs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a", type=Path)
    ap.add_argument("b", type=Path)
    ap.add_argument("--top", type=int, default=8)
    args = ap.parse_args()

    a, b = load(args.a), load(args.b)
    sa, sb = summarize("A", a), summarize("B", b)

    print("=" * 70)
    print(f"{'metric':<22}  {sa['name']:>10}  {sb['name']:>10}  {'Δ':>10}")
    print("-" * 70)
    for k in ("n", "composite", "r2_text", "r2_type", "r2_text_fuzzy",
              "details_count", "content_len"):
        va = sa.get(k, 0); vb = sb.get(k, 0)
        delta = vb - va
        if k in ("n",):
            print(f"{k:<22}  {va:>10}  {vb:>10}")
        elif k in ("empty_details",):
            print(f"{k:<22}  {int(va):>10}  {int(vb):>10}  {int(delta):>+10}")
        else:
            print(f"{k:<22}  {va:>10.3f}  {vb:>10.3f}  {delta:>+10.3f}")
    print()
    print(f"r2 bucket distribution ({sa['name']} / {sb['name']}):")
    for bucket in ("err", "low(<0.4)", "mid(0.4-0.7)", "high(≥0.7)"):
        print(f"  {bucket:<16}  {sa['r2_buckets'].get(bucket, 0):>3}  {sb['r2_buckets'].get(bucket, 0):>3}")
    print()

    diffs = per_fixture_diff(a, b)
    if diffs:
        diffs.sort(key=lambda x: x[2] - x[1], reverse=True)  # biggest lift from B over A
        print(f"top {args.top} fixtures where B > A (r2 lift):")
        for fid, ar2, br2, ac, bc in diffs[:args.top]:
            print(f"  {fid:30s}  a r2={ar2:.2f}  b r2={br2:.2f}  Δr2={br2-ar2:+.2f}  comp a={ac:.2f} b={bc:.2f}")
        print()
        print(f"bottom {args.top} fixtures where A > B (B regressed):")
        for fid, ar2, br2, ac, bc in diffs[-args.top:]:
            print(f"  {fid:30s}  a r2={ar2:.2f}  b r2={br2:.2f}  Δr2={br2-ar2:+.2f}  comp a={ac:.2f} b={bc:.2f}")


if __name__ == "__main__":
    main()
