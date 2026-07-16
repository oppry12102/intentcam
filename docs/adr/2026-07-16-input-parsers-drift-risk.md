# 2026-07-16 — InputParsers drift risk (phone regex duplicated 3 places)

## Status
Accepted. Risk documented; mitigation pending. Supersedes the
implicit "eval mirrors prod" invariant that held when the eval
could share `app/` code.

## Context

Prod (`app/`) defines the canonical input parsers once:

- `app/.../ActionDecl.kt:448-475` — `internal object PhoneExtractor`
  with three `Regex` constants: `mobile = 1[3-9]\d{9}`,
  `service = (?:400|800)[\s-]?\d{3,4}[\s-]?\d{3,4}`,
  `landline = \b0?\d{3,4}[\s-]?\d{7,8}\b`
- `app/.../ActionDecl.kt:489-520` — `internal object InputParsers`
  wraps `PhoneExtractor.firstMatch` + defines `locationQuery`
  and `textContent` as `(Bubble) -> String?` lambdas

These feed `ActionDef.requiredInputs` (via
`ActionInputSpec.parser`) which the orchestrator calls per
emit to populate `bubble.validatedInputs`.

The eval pipeline lives in `shared/eval/` and **cannot
import `app/`** (Android-coupled). To score
`r_inputs_complete`, the eval duplicates the parsers:

- `shared/.../eval/EvalRunner.kt:91-100` — inline `phoneParser`
  lambda with `MOBILE_REGEX.containsMatchIn(corpus)` etc.
- `shared/.../eval/EvalRunner.kt:116-118` — `private val`
  `MOBILE_REGEX` / `SERVICE_REGEX` / `LANDLINE_REGEX` regex
  constants (third copy)
- `shared/.../eval/EvalRunner.kt:104-106` — `ActionInputSpec`
  definitions wrapping the lambdas
- `shared/.../eval/ScorerV2.kt:83-85` — yet another copy
  of `MOBILE_REGEX` + `LANDLINE_REGEX`
- `shared/.../eval/ScorerV2.kt:160-162` — `r_inputs_complete`
  inline check using the duplicated regexes

**Three copies of the phone regex, plus two `(Bubble) -> String?`
lambda implementations of `phoneNumber`.**

## The risk

If prod adds:
- New mobile prefix (e.g. carrier rolls out `19[1-9]` series)
- New landline format (e.g. 5-digit extensions become standard)
- New service line format (e.g. `95xxx` 5-digit short codes)
- Different separator handling (`space` allowed vs `hyphen-only`)
- New action with required input (e.g. `pay_amount`, `address_line_2`)

→ prod `InputParsers.phoneNumber` updates,
→ but `EvalRunner.phoneParser` + `ScorerV2.r_inputs_complete`
   silently disagree with prod.

Symptoms:
- `r_inputs_complete` shows 1.0 for fixtures where prod would
  fail validation (eval over-counts satisfied inputs)
- Or shows 0.0 where prod passes (eval under-counts)
- `composite_v2` moves with no model change
- Regression net may flag a phantom regression
- The drift is invisible until someone cross-references the
  regex strings by hand

## Mitigation: extract a shared `shared/InputParsers` (planned)

Move the parser logic + regex constants to
`shared/src/main/kotlin/com/example/intentcam/InputParsers.kt`:

```kotlin
// shared/.../InputParsers.kt
package com.example.intentcam

object InputParsers {
    val MOBILE_REGEX = Regex("""1[3-9]\d{9}""")
    val SERVICE_REGEX = Regex("""(?:400|800)[\s-]?\d{3,4}[\s-]?\d{3,4}""")
    val LANDLINE_REGEX = Regex("""\b0?\d{3,4}[\s-]?\d{7,8}\b""")

    fun phoneNumber(bubble: Bubble): String? { ... }  // body from PhoneExtractor.firstMatch
    fun locationQuery(bubble: Bubble): String? { ... }
    fun textContent(bubble: Bubble): String? { ... }
}
```

Then:
- `app/.../ActionDecl.kt:InputParsers.phoneNumber` re-exports
  the shared one (or thin wrapper that calls the shared one)
- `EvalRunner.phoneParser` calls `InputParsers.phoneNumber(b)`
- `ScorerV2.r_inputs_complete` calls the shared regexes
  directly (no lambda needed)

Single source of truth. Drift impossible. Eval and prod
agree by construction.

## Why not done yet

The migration has cost:
- `PhoneExtractor.firstMatch` lives in `app/` and uses
  `bubble.title` / `bubble.detail` / `bubble.details` — all
  `shared/` types. Extraction is mechanical.
- But the `app/` `InputParsers` is `internal object` (not
  exported) — needs to become `public` so `shared/eval/` can
  import it.
- Cycle verification: prod + eval regression net run after
  extraction to confirm no behavior change.

Estimated: 30-60 min of mechanical refactor + 1 regression
net run. **Not done in this P0 cleanup because it's
out-of-scope (no behavior change needed for the P0 ship)**.

## Consequences of NOT doing it

The drift risk is **latent** — no immediate symptoms unless
prod changes a regex. Documented here so the next change to
phone regex / action input triggers a "did you also update
eval?" checklist item.

## Related decisions

- `2026-07-14-v3-inversion.md` — established "eval mirrors
  prod" invariant for `ToolUseLoop` (eval calls the same
  class). This ADR flags where that invariant breaks down
  for `InputParsers`.
- `2026-07-10-intent-action-framework.md` — defines the 5
  actions whose `requiredInputs` are validated by these
  parsers. Adding a new action = mandatory InputParsers
  update here.

## Migration checklist (when picked up)

1. Create `shared/src/main/kotlin/com/example/intentcam/InputParsers.kt`
   with `MOBILE_REGEX` / `SERVICE_REGEX` / `LANDLINE_REGEX`
   constants + `phoneNumber` / `locationQuery` / `textContent`
   functions (copy bodies from
   `app/.../ActionDecl.kt:448-520`)
2. Delete `app/.../ActionDecl.kt:PhoneExtractor` and
   `app/.../ActionDecl.kt:InputParsers`; update the `dial_number`
   and `open_in_maps` defs to reference `com.example.intentcam.InputParsers`
3. Update `EvalRunner.phoneParser` to call
   `InputParsers.phoneNumber(bubble)` (or the regex directly
   for the "is present" check)
4. Update `ScorerV2.r_inputs_complete` to use the shared
   regex constants
5. Run regression net: composite_v2 across all 9 suites
   should match baseline ± noise (drift == 0 if migration is
   pure refactor)
6. If composite_v2 shifts > noise, the migration is not pure
   refactor; investigate before merging