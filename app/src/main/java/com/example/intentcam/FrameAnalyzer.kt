package com.example.intentcam

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * CameraX analyzer that detects a "stable" frame (low motion vs the previous
 * tick) and emits two JPEGs only for those frames:
 *
 * - a small one (~512 px, q70) for the analyze request, which carries the
 *   lowest payload to the model server and saves image-preprocessing time;
 * - a larger one (~768 px, q75) for the answer request, which needs detail
 *   for tasks like extracting small text from labels.
 *
 * Performance notes
 * -----------------
 * - **Stability check** samples luma directly out of the RGBA_8888
 *   [ByteBuffer] using stride-aware pointer math.  No Bitmap allocation.
 *
 * - **JPEG emit** only happens on a stable frame, after the throttle and the
 *   busy-check.  The Bitmap is built once, rotated, then scaled twice — the
 *   second [Bitmap.createScaledBitmap] is much cheaper than the first because
 *   the source is already at 768 px.  Intermediates are recycled aggressively.
 *
 * Stable-frame emit is throttled by [minIntervalMs] (500 ms default) so the
 * ~2x encode cost is amortized over the much larger LLM round-trip time.
 */
class FrameAnalyzer(
    private val minIntervalMs: Long = 500L,
    private val stableThreshold: Float = 8f,
    /** Mean abs luma diff above this counts as a "dramatic scene change". */
    private val motionThreshold: Float = 40f,
    /** Min interval between two [onSceneChange] firings, in ms. */
    private val motionDebounceMs: Long = 1500L,
    /** How many pixels to skip between samples. 8 -> ~32x32 grid on 256x256 frames. */
    private val sampleStride: Int = 8,
    private val isBusy: () -> Boolean,
    private val onStableFrame: (FrameJpegs) -> Unit,
    /** Fired when motion spikes — the user has moved to a different scene. */
    private val onSceneChange: () -> Unit = {}
) : ImageAnalysis.Analyzer {

    private var prevSig: IntArray? = null
    private var lastSampleMs = 0L
    private var lastEmitMs = 0L
    private var lastSceneChangeMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
            lastSampleMs = now

            // Cheap gate: when the consuming side is busy (analyze round in
            // flight, user reading the answer, settings open), skip the
            // sampleLuma + diff math entirely.  This keeps the analyzer thread
            // quiet on the analyzer.kt executor and avoids firing
            // [onSceneChange] callbacks that would no-op anyway.
            if (isBusy()) return

            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return

            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val sig = sampleLuma(buffer, width, height, rowStride, pixelStride, sampleStride)
            val prev = prevSig
            prevSig = sig
            val diff = if (prev == null) 0f else meanAbsDiff(prev, sig)

            // Dramatic motion → user has switched scenes.  Reset prevSig inside
            // [onSceneChange] will eventually re-baseline as the camera settles,
            // and the next stable frame will drive a fresh analysis.
            if (diff > motionThreshold && now - lastSceneChangeMs > motionDebounceMs) {
                lastSceneChangeMs = now
                lastEmitMs = 0L  // next stable frame may emit immediately
                onSceneChange()
                return  // don't try to emit this very frame; the camera isn't still
            }

            val stable = diff < stableThreshold
            if (!stable) return
            if (now - lastEmitMs < minIntervalMs) return
            lastEmitMs = now

            buffer.rewind()
            val jpegs = bufferToDualJpeg(
                buffer, width, height, rowStride, pixelStride,
                rotationDegrees = image.imageInfo.rotationDegrees
            )
            if (jpegs != null) onStableFrame(jpegs)
        } catch (_: Exception) {
            // Never let a bad frame crash the analyzer pipeline.
        } finally {
            image.close()
        }
    }

    // ---- per-frame helpers ---------------------------------------------------

    private fun sampleLuma(
        buf: ByteBuffer,
        w: Int, h: Int,
        rowStride: Int, pixelStride: Int,
        step: Int
    ): IntArray {
        val effectiveStep = maxOf(step, maxOf(w, h) / SIG_GRID_LIMIT)
        val cols = (w + effectiveStep - 1) / effectiveStep
        val rows = (h + effectiveStep - 1) / effectiveStep
        val out = IntArray(cols * rows)

        var idx = 0
        var y = 0
        while (y < h) {
            val rowBase = y * rowStride
            var x = 0
            while (x < w) {
                val off = rowBase + x * pixelStride
                val r = buf.get(off).toInt() and 0xFF
                val g = buf.get(off + 1).toInt() and 0xFF
                val b = buf.get(off + 2).toInt() and 0xFF
                out[idx++] = (r * 299 + g * 587 + b * 114) / 1000
                x += effectiveStep
            }
            y += effectiveStep
        }
        return out
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return Float.MAX_VALUE
        var sum = 0L
        for (i in 0 until n) sum += kotlin.math.abs(a[i] - b[i])
        return sum.toFloat() / n
    }

    /**
     * Decode the camera buffer into a [Bitmap] once, rotate to upright, then
     * scale-down + JPEG-encode twice: once small for analyze, once larger
     * for answer.
     */
    private fun bufferToDualJpeg(
        buffer: ByteBuffer,
        width: Int, height: Int,
        rowStride: Int, pixelStride: Int,
        rotationDegrees: Int
    ): FrameJpegs? {
        val rowPaddingPx = (rowStride - pixelStride * width) / pixelStride
        val paddedWidth = width + rowPaddingPx

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        // The caller has already rewound the buffer; [copyPixelsFromBuffer]
        // reads from the current position so the position must be 0 before
        // this call.
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

        // Build the larger (answer) JPEG first, then downscale that to build
        // the smaller (analyze) JPEG.  Saves one full-sized createScaledBitmap.
        val answerJpeg = encodeScaled(work, ANSWER_MAX_DIM, ANSWER_QUALITY) ?: run {
            work.recycle()
            return null
        }
        val analyzeJpeg = encodeScaled(work, ANALYZE_MAX_DIM, ANALYZE_QUALITY)
        work.recycle()
        if (analyzeJpeg == null) return null

        return FrameJpegs(
            analyze = analyzeJpeg,
            answer = answerJpeg,
            width = work.width,
            height = work.height
        )
    }

    private fun encodeScaled(src: Bitmap, maxDim: Int, quality: Int): ByteArray? {
        val scale = maxDim.toFloat() / maxOf(src.width, src.height)
        val bitmap: Bitmap = if (scale < 1f) {
            val scaledW = (src.width * scale).toInt().coerceAtLeast(1)
            val scaledH = (src.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        } else src

        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        // Only recycle when we created a new bitmap; src is reused by the caller.
        if (bitmap !== src) bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }

    private companion object {
        const val SIG_GRID_LIMIT = 96

        // analyze: small + cheap.  Server-side image work drops with pixel area.
        const val ANALYZE_MAX_DIM = 512
        const val ANALYZE_QUALITY = 70

        // answer: still need detail (e.g. small product-label text).
        const val ANSWER_MAX_DIM = 768
        const val ANSWER_QUALITY = 75

        const val SAMPLE_INTERVAL_MS = 120L
    }
}
