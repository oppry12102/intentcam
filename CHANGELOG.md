# Changelog

All notable changes to IntentCam will be documented in this file.

## [unreleased] — 2026-07-12

### Added (eval infrastructure)

- **Regression net** (`bdf3343`): `profiling/baselines.json` 5-suite
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

### Verified (data)

- **phone_60 baseline** (`4e97879`): composite **0.9567** — matches phone_20
  (0.9575) within ±0.0008 noise. 11 categories. 0 errors.
- **pii20_60 baseline** (`d2d36e3`): composite **0.9675** — Δ -0.0113 vs
  pii_20 (0.9788), within noise. 19 categories. 0 errors.
- **direction_arrow_60 baseline** (`1524526`): composite **0.9337** full /
  **0.9549** clean (52-fixture, excluding 2 API errors: image_7162 90s
  LLM timeout + image_3798 HTTP 500 sensitive-image rejection).
  3 categories. Threshold band [0.88, 0.98].

### Baselines

`profiling/baselines.json` now tracks **8 suites**:

| Suite | Composite | n | Errors |
|---|---:|---:|---:|
| phone_20 | 0.9575 | 20 | 0 |
| phone_60 | 0.9567 | 60 | 0 |
| pii_20 | 0.9788 | 20 | 0 |
| pii20_60 | 0.9675 | 35 | 0 |
| direction_arrow_20 | 0.9850 | 20 | 0 |
| direction_arrow_60 | 0.9337 | 54 | 2 |
| phaseG_15 | 0.973 | 15 | 0 |
| rctw_20_sanity | 0.9202 | 20 | 0 |

### Not changed

- No app code changes in this batch. Latest APK remains
  `intentcam.apk` from commit `656aed1` (Phase G + Phase H v2 + Pass 1b').
- No new verifier rules, intents, or actions.

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