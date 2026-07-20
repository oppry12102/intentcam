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

/** Parked on `UiState.pendingConfirmation` when a
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

/** Payload for the `view_label` action's rendered-label page.
 *  Carried by `ActionOutcome.ShowRenderedLabel` (app/) and parked on
 *  `UiState.renderedLabel`; MainActivity overlays the full-screen
 *  label page while this is non-null.  Lives in `shared/` for the
 *  same reason as [PendingAction]: [UiState] is cross-platform and
 *  must not reference Android types.
 *
 *  The page content rides along as a *copy* (not a bubble reference)
 *  so the page survives bubble-history eviction while it is open. */
data class RenderedLabel(
    /** Page title — the bubble's intent phrase (e.g. "查看商品标签"). */
    val title: String,
    /** Full label content as markdown (the `label_markdown` field the
     *  LLM emitted in `emit_bubble`).  Rendered to HTML by
     *  [com.example.intentcam.LabelHtml]; also the payload for
     *  save-as-text / share-as-text. */
    val markdown: String,
    /** Source bubble id, for audit logging / correlation only. */
    val bubbleId: String,
)

/** Payload for the `view_ad` action's rendered-ad page
 *  (广告图文复现).  Same payload-as-copy rationale as
 *  [RenderedLabel], plus the corrected ad image rides along as JPEG
 *  bytes so the page never re-derives it from the (evictable)
 *  source bubble. */
data class RenderedAd(
    /** Page title — the bubble's intent phrase (e.g. "查看招生广告"). */
    val title: String,
    /** Full ad transcription as markdown (`emit_bubble.ad_markdown`). */
    val markdown: String,
    /** The ad region after crop + perspective correction +
     *  enhancement, JPEG-encoded.  Rendered at the top of the page
     *  and shared as-is for 分享图片.  Null when the bubble had no
     *  usable image (degenerate — page falls back to text-only). */
    val imageJpeg: ByteArray?,
    /** Source bubble id, for audit logging / correlation only. */
    val bubbleId: String,
) {
    // ByteArray in a data class needs manual equals/hashCode.
    override fun equals(other: Any?): Boolean =
        other is RenderedAd && title == other.title && markdown == other.markdown &&
            bubbleId == other.bubbleId &&
            (imageJpeg === other.imageJpeg ||
                (imageJpeg != null && other.imageJpeg != null &&
                    imageJpeg.contentEquals(other.imageJpeg)) ||
                (imageJpeg == null && other.imageJpeg == null))

    override fun hashCode(): Int {
        var r = title.hashCode()
        r = 31 * r + markdown.hashCode()
        r = 31 * r + bubbleId.hashCode()
        r = 31 * r + (imageJpeg?.contentHashCode() ?: 0)
        return r
    }
}

/** Output of [com.example.intentcam.ActionOrchestrator.validateInputs].
 *  Sealed so callers (live UI, ScorerV2, prompt framing) handle both
 *  shapes exhaustively.  Lives in `:shared/` because
 *  [com.example.intentcam.ToolUseLoop.runCycle]'s `onEmit` callback
 *  surfaces the gate's verdict through [FinalizeDecision]; the
 *  sealed class has no Android types so it crosses the module
 *  boundary without dragging Context/Intent along. */
sealed class InputsValidation {
    /** Every chosen action's required inputs are satisfied by the
     *  bubble's text surface.  Chip state = solid, ready to fire. */
    object Complete : InputsValidation()

    /** At least one action is missing ≥1 required input.  Per-action
     *  breakdown lets the UI render ghost chips with per-chip
     *  tooltips ("dial_number: 需要 手机号") rather than a single
     *  global "missing X" banner.  Keys in [perAction] are action
     *  ids (matching `Bubble.actions`); values are the missing
     *  input keys (`ActionInputSpec.key`) for that action. */
    data class Missing(
        val perAction: Map<String, List<String>>,
    ) : InputsValidation()
}

/** Output of [com.example.intentcam.ActionOrchestrator.shouldFinalize].
 *  Drives the cycle's loop control — `FINALIZE` ends the cycle and
 *  ships the bubble, `CONTINUE` triggers another round of LLM
 *  exploration with the missing-input hint injected into the user
 *  message.  Cross-platform type for the same reason as
 *  [InputsValidation]: [ToolUseLoop] needs to consume it without an
 *  Android dep. */
sealed class FinalizeDecision {
    /** Stop here; ship the bubble as-is.  [reason] is for logging
     *  and the per-cycle audit trail ("complete" / "max_rounds"). */
    data class FINALIZE(val reason: String) : FinalizeDecision()

    /** Keep going — feed [missing] into the next round's user
     *  message as a "you still need to extract these" hint.  LLM
     *  is free to call any tool (not just the suggested one). */
    data class CONTINUE(val missing: List<String>) : FinalizeDecision()
}

/** Pure projection: stamp input-validation state onto a
 *  bubble's data-class fields without touching Android types.  Mirrors
 *  [com.example.intentcam.ActionOrchestrator.markValidatedInputs] but
 *  takes the parser-registry as a parameter rather than closing over
 *  an Android-coupled [com.example.intentcam.ActionRegistry].  Eval
 *  side uses this with regex parsers; prod side uses the orchestrator
 *  helper directly.
 *
 *  @param bubble the bubble whose `actions` list drives which
 *    inputs to check.  Empty `actions` → empty outputs (legacy
 *    emit_bubble without `action_ids`).
 *  @param requiredInputs registry of input specs, keyed by action id.
 *    Missing action ids are silently skipped.
 *  @return Pair(validatedInputs map, pendingInputs list) suitable
 *    for spreading into `bubble.copy(...)`. */
fun projectInputsValidation(
    bubble: Bubble,
    requiredInputs: Map<String, List<ActionInputSpec>>,
): Pair<Map<String, Boolean>, List<String>> {
    if (bubble.actions.isEmpty()) return emptyMap<String, Boolean>() to emptyList()
    val validated = LinkedHashMap<String, Boolean>()
    val pending = LinkedHashMap<String, String>()
    bubble.actions.forEach { actionId ->
        val specs = requiredInputs[actionId].orEmpty()
        if (specs.isEmpty()) {
            validated[actionId] = true
            return@forEach
        }
        val missing = specs.filter { it.parser(bubble) == null }
        if (missing.isEmpty()) {
            validated[actionId] = true
        } else {
            validated[actionId] = false
            missing.forEach { pending.putIfAbsent(it.key, it.key) }
        }
    }
    return validated to pending.values.toList()
}

/** One declared input that an
 *  [ActionDef] requires before it can fire.  This validation boundary
 *  keeps the LLM authoritative while the orchestrator checks whether
 *  selected actions are executable; see
 *  `docs/adr/2026-07-14-v3-inversion.md`.  Distinct from
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
