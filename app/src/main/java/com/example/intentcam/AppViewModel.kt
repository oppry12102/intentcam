package com.example.intentcam

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val client = LlmClient(settings.load())

    /** Tool registry used by [toolUseLoop].  Built once at construction
     *  and reused for every recognition cycle.  Adding tools requires
     *  re-registering here. */
    private val toolRegistry = ToolRegistry().also {
        it.registerDefaultTools()
    }

    private val toolUseLoop = ToolUseLoop(
        client = client,
        registry = toolRegistry,
        log = ::logDebug,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Restore the persisted debug-overlay preference.  Defer the state
        // write to viewModelScope so it never happens during the first
        // composition (which is in flight when this ViewModel is first
        // accessed by `viewModel.state.collectAsState()`).
        viewModelScope.launch {
            _state.value = _state.value.copy(debugEnabled = settings.loadDebugEnabled())
        }
    }

    val config: LlmConfig get() = client.config

    /** Bus flag for "a recognition cycle is currently running".  Read by
     *  the camera analyzer's `isBusy()` callback and the shutter button. */
    private val analyzing = AtomicBoolean(false)

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
        if (_state.value.phase != Phase.SCANNING) return true
        // When the user is looking at a bubble's detail view, the camera
        // pipeline must stay quiet so the displayed image doesn't go stale.
        if (_state.value.selectedBubble != null) return true
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
     * Newlines are stripped and the message is capped at 160 chars so a
     * rogue caller can't blow past the 3-line render cap.
     */
    private fun logDebug(tag: String, message: String) {
        if (!_state.value.debugEnabled) return
        val safe = message.replace('\n', ' ').take(160)
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
        if (_state.value.selectedBubble != null) return
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
        _state.value = _state.value.copy(analyzing = true, error = null)
        viewModelScope.launch {
            try {
                // Wait for the analyzer to deliver the next frame, up to
                // 500 ms.  The CAS in [tryArmCapture] ensures only one
                // frame is captured regardless of how many are queued.
                val deadline = SystemClock.elapsedRealtime() + 500L
                while (latestFrame == null &&
                    _state.value.phase == Phase.SCANNING &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    delay(20L)
                }
                captureArmed.set(false)
                val frame = latestFrame
                latestFrame = null
                if (frame == null) {
                    logDebug("CAP", "500ms 内没拿到帧")
                    return@launch
                }
                logDebug(
                    "CAP",
                    "用户触发 thumb=${frame.thumbnail.size / 1024}KB " +
                        "full=${frame.fullRes.size / 1024}KB"
                )
                runRecognitionCycle(frame)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logDebug(
                    "FATAL",
                    "${e.javaClass.simpleName}: ${e.message?.take(160) ?: "无消息"}"
                )
                _state.value = _state.value.copy(error = e.message)
            } finally {
                analyzing.set(false)
                _state.value = _state.value.copy(analyzing = false)
            }
        }
    }

    /**
     * One-shot recognition cycle: delegates to [ToolUseLoop], which
     * drives a multi-round tool-use conversation with the model and
     * returns a final bubble (or a user-input placeholder).
     */
    private suspend fun runRecognitionCycle(frame: CapturedFrame) {
        _state.value = _state.value.copy(analyzing = true, error = null)
        runToolUseCycle(frame, userText = "")

        // Hint the GC to release the OkHttp / Bitmap buffers we just freed
        // up.  Best-effort: on a low-RAM device this is the difference
        // between "process reclaimed, ready for next tap" and "LMK kills us
        // for hanging on to a dead ByteArray for another 5 minutes".
        System.gc()
    }

    /** Multi-round tool-use path.  Emits a bubble, sets a placeholder,
     *  or surfaces an error in [UiState]. */
    private suspend fun runToolUseCycle(frame: CapturedFrame, userText: String) {
        logDebug(
            "TOOL",
            "→ runCycle (thumb=${frame.thumbnail.size / 1024}KB " +
                "full=${frame.fullRes.size / 1024}KB userText='${userText.take(40)}')"
        )
        val outcome = toolUseLoop.runCycle(frame.thumbnail, frame.fullRes, userText)
        when (outcome) {
            is ToolUseLoop.Outcome.Bubble -> {
                val merged = (_state.value.bubbles + outcome.bubble).takeLast(UiState.BUBBLE_MAX)
                _state.value = _state.value.copy(
                    scene = outcome.bubble.detail.take(80),
                    bubbles = merged,
                    analyzing = false,
                )
                logDebug(
                    "FINAL",
                    "type=${outcome.bubble.type} intent=${outcome.bubble.title} " +
                        "via=${outcome.bubble.toolName ?: "?"}"
                )
            }
            is ToolUseLoop.Outcome.PendingUserInput -> {
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
                logDebug("FINAL_ERR", outcome.message.take(160))
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
        )
        viewModelScope.launch {
            // Remove the placeholder so the new result doesn't show a
            // duplicate.  Use the trailing bubble list to find the
            // index.
            val withoutPlaceholder = _state.value.bubbles.filterNot { it.id == placeholder.id }
            _state.value = _state.value.copy(bubbles = withoutPlaceholder)
            // Reconstruct a CapturedFrame for the resume path.  The
            // thumbnail is the same image bytes; the fullRes is also
            // the same since the placeholder holds the original photo.
            // zoom_in still works for the resumed cycle.
            val frame = CapturedFrame(thumbnail = jpeg, fullRes = jpeg)
            runToolUseCycle(frame, userText = text)
        }
    }

    /** User cancelled the [UiState.userInputRequest].  Drops the
     *  placeholder bubble and returns to scanning. */
    fun cancelUserInput() {
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
     * User tapped a bubble.  Show its detail (full image + title + detail).
     * The camera pipeline stays quiet until [clearBubbleSelection] runs.
     */
    fun selectBubble(bubble: Bubble) {
        if (_state.value.selectedBubble?.id == bubble.id) return
        _state.value = _state.value.copy(selectedBubble = bubble)
    }

    /** User dismissed the detail view; rearm the capture pipeline. */
    fun clearBubbleSelection() {
        if (_state.value.selectedBubble == null) return
        _state.value = _state.value.copy(selectedBubble = null)
    }

    /**
     * User tapped an action chip in the detail view.  Runs a
     * Reserved for action_chips.  Deferred — emit_bubble in the
     * new architecture doesn't carry chips, so runChip has no
     * triggers in the UI.  Kept as a stub for future re-enablement.
     */
    fun runChip(jpeg: ByteArray, @Suppress("UNUSED_PARAMETER") chip: Any) {
        // no-op until action_chips are re-enabled
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