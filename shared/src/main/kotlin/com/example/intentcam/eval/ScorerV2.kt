package com.example.intentcam.eval

import com.example.intentcam.Bubble
import com.example.intentcam.Detail
import org.json.JSONArray
import org.json.JSONObject

/**
 * [2026-07-14 Phase D — inversion v3.0] New composite scorer built
 * around the "actions are primary, intents are secondary" thesis
 * from the inversion plan.  Runs side-by-side with the legacy
 * scorer (composite formula `0.45·r1 + 0.45·r2 + 0.10·r3`) for
 * 1-2 cycles of fixtures, so we can compare without hard-cutting
 * the regression net.
 *
 * ## Formula
 * ```
 * composite_v2 = 0.40·r_actions_recall
 *             + 0.30·r_inputs_complete
 *             + 0.15·r_rounds_efficiency
 *             + 0.10·r_intent_derived
 *             + 0.05·r_text
 * ```
 *
 * ## Why this formula (vs the legacy one)
 *
 * - **r_actions_recall (0.40)** is the new dominant signal.  In
 *   the inversion era, "did the model pick the right action set?"
 *   is the real correctness question — the user value is the chip
 *   they can tap, not the intent id that classifies it.  Legacy
 *   r2_type (intent family match) is replaced by
 *   r_intent_derived (a 0/1 signal derived from action overlap)
 *   and dropped to 0.10 weight.
 * - **r_inputs_complete (0.30)** measures the orchestrator's
 *   ability to gather the data each action needs.  This is the
 *   inversion's central novel signal — the legacy formula has no
 *   analog because pre-inversion the verifier just patched the
 *   intent, it didn't check input satisfiability.
 * - **r_rounds_efficiency (0.15)** rewards the LLM for
 *   converging in 1 round (no follow-up tool calls needed).
 *   Calibrated against the empirical 2-3 round median from the
 *   8-suite regression net.  ≥5 rounds scores 0.2 floor.
 * - **r_intent_derived (0.10)** is a 0/1 signal: 1.0 if
 *   `expected_actions ∩ actual ≠ ∅`, else 0.0.  We don't read
 *   `bubble.intent` text — embedding similarity is brittle on
 *   Chinese paraphrase and the action set is a stronger proxy
 *   for intent correctness anyway.
 * - **r_text (0.05)** reuses the legacy textScore (verbatim OCR
 *   fidelity).  Kept as a diagnostic at low weight so the new
 *   composite doesn't fully decouple from text quality.
 *
 * ## Phase D caveats
 *
 * - **r_inputs_complete** is a 1.0 floor in this commit — the GT
 *   files don't yet carry `expected_inputs` (that's Phase D's
 *   `migrate_gt_v2_to_v3.py` script).  Once we run the migration,
 *   the floor becomes the real per-fixture calculation.  The
 *   component is computed here for forward compatibility: when
 *   `expected_inputs` is present in the scene, we walk the
 *   GT's expected actions + verify each required input is
 *   satisfiable from the bubble's surface text using the same
 *   regex patterns as the in-app [com.example.intentcam.PhoneExtractor].
 * - **r_rounds_efficiency** is a 1.0 floor in this commit — the
 *   eval doesn't currently track n_rounds from
 *   [com.example.intentcam.ToolUseLoop].  Phase E will wire a
 *   `nRounds` field through to the per-fixture JSON.
 *
 * ## Component calculation in detail
 *
 * `r_actions_recall(bubble, scene)`:
 *   - `expected` = `scene.expected_actions` (JSONArray of strings)
 *   - `actual`   = `bubble.llmProposedActions` (LLM-emitted
 *     `action_ids` list; null when absent — fall back to
 *     `bubble.actions` (resolver-filtered))
 *   - If `expected.isEmpty()` → 1.0 (no penalty for un-annotated).
 *   - Else → `|expected ∩ actual| / |expected|`
 *
 * `r_intent_derived(bubble, scene)`:
 *   - 1.0 if `r_actions_recall > 0.0` (any expected action
 *     matched), else 0.0.  Phrased as "did the LLM pick any of
 *     the right actions?" — that's our intent-correctness
 *     signal without reading Chinese text.
 *
 * `r_text(bubble, scene)`:
 *   - Reuse [EvalRunner.scoreRound2] textScore (legacy r2 text
 *     hit-rate against GT keywords + detail values).
 *
 * `r_inputs_complete(bubble, scene)`:
 *   - Phase D: 1.0 floor (no penalty).  See caveat above.
 *
 * `r_rounds_efficiency(bubble, scene)`:
 *   - Phase D: 1.0 floor (no penalty).  See caveat above.
 *
 * ## Reuse vs duplication
 *
 * The orchestrator's [com.example.intentcam.ActionOrchestrator.validateInputs]
 * lives in `app/` (Android-coupled because ActionRegistry holds
 * ActionDef.body lambdas).  Eval can't import it directly.  The
 * pragmatic choice: duplicate the ~15 lines of validation logic
 * here, gated by the same regex set as
 * [com.example.intentcam.PhoneExtractor].  When Phase E splits
 * ActionRegistry into a shared metadata view + Android-only
 * body wrapper, we can collapse the duplication.
 */
