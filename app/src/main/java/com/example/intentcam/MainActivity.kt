package com.example.intentcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.input.KeyboardType
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
        // Wire the platform-specific image ops into the shared
        // module.  Must happen before the first ToolUseLoop.runCycle
        // call — i.e. before the user taps the shutter for the first
        // time.  Doing it here (instead of in AppViewModel.init) keeps
        // the JVM eval able to install its own ImageIO-based impl
        // without touching Android code.
        //
        // OCR: install the HMS ML Kit (Huawei) offline OCR backend.
        // `installAndroidOcr` registers an `OcrEngine.Impl` and
        // pre-warms the analyzer so the first round-1 OCR pre-pass
        // doesn't pay the cold-cache model-fetch cost.  Phase 2
        // (2026-07-11) removed the `read_text` tool — OCR now runs
        // automatically on round-1 + every zoom_in crop.  The Chinese
        // + Latin language packs are bundled as transitive dependencies
        // (`ml-computer-vision-ocr-cn-model`); HMS handles the
        // first-run download on its own scheduler.
        installAndroidImageOps()
        installAndroidOcr(application)
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
                bubble = state.selectedBubble!!,
                onRestart = viewModel::clearBubbleSelection,
                onActionTap = { actionId -> viewModel.runAction(actionId, state.selectedBubble!!.id) },
                actionDefs = state.selectedBubble!!.actions.mapNotNull {
                    viewModel.actionRegistry.get(it)
                },
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
                IntentBubbles(
                    state = state,
                    onPick = viewModel::selectBubble,
                    viewModel = viewModel,
                )
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
        // Action-arg input dialog: an action body returned
        // [ActionOutcome.RequestArgs] and is parked waiting for the
        // user to fill the form.  Distinct composable so the two
        // surfaces don't fight over the same TextField / focus state.
        state.pendingAction?.let { pending ->
            ActionArgInputDialog(
                pending = pending,
                onSubmit = viewModel::submitActionArgs,
                onCancel = viewModel::cancelActionArgs,
            )
        }
        // [2026-07-13] Action confirmation dialog: a chip-tap
        // landed on an [ActionDef] whose `requiresConfirmation` is
        // true (currently `dial_number` only).  Two-button
        // confirm/cancel gate.  Once-confirmed actions persist
        // their userPrefKey grant so the chip doesn't re-prompt.
        state.pendingConfirmation?.let { pending ->
            ActionConfirmDialog(
                pending = pending,
                onConfirm = viewModel::confirmAction,
                onCancel = viewModel::cancelConfirmation,
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
                val targetSize = pickLargestAnalysisSize(
                        provider = provider,
                        selector = CameraSelector.DEFAULT_BACK_CAMERA,
                    )
                    val analysis = ImageAnalysis.Builder()
                    // [2026-07-12] explicit ResolutionSelector.  CameraX
                    // defaults to 640×480 (VGA) for ImageAnalysis when
                    // none is set, so MAX_DIM=3200 and MAX_FULL_DIM=4096
                    // in FrameAnalyzer were no-ops — encodeBitmap only
                    // downscales (never upscales), and the LLM was
                    // getting a 640×480 JPEG instead of the configured
                    // 3200-wide thumbnail.  zoom_in crops were also
                    // dying because 50% of 640 = 320 = a tiny "magnified"
                    // image.  Query the back camera's actual supported
                    // sizes and pick the largest 4:3 (falling back to
                    // largest-by-area) so every device targets its real
                    // sensor resolution instead of a hardcoded guess.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                            )
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    targetSize,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
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
private fun IntentBubbles(
    state: UiState,
    onPick: (Bubble) -> Unit,
    viewModel: AppViewModel,
) {
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
            // [2026-07-10] Resolve bubble.actions ids through the
            //  registry at render time.  Stale ids (e.g., a retired
            //  action referenced by an older bubble in history) are
            //  silently dropped via mapNotNull.
            val actionDefs = bubble.actions.mapNotNull {
                viewModel.actionRegistry.get(it)
            }
            BubbleCard(
                bubble = bubble,
                onPick = onPick,
                actionDefs = actionDefs,
                onActionTap = { actionId -> viewModel.runAction(actionId, bubble.id) },
            )
        }
    }
}

