package com.example.intentcam

import android.os.SystemClock
import com.example.intentcam.CycleProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns concurrent recognition cycles as a classic
 * producer → bounded queue → worker pool → output pipeline.
 *
 * Design rationale + alternatives considered: see
 * [docs/adr/2026-07-16-producer-consumer-pipeline.md].
 *
 * Two independent bounds:
 *   - **Queue depth** [UiState.CYCLE_QUEUE_DEPTH] (= 8): max
 *     queued+in-flight.  Backpressure — when reached, [startCycle]
 *     returns null and the shutter dims.
 *   - **Concurrency** [UiState.CYCLE_CONCURRENCY] (= 2): max
 *     cycles actually running OCR+LLM at once.
 *
 * Status flow:
 *   - [startCycle] → PENDING, enqueued, [pump]
 *   - worker picks it up → IN_FLIGHT, runCycle on ToolUseLoop
 *   - [CycleProgress] → CycleJob.applyProgress + refreshValidation
 *   - Outcome.Bubble → COMPLETE, worker re-pumps
 *   - exception / 529 / timeout → ERRORED, worker re-pumps
 *
 * Public surface:
 *   - [startCycle] — enqueue a new job (nullable: null == rejected, queue full)
 *   - [focusedJobId] / [allJobs] / [busy] — driven by CycleManager state
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
     * Callback fired when a cycle transitions to ERRORED
     * (timeout / LLM error / exception).  AppViewModel surfaces
     * a global ErrorBanner so the user gets a system-level
     * signal even when not looking at the bubble list.  The
     * caller dedups via its own [reportedCycleErrors] set so a
     * cycle emitting multiple error events only shows the
     * banner once. */
    private val onCycleError: (cycleId: String, message: String) -> Unit = { _, _ -> },
    /**
     * Callback fired when a cycle reaches its terminal COMPLETE
     * state via [ToolUseLoop.Outcome.Bubble].  AppViewModel uses
     * this to sync the shutter counter (`syncCycleCounters`); the
     * busy spinner is now derived from [busy], no imperative
     * flip needed. */
    private val onCycleComplete: (cycleId: String, bubble: Bubble) -> Unit = { _, _ -> },
    /**
     * Callback fired when a cycle returns
     * [ToolUseLoop.Outcome.PendingUserInput].  AppViewModel
     * surfaces this as the user-input AlertDialog and routes
     * follow-up text back to the originating CycleJob via
     * [resumeCycle].  The placeholder Bubble is written into
     * `job.bubble.value` so the live UI renders it as a
     * BubbleCard. */
    private val onPendingUserInput: (cycleId: String, request: UserInputRequest, placeholder: Bubble) -> Unit = { _, _, _ -> },
    /**
     * Per-cycle wall-clock cap on the LLM call.  90s is generous
     * enough for 2-3-round cycles on slow networks but tight
     * enough that a true API hang surfaces within ~90s and the
     * InFlightCard flips to its "识别超时" branch.  Configurable
     * for tests (100ms exercises the timeout branch in <1s). */
    private val llmTimeoutMs: Long = 90_000L,
) {
    private val _allJobs = MutableStateFlow<Map<String, CycleJob>>(emptyMap())
    val allJobs: StateFlow<Map<String, CycleJob>> = _allJobs.asStateFlow()

    private val _focusedJobId = MutableStateFlow<String?>(null)
    val focusedJobId: StateFlow<String?> = _focusedJobId.asStateFlow()

    /** Derived "spinner should show" signal.  True iff the focused
     *  job exists and is in PENDING or IN_FLIGHT (the two statuses
     *  where recognition work is actively happening).  ERRORED and
     *  SUPERSEDED are terminal and stay false here — the UI gets
     *  the per-cycle "识别超时, 请再拍一张" affordance via the
     *  BubbleCard's status flow instead of a global spinner.
     *
     *  Built by `flatMapLatest` over [_focusedJobId] and the focused
     *  job's [CycleJob.status] flow so a status flip re-emits without
     *  needing the cycle map's structure to change (a status mutation
     *  alone wouldn't update `allJobs`, so a flat `combine` would miss
     *  the transition).
     *
     *  Scope is the constructor-injected `scope` (typically
     *  viewModelScope) so the flow lives for the lifetime of the
     *  ViewModel and is automatically cancelled on clear. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val busy: StateFlow<Boolean> = _focusedJobId
        .flatMapLatest { focusedId ->
            if (focusedId == null) flowOf(false)
            else _allJobs
                .map { it[focusedId] }
                .filterNotNull()
                .flatMapLatest { job -> job.status }
                .map { it == JobStatus.PENDING || it == JobStatus.IN_FLIGHT }
                .distinctUntilChanged()
        }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** Pending FIFO queue of job ids waiting for a worker slot.
     *  Only ever touched on [scope]'s dispatcher (Main.immediate),
     *  so a plain ArrayDeque is safe — [startCycle], [pump], and the
     *  worker `finally` blocks all run single-threaded there. */
    private val pendingQueue = ArrayDeque<String>()

    /** Number of worker coroutines currently running [runCycleLoop].
     *  Gated by [UiState.CYCLE_CONCURRENCY].  Same single-thread
     *  invariant as [pendingQueue]. */
    private var runningWorkers = 0

    /** Enqueue a new cycle for [frame].  Returns the [CycleJob]
     *  handle synchronously, or **null** if the queue is full
     *  (queued+in-flight already at [UiState.CYCLE_QUEUE_DEPTH]) —
     *  the shutter is dimmed at that point, so a null return is the
     *  defensive backstop for a tap that raced the gate.  The actual
     *  LLM work is deferred: the job sits PENDING in [pendingQueue]
     *  until [pump] hands it to one of the [UiState.CYCLE_CONCURRENCY]
     *  workers. */
    fun startCycle(frame: CapturedFrame): CycleJob? {
        // Backpressure: reject when queued + in-flight is saturated.
        //  COMPLETE / ERRORED / SUPERSEDED cycles do NOT count — a
        //  finished cycle frees a capture slot immediately.  This
        //  mirrors the shutter's `remaining = CYCLE_QUEUE_DEPTH -
        //  activeCycleCount` gate; startCycle enforcing it too means
        //  a tap that races the gate (finger down as the count hits
        //  0) can't overflow the pipeline.
        val liveCount = _allJobs.value.values.count {
            it.status.value == JobStatus.PENDING ||
                it.status.value == JobStatus.IN_FLIGHT
        }
        if (liveCount >= UiState.CYCLE_QUEUE_DEPTH) {
            log("CYCLE", "rejected: queue full ($liveCount/${UiState.CYCLE_QUEUE_DEPTH})")
            return null
        }

        val job = CycleJob(frame = frame)
        _focusedJobId.value = job.id

        // Insert + enforce the total map cap.  Copy-and-replace so
        // collectors always see an immutable snapshot — Compose's
        // structural-equality diffing relies on it.
        val updated = LinkedHashMap<String, CycleJob>(_allJobs.value)
        updated[job.id] = job
        // Cap the total map at CYCLES_MAX_TOTAL=8 (bounds memory +
        //  scrollback history).  Evict the oldest terminal cycle
        //  (COMPLETE / ERRORED / SUPERSEDED) first so a still-
        //  queued or in-flight cycle is never dropped out from
        //  under the user.  Live cycles are bounded by the
        //  backpressure check above (≤ CYCLE_QUEUE_DEPTH = the
        //  cap), so whenever size exceeds the cap at least one
        //  terminal entry exists to evict.
        //  See ADR docs/adr/2026-07-16-producer-consumer-pipeline.md.
        while (updated.size > UiState.CYCLES_MAX_TOTAL) {
            val toDrop = updated.values
                .filter {
                    it.status.value == JobStatus.COMPLETE ||
                        it.status.value == JobStatus.ERRORED ||
                        it.status.value == JobStatus.SUPERSEDED
                }
                .minByOrNull { it.createdAtMs }
                ?: break  // no terminal entry — leave the map as-is
            updated.remove(toDrop.id)
            log(
                "CYCLE",
                "evicted ${toDrop.id} (cap=${UiState.CYCLES_MAX_TOTAL} total, " +
                    "status=${toDrop.status.value})"
            )
        }
        _allJobs.value = updated

        // Enqueue for a worker instead of launching directly.  pump()
        // starts it iff a worker slot is free; otherwise it waits
        // PENDING until an earlier cycle finishes and re-pumps.
        pendingQueue.addLast(job.id)
        log("CYCLE", "enqueued ${job.id} (queued+inflight=${liveCount + 1}, workers=$runningWorkers)")
        pump()
        return job
    }

    /** Drain [pendingQueue] into worker coroutines while a slot is
     *  free.  Called from [startCycle] (new work arrived) and from
     *  every worker's `finally` (a slot freed).  Single-threaded on
     *  [scope]'s dispatcher, so the [runningWorkers] check-and-launch
     *  is race-free. */
    private fun pump() {
        while (runningWorkers < UiState.CYCLE_CONCURRENCY && pendingQueue.isNotEmpty()) {
            val id = pendingQueue.removeFirst()
            val job = _allJobs.value[id] ?: continue  // evicted while queued
            // Skip if it was cancelled/superseded before a worker
            // could pick it up (cancelCycle removes from the queue,
            // but guard defensively).
            if (job.status.value != JobStatus.PENDING) continue
            launchWorker(job, userText = "")
        }
    }

    /** Launch one worker coroutine for [job], accounting for the
     *  [runningWorkers] slot and re-pumping when it finishes (success,
     *  error, or cancellation — `finally` always runs).  Used by
     *  [pump] for queued jobs and by [resumeCycle] for a
     *  pending-input continuation.
     *
     *  `CoroutineStart.LAZY` + install-handle-then-start so
     *  `job.coroutine` is set BEFORE the body can suspend — otherwise
     *  a concurrent [cancelCycle] / [cancelAll] could miss the handle
     *  (the same race the pre-split resumeCycle guarded against). */
    private fun launchWorker(job: CycleJob, userText: String) {
        runningWorkers++
        val handle = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                runCycleLoop(job, userText = userText)
            } finally {
                runningWorkers--
                pump()
            }
        }
        job.coroutine = handle
        handle.start()
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
     *  Errors transition to ERRORED.
     *
     *  LLM call is wrapped in [withTimeoutOrNull] (90s default,
     *  configurable via [llmTimeoutMs]) so a hung API doesn't pin
     *  a cycle in IN_FLIGHT forever.  On timeout the cycle is
     *  marked ERRORED; any partial bubble emitted via onProgress
     *  is preserved in `job.bubble` for the InFlightCard's
     *  ERRORED branch ("识别超时, 请再拍一张").
     *
     *  `userText` parameter is empty on the initial [startCycle]
     *  invocation, populated with the user's follow-up text on a
     *  [resumeCycle] re-invocation.  Re-using the same
     *  `runCycleLoop` for both paths keeps the timeout /
     *  cancellation / per-emit validation / progress-mirroring
     *  logic in a single place.
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
                        // Per-emit gate: after every successful
                        //  emit_bubble parse, the orchestrator's
                        //  shouldFinalize() returns CONTINUE (missing
                        //  inputs → inject missing-input nudge + retry
                        //  up to 3 times) or FINALIZE (cycle ends with
                        //  current bubble).  maxRounds=4 matches
                        //  orchestrator default — 1 emit + 2-3 fills.
                        onEmit = { bubble, round ->
                            orchestrator.shouldFinalize(bubble, round, maxRounds = 4)
                        },
                        // Project validation state onto the bubble's
                        //  data-class fields (validatedInputs +
                        //  pendingInputs).  CycleJob's reactive flows
                        //  are kept in sync separately via
                        //  CycleJob.refreshValidation().
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
                            // Soft intent-alignment check.  Logs a
                            //  breadcrumb when bubble.intent doesn't
                            //  mention any primary noun from the
                            //  chosen action set — useful for
                            //  investigating "model picked right
                            //  action, wrote wrong intent"
                            //  regressions.  Warn-only; doesn't fail
                            //  the cycle.
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
                    // Fire the completion callback so AppViewModel
                    //  can sync the shutter counter.  The busy
                    //  signal is now a derived flow — no
                    //  imperative flip needed here.
                    onCycleComplete(job.id, outcome.bubble)
                    log("CYCLE", "complete ${job.id} bubble=${outcome.bubble.id}")
                }
                is ToolUseLoop.Outcome.PendingUserInput -> {
                    // The model needs free-form input to continue.
                    //  1. Normalize placeholder.cycleId so it always
                    //     matches the owning CycleJob (defensive —
                    //     ToolUseLoop.buildPlaceholder already does
                    //     this but a future regression there shouldn't
                    //     silently break resume routing).
                    //  2. Write the placeholder into `job.bubble`
                    //     so the live UI renders it as a BubbleCard.
                    //     IntentBubbles reads state.cycles, so the
                    //     per-job flow is the only path to visibility.
                    //  3. Fire the callback so AppViewModel can
                    //     surface the AlertDialog.
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

    /** Resume a cycle that returned [ToolUseLoop.Outcome.PendingUserInput]
     *  after the user submitted follow-up text.  Looks up the job
     *  by [cycleId] and re-launches [runCycleLoop] with the
     *  submitted `userText`.  Returns `true` if the resume was
     *  accepted, `false` if the cycle is no longer in a state where
     *  it can be resumed (not found or already terminal — the caller
     *  should clear the userInputRequest dialog without firing
     *  another LLM call).
     *
     *  Pre-launch side-effects: clear `job.bubble.value` (so the live
     *  UI flips back to InFlightCard spinner during the resumed
     *  call) and reset `validatedInputs` + `pendingInputs` +
     *  `nRounds` so chip states recompute from scratch.
     *
     *  The previous-coroutine invariant: [runCycleLoop] returns
     *  synchronously after firing the PendingUserInput callback,
     *  so by the time [resumeCycle] is called the old `job.coroutine`
     *  handle is already completed.  We replace it with the new
     *  handle so supersede / cancelAll can kill the resumed call. */
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
        // Route the resume through the same worker-pool accounting
        //  as a fresh cycle ([launchWorker] handles the runningWorkers
        //  slot + re-pump on completion).  Resumes are user-initiated
        //  and run immediately rather than re-queuing at the tail —
        //  may briefly make concurrency = m+1 if 2 workers are busy,
        //  acceptable trade for interactive responsiveness (the
        //  parked cycle already freed its slot on PendingUserInput).
        launchWorker(job, userText = userText)
        log(
            "CYCLE",
            "resume $cycleId userText='${userText.take(40)}' (size=${userText.length})"
        )
        return true
    }

    /** Discard a pending-input cycle when the user cancels the
     *  user-input dialog.  Marks the job SUPERSEDED + cancels its
     *  coroutine (if any) and removes it from [allJobs].  Without
     *  this, a cancelled input request would leave the cycle
     *  parked in IN_FLIGHT forever (consuming a slot + keeping
     *  the InFlightCard "识别中..." spinner stuck).  Idempotent
     *  on missing / already-terminal cycle ids. */
    fun cancelCycle(cycleId: String, reason: String) {
        val job = _allJobs.value[cycleId] ?: return
        if (job.status.value == JobStatus.COMPLETE ||
            job.status.value == JobStatus.ERRORED
        ) return
        job.status.value = JobStatus.SUPERSEDED
        job.coroutine?.cancel()
        // Drop it from the pending queue too (it may never have been
        // picked up by a worker).  Harmless no-op if not queued.
        pendingQueue.remove(cycleId)
        val updated = LinkedHashMap(_allJobs.value)
        updated.remove(cycleId)
        _allJobs.value = updated
        log("CYCLE", "cancelled pending cycle $cycleId: $reason")
    }

    /** For tests + AppViewModel's idle-check. */
    fun activeJobCount(): Int = _allJobs.value.count {
        it.value.status.value != JobStatus.COMPLETE
    }

    /** Count of cycles whose status is [JobStatus.PENDING] or
     *  [JobStatus.IN_FLIGHT] — the user-facing "in flight" gauge
     *  that drives the shutter counter (`CYCLE_QUEUE_DEPTH -
     *  inFlightJobCount()` = "remaining slots").  Distinct from
     *  [activeJobCount] which also counts ERRORED + SUPERSEDED:
     *  those are terminal and should not gate the shutter.
     *
     *  Used by AppViewModel.syncCycleCounters to populate
     *  UiState.activeCycleCount.  Cheap O(n) over the cycles
     *  map (n <= CYCLES_MAX_TOTAL in practice). */
    fun inFlightJobCount(): Int = _allJobs.value.values.count {
        it.status.value == JobStatus.IN_FLIGHT ||
            it.status.value == JobStatus.PENDING
    }

    /** Discard every cycle currently in [allJobs] and clear the map.
     *  Each non-COMPLETE job is marked SUPERSEDED and its
     *  [Job.coroutine] cancelled so the in-flight LLM HTTP request
     *  aborts (otherwise a 90-second `llmTimeoutMs` budget would
     *  bill for a discarded result).  Wired into
     *  AppViewModel.restartScanning so tapping "重新扫描" actually
     *  stops whatever the camera was doing.
     *
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
        // Drain the pending queue.  Do NOT touch runningWorkers:
        // only launched workers ever incremented it, and each
        // cancelled worker's own `finally` decrements it back (and
        // re-pumps into the now-empty queue — a no-op).  PENDING
        // jobs still in the queue were never launched, so they
        // never contributed to the count; clearing the queue is
        // enough to stop them.  Resetting the count here would race
        // the finally blocks and drive it negative.
        pendingQueue.clear()
        _allJobs.value = emptyMap()
        _focusedJobId.value = null
        log("CYCLE", "cancelled all (${jobs.size}) reason=$reason")
    }
}
