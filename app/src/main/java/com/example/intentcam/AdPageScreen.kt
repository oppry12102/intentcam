package com.example.intentcam

import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * Full-screen page for the `view_ad` action: 图文复现 — the
 * corrected/enhanced ad image on top ([AdImageCorrector] output,
 * embedded as a data URI) and the markdown transcription below,
 * rendered by the same WebView + [LabelHtml] pipeline as the label
 * page.
 *
 * Buttons:
 *  - 分享图片 — shares the CORRECTED AD IMAGE itself (the
 *    crop+rectify+enhance JPEG carried by [RenderedAd.imageJpeg]).
 *  - 分享图文 — shares the WHOLE composed page (image + formatted
 *    transcription) captured from the on-screen WebView via
 *    [LabelPageExporter.captureWebView] (the path verified on the
 *    label page).
 */
@Composable
fun AdPageScreen(
    ad: RenderedAd,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = IntentCamTheme.palette

    var pageReady by remember(ad.bubbleId) { mutableStateOf(false) }
    var busy by remember(ad.bubbleId) { mutableStateOf(false) }
    var webViewRef by remember(ad.bubbleId) { mutableStateOf<WebView?>(null) }

    val html = remember(ad.bubbleId) {
        val dataUri = ad.imageJpeg?.let {
            "data:image/jpeg;base64," + Base64.encodeToString(it, Base64.NO_WRAP)
        }
        LabelHtml.adPageHtml(ad.title, ad.markdown, dataUri)
    }

    BackHandler(onBack = onDismiss)

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        // ── header: title + close ──────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ad.title,
                color = palette.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = palette.onSurface,
                )
            }
        }
        // ── the composed ad page (image + transcription) ──────────
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White),
        ) {
            AndroidView(
                factory = { ctx ->
                    // Whole-document layout so the 分享图文 capture can
                    // draw below the fold (static, process-wide, must
                    // be set before the WebView is created).
                    WebView.enableSlowWholeDocumentDraw()
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                pageReady = true
                            }
                        }
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!pageReady) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(36.dp),
                    color = palette.accentDelegate,
                )
            }
        }
        // ── actions: share corrected image / share composed page ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 分享图片 = the corrected ad image (rectified + enhanced).
            Button(
                onClick = {
                    val jpeg = ad.imageJpeg ?: return@Button
                    scope.launch {
                        busy = true
                        val file = LabelPageExporter.writeJpegToCache(context, jpeg, "ad")
                        val ok = file != null &&
                            LabelPageExporter.shareImage(context, file, "image/jpeg")
                        if (!ok) LabelPageExporter.toast(context, "分享失败，请重试")
                        busy = false
                    }
                },
                enabled = ad.imageJpeg != null && !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentDelegate,
                    contentColor = Color.White,
                    disabledContainerColor = palette.surfaceMuted,
                    disabledContentColor = palette.onSurfaceMuted,
                ),
            ) {
                Text("分享图片", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            // 分享图文 = the whole composed page as one image.
            Button(
                onClick = {
                    val wv = webViewRef ?: return@Button
                    scope.launch {
                        busy = true
                        val png = LabelPageExporter.captureWebView(context, wv)
                        val ok = png != null && LabelPageExporter.shareImage(context, png)
                        if (!ok) LabelPageExporter.toast(context, "截图失败，请重试")
                        busy = false
                    }
                },
                enabled = pageReady && !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentDelegate,
                    contentColor = Color.White,
                    disabledContainerColor = palette.surfaceMuted,
                    disabledContentColor = palette.onSurfaceMuted,
                ),
            ) {
                Text("分享图文", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
