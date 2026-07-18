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
     *   - **ScorerV3** does NOT read `validatedInputs` — it scores
     *     `r_inputs` by walking `scene.expected_inputs` and
     *     re-running the `InputParsers` against the bubble's text
     *     surface.  The stamp here is for the live UI's chip state
     *     ([ChipStateMapper]), not for eval scoring.
     *
     *  When [Bubble.actions] is empty (legacy emit_bubble that didn't
     *  emit `action_ids`), returns the bubble with empty maps/lists
     *  — nothing to validate = nothing missing.
     *
     *  [2026-07-17 content-rescue] Runs the rescue BEFORE the mark so
     *  a rescued chip (`dial_number` / `redact_id` / `scan_to_pay`)
     *  shows up in `validatedInputs`. The rescue is additive —
     *  LLM-chosen chips are never removed.
     *
     *  [2026-07-18 P3 fix] The rescue + merge now goes through
     *  [com.example.intentcam.ActionRescue] (shared/, single
     *  implementation for prod + eval) and is filtered by [enabled]
     *  — previously rescue bypassed the user's enabled/consent set,
     *  so default-OFF PII chips (dial_number etc.) still rendered.
     *  The share precision-gate ([ActionRescue.dropUnfoundedShare])
     *  is applied here too so prod and eval see the same chips. */
    fun markValidatedInputs(bubble: Bubble, enabled: Set<String>): Bubble {
        val merged = (bubble.actions + ActionRescue.contentRescueActions(bubble))
            .distinct()
            .filter { it in enabled }
        val effectiveBubble = bubble.copy(
            actions = ActionRescue.dropUnfoundedShare(bubble, merged),
        )
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
