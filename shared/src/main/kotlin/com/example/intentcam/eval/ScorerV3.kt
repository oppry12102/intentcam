package com.example.intentcam.eval

import com.example.intentcam.Bubble

/**
 * Action-first scorer (v4).  Companion scorer that runs
 * side-by-side with [ScorerV2Result] during the dual-run window.
 *
 * ## Formula
 * ```
 * composite_v3 = 0.55 · r_actions
 *             + 0.30 · r_text
 *             + 0.15 · r_inputs
 * ```
 *
 * ## Why this formula
 *
 * The Phase J + commit `072af4d` (2026-07-15) action merge collapsed
 * six per-intent share-text actions into one intent-agnostic `share`
 * — that empirically confirmed: most intents resolve to the same
 * `share` chip, so the LLM's classification can stay fuzzy at the
 * 14-id boundary without compromising the user's actual outcome
 * (which chip fires).
 *
 * `r_type` is dropped from the canonical dimension and demoted to a
 * UI accent input. `r_actions` moves from 0.20 to **0.55** to become
 * the headline signal — it's the user-can-act score.
 *
 * - **r_actions (0.55)** — Jaccard `|∩|/|∪|` of expected vs actual
 *   action ids. Same logic as [ScorerV2Result]; promoted to the
 *   dominant weight. Empty expected → 1.0 (un-annotated floor).
 * - **r_text (0.30)** — verbatim OCR fidelity. Reused from
 *   [ScorerV2Result] inputs (no re-computation).
 * - **r_inputs (0.15)** — fraction of `expected_inputs` satisfiable
 *   from the bubble's text surface. Reused from [ScorerV2Result].
 *
 * ## Why `r_intent_hint` was dropped (Step 2 sign-off, 2026-07-15)
 *
 * The original v4 plan proposed a fourth dimension `r_intent_hint`
 * graded against GT `expected_intent_hint`. Audit revealed: GT has
 * no reliable per-fixture "user want" annotation — RCTW XMLs
 * carry bbox + text only, not user intent. `what_is_pictured` is
 * the wrong semantic axis (image content, not user goal). With
 * GT null, ScorerV3's `r_intent_hint` collapsed to a 0.5 floor
 * (LLM present + GT null) for every fixture — inception noise,
 * not signal. Action-axis weight lifted to 0.55 to absorb the
 * released 10%. See plan `~/.claude/plans/action-first-architecture.md`
 * §2.2 for the full reasoning.
 *
 * ## Canonical switch
 *
 * `ScorerV2Result` remains the regression-gating scorer (read by
 * `scripts/check_regression.py`) until IntentCam Dev signs off on
 * the dual-run report. `ScorerV3Result` runs in parallel — every
 * suite emits both numbers; no canonical switch until
 * composite_v2 PASS + composite_v3 |Δ| ≤ 0.03 week-over-week.
 *
 * ## Reuse vs duplication
 *
 * `r_actions` and `r_inputs` logic are inherited from
 * [ScorerV2Result] — same Jaccard / same inputSatisfied dispatch.
 * No duplication; this class delegates to ScorerV2 for the shared
 * computation paths via the cached scores we hand in.
 */
data class ScorerV3Result(
    val actions: Double,
    val text: Double,
    val inputs: Double,
    val composite: Double,
) {
    companion object {
        /**
         * @param bubble LLM-emitted bubble (may be null on error).
         *   Field-specific data is currently unused (ScorerV3 only
         *   reads action-ids via [bubble.llmProposedActions] /
         *   [bubble.actions] inside [ScorerV2Result]). Kept for
         *   forward-compat if a future v4 axis needs `bubble.title`
         *   or detail text.
         * @param scene  GT fixture JSON. Reads `expected_actions`
         *   and `expected_inputs` — both already populated across
         *   the 11 production suites by
         *   `scripts/migrate_gt_v2_to_v3.py` (commit `072af4d`).
         * @param textScore r_text — verbatim OCR fidelity, computed
         *   by `EvalRunner.scoreRound2Text`. Reused as-is from the
         *   parallel ScorerV2 call site (no double evaluation).
         * @param inputsScore r_inputs — fraction of expected_inputs
         *   satisfiable, computed inside [ScorerV2Result.compute].
         *   Reused as-is (no double evaluation).
         * @param actionsScore r_actions — Jaccard overlap, computed
         *   inside [ScorerV2Result.compute]. Reused as-is.
         *
         * The unused `bubble` + unused `scene` parameters are kept
         * so the call-site signature matches ScorerV2's
         * `compute(bubble, scene, textScore, typeScore)` for
         * diff-readability — ScorerV2 reads `bubble` itself,
         * ScorerV3 reads it only via the cached scores already
         * computed by ScorerV2.
         */
        @Suppress("UNUSED_PARAMETER")
        fun compute(
            bubble: Bubble?,
            scene: org.json.JSONObject,
            textScore: Double,
            inputsScore: Double,
            actionsScore: Double,
        ): ScorerV3Result {
            val text = textScore.coerceIn(0.0, 1.0)
            val inputs = inputsScore.coerceIn(0.0, 1.0)
            val actions = actionsScore.coerceIn(0.0, 1.0)

            val composite = 0.55 * actions +
                0.30 * text +
                0.15 * inputs
            return ScorerV3Result(
                actions = actions,
                text = text,
                inputs = inputs,
                composite = composite.coerceIn(0.0, 1.0),
            )
        }
    }
}

/** Helper: format the per-fixture ScorerV3 numbers into a
 *  human-readable per-fixture line for [EvalRunner]'s console
 *  output. Mirrors [ScorerV2Result.format]. */
internal fun ScorerV3Result.format(): String =
    "v3_actions=${"%.2f".format(actions)} " +
        "v3_text=${"%.2f".format(text)} " +
        "v3_inputs=${"%.2f".format(inputs)} " +
        "composite_v3=${"%.2f".format(composite)}"
