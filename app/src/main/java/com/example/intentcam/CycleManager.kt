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
 * [2026-07-14 Phase B — inversion v3.0; 2026-07-16 producer/consumer
 * split] Owns concurrent recognition cycles as a classic
 * **producer → bounded queue → worker pool → output** pipeline.
 *
 * The shutter (producer) never blocks: each tap calls [startCycle],
 * which enqueues a [CycleJob] (status PENDING) into a pending FIFO
 * queue.  A [pump] loop drains that queue into at most
 * [UiState.CYCLE_CONCURRENCY] (= 2) concurrent worker coroutines —
 * the rest of a rapid burst waits its turn as PENDING.  As each
 * worker finishes it decrements the running count and re-pumps, so
 * queued cycles start in capture order, 2-at-a-time.
 *
 * Two independent bounds (the whole point of the split):
 *   - **Queue depth** [UiState.CYCLE_QUEUE_DEPTH] (= 8, `n`):
 *     max queued+in-flight.  Backpressure — when reached,
 *     [startCycle] rejects (returns null) and the shutter is
 *     already dimmed (`remaining = CYCLE_QUEUE_DEPTH -
 *     activeCycleCount`).  COMPLETE/ERRORED/SUPERSEDED don't count,
 *     so a finished cycle frees a slot immediately.
 *   - **Concurrency** [UiState.CYCLE_CONCURRENCY] (= 2, `m`):
 *     max cycles actually running OCR+LLM at once.  Keeps
 *     concurrent Anthropic SSE streams low (fewer 529 storms),
 *     bounds device memory/CPU, avoids OCR contention.
 *
 * There is no longer a "supersede oldest on overflow + cancel its
 * LLM call" path — overflow is prevented by backpressure, so
 * in-flight work is never thrown away mid-stream (no wasted API
 * tokens).  Cancellation now only happens on explicit user intent
 * ([cancelCycle], [cancelAll] via restart / leave-screen).
 *
 * Status flow:
 *   - [startCycle] → new CycleJob (PENDING), enqueued, [pump]
 *   - worker picks it up → IN_FLIGHT, runCycle on ToolUseLoop
 *   - every [CycleProgress] → CycleJob.applyProgress + refreshValidation
 *   - cycle returns Outcome.Bubble → status = COMPLETE, worker re-pumps
 *   - exception / 529 storm / timeout → status = ERRORED, worker re-pumps
 *
 * Public surface:
 *   - [startCycle] — enqueue a new job (nullable: null == rejected, queue full)
 *   - [focusedJobId] — most-recent job's id, drives the live UI's
 *     "current capture" indicator
 *   - [allJobs] — every job keyed by id; the UI iterates this to
 *     render cards
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
     *  AppViewModel uses this to clear the busy signal — the
     *  pre-existing flow only flipped `analyzing` on the legacy
     *  single-cycle path, so live-UI cycles left the TopOverlay
     *  "识别中…" spinner stuck after completion. */
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
     *  `state.cycles`, so the placeholder has to land in the
     *  per-job flow to be visible). */
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
        // [2026-07-16] Total map cap (CYCLES_MAX_TOTAL=8) bounds
        //  memory + scrollback history.  Evict the oldest **terminal**
        //  cycle (COMPLETE / ERRORED / SUPERSEDED) first so a still-
        //  queued or in-flight cycle is never dropped out from under
        //  the user.  Live cycles are already bounded by the
        //  backpressure check above (≤ CYCLE_QUEUE_DEPTH = the cap),
        //  so whenever size exceeds the cap at least one terminal
        //  entry exists to evict.  No coroutine.cancel() needed —
        //  terminal cycles have no live coroutine.
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
                    // Fire the completion callback so AppViewModel
                    //  can sync the shutter counter.  The busy
                    //  signal is now a derived flow — no
                    //  imperative flip needed here.
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
                    //      IntentBubbles reads `state.cycles`, so the
                    //      per-job flow is the only way to make the
                    //      placeholder visible in the live card list.
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
        // [2026-07-16] Route the resume through the same worker-pool
        //  accounting as a fresh cycle ([launchWorker] handles the
        //  runningWorkers slot + re-pump on completion).  A resume is
        //  user-initiated and runs immediately rather than re-queuing
        //  at the tail — it may briefly make concurrency = m+1 if 2
        //  workers are already busy, which is an acceptable trade for
        //  interactive responsiveness (the parked cycle already freed
        //  its slot when it returned PendingUserInput).
        launchWorker(job, userText = userText)
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
     *  doing — the previous version left cycles running in the
     *  background, wasting API quota.
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
