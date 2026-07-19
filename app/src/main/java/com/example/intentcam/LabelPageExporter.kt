package com.example.intentcam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Save / share engine for the `view_label` rendered-label page.
 *
 * Four user-facing operations, all driven from LabelPageScreen's
 * button row:
 *  - saveImageToGallery  — PNG via MediaStore (API 29+) or
 *    app-specific external dir + MediaScanner (API 26–28).  **No
 *    WRITE_EXTERNAL_STORAGE permission anywhere**: scoped storage
 *    covers 29+, and `getExternalFilesDir` needs no grant below it
 *    (the scanner makes it gallery-visible).  Keeps the manifest
 *    permission-free for the same review-friendliness reason the
 *    CALL_PHONE permission was rejected earlier.
 *  - saveTextToDownloads — the markdown source as `.md`
 *    (MediaStore.Downloads on 29+, app-specific Documents dir below).
 *  - shareImage — FileProvider-granted `ACTION_SEND image/png`.
 *  - shareText  — `ACTION_SEND text/plain` with the markdown source.
 *
 * PNG capture renders the SAME HTML the on-screen page shows
 * ([LabelHtml.labelPageHtml]) into an off-screen WebView sized
 * width-exact / height-to-content, then `WebView.draw()`s it into a
 * Bitmap — the all-API-stable capture path (Compose's
 * `rememberGraphicsLayer`/`toImageBitmap` doesn't exist on the
 * app's ui-1.6.1 BOM).
 */
object LabelPageExporter {

    /** Off-screen capture height ceiling (px).  Bounds the transient
     *  bitmap allocation (1080×6000 ARGB_8888 ≈ 25 MB); real labels
     *  are far shorter — the cap only guards pathological content. */
    const val MAX_CAPTURE_HEIGHT_PX = 6000

    /** Off-screen capture width ceiling (px). */
    const val MAX_CAPTURE_WIDTH_PX = 1440

    /** Page-load wait before giving up on the off-screen render. */
    private const val PAGE_LOAD_TIMEOUT_MS = 5_000L

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Render the label page off-screen and write it as a PNG into
     *  the FileProvider-backed cache dir.  Returns the file, or null
     *  on timeout / render failure.  [widthPx] should match the
     *  on-screen card width so the saved image looks like what the
     *  user saw. */
    suspend fun capturePng(context: Context, title: String, markdown: String, widthPx: Int): File? {
        val html = LabelHtml.labelPageHtml(title, markdown)
        val width = min(widthPx, MAX_CAPTURE_WIDTH_PX)
        val bitmap = withContext(Dispatchers.Main) {
            val webView = WebView(context)
            try {
                webView.settings.javaScriptEnabled = false
                // Match the on-screen layout contract: no viewport
                // meta, so with wide-viewport OFF the page lays out
                // at exactly the view's width.
                webView.settings.useWideViewPort = false
                webView.settings.loadWithOverviewMode = false
                // Software layer is the reliable path for drawing an
                // unattached WebView into a Canvas (hardware layers
                // can rasterize blank off-screen).
                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

                val loaded = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                            override fun onReceivedError(
                                view: WebView, errorCode: Int,
                                description: String?, failingUrl: String?,
                            ) {
                                // Proceed anyway — a missing subresource
                                // shouldn't block the capture.
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        cont.invokeOnCancellation { webView.stopLoading() }
                    }
                }
                if (loaded == null) return@withContext null

                val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    width, android.view.View.MeasureSpec.EXACTLY
                )
                val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED
                )
                webView.measure(wSpec, hSpec)
                val height = min(webView.measuredHeight, MAX_CAPTURE_HEIGHT_PX)
                    .coerceAtLeast(1)
                webView.layout(0, 0, width, height)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                webView.draw(Canvas(bmp))
                bmp
            } catch (t: Throwable) {
                null
            } finally {
                webView.destroy()
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

    /** Copy [pngFile] into the user's gallery (Pictures/IntentCam).
     *  Returns a user-visible result message. */
    suspend fun saveImageToGallery(context: Context, pngFile: File): String =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, pngFile.name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/IntentCam"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: return@withContext "保存失败：无法创建相册条目"
                    resolver.openOutputStream(uri)?.use { out ->
                        pngFile.inputStream().use { it.copyTo(out) }
                    } ?: return@withContext "保存失败：无法写入相册"
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "已保存到相册 Pictures/IntentCam"
                } else {
                    // API 26–28: app-specific external dir needs no
                    // permission; MediaScanner makes it gallery-visible.
                    val dir = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "IntentCam"
                    ).apply { mkdirs() }
                    val dest = File(dir, pngFile.name)
                    pngFile.inputStream().use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    android.media.MediaScannerConnection.scanFile(
                        context, arrayOf(dest.absolutePath), arrayOf("image/png"), null
                    )
                    "已保存到相册 IntentCam 目录"
                }
            } catch (t: Throwable) {
                "保存失败：${t.message?.take(60) ?: "未知错误"}"
            }
        }

    /** Write the markdown source as a `.md` file the user can find
     *  (Download/IntentCam on 29+, app Documents dir below). */
    suspend fun saveTextToDownloads(context: Context, title: String, markdown: String): String =
        withContext(Dispatchers.IO) {
            val fileName = "label_${stampFormat.format(Date())}.md"
            val content = "# $title\n\n$markdown\n"
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/IntentCam"
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: return@withContext "保存失败：无法创建下载条目"
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    } ?: return@withContext "保存失败：无法写入文件"
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "已保存到 Download/IntentCam/$fileName"
                } else {
                    val dir = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                        "IntentCam"
                    ).apply { mkdirs() }
                    val dest = File(dir, fileName)
                    dest.writeText(content, Charsets.UTF_8)
                    "已保存到 ${dest.absolutePath}"
                }
            } catch (t: Throwable) {
                "保存失败：${t.message?.take(60) ?: "未知错误"}"
            }
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
