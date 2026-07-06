#!/usr/bin/env python3
"""Dry-run scorer comparison: re-score saved eval JSON with both
v0 (raw substring) and v1 (normalize + bidirectional fuzzyMatch) and
report the lift.

Usage:
    python3 profiling/scorer_compare.py profiling/eval_tier3.json

Reads:
  - JSON produced by :shared:eval — must have raw_content + raw_details
  - ground_truth_rctw.json — for expected_description_keywords +
    expected_details

Mirrors the Kotlin logic in
shared/src/main/kotlin/com/example/intentcam/eval/EvalRunner.kt so the
two stay in lock-step.  Update both at once.
"""
from __future__ import annotations
import argparse, json, re, sys, unicodedata
from pathlib import Path

GROUND_TRUTH = Path("profiling/ground_truth_rctw.json")

# ── Mirrors EvalRunner.normalize + fuzzyMatch ────────────────────────
_FULLWIDTH_QUOTES = re.compile("[‘’“”「」『』]")
_WS = re.compile(r"\s+")


def normalize(s: str) -> str:
    """NFKC + quote/colon fold + whitespace collapse + lowercase.
    Must stay in sync with EvalRunner.kt normalize()."""
    if not s:
        return s
    n = unicodedata.normalize("NFKC", s)
    n = _FULLWIDTH_QUOTES.sub("'", n)
    n = n.replace("：", ":")
    n = _WS.sub(" ", n)
    return n.strip().lower()


def fuzzy_match(hay: str, needle: str) -> bool:
    """Bidirectional normalized contains.  Mirrors EvalRunner.kt fuzzyMatch.
    Both sides get checked with and without internal whitespace, so
    '建国路 100号' vs '建国路100号' still match.
    """
    if needle == "":
        return True
    if hay == "":
        return False
    n = normalize(needle)
    h = normalize(hay)
    if n in h:
        return True
    if h in n and len(n) >= 2:
        return True
    n_no_ws = n.replace(" ", "")
    h_no_ws = h.replace(" ", "")
    if not n_no_ws or not h_no_ws:
        return False
    if n_no_ws in h_no_ws:
        return True
    if h_no_ws in n_no_ws and len(n_no_ws) >= 2:
        return True
    return False


def fuzzy_match_detail_hay(needle: str, value: str) -> bool:
    """Detail-row haystack is already normalized (the loop normalizes once).
    Mirrors Kotlin: eValue in lv || lv in eValue."""
    if needle == "":
        return True
    if value == "":
        return False
    # In the Kotlin side, `value` is already `normalize(value)` (lv).
    return needle in value or value in needle


# ── Scorers ──────────────────────────────────────────────────────────
def score_v0_content(content: str, expected: list[str]) -> float:
    """Original raw-lowercase substring.  Mirrors the line this PR replaced:
        expectedKeywords.getString(i).lowercase() in textLower
    """
    if not expected:
        return 1.0
    text = content.lower()
    hits = sum(1 for kw in expected if kw.lower() in text)
    return hits / len(expected)


def score_v1_content(content: str, expected: list[str]) -> float:
    """New normalize + bidirectional.  Mirrors new Kotlin:
        fuzzyMatch(contentNorm, expectedKeywords.getString(i))
    """
    if not expected:
        return 1.0
    norm = normalize(content)
    hits = sum(1 for kw in expected if fuzzy_match(norm, kw))
    return hits / len(expected)


def score_v0_detail(model_details: list[dict], expected_details: list[dict]) -> tuple[int, int]:
    """Original details matcher: lowercase + bidirectional substring on VALUE only.
    Returns (hits, denom).
    """
    if not expected_details:
        return (0, 0)
    llm = [d.get("value", "").lower() for d in model_details]
    llm = [v for v in llm if v]
    hits = 0
    for exp in expected_details:
        ev = exp.get("value", "").lower()
        if not ev:
            continue
        for lv in llm:
            if ev in lv or lv in ev:
                hits += 1
                break
    return (hits, len(expected_details))


