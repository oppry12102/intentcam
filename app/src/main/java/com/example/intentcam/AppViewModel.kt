package com.example.intentcam

import android.app.Application
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val client = LlmClient(settings.load())

    /** [2026-07-10] Intent registry — the system prompt's type list
     *  and the `emit_bubble` tool description both read from this.
     *  Built first so the tool registry below can consult it.
     *
     *  [2026-07-13] Exposed as a public val so MainActivity can
     *  resolve [Bubble.type] back into an [IntentDecl] for per-family
     *  UI accent (location→green / phone+payment_qr→pink / OBSERVE
     *  base→blue / ACT_ON base→orange). */
    val intentRegistry = IntentRegistry().also { registerDefaultIntents(it) }

    /** [2026-07-10] Action registry — built alongside the intent
     *  registry.  Declares which chip actions show up on which
     *  intent.  Android-only (lives in `app/`) because action bodies
     *  take `android.content.Context`.  Exposed as a public val so
     *  MainActivity can resolve `bubble.actions` ids back into
     *  displayable [ActionDef]s (label / iconKey) for chip rendering. */
    val actionRegistry = ActionRegistry().also { registerDefaultActions(it) }

    /** [2026-07-10] Action resolver — given a bubble, decides which
     *  action ids should surface as chips.  Reads the user's
     *  enabled-actions preference on every cycle (cheap; the Flow is
     *  just a SharedPreferences lookup).
     *
     * [2026-07-13] Two-layer gate:
     *   1. The legacy `enabledActionIds` set (op-out list).
     *   2. Per-action `userPrefKey` toggle from SettingsStore.
     *      An action with a `userPrefKey` is enabled only when the
     *      corresponding boolean in SharedPreferences is true —
     *      PII actions (`dial_number`, future `read_id_card`,
     *      `pay_*`, etc.) ship OFF by default and require an
     *      explicit opt-in.  Universal actions (`share`,
     *      `open_in_maps`) have `userPrefKey=null` and pass
     *      through unchanged.  The settings UI to flip these
     *      toggles arrives with Phase B (PII framework); for now
     *      they stay disabled. */
    private val actionResolver = ActionResolver(
        actions = actionRegistry,
        intents = intentRegistry,
        enabledIds = {
            val base = settings.loadEnabledActions() ?: actionRegistry.allIds().toSet()
            // Apply userPrefKey gates: remove any action whose
            // pref key is set but not granted.  Cheap — at most
            // ~5 actions per rebuild, SharedPreferences is
            // already a memory cache.
            val gated = base.toMutableSet()
            for (def in actionRegistry.list()) {
                val key = def.userPrefKey ?: continue
                if (!settings.loadActionPermission(key)) gated.remove(def.id)
            }
            gated
        },
    )

    /** [2026-07-15] Cycle ERRORED → ErrorBanner.  Pre-existing
     *  callback (was inlined at the constructor site); extracted
     *  to a method reference so the new [onCycleComplete] +
     *  [onPendingUserInput] handlers can sit alongside without
     *  re-introducing the "cycleManager forward reference"
     *  compile error. */
    private fun handleCycleError(cycleId: String, message: String) {
        if (reportedCycleErrors.add(cycleId)) {
            _state.value = _state.value.copy(
                error = "识别失败: $message"
            )
        }
        // [2026-07-15 P0 fix] Clear the spinner if this error
        //  left no PENDING/IN_FLIGHT cycles behind.  Pre-fix
        //  used `activeJobCount() == 0`, which includes
        //  ERRORED — so an all-error session (LLM 529 storm,
        //  network outage, every cycle timed out) pinned the
        //  spinner forever because the errored cycles kept
        //  `activeJobCount` > 0.  Switched to `inFlightJobCount
        //  () == 0` which only counts PENDING + IN_FLIGHT, so
        //  ERRORED cycles no longer block the spinner from
        //  clearing.  Also calls `syncCycleCounters` so the
        //  shutter counter ticks down immediately.
        if (cycleManager.inFlightJobCount() == 0) {
            _state.value = _state.value.copy(analyzing = false)
        }
        syncCycleCounters()
        logDebug("CYCLE", "error live cycle ${cycleId.take(8)}: $message")
    }

    /** [2026-07-15 P0 fix] Live cycle COMPLETE → clear the
     *  TopOverlay's stuck "识别中..." spinner.  Pre-existing
     *  bug: the legacy `runToolUseCycle` already wrote
     *  `analyzing = false` in its outcome branches; the live
     *  CycleManager path didn't, so the indicator stayed on
     *  after a photo finished.  Guarded by
     *  `inFlightJobCount() == 0` so a simultaneous in-flight
     *  cycle (rapid double-tap) keeps it lit.  Also calls
     *  [syncCycleCounters] so the shutter counter ticks up
     *  immediately when this is the last in-flight cycle. */
    private fun handleCycleComplete(cycleId: String, bubble: Bubble) {
        if (cycleManager.inFlightJobCount() == 0) {
            _state.value = _state.value.copy(analyzing = false)
        }
        syncCycleCounters()
        logDebug(
            "CYCLE",
            "complete live cycle ${cycleId.take(8)} " +
                "type=${bubble.type} actions=${bubble.actions.size}"
        )
    }

    /** [2026-07-15 P0 fix] Single source of truth for the
     *  `state.activeCycleCount` field.  Called from every
     *  cycle transition (startCycle / complete / error /
     *  cancel / restart) so the shutter counter stays in
     *  sync without per-status subscriptions on each
     *  [com.example.intentcam.CycleSnapshot].
     *
     *  Reads from [com.example.intentcam.CycleManager.inFlightJobCount]
     *  — PENDING + IN_FLIGHT only — because COMPLETE /
     *  ERRORED / SUPERSEDED entries must NOT count toward the
     *  user's "remaining slots" gauge (that's the whole point
     *  of switching from `cycles.size` to `inFlightJobCount`;
     *  see [com.example.intentcam.UiState.activeCycleCount]'s
     *  docstring for the bug it fixes). */
    private fun syncCycleCounters() {
        val inFlight = cycleManager.inFlightJobCount()
        if (_state.value.activeCycleCount == inFlight) return
        _state.value = _state.value.copy(activeCycleCount = inFlight)
    }

    /** [2026-07-15 P0 fix] Live cycle PendingUserInput →
     *  AlertDialog + stash full-res for [submitUserInput]'s
     *  resume.  Mirrors the legacy single-cycle path at
     *  AppViewModel.kt:626-639 so the two sources of input
     *  requests look identical to the UI.  Critically: also
     *  writes `pendingCycleId` so [submitUserInput] knows to
     *  call [CycleManager.resumeCycle] instead of starting a
     *  fresh legacy cycle that would orphan the live one. */
    private fun handlePendingUserInput(
        cycleId: String,
        request: UserInputRequest,
        placeholder: Bubble,
    ) {
        val job = cycleManager.allJobs.value[cycleId]
        pendingFullRes = job?.frame?.fullRes ?: placeholder.imageBytes
        pendingCycleId = cycleId
        val merged = (_state.value.bubbles + placeholder)
            .takeLast(UiState.BUBBLE_MAX)
        _state.value = _state.value.copy(
            scene = request.prompt,
            bubbles = merged,
            analyzing = false,
            userInputRequest = request,
        )
        logDebug(
            "INPUT",
            "需要补充 via=${request.toolName} " +
                "(live-UI cycle=${cycleId.take(8)})"
        )
    }

    /** Tool registry used by [toolUseLoop].  Built once at construction
     *  and reused for every recognition cycle.  Adding tools requires
     *  re-registering here. */
    private val toolRegistry = ToolRegistry().also {
        it.registerDefaultTools(intentRegistry)
    }

    private val toolUseLoop = ToolUseLoop(
        client = client,
        registry = toolRegistry,
        intents = intentRegistry,
        log = ::logDebug,
    )

    /** [2026-07-14 Phase A] Thin orchestrator for action-driven
     *  input validation.  Constructed after the action registry
     *  (needs the registered actions to render the system prompt
     *  block).  Consumed by [CycleManager] for per-emit validation
     *  + missing-input framing.  In Phase A it sits unused; Phase
     *  B wires it into the cycle loop. */
    private val actionOrchestrator = ActionOrchestrator(actions = actionRegistry)

    /** [2026-07-14 Phase B — inversion v3.0] Owns concurrent
     *  recognition cycles.  Replaces the single-cycle flow that
     *  `captureLatestFrame()` used to launch directly.  The
     *  shutter button is now always enabled; tapping it just
     *  spawns a new [com.example.intentcam.CycleJob] (up to
     *  [UiState.CYCLE_MAX_CONCURRENT] = 2 in flight, oldest
     *  superseded when the cap is hit).  See CycleManager.kt
     *  for the full lifecycle. */
    /**
     * [2026-07-15] Tracks which cycle ids have already fired
     * `onCycleError`, so a cycle that hits ERRORED via multiple
     * paths (LLM error + exception, or timeout + then a
     * subsequent retry-error) only writes `state.error` once.
     * ConcurrentHashMap because `onCycleError` is invoked from
     * CycleManager's coroutine scope, which can be a different
     * thread than the state-write path.  Bounded by the
     * `allJobs` map size (cleared on restartScanning).
     */
    private val reportedCycleErrors = ConcurrentHashMap.newKeySet<String>()

    private val cycleManager = CycleManager(
        scope = viewModelScope,
        toolUseLoop = toolUseLoop,
        orchestrator = actionOrchestrator,
        actionRegistry = actionRegistry,
        actionResolver = actionResolver,
        enabledIds = {
            val base = settings.loadEnabledActions() ?: actionRegistry.allIds().toSet()
            val gated = base.toMutableSet()
            for (def in actionRegistry.list()) {
                val key = def.userPrefKey ?: continue
                if (!settings.loadActionPermission(key)) gated.remove(def.id)
            }
            gated
        },
        log = ::logDebug,
        // [2026-07-15] Surface cycle ERRORED events to the global
        //  ErrorBanner.  Dedup per cycle id so a single failed
        //  cycle doesn't spam the banner (a cycle can hit the
        //  callback more than once if the orchestrator's exception
        //  path fires after a timeout already set ERRORED).
        onCycleError = ::handleCycleError,
        // [2026-07-15 P0 fix] Live cycle completion + pending-input
        //  handlers are method references — captured here so the
        //  constructor argument list stays short, and the handlers
        //  read `cycleManager` lazily (avoids the "variable must be
        //  initialized" forward-reference the compiler hit when we
        //  tried inlining them as lambdas).
        onCycleComplete = ::handleCycleComplete,
        onPendingUserInput = ::handlePendingUserInput,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** [2026-07-12] CameraX provider future, kicked off at construction
     *  time so the service connection is established in parallel with
     *  permission grant and UI render.  Consumed by the AndroidView
     *  factory in MainActivity which calls `.get()` on it — by then
     *  the future is usually already done.
     *
     *  `ProcessCameraProvider.getInstance()` is idempotent (returns the
     *  same singleton future for the same process), so calling it
     *  again from the AndroidView factory is safe — both listeners
     *  fire when the underlying service is connected. */
    private val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
        ProcessCameraProvider.getInstance(app).also { future ->
            val start = SystemClock.elapsedRealtime()
            future.addListener({
                val ready = SystemClock.elapsedRealtime() - start
                logDebug("CAM", "provider ready after ${ready}ms (cold start)")
            }, java.util.concurrent.Executor { it.run() })
        }

    /** [2026-07-14 Phase B] Subscribe to cycleManager.allJobs +
     *  focusedJobId and mirror them into [UiState.cycles].  Compose
     *  reads from UiState so this is the only coupling between
     *  CycleManager and the rest of the app.  The legacy
     *  [UiState.bubbles] field is also updated (derived from the
     *  focused job's bubble) so Phase A/C features that read
     *  `bubbles` keep working without rewriting every call site.
     *
     *  The `analyzing` flag is now driven by
     *  `cycleManager.hasFocusedJob()` rather than the legacy
     *  AtomicBoolean — see [captureLatestFrame] / [runToolUseCycle]. */
    init {
        viewModelScope.launch {
            cycleManager.allJobs.collect { jobs ->
                _state.value = _state.value.copy(
                    cycles = jobs.mapValues { (_, job) ->
                        com.example.intentcam.CycleSnapshot(
                            id = job.id,
                            status = job.status,
                            bubble = job.bubble,
                            nRounds = job.nRounds,
                            capturedAtMs = job.createdAtMs,
                            // [2026-07-15] Surface pending inputs
                            //  on the snapshot so consumers reading
                            //  UiState.cycles (debug overlay, future
                            //  REST API) see the same flow the live
                            //  UI reads via CycleJob directly.
                            pendingInputs = job.pendingInputs,
                            // [2026-07-15 P1 fix] Expose the
                            //  captured frame's thumbnail so the
                            //  BubbleCard can render its image
                            //  slot from shutter-tap onward
                            //  (same bytes as bubble.imageBytes
                            //  will be once the cycle emits).
                            //  See CycleSnapshot.thumbnail's
                            //  docstring for the layout-jump bug
                            //  this fixes.
                            thumbnail = job.frame.thumbnail,
                        )
                    }
                )
            }
        }
    }

    /** Bus flag for "a recognition cycle is currently running".
     *  [2026-07-14 Phase B] Now a derived read of
     *  [cycleManager.hasFocusedJob] — the AtomicBoolean legacy
     *  field has been removed; the only authoritative source for
     *  "is the camera busy" is the focused job's status.  Kept
     *  as a `val` (not a `var`) so call sites can't write to it. */
    private val analyzing: Boolean
        get() = cycleManager.hasFocusedJob()

    init {
        // Restore the persisted debug-overlay preference.  Defer the state
        // write to viewModelScope so it never happens during the first
        // composition (which is in flight when this ViewModel is first
        // accessed by `viewModel.state.collectAsState()`).
        viewModelScope.launch {
            _state.value = _state.value.copy(debugEnabled = settings.loadDebugEnabled())
        }
        // Pre-warming handled at the property-declaration site
        // above; nothing else to do in init.
    }

    val config: LlmConfig get() = client.config

    /**
     * Gate for the camera analyzer.  Default false: the analyzer runs its
     * cheap path (close the ImageProxy, no encode) and never produces a
     * JPEG.  The next time we want to capture a frame, [captureLatestFrame]
     * sets this true; the analyzer's first [tryArmCapture] wins the CAS,
     * encodes one frame, and reports it.  This keeps the camera idle at
     * startup — no bitmap allocation, no JPEG encoding — which is what
     * lets the app survive the OS's first-launch memory pressure on
     * low-RAM devices.
     */
    private val captureArmed = AtomicBoolean(false)

    /**
     * Latest camera frame pushed by the analyzer.  Cached until the
     * user taps the shutter.  Cleared on tap so the same frame can't
     * be dispatched twice if the analyzer hasn't pushed a fresh one yet.
     */
    @Volatile private var latestFrame: CapturedFrame? = null

    // Full-res crop source for the single in-flight needs-input placeholder.
    // Kept out of the bubble history (bubbles carry only display thumbnails)
    // so a 4-bubble history stays small; consumed on submitUserInput, cleared
    // on cancel. See the Bubble.imageBytes doc and submitUserInput.
    private var pendingFullRes: ByteArray? = null

    // [2026-07-15 P0 fix] Cycle id of the live-UI cycle that
    //  returned PendingUserInput, paired with [pendingFullRes]
    //  above.  Lets `submitUserInput` route the follow-up text
    //  back to the originating CycleJob via
    //  `cycleManager.resumeCycle(id, text)` instead of the legacy
    //  `runToolUseCycle(frame, ...)` fallback.  Cleared together
    //  with [pendingFullRes] on submit, cancel, restart, and
    //  any reset path that invalidates cycles.
    private var pendingCycleId: String? = null

    /**
     * Atomically check + disarm the camera analyzer.  Returns true on the
     * single frame that wins the CAS (the one we'll actually capture);
     * false on every other frame.  Passed as the `isArmed` callback to
     * [FrameAnalyzer].
     */
    fun tryArmCapture(): Boolean = captureArmed.compareAndSet(true, false)

    // ---- debug log -----------------------------------------------------------
    //
    // Recognition-pipeline log surfaced as a scrolling overlay while
    // [UiState.debugEnabled] is true.  Backed by an ArrayDeque guarded by
    // [debugLogsLock] — logDebug() can be invoked from the analyzer thread
    // (via onFrame) and from viewModelScope coroutines concurrently.
    private val debugLogs = ArrayDeque<DebugLogEntry>()
    private val debugLogsLock = Any()
    // 2026-07-14 C-cleanup: analyzer errors live in a SEPARATE buffer
    // from `debugLogs` so they survive a `setDebugEnabled(false)` toggle.
    // The in-app overlay shows this list regardless of the toggle.
    private val analyzerErrorLog = ArrayDeque<DebugLogEntry>()
    private val analyzerErrorLogLock = Any()
    // Monotonic counter for unique LazyColumn keys.  timestampMs has
    // millisecond resolution and multiple logDebug calls in the same ms
    // (very common in a tight tool-use loop) would crash the panel with
    // "Key X was already used".
    private val debugLogSeq = AtomicLong(0)

    fun isBusy(): Boolean {
        if (analyzing) return true
        // When the user is looking at a bubble's detail view, the camera
        // pipeline must stay quiet so the displayed image doesn't go stale.
        // SelectedBubble and phase are kept in sync by [selectBubble] /
        // [clearBubbleSelection], so checking phase alone is enough.
        if (_state.value.phase != Phase.SCANNING) return true
        return false
    }

    /**
     * Toggle the on-screen debug overlay.  When OFF, the log buffer is
     * flushed and no new entries are collected until the next ON.  The
     * preference is persisted so the developer doesn't have to re-enable
     * every launch.
     */
    fun setDebugEnabled(enabled: Boolean) {
        if (_state.value.debugEnabled == enabled) return
        settings.saveDebugEnabled(enabled)
        synchronized(debugLogsLock) {
            if (!enabled) debugLogs.clear()
        }
        _state.value = _state.value.copy(
            debugEnabled = enabled,
            debugLogs = if (enabled) snapshotDebugLogs() else emptyList(),
        )
    }

    private fun snapshotDebugLogs(): List<DebugLogEntry> = synchronized(debugLogsLock) {
        debugLogs.toList()
    }

    private fun snapshotAnalyzerErrorLog(): List<DebugLogEntry> = synchronized(analyzerErrorLogLock) {
        analyzerErrorLog.toList()
    }

    /**
     * Public surface for the camera analyzer to surface exceptions into the
     * in-app debug overlay.  Lives next to [logDebug] (private) so the only
     * way external callers reach the debug log is through named wrappers
     * that hard-code the tag.
     *
     * 2026-07-14 C-cleanup: analyzer errors are now ALWAYS recorded
     * (independent of [UiState.debugEnabled]) so an OOM or exception
     * inside `FrameAnalyzer.analyze` is still visible after the user
     * toggled the debug panel off.  Storage stays in a separate
     * `analyzerErrorLog` buffer that survives the toggle; the
     * in-app overlay shows a separate red-bordered section when
     * non-empty.  `logDebug` (other call sites) is unchanged.
     */
    fun logAnalyzerError(message: String) {
        val safe = message.replace('\n', ' ').replace('\r', ' ')
        val entry = DebugLogEntry(
            timestampMs = System.currentTimeMillis(),
            seq = debugLogSeq.incrementAndGet(),
            tag = "ANALYZER",
            message = safe,
        )
        synchronized(analyzerErrorLogLock) {
            analyzerErrorLog.addLast(entry)
            while (analyzerErrorLog.size > UiState.DEBUG_LOG_MAX) analyzerErrorLog.removeFirst()
        }
        _state.value = _state.value.copy(
            analyzerErrorLog = snapshotAnalyzerErrorLog()
        )
    }

    /**
     * Append one entry to the debug log.  No-ops when [UiState.debugEnabled]
     * is false so the toggle actually saves the cost of string formatting.
     * Newlines are stripped so each entry renders as one LazyColumn row,
     * but no character cap — callers pass full exception messages / stack
     * traces and we let the panel auto-wrap.  DEBUG mode is for hunting
     * crashes; truncation defeats the purpose.
     */
    private fun logDebug(tag: String, message: String) {
        if (!_state.value.debugEnabled) return
        val safe = message.replace('\n', ' ').replace('\r', ' ')
        val entry = DebugLogEntry(
            timestampMs = System.currentTimeMillis(),
            seq = debugLogSeq.incrementAndGet(),
            tag = tag,
            message = safe,
        )
        synchronized(debugLogsLock) {
            debugLogs.addLast(entry)
            while (debugLogs.size > UiState.DEBUG_LOG_MAX) debugLogs.removeFirst()
        }
        _state.value = _state.value.copy(debugLogs = snapshotDebugLogs())
    }

    /**
     * Read a JPEG's pixel dimensions without decoding the bitmap.
     * `inJustDecodeBounds=true` only parses the SOF marker so this
     * is allocation-free — useful in the CAP log to verify that the
     * ImageAnalysis source actually came in at the configured
     * resolution (vs CameraX's 640×480 default).  Returns
     * "WxH" or "?" on a malformed JPEG.
     */
    private fun jpegBounds(jpeg: ByteArray): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        return if (w > 0 && h > 0) "${w}x${h}" else "?"
    }

    /**
     * Called by the camera analyzer each time it produces a fresh
     * frame.  Carries two encodings: a thumbnail (for LLM initial
     * image) and a full-resolution copy (kept in memory for tools
     * like zoom_in that need to crop a region at native pixels).
     */
    fun onFrame(frame: CapturedFrame) {
        // The analyzer already gated on captureArmed before calling us, so
        // a frame here is always the one the user wanted.
        if (_state.value.phase != Phase.SCANNING) {
            // Out of phase — drop on the floor so the cycle doesn't pick
            // up a stale frame if/when it wakes up.
            return
        }
        latestFrame = frame
    }

    /**
     * User tapped the shutter button.  Arms the camera analyzer (so the
     * next frame it produces is the one we use), waits up to 3000 ms for
     * the frame to arrive, then hands it to [CycleManager.startCycle].
     *
     * [2026-07-14 Phase B — inversion v3.0] No longer gates on the
     * legacy `AtomicBoolean analyzing` re-entrancy flag — every tap
     * spawns a new [com.example.intentcam.CycleJob], and CycleManager
     * caps concurrency at [UiState.CYCLE_MAX_CONCURRENT] = 2 (oldest
     * non-COMPLETE job is dropped when a 3rd tap arrives).  The
     * shutter button in [MainActivity] now derives its `enabled`
     * state from `cycleManager.hasFocusedJob()` so the button is
     * **always** tappable; the gating that used to block rapid taps
     * now lives at the CycleManager cap.
     */
    fun captureLatestFrame() {
        // [2026-07-15 UI polish] CAS instead of set(true).  Previously,
        //  a fast double-tap of the shutter would race: the first tap
        //  set `captureArmed=true` and the second tap saw `latestFrame`
        //  == null still, so the second `viewModelScope.launch`'s
        //  3-second wait loop timed out and the user saw a "3000ms
        //  内没拿到帧" warning.  The atomic `compareAndSet(false,
        //  true)` makes the second tap a no-op — the shutter's spinner
        //  is the user's only signal that one capture is in flight.
        //  Phase B already caps the number of concurrent cycles at
        //  CYCLE_MAX_CONCURRENT=2, but a second tap that races the
        //  analyzer's encode (~50-200ms on a mid-range phone) is
        //  genuinely "double-tap on the same frame" and not "I want
        //  a second cycle queued", so we drop it.
        if (!captureArmed.compareAndSet(false, true)) {
            logDebug("CAP", "第二次 shutter tap 忽略（captureArmed 已被上一拍占用）")
            return
        }
        enterAnalyzing()
        viewModelScope.launch {
            try {
                // Wait for the analyzer to deliver the next frame.
                // [2026-07-12] raised 500ms→3000ms (cold start + larger
                //  encodes).  See Phase B preamble in plan file for the
                //  full rationale.
                val deadline = SystemClock.elapsedRealtime() + 3000L
                val t0 = SystemClock.elapsedRealtime()
                while (latestFrame == null &&
                    _state.value.phase == Phase.SCANNING &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    delay(20L)
                }
                val waitedMs = SystemClock.elapsedRealtime() - t0
                captureArmed.set(false)
                val frame = latestFrame
                latestFrame = null
                if (frame == null) {
                    logDebug("CAP", "3000ms 内没拿到帧（等了 ${waitedMs}ms）")
                    return@launch
                }
                logDebug(
                    "CAP",
                    "用户触发 waited=${waitedMs}ms " +
                        "thumb=${frame.thumbnail.size / 1024}KB " +
                        "full=${frame.fullRes.size / 1024}KB " +
                        "src=${jpegBounds(frame.fullRes)}"
                )
                // Hand off to CycleManager — it owns the actual cycle
                // coroutine + per-job bubble flow.  We do NOT block
                // on completion; the user can take more photos in the
                // meantime.
                cycleManager.startCycle(frame)
                // [2026-07-15 P0 fix] Tick the shutter counter
                //  down immediately on successful startCycle.
                //  Without this, the counter stays at the
                //  pre-tap value for the duration of the frame-
                //  wait + LLM-call (typically <100ms for the
                //  counter to feel responsive), which makes
                //  rapid double-taps look like the button didn't
                //  register.  Now the count drops the instant
                //  the cycle enters IN_FLIGHT.
                syncCycleCounters()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logDebug("FATAL", formatThrowable(e))
                _state.value = _state.value.copy(error = e.message)
            } finally {
                // [2026-07-15 P0 fix] Switched from
                //  `activeJobCount()` to `inFlightJobCount()` so
                //  ERRORED cycles no longer keep the spinner
                //  lit after the user cancels mid-cycle.  Also
                //  fixed in handleCycleError above — this site
                //  covers the "frame never arrived" path
                //  (captureArmed fired but the analyzer didn't
                //  produce a frame within 3s).
                if (cycleManager.inFlightJobCount() == 0) {
                    _state.value = _state.value.copy(analyzing = false)
                }
                syncCycleCounters()
            }
        }
    }

    /**
     * Flip the shared UI state into the "analyzing" mode.  Called from
     * every entry point that kicks off recognition (shutter tap,
     * text-input submit, internal cycle restart).  Centralized so the
     * three call sites don't drift on which fields they set.
     */
    private fun enterAnalyzing() {
        _state.value = _state.value.copy(analyzing = true, error = null)
    }

    /**
     * One-shot recognition cycle: delegates to [ToolUseLoop], which
     * drives a multi-round tool-use conversation with the model and
     * returns a final bubble (or a user-input placeholder).
     */
    private suspend fun runRecognitionCycle(frame: CapturedFrame) {
        // [captureLatestFrame] already flipped into analyzing mode; no
        // re-set needed.  Keep the entry point as a separate function
        // so submitUserInput can also reach runToolUseCycle without
        // going through the shutter-button frame-wait.
        runToolUseCycle(frame, userText = "")
    }

    /** Multi-round tool-use path.  Emits a bubble, sets a placeholder,
     *  or surfaces an error in [UiState]. */
    private suspend fun runToolUseCycle(frame: CapturedFrame, userText: String) {
        logDebug(
            "TOOL",
            "→ runCycle (thumb=${frame.thumbnail.size / 1024}KB " +
                "full=${frame.fullRes.size / 1024}KB " +
                "userText='${userText.take(40)}')"
        )
        val outcome = withContext(Dispatchers.IO) {
            toolUseLoop.runCycle(
                thumbnail = frame.thumbnail,
                fullRes = frame.fullRes,
                userText = userText,
                // [2026-07-13] Splice the registered ActionDef ids
                // into the system prompt so emit_bubble.action_ids
                // can fire.  All ids registered in the ActionRegistry
                // are eligible (the resolver still filters by
                // enabledIds at render time, so a disabled action
                // never reaches the chip UI).
                actionIds = actionRegistry.allIds(),
            )
        }
        when (outcome) {
            is ToolUseLoop.Outcome.Bubble -> {
                // [2026-07-10] Resolve action ids on the bubble before
                //  stashing it.  The resolver consults
                //  IntentRegistry (for "which intent is this bubble")
                //  and SettingsStore (for "which actions the user has
                //  disabled").  Returns ids in declaration order; the
                //  UI looks each one up in ActionRegistry to render
                //  chips.
                val withActions = outcome.bubble.copy(
                    actions = actionResolver.suggestIds(outcome.bubble)
                )
                val merged = (_state.value.bubbles + withActions).takeLast(UiState.BUBBLE_MAX)
                _state.value = _state.value.copy(
                    scene = withActions.detail.take(80),
                    bubbles = merged,
                    analyzing = false,
                )
                logDebug(
                    "FINAL",
                    "type=${withActions.type} intent=${withActions.title} " +
                        "via=${withActions.toolName ?: "?"} " +
                        "actions=${withActions.actions.size}"
                )
            }
            is ToolUseLoop.Outcome.PendingUserInput -> {
                // The placeholder bubble only carries the display thumbnail
                // (see Bubble.imageBytes). The full-res original — needed as
                // the crop source if the resumed cycle calls zoom_in — is
                // stashed here in a single transient field rather than in the
                // bubble history. Invariant: at most one placeholder is
                // pending at a time (analyzing lock + blocking text input),
                // so a single field is sufficient.
                pendingFullRes = frame.fullRes
                // [2026-07-15 P0 fix] Defensive: clear any stale
                //  live-cycleId from a prior resume attempt.  The
                //  legacy path doesn't use it (resumes via
                //  runToolUseCycle), so leaving it set would only
                //  confuse a future state inspection.
                pendingCycleId = null
                val merged = (_state.value.bubbles + outcome.placeholder).takeLast(UiState.BUBBLE_MAX)
                _state.value = _state.value.copy(
                    scene = outcome.request.prompt,
                    bubbles = merged,
                    analyzing = false,
                    userInputRequest = outcome.request,
                )
                logDebug("INPUT", "需要补充 via=${outcome.request.toolName}")
            }
            is ToolUseLoop.Outcome.Error -> {
                _state.value = _state.value.copy(
                    analyzing = false,
                    error = outcome.message,
                )
                logDebug("FINAL_ERR", outcome.message)
            }
        }
    }

    /** User submitted text for the [UiState.userInputRequest].  Resumes
     *  the recognition cycle by re-running [ToolUseLoop.runCycle] with
     *  the new text. */
    fun submitUserInput(text: String) {
        // [2026-07-15 P0 fix] Capture the exact cycle id paired
        //  with the parked placeholder before we touch any state.
        //  Earlier versions only matched on `bubbles.lastOrNull
        //  { it.needsUserInput }`, which works for the legacy
        //  single-cycle path but would conflate placeholders if
        //  multiple live cycles ever produced them concurrently.
        //  [pendingCycleId] is non-null iff the input request came
        //  from the live CycleManager path; null means it came
        //  from the legacy single-cycle path (eval, or the rare
        //  legacy direct-tool call).
        val pendingId = pendingCycleId
        val placeholder = _state.value.bubbles.lastOrNull { it.needsUserInput }
        val jpeg = placeholder?.imageBytes
        if (jpeg == null) {
            // No placeholder — defensive cleanup, dismiss dialog.
            pendingCycleId = null
            pendingFullRes = null
            _state.value = _state.value.copy(userInputRequest = null)
            return
        }
        // Synchronous state reset BEFORE launching the resume so
        // a rapid double-submit can't queue two resume calls for
        // the same cycle.  CycleManager.resumeCycle's own
        // rejection-on-terminal-status check is the second line
        // of defense.
        _state.value = _state.value.copy(
            analyzing = true,
            userInputRequest = null,
            error = null,
        )
        // Remove the placeholder from the bubbles list (the live
        // UI's per-job bubble is also cleared by
        // CycleManager.resumeCycle).  Keep `pendingFullRes` until
        // the legacy path's `CapturedFrame` construction reads it
        // — the live path doesn't need it (CycleJob.frame is the
        // authoritative source).
        val withoutPlaceholder = _state.value.bubbles.filterNot {
            it.id == placeholder.id
        }
        _state.value = _state.value.copy(bubbles = withoutPlaceholder)
        if (pendingId != null) {
            // Live-UI path: route the resume through CycleManager
            // so the same CycleJob's bubble/status flows carry the
            // new emissions.  resumeCycle() returns false if the
            // cycle is no longer resumable (already terminal /
            // superseded / evicted) — in that case we DO NOT fall
            // back to the legacy path, because that would create
            // a phantom bubble that doesn't correspond to any
            // CycleJob in the cycles map.  Clear the leftover
            // bookkeeping + log + bail.
            pendingCycleId = null
            // Live-UI resume: CycleJob owns the original
            //  full-res frame internally, so we don't need to
            //  reconstruct a CapturedFrame here.  We DO need to
            //  clear `pendingFullRes` so it doesn't leak into a
            //  future legacy-path call (defensive — the field
            //  is only read by [submitUserInput] / [runToolUseCycle]).
            pendingFullRes = null
            val accepted = cycleManager.resumeCycle(pendingId, text)
            if (!accepted) {
                logDebug(
                    "INPUT",
                    "live resume REJECTED for $pendingId " +
                        "(stale or terminal); clearing dialog without firing LLM"
                )
                if (cycleManager.inFlightJobCount() == 0) {
                    _state.value = _state.value.copy(analyzing = false)
                }
                // [2026-07-15 P0 fix] Sync the shutter counter on
                //  the rejection path — the cycle we just tried to
                //  resume is terminal, so inFlight dropped and the
                //  counter should tick up.
                syncCycleCounters()
            } else {
                logDebug("INPUT", "live resume ACCEPTED for $pendingId")
                // Accepted path: resumeCycle flipped the job back
                // to IN_FLIGHT (it's about to call runCycleLoop
                // again).  Sync so the counter ticks down the
                // moment the resume is acknowledged.
                syncCycleCounters()
            }
            return
        }
        // Legacy path: reconstruct a CapturedFrame from the
        // thumbnail + stashed full-res and call runToolUseCycle
        // directly (same as before).
        viewModelScope.launch {
            val fullRes = pendingFullRes ?: jpeg
            pendingFullRes = null
            val frame = CapturedFrame(thumbnail = jpeg, fullRes = fullRes)
            runToolUseCycle(frame, userText = text)
        }
    }

    /** User cancelled the [UiState.userInputRequest].  Drops the
     *  placeholder bubble and returns to scanning.
     *
     *  [2026-07-15 P0 fix] When the input request came from a
     *  live CycleManager cycle, also cancel the parked cycle via
     *  [CycleManager.cancelCycle] so it doesn't stay in IN_FLIGHT
     *  forever (which would keep its slot in CYCLES_MAX_TOTAL,
     *  render a stuck InFlightCard spinner, and consume an active
     *  cap count). */
    fun cancelUserInput() {
        val pendingId = pendingCycleId
        pendingCycleId = null
        pendingFullRes = null
        val placeholder = _state.value.bubbles.lastOrNull { it.needsUserInput }
        val request = _state.value.userInputRequest
        _state.value = _state.value.copy(
            userInputRequest = null,
            analyzing = false,
            bubbles = if (placeholder != null) {
                _state.value.bubbles.filterNot { it.id == placeholder.id }
            } else _state.value.bubbles,
        )
        if (pendingId != null) {
            cycleManager.cancelCycle(pendingId, "user cancelled input")
        }
        // [2026-07-15 P0 fix] Sync the shutter counter on
        //  cancel — cancelCycle removed the parked cycle from
        //  the map, so inFlight dropped and the counter should
        //  tick up immediately.  Without this the counter stays
        //  stale until the next state mutation fires.
        syncCycleCounters()
        if (request != null) logDebug("INPUT", "已取消 via=${request.toolName}")
    }

    fun onPermissionsGranted() {
        if (_state.value.phase == Phase.NEED_PERMISSION) {
            _state.value = _state.value.copy(phase = Phase.SCANNING)
        }
    }

    /**
     * User tapped a bubble.  Show its detail (full image + title + detail)
     * AND flip phase to SHOWING_DETAIL — the two must move together.  The
     * previous version only set [selectedBubble], which left phase=SCANNING
     * and broke the UI (DetailScreen's gate is `phase==SHOWING_DETAIL &&
     * selectedBubble!=null`) AND silently dropped every captured frame via
     * `onFrame`'s selectedBubble guard.  Symptom: tap a bubble (nothing
     * visible happens), tap shutter again → "[CAP] 500ms 内没拿到帧".
     */
    fun selectBubble(bubble: Bubble) {
        if (_state.value.selectedBubble?.id == bubble.id) return
        logDebug("BUBBLE", "select id=${bubble.id} title='${bubble.title.take(40)}'")
        _state.value = _state.value.copy(
            selectedBubble = bubble,
            phase = Phase.SHOWING_DETAIL,
        )
    }

    /** User dismissed the detail view; rearm the capture pipeline. */
    fun clearBubbleSelection() {
        if (_state.value.selectedBubble == null) return
        logDebug("BUBBLE", "clearSelection")
        _state.value = _state.value.copy(
            selectedBubble = null,
            phase = Phase.SCANNING,
        )
    }

    // NOTE: `runCycleWithPrePickedTool` removed 2026-07-10
    // — was a stub for the deferred action_chips feature.
    // `runAction` (below) is the re-introduced version, now backed by
    // the ActionRegistry (intent-action framework step 5+6, 2026-07-10).

    /**
     * User tapped an action chip on a bubble.  Looks up the
     * [ActionDef], runs its body with the Application context, and
     * dispatches the resulting [ActionOutcome] (startActivity for
     * outbound Intents, Toast for in-app feedback).
     *
     * [2026-07-13] Honors `requiresConfirmation`: actions tagged
     * for it (currently only `dial_number`) park a `pendingConfirmation`
     * in [UiState] instead of running the body immediately; the
     * MainActivity renders an AlertDialog; [confirmAction] /
     * [cancelConfirmation] drive the resolution.
     *
     * No-ops silently when the action id is unknown (defensive — a
     * stale bubble might reference an action that was removed) or
     * when the bubble id isn't in the current history (e.g. user
     * navigated away between render and tap).
     */
    fun runAction(actionId: String, bubbleId: String) {
        val bubble = findBubble(bubbleId)
        if (bubble == null) {
            logDebug("ACTION", "tap 收到 actionId=$actionId 但 bubble $bubbleId 不在历史中")
            return
        }
        val def = actionRegistry.get(actionId)
        if (def == null) {
            logDebug("ACTION", "未注册 action $actionId (bubble $bubbleId)")
            return
        }
        logDebug("ACTION", "tap ${def.id} for bubble='${bubble.title.take(40)}'")
        if (def.requiresConfirmation) {
            // Park a confirmation; MainActivity renders an AlertDialog.
            // Detail uses the same PhoneExtractor the body uses so the
            // user sees exactly what number will be handed to the
            // dialer — no surprise.
            val pending = PendingConfirmation(
                actionId = def.id,
                bubbleId = bubbleId,
                prompt = "确认拨打?",
                detail = "即将在系统拨号器中拨打 " +
                    (PhoneExtractor.firstMatch(bubble) ?: "未知号码"),
            )
            _state.value = _state.value.copy(pendingConfirmation = pending)
            logDebug("ACTION", "request confirm via=${def.id} prompt=${pending.detail}")
            return
        }
        executeAndDispatch(def, bubble, parsedArgsFor(bubble), bubbleId)
    }

    /** User tapped the action's "Confirm" button on the consent
     *  AlertDialog.  Drives [runAction]'s parked confirmation through
     *  [executeAndDispatch].  No-op when no confirmation is parked.
     *  After successful dispatch we grant the action's userPrefKey
     *  permission (so the chip stops prompting on subsequent taps)
     *  — the user just gave explicit consent by tapping "Confirm"
     *  once, that should persist. */
    fun confirmAction() {
        val pending = _state.value.pendingConfirmation ?: return
        logDebug("ACTION", "confirm ${pending.actionId}")
        // Clear the dialog first so a body that throws doesn't leave
        // the dialog visible.
        _state.value = _state.value.copy(pendingConfirmation = null)
        val bubble = findBubble(pending.bubbleId)
        if (bubble == null) return
        val def = actionRegistry.get(pending.actionId) ?: return
        // One-time opt-in: persist userPrefKey so future chips on
        // phone bubbles don't re-prompt.  Phase B's settings UI
        // will let the user revoke this.
        def.userPrefKey?.let { settings.saveActionPermission(it, true) }
        executeAndDispatch(def, bubble, parsedArgsFor(bubble), pending.bubbleId)
    }

    /**
     * Find a bubble by id across both the legacy `bubbles` list AND
     * the live-UI `cycles` map.  Used by [runAction] / [confirmAction]
     * / [submitActionArgs] to look up a bubble for an action chip tap.
     *
     * [2026-07-15 UI polish] Previous version only searched
     * `_state.value.bubbles`; under CycleManager (Phase B+ live UI)
     * a fresh cycle's bubble lives in
     * `_state.value.cycles[cycleId].bubble.value` and is NOT mirrored
     * into `bubbles` (which is a separate legacy-only queue).  The
     * result: tapping a chip on a just-recognized phone bubble would
     * log "bubble 不在历史中" and silently no-op.  Searching the
     * cycles map too is the minimal-risk fix — no data shape change,
     * the legacy path still resolves `bubbles` first, eval still goes
     * through `bubbles` because it doesn't use CycleManager.
     */
    private fun findBubble(id: String): Bubble? =
        _state.value.bubbles.firstOrNull { it.id == id }
            ?: _state.value.cycles.values.firstNotNullOfOrNull { it.bubble.value }

    /** [2026-07-15] Pre-compute the args map the body wants.
     *
     *  Walks every chip action on [bubble], runs each registered
     *  [ActionInputSpec.parser] against the bubble's text surface,
     *  and assembles a flat `Map<key, value>` for the body to read.
     *
     *  Why pre-compute (not lazy): the body needs all values up-front
     *  because the dispatch is synchronous; running parsers on every
     *  chip once at dispatch time keeps the per-tap cost bounded
     *  (3-4 regex passes against a ≤3 KB text surface).
     *
     *  Duplicate keys (rare — happens when two actions on the same
     *  bubble declare the same `key`) keep the first parser's value,
     *  matching the orchestrator's per-action precedence. */
    private fun parsedArgsFor(bubble: Bubble): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        bubble.actions.forEach { actionId ->
            val def = actionRegistry.get(actionId) ?: return@forEach
            def.requiredInputs.forEach { spec ->
                if (spec.key !in out) {
                    spec.parser(bubble)?.let { out[spec.key] = it }
                }
            }
        }
        return out
    }

    /** User dismissed the consent dialog (Cancel / back-press).
     *  No body invocation.  Clears [UiState.pendingConfirmation]. */
    fun cancelConfirmation() {
        val pending = _state.value.pendingConfirmation ?: return
        logDebug("ACTION", "cancel confirm ${pending.actionId}")
        _state.value = _state.value.copy(pendingConfirmation = null)
    }

    /**
     * User filled in the form parked by
     * [ActionOutcome.RequestArgs].  Re-runs the action body with the
     * collected values; the dispatch is identical to [runAction] so
     * we delegate to the same helper.
     *
     * If the body requests more args (e.g. a phone-number action
     * needs number + body), the new form replaces the current one
     * via [UiState.pendingAction].  Pure cascade.
     */
    fun submitActionArgs(args: Map<String, String>) {
        val pending = _state.value.pendingAction ?: return
        val bubble = findBubble(pending.bubbleId)
        if (bubble == null) {
            // Stale pending — bubble already evicted; just clear.
            logDebug("ACTION", "submit 但 bubble ${pending.bubbleId} 不在历史中")
            _state.value = _state.value.copy(pendingAction = null)
            return
        }
        val def = actionRegistry.get(pending.actionId)
        if (def == null) {
            logDebug("ACTION", "submit 但 action ${pending.actionId} 已下线")
            _state.value = _state.value.copy(pendingAction = null)
            return
        }
        logDebug(
            "ACTION",
            "submit args for ${def.id} keys=${args.keys.joinToString(",")}"
        )
        executeAndDispatch(def, bubble, args, pending.bubbleId)
    }

    /** User dismissed the action-arg form (back-press / cancel).
     *  Clears [UiState.pendingAction] without firing the body. */
    fun cancelActionArgs() {
        val pending = _state.value.pendingAction ?: return
        logDebug("ACTION", "cancel args for ${pending.actionId}")
        _state.value = _state.value.copy(pendingAction = null)
    }

    /**
     * Shared core for [runAction] + [submitActionArgs]: invoke the
     * body, catch into a Toast on throw, and dispatch the resulting
     * [ActionOutcome].  Always runs on [Dispatchers.Main] because
     * `startActivity` from a non-main thread crashes on Android.
     */
    private fun executeAndDispatch(
        def: ActionDef,
        bubble: Bubble,
        args: Map<String, String>,
        bubbleId: String,
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            val app = getApplication<Application>()
            val outcome = try {
                def.body(app, bubble, args)
            } catch (e: Throwable) {
                ActionOutcome.ShowUiFeedback("动作失败:${formatThrowable(e)}")
            }
            try {
                when (outcome) {
                    ActionOutcome.None -> { /* nothing to do */ }
                    is ActionOutcome.LaunchAndroidIntent -> {
                        // FLAG_ACTIVITY_NEW_TASK is required when
                        // starting from an Application context; the
                        // action body sets it, but we double-check
                        // here so a future body change doesn't
                        // silently drop it.
                        val intent = outcome.intent.apply {
                            if (flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                        // Action completed cleanly — drop any pending
                        // form so back-press doesn't reopen it.
                        if (_state.value.pendingAction != null) {
                            _state.value = _state.value.copy(pendingAction = null)
                        }
                        app.startActivity(intent)
                    }
                    is ActionOutcome.ShowUiFeedback -> {
                        if (_state.value.pendingAction != null) {
                            _state.value = _state.value.copy(pendingAction = null)
                        }
                        android.widget.Toast.makeText(
                            app,
                            outcome.message,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is ActionOutcome.RequestArgs -> {
                        // Replace any existing pending form with the
                        // freshly requested one (cascading args is
                        // supported: an action can ask for more
                        // after a partial fill).
                        val prompt = outcome.args.firstOrNull()?.helpText
                            ?: "需要补充参数"
                        _state.value = _state.value.copy(
                            pendingAction = PendingAction(
                                actionId = outcome.resumeActionId,
                                bubbleId = bubbleId,
                                args = outcome.args,
                                prompt = prompt,
                            )
                        )
                        logDebug(
                            "ACTION",
                            "request args via=${outcome.resumeActionId} " +
                                "${outcome.args.size} fields"
                        )
                    }
                }
            } catch (e: Throwable) {
                // ActivityNotFoundException for actions like
                // open_in_maps when the user has no maps app
                // installed; fall through to a Toast.
                android.widget.Toast.makeText(
                    app,
                    "无法执行:${e.message?.take(80) ?: "未知错误"}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** User explicitly tapped "重新扫描" — full reset including bubble history. */
    fun restartScanning() {
        latestFrame = null
        // [2026-07-15] Clear the per-cycle error dedup set so future
        //  cycles with new ids can re-report ERRORED.  Bounded by
        //  the concurrent cycle count, so the set is small; clear
        //  here rather than per-insert to keep the hot path O(1).
        reportedCycleErrors.clear()
        // [2026-07-15 P0 fix] Cancel every in-flight cycle so we
        //  don't keep billing for LLM calls whose results would
        //  never reach the UI.  Previous version cleared the
        //  bubbles list but left CycleManager.allJobs untouched —
        //  cycles continued running in the background until their
        //  90s `llmTimeoutMs` expired.  Also clears the pending-
        //  input bookkeeping so a subsequent input submit doesn't
        //  try to resume a now-defunct cycle.
        cycleManager.cancelAll("user restart")
        pendingCycleId = null
        pendingFullRes = null
        // [2026-07-15 P0 fix] Explicitly clear `analyzing`.
        //  cancelAll nukes the cycles map synchronously, but
        //  `analyzing` is a manually-managed UiState field —
        //  without an explicit flip here the spinner would
        //  stay lit until the next state mutation fires (e.g.
        //  an unrelated bubble completion).  Also call
        //  syncCycleCounters() so `activeCycleCount` drops to
        //  0 and the shutter counter immediately reads 8 — the
        //  post-fix behavior the user expects after a
        //  "重新扫描" (the button being ready to use again is
        //  the visible signal the reset succeeded).
        _state.value = _state.value.copy(
            phase = Phase.SCANNING,
            bubbles = emptyList(),
            selectedBubble = null,
            scene = "",
            error = null,
            analyzing = false,
        )
        syncCycleCounters()
    }

    /** [2026-07-15 UI polish] Clear the surfaced error banner without
     *  restarting scanning.  Used by MainActivity's `ErrorBanner`
     *  dismiss button so the user can clear a transient failure
     *  (529 storm, network blip) without losing their in-flight
     *  cycle list.  `restartScanning()` is the nuclear option; this
     *  is the polite "I saw it, hide it now" tap. */
    fun clearError() {
        if (_state.value.error == null) return
        _state.value = _state.value.copy(error = null)
    }

    /** [2026-07-15 P0 fix] Surface an error message via the
     *  in-app ErrorBanner.  Companion to [clearError]; used by
     *  `MainActivity.CameraPreview`'s CameraX bind failure path
     *  (previously a silent empty catch swallowed all bind
     *  exceptions — see MainActivity.kt:566-567).  The banner
     *  shows the message until the user dismisses it; the
     *  camera preview stays dead until the user restarts the
     *  app (since a half-bound CameraX provider can't be
     *  recovered at runtime). */
    fun setError(message: String) {
        if (_state.value.error == message) return
        _state.value = _state.value.copy(error = message)
    }

    fun openSettings() {
        _state.value = _state.value.copy(phase = Phase.SETTINGS)
    }

    fun closeSettings() {
        restartScanning()
    }

    fun saveConfig(newConfig: LlmConfig) {
        val cfg = newConfig.copy(
            baseUrl = newConfig.baseUrl.ifBlank { LlmConfig.DEFAULT_BASE_URL },
            authToken = newConfig.authToken.ifBlank { bakedDefaultToken },
            model = newConfig.model.ifBlank { LlmConfig.DEFAULT_MODEL }
        )
        settings.save(cfg)
        client.config = cfg
        closeSettings()
    }

    fun resetConfigToDefault() {
        settings.reset()
        client.config = settings.load()
    }

    // ───── [2026-07-13] Phase B: PII action permission toggles ─────

    /**
     * Snapshot of the current userPrefKey grants for every
     * PII-tagged action.  Maps `ActionDef.userPrefKey` →
     * `loadActionPermission(key)`.  Cheap: 4 SharedPreferences
     * booleans, lives in memory only long enough to render the
     * Settings screen.
     *
     *  Kept as a function instead of a property so the Settings UI
     *  re-reads after each toggle without needing a separate
     *  observation Flow (the screen re-renders on `MutableStateFlow`
     *  updates already, so this is consistent with the rest of the
     *  Settings layer's "you call me again, I re-read" pattern).
     */
    /** [2026-07-15 UI polish] Snapshot of the current userPrefKey
     *  grants for every PII-tagged action, paired with the
     *  [ActionDef] so the Settings screen can render the action's
     *  user-facing label (not the internal `userPrefKey` string)
     *  and the consent-gate explanation.
     *
     *  Previously returned `Map<String, Boolean>` keyed by
     *  userPrefKey.  The Settings screen then had to reverse-look-up
     *  each key in the actionRegistry to find the action's
     *  human label, which it never actually did — the screen
     *  just rendered the raw `userPrefKey` id (e.g.
     *  "action_dial_number_enabled") as the user-facing text.
     *  Bundling the [ActionDef] here is the same read cost
     *  (4 SharedPreferences booleans + a single registry list())
     *  and lets the screen render a sensible label.
     *
     *  Kept as a function (not a property) so the Settings UI
     *  re-reads after each toggle without needing a separate
     *  observation Flow.
     */
    data class PiiPermission(val key: String, val action: ActionDef, val enabled: Boolean)

    fun piiActionPermissions(): List<PiiPermission> =
        actionRegistry.list()
            .filter { it.requiresConfirmation && it.userPrefKey != null }
            .map { PiiPermission(it.userPrefKey!!, it, settings.loadActionPermission(it.userPrefKey)) }

    /** Flip one PII action's permission.  Both directions write
     *  through to [SettingsStore]; the read toggle in
     *  [piiActionPermissions] reflects the change on the next
     *  re-read.  Never throws on unknown keys (defensive — the
     *  Settings UI could pass an id we subsequently removed). */
    fun setPiiActionPermission(key: String, enabled: Boolean) {
        val def = actionRegistry.list().firstOrNull { it.userPrefKey == key }
        if (def == null) {
            logDebug("PIIGATE", "set permission 未知 key $key")
            return
        }
        settings.saveActionPermission(key, enabled)
        logDebug("PIIGATE", "${def.id} -> ${if (enabled) "ON" else "OFF"}")
    }
}

// formatThrowable moved to :shared/FormatThrowable.kt