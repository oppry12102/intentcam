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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) viewModel.onPermissionsGranted()
    }

    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
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
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
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
            // overlay, an action chip row that drives LLM-powered follow-ups,
            // and an 退出 button to dismiss.  The camera preview is
            // hidden here so the user sees the still image they tapped on,
            // not a live feed.
            DetailScreen(
                bubble = state.selectedBubble,
                state = state,
                onAction = { viewModel.triggerAction(state.selectedBubble, it) },
                onRestart = viewModel::clearBubbleSelection,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Live preview is ALWAYS on screen.  When the camera analyzer sees
            // ≥ 1 s of stability, the captured JPEG is sent through OCR +
            // object detection + multi-round LLM as a background pipeline;
            // the UI never freezes.  Bubble results slide in at the bottom.
            CameraPreview(viewModel)
            TopOverlay(state = state, onSettings = viewModel::openSettings)
            IntentBubbles(
                state = state,
                onPick = viewModel::selectBubble,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
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
                                isBusy = viewModel::isBusy,
                                onStableFrame = viewModel::onStableFrame,
                                onSceneChange = viewModel::onSceneChange
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
private fun TopOverlay(state: UiState, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // Three states, in priority order:
            //   1. analyzing: spinner + streamed scene text
            //   2. stabilityProgress non-null: thin progress bar (system alive)
            //   3. otherwise: last completed scene, or "对准物体…"
            if (state.analyzing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    val live = state.partialScene
                    Text(
                        text = if (!live.isNullOrBlank()) live else "识别中…",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2
                    )
                }
            } else {
                val sp = state.stabilityProgress
                if (sp != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { sp.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp),
                            color = Color(0xFF4F8CFF),
                            trackColor = Color(0x33FFFFFF)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "稳定中 ${(sp * 100).toInt()}%",
                            color = Color(0xFFB9C4DE),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else {
                    Text(
                        text = state.scene.ifBlank { "对准物体，正在识别你的意图…" },
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2
                    )
                }
            }
            state.location?.let {
                Text("📍 $it", color = Color(0xFFB9C4DE), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
        }
    }
}

@Composable
private fun IntentBubbles(state: UiState, onPick: (Bubble) -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
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
                // When the model returned no parseable action, the bubble's
                // title is empty and only the detail (the image description)
                // is shown — no "未识别" / "无意图" prefix.
                if (bubble.title.isNotBlank()) {
                    Text(
                        bubble.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
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
            }
            Text(
                "${(bubble.confidence * 100).toInt()}%",
                color = accent,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun DetailScreen(
    bubble: Bubble,
    state: UiState,
    onAction: (Action) -> Unit,
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
        if (fullImage != null) {
            Image(
                bitmap = fullImage.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Header strip with title and confidence
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(10.dp))
                // Same as BubbleCard: when the title is empty (the
                // no-intent / parse-failure fallback), show only the
                // image description, no "未识别" / "无意图" / "图片描述"
                // prefix.
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
            if (bubble.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    bubble.detail,
                    color = Color(0xFFE7ECF7),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        // Action chip row + streaming-result panel
        val actions = remember(bubble.type, bubble.id) {
            appViewModelActions(bubble.type)
        }
        val activeKey = state.activeActionId
        val isThisBubbleActive = activeKey?.startsWith("${bubble.id}-") == true
        if (actions.isNotEmpty()) {
            val bubbleIdForResult = activeKey?.substringBeforeLast('-')
            val activeActionIdOnly = activeKey?.substringAfterLast('-')
            val completedForThis = if (activeKey == null) {
                state.actionResults.entries
                    .firstOrNull { it.key.startsWith("${bubble.id}-") }
            } else null
            ActionPanel(
                actions = actions,
                activeKey = activeActionIdOnly,
                completedEntry = completedForThis,
                activeText = if (isThisBubbleActive) state.partialActionText else null,
                onAction = onAction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 88.dp)
                    .fillMaxWidth()
            )
        }
        // 退出 button at the bottom — dismisses the detail view; the
        // main loop restarts from step 1 and starts a fresh stability
        // counter.
        Button(
            onClick = onRestart,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .fillMaxWidth()
        ) {
            Text("退出")
        }
    }
}

/**
 * Static action catalogue.  Pulled out so [DetailScreen] can render the
 * chip row without owning an [AppViewModel] (the function is a pure
 * mapping from intent type to action list, with action ids stable enough
 * for analytics).  The actual streaming call lives in
 * [AppViewModel.triggerAction].
 */
private fun appViewModelActions(type: String): List<Action> = when (type) {
    "info" -> listOf(
        Action("info-translate", "翻译成中文", ""),
        Action("info-list",     "列出关键信息", ""),
        Action("info-evaluate", "判断是否正常", ""),
    )
    "location" -> listOf(
        Action("loc-where",   "查询地点信息", ""),
        Action("loc-navigate","给我导航路线", ""),
        Action("loc-nearby",  "附近还有什么", ""),
    )
    "solve" -> listOf(
        Action("solve-steps",  "详细解题步骤", ""),
        Action("solve-verify", "验证答案", ""),
        Action("solve-similar","给我类似题", ""),
    )
    else -> listOf(
        Action("other-explain", "再解释一下", ""),
        Action("other-detail",  "看更多细节", ""),
    )
}

@Composable
private fun ActionPanel(
    actions: List<Action>,
    activeKey: String?,
    completedEntry: Map.Entry<String, String>?,
    activeText: String?,
    onAction: (Action) -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        // Action chip row (horizontally scrollable; chips overflow on
        // narrow screens).
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actions.forEach { action ->
                val isActive = activeKey == action.id
                val isDone = completedEntry != null &&
                    completedEntry.key.substringAfterLast('-') == action.id
                FilterChip(
                    selected = isActive || isDone,
                    onClick = { onAction(action) },
                    label = { Text(action.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF4F8CFF).copy(alpha = 0.3f),
                        selectedLabelColor = Color.White,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isActive || isDone,
                        borderColor = Color(0xFFB9C4DE),
                        selectedBorderColor = Color(0xFF4F8CFF),
                        borderWidth = 1.dp,
                    )
                )
            }
        }
        // Streaming text panel (only when active)
        if (activeText != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xCC0B1228),
                    contentColor = Color(0xFFE7ECF7)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = activeText,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else if (completedEntry != null) {
            // Show the last completed action result for this bubble,
            // even when no streaming is in progress.  Each new tap on a
            // chip overwrites the previous result.
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xCC0B1228),
                    contentColor = Color(0xFFE7ECF7)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = completedEntry.value,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
