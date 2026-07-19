package com.example.intentcam

import android.content.Context

/**
 * Result of an [ActionDef.body] invocation.  The UI dispatches based
 * on this type:
 *
 *   - [LaunchAndroidIntent]  — MainActivity.startActivity(intent)
 *                              (typically fires a system app like
 *                              maps / browser / share / etc.)
 *   - [ShowUiFeedback]       — Toast or in-app banner with [message]
 *   - [None]                 — no-op; reserved for actions whose
 *                              effect is purely UI-side (e.g. a
 *                              chip whose effect is jumping to a
 *                              detail screen — currently handled by
 *                              tapping the bubble card itself).
 *   - [RequestArgs]          — the action needs user-supplied
 *                              arguments before it can fire.  AppViewModel
 *                              parks the [pendingAction] and re-invokes
 *                              `body(ctx, bubble, args)` once the user
 *                              fills the form.  Closes the "intent
 *                              argument extraction" gap: actions can
 *                              legitimately need a destination, a
 *                              phone number, a timestamp, etc. that
 *                              the LLM can guess from the scene but
 *                              must hand off to the user.
 *   - [ShowRenderedLabel]    — view_label's rendered-label page:
 *                              AppViewModel parks the payload on
 *                              `UiState.renderedLabel`; MainActivity
 *                              overlays the full-screen page (WebView
 *                              card + save/share buttons).
 *
 * Kept deliberately narrow (5 cases).  Adding a new action category
 * (e.g., "LaunchApp") typically means adding a new outcome variant —
 * which is the point: the dispatcher in AppViewModel is exhaustive on
 * this sealed class, so adding an outcome catches every site that
 * needs to learn how to handle it.
 */
sealed class ActionOutcome {
    object None : ActionOutcome()
    data class LaunchAndroidIntent(val intent: android.content.Intent) : ActionOutcome()
    data class ShowUiFeedback(val message: String) : ActionOutcome()
    /** Body needs user input before it can run.  AppViewModel
     *  re-invokes `body(ctx, bubble, args)` with the form values
     *  once `submitActionArgs` lands. */
    data class RequestArgs(
        /** Each arg spec drives one form field.  The UI renders
         *  in this order; submit-time values land in
         *  `body(..., args: Map<String, String>)` keyed by
         *  [ActionArgSpec.key]. */
        val args: List<ActionArgSpec>,
        /** Id re-stamped so the UI can re-display the chip's
         *  label during the prompt (could differ from
         *  `pendingAction.actionId` after a redirect, but matches
         *  in practice). */
        val resumeActionId: String,
    ) : ActionOutcome()

    /** Open the in-app rendered-label page.  The body has already
     *  packed everything the page needs into [label] (a *copy*, so
     *  the page survives bubble-history eviction); AppViewModel just
     *  parks it on `UiState.renderedLabel` and MainActivity overlays
     *  the page.  Distinct from [None]'s "purely UI-side effect"
     *  because the dispatcher must carry a payload. */
    data class ShowRenderedLabel(val label: RenderedLabel) : ActionOutcome()
}

/**
 * Declarative description of one user-facing action.  Adding a new
 * action = register one more [ActionDef] in
 * [registerDefaultActions] (or in app code).  The orchestrator,
 * evaluator, and routing all stay unchanged.
 *
 * **Why this lives in `app/`, not `shared/`:** the [body] lambda
 * takes `android.content.Context` and produces [ActionOutcome] values
 * that carry `android.content.Intent`.  Pulling those into the
 * `shared/` (pure-JVM) module would force KMP `expect/actual`s on
 * everything downstream.  Android-only decision saves the plumbing;
 * see §4.8 in `~/.claude/plans/intent-action-framework.md`.
 *
 * **Why `bubble.actions` is `List<String>` (IDs), not this struct:**
 * `Bubble` lives in `shared/` (so `Models.kt` stays cross-platform).
 * The IDs are plumbed through the orchestrator; MainActivity resolves
 * them into `ActionDef`s at render time.
 *
 * **Action-first routing** (2026-07-17 retirement):
 * The registered intent taxonomy was retired — intent is the LLM's
 * free-form summary (`Bubble.intent`), not a routing key.  The LLM's
 * `action_ids` proposal is the sole signal for which chips render
 * ([ActionResolver.suggestIds] trusts it directly); content-rescue
 * adds structured misses (phone / id / payment-QR) add-only.  No
 * `applicableIntents` filter remains.
 */
