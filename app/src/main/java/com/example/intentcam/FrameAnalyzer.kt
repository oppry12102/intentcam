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

/**
 * One captured frame, in two encodings:
 *  - [thumbnail] is sized for the LLM (768 max-dim, q80).  Sent as
 *    the round-1 image so the model can pick a tool + scan the scene.
 *  - [fullRes] is the original photo at high quality.  Stays in
 *    memory in case the model's `zoom_in` tool needs to crop a
 *    region at native resolution.
 *
 * Keeping both lets the LLM trade off bandwidth (small thumbnail by
 * default) for detail (a 1/4-region crop at full native pixels on
 * demand) without re-shooting the photo.
 */
data class CapturedFrame(
    val thumbnail: ByteArray,
    val fullRes: ByteArray,
)

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
            // the user actually sees.
            android.util.Log.w(
                "IntentCam",
                "analyze() swallowed: ${e.javaClass.simpleName}: ${e.message?.take(200) ?: ""}",
                e
            )
            onError("${e.javaClass.simpleName}: ${e.message?.take(160) ?: "无消息"}")
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
    }
}

/**
 * Crop a normalized rectangle out of a JPEG and re-encode it as a
 * thumbnail-sized JPEG.  Used by the `zoom_in` tool body so the model
 * can ask for a region of interest at near-native pixel density.
 *
 * @param fullResJpeg the high-quality JPEG the analyzer kept
 * @param x left edge in [0, 1] (image-width normalized)
 * @param y top edge in [0, 1]
 * @param w width in [0, 1]
 * @param h height in [0, 1]
 * @return cropped + resized JPEG, or null if the JPEG is unreadable
 */
fun cropJpegRegion(
    fullResJpeg: ByteArray,
    x: Float, y: Float, w: Float, h: Float,
): ByteArray? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(fullResJpeg, 0, fullResJpeg.size, opts)
        val fullW = opts.outWidth
        val fullH = opts.outHeight
        if (fullW <= 0 || fullH <= 0) return null
        val left = (x.coerceIn(0f, 1f) * fullW).toInt().coerceAtLeast(0)
        val top = (y.coerceIn(0f, 1f) * fullH).toInt().coerceAtLeast(0)
        val right = ((x + w).coerceIn(0f, 1f) * fullW).toInt()
            .coerceAtMost(fullW)
        val bot = ((y + h).coerceIn(0f, 1f) * fullH).toInt()
            .coerceAtMost(fullH)
        val rect = Rect(left, top, right, bot)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val regionDecoder = BitmapRegionDecoder.newInstance(
            fullResJpeg, 0, fullResJpeg.size, false
        )
        val cropped = regionDecoder.decodeRegion(rect, null)
        regionDecoder.recycle()
        if (cropped == null) return null
        val out = ByteArrayOutputStream()
        // Re-encode the crop at the thumbnail sizing the LLM is
        // expecting.  We pass MAX_DIM=768 so the crop arrives at the
        // same resolution the LLM is used to; the *content* is
        // higher-fidelity because the crop is ~1/4 of the source.
        val scale = 768f / maxOf(cropped.width, cropped.height)
        val finalBitmap = if (scale < 1f) {
            val sw = (cropped.width * scale).toInt().coerceAtLeast(1)
            val sh = (cropped.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(cropped, sw, sh, true)
                .also { if (it !== cropped) cropped.recycle() }
        } else cropped
        val ok = finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (finalBitmap !== cropped) finalBitmap.recycle()
        if (ok) out.toByteArray() else null
    } catch (_: Throwable) {
        null
    }
}