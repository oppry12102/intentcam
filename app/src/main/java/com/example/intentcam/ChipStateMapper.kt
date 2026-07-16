package com.example.intentcam

/**
 * Visual state of one action chip on a bubble card.  Drives both
 * the chip's color / decoration
 * and whether the chip is tappable:
 *
 *  - [Validated] — every required input for this action was
 *    satisfied by the bubble's surface text.  Chip renders solid
 *    + tap fires the action immediately.
 *  - [Ghost]     — at least one required input is missing.  Chip
 *    renders gray + tooltip "需要 X"; tapping it does nothing
 *    (or, in a future phase, could open an inline input dialog).
 *  - [Spinner]   — the cycle is still IN_FLIGHT and this action
 *    hasn't been validated yet.  Chip renders yellow with a small
 *    spinner icon; tapping it does nothing until the cycle
 *    reaches COMPLETE.
 *  - [Hidden]    — the action's `requiredInputs` cannot be parsed
 *    from this bubble's text at all (e.g. `dial_number` on a
 *    bubble with no phone number AND no detail rows).  Chip is
 *    not rendered at all.  Reserved for future actions that
 *    want to auto-hide on clearly-irrelevant bubbles.
 *
 * Resolved by the pure function [resolveChipState]; both the bubble
 * card and the detail screen call into the same mapper so the
 * visual contract stays consistent.
 */
sealed class ChipState {
    object Validated : ChipState()
    object Ghost : ChipState()
    object Spinner : ChipState()
    object Hidden : ChipState()
}

/**
 * Pure function — given a bubble + an action, decide what chip
 * state to show.  Side-effect-free.
 *
 * Inputs read:
 *   - `bubble.validatedInputs[actionId]` — boolean written by
 *     [ActionOrchestrator.validateInputs] at every cycle progress
 *     event.  When the key is absent (legacy bubble, or the cycle
 *     hasn't validated yet), we fall through to Spinner.
 *   - `bubble.pendingInputs` — list of missing input keys across
 *     all the bubble's actions.  Used to disambiguate Spinner
 *     (cycle in flight) from Ghost (cycle done, missing inputs).
 *
 * Decision tree (in order):
 *   1. `actionId ∉ bubble.validatedInputs` AND cycle in flight
 *      → Spinner (waiting on validation).
 *   2. `bubble.validatedInputs[actionId] == true` → Validated.
 *   3. `bubble.validatedInputs[actionId] == false` → Ghost (cycle
 *      finished but this action's inputs are missing).
 *   4. `action.requiredInputs.isEmpty()` AND cycle complete →
 *      Validated (actions without inputs are always fireable).
 *
 * The Hidden case is not currently produced by this function —
 * it's reserved for future "irrelevant action on this bubble"
 * heuristics (e.g. `dial_number` on a pure menu bubble).  Callers
 * that want to render Hidden can override via their own logic.
 */
fun resolveChipState(bubble: Bubble, action: ActionDef, cycleStatus: JobStatus): ChipState {
    val validated = bubble.validatedInputs[action.id]
    // Spinner: cycle still in flight and this action hasn't been
    // validated yet.
    if (validated == null && (cycleStatus == JobStatus.PENDING || cycleStatus == JobStatus.IN_FLIGHT)) {
        return ChipState.Spinner
    }
    // No required inputs → always Validated (universal actions).
    if (action.requiredInputs.isEmpty()) return ChipState.Validated
    // Cycle complete (or superseded) + explicitly validated.
    if (validated == true) return ChipState.Validated
    // Cycle complete + explicitly missing.
    if (validated == false) return ChipState.Ghost
    // Validation not yet computed and cycle is no longer in flight
    // (rare — should only happen if the cycle completed without
    //  writing validatedInputs).  Default to Validated to avoid
    //  hiding chips unnecessarily.
    return ChipState.Validated
}