@Composable
private fun BubbleCard(
    bubble: Bubble,
    onPick: (Bubble) -> Unit,
    actionDefs: List<ActionDef> = emptyList(),
    onActionTap: (actionId: String) -> Unit = {},
) {
    val accent = when (bubble.type) {
        "location" -> Color(0xFF37D399)
        "solve" -> Color(0xFFFFAF54)
        else -> Color(0xFF4F8CFF)
    }
    val thumbnail = remember(bubble.imageBytes) {
        // Decode on a worker thread; the result bitmap is cached for the
        // composition's lifetime so re-emits are cheap.  Downscaled to a
        // card-sized bitmap — no point decoding a 3200 px thumbnail full
        // for a 56 dp preview.
        decodeScaled(bubble.imageBytes, targetMaxDim = 400)
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
                    val toolName = bubble.toolName
                    if (toolName != null) {
                        Spacer(Modifier.width(6.dp))
                        ToolChip(toolName = toolName)
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
                // [2026-07-10] Action chips: each chip is a tap target
                //  that fires `onActionTap(actionId)`.  Horizontal
                //  scroll keeps a long chip list from squashing the
                //  title / detail columns above.  Empty list → the
                //  Row collapses to zero-height and no visual change
                //  (existing bubble card layout is preserved).
                if (actionDefs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        actionDefs.forEach { def ->
                            ActionChip(
                                label = def.label,
                                onClick = { onActionTap(def.id) },
                            )
                        }
                    }
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

/**
 * Small pill-shaped chip rendered for each [ActionDef] suggested on a
 * bubble.  Tap fires [onClick] (which the caller wires to
 * `AppViewModel.runAction`).
 *
 * V1 uses a synthetic accent (matches the bubble's intent color)
 * rather than the icon mapping from [ActionDef.iconKey] — icon
 * assets aren't in scope for this iteration; later we'll plumb a
 * proper Material icon set.
 */
@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Surface(
        color = Color(0x553F6CD8),
        shape = RoundedCornerShape(10.dp),
        onClick = onClick,
    ) {
        Text(
            label,
            color = Color(0xFFE7ECF7),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
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

/** Decode [bytes] downscaled so the long side is ≈ [targetMaxDim] px, via
 *  a power-of-2 `inSampleSize`.  Avoids decoding a 3200 px thumbnail into a
 *  full ~30 MB ARGB bitmap just to render it small (card) or Fit-scaled to a
 *  ~1080 px screen (detail).  Returns null on empty/undecodable input. */
private fun decodeScaled(bytes: ByteArray, targetMaxDim: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= targetMaxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreen(
    bubble: Bubble,
    onRestart: () -> Unit,
    onActionTap: (actionId: String) -> Unit,
    actionDefs: List<ActionDef>,
    modifier: Modifier
) {
    val fullImage = remember(bubble.imageBytes) {
        // The bubble carries the ~3200 px display thumbnail; decode it
        // downscaled to ~1600 px — plenty for a Fit view on a phone
        // screen, and ~1/4 the ARGB footprint of a full decode.
        decodeScaled(bubble.imageBytes, targetMaxDim = 1600)
    }
    val accent = when (bubble.type) {
        "location" -> Color(0xFF37D399)
        "solve" -> Color(0xFFFFAF54)
        else -> Color(0xFF4F8CFF)
    }
    // [2026-07-13] fullscreen redesign: the image fills the entire screen
    // (ContentScale.Fit, black letterbox) so the user can see it clearly
    // and cross-check it against the results.  The result panel and the
    // 退出 button float on top of the image as a bottom overlay.  The panel
    // is a tap-to-collapse sheet — collapsed gives the image the whole
    // screen; expanded shows the (scrollable, half-screen-capped,
    // semi-transparent) content over the lower part of the image.
    var textExpanded by remember { mutableStateOf(true) }
    val textScroll = rememberScrollState()
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        // Layer 1 — fullscreen image.  When bytes are missing (rare), the
        // Box's black background stands in.
        if (fullImage != null) {
            Image(
                bitmap = fullImage.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Layer 2 — bottom overlay: collapsible result sheet + sticky 退出.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // Sheet header — always visible, tap to collapse/expand.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xE6111828))
                    .clickable { textExpanded = !textExpanded }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = bubble.title.ifBlank { "识别结果" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(bubble.confidence * 100).toInt()}%",
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (textExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowUp
                    },
                    contentDescription = if (textExpanded) "收起文字" else "展开文字",
                    tint = Color(0xFFB9C4DE),
                )
            }
            // Expanded content — semi-transparent panel over the image,
            // capped to half the screen and scrollable so the image top
            // stays visible for cross-checking.
            if (textExpanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        .background(Color(0xE60E1320))
                        .verticalScroll(textScroll)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    bubble.toolName?.let { name ->
                        ToolChip(toolName = name)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (bubble.detail.isNotBlank()) {
                        Text(
                            bubble.detail,
                            color = Color(0xFFE7ECF7),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (bubble.needsUserInput) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "需要补充信息（点击下方退出后重拍画面）",
                            color = Color(0xFFFFAF54),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (bubble.details.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "图片细节",
                            color = Color(0xFFB9C4DE),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        DetailsTable(bubble.details)
                    }
                    // [2026-07-10] Action chips in the detail panel —
                    //  same source of truth as the bubble card; the
                    //  caller passes the resolved [ActionDef]s and
                    //  the tap handler.  Empty list → no row, no
                    //  visual change.
                    if (actionDefs.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "可执行操作",
                            color = Color(0xFFB9C4DE),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            actionDefs.forEach { def ->
                                ActionChip(
                                    label = def.label,
                                    onClick = { onActionTap(def.id) },
                                )
                            }
                        }
                    }
                }
            }
            // 退出 button — sticky at the bottom, floating over the image.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xE6111828))
                    .navigationBarsPadding()
                    .padding(20.dp),
            ) {
                Button(
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("退出")
                }
            }
        }
    }
}

/** Renders a small (kind, label, value) table for the detail view.
 *  Used by DetailScreen when the LLM extracted structured details
 *  via emit_bubble.
 *
 *  When a row carries a `bbox` (4-corner normalized coords from the
 *  round-1 OCR hint), a small accent dot is appended to the right of
 *  the value — a visual cue that "this row has a positional anchor
 *  in the photo" (future enhancement: tap-to-zoom on the dot). */
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
                if (!d.bbox.isNullOrEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    // Accent dot marking rows with positional OCR
                    // anchors.  8dp emerald to match the bubble
                    // location accent (sub-system consistency).
                    Box(
                        Modifier
                            .size(8.dp)
                            .align(Alignment.CenterVertically)
                            .background(Color(0xFF37D399), shape = CircleShape)
                    )
                }
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
 * whenever [logs] grows.  No per-row line cap — entries with long stack
 * traces or exception messages render at full length and the LazyColumn
 * auto-wraps them.  DEBUG mode is for hunting crashes; truncating is the
 * opposite of useful here.
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

/**
 * Form for an [ActionOutcome.RequestArgs] park.  Renders one
 * [OutlinedTextField] per [ActionArgSpec], validates required fields
 * on submit, and submits a `Map<key, value>` to the view model.
 *
 * `keyboardType` is chosen per [ArgKind] so numeric / phone fields
 * pop the right IME variant.  Required-field state is checked on
 * submit (not per-keystroke) to avoid spurious disabled-button flicker.
 */
@Composable
private fun ActionArgInputDialog(
    pending: PendingAction,
    onSubmit: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    // Per-field text state.  Default values from
    // [ActionArgSpec.default] pre-fill on first composition.
    val fieldState = remember(pending) {
        mutableStateMapOf<String, String>().apply {
            pending.args.forEach { put(it.key, it.default ?: "") }
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("动作需要补充信息")
                Spacer(Modifier.width(8.dp))
                ToolChip(toolName = pending.actionId)
            }
        },
        text = {
            // Cap height so a long form scrolls inside the dialog
            // and the screen keyboard doesn't shove the confirm
            // button off-screen.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    pending.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                pending.args.forEach { spec ->
                    OutlinedTextField(
                        value = fieldState[spec.key] ?: "",
                        onValueChange = { fieldState[spec.key] = it },
                        label = { Text(spec.label) },
                        placeholder = spec.default?.let { { Text(it) } },
                        singleLine = spec.kind == ArgKind.NUMERIC ||
                            spec.kind == ArgKind.PHONE,
                        keyboardOptions = when (spec.kind) {
                            ArgKind.NUMERIC -> KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            )
                            ArgKind.PHONE -> KeyboardOptions(
                                keyboardType = KeyboardType.Phone
                            )
                            ArgKind.TEXT -> KeyboardOptions.Default
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    spec.helpText?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            it,
                            color = Color(0xFF7B8FB8),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            val submitEnabled = pending.args.all { spec ->
                !spec.required || !(fieldState[spec.key] ?: "").isBlank()
            }
            TextButton(
                onClick = {
                    val args = pending.args.associate { spec ->
                        spec.key to (fieldState[spec.key] ?: "").trim()
                    }
                    onSubmit(args)
                },
                enabled = submitEnabled,
            ) { Text("执行") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

/**
 * [2026-07-13] Yes/no confirmation dialog parked by an [ActionDef]
 * whose `requiresConfirmation` is true (currently only
 * `dial_number`).  Two buttons: "确认" (calls `onConfirm`) and
 * "取消" (calls `onCancel`); `onDismissRequest` (back-press) routes
 * to cancel so an impatient back-press never silently fires the
 * side-effect.
 *
 * Body of the dialog is [PendingConfirmation.detail] — pre-baked
 * in AppViewModel when the chip was tapped, so the same wording
 * appears whether the user is online or offline, and so a future
 * "show help icon in this dialog" change has a single source of
 * truth (the parked state, not the body lambda at click-time).
 *
 * Distinct from [UserInputDialog] (free-text follow-up) and
 * [ActionArgInputDialog] (form-fill) so the mutually-exclusive
 * `state.x?.let { ... }` overlays don't compose-conflict: each
 * composable owns its own focus / TextField / layout height.
 */
@Composable
private fun ActionConfirmDialog(
    pending: PendingConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pending.prompt)
                Spacer(Modifier.width(8.dp))
                ToolChip(toolName = pending.actionId)
            }
        },
        text = {
            Text(
                pending.detail,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

/**
 * Pick the largest size the given camera's sensor supports for
 * ImageAnalysis.  Reads `StreamConfigurationMap.getOutputSizes(...)`
 * via Camera2 interop so we target the *real* sensor size instead
 * of a hardcoded guess (which would either waste resolution on
 * a low-end device or crash the analyzer on a high-end one whose
 * sensor is bigger than 4096 — e.g. Samsung's 50MP sensor outputs
 * 8160×6120, Huawei P40 Pro 8192×6144).
 *
 * Strategy:
 *  1. Prefer 4:3 sizes (matches the system prompt's "横屏/竖屏都按 4:3"
 *     assumption and pairs cleanly with the user's framing).
 *  2. Among 4:3 sizes, pick the one with the largest area.
 *  3. If no 4:3 size is available, fall back to the overall
 *     largest size (defensive — should never happen on a stock
 *     Android camera).
 *  4. If the camera2 query fails entirely (rare OEM bug), fall
 *     back to 1920×1080 (a size every CameraX-supported device
 *     exposes for ImageAnalysis).
 *
 * Pass the result into `ResolutionStrategy(size, ...)` so the
 * selector targets an exact sensor size; combined with
 * `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` we still degrade
 * gracefully on devices where the exact size is unavailable.
 */
private fun pickLargestAnalysisSize(
    provider: ProcessCameraProvider,
    selector: CameraSelector,
): Size {
    // `selector.filter(List<CameraInfo>)` returns the sub-list that
    // matches the selector; we then pick the first match.  No
    // `first { predicate }` here because CameraSelector's filter
    // is a list operation, not a predicate.
    val backInfo: CameraInfo = try {
        selector.filter(provider.availableCameraInfos).firstOrNull()
    } catch (_: Throwable) {
        null
    } ?: return Size(1920, 1080)
    val camera2Info = runCatching { Camera2CameraInfo.from(backInfo) }.getOrNull()
        ?: return Size(1920, 1080)
    val map = runCatching {
        camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )
    }.getOrNull() ?: return Size(1920, 1080)
    // ImageAnalysis accepts YUV_420_888 (default) and converts to
    // RGBA_8888 internally; querying YUV gives the physical sensor
    // sizes CameraX will deliver.
    val sizes = runCatching {
        map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
    }.getOrNull() ?: return Size(1920, 1080)
    if (sizes.isEmpty()) return Size(1920, 1080)
    val fourThirds = sizes.filter {
        val ratio = it.width.toFloat() / it.height.toFloat()
        // 4:3 = 1.333...  accept 1.30–1.36 to cover OEM rounding.
        ratio in 1.30f..1.36f
    }
    val picked = fourThirds.maxByOrNull { it.width.toLong() * it.height }
        ?: sizes.maxByOrNull { it.width.toLong() * it.height }
        ?: Size(1920, 1080)
    return picked
}