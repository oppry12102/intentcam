# 2026-07-10 → 2026-07-13 — Intent↔Action framework (Phases A→J)

## Status
Accepted. Shipped in 10 phases over 4 days (2026-07-10 →
2026-07-13).

## Context

Pre-framework, `Bubble` carried a 3-bucket `type` enum
(`info` | `location` | `solve`) and zero action surface — the
user could only see the bubble's title and tap for details, not
do anything with the recognition result.

## Decision

Two-layer classification + action framework:

- **`IntentDecl`** — 14 intent ids, 2 families (`OBSERVE` for
  read-and-keep, `ACT_ON` for trigger-a-side-effect).  Each id
  carries `id`, `label`, `family`, `llmHint` (Chinese description
  baked into the system prompt).
- **`ActionDecl`** — 5 user-facing actions.  Each def carries
  `id`, `label`, `applicableIntents` / `applicableFamilies`,
  `requiresConfirmation`, `userPrefKey?`, `requiredInputs`,
  `accent` (UI color), `body(ctx, bubble, args)`.
- **LLM is authoritative** — emits `type` + `action_ids` in
  `emit_bubble`.  No plumbing-side intent rewriting (post-v3.0).

### Phase ship timeline

| Phase | Date | Ship | New intent | New action |
|---|---|---|---|---|
| A | 2026-07-10 | Step 2-5 + chip UI + open_in_maps | `phone` | `dial_number` |
| B | 2026-07-11 | 4 PII intents + 4 actions | `real_estate_rental`, `recruit_hiring`, `payment_qr`, `id_document` | 4 PII actions (Toast-only for `scan_to_pay` / `redact_id`) |
| G | 2026-07-12 | 3 OBSERVE intents + 3 share actions | `warning_safety`, `menu_food`, `hours_schedule` | `copy_warning`, `copy_menu`, `copy_hours` |
| H | 2026-07-12 | `route_to` + open_in_maps widens | `route_to` | (reuses open_in_maps) |
| I | 2026-07-12 | 32-keyword institution regex | `service_institution` | (reuses share + open_in_maps) |
| J | 2026-07-13 | 13-keyword promo regex | `shopping_promo` | `copy_promo` |

### 2026-07-15 action-first merge

Collapsed 6 per-intent share actions (`copy_warning` /
`copy_menu` / `copy_hours` / `copy_promo` / `save_posting` /
`copy_listing`) into **one intent-agnostic `share` action**.
Empirically confirmed that most intents resolve to the same
`share` chip — the 14-bucket intent boundary was fuzzy in a
way that didn't matter to the user's actual outcome.  5 actions
total: `open_in_maps`, `dial_number`, `scan_to_pay`, `redact_id`,
`share`.

## Consequences

- Action chip UX (vs read-only detail view).
- 3 PII actions (`dial_number` / `scan_to_pay` / `redact_id`)
  ship **OFF by default** with `requiresConfirmation = true`
  + `userPrefKey` gating.  Universal actions (`share` /
  `open_in_maps`) default ON (the system share-sheet / maps
  picker is its own consent step).
- **`scan_to_pay` and `redact_id` are Toast-only by design** —
  even with consent we don't auto-launch a payment from a
  photo's QR (could be screenshot / phishing context); we route
  the user to physically scan a *new* code, never the one in
  the photo.  Real redaction (mask middle 6 of 18-digit 身份证)
  is a future ship.
- Drift between `IntentDecl` and `ActionDecl` registries =
  silent chip miss for the new intent; **`2-register lockstep`
  invariant** (Phase A+) when adding a new intent.

## Migration

Adding a new intent = lockstep edits in 2 files:
1. `shared/.../IntentDecl.kt` `registerDefaultIntents()`
2. `app/.../ActionDecl.kt` `registerDefaultActions()`

Old `IntentVerifier` (13-pass regex post-processor) was retired
in v3.0 inversion — see
`.claude/decisions/2026-07-14-v3-inversion.md`.