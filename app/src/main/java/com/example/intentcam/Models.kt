package com.example.intentcam

/**
 * One intent bubble shown to the user.  Carries the captured JPEG so
 * the bubble can show a thumbnail and the detail view can show the
 * full picture without re-fetching from anywhere.
 *
 * [imageBytes] is the JPEG bytes returned by the camera at capture
 * time.  For a 384 px q60 capture that's ~10–15 kB; with a 4-bubble
 * cap we hold at most ~60 kB of image data in memory, which is fine.
 *
 * [toolName] names the tool that produced this bubble (e.g.
 * "identify_product").  Surfaced as "via ${toolName}" in the UI so the
 * user can tell which path the model took.  Null for bubbles from the
 * legacy one-shot path.
 *
 * [needsUserInput] is true for placeholder bubbles parked while the
 * orchestrator waits for the user to type a follow-up (e.g. the
 * destination of a navigate_to_block call).  The UI shows an input
 * dialog and submits the text back to the orchestrator.
 *
 * [chips] are follow-up actions the LLM suggested via emit_bubble.
 * Each chip names a tool + saved input; tapping one in the detail view
 * triggers a new recognition cycle with that tool called directly.
 */
data class Bubble(
    val id: String,
    val type: String,         // "info" | "location" | "solve" | other
    val title: String,
    val detail: String,
    val confidence: Float,    // 0.0 .. 1.0
    val imageBytes: ByteArray,
    val createdAtMs: Long,
    val toolName: String? = null,
    val needsUserInput: Boolean = false,
    val chips: List<ActionChip> = emptyList(),
)

/** A follow-up action the model suggested.  Surfaced as a tappable
 *  chip in the bubble's detail view; tapping it tells the orchestrator
 *  to call [toolName] with [toolInput] as if the model had picked it. */
data class ActionChip(
    val label: String,
    val toolName: String,
    /** Raw JSON string from the model's emit_bubble input.  Kept as a
     *  string so we can re-parse it without an intermediate typed
     *  object — the orchestrator only ever round-trips it. */
    val toolInputJson: String,
)

/** Whole-screen UI state exposed by [AppViewModel]. */
data class UiState(
    val phase: Phase = Phase.NEED_PERMISSION,
    val scene: String = "",
    /** A network request is in flight (LLM call). */
    val analyzing: Boolean = false,
    /** FIFO queue of bubbles; oldest evicted when length exceeds [BUBBLE_MAX]. */
    val bubbles: List<Bubble> = emptyList(),
    val selectedBubble: Bubble? = null,
    val error: String? = null,
    /** When true, the recognition process streams onto a translucent
     *  overlay above the camera preview.  Persisted in [SettingsStore]. */
    val debugEnabled: Boolean = true,
    /** Newest-last ring of [DebugLogEntry] entries.  Capped at [DEBUG_LOG_MAX];
     *  older entries are dropped when new ones arrive. */
    val debugLogs: List<DebugLogEntry> = emptyList(),
    /** Non-null while a tool needs free-form user input to continue
     *  (e.g. navigate_to_block's destination).  The UI shows a dialog;
     *  AppViewModel.submitUserInput(text) feeds it back into the
     *  orchestrator. */
    val userInputRequest: UserInputRequest? = null,
) {
    companion object {
        /** Hard cap on bubble count.  When a new bubble arrives and we're
         *  already at this count, the oldest is dropped. */
        const val BUBBLE_MAX = 4
        /** Max entries kept in [debugLogs] before the oldest is evicted. */
        const val DEBUG_LOG_MAX = 40
    }
}

/** Asked by a tool body that needs a free-form follow-up from the
 *  user before it can finish.  The UI surfaces this as a dialog; the
 *  orchestrator resumes once [AppViewModel.submitUserInput] (or
 *  [AppViewModel.cancelUserInput]) is called. */
data class UserInputRequest(
    val toolName: String,
    val prompt: String,
)

/** One entry in the recognition debug log.  Surfaced on screen as a
 *  scrolling overlay while [UiState.debugEnabled] is true. */
data class DebugLogEntry(
    /** Wall-clock millis when the entry was logged.  Display only. */
    val timestampMs: Long,
    /** Process-monotonic sequence number, unique per AppViewModel
     *  instance.  Used as the LazyColumn key — `timestampMs` collides
     *  when two logDebug calls land in the same millisecond, which
     *  crashes the LazyColumn with "Key already used".  The seq is
     *  cheap to allocate (AtomicLong.incrementAndGet) and monotonic. */
    val seq: Long,
    /** Short tag for at-a-glance filtering ("OCR", "R1", "TOOL", "ERR"). */
    val tag: String,
    /** Single-line human-readable message.  Newlines are stripped by the
     *  emitter; rendering truncates at 3 lines regardless. */
    val message: String,
)

enum class Phase {
    NEED_PERMISSION,  // camera permission not granted yet
    SCANNING,         // live preview + shutter button armed
    SHOWING_DETAIL,   // user tapped a bubble; full image + description visible
    SETTINGS          // settings screen
}

/** User-editable model configuration.  The only knobs are the endpoint
 *  URL, the bearer token, and the model id; everything else
 *  (temperature, max_tokens, tool-use protocol) is hard-coded for
 *  determinism. */
data class LlmConfig(
    val baseUrl: String,
    val authToken: String,
    val model: String,
) {
    companion object {
        // Defaults baked into the debug APK so the app starts working out
        // of the box.  The token is a placeholder — replace at runtime via
        // the Settings screen or pass via env (ANTHROPIC_AUTH_TOKEN) when
        // building a release.
        const val DEFAULT_BASE_URL = "https://api.minimaxi.com/anthropic"
        const val DEFAULT_TOKEN = "REPLACE_AT_RUNTIME"
        const val DEFAULT_MODEL = "MiniMax-M3"
    }
}