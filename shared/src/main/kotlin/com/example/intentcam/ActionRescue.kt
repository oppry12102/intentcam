package com.example.intentcam

/**
 * Content-based action rescue + the shared "visible chip set"
 * computation.  Lives in `shared/` (pure JVM, no Android types) so
 * the production path (`app/.../ActionOrchestrator`) and the eval
 * path (`shared/.../eval/EvalRunner`) call the SAME implementation —
 * before 2026-07-18 the rescue rules were duplicated inline in both
 * places and drifted twice (rescue scored ~nothing in eval until
 * `bb111af`; see `docs/adr/2026-07-16-input-parsers-drift-risk.md`).
 *
 * Two entry points:
 *  - [contentRescueActions] — the add-only rescue rules
 *    (phone / id-document / payment-QR).
 *  - [visibleActions] — the full visible chip set:
 *    (LLM proposals + rescue) ∩ enabled, with the share
 *    precision-gate applied.  Prod passes the user's enabled set;
 *    eval passes the full registered vocabulary (no gating), which
 *    keeps eval measuring the idealized config by design.
 */
object ActionRescue {

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
     * misses that the image also contains a phone number / id
     * document / payment QR. After the 2026-07-17 intent-taxonomy
     * retirement there's no type-keyed soft hint; content-rescue is
     * the add-only safety net for these structured signals.
     *
     * **Rules** (each is independent — multiple can fire on one bubble):
     *   - `dial_number` rescue — `InputParsers.phoneNumber(bubble) != null`
     *     AND `dial_number` not already in `bubble.actions`. Covers
     *     phone-bearing mixed content (restaurant / school / billboard
     *     fixtures where the LLM classifies the scene but skips the
     *     phone chip).
     *   - `redact_id` rescue — `InputParsers.idDocument(bubble) != null`
     *     AND `redact_id` not already in `bubble.actions`.
     *   - `scan_to_pay` rescue — `InputParsers.paymentQr(bubble) != null`
     *     AND `scan_to_pay` not already in `bubble.actions`.
     *
     * **NOT rescued** (deliberate):
     *   - `open_in_maps` — `InputParsers.locationQuery` is too lenient
     *     (returns any non-blank title), false-positive rate would
     *     put `open_in_maps` on every bubble.
     *   - `share` — `InputParsers.textContent` always returns non-null
     *     for any populated bubble, would put `share` on every
     *     bubble.  (Share over-emission is handled the opposite way —
     *     by the precision gate in [visibleActions].)
     *
     * Note: the returned ids are NOT filtered against the user's
     * enabled set here — that gate lives in [visibleActions] (and in
     * prod's `ActionOrchestrator.markValidatedInputs`), so a user who
     * never opted into a PII action doesn't see its rescue chip.
     */
    fun contentRescueActions(bubble: Bubble): List<String> {
        val current = bubble.actions.toSet()
        val rescue = mutableListOf<String>()
        if ("dial_number" !in current && InputParsers.phoneNumber(bubble) != null) {
            rescue += "dial_number"
        }
        if ("redact_id" !in current && InputParsers.idDocument(bubble) != null) {
            rescue += "redact_id"
        }
        if ("scan_to_pay" !in current && InputParsers.paymentQr(bubble) != null) {
            rescue += "scan_to_pay"
        }
        return rescue
    }

    /**
     * The full visible chip set for [bubble]:
     * `(LLM proposals + content-rescue) ∩ enabled`, with the share
     * precision-gate applied.
     *
     *  - `base` = `bubble.llmProposedActions ?: bubble.actions` — the
     *    LLM's explicit pick when present, else whatever the bubble
     *    already carries (resolver-filtered list in prod, empty for a
     *    raw eval bubble).
     *  - rescue ids are merged in, deduped, then intersected with
     *    [enabled] so consent-gated actions the user hasn't enabled
     *    stay hidden (the 2026-07-18 P3 fix: rescue previously
     *    bypassed the enabled gate and surfaced default-OFF PII
     *    chips).
     *  - finally the share precision-gate drops an unfounded `share`
     *    (see [dropUnfoundedShare]).
     */
    fun visibleActions(bubble: Bubble, enabled: Set<String>): List<String> {
        val base = bubble.llmProposedActions ?: bubble.actions
        val merged = (base + contentRescueActions(bubble)).distinct()
            .filter { it in enabled }
        return dropUnfoundedShare(bubble, merged)
    }

    /**
     * Share precision-gate (2026-07-18, over-fire lever).  The LLM's
     * single biggest over-fire source is `share` on decorative text:
     * 9 of 13 over-fired chips on the `none` eval suite were `share`
     * (prompt-v2, run none_20260717_224736).  `share`'s entire value
     * is copying text — a bubble with fewer than 2 detail rows AND a
     * sub-40-char content has no payload worth sharing, so the gate
     * drops the proposal.  Recall risk is bounded: any fixture with
     * real informational text (menu / poster / hours / listing)
     * produces ≥2 detail rows or a long content by construction of
     * the emit_bubble prompt ("图里每一处独立的文字…都要有一行").
     *
     * Only `share` is gated — the other four actions carry
     * content-verifiable cues (phone regex / id regex / QR keywords /
     * address keywords) and don't share the decoration failure mode.
     */
    fun dropUnfoundedShare(bubble: Bubble, actions: List<String>): List<String> {
        if ("share" !in actions) return actions
        val hasTextPayload = bubble.details.size >= 2 || bubble.detail.length >= 40
        return if (hasTextPayload) actions else actions - "share"
    }
}
