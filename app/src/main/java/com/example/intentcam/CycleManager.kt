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
     * [2026-07-15 P0 fix] Callback fired when a cycle reaches
     *  its terminal COMPLETE state via [ToolUseLoop.Outcome.Bubble].
     *  AppViewModel uses this to clear `state.analyzing` — the
     *  pre-existing flow only cleared `analyzing` on the legacy
     *  single-cycle path, so live-UI cycles left the TopOverlay
     *  "识别中…" spinner stuck after completion.  Pair with the
     *  legacy `enterAnalyzing()` so the two paths now balance. */
    private val onCycleComplete: (cycleId: String, bubble: Bubble) -> Unit = { _, _ -> },
    /**
     * [2026-07-15 P0 fix] Callback fired when a cycle returns
     *  [ToolUseLoop.Outcome.PendingUserInput].  AppViewModel
     *  surfaces this as the user-input AlertDialog and stashes
     *  the originating frame's full-resolution JPEG so
     *  [com.example.intentcam.AppViewModel.submitUserInput] can
     *  resume via [resumeCycle].  The placeholder Bubble is
     *  also written into `job.bubble.value` so the live UI
     *  renders it as a BubbleCard (the live card list reads
     *  `state.cycles`, not `state.bubbles`, so the placeholder
     *  has to land in the per-job flow to be visible). */
    private val onPendingUserInput: (cycleId: String, request: UserInputRequest, placeholder: Bubble) -> Unit = { _, _, _ -> },
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
        // [2026-07-15 bug fix] Cap on ACTIVE (non-COMPLETE) cycles,
        //  not total map size.  Previous version compared
        //  `updated.size > CYCLE_MAX_CONCURRENT` — but COMPLETE
        //  cycles never get evicted from the map (the LLM work is
        //  done but the job stays for the bubble-card UI to
        //  reference).  After 2 cycles complete, the map held 2
        //  COMPLETE entries; a 3rd tap added a 3rd entry (size=3
        //  > cap=2), the "drop oldest non-COMPLETE" filter
        //  returned the just-added cycle (the only non-COMPLETE),
        //  and the new cycle was immediately SUPERSEDED before
        //  the user saw any progress bar.  User reported: "tap
        //  识别 twice, both finish, tap again — no response, debug
        //  shows it's still processing the original 2".
        //
        //  New check counts only non-COMPLETE cycles, which is
        //  the actual concurrency cap (the comment: "user taps
        //  shutter while one photo is mid-cycle").  2 COMPLETE +
        //  1 new IN_FLIGHT → activeCount=1, well under the cap.
        var activeCount = updated.values.count { it.status.value != JobStatus.COMPLETE }
        while (activeCount > UiState.CYCLE_MAX_CONCURRENT) {
            val toDrop = updated.values
                .filter { it.status.value != JobStatus.COMPLETE }
                .minByOrNull { it.createdAtMs }
            if (toDrop == null) break  // shouldn't happen — activeCount > 0
            toDrop.status.value = JobStatus.SUPERSEDED
            // [2026-07-15] Actually cancel the LLM coroutine
            //  instead of leaving it to run to completion.  The
            //  previous behavior (mark SUPERSEDED + leave the
            //  coroutine) meant every "rapid capture" cycle was
            //  billed for an LLM call whose result would never
            //  reach the user — the cycle was dropped from
            //  allJobs so the bubble has no UI to render into.
            //  With cancel() the OkHttp request gets
            //  CancellationException, the LLM API call aborts
            //  mid-stream, and the API quota is preserved.
            toDrop.coroutine?.cancel()
            updated.remove(toDrop.id)
            activeCount--
            log("CYCLE", "superseded+cancelled ${toDrop.id} (cap=${UiState.CYCLE_MAX_CONCURRENT})")
        }

        // [2026-07-15] Total cap enforcement (CYCLES_MAX_TOTAL=8).
        //  Distinct from the IN_FLIGHT cap above — this one
        //  bounds the *visible* count of cycles (any status) on
        //  the camera screen, regardless of how many are
        //  actively processing.  Shutter button is disabled when
        //  we hit this cap, so the eviction path is defensive
        //  (any future bypass of the button gate is still
        //  safe).  Eviction is FIFO by createdAtMs; if the
        //  evicted entry is IN_FLIGHT, its coroutine is
        //  cancelled so we don't bill for a discarded LLM
        //  call.  Same cancellation pattern as the
        //  cap-2-IN_FLIGHT branch above.
        while (updated.size > UiState.CYCLES_MAX_TOTAL) {
            val toDrop = updated.values.minByOrNull { it.createdAtMs }
                ?: break
            toDrop.status.value = JobStatus.SUPERSEDED
            toDrop.coroutine?.cancel()
            updated.remove(toDrop.id)
            log(
                "CYCLE",
                "evicted ${toDrop.id} (cap=${UiState.CYCLES_MAX_TOTAL} total, " +
                    "status=${toDrop.status.value})"
            )
        }
        _allJobs.value = updated

        // [2026-07-15] Capture the launch handle so a later
        //  supersede can cancel() it (see cap-enforcement above).
        //  Without this, a SUPERSEDED cycle's coroutine would
        //  keep running to completion — see CycleJob.coroutine's
        //  docstring.
        val launchHandle = scope.launch {
            runCycleLoop(job)
        }
        job.coroutine = launchHandle
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
     *  branch on the next state read.
     *
     * [2026-07-15 P0 fix] `userText` parameter (default "") —
     *  empty on the initial [startCycle] invocation, populated
     *  with the user's submitted follow-up text on a [resumeCycle]
     *  re-invocation.  Re-using the same `runCycleLoop` for both
     *  initial + resumed paths keeps the timeout / cancellation /
     *  per-emit validation / progress-mirroring logic in a single
     *  place. */
    private suspend fun runCycleLoop(job: CycleJob, userText: String = "") {
        job.status.value = JobStatus.IN_FLIGHT
        val t0 = SystemClock.elapsedRealtime()
        try {
            val outcome = withTimeoutOrNull(llmTimeoutMs) {
                withContext(Dispatchers.IO) {
                    toolUseLoop.runCycle(
                        thumbnail = job.frame.thumbnail,
                        fullRes = job.frame.fullRes,
                        userText = userText,
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
                    // [2026-07-15 P0 fix] Fire the completion
                    //  callback so AppViewModel can clear
                    //  `state.analyzing`.  Pre-existing bug: the
                    //  TopOverlay's "识别中…" spinner was stuck on
                    //  after a live-UI cycle finished because the
                    //  legacy `analyzing` flip was wired only to
                    //  the single-cycle path.
                    onCycleComplete(job.id, outcome.bubble)
                    log("CYCLE", "complete ${job.id} bubble=${outcome.bubble.id}")
                }
                is ToolUseLoop.Outcome.PendingUserInput -> {
                    // [2026-07-15 P0 fix] The model needs free-form
                    //  input to continue.  Previously this branch
                    //  only logged — the user could never enter
                    //  follow-up text because AppViewModel's
                    //  userInputRequest was never set.  Now:
                    //   1. Normalize the placeholder's cycleId so
                    //      it always matches the owning CycleJob
                    //      (defensive — ToolUseLoop.buildPlaceholder
                    //      already does this since 2026-07-15 but
                    //      we don't want a future regression there
                    //      to silently break resume routing).
                    //   2. Write the placeholder into `job.bubble`
                    //      so the live UI renders it as a BubbleCard.
                    //      IntentBubbles reads `state.cycles` (not
                    //      `state.bubbles`) when cycles is non-empty,
                    //      so the per-job flow is the only way to
                    //      make the placeholder visible in the live
                    //      card list.
                    //   3. Fire the callback so AppViewModel can
                    //      surface the AlertDialog + stash fullRes.
                    val placeholder = outcome.placeholder.copy(cycleId = job.id)
                    job.bubble.value = placeholder
                    onPendingUserInput(job.id, outcome.request, placeholder)
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

    /** [2026-07-15 P0 fix] Resume a cycle that returned
     *  [ToolUseLoop.Outcome.PendingUserInput] after the user
     *  submitted follow-up text.  Looks up the job by [cycleId]
     *  and re-launches [runCycleLoop] with the submitted
     *  `userText`.  Returns `true` if the resume was accepted,
     *  `false` if the cycle is no longer in a state where it can
     *  be resumed (not found, already terminal, or stale — the
     *  stale-job case should not silently fall back to the legacy
     *  single-cycle path; see AppViewModel.submitUserInput).
     *
     *  The previous-coroutine invariant: [runCycleLoop] returns
     *  synchronously after firing the PendingUserInput callback,
     *  so by the time [resumeCycle] is called the old `job.coroutine`
     *  handle is already completed.  We replace it with the new
     *  handle so supersede / cancelAll can kill the resumed call
     *  if the user dismisses the dialog and immediately takes a
     *  new photo.
     *
     *  [2026-07-15] Side-effects before launching the new call:
     *   - `job.bubble.value = null` — clears the parked
     *     placeholder so the live UI flips back to an InFlightCard
     *     spinner while the resumed LLM call runs.  Without this
     *     the card would keep showing the "需要补充信息" BubbleCard
     *     until the resumed call emits a new bubble.
     *   - Reset `validatedInputs` + `pendingInputs` + `nRounds` so
     *     the live chip states (`Spinner` → `Validated`) recompute
     *     from scratch against the resumed bubble.
     *
     *  Returns `false` (resume rejected) if the cycle is missing
     *  or already terminal — the caller should clear the
     *  userInputRequest dialog without firing another LLM call. */
    fun resumeCycle(cycleId: String, userText: String): Boolean {
        val job = _allJobs.value[cycleId] ?: run {
            log("CYCLE", "resume REJECTED $cycleId: not in allJobs")
            return false
        }
        if (job.status.value == JobStatus.COMPLETE ||
            job.status.value == JobStatus.ERRORED ||
            job.status.value == JobStatus.SUPERSEDED
        ) {
            log(
                "CYCLE",
                "resume REJECTED $cycleId: status=${job.status.value}"
            )
            return false
        }
        if (userText.isBlank()) {
            log("CYCLE", "resume REJECTED $cycleId: blank userText")
            return false
        }
        // Clear the parked placeholder + validation state so the
        // live UI flips back to InFlightCard while the resumed
        // LLM call runs.
        job.bubble.value = null
        job.validatedInputs.value = emptyMap()
        job.pendingInputs.value = emptyList()
        job.nRounds.value = 0
        // Launch the resumed call.  CoroutineStart.LAZY so we can
        // install the handle into `job.coroutine` BEFORE execution
        // begins — without LAZY, `scope.launch` may start running
        // synchronously up to the first suspension point, racing
        // against the supersede path's `job.coroutine?.cancel()`.
        val handle = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runCycleLoop(job, userText = userText)
        }
        job.coroutine = handle
        handle.start()
        log(
            "CYCLE",
            "resume $cycleId userText='${userText.take(40)}' (size=${userText.length})"
        )
        return true
    }

    /** [2026-07-15 P0 fix] Discard a pending-input cycle when the
     *  user cancels the user-input dialog.  Without this, a
     *  cancelled input request would leave the cycle parked in
     *  IN_FLIGHT forever (consuming a slot in CYCLES_MAX_TOTAL +
     *  keeping the InFlightCard "识别中..." spinner stuck).
     *  Marks the job SUPERSEDED + cancels its coroutine (if any)
     *  and removes it from [allJobs] — the standard eviction
     *  path used by [startCycle] when a new capture supersedes
     *  an older one.  Idempotent on missing / already-terminal
     *  cycle ids. */
    fun cancelCycle(cycleId: String, reason: String) {
        val job = _allJobs.value[cycleId] ?: return
        if (job.status.value == JobStatus.COMPLETE ||
            job.status.value == JobStatus.ERRORED
        ) return
        job.status.value = JobStatus.SUPERSEDED
        job.coroutine?.cancel()
        val updated = LinkedHashMap(_allJobs.value)
        updated.remove(cycleId)
        _allJobs.value = updated
        log("CYCLE", "cancelled pending cycle $cycleId: $reason")
    }

    /** For tests + AppViewModel's idle-check. */
    fun activeJobCount(): Int = _allJobs.value.count {
        it.value.status.value != JobStatus.COMPLETE
    }

    /** [2026-07-15 P0 fix] Count of cycles whose status is
     *  [JobStatus.PENDING] or [JobStatus.IN_FLIGHT] — the
     *  user-facing "in flight" gauge that drives the shutter
     *  counter (CYCLE_MAX_CONCURRENT - inFlightJobCount() =
     *  "remaining slots").  Distinct from [activeJobCount] which
     *  also includes [JobStatus.ERRORED] and
     *  [JobStatus.SUPERSEDED]: those are terminal and should
     *  not gate the shutter.
     *
     *  Used by [com.example.intentcam.AppViewModel.syncCycleCounters]
     *  to populate [com.example.intentcam.UiState.activeCycleCount]
     *  on every cycle transition.  Cheap O(n) over the cycles
     *  map (n <= 8 in practice due to CYCLES_MAX_TOTAL). */
    fun inFlightJobCount(): Int = _allJobs.value.values.count {
        it.status.value == JobStatus.IN_FLIGHT ||
            it.status.value == JobStatus.PENDING
    }

    /** [2026-07-15 P0 fix] Discard every cycle currently in
     *  [allJobs] and clear the map.  Each non-COMPLETE job is
     *  marked SUPERSEDED and its [Job.coroutine] cancelled so the
     *  in-flight LLM HTTP request aborts (otherwise a 90-second
     *  `llmTimeoutMs` budget would bill for a discarded result).
     *  Wired into [com.example.intentcam.AppViewModel.restartScanning]
     *  so tapping "重新扫描" actually stops whatever the camera was
     *  doing — the previous version cleared `UiState.bubbles` but
     *  left cycles running in the background, wasting API quota.
     *  Called from the main thread; safe because it only mutates
     *  StateFlows and calls `Job.cancel()` (which is thread-safe). */
    fun cancelAll(reason: String = "user restart") {
        val jobs = _allJobs.value.values.toList()
        for (job in jobs) {
            if (job.status.value != JobStatus.COMPLETE) {
                job.status.value = JobStatus.SUPERSEDED
                job.coroutine?.cancel()
            }
        }
        _allJobs.value = emptyMap()
        _focusedJobId.value = null
        log("CYCLE", "cancelled all (${jobs.size}) reason=$reason")
    }
}
