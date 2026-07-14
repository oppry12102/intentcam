package com.example.intentcam.eval

import com.example.intentcam.Bubble
import com.example.intentcam.Detail
import org.json.JSONArray
import org.json.JSONObject

/**
 * [2026-07-15 scoring redesign] Intent-first composite scorer.  This
 * is now the SOLE score — the legacy `0.45·r1 + 0.45·r2 + 0.10·r3`
 * composite has been retired (see EvalRunner).
 *
 * ## Formula
 * ```
 * composite_v2 = 0.35·r_type
 *             + 0.25·r_text
 *             + 0.20·r_actions
 *             + 0.20·r_inputs
 * ```
 *
 * ## Why this formula
 *
 * The 2026-07-15 action merge collapsed six per-intent share-text
 * actions into one intent-agnostic `share` (7 intents → 1 id).  That
 * gutted the old `r_actions_recall`-dominant formula: emitting
 * `share` scored 1.0 regardless of whether the model understood the
 * scene, and `r_intent_derived` (derived from action overlap) +
 * `r_rounds_efficiency` (a hardcoded 1.0 floor) carried no real
 * signal.  This redesign restores intent-classification as the
 * dominant measured dimension and drops the dead ones.
 *
 * - **r_type (0.35)** — graded intent-type correctness against
 *   `bubble.type`: exact 1.0 / same registered family 0.7 / both
 *   registered but wrong family 0.3 / empty·unknown 0.0.  Computed
 *   in [EvalRunner] (which owns the populated `IntentRegistry`) and
 *   passed in as `typeScore`, mirroring how `textScore` is passed.
 *   This is the discrimination the `share` merge erased.
 * - **r_text (0.25)** — verbatim OCR fidelity: hit-rate against GT
 *   `expected_description_keywords` + `expected_details`.  Computed
 *   by [EvalRunner.scoreRound2Text] and passed as `textScore`.  The
 *   most objective signal, promoted from its old 0.05.
 * - **r_actions (0.20)** — Jaccard `|∩|/|∪|` of expected vs actual
 *   action ids (not pure recall), so spurious / wrong chips are
 *   penalized.  Empty expected → 1.0 (un-annotated fixtures).
 * - **r_inputs (0.20)** — fraction of `expected_inputs` satisfiable
 *   from the bubble's text surface (phone regex / query / text).
 *
 * ## Component calculation in detail
 *
 * `r_actions(bubble, scene)`:
 *   - `expected` = `scene.expected_actions` (deduped to a set)
 *   - `actual`   = `bubble.llmProposedActions` (LLM-emitted
 *     `action_ids`; fall back to `bubble.actions` when absent),
 *     deduped to a set
 *   - `expected.isEmpty()` → 1.0; null bubble + nonempty → 0.0
 *   - else → `|expected ∩ actual| / |expected ∪ actual|`
 *
 * `r_inputs(bubble, scene)`:
 *   - fraction of `expected_inputs` entries satisfiable via
 *     [inputSatisfied]; empty → 1.0; null bubble + nonempty → 0.0.
 *
 * ## Reuse vs duplication
 *
 * The orchestrator's [com.example.intentcam.ActionOrchestrator.validateInputs]
 * lives in `app/` (Android-coupled because ActionRegistry holds
 * ActionDef.body lambdas).  Eval can't import it directly, so the
 * ~15 lines of input-validation logic are duplicated here, gated by
 * the same regex set as [com.example.intentcam.PhoneExtractor].
 */
