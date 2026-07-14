package com.example.intentcam

/**
 * Cross-platform data carriers for the action-argument form
 * (`ActionOutcome.RequestArgs` ↔ `PendingAction` ↔ `Map<String,
 * String>`).  Lives in `shared/` so [Models.Bubble] / [PendingAction]
 * can hold an `args: List<ActionArgSpec>` without dragging
 * `android.*` types into the cross-platform module.
 *
 * The Android-only pieces stay in `app/ActionDecl.kt`:
 *  - [ActionOutcome] (sealed class with `LaunchAndroidIntent`)
 *  - [ActionDef] (its `body` lambda takes `Context`)
 *  - [ActionRegistry] / [ActionResolver] (instantiated at app start)
 *
 * The split rule: pure-POD types used by both sides live here;
 * anything that touches `Context` or `Intent` stays in `app/`.
 */

/** What kind of input each [ActionArgSpec] needs.  Coarse on
 *  purpose — TEXT covers the in-product cases (destination / phone /
 *  message body).  NUMERIC / PHONE come with format-aware IMEs;
 *  finer validation (e.g. real-phone-number regex) can hang on
 *  later without changing the dispatcher contract. */
enum class ArgKind { TEXT, NUMERIC, PHONE }

/** One form field an action needs before it can fire. */
data class ActionArgSpec(
    /** Form field key — looks up the value in the args map passed
     *  to `body(..., args)`.  Required, non-blank. */
    val key: String,
    /** Human label shown next to the field.  Localize per UI. */
    val label: String,
    /** Input flavor.  Defaults to TEXT. */
    val kind: ArgKind = ArgKind.TEXT,
    /** When true, the confirm button stays disabled until the
     *  field has a non-blank value. */
    val required: Boolean = true,
    /** Optional pre-fill so an action can pre-populate from the
     *  bubble's title / detail. */
    val default: String? = null,
    /** Helper text shown under the field.  Optional. */
    val helpText: String? = null,
)

/** Parked on `UiState.pendingAction` when an [ActionDef.body]
 *  returned `ActionOutcome.RequestArgs`.  The UI renders an
 *  [ActionArgSpec]-shaped form; when the user submits, AppViewModel
 *  re-invokes `body(ctx, bubble, args)` with the form values.  Cross-
 *  platform because [Models.UiState] lives in `shared/`. */
data class PendingAction(
    /** Re-stamped action id for the dispatcher's audit log +
     *  Logger tag. */
    val actionId: String,
    /** Bubble whose chip the user tapped; the resumed `body`
     *  receives this so it can pull title / detail / details[] for
     *  the args it needs. */
    val bubbleId: String,
    /** Form fields.  Same shape as `RequestArgs.args`.  UI collects
     *  into `Map<String, String>` keyed by [ActionArgSpec.key]. */
    val args: List<ActionArgSpec>,
    /** Prompt shown above the form (e.g. "发短信要先填号码").  Body's
     *  `RequestArgs.helpText` becomes this when present. */
    val prompt: String,
)

/** [2026-07-13] Parked on `UiState.pendingConfirmation` when a
 *  chip-tap lands on an [ActionDef] whose `requiresConfirmation` is
 *  true (currently `dial_number`).  The UI surfaces an AlertDialog
 *  with [prompt] + [detail]; confirm routes back through
 *  `AppViewModel.confirmAction()`, cancel routes through
 *  `cancelConfirmation()`.  Split from [PendingAction] so the
 *  mutually-exclusive contract ("only one dialog can be parked at
 *  a time") stays simple — a confirmation dialog and an args form
 *  are different UX flows and need different buttons / dismissal
 *  semantics. */
data class PendingConfirmation(
    /** The action the user originally tapped.  Re-stamped onto
     *  the dispatcher's audit log so we can correlate "user
     *  confirmed" with the original chip id. */
    val actionId: String,
    /** Bubble whose chip fired the parked confirmation.  Resolved
     *  in `confirmAction` before dispatching. */
    val bubbleId: String,
    /** Title shown above the dialog (e.g., "确认拨号?"). */
    val prompt: String,
    /** Body of the dialog — the human-readable description of the
     *  side-effect ("即将在系统拨号器中拨打 138xxxx").  Pre-baked
     *  when the chip is tapped so the same confirmation works
     *  even if the user is offline. */
    val detail: String,
)

/** [2026-07-14 Phase A — inversion v3.0] One declared input that an
 *  [ActionDef] requires before it can fire.  Distinct from
 *  [ActionArgSpec] (which is the form rendered at runtime when the
 *  user fills in missing fields):
 *
 *  - **ActionInputSpec** = "this action needs a `phone_number` value
 *    to function; the parser extracts it from the bubble's text".
 *    Drives the orchestrator's requiredInputs validator and the
 *    LLM's targeted exploration framing.
 *  - **ActionArgSpec** = "this action pops a form with these fields
 *    the user must type into before the body runs".  Drives the
 *    `RequestArgs` runtime form.
 *
 *  Same `key` namespace as `ActionArgSpec.key` — a future action
 *  can declare a `requiredInputs` entry for `phone_number` AND
 *  request the same key via `RequestArgs` if it wants a user-typed
 *  fallback after the bubble-derived value fails the parser.
 *
 *  Lives in `shared/` because `ActionDef.requiredInputs` is consumed
 *  by `ActionOrchestrator` (a shared/ class) and `EvalScorerV2`'s
 *  `r_inputs_complete` calculation.  The Android-only piece —
 *  the actual body that *uses* the parsed value — stays in
 *  `app/ActionDecl.kt`.
 */
data class ActionInputSpec(
    /** Stable id; matches `ActionArgSpec.key` when the same action
     *  also exposes a runtime form.  Required, non-blank. */
    val key: String,
    /** Human-readable label, e.g. "手机号" / "地点或地址" / "正文内容".
     *  Surfaced in the orchestrator's missing-input framing so the
     *  LLM knows what to look for. */
    val label: String,
    /** Pure function that pulls the input value out of the bubble's
     *  text surface (title + detail + details[].value).  Returns
     *  `null` when the input cannot be derived from this bubble —
     *  the orchestrator treats that as a "missing input" signal
     *  and either surfaces a ghost chip or prompts the LLM to
     *  explore further.  Side-effect-free; safe to call from any
     *  context. */
    val parser: (Bubble) -> String?,
)
