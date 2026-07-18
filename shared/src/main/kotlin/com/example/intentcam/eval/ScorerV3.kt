package com.example.intentcam.eval

import com.example.intentcam.Bubble
import org.json.JSONObject

/**
 * Action-first canonical scorer (the sole scorer after the 2026-07-17
 * intent-taxonomy retirement). Actions are the user's real need; intent
 * is free-form UX glue the LLM summarizes, NOT a scored dimension.
 *
 * ## Formula
 * ```
 * composite = 0.55 · r_actions   (recall)
 *           + 0.30 · r_text      (verbatim OCR fidelity)
 *           + 0.15 · r_inputs    (expected_inputs satisfaction)
 * ```
 *
 * - **r_actions (0.55)** — RECALL `|expected ∩ actual| / |expected|` of
 *   expected action ids against the VISIBLE chip set (`Bubble.actions`
 *   = LLM proposals + content-rescue; falls back to raw
 *   `llmProposedActions` for legacy bubbles). Recall (not Jaccard) so
 *   add-only rescue credits hits without penalizing over-fire. Empty
 *   expected → 1.0 (un-annotated floor); null bubble + nonempty → 0.0.
 * - **r_text (0.30)** — verbatim OCR fidelity, computed by
 *   `EvalRunner.scoreRound2Text` and passed in.
 * - **r_inputs (0.15)** — fraction of `scene.expected_inputs`
 *   satisfiable from the bubble's text surface via the prod
 *   [com.example.intentcam.InputParsers] (single source of truth).
 *
 * ## Why no r_type
 *
 * The 2026-07-17 architecture retirement dropped the registered intent
 * taxonomy: intent is the LLM's free-form summary (`Bubble.intent`),
 * not a classified/scored enum. Scoring intent classification (the old
 * `r_type`, 0.35 weight in the retired ScorerV2) measured the wrong
 * axis — the user's outcome is which chips fire, not which bucket the
 * LLM named. r_actions (recall) IS the user-can-act signal.
 *
 * ## Reuse vs duplication
 *
 * `r_inputs` validation logic mirrors the production
 * [com.example.intentcam.ActionOrchestrator.validateInputs] (~15 lines)
 * because that orchestrator lives in `app/` (Android-coupled via
 * ActionRegistry's body lambdas) and eval can't import it. The regex
 * set itself is shared — both call [com.example.intentcam.InputParsers].
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
         * @param scene  GT fixture JSON. Reads `expected_actions` +
         *   `expected_inputs` (populated across the 11 production
         *   suites by `scripts/migrate_gt_v2_to_v3.py`).
         * @param textScore r_text — verbatim OCR fidelity, computed by
         *   `EvalRunner.scoreRound2Text`.
         */
        fun compute(
            bubble: Bubble?,
            scene: JSONObject,
            textScore: Double,
        ): ScorerV3Result {
            val text = textScore.coerceIn(0.0, 1.0)
            val actions = computeActions(bubble, scene)
            val inputs = computeInputsComplete(bubble, scene)

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

        /** Recall of expected action ids against the VISIBLE chip set:
         *  `|expected ∩ actual| / |expected|`.  Of the chips the user
         *  was supposed to see, how many did they actually see?
         *
         *  `actual` = the visible set (`Bubble.actions` once the
         *  orchestrator / eval mirror has folded LLM proposals +
         *  content-rescue into it; falls back to raw
         *  `llmProposedActions` for legacy bubbles).  RECALL (not
         *  Jaccard): content rescue is add-only, so a rescue chip
         *  matching expected must CREDIT and a rescue over-fire must
         *  NOT dilute (Jaccard fought the add-only design).  Trade-off:
         *  recall no longer penalizes LLM over-emission; r_text/r_inputs
         *  carry the precision signal.
         *
         *  Empty expected → 1.0; null bubble + nonempty → 0.0. */
        private fun computeActions(bubble: Bubble?, scene: JSONObject): Double {
            val expectedArr = scene.optJSONArray("expected_actions") ?: return 1.0
            val expected = mutableSetOf<String>()
            for (i in 0 until expectedArr.length()) {
                expected.add(expectedArr.getString(i))
            }
            if (expected.isEmpty()) return 1.0
            val bubble0 = bubble ?: return 0.0
            val actual: Set<String> = when {
                bubble0.actions.isNotEmpty() -> bubble0.actions.toSet()
                !bubble0.llmProposedActions.isNullOrEmpty() ->
                    bubble0.llmProposedActions.toSet()
                else -> emptySet()
            }
            val hits = expected.intersect(actual).size
            return hits.toDouble() / expected.size
        }

        /** Convenience: did this bubble carry a phone number?  Used by
         *  [inputSatisfied] for the `phone_number` key.  Mirrors
         *  [com.example.intentcam.InputParsers.phoneNumber]. */
        internal fun bubbleHasPhoneNumber(bubble: Bubble): Boolean =
            com.example.intentcam.InputParsers.phoneNumber(bubble) != null

        /** Per-input satisfaction check.  Dispatches each input key to
         *  the SAME parser prod uses ([com.example.intentcam.InputParsers])
         *  so eval and prod agree — single source of truth per the
         *  input-parsers-drift-risk ADR.  Mapping mirrors
         *  `ACTION_REQUIRED_INPUTS` in `scripts/migrate_gt_v2_to_v3.py`. */
        private fun inputSatisfied(
            bubble: Bubble,
            @Suppress("UNUSED_PARAMETER") actionId: String,
            key: String,
        ): Boolean = when {
            key == "phone_number" -> bubbleHasPhoneNumber(bubble)
            key == "query" ->
                com.example.intentcam.InputParsers.locationQuery(bubble) != null
            key == "text" ->
                com.example.intentcam.InputParsers.textContent(bubble) != null
            else -> false
        }

        /** Walk scene.expected_inputs, check each entry against the
         *  bubble's text surface via [inputSatisfied].  Empty → 1.0;
         *  null bubble + nonempty → 0.0. */
        private fun computeInputsComplete(bubble: Bubble?, scene: JSONObject): Double {
            val arr = scene.optJSONArray("expected_inputs") ?: return 1.0
            if (arr.length() == 0) return 1.0
            val b = bubble ?: return 0.0
            var satisfied = 0
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val actionId = entry.optString("action")
                val key = entry.optString("key")
                if (inputSatisfied(b, actionId, key)) satisfied++
            }
            return satisfied.toDouble() / arr.length()
        }
    }
}

/** Helper: format the per-fixture numbers into a human-readable line
 *  for [EvalRunner]'s console output. */
internal fun ScorerV3Result.format(): String =
    "actions=${"%.2f".format(actions)} " +
        "text=${"%.2f".format(text)} " +
        "inputs=${"%.2f".format(inputs)} " +
        "composite=${"%.2f".format(composite)}"
