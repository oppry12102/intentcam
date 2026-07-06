package com.example.intentcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

// CapturedFrame lives in :shared so the eval can build frames from
// raw JPEGs the same way FrameAnalyzer does on-device.

// One captured frame, in three encodings (see [CapturedFrame]).

// cropJpegRegion used to live here but moved to :shared/CropStrategy.kt
// so the JVM eval can call the same top-level function.  The Android
// implementation is wired in via `installAndroidImageOps()` at app
// startup.

/**
 * CameraX analyzer that emits a captured frame at a fixed cadence.
 * Each emission carries two encodings (see [CapturedFrame]).
 *
 * Cadence: gated by the caller's [isArmed] callback — the analyzer
 * only encodes when a capture is in flight, so the common case
 * (user just looking at the screen) costs ~nothing.
 *
 * Busy handling: same as the consumer side — drop frames when nobody
 * is listening.
 */
class FrameAnalyzer(
    private val isArmed: () -> Boolean,
    private val onFrame: (CapturedFrame) -> Unit,
    /**
     * Invoked from the analyzer thread when [analyze] swallows an exception
     * (e.g. `OutOfMemoryError` on bitmap allocation under heavy heap pressure
     * after several recognitions).  Defaulted to no-op so existing call sites
     * keep working.  Wired to `viewModel.logDebug("ANALYZER", ...)` in the UI
     * so the user sees the actual cause in the in-app debug overlay, not just
     * the coroutine's "500ms 内没拿到帧" timeout message.
     */
    private val onError: (String) -> Unit = {},
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            // Critical: when the user is just looking at the screen (no
            // capture in flight) we skip the encoder entirely.  ImageProxy
            // is closed unconditionally in `finally`; no bitmap allocation,
            // no JPEG compression, no memory pressure.
            if (!isArmed()) return

            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return

            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            buffer.rewind()
            val frame = bufferToFrame(
                buffer, width, height, rowStride, pixelStride,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )
            if (frame != null) onFrame(frame)
        } catch (e: Throwable) {
            // Both logcat (stack trace for `adb logcat`) and the in-app
            // callback (visible in the debug overlay) — the latter is what
            // the user actually sees.  Pass the full message + cause
            // chain; the overlay now auto-wraps so we no longer truncate.
            android.util.Log.w("IntentCam", "analyze() swallowed", e)
            onError(formatThrowable(e))
        } finally {
            image.close()
        }
    }

    /**
     * Decode the camera buffer into a [Bitmap], rotate to upright,
     * and emit TWO JPEGs:
     *  - thumbnail: scaled to [MAX_DIM], quality [QUALITY] — for LLM.
     *  - fullRes:   at native resolution, quality [FULL_QUALITY] —
     *    kept in memory so zoom_in can crop from it.
     */
    private fun bufferToFrame(
        buffer: java.nio.ByteBuffer,
        width: Int, height: Int,
        rowStride: Int, pixelStride: Int,
        rotationDegrees: Int,
    ): CapturedFrame? {
        val rowPaddingPx = (rowStride - pixelStride * width) / pixelStride
        val paddedWidth = width + rowPaddingPx

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)

        var work: Bitmap = if (rowPaddingPx > 0) {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        } else {
            padded
        }

        if (rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            work = Bitmap.createBitmap(work, 0, 0, work.width, work.height, m, true).also {
                work.recycle()
            }
        }

        // Full-resolution JPEG first (the more expensive encode), then
        // the downscaled thumbnail.  We can keep the full-res bitmap
        // referenced for any zoom_in calls that follow; the thumbnail
        // is the small payload that ships to the LLM.
        val fullRes = encodeBitmap(work, quality = FULL_QUALITY, maxDim = MAX_FULL_DIM)
        val thumbnail = encodeBitmap(work, quality = QUALITY, maxDim = MAX_DIM)
        // Four quadrant crops at QUADRANT_MAX_DIM — sent in round 1
        // alongside the thumbnail so the LLM sees high-detail in
        // every corner from the start, instead of having to round-trip
        // 1-2 zoom_ins before it can read small text.  Each crop is
        // 50% × 50% of the upright full-res image.
        val quadrants = listOf(
            encodeQuadrant(work, 0f, 0f, 0.5f, 0.5f),     // top-left
            encodeQuadrant(work, 0.5f, 0f, 0.5f, 0.5f),   // top-right
            encodeQuadrant(work, 0f, 0.5f, 0.5f, 0.5f),   // bottom-left
            encodeQuadrant(work, 0.5f, 0.5f, 0.5f, 0.5f), // bottom-right
        ).filterNotNull()
        if (quadrants.size < 4) {
            // encodeQuadrant returned null for at least one crop —
            // usually OOM under heavy heap pressure after the
            // fullRes + thumbnail encodes.  The LLM will see fewer
            // than 5 images; surface this so the user / debug overlay
            // can see why, instead of silently degrading the model's
            // round-1 input.
            val msg = "FrameAnalyzer: ${4 - quadrants.size}/4 quadrant(s) failed to encode; " +
                "LLM will see ${quadrants.size + 1} image(s) instead of 5"
            android.util.Log.w("IntentCam", msg)
            onError(msg)
        }
        work.recycle()
        if (fullRes == null || thumbnail == null) return null
        return CapturedFrame(thumbnail = thumbnail, fullRes = fullRes, quadrants = quadrants)
    }

    private fun encodeQuadrant(src: Bitmap, fx: Float, fy: Float, fw: Float, fh: Float): ByteArray? {
        val W = src.width
        val H = src.height
        val left = (fx * W).toInt().coerceAtLeast(0)
        val top = (fy * H).toInt().coerceAtLeast(0)
        val right = ((fx + fw) * W).toInt().coerceAtMost(W)
        val bot = ((fy + fh) * H).toInt().coerceAtMost(H)
        if (right <= left || bot <= top) return null
        val crop = Bitmap.createBitmap(src, left, top, right - left, bot - top)
        try {
            return encodeBitmap(crop, quality = QUADRANT_QUALITY, maxDim = QUADRANT_MAX_DIM)
        } finally {
            crop.recycle()
        }
    }

    private fun encodeBitmap(src: Bitmap, quality: Int, maxDim: Int): ByteArray? {
        val scale = maxDim.toFloat() / maxOf(src.width, src.height)
        val bitmap: Bitmap = if (scale < 1f) {
            val scaledW = (src.width * scale).toInt().coerceAtLeast(1)
            val scaledH = (src.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        } else src
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bitmap !== src) bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }

    private companion object {
        // Thumbnail (initial LLM image): 768 px max-dim, q80.  Picked
        // after benchmarking — see eval-history note in
        // profiling/eval_resize.py.
        const val MAX_DIM = 768
        const val QUALITY = 80
        // Full-res kept in memory for zoom_in crops.  No downscale;
        // the JPEG is at native phone-photo size (e.g. 1920x1440 for
        // a 4:3 sensor).  q95 is "visually lossless" so each
        // subsequent crop is also visually lossless.
        const val MAX_FULL_DIM = 4096
        const val FULL_QUALITY = 95
        // Quadrant crops sent in round 1 with the thumbnail.  Same
        // max-dim as the original thumbnail (so the LLM sees them at
        // a comparable scale) but slightly higher quality since each
        // crop has the full pixel budget.
        const val QUADRANT_MAX_DIM = 768
        const val QUADRANT_QUALITY = 85
    }
}

// cropJpegRegion moved to :shared/CropStrategy.kt as a free function
// that delegates to ImageOps.cropJpegRegion.  The Android
// implementation lives in app/.../AndroidImageOps.kt and is wired in
// from MainActivity.onCreate via [installAndroidImageOps].