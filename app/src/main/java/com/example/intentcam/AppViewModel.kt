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

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val client = LlmClient(settings.load())

    /** [2026-07-10] Intent registry — the system prompt's type list
     *  and the `emit_bubble` tool description both read from this.
     *  Built first so the tool registry below can consult it. */
    private val intentRegistry = IntentRegistry().also { registerDefaultIntents(it) }

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
     *      explicit opt-in.  Universal actions (`view_details`,
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

    /** Bus flag for "a recognition cycle is currently running".  Read by
     *  the camera analyzer's `isBusy()` callback and the shutter button. */
    private val analyzing = AtomicBoolean(false)

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
    // Monotonic counter for unique LazyColumn keys.  timestampMs has
    // millisecond resolution and multiple logDebug calls in the same ms
    // (very common in a tight tool-use loop) would crash the panel with
    // "Key X was already used".
    private val debugLogSeq = AtomicLong(0)

    fun isBusy(): Boolean {
        if (analyzing.get()) return true
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

    /**
     * Public surface for the camera analyzer to surface exceptions into the
     * in-app debug overlay.  Lives next to [logDebug] (private) so the only
     * way external callers reach the debug log is through named wrappers
     * that hard-code the tag.
     */
    fun logAnalyzerError(message: String) {
        logDebug("ANALYZER", message)
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
     * next frame it produces is the one we use), waits up to 500 ms for
     * the frame to arrive, then runs one recognition cycle.  No-op if
     * another cycle is already in flight (button stays disabled).
     */
    fun captureLatestFrame() {
        if (!analyzing.compareAndSet(false, true)) return
        captureArmed.set(true)
        enterAnalyzing()
        viewModelScope.launch {
            try {
                // Wait for the analyzer to deliver the next frame.
                // [2026-07-12] raised 500ms→3000ms.  Two reasons:
                //  1. Cold start: CameraX bindToLifecycle takes
                //     200-800ms on first launch; `analyze()` doesn't
                //     fire at all until binding completes, so the
                //     first shutter tap after `onPermissionsGranted`
                //     would always hit 500ms and fail.  Second tap
                //     worked because binding finished in between.
                //  2. Larger encodes: ResolutionSelector now targets
                //     sensor max (~4032×3024).  JPEG q95 on the
                //     full-res bitmap takes ~200-400ms on its own,
                //     so even with the camera warm we burn ~300ms
                //     before the first analyze() returns.  500ms
                //     was tight for 640×480; it's racy for sensor max.
                // 3s is enough for both: covers cold bind + encode
                // overhead, with no impact on the warm path (typical
                // wait is 50-80ms so we exit the loop early).
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
                runRecognitionCycle(frame)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logDebug("FATAL", formatThrowable(e))
                _state.value = _state.value.copy(error = e.message)
            } finally {
                analyzing.set(false)
                _state.value = _state.value.copy(analyzing = false)
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
        val placeholder = _state.value.bubbles.lastOrNull { it.needsUserInput }
        val jpeg = placeholder?.imageBytes
        if (jpeg == null) {
            _state.value = _state.value.copy(userInputRequest = null)
            return
        }
        _state.value = _state.value.copy(
            analyzing = true,
            userInputRequest = null,
            error = null,
        )
        viewModelScope.launch {
            // Remove the placeholder so the new result doesn't show a
            // duplicate.  Use the trailing bubble list to find the
            // index.
            val withoutPlaceholder = _state.value.bubbles.filterNot { it.id == placeholder.id }
            _state.value = _state.value.copy(bubbles = withoutPlaceholder)
            // Reconstruct a CapturedFrame for the resume path.  `jpeg` is
            // the placeholder's display thumbnail; the full-res crop source
            // comes from the stashed pendingFullRes (falls back to the
            // thumbnail if — unexpectedly — it wasn't set, so resume still
            // works, just cropping from the lower-res image).
            val fullRes = pendingFullRes ?: jpeg
            pendingFullRes = null
            val frame = CapturedFrame(thumbnail = jpeg, fullRes = fullRes)
            runToolUseCycle(frame, userText = text)
        }
    }

    /** User cancelled the [UiState.userInputRequest].  Drops the
     *  placeholder bubble and returns to scanning. */
    fun cancelUserInput() {
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
        val bubble = _state.value.bubbles.firstOrNull { it.id == bubbleId }
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
        executeAndDispatch(def, bubble, emptyMap(), bubbleId)
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
        val bubble = _state.value.bubbles.firstOrNull { it.id == pending.bubbleId }
        if (bubble == null) return
        val def = actionRegistry.get(pending.actionId) ?: return
        // One-time opt-in: persist userPrefKey so future chips on
        // phone bubbles don't re-prompt.  Phase B's settings UI
        // will let the user revoke this.
        def.userPrefKey?.let { settings.saveActionPermission(it, true) }
        executeAndDispatch(def, bubble, emptyMap(), pending.bubbleId)
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
        val bubble = _state.value.bubbles.firstOrNull { it.id == pending.bubbleId }
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
        analyzing.set(false)
        _state.value = _state.value.copy(
            phase = Phase.SCANNING,
            bubbles = emptyList(),
            selectedBubble = null,
            scene = "",
            error = null,
        )
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
            authToken = newConfig.authToken.ifBlank { LlmConfig.DEFAULT_TOKEN },
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
}

// formatThrowable moved to :shared/FormatThrowable.kt