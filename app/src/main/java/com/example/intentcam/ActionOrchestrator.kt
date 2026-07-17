package com.example.intentcam

/**
 * Thin boundary checker for the Intent↔Action framework.  LLM is
 * the loop driver; this class only
 * runs at three boundaries:
 *
 *   1. **Before round 1** — [frameAvailableActions] renders the
 *      actions + requiredInputs block that gets spliced into the
 *      system prompt so the LLM knows what's available.
 *   2. **After every `emit_bubble`** — [validateInputs] parses each
 *      action's required inputs from the bubble's text surface and
 *      reports which are missing.
 *   3. **At cycle end** — [shouldFinalize] decides "stop here" vs
 *      "ask the LLM to continue exploring" based on missing-input
 *      coverage and the round counter.
 *
 * The orchestrator does NOT decide which actions to surface
 * (LLM does, via [Bubble.llmProposedActions]), does NOT drive
 * tool calls (LLM does), and does NOT flip intent types (the
 * legacy [IntentVerifier] still handles that in Phase A-D as a
 * safety net — removed in Phase E).
 *
 * Kept side-effect-free.  Lives in `app/` (not `shared/`) because
 * it references [ActionRegistry], which is Android-coupled (its
 * [ActionDef.body] lambda takes `android.content.Context`).  The
 * body of this class itself touches no Android types — only the
 * type signatures do.  When Phase E migrates the action framework
 * further, the orchestrator can move back to `shared/` once
 * [ActionRegistry] is split into a metadata-only shared view +
 * Android-only body wrapper.
 *
 * Consumed by:
 *   - `AppViewModel` (Phase C) — [validateInputs] drives live-UI
 *     ghost-vs-solid chip state.
 *   - `eval/ScorerV2` (Phase D) — duplicates the [validateInputs]
 *     body inline (≈15 lines) to compute `r_inputs_complete`
 *     without depending on app/.  Sharing the code would require
 *     lifting [ActionRegistry] into shared/, which is Phase E's
 *     layer-split refactor.  The duplication is contained and
 *     tested against the same fixtures.
 *
 * Total ~120 lines; intentionally small so the entire
 * "what does the orchestrator do" surface fits in one screen.
 */
