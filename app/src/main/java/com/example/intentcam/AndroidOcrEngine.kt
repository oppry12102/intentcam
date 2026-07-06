package com.example.intentcam

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android-side [OcrEngine.Impl] backed by ML Kit's bundled Chinese +
 * Latin text recognition model.  Decodes the JPEG bytes to a
 * Bitmap internally, runs the recognizer, and returns the
 * concatenated text.
 *
 * ML Kit requires an Application context to construct its
 * recognizer; we cache it on the first call (thread-safe via
 * synchronized init).
 *
 * Call [installAndroidOcr] once at app startup (MainActivity.onCreate)
 * after `installAndroidImageOps`.  The recognizer is created lazily
 * on the first [OcrEngine.recognize] call so the on-device OCR
 * library only loads when read_text is actually invoked (cold start
 * stays fast).
 */
fun installAndroidOcr(app: android.app.Application) {
    OcrEngine.impl = AndroidOcrEngine(app)
}

private class AndroidOcrEngine(private val app: android.app.Application) : OcrEngine.Impl {
    @Volatile private var recognizer: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        recognizer?.let { return it }
        return synchronized(this) {
            recognizer ?: TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            ).also { recognizer = it }
        }
    }

    override suspend fun recognize(jpegBytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return ""
        return try {
            suspendCancellableCoroutine<String> { cont ->
                val image = InputImage.fromBitmap(bitmap, 0)
                getRecognizer().process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            bitmap.recycle()
        }
    }
}