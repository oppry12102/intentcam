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
     *  - thumbnail: scaled to [MAX_DIM], quality [QUALITY] — for LLM
     *    round 1.
     *  - fullRes:   at native resolution, quality [FULL_QUALITY] —
     *    kept in memory so zoom_in can crop from it.
     *
     * **1-only image strategy** (default since 2026-07-06).  The model
     * sees the thumbnail alone in round 1 and calls zoom_in for
     * regions it can't read.  Sending 1+4 (5 images up front) was
     * tried and benchmarked worse on RCTW-17 (composite 0.68 vs 0.77
     * on a 20-fixture sample) — the 5-image burst blew past the 20s
     * round budget on dense scenes.
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
        work.recycle()
        if (fullRes == null || thumbnail == null) return null
        return CapturedFrame(thumbnail = thumbnail, fullRes = fullRes)
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
        // Thumbnail (initial LLM image): 3200 px max-dim, q90.
        // 2026-07-12 (option C, shipped): 3200 is the ceiling.
        // Tested 1568 (baseline 0.889) → 3200 (0.902 ⭐) → 4096
        // (0.885, REVERTED).  The 4096 result confirmed the
        // "attention-spread" failure pattern: pushing dimension
        // to model max makes the model lose focus on text regions.
        // 3200 is the sweet spot for this LLM's vision encoder.
        //
        // Token cost: 3200² ≈ 10.24M px (vs 4096² ≈ 16.78M,
        // 1.64× less).  Round-1 thumbnail is sent every round, so
        // 3200 stays under 16M px / round — well under the cap.
        const val MAX_DIM = 3200
        const val QUALITY = 90
        // Full-res kept in memory for zoom_in crops (sibling views).
        // No downscale; the JPEG is at native phone-photo size
        // (e.g. 1920x1440 for a 4:3 sensor, or 4032x3024 for higher-end
        // sensors — the 4096 cap just bounds memory).  q95 is
        // "visually lossless" so each subsequent crop is also
        // visually lossless.
        // TEST 2026-07-12 (revisit): bump 2048→4096 back to test
        // against the Phase 2 architecture (auto-OCR on every
        // zoom crop + 4-step trust-crop-OCR workflow).  The
        // 2026-07-10 round 3 #5 result (2048 winning +0.057) was
        // measured WITHOUT Phase 2 — extra source pixels were
        // wasted on the cap.  With Phase 2, more pixels → more
        // raw chars the crop OCR recognises → 'trust verbatim'
        // step can surface them.  See [[user-decision-4096-2026-07-12]]
        // for tracking; revert if composite regresses > noise.
        const val MAX_FULL_DIM = 4096
        const val FULL_QUALITY = 95
    }
}

// cropJpegRegion moved to :shared/CropStrategy.kt as a free function
// that delegates to ImageOps.cropJpegRegion.  The Android
// implementation lives in app/.../AndroidImageOps.kt and is wired in
// from MainActivity.onCreate via [installAndroidImageOps].