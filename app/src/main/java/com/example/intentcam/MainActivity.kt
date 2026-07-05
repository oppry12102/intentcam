package com.example.intentcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IntentCamTheme {
                Surface(color = Color.Black) {
                    AppRoot(viewModel)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: AppViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onPermissionsGranted()
    }

    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    when (state.phase) {
        Phase.SETTINGS -> SettingsScreen(
            current = viewModel.config,
            onSave = viewModel::saveConfig,
            onResetDefault = viewModel::resetConfigToDefault,
            onClose = viewModel::closeSettings
        )
        Phase.NEED_PERMISSION -> PermissionScreen {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        else -> CameraScreen(viewModel, state)
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B1021)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要相机权限才能识别意图", color = Color.White)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("授予权限") }
        }
    }
}

@Composable
private fun CameraScreen(viewModel: AppViewModel, state: UiState) {
    Box(Modifier.fillMaxSize()) {
        if (state.phase == Phase.SHOWING_DETAIL && state.selectedBubble != null) {
            // Detail view: show the captured image full-bleed, with a header
            // overlay carrying title + scene description + confidence, and
            // a 退出 button to dismiss.  The camera preview is hidden
            // here so the user sees the still image they tapped on,
            // not a live feed.
            DetailScreen(
                bubble = state.selectedBubble,
                onRestart = viewModel::clearBubbleSelection,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Live preview + translucent top overlay + bubbles + the
            // shutter button.  No motion-sensor trigger — recognition
            // fires when the user taps the shutter.
            CameraPreview(viewModel)
            TopOverlay(
                state = state,
                debugEnabled = state.debugEnabled,
                onToggleDebug = { viewModel.setDebugEnabled(!state.debugEnabled) },
                onSettings = viewModel::openSettings,
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                if (state.debugEnabled && state.phase == Phase.SCANNING) {
                    DebugLogPanel(
                        logs = state.debugLogs,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ShutterButton(
                    enabled = !state.analyzing && state.phase == Phase.SCANNING,
                    onClick = { viewModel.captureLatestFrame() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                IntentBubbles(state = state, onPick = viewModel::selectBubble)
            }
        }

        // User-input dialog overlays everything when a tool needs a
        // free-form follow-up (e.g. navigate_to_block's destination).
        state.userInputRequest?.let { req ->
            UserInputDialog(
                request = req,
                onSubmit = viewModel::submitUserInput,
                onCancel = viewModel::cancelUserInput,
            )
        }
    }
}

/**
 * Large round shutter button.  Disabled while a recognition cycle is
 * in flight (the spinner inside shows that work is happening).  Disabled
 * outside of SCANNING so we don't dispatch while the user is reading
 * a bubble detail.
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (enabled) Color(0xFF4F8CFF) else Color(0xFF4F8CFF).copy(alpha = 0.35f),
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (enabled) {
                    Text(
                        "识别",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(viewModel: AppViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(
                            executor,
                            FrameAnalyzer(
                                isArmed = viewModel::tryArmCapture,
                                onFrame = { frame -> viewModel.onFrame(frame) },
                                // Surface analyzer exceptions (typically OOM under
                                // heap pressure after several recognitions) to the
                                // in-app debug overlay so the user sees the actual
                                // cause instead of just the coroutine's
                                // "500ms 内没拿到帧" timeout.
                                onError = { msg -> viewModel.logAnalyzerError(msg) },
                            )
                        )
                    }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

@Composable
private fun TopOverlay(
    state: UiState,
    debugEnabled: Boolean,
    onToggleDebug: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // Two states in priority order:
            //   1. analyzing: spinner + scene text (or "识别中…")
            //   2. otherwise: last completed scene, or "对准物体…"
            if (state.analyzing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "识别中…",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else {
                Text(
                    text = state.scene.ifBlank { "对准物体，点击下方按钮识别…" },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2
                )
            }
        }
        // Debug toggle.  Bug icon is green when the scrolling log overlay
        // is active (default), gray when it's muted.  Tap to flip; the
        // preference persists across launches via SettingsStore.
        IconButton(onClick = onToggleDebug) {
            Icon(
                Icons.Filled.Build,
                contentDescription = if (debugEnabled) "关闭调试输出" else "开启调试输出",
                tint = if (debugEnabled) Color(0xFF37D399) else Color(0xFF6E7891),
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
        }
    }
}

@Composable
private fun IntentBubbles(state: UiState, onPick: (Bubble) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.bubbles.isNotEmpty()) {
            Text(
                "识别到的意图（点击查看详情）",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        // Rendered newest-last so the most recent is closest to the camera
        // preview / bottom edge.  The bubble list is a FIFO queue capped at
        // UiState.BUBBLE_MAX; older bubbles have already been evicted.
        state.bubbles.forEach { bubble ->
            BubbleCard(bubble = bubble, onPick = onPick)
        }
    }
}

@Composable
private fun BubbleCard(bubble: Bubble, onPick: (Bubble) -> Unit) {
    val accent = when (bubble.type) {
        "location" -> Color(0xFF37D399)
        "solve" -> Color(0xFFFFAF54)
        else -> Color(0xFF4F8CFF)
    }
    val thumbnail = remember(bubble.imageBytes) {
        // Decode on a worker thread; the result bitmap is cached for the
        // composition's lifetime so re-emits are cheap.
        runCatching {
            BitmapFactory.decodeByteArray(bubble.imageBytes, 0, bubble.imageBytes.size)
        }.getOrNull()
    }
    Surface(
        color = Color(0xE6161C2E),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = { onPick(bubble) }
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bubble.title.isNotBlank()) {
                        Text(
                            bubble.title,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (bubble.toolName != null) {
                        Spacer(Modifier.width(6.dp))
                        ToolChip(toolName = bubble.toolName)
                    }
                }
                if (bubble.detail.isNotBlank()) {
                    Text(
                        bubble.detail,
                        color = if (bubble.title.isBlank()) Color.White
                               else Color(0xFFB9C4DE),
                        fontWeight = if (bubble.title.isBlank()) FontWeight.Normal
                                     else FontWeight.Normal,
                        style = if (bubble.title.isBlank())
                                    MaterialTheme.typography.bodyMedium
                                 else MaterialTheme.typography.bodySmall,
                        maxLines = if (bubble.title.isBlank()) 4 else 2
                    )
                }
                if (bubble.needsUserInput) {
                    Text(
                        "需要补充信息",
                        color = Color(0xFFFFAF54),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                "${(bubble.confidence * 100).toInt()}%",
                color = accent,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** Small green pill naming the tool that produced this bubble
 *  (e.g. "via identify_product").  Helps the user tell which path
 *  the model took. */
@Composable
private fun ToolChip(toolName: String) {
    Box(
        Modifier
            .background(Color(0x3337D399), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "via $toolName",
            color = Color(0xFF37D399),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreen(
    bubble: Bubble,
    onRestart: () -> Unit,
    modifier: Modifier
) {
    val fullImage = remember(bubble.imageBytes) {
        runCatching {
            BitmapFactory.decodeByteArray(bubble.imageBytes, 0, bubble.imageBytes.size)
        }.getOrNull()
    }
    val accent = when (bubble.type) {
        "location" -> Color(0xFF37D399)
        "solve" -> Color(0xFFFFAF54)
        else -> Color(0xFF4F8CFF)
    }
    Box(
        modifier
            .background(Color(0xFF000000))
    ) {
        // Top half: image (fixed height).  ContentScale.Fit keeps
        // aspect ratio; black bars on the sides for non-16:9.
        if (fullImage != null) {
            Image(
                bitmap = fullImage.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .align(Alignment.TopCenter)
                    .background(Color.Black)
            )
        }
        // Bottom half: header info + details table + 退出 button.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE6111828))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(10.dp))
                if (bubble.title.isNotBlank()) {
                    Text(
                        bubble.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(bubble.confidence * 100).toInt()}%",
                        color = accent,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            bubble.toolName?.let { name ->
                Spacer(Modifier.height(4.dp))
                ToolChip(toolName = name)
            }
            if (bubble.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    bubble.detail,
                    color = Color(0xFFE7ECF7),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (bubble.needsUserInput) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "需要补充信息（点击下方退出后重拍画面）",
                    color = Color(0xFFFFAF54),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // Details table — extracted from the image by the LLM.
            if (bubble.details.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "图片细节",
                    color = Color(0xFFB9C4DE),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                DetailsTable(bubble.details)
            }
            // 退出 button — back to the recognition loop.
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("退出")
            }
        }
    }
}

/** Renders a small (kind, label, value) table for the detail view.
 *  Used by DetailScreen when the LLM extracted structured details
 *  via emit_bubble. */
@Composable
private fun DetailsTable(details: List<Detail>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2138), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        details.forEachIndexed { i, d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    d.kind,
                    color = Color(0xFF7B8FB8),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(60.dp),
                )
                Text(
                    d.label,
                    color = Color(0xFFB9C4DE),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(110.dp),
                )
                Text(
                    d.value,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            if (i < details.size - 1) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF2A3050))
                )
            }
        }
    }
}

/** Pill-shaped action button.  Distinct from [ToolChip] (which is a
 *  passive label) — this is a tappable button. */
@OptIn(ExperimentalLayoutApi::class)
// ActionChipButton removed — action_chips deferred.  Re-add when
// chips come back to the emit_bubble schema.

/**
 * Translucent scrolling panel that shows the recognition-pipeline log.
 * One row per [DebugLogEntry]; the list auto-scrolls to the newest entry
 * whenever [logs] grows.  Each row is capped at 3 lines so a runaway
 * long message can't blow past the visible area — the developer sees
 * the full message at the top, and the tail gets truncated with "…".
 *
 * Sits above the bubble list (camera preview stays partially visible
 * below the top overlay) with a thin dark background so the text stays
 * legible against any camera frame.
 */
@Composable
private fun DebugLogPanel(
    logs: List<DebugLogEntry>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            // Skip the animation when the panel is first composed or
            // when the user has scrolled up to read history; only snap
            // forward when we're already at the bottom.
            if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= logs.size - 2) {
                listState.scrollToItem(logs.size - 1)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .heightIn(max = 200.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC0B1021))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        items(logs, key = { it.seq }) { entry ->
            DebugLogRow(entry)
        }
    }
}

