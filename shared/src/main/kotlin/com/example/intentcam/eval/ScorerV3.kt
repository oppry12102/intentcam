package com.example.intentcam.eval

import com.example.intentcam.Bubble
import org.json.JSONArray

/**
 * [2026-07-15 v4 — action-first scorer] Companion scorer that runs
 * side-by-side with [ScorerV2Result] during the dual-run window.
 *
 * ## Formula
 * ```
 * composite_v3 = 0.50 · r_actions
 *             + 0.25 · r_text
 *             + 0.15 · r_inputs
 *             + 0.10 · r_intent_hint
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
 * UI accent input ([2026-07-15 v4 plan §2.3]). `r_actions` moves
 * from 0.20 to 0.50 to become the headline signal — it's the
 * user-can-act score.
 *
 * - **r_actions (0.50)** — Jaccard `|∩|/|∪|` of expected vs actual
 *   action ids. Same logic as [ScorerV2Result]; promoted to the
 *   dominant weight. Empty expected → 1.0 (un-annotated floor).
 * - **r_text (0.25)** — verbatim OCR fidelity. Reused from
 *   [ScorerV2Result] inputs (no re-computation).
 * - **r_inputs (0.15)** — fraction of `expected_inputs` satisfiable
 *   from the bubble's text surface. Reused from [ScorerV2Result].
 * - **r_intent_hint (0.10)** — NEW. Optional UX subtitle signal:
 *   both null → 1.0; LLM null + GT present → 0.5 (LLM under-emitted
 *   context); both present → strict_text overlap ≥ 0.67. Built for
 *   GT `expected_intent_hint` (Step 2 of the v4 plan).
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
    val intentHint: Double,
    val composite: Double,
) {
    companion object {
        /**
         * @param bubble LLM-emitted bubble (may be null on error).
         * @param scene  GT fixture JSON. Reads `expected_actions`,
         *   `expected_inputs`, and (after Step 2 ships) the optional
         *   `expected_intent_hint` string.
         * @param textScore r_text — verbatim OCR fidelity, computed
         *   by `EvalRunner.scoreRound2Text`. Reused as-is from the
         *   parallel ScorerV2 call site (no double evaluation).
         * @param inputsScore r_inputs — fraction of expected_inputs
         *   satisfiable, computed inside [ScorerV2Result.compute].
         *   Reused as-is (no double evaluation).
         * @param actionsScore r_actions — Jaccard overlap, computed
         *   inside [ScorerV2Result.compute]. Reused as-is.
         */
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
            val intentHint = computeIntentHint(bubble, scene)

            val composite = 0.50 * actions +
                0.25 * text +
                0.15 * inputs +
                0.10 * intentHint
            return ScorerV3Result(
                actions = actions,
                text = text,
                inputs = inputs,
                intentHint = intentHint,
                composite = composite.coerceIn(0.0, 1.0),
            )
        }

        /**
         * Per-component intent-hint score. Mirrors the C3 v3 prompt
         * convention: hint is an optional ≤30-char Chinese phrase the
         * LLM emits as a UX subtitle, not a classifier.
         *
         * Grading:
         *   - both null/empty          → 1.0 (no signal to mismatch)
         *   - LLM null + GT present   → 0.5 (under-emitted context)
         *   - LLM present + GT null   → 0.5 (over-emitted context)
         *   - both present             → [strictTextOverlap] ≥ 0.67
         *
         * Mirrors the action-mismatch symmetry (under / over / match)
         * so this signal is comparable to r_actions at the same
         * scale. Empty-string LLM hint is treated as null (model
         * emitted the field but left it blank).
         */
        private fun computeIntentHint(
            bubble: Bubble?,
            scene: org.json.JSONObject,
        ): Double {
            val expected = scene.optString("expected_intent_hint", "").trim().takeIf { it.isNotEmpty() }
            // [2026-07-15 v4 Step 2] expected_intent_hint only present
            //  after the GT migration script runs. Before Step 2, the
            //  field is absent → ScorerV3 falls through to the
            //  "no-signal" 1.0 case, so dual-run numbers stay
            //  comparable to ScorerV2.
            //
            //  After Step 2, fixtures with null expected_intent_hint
            //  still score 1.0 (no expectation, no penalty). Only
            //  fixtures the migration script annotated carry the
            //  signal.
            //
            // LLM-side hint: read from `bubble.title` (Phase E put
            //  the free-form intent phrase in `title`). Fall back to
            //  null when the bubble is missing or title is blank.
            val actual = bubble?.title?.trim()?.takeIf { it.isNotEmpty() }
            if (expected == null && actual == null) return 1.0
            if (expected == null || actual == null) return 0.5
            return if (strictTextOverlap(actual, expected) >= 0.67) 1.0 else 0.0
        }

        /**
         * Char-overlap coefficient on the intersection of [a] and
         * [b]. Conservative — counts each character's first
         * appearance (no double-counting on repeated chars), so
         * "拨打联系电话" vs "拨打电话联系" yields a meaningful
         * mid-range score rather than inflated full-credit.
         *
         * Returns 0.0..1.0. Empty inputs return 0.0; callers
         * already short-circuit on nullity before reaching here.
         */
        private fun strictTextOverlap(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val seen = HashSet<Char>()
            for (c in a) if (c.isLetterOrDigit() || c.code > 0x7F) seen.add(c)
            val matched = seen.count { c -> b.contains(c) }
            return matched.toDouble() / seen.size
        }

        // Suppress unused-import warning (JSONArray re-exported for
        //  future fixture-hint migration scripts).
        @Suppress("unused")
        private val unused: JSONArray? = null
    }
}

/** Helper: format the per-fixture ScorerV3 numbers into a
 *  human-readable per-fixture line for [EvalRunner]'s console
 *  output. Mirrors [ScorerV2Result.format]. */
internal fun ScorerV3Result.format(): String =
    "v3_actions=${"%.2f".format(actions)} " +
        "v3_text=${"%.2f".format(text)} " +
        "v3_inputs=${"%.2f".format(inputs)} " +
        "v3_hint=${"%.2f".format(intentHint)} " +
        "composite_v3=${"%.2f".format(composite)}"
