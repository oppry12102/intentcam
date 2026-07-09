#!/usr/bin/env python3
"""Re-score an eval JSON with both the OLD (separate) and NEW (union)
r2_text algorithms on the SAME model output, to isolate the scoring
change from LLM non-determinism.

Usage:
  python3 profiling/rescore_union_vs_separate.py <eval_json> <ground_truth>

Output: per-fixture (id, comp_old, comp_new, delta) + summary.
"""
import json
import sys
import unicodedata
from typing import List


def normalize(s: str) -> str:
    """Mirror EvalRunner.normalize: NFKC + strip whitespace + drop common
    punctuation that breaks substring matching."""
    if not s:
        return ""
    s = unicodedata.normalize("NFKC", s)
    s = s.replace("　", " ")  # fullwidth space
    s = s.replace(" ", " ")
    s = re.sub(r"\s+", "", s)
    s = s.replace(" ", "")
    s = s.replace(",", "").replace(".", "").replace(":", "").replace(";", "")
    s = s.replace("!", "").replace("?", "").replace("，", "").replace("。", "")
    s = s.replace("：", "").replace("；", "").replace("！", "").replace("？", "")
    return s


def fuzzy_match(hay: str, needle: str) -> bool:
    """Bidirectional normalized contains (mirrors EvalRunner.fuzzyMatch)."""
    if not needle:
        return True
    if not hay:
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


def char_overlap(hay: str, needle: str, threshold: float = 0.67) -> bool:
    """Mirror EvalRunner.hybridMatch char-overlap fallback."""
    n = normalize(needle).replace(" ", "")
    if not n:
        return True
    h = normalize(hay).replace(" ", "")
    if not h:
        return False
    n_chars = set(n)
    if not n_chars:
        return True
    if len(n_chars) == 1:
        return n_chars.pop() in h
    present = sum(1 for c in n_chars if c in h)
    return present / len(n_chars) >= threshold


def hybrid_match(hay: str, needle: str) -> bool:
    return fuzzy_match(hay, needle) or char_overlap(hay, needle)


import re

# ---- Two scoring algorithms on the SAME content+details ----

def score_separate(content: str, details_values: List[str],
                   expected_keywords: List[str],
                   expected_details: List[dict]) -> float:
    """OLD algorithm: textScore on content alone, detailScore on details
    values alone, average."""
    text_score = 0.0
    denom = 0
    if expected_keywords:
        hits = sum(1 for kw in expected_keywords
                   if kw and hybrid_match(content, kw))
        text_score = hits / len(expected_keywords)
        denom += 1
    if expected_details:
        llm_values = [normalize(v) for v in details_values if v.strip()]
        hits = 0
        for exp in expected_details:
            e = normalize(exp.get("value", ""))
            if not e:
                continue
            if any(hybrid_match(lv, e) for lv in llm_values):
                hits += 1
        detail_score = hits / len(expected_details)
        text_score = (text_score + detail_score) / 2.0 if denom else detail_score
        denom += 1
    if denom == 0:
        return 1.0
    return text_score


def score_union(content: str, details_values: List[str],
                expected_keywords: List[str],
                expected_details: List[dict]) -> float:
    """NEW algorithm: textScore and detailScore both match against
    (content + all details values) union haystack."""
    haystack = " ".join([content] + details_values)
    text_score = 0.0
    denom = 0
    if expected_keywords:
        hits = sum(1 for kw in expected_keywords
                   if kw and hybrid_match(haystack, kw))
        text_score = hits / len(expected_keywords)
        denom += 1
    if expected_details:
        hits = 0
        for exp in expected_details:
            e = normalize(exp.get("value", ""))
            if not e:
                continue
            if hybrid_match(haystack, e):
                hits += 1
        detail_score = hits / len(expected_details)
        text_score = (text_score + detail_score) / 2.0 if denom else detail_score
        denom += 1
    if denom == 0:
        return 1.0
    return text_score


