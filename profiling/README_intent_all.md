# `intent_all` — Intent Index for RCTW-171

Source-of-truth index that maps every RCTW-171 image to an intent class
(`info` / `location` / `solve`). Built once, used forever.

## Files

| File | Role | Edit? |
|---|---|---|
| `profiling/intent_all.json` | Auto-built. **Single source of truth.** Auto-classified intent per image. | ❌ don't edit directly |
| `profiling/intent_corrections.json` | Human edits. Pure audit trail: `{image, intent, reason}`. | ✅ add entries here |
| `profiling/build_intent_all.py` | Builds `intent_all.json` from RCTW-171 (train GT + test OCR). | rebuild |

## Schema (`intent_all.json`)

```json
{
  "version": 1,
  "generated_at": "2026-07-11T...",
  "description": "...",
  "counts": { "info": N, "location": M, "solve": K, "unknown": U },
  "items": [
    {
      "image": "icdar2017rctw_train/train_images/image_42.jpg",
      "auto_intent": "location",
      "auto_basis": "matched=[店, 号] (text='金氏眼镜 创于1989 城建店')",
      "confidence": 0.85,
      "human_override": null
    }
  ]
}
```

**Effective intent** (computed at read-time, NOT stored) =
`item.human_override ?? item.auto_intent`

## Schema (`intent_corrections.json`)

```json
{
  "version": 1,
  "description": "Apply corrections on top of intent_all. Add entries; do not delete old ones unless typo.",
  "edits": [
    {
      "image": "icdar2017rctw_train/train_images/image_42.jpg",
      "intent": "info",
      "reason": "False positive on '创于1989' — '号' was matched inside a date string",
      "reviewer": "human",
      "timestamp": "2026-07-11T12:00:00Z"
    }
  ]
}
```

## Correcting a wrong entry

1. Find the offending line in `profiling/intent_all.json` (or `jq` query).
2. Add a new entry to `intent_corrections.json` — never modify `intent_all.json` directly.
3. Run `python3 build_intent_all.py apply-corrections` to verify the
   effective intent changes correctly.
4. (Optional) re-classify if many in a batch share the same false
   positive → edit the keyword list in `build_intent_all.py` and rebuild.

## Picking the test fixtures (per intent)

`build_intent_all.py select --intent=location --limit=10 --out=profiling/location_10.txt`

This emits a list of image paths, sorted by `confidence DESC`, that
the eval can pass via `--fixtures`. Picks the highest-confidence
auto-classified rows **unless** they have a non-null `human_override`
to a different intent — those are filtered out.

The same selector, applied to **all** registered intents, lets the
eval ramp test sets incrementally:
- v1 (now): 10 location fixtures → bring r3 signal to 10/100.
- v2: 10 solve fixtures when we have any.
- v3: +N info fixtures to detect regressions on info (the dominant class).

## Build pipeline

`build_intent_all.py build` does two passes:

1. **train (8034 images)**: parses `RCTW-171/train_gts/image_*.txt` —
   each line is `x1,y1,x2,y2,x3,y3,x4,y4,difficult,"text"`. Cheap, no
   network.
2. **test (4229 images)**: runs Huawei Cloud OCR on each. ~10 min
   wall-clock with naive serial, ~3 min with 8-way parallelism.

Both passes feed text through the same heuristic:

```
# Iterate over each text block, count keyword hits
location_keywords = [路, 街, 道, 巷, 号, 栋, 楼, 层, 室, 店, 商厦,
                     广场, 中心, 城, 镇, 园, 区, 村, 地铁, 公交,
                     站, 机场, 出口, 入口, 高速, 省, 市, 县, 方向,
                     东, 南, 西, 北, 米, km, 邮政编码]
solve_keywords    = [解, 方程, 公式, 求, 答案, 计算, 证明, 题,
                     =, ÷, ×, ∫, ∑]

if location_hits >= 2:                      → location
elif solve_hits >= 2 and location_hits == 0: → solve
elif (len(text_chars_total) > 30):          → info   (text-rich, default)
else:                                       → unknown (no usable signal)
```

Confidence = `min(1.0, (location_hits + solve_hits) / 5)`. Single
keyword = 0.20, multi = higher. Images with no usable text land in
`unknown` at confidence 0.0.

## Adding a new intent (future)

When `IntentDecl.kt` registers a new intent (e.g. `shopping` in
`ACT_ON` family):

1. Edit `build_intent_all.py`, add a `shopping_keywords = [...]`
   list and a fourth branch in the classifier.
2. `python3 build_intent_all.py rebuild` (or just `build`, the
   script always overwrites `intent_all.json`).
3. The intent selector picks top-20 (the per-intent standard) by
   default — see *Per-intent standard: 20 fixtures per intent*
   below.  No flag needed:
   `build_intent_all.py select --intent=shopping --out=shopping_20.tsv`
4. Annotate the top-20 fixtures by hand from
   `shopping_20.tsv` + `intent_all.json` into
   `ground_truth_shopping_20.json`.
5. Run `:shared:eval --ground-truth=ground_truth_shopping_20.json
   --img-dir=...` for the intent-suite regression check.

## Per-intent standard: **20 fixtures per intent** (2026-07-11)

User-set rule: each intent-suite starts with **20** fixtures.

### Why 20

- Big enough to expose per-fixture volatility (a regression on a single
  image moves the suite by 1/20 = 0.05).  Smaller (e.g. 10) hides
  regressions behind noise; bigger (e.g. 50) burns enough wall-time
  per iteration to discourage re-runs.
- Walk-time per suite ≈ 20 × 25 s/fix ≈ **8 min** on a typical
  Huawei Cloud connection.  Fits inside a focused iteration session.

### How to apply

```
# Adding a new intent (e.g. shopping in ACT_ON family):
python3 profiling/build_intent_all.py select --intent shopping \
    --out profiling/shopping_20.tsv                  # default limit = 20
# Annotate fixtures by hand (--src column tells you which intent_all
# row each one came from):
python3 -c "..."  # slice shopping_20.tsv into ground_truth_shopping_20.json
# Smoke:
gradle :shared:eval --offline --args="--limit 20 \
    --ground-truth profiling/ground_truth_shopping_20.json \
    --img-dir /home/oppry/RCTW-171/train_images"
```

If a fixture's OCR text in `intent_all.json` isn't enough to write
`must_have_in_scene_or_observation`, eyeball the image (open it in
the file browser).  Or annotate more keywords first and re-run
`select`.

### Computing confidence intervals

With 20 fixtures @ temp=0 the per-fixture standard deviation is
roughly 0.10 on `r3_actions` (since most fixtures are 0-or-1 with
rare misses).  A 0.05 swing between two commits is in noise; 0.10+
is a real signal.  Track it as a "regression threshold of 1 fixture"
at p≈50%.

## Eval-regime split

Two-track evaluation, run separately so each has a stable baseline:

| Track | What | When |
|---|---|---|
| **Baseline** | The existing 100 (or 20) fixtures — info-heavy by design. Composite weights `0.45 / 0.45 / 0.10`. | Default `:shared:eval` (no `--intent-suite`). |
| **Intent-suite** | Per-intent subsets pulled from `intent_all.json`. Composite isolates `action_recall` so per-intent action-prediction quality is visible. | `build_intent_all.py select --intent=$X --limit=N` + `:shared:eval --fixtures=... --json-out=eval_$X.json`. |

The two composites are NOT averaged into one number — they measure
different things and would mask per-intent regressions if combined.
