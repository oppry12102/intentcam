package com.example.intentcam.eval

import com.example.intentcam.Bubble
import com.example.intentcam.Detail
import org.json.JSONArray
import org.json.JSONObject

/**
 * Intent-first composite scorer.  This is now the SOLE score — the
 * legacy `0.45·r1 + 0.45·r2 + 0.10·r3` composite has been retired
 * (see EvalRunner).
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
        // Phone regexes live in `com.example.intentcam.InputParsers`
        // (single source of truth shared with prod + EvalRunner).
        // No local copies.  See ADR
        // docs/adr/2026-07-16-input-parsers-drift-risk.md.

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

        /** Recall of expected action ids against the VISIBLE chip set:
         *  `|expected ∩ actual| / |expected|`.  Of the chips the user
         *  was supposed to see, how many did they actually see?
         *
         *  `actual` = the visible set (`Bubble.actions` once the
         *  orchestrator / eval mirror has folded LLM proposals +
         *  content-rescue into it; falls back to raw
         *  `llmProposedActions` for legacy bubbles).  Using RECALL
         *  (not Jaccard) is the 2026-07-17 decision: content rescue is
         *  add-only by design, so a rescue chip the LLM didn't propose
         *  should CREDIT the score when it matches expected, and a
         *  rescue chip that doesn't match expected must NOT dilute it
         *  (Jaccard fought the add-only design — a rescue over-fire
         *  like `redact_id` on a storefront dropped a correct
         *  `dial_number` from 1.0 to 0.5).  Recall credits rescue hits
         *  without penalizing rescue extras.  Trade-off: recall no
         *  longer penalizes LLM over-emission (emitting every chip
         *  would score 1.0); the r_text/r_type/r_inputs components
         *  carry the precision signal instead.
         *
         *  Empty expected → 1.0 (no penalty for un-annotated fixtures);
         *  null bubble + nonempty expected → 0.0. */
        private fun computeActions(bubble: Bubble?, scene: JSONObject): Double {
            val expectedArr = scene.optJSONArray("expected_actions") ?: return 1.0
            val expected = mutableSetOf<String>()
            for (i in 0 until expectedArr.length()) {
                expected.add(expectedArr.getString(i))
            }
            if (expected.isEmpty()) return 1.0
            val bubble0 = bubble ?: return 0.0
            // The VISIBLE chip set the user sees = `.actions` once the
            // orchestrator / eval mirror has folded the LLM's proposals
            // + content rescue into it (prod: resolver(LLM∩enabled) +
            // rescue; eval mirror: llmProposedActions + rescue).  Fall
            // back to the raw llmProposedActions for legacy bubbles
            // whose `.actions` was never populated (old runs / no
            // mirror).
            val actual: Set<String> = when {
                bubble0.actions.isNotEmpty() -> bubble0.actions.toSet()
                !bubble0.llmProposedActions.isNullOrEmpty() ->
                    bubble0.llmProposedActions.toSet()
                else -> emptySet()
            }
            // Recall: |expected ∩ actual| / |expected|.  expected is
            //  non-empty here (guarded above), so no div-by-zero.
            val hits = expected.intersect(actual).size
            return hits.toDouble() / expected.size
        }

        /** Convenience: did this bubble carry a phone number?  Used
         *  to set [computeInputsComplete]'s per-input satisfaction
         *  once Phase D's GT migration populates expected_inputs.
         *  Mirrors [com.example.intentcam.PhoneExtractor.firstMatch]. */
        internal fun bubbleHasPhoneNumber(bubble: Bubble): Boolean =
            com.example.intentcam.InputParsers.phoneNumber(bubble) != null

        /** Per-input satisfaction check used by
         *  [computeInputsComplete].  Dispatches each input key to the
         *  SAME parser prod uses ([com.example.intentcam.InputParsers])
         *  so eval and prod agree on "is this input present in the
         *  bubble's text surface?" — single source of truth per the
         *  input-parsers-drift-risk ADR.  Returns true when the parser
         *  would have returned non-null for the bubble.
         *
         *  Mapping mirrors `ACTION_REQUIRED_INPUTS` in
         *  `scripts/migrate_gt_v2_to_v3.py`; the two must stay in sync.
         *  When they drift, `r_inputs_complete` becomes a soft signal
         *  (false 0s); the canonical fix is to update this dispatch. */
        private fun inputSatisfied(
            bubble: Bubble,
            @Suppress("UNUSED_PARAMETER") actionId: String,
            key: String,
        ): Boolean = when {
            // phone_number → InputParsers.phoneNumber (mobile → service → landline)
            key == "phone_number" -> bubbleHasPhoneNumber(bubble)
            // query → InputParsers.locationQuery (open_in_maps). After
            //  the 2026-07-17 locationQuery rewrite this scans
            //  details[] for address keywords with a 4-level fallback;
            //  calling the parser directly (instead of the old
            //  "any non-blank text" collapse) means eval follows prod
            //  automatically if the parser ever tightens.
            key == "query" ->
                com.example.intentcam.InputParsers.locationQuery(bubble) != null
            // text → InputParsers.textContent (copy_* share payload).
            key == "text" ->
                com.example.intentcam.InputParsers.textContent(bubble) != null
            // Unknown key (future action): default to false so
            // the signal reflects "we don't know how to
            // validate this" rather than a false positive.
            // Drift fix lives in the ACTION_REQUIRED_INPUTS table.
            else -> false
        }

        /** Walk scene.expected_inputs, check each
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
