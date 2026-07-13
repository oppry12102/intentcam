# Changelog

All notable changes to IntentCam will be documented in this file.

## [unreleased]

## [2026-07-14a] — real_estate_rental llmHint broadening + diagnostic tooling

Single-feature release shipping a real_estate_rental recognition
improvement plus a server-CPU diagnostic script. Eval infrastructure
changes are also bundled here for chronological clarity.

### Improved — real_estate_rental llmHint broaden (`1a2d393`)

The llmHint for the 13th intent (real_estate_rental) was too
narrow:

```
before:  租房：出租 / 二手房 / 房源 / 中介
after :  房地产：出租 / 出售 / 二手房 / 楼盘 / 户型 / 平米 / 急售
              / 吉房 / 中介 / 物业
```

The new list is intentionally bounded to the verifier's REAL_ESTATE
regex tokens (`IntentVerifier.kt:73`) — expansion stays
"consume-the-corpus" rather than "loosen-the-guard". That keeps
Pass 7's `real_estate_rental + MOBILE + !REAL_ESTATE → phone`
guard from misfiring on tokens that are NOT in the verifier's
canonical real-estate vocabulary.

**Effect on `real_estate_rental_11`**: composite **0.938 → 0.923** as
the new post-hint baseline (Δ=-0.015 within noise — first
12-fixture hint-test hit 0.992, but that included the now-removed
`image_572` fixture + a lucky `image_1956` LLM first-pass; current
0.923 is the honest post-hint 11-fixture measurement). 8/11 perfect,
3/11 partial at r2_type=0.5 (LLM variance).

### Fixed — `image_572` GT retype OUT of real_estate_rental (`1a2d393`)

`image_572` (世界城营销中心 + 70平米 + 收租150万) was originally in
the suite, but its dominant image content is a 公安治安监控
(public-security surveillance sign) with police emergency phones
(110 + 027-85393898). Real-estate ad is a background billboard.
Re-typed to `phone` / `dial_number`. Suite renamed `_12 → _11`,
limit 12 → 11.

### Added — server-CPU diagnostic script (`80d3453`)