class ActionOrchestrator(
    private val actions: ActionRegistry,
) {
    // ── Per-emit validator ──────────────────────────────────────────

    /**
     * Pure function: given the LLM's latest emit_bubble, report
     * whether every chosen action's required inputs are satisfiable
     * from the bubble's text surface.  Used by:
     *
     *   - **Phase C live UI** — chip with any missing input renders
     *     as a ghost (gray + tooltip "需要 X"), chip with all
     *     inputs satisfied renders solid (the action fires on tap).
     *   - **Phase D ScorerV2** — `r_inputs_complete` =
     *     `|satisfied_inputs| / |required_inputs|` averaged across
     *     the fixture's expected actions.
     *   - **Phase E orchestrator frame** — [missingInputLabels]
     *     string goes into the next round's user message when the
     *     LLM should explore more.
     *
     * `bubble.actions` is the source of truth for "which actions to
     * validate" — it carries the post-resolve chip list (LLM
     * proposal intersected with applicability + enabled set).  When
     * a bubble has no actions yet (e.g. legacy emit_bubble that
     * didn't emit `action_ids`), the result is `Complete` by
     * definition: nothing to validate = nothing missing.
     */
    fun validateInputs(bubble: Bubble): InputsValidation {
        if (bubble.actions.isEmpty()) return InputsValidation.Complete
        val missingPerAction = LinkedHashMap<String, List<String>>()
        bubble.actions.forEach { actionId ->
            val def = actions.get(actionId) ?: return@forEach
            if (def.requiredInputs.isEmpty()) return@forEach
            val missing = def.requiredInputs
                .filter { spec -> spec.parser(bubble) == null }
                .map { it.key }
            if (missing.isNotEmpty()) missingPerAction[actionId] = missing
        }
        return if (missingPerAction.isEmpty()) {
            InputsValidation.Complete
        } else {
            InputsValidation.Missing(perAction = missingPerAction)
        }
    }

    /** Flat list of missing input keys across all of [bubble]'s
     *  actions (deduplicated, ordered by first appearance).
     *  Convenience accessor for the orchestrator's prompt framing
     *  and for the live-UI's "你需要 ..." footer.  Returns empty
     *  list when validation is Complete. */
    fun missingInputKeys(bubble: Bubble): List<String> =
        when (val v = validateInputs(bubble)) {
            is InputsValidation.Complete -> emptyList()
            is InputsValidation.Missing -> v.perAction.values.flatten().distinct()
        }

    /** Human-readable label list (e.g. `["手机号", "地点或地址"]`),
     *  parallel to [missingInputKeys].  Used when prompting the LLM
     *  or rendering a ghost-chip tooltip. */
    fun missingInputLabels(bubble: Bubble): List<String> {
        if (bubble.actions.isEmpty()) return emptyList()
        val labels = LinkedHashMap<String, String>()
        bubble.actions.forEach { actionId ->
            val def = actions.get(actionId) ?: return@forEach
            def.requiredInputs.forEach { spec ->
                if (spec.parser(bubble) == null) {
                    labels.putIfAbsent(spec.key, spec.label)
                }
            }
        }
        return labels.values.toList()
    }

    /** Pure projection: stamp [validateInputs] result
     *  onto the bubble's data-class fields.  Returns a new bubble
     *  with `validatedInputs` (per-action true/false map) +
     *  `pendingInputs` (cross-action missing-key list, deduped)
     *  populated, leaving everything else intact.
     *
     *  Used by:
     *   - **ToolUseLoop.runCycle** (via the `markValidated` callback)
     *     — stamps the bubble BEFORE the `onEmit` gate and the
     *     terminal `onProgress`, so `CycleJob.bubble.value` (what the
     *     live UI renders) carries the populated fields and
     *     `shouldFinalize` can detect missing inputs.
     *   - **ScorerV2** does NOT read `validatedInputs` — it scores
     *     `r_inputs_complete` by walking `scene.expected_inputs` and
     *     re-running the `InputParsers` against the bubble's text
     *     surface.  The stamp here is for the live UI's chip state
     *     ([ChipStateMapper]), not for eval scoring.
     *
     *  When [Bubble.actions] is empty (legacy emit_bubble that didn't
     *  emit `action_ids`), returns the bubble with empty maps/lists
     *  — nothing to validate = nothing missing.
     *
     *  [2026-07-17 content-rescue] Runs [rescueActions] BEFORE the
     *  mark so the rescue chip (`dial_number` / `redact_id` /
     *  `scan_to_pay`) shows up in `validatedInputs` and gets scored
     *  correctly by downstream consumers. The rescue is additive —
     *  LLM-chosen chips are never removed. */
    fun markValidatedInputs(bubble: Bubble): Bubble {
        val rescued = rescueActions(bubble)
        val effectiveBubble = if (rescued.isEmpty()) bubble
            else bubble.copy(actions = (bubble.actions + rescued).distinct())
        return when (val v = validateInputs(effectiveBubble)) {
            is InputsValidation.Complete -> effectiveBubble.copy(
                validatedInputs = effectiveBubble.actions.associateWith { true },
                pendingInputs = emptyList(),
            )
            is InputsValidation.Missing -> effectiveBubble.copy(
                validatedInputs = effectiveBubble.actions.associateWith { actionId ->
                    actionId !in v.perAction
                },
                pendingInputs = missingInputKeys(effectiveBubble),
            )
        }
    }

    /**
     * Content-based rescue — scan [bubble]'s text surface (title +
     * detail + details[].value) for actionable patterns the LLM may
     * have omitted. Returns the list of action ids to ADD to
     * [Bubble.actions] (additive — LLM-chosen chips are never
     * removed). The LLM remains authoritative for type + action
     * selection; this only fills gaps where a clear, verifiable
     * content cue supports a chip the LLM didn't pick.
     *
     * Designed to recover mixed-content fixtures where the LLM
     * correctly classifies the type (e.g. `location`) but misses
     * that the image also contains a phone number / id document /
     * payment QR. Per-intent [soft hint][com.example.intentcam.IntentDecl.canonicalAction]
     * doesn't help those because the type isn't the rescue target.
     *
     * **Rules** (each is independent — multiple can fire on one bubble):
     *   - `dial_number` rescue — `InputParsers.phoneNumber(bubble) != null`
     *     AND `dial_number` not already in `bubble.actions`. Covers
     *     phone-20 mixed-content (restaurant / school / billboard
     *     fixtures where the LLM classifies as location but a phone
     *     number is present in OCR).
     *   - `redact_id` rescue — `InputParsers.idDocument(bubble) != null`
     *     AND `redact_id` not already in `bubble.actions`. Covers
     *     pii-20 fixtures (clinic registration / business license /
     *     id-document surfaces).
     *   - `scan_to_pay` rescue — `InputParsers.paymentQr(bubble) != null`
     *     AND `scan_to_pay` not already in `bubble.actions`. Covers
     *     payment_qr intent fixtures.
     *
     * **NOT rescued** (deliberate, see plan
     * `~/.claude/plans/sorted-meandering-pond.md` "Why this design"):
     *   - `open_in_maps` — `InputParsers.locationQuery` is too lenient
     *     (returns any non-blank title), false-positive rate would
     *     put `open_in_maps` on every bubble. Soft hint + LLM is the
     *     lever for location intent.
     *   - `share` — `InputParsers.textContent` always returns non-null
     *     for any populated bubble, would put `share` on every
     *     bubble. Soft hint is the lever for share-able intents.
     *
     * The eval pipeline's [com.example.intentcam.eval.EvalRunner.markValidated]
     * callback mirrors this rescue (via [com.example.intentcam.InputParsers]
     * shared state) so eval scores what users actually see.
     */
    fun rescueActions(bubble: Bubble): List<String> {
        val current = bubble.actions.toSet()
        val rescue = mutableListOf<String>()
        if ("dial_number" !in current && com.example.intentcam.InputParsers.phoneNumber(bubble) != null) {
            rescue += "dial_number"
        }
        if ("redact_id" !in current && com.example.intentcam.InputParsers.idDocument(bubble) != null) {
            rescue += "redact_id"
        }
        if ("scan_to_pay" !in current && com.example.intentcam.InputParsers.paymentQr(bubble) != null) {
            rescue += "scan_to_pay"
        }
        return rescue
    }

    // ── Layer 3: Finalizer ───────────────────────────────────────────

    /**
     * Pure function: given the LLM's latest emit_bubble + the
     * round counter, decide whether to stop or keep exploring.
     *
     * Rules (in order):
     *   1. [maxRounds] reached → FINALIZE("max_rounds").  Safety
     *      net so a stubborn LLM can't loop forever; user waits
     *      max ~[maxRounds] × 2-3 s before the partial bubble
     *      ships as-is (ghost chips intact).
     *   2. All required inputs satisfied → FINALIZE("complete").
     *   3. Some inputs missing → CONTINUE(missing).
     *
     * Default [maxRounds] is 4 — calibrated against the empirical
     * 2-3 round median for a `phone` bubble on PP-OCRv4 mobile
     * (see eval-phaseG-15-fixture-2026-07-12 + phase-H-2026-07-12
     * memory for the timing data).  Bumping to 6 is safe but the
     * user starts to feel the wait; 3 is too aggressive for the
     * real_estate / recruit multi-signal cases.
     */
    fun shouldFinalize(
        bubble: Bubble,
        round: Int,
        maxRounds: Int = 4,
    ): FinalizeDecision = when (val v = validateInputs(bubble)) {
        is InputsValidation.Complete ->
            FinalizeDecision.FINALIZE(reason = "complete")
        is InputsValidation.Missing -> when {
            round >= maxRounds ->
                FinalizeDecision.FINALIZE(reason = "max_rounds")
            else ->
                FinalizeDecision.CONTINUE(
                    missing = v.perAction.values.flatten().distinct()
                )
        }
    }
}

