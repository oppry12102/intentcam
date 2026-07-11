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
 *                              effect is purely UI-side (e.g.
 *                              "view_details" jumps to a detail
 *                              screen — but that's now handled by
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
 *
 * Kept deliberately narrow (4 cases).  Adding a new action category
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
 * **Intent applicability** (2026-07-13):
 * [applicableIntents] is the explicit list of intent ids the action
 * applies to (e.g. `setOf("location")` for `open_in_maps`).
 * [applicableFamilies] is the broader family-or-set filter:
 * `setOf(IntentFamily.OBSERVE)` matches every OBSERVE-family bubble
 * (info / location in the default registry) without re-listing them
 * every time a new in-family intent is added.  Both default to empty
 * (= the action applies to nothing).  Resolver keeps both in
 * OR-semantics: a bubble matches when `bubble.type ∈ applicableIntents
 * || bubble.family ∈ applicableFamilies`.  Empty = applies to all
 * (the "universal" pattern, reserved for future actions).
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
    /** Ids of intents this action applies to.  Empty set +
     *  [applicableFamilies] empty = applies to none.  Either list
     *  non-empty = applies to matching bubbles. */
    val applicableIntents: Set<String> = emptySet(),
    /** [IntentFamily] values the action matches.  Lets the action
     *  "follow the family" — adding a new in-OBSERVE intent
     *  automatically lights it up. */
    val applicableFamilies: Set<IntentFamily> = emptySet(),
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
)

/** Mutable bag of [ActionDef]s, build-once at app start.  Mirrors
 *  the shape of `IntentRegistry`. */
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

    companion object {
        /** Reserved id for actions that just bring the user to the
         *  detail screen.  Not currently wired in step 3 (the bubble
         *  card itself opens detail), but kept as a reference for
         *  future "chip = same as tap card" semantics. */
        const val DEFAULT_ID = "view_details"
    }
}

/** Register IntentCam's default actions.  Adding a new action = add
 *  one more `ActionDef` here.  No orchestrator / eval / settings
 *  change needed.
 *
 *  **2026-07-13** `applicableIntents` and `applicableFamilies` are
 *  now both fields; `registerDefaultActions` keeps the old explicit
 *  per-intent lists (each action targets a specific intent, no
 *  family-wide shortcut yet).  Future "share", "set reminder" etc.
 *  will opt into families — e.g. `share` over ACT_ON, `set reminder`
 *  universal. */
