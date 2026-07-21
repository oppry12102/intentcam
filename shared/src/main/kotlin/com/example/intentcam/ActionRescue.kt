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
 *    (LLM proposals + rescue) ∩ enabled.  Prod passes the user's
 *    enabled set; eval passes the full registered vocabulary
 *    (no gating), which keeps eval measuring the idealized config
 *    by design.
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
     *   - `view_label` rescue — `InputParsers.labelMarkdown(bubble) != null`
     *     AND `view_label` not already in `bubble.actions`.  The LLM
     *     transcribed a label (`label_markdown` present) but forgot to
     *     light its chip — the field's existence IS the verifiable cue,
     *     so precision risk is nil (unlike the lenient text parsers,
     *     this field only exists when the model deliberately wrote it).
     *   - `view_ad` rescue — `InputParsers.adMarkdown(bubble) != null`
     *     AND `view_ad` not already in `bubble.actions`.  Same
     *     field-existence cue shape as `view_label`.
     *   - `open_in_maps` rescue (view_ad-context ONLY, 2026-07-21) —
     *     `view_ad` already in the visible set AND
     *     `InputParsers.addressRow(bubble) != null` AND `open_in_maps`
     *     not already in `bubble.actions`.  The widened view_ad
     *     definition makes the model fire view_ad and sometimes drop
     *     the maps chip even when the ad carries a venue/address row
     *     (substitution: 2571/5496/2704 measured 2026-07-21).  The
     *     gate is the STRICT details-address signal (locationQuery's
     *     step-1), NOT the lenient title/detail fallbacks — offline
     *     estimate over the 2026-07-21 runs: 5 GT-correct rescues,
     *     3 recall-blind extras, 0 new none-suite fires.
     *
     * **NOT rescued** (deliberate):
     *   - `open_in_maps` outside the view_ad context —
     *     `InputParsers.locationQuery` is too lenient
     *     (returns any non-blank title), false-positive rate would
     *     put `open_in_maps` on every bubble.
     *   - `share` — `InputParsers.textContent` always returns non-null
     *     for any populated bubble, would put `share` on every
     *     bubble.
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
        if ("view_label" !in current && InputParsers.labelMarkdown(bubble) != null) {
            rescue += "view_label"
        }
        if ("view_ad" !in current && InputParsers.adMarkdown(bubble) != null) {
            rescue += "view_ad"
        }
        // view_ad-context maps rescue — must come AFTER the view_ad
        // line so an ad_markdown-only bubble (view_ad itself rescued
        // above) still qualifies for the maps check.
        if ("open_in_maps" !in current &&
            ("view_ad" in current || "view_ad" in rescue) &&
            InputParsers.addressRow(bubble) != null
        ) {
            rescue += "open_in_maps"
        }
        return rescue
    }

    /**
     * The full visible chip set for [bubble]:
     * `(LLM proposals + content-rescue) ∩ enabled`.
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
     *
     *  Note: a share precision-gate (`details<2 && content<40` →
     *  drop `share`) was implemented here on 2026-07-18 and REVERTED
     *  the same day: offline replay over the stored eval JSONs showed
     *  it caught 0/9 none-suite over-fires (those fixtures are
     *  text-rich menus/posters — the over-fire is GT under-annotation,
     *  not LLM decoration confusion) while adding prod false-negative
     *  risk on terse-but-legit share targets (WiFi posters).  The
     *  over-fire lever is GT curation, not a code gate — see the
     *  2026-07-18 audit memory.
     */
    fun visibleActions(bubble: Bubble, enabled: Set<String>): List<String> {
        val base = bubble.llmProposedActions ?: bubble.actions
        return (base + contentRescueActions(bubble)).distinct()
            .filter { it in enabled }
    }
}
