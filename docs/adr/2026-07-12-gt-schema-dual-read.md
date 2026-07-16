# 2026-07-12 — GT schema dual-read (expected_top_intent_type)

## Status
Accepted. Shipped 2026-07-12 (eval-only fix, measurement bug).

## Context

New intent-diverse ground truth files (pii_20, phone_20,
phaseG_15) used the schema field `expected_top_intent_type` to
describe the canonical intent id.  The older RCTW-171 ground
truth (8034 images, unchanged for backward compat) used
`expected_type`.

`EvalRunner.kt` only read `expected_type` — silently falling
back to `IntentRegistry.FALLBACK_ID = "info"` for any suite that
authored under the new schema.  Result: every fixture in the
new suites was scored as `info`, dragging `r_type` to 0.5
systematically across the whole suite.  The model was correct;
the measurement was broken.

`pii_20 @20 = 0.8794` looked like a -0.02 regression vs the
0.90 baseline — actually a measurement bug.

## Decision

Eval-side fix only (no model / prompt change):

```kotlin
val expectedType = scene.optString(
    "expected_type",
    scene.optString("expected_top_intent_type", IntentRegistry.FALLBACK_ID),
)
```

Read `expected_type` first (RCTW backward compat), fall back to
`expected_top_intent_type` (new suites), fall back to FALLBACK_ID.

## Consequences

- `pii_20` 0.8794 → **0.9631 (+0.0837 cumulative)**, of which
  ~80% came from image_3285 alone (was scored as `info` →
  `real_estate_rental`, +0.225 on that fixture).
- RCTW @100 unchanged (RCTW uses `expected_type` correctly).
- New ground truth MUST use `expected_top_intent_type`.  Old
  field kept for RCTW backward compat.

## Migration

None — eval-only fix, no app changes.