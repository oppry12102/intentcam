package com.example.intentcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ad-image correction for the `view_ad` page: crop the ad region
 * from the captured frame, rectify its perspective, and enhance it.
 *
 * Deliberately dependency-free (no OpenCV — the project avoids new
 * native deps; see the view_label ADR for the same constraint):
 *  - **Perspective correction** — `Matrix.setPolyToPoly` maps the
 *    LLM-framed quad (4 corners, TL→TR→BR→BL, normalized [0,1]) onto
 *    the output rectangle; a bilinear-filtered `drawBitmap` does the
 *    warp.  This is a full 4-point projective transform, not just an
 *    affine shear.
 *  - **Enhancement** — three cheap passes tuned for photographed
 *    posters: auto-contrast (luminance mean/std normalized via a
 *    ColorMatrix), a mild saturation lift, and a light 3×3 unsharp
 *    convolution.  All are bounded so an already-good capture is
 *    barely touched (scale clamped to [0.8, 2.0]).
 *
 * When the quad is absent/degenerate the whole frame is used
 * unwarped (enhancement still applies) — the page stays useful.
 */
object AdImageCorrector {

    /** Long-edge cap for the corrected output (px).  Bounds both the
     *  convolution cost and the payload kept in `RenderedAd`. */
    const val MAX_OUT_DIM = 2200

    /** Output JPEG quality for the corrected ad image. */
    const val OUT_JPEG_QUALITY = 92

    /** Mild saturation lift for washed-out poster photos (1 = none). */
    private const val SATURATION = 1.12f

    /** Unsharp edge weight (0 disables).  0.25 is deliberately light —
     *  posters don't tolerate halos. */
    private const val SHARPEN_EDGE = 0.25f

    /** Decode [imageBytes], correct against [quad] (nullable), and
     *  return the JPEG-encoded result.  Null on decode failure. */
    suspend fun correctToJpeg(imageBytes: ByteArray, quad: List<OcrPoint>?): ByteArray? =
        withContext(Dispatchers.Default) {
            val frame = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return@withContext null
            val corrected = correct(frame, quad)
            val out = ByteArrayOutputStream()
            corrected.compress(Bitmap.CompressFormat.JPEG, OUT_JPEG_QUALITY, out)
            if (corrected !== frame) frame.recycle()
            corrected.recycle()
            out.toByteArray()
        }

    /** Crop + rectify + enhance.  [quad] may be null or malformed —
     *  falls back to the whole frame.  May return [frame] itself
     *  (callers must not recycle the input blindly). */
    fun correct(frame: Bitmap, quad: List<OcrPoint>?): Bitmap {
        val rectified = rectify(frame, quad) ?: downscaleIfNeeded(frame)
        val enhanced = enhance(rectified)
        return enhanced
    }

    // ── perspective correction ────────────────────────────────────

