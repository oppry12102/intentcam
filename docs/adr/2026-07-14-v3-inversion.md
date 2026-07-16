# 2026-07-14 — v3.0 inversion: LLM authoritative, IntentVerifier retired

## Status
Accepted. Shipped in 6 phases (A→F) over 2026-07-14.

## Context

Through Phase J (2026-07-13), the recognition pipeline had:
- LLM emits a `type` + `action_ids`
- `IntentVerifier.kt` (513 lines, 13 regex passes + post-guard)
  silently overwrites `bubble.type` when a strong out-of-family
  signal fires (e.g. `info` + 400 number → `phone`)
- Verifier then auto-injects the new type's canonical action via
  `actionFor()` map (Phase F invariant: never delete LLM's
  `proposedActions`, only add)

This worked but had three structural problems:
1. **Two sources of truth** for `bubble.type` (LLM emit +
   verifier override).  Regression diagnosis was hard — which
   pass went wrong?
2. **r2_type lift and r2_text lift entangled** — a verifier
   flip would silently shift r2_type without the model doing
   anything wrong.
3. **Verifier maintenance burden** — every new intent needed
   a new pass + lockstep with the eval scorer's
   `defaultActionIds`.

## Decision

**Delete `IntentVerifier.kt`** (commit 59c1128).  The LLM is
authoritative for both `type` and `action_ids`:

- **LLM-emit path is the only path.**  ToolUseLoop uses
  `tb.type` directly (no override) and passes
  `tb.proposedActions` verbatim through to
  `Bubble.llmProposedActions`.
- **System prompt's C3 v3 type→action table** is the prompt-side
  guidance so the LLM emits the right `action_ids` from start.
- **`r_type` reformulated** to graded partial credit (exact 1.0 /
  same family 0.7 / cross-family 0.3 / empty·unknown 0.0) instead
  of verifier-corrected.
- **Composite_v2** formula replaces legacy composite:
  `composite_v2 = 0.40·r_actions_recall + 0.30·r_inputs_complete
  + 0.15·r_rounds_efficiency + 0.10·r_intent_derived + 0.05·r_text`
  — later updated to action-first weights in 2026-07-15.

### Trade-offs accepted

- **Phone suites lift** (+0.05 to +0.08 across phone_60/pii20_60):
  LLM picks `dial_number` without the verifier crutch.
- **OBSERVE-family + PII clusters drop** (-0.10 to -0.37):
  LLM is less reliable at emitting the canonical `share`
  action for OBSERVE bubbles.  Mitigated by C3 v3 prompt table;
  remaining gap is a planned follow-up to re-introduce a soft
  system-prompt hint.

### What stayed

- **`ActionOrchestrator`** (validateInputs + shouldFinalize)
  — pure boundary checker for per-emit input validation + missing-
  input nudges.  Lives in `app/` because it closes over the
  Android-coupled `ActionRegistry`.
- **`ActionResolver`** — filters `llmProposedActions` against
  applicability + enabled set to populate `bubble.actions`.
- **2-register lockstep** invariant for new intents.

## Consequences

- ~530 lines deleted (`IntentVerifier.kt` + `eval/ScorerV2.kt`
  legacy composite path).
- Regression diagnosis cleaner: r2_type moves with r2_text (both
  driven by the LLM).
- Planned follow-up: re-introduce the canonical mapping as a
  soft system-prompt hint once LLM behavior stabilizes on the
  v3 inversion.

## Migration

- `IntentDecl` + `ActionDecl` 2-register lockstep replaces the
  old 3-register lockstep (IntentVerifier dropped).
- Eval pipeline reads `bubble.type` directly (no verifier override
  to undo).
- `ScorerV2` becomes the canonical scorer; `ScorerV3` runs
  side-by-side (informational only until sign-off).