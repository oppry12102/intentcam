package com.example.intentcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * Android-side implementations of [ImageOps.cropImpl] and
 * [ImageOps.thumbnailImpl].  Mirrors the BitmapFactory +
 * BitmapRegionDecoder logic that used to live inside
 * FrameAnalyzer.cropJpegRegion — moved here so the JVM eval can swap
 * in its own ImageIO-based impl while the app keeps using the
 * Android Bitmap pipeline.
 *
 * Call [installAndroidImageOps] once at app startup (MainActivity.onCreate)
 * before any ToolUseLoop.runCycle call.
 */
fun installAndroidImageOps() {
    ImageOps.cropImpl = ::androidCropJpegRegion
    ImageOps.thumbnailImpl = ::androidEncodeThumbnail
}

private fun androidCropJpegRegion(
    fullResJpeg: ByteArray,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
): ByteArray? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(fullResJpeg, 0, fullResJpeg.size, opts)
        val fullW = opts.outWidth
        val fullH = opts.outHeight
        if (fullW <= 0 || fullH <= 0) return null
        val left = (x.coerceIn(0f, 1f) * fullW).toInt().coerceAtLeast(0)
        val top = (y.coerceIn(0f, 1f) * fullH).toInt().coerceAtLeast(0)
        val right = ((x + w).coerceIn(0f, 1f) * fullW).toInt().coerceAtMost(fullW)
        val bot = ((y + h).coerceIn(0f, 1f) * fullH).toInt().coerceAtMost(fullH)
        val rect = Rect(left, top, right, bot)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val regionDecoder = BitmapRegionDecoder.newInstance(
            fullResJpeg, 0, fullResJpeg.size, false
        )
        val cropped = regionDecoder.decodeRegion(rect, null)
        regionDecoder.recycle()
        if (cropped == null) return null
        // Re-encode the crop at thumbnail sizing so the LLM gets a
        // single-resolution payload it can compare across rounds.
        val scale = 768f / maxOf(cropped.width, cropped.height)
        val finalBitmap = if (scale < 1f) {
            val sw = (cropped.width * scale).toInt().coerceAtLeast(1)
            val sh = (cropped.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(cropped, sw, sh, true)
                .also { if (it !== cropped) cropped.recycle() }
        } else cropped
        val out = ByteArrayOutputStream()
        val ok = finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (finalBitmap !== cropped) finalBitmap.recycle()
        if (ok) out.toByteArray() else null
    } catch (_: Throwable) {
        null
    }
}

private fun androidEncodeThumbnail(
    fullResJpeg: ByteArray,
    maxDim: Int,
    quality: Int,
): ByteArray? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(fullResJpeg, 0, fullResJpeg.size, opts)
        val fullW = opts.outWidth
        val fullH = opts.outHeight
        if (fullW <= 0 || fullH <= 0) return null
        val scale = maxDim.toFloat() / maxOf(fullW, fullH)
        val targetW = if (scale < 1f) (fullW * scale).toInt().coerceAtLeast(1) else fullW
        val targetH = if (scale < 1f) (fullH * scale).toInt().coerceAtLeast(1) else fullH
        val opts2 = BitmapFactory.Options().apply { inSampleSize = 1 }
        val bitmap = BitmapFactory.decodeByteArray(
            fullResJpeg, 0, fullResJpeg.size, opts2
        ) ?: return null
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else bitmap
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        if (ok) out.toByteArray() else null
    } catch (_: Throwable) {
        null
    }
}