data class ActionDef(
    /** Unique id, e.g. "open_in_maps".  Stored in
     *  `SettingsStore.enabledActionIds` so the user can disable
     *  individual actions. */
    val id: String,
    /** Short human label rendered on the chip. */
    val label: String,
    /** Resource-free icon key — Android side maps to a drawable via
     *  IconRegistry (planned).  Currently unused by the chips UI; the
     *  label alone is enough to be tappable. */
    val iconKey: String,
    /** Inputs this action needs to fire.  Each spec's
     *  [ActionInputSpec.parser] pulls
     *  the value out of the bubble's text surface
     *  (title + detail + details[].value); null = missing.  The
     *  orchestrator (`ActionOrchestrator.validateInputs`) drives
     *  the live-UI ghost-chip state and the LLM's targeted
     *  exploration framing from this list.  Empty = action is
     *  always fireable as soon as the user taps it (e.g. the
     *  Toast-only stubs `scan_to_pay` / `redact_id`). */
    val requiredInputs: List<ActionInputSpec> = emptyList(),
    /** Display priority when multiple chips show on the same bubble.
     *  Higher = more prominent.
     *  Negative = "show last" pattern (e.g. the always-on
     *  `copy_structured` default uses `Int.MIN_VALUE` to land at
     *  the end).  Default `0`. */
    val priority: Int = 0,
    /** When true, the chip throws a confirmation AlertDialog before
     *  firing [body].  Set on actions with side effects (share,
     *  set-reminder, etc.); false on read-only actions. */
    val requiresConfirmation: Boolean = false,
    /** Optional preference key — when set, the user's "enabled"
     *  state survives across launches via [SettingsStore].  Null =
     *  always enabled, can't be toggled. */
    val userPrefKey: String? = null,
    /** The action's runtime effect.  Receives an Android context
     *  (typically the activity), the bubble whose chip was tapped,
     *  and (when the action returned [ActionOutcome.RequestArgs]
     *  first and the user filled the form) the resolved args.  An
     *  empty map means "user tapped the chip but provided no args"
     *  — used by simple actions that don't need them.  Returning
     *  [ActionOutcome.LaunchAndroidIntent] makes MainActivity
     *  `startActivity` with the given Intent. */
    val body: suspend (ctx: Context, bubble: Bubble, args: Map<String, String>) -> ActionOutcome,
    /** Accent cluster this action belongs to, drives
     *  [bubbleAccentActions] in MainActivity.  Was previously
     *  a side-table of two `Set<String>`s in MainActivity.kt
     *  (EXECUTE_IDS / DELEGATE_IDS); promoting the data onto
     *  [ActionDef] means a new PII action only needs to set
     *  `accent = AccentCluster.EXECUTE` here, with no UI-side
     *  list to keep in sync.  `body` lambdas that don't fill the
     *  field default to `DELEGATE` (most common; non-consent
     *  actions like `share` / `open_in_maps`). */
    val accent: AccentCluster = AccentCluster.DELEGATE,
)

/** Three accent clusters the bubble card uses to color its left
 *  dot / IntentChip / confidence percentage:
 *
 *  - [EXECUTE]  — pink; consent-gated chip.  dials a number,
 *    pays a QR, masks an ID.  Visually the highest-leverage
 *    actions — the user should pause before tapping.
 *  - [DELEGATE] — blue; OS-handoff chip.  opens a maps app,
 *    shows a share-sheet.  The OS chooser is itself the consent
 *    step.
 *  - [CLARIFY]  — gray; pure info / no action.  The bubble
 *    explains what the LLM saw but offers no follow-up.
 */
enum class AccentCluster { EXECUTE, DELEGATE, CLARIFY }

