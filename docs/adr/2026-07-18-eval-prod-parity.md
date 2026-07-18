# ADR: Eval/prod chip-visibility parity (2026-07-18)

## Context

The action-first eval (`:shared:eval`, ScorerV3) scores `r_actions`
as recall of `expected_actions` against the **visible chip set** —
the ids written into `Bubble.actions` by the `markValidated`
callback.  Production computes the same set through
`ActionResolver.suggestIds` (LLM proposal ∩ user's enabled set)
followed by `ActionOrchestrator.markValidatedInputs` (rescue, also
∩ enabled since the 2026-07-18 P3 fix).

The two sides deliberately differ in ONE input: the **enabled set**.

| | enabled set |
|---|---|
| prod | `SettingsStore` — `dial_number` / `scan_to_pay` / `redact_id` carry `userPrefKey` and ship **OFF by default** (consent); `share` / `open_in_maps` always on |
| eval | the full 5-action vocabulary (`EvalRunner.defaultActionIds`) |

So the eval measures the **idealized configuration** (every action
available), not what a fresh install displays.  Recall numbers in
`baselines.json` are therefore an upper bound on fresh-user-visible
recall for the three consent-gated actions.

## Decision

Keep the asymmetry, record it (this ADR), and share every other
piece of the computation: `ActionRescue.visibleActions` (single
implementation since 2026-07-18) is called by both sides with their
respective enabled sets, so no other drift surface remains.

Rationale for not gating the eval: baselines must not move when a
*product* decision (default-OFF vs default-ON for a PII action)
changes — the eval measures LLM + parser capability, not settings
defaults.  If the defaults ever flip to ON, prod-visible recall
converges to the baselines with no eval change needed.

## Consequences

- Reading `baselines.json`: `dial_number` / `scan_to_pay` /
  `redact_id` recall is "what the pipeline *can* surface", not what
  a default user sees today (they see the chip after a one-time
  opt-in; the LLM+rescue quality is identical post-opt-in).
- The `none` suite's `over_fire_rate` is unaffected: over-fire is
  measured on LLM proposals + rescue, which are computed before the
  enabled gate on both paths... and prod users with PII actions OFF
  actually see *fewer* chips, so the eval over-fire rate is again
  the conservative (upper) bound.