data class ScorerV2Result(
    val actionsRecall: Double,
    val inputsComplete: Double,
    val intentDerived: Double,
    val roundsEfficiency: Double,
    val text: Double,
    val composite: Double,
) {
    companion object {
        /** Phone-number regexes — duplicate of
         *  [com.example.intentcam.PhoneExtractor] (lives in app/ so
         *  can't be imported).  Kept in sync by hand; the canonical
         *  copy is the eval-side one for fixtures without an
         *  `action_<id>_enabled` pref. */
        private val MOBILE_REGEX = Regex("""1[3-9]\d{9}""")
        private val SERVICE_REGEX = Regex("""(?:400|800)[\s-]?\d{3,4}[\s-]?\d{3,4}""")
        private val LANDLINE_REGEX = Regex("""\b0?\d{3,4}[\s-]?\d{7,8}\b""")

        fun compute(bubble: Bubble?, scene: JSONObject, textScore: Double): ScorerV2Result {
            val actionsRecall = computeActionsRecall(bubble, scene)
            val intentDerived = if (actionsRecall > 0.0) 1.0 else 0.0
            val text = textScore.coerceIn(0.0, 1.0)
            // Phase D floors: real wiring lands in Phase E.
            val inputsComplete = 1.0
            val roundsEfficiency = 1.0

            val composite = 0.40 * actionsRecall +
                0.30 * inputsComplete +
                0.15 * roundsEfficiency +
                0.10 * intentDerived +
                0.05 * text
            return ScorerV2Result(
                actionsRecall = actionsRecall,
                inputsComplete = inputsComplete,
                intentDerived = intentDerived,
                roundsEfficiency = roundsEfficiency,
                text = text,
                composite = composite.coerceIn(0.0, 1.0),
            )
        }

        /** Set intersection of expected vs actual action ids.  Empty
         *  expected → 1.0 (no penalty for un-annotated fixtures). */
        private fun computeActionsRecall(bubble: Bubble?, scene: JSONObject): Double {
            val expectedArr = scene.optJSONArray("expected_actions") ?: return 1.0
            val expected = mutableSetOf<String>()
            for (i in 0 until expectedArr.length()) {
                expected.add(expectedArr.getString(i))
            }
            if (expected.isEmpty()) return 1.0
            val bubble0 = bubble ?: return 0.0
            // Prefer the raw LLM-emitted list (intent of the eval —
            // did the LLM get it right?).  Fall back to the
            // resolver-filtered list when LLM didn't emit action_ids.
            val actual: Set<String> = when {
                !bubble0.llmProposedActions.isNullOrEmpty() ->
                    bubble0.llmProposedActions.toSet()
                else -> bubble0.actions.toSet()
            }
            val hit = expected.count { it in actual }
            return hit.toDouble() / expected.size
        }

        /** Convenience: did this bubble carry a phone number?  Used
         *  to set [computeInputsComplete]'s per-input satisfaction
         *  once Phase D's GT migration populates expected_inputs.
         *  Mirrors [com.example.intentcam.PhoneExtractor.firstMatch]. */
        internal fun bubbleHasPhoneNumber(bubble: Bubble): Boolean {
            val corpus = buildString {
                append(bubble.title).append('\n')
                append(bubble.detail).append('\n')
                bubble.details.forEach { d -> append(d.value).append('\n') }
            }
            return MOBILE_REGEX.containsMatchIn(corpus) ||
                SERVICE_REGEX.containsMatchIn(corpus) ||
                LANDLINE_REGEX.containsMatchIn(corpus)
        }

        /** Convenience: did this bubble carry any non-blank text
         *  (title/detail/detail-rows)?  Mirrors the `textContent`
         *  parser in [com.example.intentcam.InputParsers]. */
        internal fun bubbleHasTextContent(bubble: Bubble): Boolean =
            bubble.title.isNotBlank() || bubble.detail.isNotBlank() ||
                bubble.details.any { it.value.isNotBlank() }
    }
}

/** Helper: extract all detail values from a bubble as a single
 *  corpus string.  Used by future phases that wire ScorerV2's
 *  r_inputs_complete against GT `expected_inputs`. */
internal fun Bubble.corpus(): String = buildString {
    append(title).append('\n')
    append(detail).append('\n')
    details.forEach { d -> append(d.value).append('\n') }
}

/** Helper: format the per-fixture ScorerV2 numbers into the same
 *  human-readable string style as the legacy scorer line.  Used
 *  by [EvalRunner] to print a side-by-side `composite (old) |
 *  composite_v2 (new)` summary per fixture. */
internal fun ScorerV2Result.format(): String =
    "actions_recall=${"%.2f".format(actionsRecall)} " +
    "intent_derived=${"%.2f".format(intentDerived)} " +
    "text=${"%.2f".format(text)} " +
    "composite_v2=${"%.2f".format(composite)}"