/** Mutable bag of [ActionDef]s, build-once at app start. */
class ActionRegistry {
    private val byId = linkedMapOf<String, ActionDef>()

    fun register(def: ActionDef): ActionDef {
        require(def.id !in byId) { "duplicate action id: ${def.id}" }
        byId[def.id] = def
        return def
    }

    fun get(id: String): ActionDef? = byId[id]
    fun list(): List<ActionDef> = byId.values.toList()
    fun allIds(): List<String> = byId.keys.toList()
}

/** Register IntentCam's default actions.  Adding a new action = add
 *  one more `ActionDef` here.  No orchestrator / eval / settings
 *  change needed.
 *
 *  **2026-07-17** The per-intent `applicableIntents` filter was
 *  retired with the intent taxonomy; the LLM's `action_ids` proposal
 *  is the sole routing signal.  Each `ActionDef` below now declares
 *  only its inputs / accent / body, not which intents it applies to.
 */
fun registerDefaultActions(reg: ActionRegistry) {
    // First real outbound action: open a location bubble in maps.
    // Android Intent geo:0,0?q=… opens the user's default maps app
    // (百度/高德/Google Maps) with a query string.  No confirmation —
    // the user already tapped the chip, that's intent enough.
    reg.register(ActionDef(
        id = "open_in_maps",
        label = "在地图中打开",
        iconKey = "map",
        requiredInputs = listOf(ActionInputSpec(
            key = "query",
            label = "地点或地址",
            parser = { b -> com.example.intentcam.InputParsers.locationQuery(b) },
        )),
        body = { _, bubble, args ->
            // Body-level fallback chain mirrors `InputParsers.locationQuery`
            //  (priority: details[] address → simplified title → detail →
            //  fallback detail row).  args["query"] comes from the
            //  orchestrator's parser pass (`AppViewModel.parsedArgsFor`);
            //  null when the parser returned null.  We re-derive here
            //  so the legacy path (user taps chip before orchestrator
            //  gate ran) also gets the new parser logic.
            //
            //  [2026-07-17 bugfix] The previous body fell through to
            //  `bubble.title` and then `bubble.detail.take(40)` — both
            //  wrong: title is the LLM action phrase ("导航去仙桃市仙桃
            //  大道上岛咖啡"), detail.take(40) truncated real addresses
            //  mid-token ("...上岛咖啡西餐厅" → "...上岛咖").  See
            //  ~/.claude/plans/sorted-meandering-pond.md for context.
            val rawQuery = (args["query"] ?: com.example.intentcam.InputParsers.locationQuery(bubble))
                ?.trim().orEmpty()
            // Cap query length to keep the geo: URI well under Android's
            //  typical 2k Intent limit and avoid map-app-specific overflow
            //  bugs (高德 truncates very long `q=` strings).
            val query = rawQuery.take(60)
            if (query.isBlank()) {
                // No address recognized — typical for `route_to` fixtures
                //  ("向前20米 藏方养生馆") that have a storefront name
                //  but no mappable street.  Surface a Toast instead of
                //  firing `geo:0,0?q=附近` (undefined map-app behavior;
                //  some apps show empty map, some show user location).
                ActionOutcome.ShowUiFeedback(
                    "未识别到地址，请手动输入后打开地图"
                )
            } else {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(
                        "geo:0,0?q=${android.net.Uri.encode(query)}"
                    )
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                // Explicit chooser so the user picks a known-installed
                //  maps app rather than depending on system-default
                //  resolution (which on some OEM ROMs surfaces non-installed
                //  placeholder entries when the query is fuzzy).
                ActionOutcome.LaunchAndroidIntent(
                    android.content.Intent.createChooser(intent, "选择地图 App 打开")
                )
            }
        },
        // DELEGATE cluster: opens a system maps app with no in-app side
        //  effect; the OS chooser is the consent step.
        accent = AccentCluster.DELEGATE,
    ))
    // Second real outbound action: dial a phone number extracted
    //  from a `phone` bubble.
    //  2026-07-19 dev phase: the consent gates were lifted
    //  (requiresConfirmation / userPrefKey removed) so the chip is
    //  always active; re-add both before any end-user build.
    //  Former gate, for the record:
    //   - requiresConfirmation=true → chip-tap pops a Compose
    //     AlertDialog before the body's ACTION_DIAL fires.
    //   - userPrefKey → SettingsStore toggle, default OFF.
    //  ACTION_DIAL is used (NOT ACTION_CALL) so the user gets one
    //  final "press call" tap inside the dialer app; avoids needing
    //  the runtime CALL_PHONE permission (Manifest entry was
    //  rejected for review-friendliness on the previous open_in_maps
    //  commit).
    reg.register(ActionDef(
        id = "dial_number",
        label = "拨号",
        iconKey = "phone",
        requiredInputs = listOf(ActionInputSpec(
            key = "phone_number",
            label = "手机号",
            parser = { b -> com.example.intentcam.InputParsers.phoneNumber(b) },
        )),
        // 2026-07-19 dev phase: consent gates OFF (requiresConfirmation
        // and userPrefKey removed) — chips are always visible and fire
        // directly so the flow can be exercised end-to-end.  Re-add both
        // before any end-user build: requiresConfirmation=true parks an
        // AlertDialog, userPrefKey ships the toggle default-OFF.
        // ACTION_DIAL itself is safe: the dialer still needs the user to
        // press "call".
        body = { _, bubble, args ->
            // Phone extractor: pull the first plausible phone number
            // from title / detail / details[].value.  Order priority:
            //   1. mobile (1xxxxxxxxxx) — most likely intended target
            //   2. 400/800 service line (400/800-xxx-xxxx)
            //   3. landline (xxx-xxxxxxxx or 0xxxxxxxxxxx)
            //  The text could also have "电话:138xxxx" / "Tel:xxx"
            //  prefixes; strip those.  Returns null on no match —
            //  the action then surfaces a Toast instead of firing
            //  a bogus Intent.
            // Prefer the orchestrator-parsed `phone_number` from `args`
            //  (validated path); fall back to InputParsers.phoneNumber
            //  (shared/) for the user-form path or legacy chip-tap
            //  before the orchestrator gate ran.
            val number = args["phone_number"] ?: com.example.intentcam.InputParsers.phoneNumber(bubble)
            val outcome: ActionOutcome =
                number?.let {
                    ActionOutcome.LaunchAndroidIntent(
                        android.content.Intent(
                            android.content.Intent.ACTION_DIAL,
                            android.net.Uri.fromParts("tel", it, null),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } ?: ActionOutcome.ShowUiFeedback("未发现可拨打的号码")
            outcome
        },
        // EXECUTE cluster: dials a phone number — a side effect the
        //  user should pause on.  (The pink accent predates the
        //  dev-phase consent-gate lift; the cluster still marks
        //  "acts on the world" vs DELEGATE's OS handoff.)
        accent = AccentCluster.EXECUTE,
    ))
    // PII-sensitive stub actions (scan_to_pay / redact_id).
    //  2026-07-19 dev phase: consent gates lifted (same as dial_number
    //  — requiresConfirmation / userPrefKey removed); chips are always
    //  active.  Re-add both before any end-user build.
    //  Bodies are Toast-only guidance — the heavy lifting was the
    //  consent gate, not the side effect.
    reg.register(ActionDef(
        id = "scan_to_pay",
        label = "扫码支付",
        iconKey = "wallet",
        // Toast-only body — no parser needed.
        // The requiredInputs list is empty; the action is always
        // fireable, but the body itself surfaces a guidance Toast
        // instead of launching a payment app (see body comment for
        // the security rationale).
        // 2026-07-19 dev phase: consent gates OFF (see dial_number).
        body = { _, _, _ ->
            // CRITICAL: NEVER auto-launch a payment app.
            //  The QR could be presented in a screenshot / phishing
            //  context where auto-pay = money lost.  Even after the
            //  user confirms via the AlertDialog, we open the user's
            //  *default camera* (or an explicit "scan a QR" launcher)
            //  so the user has to physically point at a NEW code they
            //  trust — never the one in the current photo.  This
            //  body never fires — see the always-None below.
            //
            //  Until we have a "scan a fresh QR" UI, surface only the
            //  guidance Toast.  Phase B's conservative default.
            ActionOutcome.ShowUiFeedback(
                "请在相机/支付 App 里手动扫描二维码。不要直接扫描截图里的码。"
            )
        },
        // EXECUTE cluster: payment-side-effect.
        accent = AccentCluster.EXECUTE,
    ))
    reg.register(ActionDef(
        id = "redact_id",
        label = "遮挡证件号",
        iconKey = "lock",
        // Toast-only body.  The id_document bubble's text surface
        // IS the input the user would care about (身份证号 etc.),
        // but the conservative-v1 body just shows a guidance Toast
        // — no real parser is wired because we never write the value
        // to a real sink yet.
        // 2026-07-19 dev phase: consent gates OFF (see dial_number).
        body = { _, bubble, _ ->
            // Conservative v1: copy the FULL text to clipboard (so
            //  the user can re-paste into a trusted form), but mark
            //  the bubble as "ID seen, do not share the screenshot"
            //  via Toast.  Future Phase C will copy a redacted version
            //  (mask middle 6 digits of 身份证) — Phase B ships the
            //  simplest safe thing first.
            val text = bubble.detail.ifBlank { bubble.title }
            // No clipboard write yet — Toast only until Phase C
            // bundles a proper redactor + clipboard plumbing.  The
            // chip's purpose at this stage is to register that the
            // user saw an ID-containing image (audit trail), not to
            // bypass the consent gate.
            ActionOutcome.ShowUiFeedback(
                "识别到证件类图片。建议手打,不要截图分享。文本: ${text.take(40)}…"
            )
        },
        // EXECUTE cluster: PII redaction — even the Toast-only v1 is
        //  a "this is ID-bearing content" signal worth visually
        //  flagging.
        accent = AccentCluster.EXECUTE,
    ))
    // Unified `share` action — collapses the six former per-intent
    //  share-text actions (copy_listing / save_posting /
    //  copy_warning / copy_menu / copy_hours / copy_promo) into one.
    //  Every one did the same thing: fire an ACTION_SEND text/plain
    //  chooser pre-loaded with the bubble's title + detail.  They
    //  differed only by chip label, chooser title, and a text-empty
    //  fallback string — all now derived from `bubble.type` in the
    //  body's `when`.
    //
    //  No consent gate (requiresConfirmation=false, no userPrefKey):
    //  the OS share-sheet target picker is itself the user-mediated
    //  confirmation, and the payload is text already visible on
    //  screen.  Enabled by default.  (This drops the former per-PII
    //  toggles action_copy_listing_enabled / action_save_posting_enabled
    //  — the share sheet is the gate.)
    reg.register(ActionDef(
        id = "share",
        label = "分享文本",
        iconKey = "clipboard",
        requiredInputs = listOf(ActionInputSpec(
            key = "text",
            label = "正文",
            parser = { b -> com.example.intentcam.InputParsers.textContent(b) },
        )),
        body = { _, bubble, args ->
            // Uniform share-sheet payload.  (The 2026-07-17 intent
            //  retirement made `bubble.type` a free-form label, so the
            //  former per-intent chooser-title `when` collapsed to
            //  this single default — the branches it keyed on can no
            //  longer be produced.)
            // Prefer the orchestrator-parsed `text` from `args` (validated
            //  path); fall back to inline build for a legacy chip-tap
            //  before the orchestrator gate ran.  600-char cap is ample
            //  for a share-sheet payload.
            val payload = (args["text"] ?: buildString {
                append(bubble.title.takeIf { it.isNotBlank() } ?: "内容")
                append('\n')
                append(bubble.detail)
            }.trim()).take(600)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, payload)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActionOutcome.LaunchAndroidIntent(android.content.Intent.createChooser(intent, "分享"))
        },
        // DELEGATE cluster: the OS share-sheet target picker is the
        //  consent step; the text payload is already visible to the
        //  user.
        accent = AccentCluster.DELEGATE,
    ))
    // view_label — 标签识别。  When the LLM recognizes a label-like
    // structured text block (商品标签/价签/吊牌/合格证/快递面单/票据/
    // 铭牌 …) it transcribes the FULL label content into
    // `emit_bubble.label_markdown`; this action renders that markdown
    // into a styled in-app page (LabelPageScreen, WebView + built-in
    // HTML template) that can be saved / shared as image or text.
    //
    // requiredInputs=[label_markdown]: proposing the action without
    // the content lands the chip in ghost state, same contract as
    // open_in_maps' `query`.  No confirmation, no pref toggle:
    // read-only page view with zero side effects (save/share are
    // user-driven buttons on the page itself, and each goes through
    // its own OS-mediated surface — gallery insert / share sheet).
    reg.register(ActionDef(
        id = "view_label",
        label = "查看标签",
        iconKey = "tag",
        requiredInputs = listOf(ActionInputSpec(
            key = "label_markdown",
            label = "标签内容",
            parser = { b -> com.example.intentcam.InputParsers.labelMarkdown(b) },
        )),
        body = { _, bubble, args ->
            val md = args["label_markdown"]
                ?: com.example.intentcam.InputParsers.labelMarkdown(bubble)
            if (md == null) {
                ActionOutcome.ShowUiFeedback("未识别到标签内容")
            } else {
                ActionOutcome.ShowRenderedLabel(
                    com.example.intentcam.RenderedLabel(
                        title = bubble.title,
                        markdown = md,
                        bubbleId = bubble.id,
                    )
                )
            }
        },
        // DELEGATE cluster: hands the content off to a viewer page;
        //  no consent gate needed — the page is read-only and every
        //  outbound path from it (save / share) is separately
        //  user-initiated.
        accent = AccentCluster.DELEGATE,
    ))
}

/** Decides which actions should surface on a given bubble, given the
 * LLM's proposal + the user's currently-enabled set.
 *
 * Returns a list of *ids* (not full [ActionDef]s) because the caller
 * (`AppViewModel`) stitches them into `Bubble.actions: List<String>`
 * — and `Bubble` lives in `shared/` and can't hold Android types.
 *
 * Filtering rules:
 *   1. The LLM's `action_ids` proposal is the sole routing signal
 *      (the intent taxonomy + `applicableIntents` filter were retired
 *      2026-07-17).  Unknown ids are dropped (defensive against LLM
 *      hallucination).
 *   2. The action must be in the user's enabled set (driven by
 *      `SettingsStore.enabledActionIds` + per-action `userPrefKey`
 *      consent toggles).  When the prefs entry is absent we treat the
 *      action as enabled — the source of truth for "enabled by
 *      default" is whoever builds the `enabledIds` closure in
 *      `AppViewModel`.
 * Null / empty [Bubble.llmProposedActions] → empty list: no LLM
 * proposal means no resolver chips (only content-rescue, applied
 * downstream by [com.example.intentcam.ActionOrchestrator], can
 * still add).
 */
class ActionResolver(
    private val actions: ActionRegistry,
    private val enabledIds: suspend () -> Set<String>,
) {

    /** Bubble → list of action ids that should render as chips on
     *  that bubble.  Action-first: trust the LLM's `action_ids`
     *  proposal (whitelisted by id, then intersected with the user's
     *  enabled set).  No intent-based applicability filter — the
     *  registered intent taxonomy was retired 2026-07-17, so intent
     *  is free-form UX glue, not a routing key.  When the LLM omits
     *  `action_ids`, no chips render except content-rescue additions
     *  (added downstream by [com.example.intentcam.ActionOrchestrator]). */
    suspend fun suggestIds(bubble: Bubble): List<String> {
        val enabled = enabledIds()
        val llmProposed = bubble.llmProposedActions ?: return emptyList()
        // Whitelist by id; defensive against the LLM hallucinating an
        // unknown action id (the prompt's enum usually catches this,
        // but JSON parsers can be lenient).
        return llmProposed
            .mapNotNull { actions.get(it) }
            .map { it.id }
            .filter { it in enabled }
    }
}