def score_v1_detail(model_details: list[dict], expected_details: list[dict]) -> tuple[int, int]:
    """New details matcher: normalize + bidirectional (with min-length guard).
    Value-only match — label/kind ignored because GT labels are positional
    ('区域1', '招牌') while model labels are semantic ('品牌', '价格').
    """
    if not expected_details:
        return (0, 0)
    llm = [normalize(d.get("value", "")) for d in model_details]
    llm = [v for v in llm if v]
    hits = 0
    for exp in expected_details:
        ev = normalize(exp.get("value", ""))
        if not ev:
            continue
        for lv in llm:
            if fuzzy_match(lv, ev):
                hits += 1
                break
    return (hits, len(expected_details))


# ── Driver ───────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report", type=Path, help="path to eval_tierN.json")
    ap.add_argument("--gt", type=Path, default=GROUND_TRUTH)
    ap.add_argument("--limit", type=int, default=0,
                    help="limit fixtures (debug); 0 = all")
    args = ap.parse_args()

    rep = json.loads(args.report.read_text())
    gt_raw = json.loads(args.gt.read_text())
    if isinstance(gt_raw, dict) and "scenes" in gt_raw:
        gt = {s["id"]: s for s in gt_raw["scenes"]}
    else:
        gt = {s["id"]: s for s in gt_raw}

    fixtures = rep["fixtures"]
    if args.limit:
        fixtures = fixtures[:args.limit]

    n = len(fixtures)
    v0_texts, v1_texts = [], []
    v0_det_score, v1_det_score = [], []  # avg per fixture
    matched_pairs = []
    for f in fixtures:
        scene = gt[f["id"]]
        exp_kw = scene.get("expected_description_keywords", [])
        exp_det = scene.get("expected_details", [])

        content = f.get("raw_content", "")
        details = f.get("raw_details", [])

        s0 = score_v0_content(content, exp_kw)
        s1 = score_v1_content(content, exp_kw)
        v0_texts.append(s0)
        v1_texts.append(s1)

        if exp_det:
            h0, d0 = score_v0_detail(details, exp_det)
            h1, d1 = score_v1_detail(details, exp_det)
            v0_det_score.append(h0 / d0 if d0 else 1.0)
            v1_det_score.append(h1 / d1 if d1 else 1.0)
        else:
            v0_det_score.append(1.0)
            v1_det_score.append(1.0)

        if s1 > s0 + 0.01:
            matched_pairs.append((f["id"], s0, s1, content[:80].replace("\n", " ")))

    avg = lambda xs: sum(xs) / len(xs) if xs else 0.0
    print(f"fixtures scored: {n}")
    print()
    print(f"content keyword hit rate  v0={avg(v0_texts):.3f}   v1={avg(v1_texts):.3f}   "
          f"lift={avg(v1_texts) - avg(v0_texts):+.3f}")
    print(f"details hit rate          v0={avg(v0_det_score):.3f}   v1={avg(v1_det_score):.3f}   "
          f"lift={avg(v1_det_score) - avg(v0_det_score):+.3f}")
    print()

    # Rank by lift to find biggest scorer wins
    print("top 10 fixtures where v1 > v0 (scorer change wins):")
    matched_pairs.sort(key=lambda x: x[2] - x[1], reverse=True)
    for fid, s0, s1, snip in matched_pairs[:10]:
        print(f"  {fid:30s}  v0={s0:.2f}  v1={s1:.2f}  Δ={s1-s0:+.2f}  | {snip!r}")

    # Headline: what would the per-fixture composite look like if
    # we used v1 in place of v0?  Use the saved composite's r2_text as
    # anchor — if v0 matches saved r2_text well, our mirror is faithful.
    saved = [f.get("r2_text", 0) for f in fixtures]
    print()
    print(f"sanity: mean saved r2_text={avg(saved):.3f}, "
          f"mean re-scored v0 content={avg(v0_texts):.3f}  "
          f"(close → mirror is faithful)")


if __name__ == "__main__":
    main()