    /** Warp [quad] of [frame] into a rectangle sized from the quad's
     *  average edge lengths.  Null → caller's fallback. */
    private fun rectify(frame: Bitmap, quad: List<OcrPoint>?): Bitmap? {
        if (quad == null || quad.size != 4) return null
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        val px = FloatArray(8)
        for (i in 0 until 4) {
            px[i * 2] = quad[i].x * w
            px[i * 2 + 1] = quad[i].y * h
        }
        // Sanity: quad must span a non-degenerate area.
        val topW = dist(px, 0, 2)
        val botW = dist(px, 6, 4)
        val leftH = dist(px, 0, 6)
        val rightH = dist(px, 2, 4)
        if (min(topW, botW) < 8 || min(leftH, rightH) < 8) return null

        var outW = ((topW + botW) / 2f).toInt().coerceAtLeast(16)
        var outH = ((leftH + rightH) / 2f).toInt().coerceAtLeast(16)
        val scale = max(outW, outH).toFloat() / MAX_OUT_DIM
        if (scale > 1f) {
            outW = (outW / scale).toInt()
            outH = (outH / scale).toInt()
        }

        val dst = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat(),
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(px, 0, dst, 0, 4)) return null
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(frame, matrix, paint)
        return out
    }

    private fun dist(p: FloatArray, a: Int, b: Int): Float {
        val dx = p[a] - p[b]
        val dy = p[a + 1] - p[b + 1]
        return sqrt(dx * dx + dy * dy)
    }

    /** No-quad path: keep the frame, just cap the long edge so the
     *  enhancer's convolution stays cheap. */
    private fun downscaleIfNeeded(frame: Bitmap): Bitmap {
        val longEdge = max(frame.width, frame.height)
        if (longEdge <= MAX_OUT_DIM) return frame
        val scale = MAX_OUT_DIM.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            frame,
            (frame.width * scale).toInt(),
            (frame.height * scale).toInt(),
            true,
        )
    }

    // ── enhancement ───────────────────────────────────────────────

    private fun enhance(src: Bitmap): Bitmap {
        var bmp = autoContrastAndSaturation(src)
        if (SHARPEN_EDGE > 0f) {
            bmp = unsharp(bmp)
        }
        return bmp
    }

    /** Contrast stretch for LOW-CONTRAST captures only, mean-
     *  preserving, plus a mild saturation lift — one ColorMatrix
     *  pass.  (First cut normalized mean→128, which turned white
     *  posters gray — exactly the common case for ads.  Now: stretch
     *  only when std is low, never compress, and keep the original
     *  mean luminance.) */
    private fun autoContrastAndSaturation(src: Bitmap): Bitmap {
        // Sample luminance statistics on a coarse grid.
        val step = max(1, max(src.width, src.height) / 256)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 0 until src.height step step) {
            for (x in 0 until src.width step step) {
                val p = src.getPixel(x, y)
                val l = 0.299 * ((p shr 16) and 0xff) +
                    0.587 * ((p shr 8) and 0xff) + 0.114 * (p and 0xff)
                sum += l
                sumSq += l * l
                n++
            }
        }
        val mean = if (n > 0) sum / n else 128.0
        val variance = if (n > 0) sumSq / n - mean * mean else 0.0
        val std = sqrt(max(0.0, variance))
        // Low-contrast capture (dim/flat) → stretch std toward ~55,
        // around the image's own mean.  Contrasty capture → scale 1.
        val scale = if (std < 35.0) {
            (55.0 / max(std, 14.0)).toFloat().coerceIn(1.0f, 2.0f)
        } else {
            1.0f
        }
        val offset = (mean * (1.0 - scale)).toFloat()

        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val sat = ColorMatrix()
        sat.setSaturation(SATURATION)
        cm.postConcat(sat)

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** 3×3 unsharp mask: center 1+4·e, edges −e. */
    private fun unsharp(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val e = SHARPEN_EDGE
        val center = 1f + 4f * e
        for (y in 0 until h) {
            val yUp = max(0, y - 1)
            val yDown = min(h - 1, y + 1)
            for (x in 0 until w) {
                val xL = max(0, x - 1)
                val xR = min(w - 1, x + 1)
                val c = pixels[y * w + x]
                val acc = FloatArray(3)
                fun mix(p: Int, k: Float) {
                    acc[0] += ((p shr 16) and 0xff) * k
                    acc[1] += ((p shr 8) and 0xff) * k
                    acc[2] += (p and 0xff) * k
                }
                mix(c, center)
                mix(pixels[y * w + xL], -e)
                mix(pixels[y * w + xR], -e)
                mix(pixels[yUp * w + x], -e)
                mix(pixels[yDown * w + x], -e)
                val r = acc[0].toInt().coerceIn(0, 255)
                val g = acc[1].toInt().coerceIn(0, 255)
                val b = acc[2].toInt().coerceIn(0, 255)
                out[y * w + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}
