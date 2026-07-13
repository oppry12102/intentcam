# Changelog

All notable changes to IntentCam will be documented in this file.

## [unreleased]

## [2026-07-13] — Type/intentFocus refactor + Phase I + local OCR backend

This release batch covers 4 feature threads plus eval infrastructure:
(1) drop dead `intentFocus` field + per-family UI accent,
(2) Phase I 13th intent `service_institution` + Pass 12 verifier,
(3) OCR backend swap from Huawei Cloud to local PP-OCRv4 mobile,
(4) eval regression net + 60-fixture GT scaling.

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

### Final baselines (9 suites, 2026-07-13 — local OCR backend)

| Suite | Composite | n | Notes |
|---|---:|---:|---|
| phone_20 | **0.944** | 20 | local OCR baseline @`144ba61` |
| phone_60 | **0.9200** | 55 | post-Phase-I v5, GT reclass v2 |
| pii_20 | **0.929** | 18 | local OCR baseline @`144ba61` |
| pii20_60 | **0.9521** | 18 | post-Phase-I v4 |
| direction_arrow_20 | **0.974** | 20 | local OCR baseline @`144ba61` |
| direction_arrow_60 | **0.9694** | 20 | post-Phase-I v3 |
| service_institution_60 | **0.9664** | 63 | Phase I NEW |
| phaseG_15 | 0.973 | 15 | pre-PP-OCRv4 (Huawei Cloud ref) |
| rctw_20_sanity | 0.9202 | 20 | pre-PP-OCRv4 (Huawei Cloud ref) |

`profiling/baselines.json` now tracks all 9 suites with
`baseline_commit` field pointing to the measurement-point commit
(`144ba61` for local-OCR suites, older for pre-PP-OCRv4 refs).
Pre-PP-OCRv4 numbers are retained as `*_huawei_cloud_ref` entries
for historical reference only — do NOT use for regression checks.

### Not changed

- No APK bump in this release batch. Latest APK remains the 16.7 MB
  build from commit `656aed1` (2026-07-12: Phase G + Phase H v2 + Pass 1b').
  Next APK ship will bundle the per-family UI accent refactor.
- Verifier still 10-pass + 2 post-guard (Pass 11 / Pass 12 in `IntentVerifier.kt`).

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