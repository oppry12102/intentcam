# 2026-07-15 — v4 action-first composite + UI accent

## Status
Accepted. Shipped 2026-07-15.

## Context

The v3.0 inversion (2026-07-14) made the prompt action-driven
(`action_ids` default-required, free-form `intent`/`type`), but
the scorer still anchored on a 14-bucket `r_type` partial-credit
grade.  The 2026-07-15 `072af4d` action merge (six per-intent
share actions collapsed into one intent-agnostic `share`)
empirically confirmed that most intents resolve to the same
`share` chip — the 14-bucket type boundary was fuzzy in a way
that didn't matter to the user's actual outcome.

The v3 inversion was a **half-flip** — the prompt was
action-driven but the scorer was still type-driven.  Scoring
the LLM-emitted type with graded partial-credit family
membership was misleading when the user's actual outcome
depends on the chip surface, not the type label.

## Decision

Three coordinated changes:

1. **`ScorerV3` (informational dual-run)** — new canonical
   composite weighing action coverage highest:
   `composite_v3 = 0.55·r_actions + 0.30·r_text + 0.15·r_inputs`.
   `r_type` drops from the scorer dimension; `Bubble.type` stays
   as a UI accent input only.
2. **`EvalRunner` dual-run** — runs `ScorerV2` + `ScorerV3`
   side-by-side.  `check_regression.py` still gates on
   `composite_v2` until dual-run stability sign-off (gated on
   `composite_v2` PASS + `composite_v3` week-over-week |Δ| ≤ 0.03
   across all production suites).
3. **`bubbleAccentActions(bubble, palette, registry)`** — replaces
   the type-based `bubbleAccent(type, registry?)` helper.  Three-
   cluster resolver: EXECUTE (pink) / DELEGATE (blue) / CLARIFY
   (gray).  UI accent now follows the canonical chip surface.

The 14 `IntentDecl` ids remain registered as UI-input fallbacks
+ eval-side `r_type` backwards-compat.  Adding new ids is not
the recommended path going forward — adding new canonical
actions is.

## Consequences

- Dual-run report (composite_v2 + composite_v3) on every eval
  until sign-off.
- UI accent derives from action surface, not type — fewer
  visual artifacts from "type says X but actions are Y"
  mismatches.
- Future-proof: scoring is now decoupled from type semantics;
  new chip types don't require re-grading the scorer.

## Migration

None for the production app (UI accent helper swap).  Eval
script emits both numbers; canonical switch pending sign-off.