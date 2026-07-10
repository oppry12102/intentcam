package com.example.intentcam

/**
 * One row in the detail-view table.  Each detail is something the
 * LLM extracted from the image (text, number, object, color, etc.)
 * and the table renders them in (kind, label, value) columns.
 *
 * [bbox] is the optional 4-corner normalized [0,1] coordinates of
 * the row's text region in the source image (TL → TR → BR → BL).
 * Populated by `emit_bubble` when the model copied the bbox from
 * the round-1 OCR hint so the detail view can highlight the row's
 * position in the photo.  Null for rows whose source the LLM
 * described from memory (no positional anchor).  Backward
 * compatible — old bubbles with `bbox = null` render unchanged.
 */
data class Detail(
    val kind: String,    // "text" | "number" | "object" | "color" | "shape" | ...
    val label: String,   // human-friendly name, e.g. "Brand name"
    val value: String,   // the extracted content
    val bbox: List<OcrPoint>? = null,  // 4-corner normalized [0,1]; null = no anchor
)

/**
 * One intent bubble shown to the user.  Carries the captured JPEG so
 * the bubble can show a thumbnail and the detail view can show the
 * full picture without re-fetching from anywhere.
 *
 * Two-stage recognition result:
 *  - [content]  : 1-2 sentence overall image description
 *  - [title]    : the user's inferred intent (动宾短语, ≤30 chars)
 *  - [type]     : info / location / solve
 *  - [details]  : structured items for the detail-view table; can
 *                 be empty if the LLM chose not to extract any
 *  - [intentFocus] : which area of the image informs the intent
 *  - [confidence] : 0.0..1.0
 *
 * [imageBytes] is the **thumbnail** JPEG (display-only, ~3200 px).
 * The full-res original is NOT held here — for the needs-input resume
 * path the app keeps it in a transient AppViewModel field, not in the
 * bubble history, so a 4-bubble history stays small.  [needsUserInput]
 * is true for placeholder bubbles parked while the orchestrator waits
 * for the user to type a follow-up.
 */
data class Bubble(
    val id: String,
    val type: String,             // "info" | "location" | "solve"
    val title: String,             // intent (动宾短语)
    val detail: String,            // content description (was 'detail' in tool)
    val confidence: Float,
    val imageBytes: ByteArray,
    val createdAtMs: Long,
    val toolName: String? = null,
    val needsUserInput: Boolean = false,
    val intentFocus: String? = null,
    val details: List<Detail> = emptyList(),
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