@Composable
private fun DebugLogRow(entry: DebugLogEntry) {
    val time = remember(entry.timestampMs) {
        TIME_FORMAT.get()!!.format(Date(entry.timestampMs))
    }
    Text(
        text = "$time  [${entry.tag}] ${entry.message}",
        color = Color(0xFFB9C4DE),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

// ThreadLocal because SimpleDateFormat isn't thread-safe and the lazy
// scroll-to-bottom can fire from any coroutine.  Allocating per call
// would be wasteful for a panel that re-renders on every new entry.
private val TIME_FORMAT: java.lang.ThreadLocal<SimpleDateFormat> =
    java.lang.ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

/**
 * Modal dialog asking the user for a free-form follow-up.  Surfaces
 * when a tool body returned `needsUserInput = true` (e.g. the
 * navigate_to_block tool waiting for a destination).
 *
 * The placeholder bubble remains in the queue until the user submits
 * or cancels; submitting re-runs the recognition cycle with the typed
 * text, cancelling drops the placeholder and returns to scanning.
 */
@Composable
private fun UserInputDialog(
    request: UserInputRequest,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("补充信息")
                Spacer(Modifier.width(8.dp))
                ToolChip(toolName = request.toolName)
            }
        },
        text = {
            Column {
                Text(request.prompt, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("在这里输入…") },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onSubmit(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}