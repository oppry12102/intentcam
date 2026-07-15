package com.example.intentcam

import android.os.SystemClock
import com.example.intentcam.CycleProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [2026-07-14 Phase B — inversion v3.0] Owns concurrent
 * recognition cycles.  Replaces the legacy single-cycle flow inside
 * [AppViewModel.captureLatestFrame] where an [java.util.concurrent.atomic.AtomicBoolean]
 * (`analyzing`) gated the shutter button — under CycleManager the
 * shutter is **never** blocked, every tap spawns a new [CycleJob],
 * and old jobs continue in the background until they reach
 * COMPLETE / ERRORED.
 *
 * Concurrency cap is [UiState.CYCLE_MAX_CONCURRENT] = 2: when a 3rd
 * job arrives while 2 are still active, the oldest non-COMPLETE
 * job is marked SUPERSEDED and dropped from [allJobs] (its
 * coroutine keeps running so the LLM round isn't wasted, but its
 * bubble never reaches the UI).  Cap = 2 chosen to (a) match the
 * mental model of "user taps shutter while one photo is mid-cycle"
 * and (b) bound LLM API request rate on real networks.
 *
 * Status flow:
 *   - [startCycle] → new CycleJob (PENDING), launches coroutine
 *   - coroutine → IN_FLIGHT, runCycle on ToolUseLoop
 *   - every [CycleProgress] → CycleJob.applyProgress + refreshValidation
 *   - cycle returns Outcome.Bubble → status = COMPLETE
 *   - new cycle arrives → oldest non-COMPLETE marked SUPERSEDED
 *   - exception / 529 storm → status = ERRORED
 *
 * Public surface:
 *   - [startCycle] — spawn a new job
 *   - [supersedeCurrent] — explicitly mark the most-recently-focused
 *     non-COMPLETE job as SUPERSEDED (rarely called directly;
 *     startCycle does it implicitly when cap is hit)
 *   - [focusedJobId] — most-recent job's id, drives the live UI's
 *     "current capture" indicator
 *   - [allJobs] — every active job keyed by id; the UI iterates
 *     this to render cards
 *
 * Total ~110 lines.  The actual ToolUseLoop call is one
 * [ToolUseLoop.runCycle] invocation; everything else is bookkeeping.
 */