// `InputsValidation` and `FinalizeDecision` moved to
// `shared/.../ActionArgs.kt` so `ToolUseLoop.runCycle`'s `onEmit`
// callback can return a `FinalizeDecision` without dragging Android
// types into `:shared/`.  The class itself stays here because it
// depends on [ActionRegistry] (Android-coupled via `ActionDef.body`
// taking `android.content.Context`).
//
// Added `IntentAlignmentCheck` + `validateIntentAlignment` for the
// soft intent-validation gate.  Kept here as a free top-level fn +
// sealed class so callers don't have to thread the orchestrator
// instance through to read intent alignment.

// Output of [validateIntentAlignment].  Sealed so the
//  caller (live UI, eval, debug overlay) handles both shapes
//  exhaustively.  Cross-platform type in `:shared/` for parity with
//  [InputsValidation] — kept here for now because the alignment
//  helper itself depends on the Android-coupled [ActionRegistry].
//  Move to shared/ if a future decoupling pulls action-noun tables
//  into a cross-platform data file.
sealed class IntentAlignmentCheck {
    /** bubble.intent mentions at least one primary noun for at least
     *  one of the bubble's chosen actions.  Healthy state. */
    object Aligned : IntentAlignmentCheck()
    /** bubble.intent doesn't mention any primary noun from any chosen
     *  action.  Soft warning — the orchestrator still ships the
     *  bubble; the caller decides whether to surface a UI hint. */
    data class Mismatch(val missingNouns: Set<String>) : IntentAlignmentCheck()
}

