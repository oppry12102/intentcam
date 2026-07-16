# 2026-07-11 — C3 v3 type→action table in system prompt

## Status
Accepted. Shipped 2026-07-11, commit `668ec6f`.

## Context

Through Phase A (IntentDecl + ActionDecl ship), the system
prompt asked the model to fill `action_ids` via a soft one-line
nudge ("默认应填 action_ids") — but r3 (action recall) was
still inconsistent across intent types.  Phone bubbles got
`dial_number` ~75% of the time; location bubbles got
`open_in_maps` only when the model remembered the nudge.

Earlier attempts at verbose type descriptions in the prompt
were rejected (composite -0.035; verbosity distracted OCR).

## Decision

Replace the soft nudge with an **explicit type → action mapping
table** in `LlmClient.TOOL_USE_SYSTEM` Step 2:

```
phone               → dial_number
real_estate_rental  → share
recruit_hiring      → share
id_document         → redact_id
payment_qr          → scan_to_pay
location            → open_in_maps
warning_safety      → share
menu_food           → share
hours_schedule      → share
service_institution → share
shopping_promo      → share
route_to            → open_in_maps
```

(`info`, `solve` carry no canonical action — no entry.)

The table **mirrors the verifier's `actionFor()` map exactly** so
the prompt-side and plumbing-side (later retired in v3.0)
agreed.

## Consequences

- `pii_20 @20` 0.8644 → **0.8794 (+0.015)** — 3+ fixtures
  real-lift, no r2_type regression.
- Later extended (Phase G/H/I/J) to 9 → 11 → 13 → 14 rows
  matching new intents.
- After v3.0 inversion (2026-07-14), this table became the
  **sole** source of truth for the model's intent→action
  guidance — verifier retired, no plumbing-side injection.

## Migration

None — prompt-only change.  Eval prompts load the same
`TOOL_USE_SYSTEM` template.