class CycleManager(
    private val scope: CoroutineScope,
    private val toolUseLoop: ToolUseLoop,
    private val orchestrator: ActionOrchestrator,
    private val actionRegistry: ActionRegistry,
    private val actionResolver: ActionResolver,
    private val enabledIds: suspend () -> Set<String>,
    private val log: (tag: String, msg: String) -> Unit = { _, _ -> },
    /**
     * [2026-07-15] Callback fired every time a cycle
     * transitions to ERRORED (timeout / LLM error /
     * exception).  AppViewModel uses this to surface a
     * global `ErrorBanner` so the user gets a system-level
     * signal beyond the per-cycle InFlightCard "识别超时"
     * branch — the user might not be looking at the bubble
     * list when a cycle dies, and the banner is the only
     * thing pinned to the top of the screen.  Pair with
     * [reportedCycleErrors] (Set) on the caller side for
     * dedup so a cycle that emits multiple error events
     * only shows the banner once. */
    private val onCycleError: (cycleId: String, message: String) -> Unit = { _, _ -> },
    /**
     * [2026-07-15] Per-cycle wall-clock cap on the LLM call.
     * Default 90s — generous enough for 2-3-round cycles on
     * slow networks (median ~2-3s per round) but tight enough
     * that a true API hang surfaces within ~90s and the
     * InFlightCard flips to its "识别超时" branch instead of
     * pinning the cycle in IN_FLIGHT forever.  Configurable
     * for tests (a 100ms cap lets you exercise the timeout
     * branch in <1s). */
    private val llmTimeoutMs: Long = 90_000L,
) {
    private val _allJobs = MutableStateFlow<Map<String, CycleJob>>(emptyMap())
    val allJobs: StateFlow<Map<String, CycleJob>> = _allJobs.asStateFlow()

    private val _focusedJobId = MutableStateFlow<String?>(null)
    val focusedJobId: StateFlow<String?> = _focusedJobId.asStateFlow()

    /** Spawn a new cycle for [frame].  Returns the job handle
     *  synchronously; the actual LLM work happens on [scope].  If
     *  [UiState.CYCLE_MAX_CONCURRENT] is already saturated, the
     *  oldest non-COMPLETE job is dropped from [allJobs] (marked
     *  SUPERSEDED, its coroutine continues in the background). */
    fun startCycle(frame: CapturedFrame): CycleJob {
        val job = CycleJob(frame = frame)
        _focusedJobId.value = job.id

        // Insert + enforce concurrency cap.  Use a copy-and-replace
        // pattern so collectors always see an immutable map
        // snapshot — Compose's recomposition relies on this for
        // structural-equality diffing.
        val updated = LinkedHashMap<String, CycleJob>(_allJobs.value)
        updated[job.id] = job
        // Drop oldest non-COMPLETE jobs until under cap.
        while (updated.size > UiState.CYCLE_MAX_CONCURRENT) {
            val toDrop = updated.values
                .filter { it.status.value != JobStatus.COMPLETE }
                .minByOrNull { it.createdAtMs }
            if (toDrop == null) break  // all COMPLETE; cap is soft
            toDrop.status.value = JobStatus.SUPERSEDED
            updated.remove(toDrop.id)
            log("CYCLE", "superseded ${toDrop.id} (cap=${UiState.CYCLE_MAX_CONCURRENT})")
        }
        _allJobs.value = updated

        scope.launch {
            runCycleLoop(job)
        }
        return job
    }

    /** Mark the currently-focused job (if any non-COMPLETE) as
     *  SUPERSEDED.  Does NOT remove from [allJobs] — that's the
     *  startCycle cap-enforcement's job.  Useful when the UI
     *  wants to demote a cycle without immediately evicting it
     *  (e.g. user scrolled past it). */
    fun supersedeCurrent() {
        val focusedId = _focusedJobId.value ?: return
        val job = _allJobs.value[focusedId] ?: return
        if (job.status.value == JobStatus.COMPLETE) return
        job.status.value = JobStatus.SUPERSEDED
        log("CYCLE", "superseded focused $focusedId")
    }

    /** True iff there's at least one job that should block the
     *  user from doing something silly (e.g. tapping a second
     *  action chip on a different bubble).  Phase C will use
     *  this for the shutter button: button is enabled iff there
     *  is no focused non-COMPLETE job (so the user can always
     *  take a photo — CycleManager handles the buffering). */
    fun hasFocusedJob(): Boolean {
        val focusedId = _focusedJobId.value ?: return false
        val job = _allJobs.value[focusedId] ?: return false
        return job.status.value != JobStatus.COMPLETE
    }

    /** Drive one cycle: mark IN_FLIGHT, call runCycle with the
     *  job's id + an onProgress callback that mirrors
     *  CycleProgress back into the job's MutableStateFlows.
     *  Errors transition to ERRORED; cancellation does NOT (the
     *  job's coroutine continues in the background per
     *  startCycle's lifecycle).
     *
     *  [2026-07-15] Wrapped the LLM call in
     *  [withTimeoutOrNull] (default 90s) so a hung API call can't
     *  pin a cycle in IN_FLIGHT forever — the user reported
     *  "second photo completes, first one stays gray with
     *  识别中... spinning indefinitely" when the API was slow.
     *  90s is ~6-12× the median cycle (2-3 rounds × 2-3s per
     *  round) — generous enough not to false-positive on a
     *  legitimately long image, tight enough that a true hang
     *  surfaces within a minute-and-a-half.  On timeout the
     *  cycle is marked ERRORED; any partial bubble that was
     *  emitted via onProgress before the timeout is preserved
     *  in `job.bubble` so the InFlightCard's ERRORED branch
     *  ("识别超时, 请再拍一张") takes over from the bubble-card
     *  branch on the next state read. */
    private suspend fun runCycleLoop(job: CycleJob) {
        job.status.value = JobStatus.IN_FLIGHT
        val t0 = SystemClock.elapsedRealtime()
        try {
            val outcome = withTimeoutOrNull(llmTimeoutMs) {
                withContext(Dispatchers.IO) {
                    toolUseLoop.runCycle(
                        thumbnail = job.frame.thumbnail,
                        fullRes = job.frame.fullRes,
                        userText = "",
                        actionIds = actionRegistry.allIds(),
                        cycleId = job.id,
                        // [2026-07-15] Wire the orchestrator's per-emit
                        //  gate.  After every successful `emit_bubble`
                        //  parse inside ToolUseLoop, this closure fires
                        //  with the post-resolve bubble (LLM proposal ∩
                        //  applicability ∩ enabled set).  When the
                        //  orchestrator returns CONTINUE, ToolUseLoop
                        //  injects a missing-input nudge and re-loops
                        //  (capped at 3 retries); when it returns
                        //  FINALIZE, the cycle ends with the current
                        //  bubble.  maxRounds=4 matches the orchestrator
                        //  default — 1 round for emit + 2-3 rounds for
                        //  the LLM to fill missing inputs.
                        onEmit = { bubble, round ->
                            orchestrator.shouldFinalize(bubble, round, maxRounds = 4)
                        },
                        // [2026-07-15] Stamp validation state onto the
                        //  final bubble before the cycle returns.  This
                        //  populates `bubble.validatedInputs` and
                        //  `bubble.pendingInputs` (the data-class fields,
                        //  separate from CycleJob's reactive flows which
                        //  are kept in sync by [CycleJob.refreshValidation]
                        //  + this callback's pre-return projection).
                        markValidated = { bubble -> orchestrator.markValidatedInputs(bubble) },
                        onProgress = { progress ->
                            job.applyProgress(progress)
                            // Resolve actions on every partial emit so
                            // the live UI sees chips as soon as the LLM
                            // settles on a proposed set.  Cheap (single
                            // suspend call to SettingsStore via
                            // enabledIds closure).
                            val resolved = actionResolver.suggestIds(progress.bubble)
                            val withActions = if (resolved.toSet() == progress.bubble.actions.toSet()) {
                                progress.bubble
                            } else {
                                progress.bubble.copy(actions = resolved)
                            }
                            job.bubble.value = withActions
                            job.refreshValidation(orchestrator)
                            // [2026-07-15] Soft intent-alignment check.
                            //  Logs a breadcrumb when bubble.intent doesn't
                            //  mention any primary noun from the bubble's
                            //  chosen action set — useful when investigating
                            //  "model picked right action, wrote wrong intent"
                            //  regressions.  Warn-only; doesn't fail the
                            //  cycle (intent is display-only per Decision C).
                            when (val a = validateIntentAlignment(withActions)) {
                                is IntentAlignmentCheck.Mismatch -> log(
                                    "INTENT_WARN",
                                    "cycle ${job.id} round=${progress.round} " +
                                        "intent='${withActions.intent.take(40)}' " +
                                        "missing=${a.missingNouns.take(5)}"
                                )
                                IntentAlignmentCheck.Aligned -> { /* no-op */ }
                            }
                            log(
                                "CYCLE",
                                "progress ${job.id} round=${progress.round} " +
                                    "terminal=${progress.isTerminal} " +
                                    "actions=${withActions.actions.size} " +
                                    "missing=${job.pendingInputs.value.size}"
                            )
                        },
                    )
                }
            }
            if (outcome == null) {
                // withTimeoutOrNull returned null — the LLM call
                //  hung past `llmTimeoutMs`.  Mark ERRORED so the
                //  InFlightCard flips to its "识别超时" branch and
                //  the user knows the cycle is dead.  The
                //  CancellationException is caught by
                //  withTimeoutOrNull internally (it cancels the
                //  inner coroutine, then the outer returns null),
                //  so we don't re-throw it here.
                val elapsed = SystemClock.elapsedRealtime() - t0
                job.status.value = JobStatus.ERRORED
                log(
                    "CYCLE",
                    "TIMEOUT ${job.id} after ${elapsed}ms (cap=${llmTimeoutMs}ms)"
                )
                onCycleError(job.id, "识别超时 (${elapsed / 1000}s)")
                return
            }
            when (outcome) {
                is ToolUseLoop.Outcome.Bubble -> {
                    // Bubble is already in job.bubble via the final
                    // onProgress.  Mark COMPLETE for the UI to
                    // optionally badge "done".
                    job.status.value = JobStatus.COMPLETE
                    log("CYCLE", "complete ${job.id} bubble=${outcome.bubble.id}")
                }
                is ToolUseLoop.Outcome.PendingUserInput -> {
                    // The model needs free-form input to continue;
                    // leave IN_FLIGHT and let AppViewModel's
                    // existing userInputRequest flow park the
                    // dialog.  Future cycle will pick up via
                    // submitUserInput().
                    log("CYCLE", "pending-user-input ${job.id}")
                }
                is ToolUseLoop.Outcome.Error -> {
                    job.status.value = JobStatus.ERRORED
                    log("CYCLE", "error ${job.id}: ${outcome.message}")
                    onCycleError(job.id, outcome.message)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine cancelled (e.g. process backgrounded).  Do
            // NOT mark ERRORED — the user might just have closed
            // the app.  Leave the job in its last observed state;
            // it will be GC'd when superseded by the next cycle.
            log("CYCLE", "cancelled ${job.id}: ${e.message}")
            throw e
        } catch (e: Throwable) {
            job.status.value = JobStatus.ERRORED
            log("CYCLE", "ERROR ${job.id}: ${e.message}")
            onCycleError(job.id, e.message ?: "识别异常")
        }
    }

    /** For tests + AppViewModel's idle-check. */
    fun activeJobCount(): Int = _allJobs.value.count {
        it.value.status.value != JobStatus.COMPLETE
    }
}
