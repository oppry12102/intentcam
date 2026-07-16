# 2026-07-12 — PP-OCRv4 local OCR backend swap (cost optimization)

## Status
Accepted. Shipped 2026-07-13.

## Context

Eval pipeline OCR was running on **Huawei Cloud OCR** (via
`JvmHuaweiCloudOcrEngine.kt`).  Per-call cost was unsustainable
for the iterative eval cadence (~50 fixture runs/day across
suites).  Each call also carried 200-400ms latency round-trip.

## Decision

Swap to local **PP-OCRv4 mobile** (`paddleocr==2.7.3`, 12 MB
model, ~2.4 s/img CPU on reference hardware) at
`/home/oppry/work/pp_ocrv4_mobile_engine`.  Eval invokes via
long-lived `profiling/pp_ocrv4_runner.py` JSON-RPC subprocess
(referenced, NOT imported into the Kotlin build).

**Cascade** (3 tiers):
1. Local PP-OCRv4 — primary, default
2. Huawei Cloud — fallback when local init fails (env vars
   `HUAWEICLOUD_SDK_{AK,SK,PROJECT_ID}`)
3. Blind — no OCR; eval still runs (round-1 hint is empty)

`--backend local|huawei|blind` flag forces a tier for ablation.

## Consequences

- Per-call cost → $0.
- First run takes 5-30 s for PaddleOCR to load weights;
  subsequent calls reuse the cached engine.
- Eval baseline numbers pre-2026-07-13 are **reference only** —
  local OCR differs from Huawei Cloud at the noise floor:
  - `phone_20` Huawei 0.9575 → local 0.944 (-0.013 noise)
  - `pii20_60` Huawei 0.9675 → local 0.929 mobile / 0.940 server
  - `direction_arrow_60` Huawei 0.9694 → local 0.974 (+0.005)
- EvalRunner keeps Huawei backend (`JvmHuaweiCloudOcrEngine.kt`)
  intact as the cascade's tier 2.

## Migration

- `profiling/pp_ocrv4_runner.py` is the eval entry point
  (long-lived JSON-RPC subprocess).
- `shared/eval/EvalRunner.kt` calls it via `OcrEngine.recognize()`.
- Pre-2026-07-13 numbers in `profiling/baselines.json` retained
  for historical reference.