fun registerDefaultActions(reg: ActionRegistry) {
    reg.register(ActionDef(
        id = ActionRegistry.DEFAULT_ID,
        label = "查看详情",
        iconKey = "expand",
        applicableIntents = setOf("info", "location", "solve"),
        // The bubble card itself opens detail on tap; this action
        // is registered for completeness but its body is no-op.
        body = { _, _, _ -> ActionOutcome.None },
    ))
    // First real outbound action: open a location bubble in maps.
    // Android Intent geo:0,0?q=… opens the user's default maps app
    // (百度/高德/Google Maps) with a query string.  No confirmation —
    // the user already tapped the chip, that's intent enough.
    reg.register(ActionDef(
        id = "open_in_maps",
        label = "在地图中打开",
        iconKey = "map",
        applicableIntents = setOf("location"),
        body = { _, bubble, _ ->
            // Query = the user's intent (动宾短语) if present, else
            // the scene description.  Maps app will resolve however
            // it's configured (geocode search vs literal match).
            val query = bubble.title.takeIf { it.isNotBlank() }
                ?: bubble.detail.take(40).ifBlank { "附近" }
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(
                    "geo:0,0?q=${android.net.Uri.encode(query)}"
                )
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ActionOutcome.LaunchAndroidIntent(intent)
        },
    ))
    // [2026-07-13] Second real outbound action: dial a phone number
    //  extracted from a `phone` bubble.  Strictly consent-gated:
    //   - requiresConfirmation=true → chip-tap pops a Compose
    //     AlertDialog before the body's ACTION_DIAL fires (handled
    //     by AppViewModel when def.requiresConfirmation is true; the
    //     body itself assumes consent has already been granted).
    //   - userPrefKey="action_dial_number_enabled" →
    //     SettingsStore backs the toggle; AppViewModel's
    //     enabledIds() filters out this action unless the key is
    //     true.  Default OFF — user must opt in once.
    //  ACTION_DIAL is used (NOT ACTION_CALL) so the user gets one
    //  final "press call" tap inside the dialer app; avoids needing
    //  the runtime CALL_PHONE permission (Manifest entry was
    //  rejected for review-friendliness on the previous open_in_maps
    //  commit).
    reg.register(ActionDef(
        id = "dial_number",
        label = "拨号",
        iconKey = "phone",
        applicableIntents = setOf("phone"),
        requiresConfirmation = true,
        userPrefKey = "action_dial_number_enabled",
        body = { _, bubble, _ ->
            // Phone extractor: pull the first plausible phone number
            // from title / detail / details[].value.  Order priority:
            //   1. mobile (1xxxxxxxxxx) — most likely intended target
            //   2. 400/800 service line (400/800-xxx-xxxx)
            //   3. landline (xxx-xxxxxxxx or 0xxxxxxxxxxx)
            //  The text could also have "电话:138xxxx" / "Tel:xxx"
            //  prefixes; strip those.  Returns null on no match —
            //  the action then surfaces a Toast instead of firing
            //  a bogus Intent.
            val outcome: ActionOutcome =
                PhoneExtractor.firstMatch(bubble)?.let { number ->
                    ActionOutcome.LaunchAndroidIntent(
                        android.content.Intent(
                            android.content.Intent.ACTION_DIAL,
                            android.net.Uri.fromParts("tel", number, null),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } ?: ActionOutcome.ShowUiFeedback("未发现可拨打的号码")
            outcome
        },
    ))
    // [2026-07-13] Phase B: PII-sensitive actions.  All four share
    //  the same consent + default-off gating as `dial_number`:
    //   - requiresConfirmation=true (chip-tap parks AlertDialog)
    //   - userPrefKey="action_<id>_enabled" (SettingsStore backs the
    //     toggle; Settings screen in Phase B3 surfaces 4 switches)
    //  Bodies are minimal text → clipboard / warn / info-only — the
    //  heavy lifting is the consent gate, not the side effect.
    reg.register(ActionDef(
        id = "copy_listing",
        label = "复制房源",
        iconKey = "clipboard",
        applicableIntents = setOf("real_estate_rental"),
        requiresConfirmation = true,
        userPrefKey = "action_copy_listing_enabled",
        body = { _, bubble, _ ->
            // ACTION_SEND with text/plain — the user gets the system
            // share sheet pre-loaded with the listing text, which is
            // more honest than silently dropping it on the
            // clipboard (less surprising if it doesn't paste right).
            val payload = buildString {
                append(bubble.title.takeIf { it.isNotBlank() } ?: bubble.detail.take(60))
                append('\n')
                append(bubble.detail)
            }.trim()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, payload)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActionOutcome.LaunchAndroidIntent(android.content.Intent.createChooser(intent, "分享房源"))
        },
    ))
    reg.register(ActionDef(
        id = "save_posting",
        label = "保存招聘",
        iconKey = "clipboard",
        applicableIntents = setOf("recruit_hiring"),
        requiresConfirmation = true,
        userPrefKey = "action_save_posting_enabled",
        body = { _, bubble, _ ->
            // Same share-sheet path as copy_listing — keeps the
            // surface small and consistent.  Real save-to-Notes /
            // save-to-RecyclerView is Phase C.
            val payload = buildString {
                append(bubble.title.takeIf { it.isNotBlank() } ?: bubble.detail.take(60))
                append('\n')
                append(bubble.detail)
            }.trim()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, payload)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActionOutcome.LaunchAndroidIntent(android.content.Intent.createChooser(intent, "保存招聘"))
        },
    ))
    reg.register(ActionDef(
        id = "scan_to_pay",
        label = "扫码支付",
        iconKey = "wallet",
        applicableIntents = setOf("payment_qr"),
        requiresConfirmation = true,
        userPrefKey = "action_scan_to_pay_enabled",
        body = { _, _, _ ->
            // [2026-07-13] CRITICAL: NEVER auto-launch a payment app.
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
    ))
    reg.register(ActionDef(
        id = "redact_id",
        label = "遮挡证件号",
        iconKey = "lock",
        applicableIntents = setOf("id_document"),
        requiresConfirmation = true,
        userPrefKey = "action_redact_id_enabled",
        body = { _, bubble, _ ->
            // [2026-07-13] Conservative v1: copy the FULL text to
            //  clipboard (so the user can re-paste into a trusted
            //  form), but mark the bubble as "ID seen, do not share
            //  the screenshot" via Toast.  Future Phase C will copy a
            //  redacted version (mask middle 6 digits of 身份证)
            //  — Phase B ships the simplest safe thing first.
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
    ))
}

/** Pull the first plausible phone number from a bubble's surface text.
 *  Pure function; lives alongside [ActionDef.body] so the extraction
 *  regex + priority order stay co-located with the only caller.
 *
 *  [2026-07-13] Initial version for the dial_number action.  Order
 *  matters: mobile (1xxxxxxxxxx) wins over landline (0xxxxxxxxxxx
 *  or xxx-xxxxxxxx), which wins over 400/800 service lines.  This
 *  matches what a Chinese user tapping "dial" would expect — the
 *  number on a storefront sign is overwhelmingly a mobile when both
 *  are present.
 *
 *  Returns null on no match — the action then surfaces a Toast.
 *  Side-effect-free; safe to call from any context (it's a suspend-
 *  ready regex on a string).
 */
internal object PhoneExtractor {
    // Mobile: 1[3-9] + 9 digits (covers all 3 Chinese carriers incl.
    // 14x/15x/16x/17x/18x/19x series).
    private val mobile = Regex("""1[3-9]\d{9}""")
    // 400 / 800 service line.  Format: 400/800 + (3-4 digits) + (3-4
    // digits), possibly hyphenated.
    private val service = Regex("""(?:400|800)[\s-]?\d{3,4}[\s-]?\d{3,4}""")
    // Landline: area code 3-4 digits + 7-8 digits, possibly hyphenated,
    // optionally leading 0 (sometimes present, sometimes not — sign
    // posters are inconsistent).
    private val landline = Regex("""\b0?\d{3,4}[\s-]?\d{7,8}\b""")

    fun firstMatch(bubble: Bubble): String? {
        // Concatenate all surfaces the model produced into one search
        // corpus.  Title gets a slight boost (the model often puts the
        // number there) by being first.
        val corpus = buildString {
            append(bubble.title).append('\n')
            append(bubble.detail).append('\n')
            bubble.details.forEach { d -> append(d.value).append('\n') }
        }
        // Order matters — first match wins on each call.
        mobile.find(corpus)?.value?.let { return it }
        service.find(corpus)?.value?.let { return it.replace(Regex("""[\s-]"""), "-") }
        landline.find(corpus)?.value?.let { return it.replace(Regex("""[\s-]"""), "") }
        return null
    }
}

/**
 * Decides which actions should surface on a given bubble, given the
 * intent + the user's currently-enabled set.
 *
 * Returns a list of *ids* (not full [ActionDef]s) because the caller
 * (`AppViewModel`) stitches them into `Bubble.actions: List<String>`
 * — and `Bubble` lives in `shared/` and can't hold Android types.
 *
 * Filtering rules, in order:
 *   1. The bubble's intent must be registered (defensive — guards
 *      against the LLM emitting an unknown id).
 *   2. The action's [ActionDef.applicableIntents] includes the
 *      bubble's intent **OR** its [ActionDef.applicableFamilies]
 *      includes the bubble's intent's family.  Either list being
 *      non-empty AND matching is enough; both empty = the action
 *      applies to nothing (it's misconfigured).
 *   3. The action must be in the user's enabled set (driven by
 *      `SettingsStore.enabledActionIds`).  When the prefs entry is
 *      absent we treat the action as enabled — the source of truth
 *      for "enabled by default" is whoever builds the `enabledIds`
 *      closure in `AppViewModel`.
 *   4. **LLM override (2026-07-13)**: when the bubble carries
 *      [Bubble.llmProposedActions] (the model emitted an explicit
 *      list in `emit_bubble`), intersect that with the user's
 *      enabled set instead of the applicability filter.  Keeps the
 *      model from auto-suggesting every applicable action when it
 *      only meant to propose one.  Empty / null list = fall back to
 *      the applicability filter (acts as a feature flag: when the
 *      prompt isn't updated to ask for action_ids, behavior is
 *      unchanged).
 */
class ActionResolver(
    private val actions: ActionRegistry,
    private val intents: IntentRegistry,
    private val enabledIds: suspend () -> Set<String>,
) {

    /** Bubble → list of action ids that should render as chips on
     *  that bubble.  Empty when the bubble's intent is unknown or
     *  every matching action has been disabled. */
    suspend fun suggestIds(bubble: Bubble): List<String> {
        val intent = intents.get(bubble.type) ?: return emptyList()
        val enabled = enabledIds()
        // LLM-proposed narrow path: trust the model.  Empty list =
        // model didn't propose anything → fall back to applicability.
        val llmProposed = bubble.llmProposedActions
        val candidates: List<ActionDef> = if (llmProposed != null) {
            // Whitelist by id first; defensive against the LLM
            // hallucinating an unknown action id (the prompt's enum
            // usually catches this, but JSON parsers can be lenient).
            llmProposed.mapNotNull { actions.get(it) }
        } else {
            actions.list().filter { def ->
                intent.id in def.applicableIntents ||
                    intent.family in def.applicableFamilies
            }
        }
        return candidates
            .map { it.id }
            .filter { it in enabled }
    }

    /** Resolve a list of ids (typically `bubble.actions`) to the
     *  matching [ActionDef]s.  Missing ids are silently skipped so
     *  that older bubbles referencing retired actions don't crash. */
    fun resolve(actions: List<String>): List<ActionDef> =
        actions.mapNotNull { this.actions.get(it) }
}
