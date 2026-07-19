package com.example.intentcam

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
 * Full-screen page for the `view_label` action: the label's markdown
 * rendered as a white full-bleed page (WebView + [LabelHtml]
 * template) with share affordances in the bottom bar.
 *
 * Hosted from `AppRoot` while `UiState.renderedLabel` is non-null,
 * so it covers whatever is underneath (camera or detail screen).
 *
 * 2026-07-19 redesign after on-device acceptance:
 *  - The page OCCUPIES THE SCREEN (user report: the previous centered
 *    card rendered tiny).  Full width; the WebView takes all vertical
 *    space between the header and the button bar; longer labels
 *    scroll inside the WebView.
 *  - The HTML carries a `width=device-width` viewport so CSS px = dp
 *    — the page renders at proper reading size on any screen
 *    ("根据屏幕大小渲染").
 *  - Only 分享图片 / 分享文字 remain (save-to-gallery / save-as-file
 *    were dropped on request — the share sheet covers every export).
 *
 * 分享图片 re-renders the SAME html off-screen
 * ([LabelPageExporter.capturePng]) so the shared image covers the
 * FULL content height, not just the scrolled viewport.
 */
@Composable
fun LabelPageScreen(
    label: RenderedLabel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = IntentCamTheme.palette

    var pageReady by remember(label.bubbleId) { mutableStateOf(false) }
    var busy by remember(label.bubbleId) { mutableStateOf(false) }
    // The rendered page's WebView — 分享图片 captures THIS view (the
    // provably-correct on-screen rasterization), not an off-screen
    // re-render; see LabelPageExporter's doc for the failure history.
    var webViewRef by remember(label.bubbleId) { mutableStateOf<WebView?>(null) }

    val html = remember(label.bubbleId) {
        LabelHtml.labelPageHtml(label.title, label.markdown)
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
                label.title,
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
        // ── the rendered label page (fills all remaining space) ────
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White),
        ) {
            AndroidView(
                factory = { ctx ->
                    // Lay out the WHOLE document up front so the
                    // full-height share-image capture can draw
                    // below the fold.  Static, process-wide, and must
                    // be called before the WebView is created.
                    WebView.enableSlowWholeDocumentDraw()
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        // HTML carries width=device-width; overview
                        // scaling stays OFF so CSS px maps 1:1 to dp.
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
        // ── actions: share as image / share as text ────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabelPageButton("分享图片", enabled = pageReady && !busy, Modifier.weight(1f)) {
                val wv = webViewRef ?: return@LabelPageButton
                scope.launch {
                    busy = true
                    val png = LabelPageExporter.captureWebView(context, wv)
                    val ok = png != null && LabelPageExporter.shareImage(context, png)
                    if (!ok) LabelPageExporter.toast(context, "截图失败，请重试")
                    busy = false
                }
            }
            LabelPageButton("分享文字", enabled = !busy, Modifier.weight(1f)) {
                if (!LabelPageExporter.shareText(context, label.title, label.markdown)) {
                    LabelPageExporter.toast(context, "分享失败，请重试")
                }
            }
        }
    }
}

@Composable
private fun LabelPageButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = IntentCamTheme.palette.accentDelegate,
            contentColor = Color.White,
            disabledContainerColor = IntentCamTheme.palette.surfaceMuted,
            disabledContentColor = IntentCamTheme.palette.onSurfaceMuted,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
