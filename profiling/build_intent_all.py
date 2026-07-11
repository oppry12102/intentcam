#!/usr/bin/env python3
"""
Build / query the intent_all index for RCTW-171.

Subcommands:
  build             Walk RCTW-171, OCR train + test, classify, write
                    profiling/intent_all.json.
  apply-corrections Merge intent_corrections.json into intent_all.json so
                    downstream readers can pick the effective intent
                    (override ?? auto_intent) without re-running OCR.
  select --intent=X --limit=N
                    Emit a TSV of image paths ranked by confidence,
                    filtered to effective_intent == X.  Eval can pipe
                    this into --fixtures.
  inspect --limit=K Sample N items at random to eyeball classification
                    quality; prints image + intent + first 80 chars of
                    matched text.  Useful the first time you run this
                    on a new dataset.

See profiling/README_intent_all.md for schema + correction flow.

Usage:
    set -a; . ~/.huawei_env; set +a  # Huawei Cloud creds for test OCR
    python3 build_intent_all.py build --rctw-root /home/oppry/RCTW-171
    python3 build_intent_all.py select --intent location --limit 10 \
        --out img_dirs/location_10.txt
    python3 build_intent_all.py apply-corrections
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
INDEX_PATH = REPO / "profiling" / "intent_all.json"
CORRECTIONS_PATH = REPO / "profiling" / "intent_corrections.json"

# ─── Keyword lists ─────────────────────────────────────────────────────────
# Picked up front so the regression risk of changing them is visible.
# Multi-class images: location takes precedence over solve over info;
# matches in this priority order are summed, the highest-count class wins.

LOCATION_KEYWORDS = [
    # street / road
    "路", "街", "道", "巷", "胡同", "大街",
    # number / address suffix
    "号", "栋", "楼", "层", "室", "单元",
    # commercial establishment
    "店", "商厦", "广场", "中心", "城", "镇", "园", "院", "村",
    "坊", "港", "大厦", "大楼", "公寓",
    # transport
    "地铁", "公交", "站", "机场", "港口", "出口", "入口", "高速",
    # administrative
    "省", "市", "县", "区", "乡", "州",
    # direction
    "方向", "东", "南", "西", "北",
    # distance
    "米", "km", "公里", "米处",
    # postal
    "邮编", "邮政编码",
    # english-ish location tokens that bleed into rctw mix
    "ROAD", "STREET", "AVE", "BLVD", "PLAZA", "MALL",
]

SOLVE_KEYWORDS = [
    # math verbs (Chinese-only).  Pure operators like + - = ÷ are
    # omitted because they appear in menu/price/temperature-control
    # contexts far more often than in equations; including them
    # caused systematic misclassification on McDonald's-style
    # images during the 2026-07-11 dry-run.
    "解", "方程", "公式", "求", "答案", "计算", "证明", "题",
    "因式分解", "化简", "化简求值", "几何", "代数",
    # school contexts
    "试卷", "练习", "卷", "年级",
    # English math tokens — leading/trailing spaces so 'x' alone
    # (a variable name, multiplication symbol, or brand letter)
    # doesn't false-positive.
    "Solve", " x ", " y ", "cos", "sin", "tan",
]


def classify(text: str) -> tuple[str, list[str], float]:
    """Return (intent, matched_keywords, confidence).  Empty / no-signal
    text → ('unknown', [], 0.0)."""
    if not text or not text.strip():
        return ("unknown", [], 0.0)
    # Use the most common character forms (full-width ASCII counts as
    # a hit when the keyword is a letter — handles bilingual signage).
    text_lower = text

    loc_hits = sorted({kw for kw in LOCATION_KEYWORDS if kw in text_lower})
    sol_hits = sorted({kw for kw in SOLVE_KEYWORDS if kw in text_lower})

    if len(loc_hits) >= 2 and len(loc_hits) > len(sol_hits):
        # Two or more distinct location markers wins.  Example: image
        # has '金氏眼镜 创于1989 城建店' → matches 店 + (no 路/号 so
        # only 1 hit, falls through to info by default — see below).
        conf = min(1.0, len(loc_hits) / 5.0 + 0.20)
        return ("location", loc_hits, conf)
    if len(sol_hits) >= 2 and len(sol_hits) > len(loc_hits):
        conf = min(1.0, len(sol_hits) / 5.0 + 0.20)
        return ("solve", sol_hits, conf)
    if loc_hits or sol_hits:
        # Single keyword = the heuristic is suggestive but not
        # committed.  Confident enough for "info" (default) but mark
        # basis so reviewers know what triggered.
        kind = "location" if loc_hits else "solve"
        matched = loc_hits or sol_hits
        conf = 0.50 if matched else 0.30
        return ("info", matched, conf)
    # No keyword hits at all — text-rich default is "info" (the
    # dominant class in scene-text datasets).
    conf = 0.30 if len(text_lower) > 30 else 0.10
    return ("info", [], conf)


# ─── RCTW GT parser ───────────────────────────────────────────────────────

def parse_train_gt_file(gt_path: Path) -> str:
    """RCTW train GT files are line-orientated text:
        x1,y1,x2,y2,x3,y3,x4,y4,difficult,"quoted text"
    Returns the concatenated, dedup-while-keeping-order text for the
    whole image (one space separator per line)."""
    out = []
    seen = set()
    try:
        with gt_path.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                # Split on the last comma — the trailing "text" may
                # itself contain commas (rare, but defensive).
                m = re.match(r"^[^\"]*\"(.*)\"\s*$", line)
                if m:
                    text = m.group(1)
                else:
                    text = line.rsplit(",", 1)[-1]
                if text and text not in seen:
                    seen.add(text)
                    out.append(text)
    except FileNotFoundError:
        return ""
    return " ".join(out)


def scan_train(rctw_root: Path) -> list[dict]:
    """Iterate train_gts/image_*.txt and emit per-image intent rows.
    Each row has an `image` path relative to `rctw_root` so downstream
    tooling can resolve it regardless of where the index lives."""
    train_gts = rctw_root / "train_gts"
    train_imgs = rctw_root / "train_images"
    rows = []
    if not train_gts.exists():
        return rows
    for gt in sorted(train_gts.glob("image_*.txt")):
        text = parse_train_gt_file(gt)
        if not text:
            continue
        intent, basis, conf = classify(text)
        img = train_imgs / gt.name.replace(".txt", ".jpg")
        if not img.exists():
            continue
        rows.append({
            "image": str(img.relative_to(rctw_root)),
            "auto_intent": intent,
            "auto_basis": _basis_str(basis),
            "confidence": round(conf, 3),
            "matched_text": text[:200],
            "human_override": None,
        })
    return rows


# ─── Test-set OCR + classify ──────────────────────────────────────────────

def scan_test(rctw_root: Path, limit: int | None = None,
              ocr_lang: str = "zh") -> list[dict]:
    """Iterate icdar2017rctw_test/image_*.jpg.  For each, call Huawei
    Cloud OCR via profiling/ocr_huaweicloud.recognize().  The
    `profiling/` dir is added to sys.path so the import resolves.
    Returns per-image intent rows."""
    test_dir = rctw_root / "icdar2017rctw_test"
    if not test_dir.exists():
        return []
    sys.path.insert(0, str(REPO / "profiling"))
    from ocr_huaweicloud import recognize, env_available, sdk_available
    if not sdk_available():
        print("[ERR] huaweicloudsdkocr not installed — pip install first",
              file=sys.stderr)
        sys.exit(1)
    if not env_available():
        print("[ERR] Huawei env vars missing — `set -a; . ~/.huawei_env; set +a`",
              file=sys.stderr)
        sys.exit(1)

    test_files = sorted(test_dir.glob("image_*.jpg"))
    if limit:
        test_files = test_files[:limit]
    rows = []
    t0 = time.time()
    for i, img_path in enumerate(test_files):
        try:
            blocks = recognize(img_path) or []
        except Exception as e:
            print(f"[warn] OCR fail {img_path.name}: {e}", file=sys.stderr)
            continue
        text = " ".join((b.get("text") or "").strip() for b in blocks)
        intent, basis, conf = classify(text)
        rows.append({
            "image": str(img_path.relative_to(rctw_root)),
            "auto_intent": intent,
            "auto_basis": _basis_str(basis),
            "confidence": round(conf, 3),
            "matched_text": text[:200],
            "human_override": None,
        })
        if (i + 1) % 100 == 0:
            elapsed = time.time() - t0
            print(f"  test [{i+1}/{len(test_files)}] "
                  f"elapsed={elapsed:.1f}s rate={(i+1)/elapsed:.1f}/s")
    return rows


def _basis_str(basis: list[str]) -> str:
    if not basis:
        return ""
    return f"matched={basis}"


# ─── IO ───────────────────────────────────────────────────────────────────

def write_index(rows: list[dict], out_path: Path,
                rctw_root: Path, train_count: int) -> None:
    counts = Counter(r["auto_intent"] for r in rows)
    doc = {
        "version": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "description": (
            "Per-image intent class for RCTW-171. "
            "effective_intent = human_override ?? auto_intent. "
            "Edit intent_corrections.json (don't edit this file)."
        ),
        "rctw_root": str(rctw_root),
        "counts": dict(counts),
        "items": rows,
    }
    out_path.write_text(json.dumps(doc, ensure_ascii=False, indent=2))
    print(f"wrote {len(rows)} entries to {out_path}")
    print(f"  train indexed: {train_count}")
    print(f"  test indexed:  {len(rows) - train_count}")
    for k in ("info", "location", "solve", "unknown"):
        if k in counts:
            print(f"  {k}: {counts[k]}")


def load_index(path: Path = INDEX_PATH) -> dict:
    if not path.exists():
        print(f"[ERR] index not found: {path}", file=sys.stderr)
        print("       run `build_intent_all.py build` first.",
              file=sys.stderr)
        sys.exit(1)
    return json.loads(path.read_text())


def apply_corrections(idx: dict, corrections_path: Path) -> dict:
    """Read intent_corrections.json and patch items[].human_override in
    place.  Effects are visible in subsequent `select` / `inspect`
    runs — does NOT rewrite intent_all.json (audit trail wants the
    auto-classifier output frozen)."""
    if not corrections_path.exists():
        return idx
    corr = json.loads(corrections_path.read_text())
    by_img = {e["image"]: e for e in corr.get("edits", [])}
    n_applied = 0
    for item in idx["items"]:
        c = by_img.get(item["image"])
        if c:
            item["human_override"] = c["intent"]
            n_applied += 1
    print(f"applied {n_applied} corrections from {corrections_path}")
    return idx


def effective_intent(item: dict) -> str:
    return item.get("human_override") or item["auto_intent"]


def select(idx: dict, intent: str, limit: int) -> list[dict]:
    """Highest-confidence items whose effective_intent == intent,
    ties broken by image-path stability."""
    pool = [it for it in idx["items"] if effective_intent(it) == intent]
    pool.sort(key=lambda it: (-it["confidence"], it["image"]))
    return pool[:limit]


# ─── CLI ──────────────────────────────────────────────────────────────────

def cmd_build(args) -> int:
    rctw_root = Path(args.rctw_root).resolve()
    if not (rctw_root / "train_gts").exists() or \
            not (rctw_root / "icdar2017rctw_test").exists():
        print(f"[ERR] {rctw_root} doesn't look like RCTW-171 "
              "(missing train_gts/ or icdar2017rctw_test/)",
              file=sys.stderr)
        return 1
    print(f"[train] scanning {rctw_root / 'train_gts'} ...")
    train_rows = scan_train(rctw_root)
    print(f"[train] indexed {len(train_rows)} images")
    if args.dry_run:
        print("[dry-run] skipping test OCR")
        test_rows = []
    else:
        print(f"[test]  scanning {rctw_root / 'icdar2017rctw_test'} via "
              "Huawei Cloud OCR ...")
        test_rows = scan_test(rctw_root, limit=args.test_limit)
        print(f"[test]  indexed {len(test_rows)} images")
    write_index(train_rows + test_rows, Path(args.out), rctw_root,
                train_count=len(train_rows))
    return 0


def cmd_apply_corrections(args) -> int:
    idx = load_index(Path(args.index))
    idx = apply_corrections(idx, Path(args.corrections))
    print(f"effective-intent counts after overrides:")
    eff = Counter(effective_intent(it) for it in idx["items"])
    for k in ("info", "location", "solve", "unknown"):
        if k in eff:
            print(f"  {k}: {eff[k]}")
    return 0


def cmd_select(args) -> int:
    idx = load_index(Path(args.index))
    idx = apply_corrections(idx, Path(args.corrections))
    picks = select(idx, args.intent, args.limit)
    print(f"[select] intent={args.intent} → {len(picks)} picks")
    out = Path(args.out)
    with out.open("w") as f:
        for it in picks:
            # Emit full image paths so downstream tools can resolve
            # directly without consulting the rctw_root field.
            abs_path = Path(idx["rctw_root"]) / it["image"]
            f.write(f"{abs_path}\t{it['confidence']:.3f}\t"
                    f"{effective_intent(it)}\n")
    print(f"wrote TSV → {out}")
    print(f"--fixtures \"$(awk '{{print $1}}' {out} | sed 's/.*\\///' | xargs)\"")
    return 0


def cmd_inspect(args) -> int:
    import random
    idx = load_index(Path(args.index))
    idx = apply_corrections(idx, Path(args.corrections))
    sample = random.sample(idx["items"], min(args.limit, len(idx["items"])))
    for it in sample:
        eff = effective_intent(it)
        print(f"  [{eff:8s}][{it['auto_intent']:8s}][conf={it['confidence']:.2f}]"
              f" {it['image']}")
        snippet = (it.get("matched_text") or "")[:80].replace("\n", " ")
        print(f"        text={snippet!r}")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0] if __doc__ else "")
    sub = p.add_subparsers(required=True)

    pb = sub.add_parser("build", help="Walk RCTW-171, OCR train+test, classify, write index.")
    pb.add_argument("--rctw-root", required=True)
    pb.add_argument("--out", default=str(INDEX_PATH))
    pb.add_argument("--test-limit", type=int, default=None,
                    help="Cap test-image count for fast iteration.")
    pb.add_argument("--dry-run", action="store_true",
                    help="Train only; skip test-image OCR (no network).")
    pb.set_defaults(fn=cmd_build)

    pc = sub.add_parser("apply-corrections",
                        help="Read corrections file, print effective counts.")
    pc.add_argument("--index", default=str(INDEX_PATH))
    pc.add_argument("--corrections", default=str(CORRECTIONS_PATH))
    pc.set_defaults(fn=cmd_apply_corrections)

    ps = sub.add_parser("select",
                        help="Pick top-N images for one intent, write TSV.")
    ps.add_argument("--intent", required=True,
                    choices=("info", "location", "solve"))
    # Default 20 = project-wide per-intent standard (set 2026-07-11).
    # Override --limit when you need a different count.
    ps.add_argument("--limit", type=int, default=20)
    ps.add_argument("--index", default=str(INDEX_PATH))
    ps.add_argument("--corrections", default=str(CORRECTIONS_PATH))
    ps.add_argument("--out", required=True)
    ps.set_defaults(fn=cmd_select)

    pi = sub.add_parser("inspect", help="Random sample for eyeball QA.")
    pi.add_argument("--limit", type=int, default=10)
    pi.add_argument("--index", default=str(INDEX_PATH))
    pi.add_argument("--corrections", default=str(CORRECTIONS_PATH))
    pi.set_defaults(fn=cmd_inspect)

    args = p.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