`scripts/diagnose_pii_ocr.sh <suite>` runs the same suite under
both PP-OCRv4 backends (mobile + server-CPU) and prints per-fixture
composite deltas. Classifies each fixture as "OCR-bound"
(server recovers ≥ 0.05) vs "LLM-bound" (server doesn't help).
Writes `profiling/regression/pii_ocr_diff_<ts>.json`. Wall-time
~12 min serial; intended for triage when a fixture is stuck below
the regression threshold.

### APK artifacts

| Variant | Path | Size | mtime |
|---|---|---|---|
| Debug | `/home/oppry/work/app3/intentcam.apk` | 25.4 MB | 2026-07-14 05:02 |
| Release | `/home/oppry/work/app3/intentcam-release.apk` | 16.7 MB | 2026-07-14 05:02 |

Both built post-commit `97eea08` so the broadened llmHint ships.

## [2026-07-13b] — r3 verifier fix + shopping_promo GT curation

This is a single-feature mini-release that ships a verifier correctness
fix and one GT correctness fix surfaced by the post-r3 regression net.
Eval infrastructure (regression scripts, baseline files, GT-data fixes
already shipped in 6/13 of the commits) is **not** included in this
release — only changes that affect the on-device APK.

### Fixed — IntentVerifier canonical-action injection for missing-canonical case (`355c001`)

Phase F (2026-07-11) had scoped the verifier's canonical-action
injection to type-flip only: when the LLM picked a different type than
the verifier's pick, the verifier would inject the canonical action;
otherwise it left the LLM's proposal untouched. This protected r3 as a
"real model-behavior signal" for the non-flip majority.

But it broke for **new intents** where the LLM has no prior and emits
its own heuristic — e.g. for Phase J's `shopping_promo`, the LLM emits
`dial_number` (because the sign has a phone number) and ignores the
type→canonical mapping. The new condition injects the canonical action
whenever it isn't already in the LLM's proposal, covering both the
type-flip case and the missing-canonical case.

Validated by the 8-suite post-r3-fix regression net (`e85ec64`,
`1cd318e`, `7489945`):

| suite | pre-fix | post-fix | Δ |
|---|---:|---:|---:|
| shopping_promo_20 | 0.901 | **0.943** | +0.042 |
| direction_arrow_20 | 0.974 | 0.995 | +0.021 |
| direction_arrow_60 | 0.969 | 0.990 | +0.021 |
| pii20_60 | 0.952 | 0.964 | +0.012 |
| service_institution_60 | 0.970 | 0.977 | +0.006 |
| phone_60 | 0.920 | 0.918 | -0.002 |
| phaseG_15 | 0.973 | 0.959 | -0.014 (within noise) |

### Fixed — shopping_promo GT false-positive `image_3533` (`99b5e90`)

`image_3533` (万达利眼镜 店招 / 鲁巷广场) was auto-scaled into
`shopping_promo_20` by `scale_fixtures.py` on a "大优惠" hit on the
digital marquee scroll, but the dominant signal is address-level
location. LLM has consistently classified it as `location` /
`open_in_maps` in 3 prior runs at composite 0.90 (r1=1, r2_type=1,
r3=0). Re-curated to `location` / `open_in_maps`. After the r3 fix
shipped 4 of 5 false-positive GT entries in this file reached
composite 1.00; `image_3533` stood out as the lone GT-side error and
is now closed.

Cross-references: `f017733` retyped the prior 2 GT false-positives
(`image_2562` → `real_estate_rental`, `image_2898` →
`service_institution`); `99b5e90` closes the loop on the third.

## [2026-07-13] — Type/intentFocus refactor + Phase I + Phase J + local OCR backend

This release batch covers 5 feature threads plus eval infrastructure:
(1) drop dead `intentFocus` field + per-family UI accent,
(2) Phase I 13th intent `service_institution` + Pass 12 verifier,
(3) **Phase J 14th intent `shopping_promo` + Pass 13 verifier**,
(4) OCR backend swap from Huawei Cloud to local PP-OCRv4 mobile,
(5) eval regression net + 60-fixture GT scaling.

### Added — Local PP-OCRv4 OCR backend (replaces Huawei Cloud as primary) (`25d2453`, `1c3db15`)

Huawei Cloud OCR's per-call cost made the eval pipeline unsustainable.
Replaced the JVM eval OCR backend with the local on-prem PP-OCRv4 mobile
engine (`pp_ocrv4_mobile_engine`, PaddleOCR 2.7.3, 12 MB model,
~2.4 s/img CPU). Cascade: local PP-OCRv4 → Huawei Cloud fallback → blind.
Subprocess is a long-lived stdin/stdout JSON-RPC bridge
(`profiling/pp_ocrv4_runner.py`) to amortize the 5–30 s PaddleOCR init
across the full eval run.

**All prior eval numbers (Huawei Cloud) are reference-only.** New
local-OCR baselines:

| Suite | Composite | n | Δ vs Huawei Cloud |
|---|---:|---:|---:|
| phone_20 | **0.944** | 20 | -0.0135 (noise) |
| pii_20 | **0.929** | 18 | -0.0231 (noise, OCR-sensitive) |
| direction_arrow_20 | **0.974** | 20 | +0.0046 (noise) |

Server-CPU model (450 MB, 27 s/img) measured `pii_20=0.940` (+0.011 over
mobile) — not default; tracked as best-effort reference via
`LOCAL_OCR_KIND=server`.

### Phase I — `service_institution` intent (13th intent, OBSERVE) (`9226652`, `0f62858`)

- **13th intent**, OBSERVE family. Targets RCTW's 514-image
  service_institution cluster (rank #5 in `scan_intents.py`).
  LLM hint: "公共机构：医院 / 学校 / 政府机关 / 银行 / 邮局 /
  法院 / 派出所 / 大大使馆".
- **5-file pure-add architecture** (mirrors Phase G/H pattern):
  1. `IntentDecl.kt` — register `service_institution`.
  2. `app/ActionDecl.kt` — widen `open_in_maps.applicableIntents`
     + `copy_hours.applicableIntents` to include `service_institution`.
  3. `IntentVerifier.kt` — `SERVICE_INSTITUTION` regex (32 institution
     keywords, v2 tightened: dropped 邮政/邮局 + 工商局/税务局/市场监督
     for false-positive avoidance) + **Pass 12** verifier rule
     (info | location source + SERVICE_INSTITUTION → flip) +
     `actionFor("service_institution")` → "open_in_maps".
  4. `ToolImplementations.kt` — C3 v3 prompt table row 13:
     service_institution → open_in_maps.
  5. `scripts/scale_fixtures.py` — service_institution_20 entry.

- **service_institution_60 baseline** (`016b6bd`): composite
  **0.9508** (full 60-fixture) / **0.9608** (clean 59-fixture,
  excluding 1 API error image_6117 HTTP 500). 5 categories.

### Phase I follow-up — dedup bug fix + regression check + GT reclassification (`838d012`, `5193da1`, `1e1ac40`, `ad035a6`, `8b977be`, `14bd454`, `4444230`)

- **`scale_fixtures.py` duplicate bug fix** (`838d012`):
  `find_candidates` compared full image path "train_images/image_X.jpg"
  against `exclude` set containing just "image_X" → exclusion never
  matched → auto-scaled fixtures duplicated seed IDs (18 dups in
  phone_60). Fixed by `Path(it["image"]).stem` comparison. Real GT
  shapes after fix:
  - `phone_60` 60 unique (unchanged total, deduped)
  - `pii20_60` 20 unique (was 35; corpus ceiling hit — only 20
    real_estate_rental fixtures in intent_all.json)
  - `direction_arrow_60` 20 unique (was 54; corpus ceiling)

- **Phase I regression on phone_60 / pii20_60** (`ad035a6`):
  clean-GT post-Phase-I evals showed composite drops:
  - phone_60: 0.9567 → **0.9179** (-0.0387)
  - pii20_60: 0.9675 → **0.9356** (-0.0319)
  - direction_arrow_60: 0.9337 → 0.9694 (+0.0357, no regression)
  Per `feedback-investigate-before-revert` both conditions met
  (≥2 fixtures sharing root cause + net < -0.03). Decision: KEEP
  framework (option B) — Pass 12 verifier working as designed,
  regression root cause = GT outdated (institution signs were
  GT=phone pre-Phase-I). Real fix = GT reclassification (option C,
  ships in this commit).

- **Phase I GT reclassification** (`1e1ac40`, `8b977be`):
  `scripts/reclassify_phase_i.py` moves fixtures that the LLM
  correctly classifies as `service_institution` (per the new llmHint)
  out of phone_60 / pii20_60 and into service_institution_60:
  - image_2905 (萌乐园少儿托管中心) from phone_60
  - image_2372 (朱铁生西医外科诊所) from pii20_60
  - image_2540 (彭春阳西医内科诊所) from pii20_60
  - v2: image_1882 / 6636 / 7296 / 7376 (4 more institution fixtures)

  Re-run on reclassified GTs:

  | Suite | v3 (before reclass) | v4 (after reclass) | v5 (after reclass v2) |
  |---|---:|---:|---:|
  | phone_60 (60→55 fx) | 0.9179 | **0.9307** | **0.9200** |
  | pii20_60 (20→18 fx) | 0.9356 | **0.9521** | — |
  | service_institution_60 (60→63 fx) | 0.9508 | **0.9664** | — |

### Added (eval infrastructure)

- **Regression net** (`bdf3343`): `profiling/baselines.json` 9-suite
  manifest + `scripts/run_regression.sh` bash driver + `scripts/check_regression.py`
  comparator. Auto-runs every suite back-to-back, exits non-zero when any
  composite drops by ≥ 0.05 absolute from baseline. Above the ±0.03 LLM
  variance floor; below 0.05 the alert would false-positive on API noise.
- **GT fixture scaling 20→60** (`7be4961`): `scripts/scale_fixtures.py` +
  three 60-fixture GT files. `phone_60` clean (60 fixtures from 645
  phone-cluster); `pii20_60` corpus-limited (35 from 131 real_estate_rental
  after dedup); `direction_arrow_60` corpus-limited (54 from 895 cluster
  after regex stricter than scan_intents).
- **Hand-curated 7 fixtures per scaled suite** (`4277516`):
  `scripts/curate_fixtures.py`. Refined auto-assigned placeholder categories
  + filled `must_have_in_scene_or_observation` with concrete identifiable
  strings (phone numbers, addresses, directional text).
- **60-fixture baselines (pre-Phase-I, v4 GT shapes)** (`bbd8771`, `4e97879`, `d2d36e3`, `1524526`):
  - phone_60 baseline (pre-Phase-I): **0.9617**
  - pii20_60 baseline (pre-Phase-I): **0.9582**
  - direction_arrow_60 baseline (pre-Phase-I): **0.9488**

### Refactored — Drop dead `intentFocus` field + per-family UI accent (`9a24f5b`)

- **Drop dead `intentFocus` field**: the LLM-facing `intent_focus` prompt
  field (a free-form image-region description, never parsed by
  `emit_bubble`'s body) and the corresponding `Bubble.intentFocus` are
  removed end-to-end — schema, prompt, `Models.kt`, `ToolUseLoop.kt`,
  `MainActivity.kt`, `ARCHITECTURE.md`. `Bubble.type` already carries
  the precise intent id from the `IntentRegistry`; the second field was
  always null and never consumed. IntentChip label is now `bubble.type`
  directly.
- **Per-family UI accent**: bubble card + detail screen + intent chip now
  color by `IntentFamily` with two important-intent overrides.
  OBSERVE → blue, ACT_ON → orange, `location` keeps its green anchor,
  `phone` / `payment_qr` get a pink accent so high-priority tap actions
  are visually distinct. Implementation: new `bubbleAccent(type, registry)`
  helper in MainActivity; `AppViewModel.intentRegistry` exposed as `val`
  (was private) so the feed can resolve family without owning a registry.
  Unregistered / unknown types render gray — fail-loud instead of
  silently pretending to be OBSERVE.

### Fixed — Bubble card overflow + drop 'via <tool>' debug pill (`9b42856`)

- Bubble card text overflow on long raw_content (>1 line on narrow screens)
- Removed the `via <tool>` debug pill from the bubble card — visual noise
  with no signal value in production

### Verified (data) — post type-refactor PP-OCRv4 re-run (`77c771f`)

Re-ran all three local-backend suites at commit `144ba61` to anchor
the refactor against the new architecture (UI color migration is
structural and does not enter scoring, so any Δ is variance):

- **phone_20** at commit `144ba61`: composite **0.917**
  (Δ=-0.027 vs baseline 0.944, within ±0.03 LLM variance band;
  0 contamination, 0 Outcome.Error). Baseline value unchanged.
- **pii_20** at commit `144ba61`: composite **0.923**
  (Δ=-0.006 vs baseline 0.929, within noise). Baseline value unchanged.
- **direction_arrow_20** at commit `144ba61`: composite **0.975**
  (Δ=+0.001 vs baseline 0.974, within noise). Baseline value unchanged.

All three suites stay anchored at the PP-OCRv4 local baseline; no
regression per `feedback-529-contamination-awareness` (Δ ≤ ±0.03 +
zero contamination ⇒ variance, not regression). `profiling/baselines.json`
`baseline_commit` fields updated to `144ba61` to reflect the
post-refactor measurement point.

### Final baselines (10 suites, 2026-07-13 — local OCR backend)

| Suite | Composite | n | Notes |
|---|---:|---:|---|
| phone_20 | **0.944** | 20 | local OCR baseline @`144ba61` |
| phone_60 | **0.9200** | 55 | post-Phase-I v5, GT reclass v2 |
| pii_20 | **0.929** | 18 | local OCR baseline @`144ba61` |
| pii20_60 | **0.9521** | 18 | post-Phase-I v4 |
| direction_arrow_20 | **0.974** | 20 | local OCR baseline @`144ba61` |
| direction_arrow_60 | **0.9694** | 20 | post-Phase-I v3 |
| service_institution_60 | **0.9664** | 63 | Phase I |
| **shopping_promo_20** | **0.918** | 20 | **Phase J NEW** (r3_actions=0.350 — see Phase J section) |
| phaseG_15 | 0.973 | 15 | pre-PP-OCRv4 (Huawei Cloud ref) |
| rctw_20_sanity | 0.9202 | 20 | pre-PP-OCRv4 (Huawei Cloud ref) |

`profiling/baselines.json` now tracks all 10 suites with
`baseline_commit` field pointing to the measurement-point commit
(`144ba61` for local-OCR suites, older for pre-PP-OCRv4 refs).
Pre-PP-OCRv4 numbers are retained as `*_huawei_cloud_ref` entries
for historical reference only — do NOT use for regression checks.

### Phase J — `shopping_promo` intent (14th intent, OBSERVE) (`6f87e00`)

- **14th intent**, OBSERVE family. Targets RCTW's 351-image
  `shopping_promo` cluster (rank #7 in `scan_intents.py` — highest
  un-shipped intent cluster). LLM hint covers 13 keywords:
  `特价 / 促销 / 优惠 / 打折 / 满减 / 秒杀 / 亏本 / 清仓 / 甩卖 /
  红包 / 抵用券 / 代金券 / 限时 / 抢购 / 直降`. Maps to new
  `copy_promo` action (share-sheet, mirrors `copy_menu` plumbing).
- **5-file pure-add architecture** (mirrors Phase G/H/I):
  1. `IntentDecl.kt` — register `shopping_promo`.
  2. `app/ActionDecl.kt` — new `copy_promo` action (cap 600 chars).
  3. `IntentVerifier.kt` — **Pass 13** PROMO regex + 2 guards:
     - `!REAL_ESTATE` — prevent 二手房急售 转让 mis-fire on Phase B
     - `!MENU` — prevent 今日特价 mis-fire on menu_food (Phase G)
     + `actionFor("shopping_promo")` → "copy_promo".
  4. `ToolImplementations.kt` — C3 v3 prompt table row 14:
     shopping_promo → copy_promo.
  5. `eval/EvalRunner.kt` — `copy_promo` added to `defaultActionIds`
     (3rd lockstep site).
  6. `scripts/scale_fixtures.py` — `shopping_promo_20` entry with
     5 sub-categories (price_discount / sale_promotion / coupon_voucher
     / flash_sale / clearance) + fallback `general_promo`.

- **shopping_promo_20 baseline** (`6f87e00`): composite **0.918**
  (20-fixture, local OCR, 0 contamination, 0 Outcome.Error).
  r2_text fuzzy=1.000, r2_type=1.000 (classification perfect).
  Per-category: general_promo 0.967 / sale_promotion 0.923 /
  coupon_voucher 0.922 / price_discount 0.900 / flash_sale 0.844.

- **Known follow-up: r3_actions=0.350** — pre-existing EvalRunner
  wiring gap: `EvalRunner.orchestrator = ToolUseLoop(...)` does not
  pass `actionIds` parameter, so the system prompt tells the LLM
  "actions ∈ {}（暂无动作可选；emit_bubble.action_ids 留空即可）".
  C3 v3 inline table is the only signal for new actions; Lift
  opportunity for future r3-only Phase. r2_type=1.00 confirms
  classification is perfect; the gap is purely action emission.

### Not changed

- No APK bump in this release batch. Latest APK remains the 16.7 MB
  build from commit `656aed1` (2026-07-12: Phase G + Phase H v2 + Pass 1b').
  Next APK ship will bundle the per-family UI accent refactor.
- Verifier now 10-pass + 3 post-guard (Pass 11 / Pass 12 / **Pass 13** in `IntentVerifier.kt`).

## [2026-07-12] — Phase H v2 + Pass 1b' + APK ship

### Added

- **Phase H route_to intent** (`dc7c380`): 12th intent id, OBSERVE family.
  LLM hint covers arrows / 方位词 / 距离短语 / 出口入口 markers. Verifier
  Pass 11 (info + DIRECTION_ARROW → route_to) + open_in_maps.applicableIntents
  widened to include route_to. Pure-add architecture; regression risk LOW.
- **Pass 1b' LANDLINE → phone** (`ff39451`): post-guard option (a) shipped.
  Activates LANDLINE regex in Pass 1 location-source rule; lifts image_1359
  and 5 other landline fixtures.

### Fixed

- **Phase H v2 verifier** (`6f0cd1b`): loosen DIRECTION_ARROW distance
  regex `.{0,4}` → `.{0,8}` (catches 行走三十步); add Pass 11
  real_estate_rental source with `!REAL_ESTATE` guard (recovers image_6423).

### Verified

- **direction_arrow_20 v2 = 0.9850** (commit `6f0cd1b`, +0.0263 vs v1).
- **phone_20 = 0.9575** history-high (commit `656aed1`).
- **pii_20 = 0.9788** (+0.0157 cumulative, commit `fc1cae2`).
- **RCTW @20 sanity = 0.9202** (no regression, commit `bfd7a47`).
- **APK shipped** at commit `656aed1` — 16.7 MB intentcam.apk.

## Earlier history

See git log. Key milestones:
- 2026-07-10: v1.3 ship (composite 0.9391)
- 2026-07-08: Phase 2 architecture + r3 weight added
- 2026-07-06: post-C5 + ImageOps baseline 0.67-0.70