def compute_text_and_detail(content: str, details_values: List[str],
                            expected_keywords: List[str],
                            expected_details: List[dict]):
    """Compute textScore (content-only) and detailScore (details-only)
    separately, mimicking the new Kotlin 'content-only r2_text, detailScore
    diagnostic' scoring.  Returns (textScore, detailScore)."""
    text_score = 0.0
    has_keywords = expected_keywords and len(expected_keywords) > 0
    if has_keywords:
        content_norm = normalize(content)
        hits = sum(1 for kw in expected_keywords
                   if kw and hybrid_match(content_norm, kw))
        text_score = hits / len(expected_keywords)
    elif not expected_details:
        text_score = 1.0

    detail_score = 0.0
    if expected_details:
        llm_values = [normalize(v) for v in details_values if v.strip()]
        hits = 0
        for exp in expected_details:
            e = normalize(exp.get("value", ""))
            if not e:
                continue
            if any(hybrid_match(lv, e) for lv in llm_values):
                hits += 1
        detail_score = hits / len(expected_details)
    return text_score, detail_score


def main():
    if len(sys.argv) < 3:
        print("usage: rescore_union_vs_separate.py <eval.json> <ground_truth.json>")
        sys.exit(1)
    eval_path = sys.argv[1]
    gt_path = sys.argv[2]

    d = json.load(open(eval_path))
    gt = json.load(open(gt_path))
    scenes = gt["scenes"]
    scene_map = {s["id"]: s for s in scenes}

    fixtures = d["fixtures"]
    n = len(fixtures)
    print(f"Re-scoring {n} fixtures from {eval_path}")
    print(f"GT has {len(scene_map)} scenes")
    print()

    rows = []
    for f in fixtures:
        fid = f["id"]
        scene = scene_map.get(fid)
        if not scene:
            continue
        content = f.get("raw_content", "")
        details = f.get("raw_details", [])
        details_values = [d.get("value", "") for d in details]
        exp_kw = scene.get("expected_description_keywords", [])
        exp_det = scene.get("expected_details", [])

        s_old = score_separate(content, details_values, exp_kw, exp_det)
        s_new = score_union(content, details_values, exp_kw, exp_det)
        text_co, detail_co = compute_text_and_detail(content, details_values, exp_kw, exp_det)
        rows.append((fid, s_old, s_new, text_co, detail_co,
                     f.get("r1", 0), f.get("r2_type", 0),
                     f.get("content_len", 0), len(details)))

    # Build composite (r1 + r2)/2 with each scoring
    print(f"{'id':<22} {'r2_old':<8} {'r2_union':<9} {'r2_co':<7} {'comp_old':<10} {'comp_union':<11} {'comp_co':<10} {'Δunion':<7} {'Δco':<7} {'det_co':<7}")
    sum_old = sum_union = sum_co = 0
    wins_u = losses_u = ties_u = 0
    wins_co = losses_co = ties_co = 0
    for fid, so, su, tco, dco, r1, r2t, cl, dn in rows:
        co_old = (r1 + so) / 2.0
        co_union = (r1 + su) / 2.0
        # NEW content-only: r2 = 0.5*textScore + 0.5*typeScore
        r2_co = 0.5 * tco + 0.5 * r2t
        co_new = (r1 + r2_co) / 2.0
        sum_old += co_old
        sum_union += co_union
        sum_co += co_new
        delta_u = su - so
        delta_co = co_new - co_old
        if abs(delta_u) > 0.01 or abs(delta_co) > 0.01:
            flag_u = '↑' if delta_u > 0.01 else ('↓' if delta_u < -0.01 else '=')
            flag_co = '↑' if delta_co > 0.01 else ('↓' if delta_co < -0.01 else '=')
            print(f"{fid:<22} {so:<8.3f} {su:<9.3f} {r2_co:<7.3f} {co_old:<10.3f} {co_union:<11.3f} {co_new:<10.3f} {delta_u:+.3f} {flag_u} {delta_co:+.3f} {flag_co} {dco:<7.3f}")
        if delta_u > 0.01: wins_u += 1
        elif delta_u < -0.01: losses_u += 1
        else: ties_u += 1
        if delta_co > 0.01: wins_co += 1
        elif delta_co < -0.01: losses_co += 1
        else: ties_co += 1

    print()
    print(f"=== summary ===")
    print(f"  composite (old, separate)              : {sum_old/len(rows):.4f}")
    print(f"  composite (union)                     : {sum_union/len(rows):.4f}")
    print(f"  composite (content-only r2 + det diag): {sum_co/len(rows):.4f}")
    print(f"  Δ union     vs old   : {(sum_union-sum_old)/len(rows):+.4f}  ({wins_u}W/{losses_u}L/{ties_u}T)")
    print(f"  Δ content-only vs old: {(sum_co-sum_old)/len(rows):+.4f}  ({wins_co}W/{losses_co}L/{ties_co}T)")


if __name__ == "__main__":
    main()