/** Per-action primary-noun table for intent alignment.
 *  Used by [validateIntentAlignment] to check that bubble.intent's
 *  free-form Chinese phrase mentions at least one noun associated
 *  with the bubble's chosen action set.  When it doesn't, the LLM
 *  probably drifted from the action it picked — e.g. chose
 *  `dial_number` but wrote "我想看营业时间" as the intent.  That's
 *  not necessarily wrong (the chip still works), but it's worth
 *  surfacing as a debug hint.
 *
 *  Kept as a function rather than a static table so the map lives
 *  next to [ActionOrchestrator] and stays in sync with the
 *  [ActionRegistry] it queries.  Empty map → no alignment check
 *  possible (caller falls back to `Aligned`).
 *
 *  Nouns are the most common short tokens a Chinese user would use
 *  to describe the action's purpose.  Order in the list is
 *  irrelevant — any single hit counts as Aligned.  Compiled at
 *  call time so adding a new action means adding one entry below. */
private fun primaryNounsFor(actionId: String): List<String> = when (actionId) {
    "dial_number"    -> listOf("拨打", "电话", "手机号", "联系", "拨号")
    "open_in_maps"   -> listOf("导航", "地图", "位置", "找", "去", "路线", "步行", "开车", "到", "在")
    "scan_to_pay"    -> listOf("支付", "付款", "收款", "扫码", "转账", "扫一扫")
    "redact_id"      -> listOf("证件", "身份证", "驾照", "营业执照", "证照")
    // Unified `share` — union of the six former per-intent share
    //  actions' nouns (房源/招聘/警示/菜单/营业时间/促销).
    "share"          -> listOf(
        "房源", "租房", "出租", "二手房", "楼盘", "中介",
        "招聘", "招工", "求职", "兼职", "高薪", "工作", "招聘启事",
        "警告", "警示", "危险", "禁止", "请勿", "注意",
        "菜单", "菜品", "招牌菜", "套餐", "价格",
        "营业时间", "营业", "开放", "关门", "开门",
        "促销", "特价", "折扣", "满减", "优惠", "活动",
    )
    else -> emptyList()
}

/** Soft intent-alignment gate.  Returns
 *  [IntentAlignmentCheck.Aligned] when [bubble]'s free-form
 *  `intent` text mentions at least one primary noun from any of
 *  the bubble's chosen actions; [IntentAlignmentCheck.Mismatch]
 *  with the set of nouns that the intent should have mentioned.
 *
 *  Empty `bubble.actions` (legacy emit_bubble that didn't emit
 *  `action_ids`) → Aligned by default.  Action with no entry in
 *  [primaryNounsFor] → that action's noun set is empty, doesn't
 *  count as either Aligned or Mismatch (skipped).  When every
 *  chosen action has an empty noun set, the bubble gets
 *  Aligned (can't disagree on nothing).
 *
 *  Wired by [CycleManager] to log a `INTENT_WARN` debug line —
 *  not a failure, just a breadcrumb for the next regression
 *  investigation.  Eval-side ScorerV2 reads the warn flag for
 *  `r_intent_derived` breakdown. */
fun validateIntentAlignment(bubble: Bubble): IntentAlignmentCheck {
    if (bubble.actions.isEmpty()) return IntentAlignmentCheck.Aligned
    val intentText = bubble.intent.takeIf { it.isNotBlank() } ?: bubble.title
    if (intentText.isBlank()) return IntentAlignmentCheck.Aligned
    // Collect noun sets across the bubble's chosen actions; if
    // ANY action's set has a hit, we're Aligned (don't penalize
    // multi-action bubbles for partial coverage).
    val missing = LinkedHashSet<String>()
    var aligned = false
    bubble.actions.forEach { actionId ->
        val nouns = primaryNounsFor(actionId)
        if (nouns.isEmpty()) return@forEach
        val hit = nouns.any { intentText.contains(it) }
        if (hit) aligned = true else missing.addAll(nouns)
    }
    return if (aligned || missing.isEmpty()) {
        IntentAlignmentCheck.Aligned
    } else {
        IntentAlignmentCheck.Mismatch(missingNouns = missing)
    }
}
// `shared/.../ActionArgs.kt` so `ToolUseLoop.runCycle`'s `onEmit`
// callback can return a `FinalizeDecision` without dragging Android
// types into `:shared/`.  The class itself stays here because it
// depends on [ActionRegistry] (Android-coupled via `ActionDef.body`
// taking `android.content.Context`).
