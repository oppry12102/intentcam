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
