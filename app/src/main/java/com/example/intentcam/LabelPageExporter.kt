package com.example.intentcam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Share engine for the `view_label` rendered-label page.
 *
 * Two user-facing operations from LabelPageScreen's button bar
 * (2026-07-19: save-to-gallery / save-as-file were dropped on user
 * request — the share sheet covers every export target):
 *  - shareImage — full-page capture of the ON-SCREEN WebView → PNG →
 *    FileProvider-granted `ACTION_SEND image/png`.
 *  - shareText  — `ACTION_SEND text/plain` with the markdown source.
 *
 * Capture approach (2026-07-19, second "空白图" fix): draw the
 * VISIBLE WebView — the one the user already sees rendered
 * correctly.  Two earlier off-screen attempts (fresh WebView, load
 * then draw) shipped blank PNGs: an unattached Chromium WebView is
 * not guaranteed to rasterize into an arbitrary Canvas (and
 * `onPageFinished` fires before the first frame anyway).  The
 * attached view rasterizes for the screen every frame, so drawing
 * it into a Bitmap reuses a path that provably works on-device.
 * Requirements on the host WebView, set up in LabelPageScreen:
 *  - `enableSlowWholeDocumentDraw()` BEFORE load — without it the
 *    WebView lays content out lazily in viewport tiles and anything
 *    below the fold draws blank (this API exists exactly for
 *    print/capture of full documents).
 * Capture then: temporarily resize the view to the full content
 * height, `draw()` into a Bitmap with a software layer, restore the
 * original bounds in the same main-thread turn (no frame is ever
 * presented at the stretched size), and run a blank-pixel guard so
 * a rasterization failure surfaces as "截图失败" instead of a
 * silently blank share.
 */
object LabelPageExporter {

    /** Capture height ceiling (px).  Bounds the transient bitmap
     *  allocation (1080×6000 ARGB_8888 ≈ 25 MB); real labels are far
     *  shorter — the cap only guards pathological content. */
    const val MAX_CAPTURE_HEIGHT_PX = 6000

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Draw [webView] at its FULL content height into a PNG in the
     *  FileProvider-backed cache dir.  Returns the file, or null on
     *  failure (including the blank-guard tripping). */
    suspend fun captureWebView(context: Context, webView: WebView): File? {
        val bitmap = withContext(Dispatchers.Main) {
            try {
                val width = webView.width.coerceAtLeast(1)
                val fullHeight = min(
                    (webView.contentHeight * webView.scale).toInt(),
                    MAX_CAPTURE_HEIGHT_PX
                ).coerceAtLeast(1)
                val oldLayer = webView.layerType
                val l = webView.left
                val t = webView.top
                val r = webView.right
                val b = webView.bottom
                // Software layer so draw() rasterizes into our Canvas
                // synchronously (a hardware layer can't render into an
                // arbitrary software Canvas).
                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        width, android.view.View.MeasureSpec.EXACTLY
                    ),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        fullHeight, android.view.View.MeasureSpec.EXACTLY
                    )
                )
                webView.layout(l, t, l + width, t + fullHeight)
                val bmp = Bitmap.createBitmap(width, fullHeight, Bitmap.Config.ARGB_8888)
                webView.draw(Canvas(bmp))
                // Restore before returning to the event loop — the
                // stretched size is never presented on screen.
                webView.layout(l, t, r, b)
                webView.setLayerType(oldLayer, null)
                if (looksBlank(bmp)) {
                    bmp.recycle()
                    null
                } else {
                    bmp
                }
            } catch (t: Throwable) {
                null
            }
        } ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "label_pages").apply { mkdirs() }
                val file = File(dir, "label_${stampFormat.format(Date())}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                file
            } catch (t: Throwable) {
                null
            }
        }
    }

    /** Cheap rasterization-failure detector: sample a coarse grid of
     *  pixels; if every sample is (near-)white/transparent the page
     *  almost certainly didn't render and we'd share a blank.  A
     *  real label page always has dark text. */
    private fun looksBlank(bmp: Bitmap): Boolean {
        val stepsX = 8
        val stepsY = 24
        for (iy in 1..stepsY) {
            for (ix in 1..stepsX) {
                val x = (bmp.width * ix / (stepsX + 1)).coerceIn(0, bmp.width - 1)
                val y = (bmp.height * iy / (stepsY + 1)).coerceIn(0, bmp.height - 1)
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val bl = p and 0xff
                if (r < 0xF0 || g < 0xF0 || bl < 0xF0) return false
            }
        }
        return true
    }

    /** Fire the system share sheet with [pngFile] as an image/png
     *  stream.  The URI is FileProvider-granted; ClipData is set in
     *  addition to EXTRA_STREAM because several targets (微信 among
     *  them) read the grant from ClipData only. */
    fun shareImage(context: Context, pngFile: File): Boolean = try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", pngFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(
                context.contentResolver, "label", uri
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享标签图片"))
        true
    } catch (t: Throwable) {
        false
    }

    /** Fire the system share sheet with the markdown source as plain
     *  text — same intent shape as the `share` action. */
    fun shareText(context: Context, title: String, markdown: String): Boolean = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "# $title\n\n$markdown")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享标签文字"))
        true
    } catch (t: Throwable) {
        false
    }

    /** Main-safe Toast helper for the button callbacks. */
    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
