package com.example.intentcam

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * Full-screen overlay for the `view_label` action: the label's
 * markdown rendered as a styled card page (WebView + [LabelHtml]
 * template), sized to fit its content, with save/share affordances.
 *
 * Hosted from `AppRoot` while `UiState.renderedLabel` is non-null,
 * so it overlays whatever is underneath (camera or detail screen).
 *
 * Sizing ("合适大小的页面"): the card takes the screen width minus
 * margins (capped for tablets); its height follows the WebView's
 * measured content height, capped at [MAX_PAGE_HEIGHT_FRACTION] of
 * the screen — longer labels scroll inside the WebView.
 *
 * The four buttons:
 *  - 保存图片 — off-screen re-render → PNG → gallery (MediaStore).
 *  - 保存文字 — markdown source → `.md` in Downloads.
 *  - 分享图片 — off-screen re-render → PNG → share sheet (FileProvider).
 *  - 分享文字 — markdown source → share sheet (text/plain).
 * Image buttons re-render off-screen ([LabelPageExporter.capturePng])
 * rather than grabbing the on-screen WebView: the capture then covers
 * the FULL content height, not just the scrolled viewport.
 */
@Composable
fun LabelPageScreen(
    label: RenderedLabel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val palette = IntentCamTheme.palette

    var pageReady by remember(label.bubbleId) { mutableStateOf(false) }
    var busy by remember(label.bubbleId) { mutableStateOf(false) }
    // Follows WebView content height once the page finishes loading;
    // starts at a small placeholder so the card doesn't flash tall.
    var contentHeightDp by remember(label.bubbleId) { mutableStateOf(160.dp) }

    val html = remember(label.bubbleId) {
        LabelHtml.labelPageHtml(label.title, label.markdown)
    }
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp
    val cardWidthDp = (screenWidthDp - 32.dp).coerceAtMost(MAX_CARD_WIDTH_DP)
    val maxPageHeightDp = screenHeightDp * MAX_PAGE_HEIGHT_FRACTION

    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = MAX_CARD_WIDTH_DP)
                .fillMaxWidth()
                // Swallow taps so they don't fall through to the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── header: title + close ──────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                    )
                }
            }
            // ── the rendered label page ────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(contentHeightDp.coerceAtMost(maxPageHeightDp))
                    .background(Color.White, RoundedCornerShape(12.dp)),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            // No viewport meta in the HTML; with the
                            // wide-viewport feature OFF the page lays
                            // out at exactly this view's width.
                            settings.useWideViewPort = false
                            settings.loadWithOverviewMode = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    val contentDp = with(density) {
                                        (view.contentHeight * view.scale).toInt().toDp()
                                    }
                                    contentHeightDp = contentDp.coerceAtLeast(80.dp)
                                    pageReady = true
                                }
                            }
                            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!pageReady) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(32.dp),
                        color = palette.accentDelegate,
                    )
                }
            }
            // ── actions: save / share ──────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val imageWidthPx = remember(cardWidthDp) {
                    with(density) { cardWidthDp.roundToPx() }
                }
                LabelPageButton("保存图片", enabled = pageReady && !busy, Modifier.weight(1f)) {
                    scope.launch {
                        busy = true
                        val png = LabelPageExporter.capturePng(
                            context, label.title, label.markdown, imageWidthPx
                        )
                        val msg = if (png == null) "截图失败，请重试"
                        else LabelPageExporter.saveImageToGallery(context, png)
                        LabelPageExporter.toast(context, msg)
                        busy = false
                    }
                }
                LabelPageButton("保存文字", enabled = !busy, Modifier.weight(1f)) {
                    scope.launch {
                        busy = true
                        val msg = LabelPageExporter.saveTextToDownloads(
                            context, label.title, label.markdown
                        )
                        LabelPageExporter.toast(context, msg)
                        busy = false
                    }
                }
                LabelPageButton("分享图片", enabled = pageReady && !busy, Modifier.weight(1f)) {
                    scope.launch {
                        busy = true
                        val png = LabelPageExporter.capturePng(
                            context, label.title, label.markdown, imageWidthPx
                        )
                        val ok = png != null && LabelPageExporter.shareImage(context, png)
                        if (!ok) LabelPageExporter.toast(context, "分享失败，请重试")
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
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Card width ceiling for very wide screens. */
private val MAX_CARD_WIDTH_DP = 520.dp

/** The page's height never exceeds this fraction of the screen —
 *  longer labels scroll inside the WebView. */
private const val MAX_PAGE_HEIGHT_FRACTION = 0.62f

private const val SCRIM_ALPHA = 0.72f
