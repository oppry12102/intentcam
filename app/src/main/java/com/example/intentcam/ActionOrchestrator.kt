package com.example.intentcam

/**
 * [2026-07-14 Phase A — inversion v3.0] Thin boundary checker for the
 * Intent↔Action framework.  LLM is the loop driver; this class only
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
    // ── Layer 1: Prompt framing ──────────────────────────────────────

    /**
     * Render the actions + requiredInputs block that gets spliced
     * into the system prompt and the `emit_bubble` tool description.
     *
     * Format (one line per action):
     *   `action_id (label) [需要: input_key1=label1, input_key2=label2]`
     * Followed by a single-line workflow hint that tells the LLM:
     *   - "选 1-N 个 action"
     *   - "每个 action 的 required input 要在 bubble.details[] 里"
     *   - "缺数据就调 extract_text / zoom_in 抓"
     *   - "emit_bubble(intent, action_ids, details) 收尾"
     *
     * The exact wording is kept short on purpose — a verbose block
     * would steal attention from the verbatim-OCR rules above it
     * (see `IntentDecl.renderIntentBlock`'s docstring for the same
     * attention-density rationale).
     *
     * Stable across calls for a given registry (the registry is
     * build-once-at-startup), so the result can be cached at the
     * `LlmClient.toolUseSystemPrompt` call site if profiling shows
     * string allocation is a hotspot — for now we recompute per call.
     */
    fun frameAvailableActions(): String = buildString {
        appendLine("可用 actions（选 1-N 个）:")
        actions.list().forEach { def ->
            val inputsDesc = if (def.requiredInputs.isEmpty()) {
                "(无要求)"
            } else {
                def.requiredInputs.joinToString(", ") { "${it.key}=${it.label}" }
            }
            appendLine("  - ${def.id} (${def.label}) [需要: $inputsDesc]")
        }
        appendLine()
        appendLine("Workflow:")
        appendLine("1. 看图 + on-device OCR")
        appendLine("2. 选合适的 actions，每个 action 的 required input 要在 bubble.details[] 里")
        appendLine("3. 缺数据就调 extract_text / zoom_in 抓")
        appendLine("4. emit_bubble(intent, action_ids, details) 收尾")
    }

    // ── Layer 2: Per-emit validator ──────────────────────────────────

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

/** Output of [ActionOrchestrator.validateInputs].  Sealed so the
 *  callers (live UI, ScorerV2, prompt framing) handle both shapes
 *  exhaustively. */
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

/** Output of [ActionOrchestrator.shouldFinalize].  Drives the
 *  cycle's loop control — `FINALIZE` ends the cycle and ships the
 *  bubble, `CONTINUE` triggers another round of LLM exploration
 *  with the missing-input hint injected into the user message. */
sealed class FinalizeDecision {
    /** Stop here; ship the bubble as-is.  [reason] is for logging
     *  and the per-cycle audit trail ("complete" / "max_rounds"). */
    data class FINALIZE(val reason: String) : FinalizeDecision()

    /** Keep going — feed [missing] into the next round's user
     *  message as a "you still need to extract these" hint.  LLM
     *  is free to call any tool (not just the suggested one). */
    data class CONTINUE(val missing: List<String>) : FinalizeDecision()
}