data class ScorerV2Result(
    val type: Double,
    val text: Double,
    val actions: Double,
    val inputs: Double,
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

        /**
         * @param textScore r_text — verbatim OCR fidelity, computed by
         *   [EvalRunner.scoreRound2Text].
         * @param typeScore r_type — graded intent-type correctness,
         *   computed by [EvalRunner.scoreRound2Type] (which owns the
         *   populated IntentRegistry).
         */
        fun compute(
            bubble: Bubble?,
            scene: JSONObject,
            textScore: Double,
            typeScore: Double,
        ): ScorerV2Result {
            val type = typeScore.coerceIn(0.0, 1.0)
            val text = textScore.coerceIn(0.0, 1.0)
            val actions = computeActions(bubble, scene)
            // r_inputs: walk scene.expected_inputs (populated by
            //  scripts/migrate_gt_v2_to_v3.py) and run the matching
            //  eval-side parser against the bubble's text surface.
            //  Empty expected_inputs → 1.0 floor (un-annotated).
            val inputs = computeInputsComplete(bubble, scene)

            val composite = 0.35 * type +
                0.25 * text +
                0.20 * actions +
                0.20 * inputs
            return ScorerV2Result(
                type = type,
                text = text,
                actions = actions,
                inputs = inputs,
                composite = composite.coerceIn(0.0, 1.0),
            )
        }

        /** Jaccard overlap of expected vs actual action ids:
         *  `|expected ∩ actual| / |expected ∪ actual|`.  Penalizes
         *  both misses and spurious/wrong chips (symmetric), unlike
         *  pure recall.  Empty expected → 1.0 (no penalty for
         *  un-annotated fixtures); null bubble + nonempty expected
         *  → 0.0. */
        private fun computeActions(bubble: Bubble?, scene: JSONObject): Double {
            val expectedArr = scene.optJSONArray("expected_actions") ?: return 1.0
            val expected = mutableSetOf<String>()
            for (i in 0 until expectedArr.length()) {
                expected.add(expectedArr.getString(i))
            }
            if (expected.isEmpty()) return 1.0
            val bubble0 = bubble ?: return 0.0
            // Prefer the raw LLM-emitted list (did the LLM get it
            // right?).  Fall back to the resolver-filtered list when
            // the LLM didn't emit action_ids.
            val actual: Set<String> = when {
                !bubble0.llmProposedActions.isNullOrEmpty() ->
                    bubble0.llmProposedActions.toSet()
                else -> bubble0.actions.toSet()
            }
            val intersection = expected.intersect(actual).size
            val union = expected.union(actual).size
            if (union == 0) return 1.0
            return intersection.toDouble() / union
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

        /** [2026-07-15] Per-input satisfaction check used by
         *  [computeInputsComplete].  Mirrors the production
         *  [com.example.intentcam.InputParsers] regex set so
         *  eval and prod agree on "is this input present in
         *  the bubble's text surface?".  Returns true when the
         *  parser would have returned non-null for the bubble.
         *
         *  Mapping mirrors `ACTION_REQUIRED_INPUTS` in
         *  `scripts/migrate_gt_v2_to_v3.py`; the two must stay
         *  in sync.  When they drift, `r_inputs_complete`
         *  becomes a soft signal (false 0s); the canonical fix
         *  is to update this dispatch. */
        private fun inputSatisfied(
            bubble: Bubble,
            @Suppress("UNUSED_PARAMETER") actionId: String,
            key: String,
        ): Boolean = when {
            // phoneNumber → regex chain (mobile → service → landline)
            key == "phone_number" -> bubbleHasPhoneNumber(bubble)
            // textContent + locationQuery both reduce to "is
            // there ANY non-blank surface text on the bubble".
            // Distinguishing them would require duplicating the
            // parser's exact priority order (title → 40-char
            // detail → first details[].value); for the v3.0
            // r_inputs_complete floor they're equivalent.
            key == "text" || key == "query" -> bubbleHasTextContent(bubble)
            // Unknown key (future action): default to false so
            // the signal reflects "we don't know how to
            // validate this" rather than a false positive.
            // Drift fix lives in the ACTION_REQUIRED_INPUTS table.
            else -> false
        }

        /** [2026-07-15] Walk scene.expected_inputs, check each
         *  entry against the bubble's text surface via
         *  [inputSatisfied].  Returns the fraction satisfied
         *  (0.0..1.0).  Empty expected_inputs → 1.0 (no
         *  penalty for un-annotated fixtures).  Null bubble +
         *  non-empty expected_inputs → 0.0 (no bubble to
         *  validate against). */
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

/** Helper: extract all detail values from a bubble as a single
 *  corpus string.  Used by future phases that wire ScorerV2's
 *  r_inputs_complete against GT `expected_inputs`. */
internal fun Bubble.corpus(): String = buildString {
    append(title).append('\n')
    append(detail).append('\n')
    details.forEach { d -> append(d.value).append('\n') }
}

/** Helper: format the per-fixture ScorerV2 numbers into a
 *  human-readable per-fixture line for [EvalRunner]'s console output. */
internal fun ScorerV2Result.format(): String =
    "type=${"%.2f".format(type)} " +
    "text=${"%.2f".format(text)} " +
    "actions=${"%.2f".format(actions)} " +
    "inputs=${"%.2f".format(inputs)} " +
    "composite_v2=${"%.2f".format(composite)}"
