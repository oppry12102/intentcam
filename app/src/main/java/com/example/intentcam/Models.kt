package com.example.intentcam

/** One candidate user intent returned by the model. */
data class IntentItem(
    val id: String,
    val type: String,      // "info" | "location" | "solve" | other
    val title: String,     // short label shown on the bubble
    val detail: String,    // one-line explanation
    val confidence: Float  // 0.0 .. 1.0
)

/**
 * JPEGs produced from one stable camera frame.  The camera analyzer emits both
 * at the same time because the analyze call wants the smallest possible payload
 * (server-side image processing scales with pixel area), while the answer call
 * still benefits from a more detailed JPEG for tasks like reading small text.
 *
 * [width] / [height] are the pixel dimensions of the encoded JPEGs (both
 * variants share the same aspect ratio).  The UI uses these to project the
 * OCR overlay onto the live preview or the frozen still.
 */
data class FrameJpegs(
    /** Small JPEG for the analyze request. ~30 KB at 512px / q70. */
    val analyze: ByteArray,
    /** Larger JPEG for the answer request. ~70 KB at 768px / q75. */
    val answer: ByteArray,
    val width: Int,
    val height: Int,
)

/** Result of one intent-analysis round. */
data class AnalysisResult(
    val scene: String,
    val intents: List<IntentItem>,
    /** CoT-grounded description of what the model actually saw in the image.
     *  Empty for legacy callers / older rounds that didn't emit it. */
    val observation: String = ""
)

/**
 * Snapshot of what the device-side OCR found.  Fed into model prompts as a
 * ground-truth hint; consumers must treat it as fallible.
 *
 * [imageWidth] / [imageHeight] are the dimensions of the **original JPEG**
 * (not the engine's internally-downsampled bitmap).  All [RecognizedLine]
 * coordinates are in that coordinate space, so the UI doesn't need to know
 * the engine's internal downsampling factor.
 */
data class OcrResult(
    val lines: List<RecognizedLine>,
    /** Full concatenated text from the Latin recognizer (for telemetry). */
    val latinText: String,
    /** Full concatenated text from the Chinese recognizer. */
    val chineseText: String,
    val imageWidth: Int,
    val imageHeight: Int
) {
    val text: String get() = lines.joinToString("\n") { it.text }

    fun isBlank(): Boolean = lines.isEmpty()

    companion object {
        val EMPTY = OcrResult(emptyList(), "", "", 0, 0)
    }
}

/** One text line detected by on-device OCR.  Coordinates are in original JPEG space. */
data class RecognizedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float
)

/** Snapshot of what the device-side object detector found. */
data class ObjectResult(val items: List<ObjectItem>) {
    fun isBlank(): Boolean = items.isEmpty()
    fun labelsForPrompt(): String =
        items.take(8).joinToString(", ") { "${it.label}(${(it.confidence * 100).toInt()}%)" }

    companion object {
        val EMPTY = ObjectResult(emptyList())
    }
}

/** One object detected by the on-device detector (coordinates in original JPEG space). */
data class ObjectItem(
    val label: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

enum class Phase {
    NEED_PERMISSION,  // camera/location not granted yet
    SCANNING,         // continuously analysing frames, showing intent bubbles
    ANSWERING,        // a specific intent selected, waiting for the answer
    ANSWER,           // answer is shown
    SETTINGS          // settings screen
}

/** Whole-screen UI state exposed by [AppViewModel]. */
data class UiState(
    val phase: Phase = Phase.NEED_PERMISSION,
    val scene: String = "",
    val intents: List<IntentItem> = emptyList(),
    val analyzing: Boolean = false,       // a network request is in flight
    val partialScene: String? = null,    // streamed scene text, replaced as final when done
    val selected: IntentItem? = null,
    val answer: String = "",
    val error: String? = null,
    val location: String? = null,
    val roundCount: Int = 0,
    /** Elapsed-realtime ms when the latest dramatic scene change was detected.
     *  0L = no recent scene change. */
    val lastSceneChangeMs: Long = 0L,
    /** Bounding boxes of the latest OCR pass, in original-JPEG coord space.
     *  Empty until the first analysis cycle completes.  Currently unused by
     *  the UI (the live camera preview doesn't share coordinates with the
     *  captured JPEG) but kept for telemetry / future use. */
    val ocrOverlay: List<RecognizedLine> = emptyList(),
    /** Monotonic counter bumped on each fresh OCR pass. */
    val ocrEpoch: Int = 0,
)

/** User-editable model configuration. */
data class LlmConfig(
    val baseUrl: String,
    val authToken: String,
    val model: String
) {
    companion object {
        // Defaults requested by the product spec (Minimax Anthropic-compatible endpoint).
        //
        // The auth token is intentionally NOT shipped in source — populate
        // [DEFAULT_TOKEN] locally if you need a built-in default (e.g. for a
        // self-hosted relay), or enter it once in the Settings screen and let
        // it persist to SharedPreferences.  Users pulling this repo from a
        // public registry will see the empty placeholder and have to set their
        // own token.
        const val DEFAULT_BASE_URL = "https://api.minimaxi.com/anthropic"
        const val DEFAULT_TOKEN = ""
        const val DEFAULT_MODEL = "MiniMax-M3"
    }
}
