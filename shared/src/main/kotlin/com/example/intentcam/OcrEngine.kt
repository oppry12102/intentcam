package com.example.intentcam

/**
 * Strategy holder for on-device OCR.  Lives in `:shared` so the
 * `read_text` tool body can call it without an Android dependency.
 *
 * The implementation strategy is set once at startup:
 *   - Android app: `MainActivity.onCreate` installs an ML Kit-backed
 *     recognizer that decodes the JPEG bytes to a Bitmap internally.
 *   - JVM eval:    no implementation installed — the eval returns
 *     "[OCR unavailable]" from `read_text` since the eval machine
 *     has no on-device OCR.  (The system prompt steers the model to
 *     avoid `read_text` by default anyway.)
 *
 * If `impl` is null, `recognize` returns "" — fail-closed.
 */
object OcrEngine {
    /** One OCR backend.  Returns the verbatim text (no newline at end). */
    fun interface Impl {
        suspend fun recognize(jpegBytes: ByteArray): String
    }

    @JvmStatic
    var impl: Impl? = null

    suspend fun recognize(jpegBytes: ByteArray): String =
        impl?.recognize(jpegBytes) ?: ""
}