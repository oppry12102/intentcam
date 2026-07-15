package com.example.intentcam

import com.example.intentcam.CycleProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * [2026-07-14 Phase B — inversion v3.0] One in-flight recognition
 * cycle.  Owns the [CapturedFrame] being processed plus the
 * reactive state (status / bubble / validatedInputs / nRounds)
 * that the live UI reads via [MutableStateFlow].  Created by
 * [CycleManager.startCycle]; never constructed directly by
 * application code.
 *
 * Identity ([id]) is a UUID stamped onto every [Bubble] the cycle
 * produces (via `Bubble.cycleId`) so the live UI can route
 * per-bubble taps back to the originating job when 2+ cycles run
 * concurrently.
 *
 * `bubble` flow carries the latest finalized bubble (post-verifier,
 * post-action-resolver).  `null` while the cycle is PENDING or
 * between rounds with no emit_bubble parsed yet.  Each new emit
 * overwrites the value — Compose's `collectAsState` recomposes
 * the bubble card with the new value.
 *
 * `validatedInputs` flow is populated by [ActionOrchestrator.validateInputs]
 * on each bubble finalize.  Phase C reads this to drive the chip
 * state (validated / ghost / spinner).
 *
 * Status transitions (enforced by CycleManager, not by CycleJob):
 *   PENDING → IN_FLIGHT → COMPLETE
 *                       → ERRORED
 *                       → SUPERSEDED (orthogonal — can happen at
 *                         any point between PENDING and COMPLETE)
 *
 * Total ~50 lines.  The class is essentially a typed bag of
 * MutableStateFlows; the orchestration logic lives in
 * [CycleManager].
 */
class CycleJob internal constructor(
    val id: String = UUID.randomUUID().toString(),
    val frame: CapturedFrame,
    val status: MutableStateFlow<JobStatus> = MutableStateFlow(JobStatus.PENDING),
    val bubble: MutableStateFlow<Bubble?> = MutableStateFlow(null),
    val validatedInputs: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    val pendingInputs: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
    val nRounds: MutableStateFlow<Int> = MutableStateFlow(0),
    val createdAtMs: Long = System.currentTimeMillis(),
    /**
     * [2026-07-15] Handle to the [kotlinx.coroutines.launch]-ed
     * coroutine driving this cycle's LLM call.  Set by
     * [CycleManager.startCycle] right after `scope.launch` so a
     * later supersede can call `coroutine.cancel()` and actually
     * stop the in-flight HTTP request — the previous behavior
     * marked the cycle SUPERSEDED in the UI but left the
     * coroutine running in the background, wasting API quota
     * for cycles whose result would never reach the user.
     *
     * Nullable because the legacy single-cycle path
     * (ToolUseLoop.runCycle called directly from
     * AppViewModel.runToolUseCycle) constructs a CycleJob
     * without going through CycleManager.startCycle; for those
     * jobs there's no handle to cancel.  In the live-UI path
     * (Phase B+), the field is always non-null. */
    var coroutine: Job? = null,
) {
    /**
     * Apply one [CycleProgress] event from [ToolUseLoop.runCycle].
     * Called by [CycleManager.runCycleLoop] for every successful
     * `emit_bubble` parse inside the loop, plus the terminal
     * round.  Updates the reactive surface so the live UI
     * recomposes with the latest bubble.
     */
    internal fun applyProgress(progress: CycleProgress) {
        bubble.value = progress.bubble
        nRounds.value = progress.round
        status.value = if (progress.isTerminal) JobStatus.COMPLETE else JobStatus.IN_FLIGHT
        // validatedInputs + pendingInputs are recomputed by the
        // orchestrator on every bubble; CycleManager fills them
        // via the dedicated [refreshValidation] call so the
        // orchestrator's pure function doesn't need to know about
        // MutableStateFlows.
    }

    /** Re-run [ActionOrchestrator.validateInputs] against the
     *  current bubble and write the per-action map + cross-action
     *  pending-input list into the reactive flows.  Called by
     *  [CycleManager] after every [applyProgress]. */
    internal fun refreshValidation(orchestrator: ActionOrchestrator) {
        val current = bubble.value ?: return
        when (val v = orchestrator.validateInputs(current)) {
            is InputsValidation.Complete -> {
                validatedInputs.value = current.actions.associateWith { true }
                pendingInputs.value = emptyList()
            }
            is InputsValidation.Missing -> {
                validatedInputs.value = current.actions.associateWith { actionId ->
                    actionId !in v.perAction
                }
                pendingInputs.value = orchestrator.missingInputKeys(current)
            }
        }
    }
}
