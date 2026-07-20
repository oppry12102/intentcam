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
import androidx.core.app.ActivityCompat
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
        // Debug-only dev hook: `adb shell am start -n
        // com.example.intentcam/.MainActivity --ez dev_label_page true`
        // opens the view_label page with canned content so the
        // render / full-page capture / share path can be verified on
        // an emulator without a camera frame + LLM round.
        // `--ez dev_ad_page true` does the same for the view_ad page
        // (synthesizes a tilted ad photo through AdImageCorrector).
        if (BuildConfig.DEBUG && intent.getBooleanExtra("dev_label_page", false)) {
            viewModel.devShowLabelPage()
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("dev_ad_page", false)) {
            viewModel.devShowAdPage()
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

    // Track whether camera permission has been denied at least once. Android
    // reports `shouldShowRequestPermissionRationale == false` both before the
    // first request and after permanent denial, so `hasLaunchedOnce` separates
    // those states and lets the UI offer a direct path to system settings.
    // A Boolean captures the only meaningful signal without an unbounded count.
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    var hasLaunchedOnce by remember { mutableStateOf(false) }
    val permanentlyDenied = remember(permissionDeniedOnce, hasLaunchedOnce) {
        hasLaunchedOnce && permissionDeniedOnce &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                context as androidx.activity.ComponentActivity,
                Manifest.permission.CAMERA,
            )
    }

    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            viewModel.onPermissionsGranted()
        } else {
            hasLaunchedOnce = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (state.phase) {
            Phase.SETTINGS -> SettingsScreen(
                current = viewModel.config,
                debugEnabled = state.debugEnabled,
                onToggleDebug = viewModel::setDebugEnabled,
                onSave = viewModel::saveConfig,
                onClose = viewModel::closeSettings,
            )
            Phase.NEED_PERMISSION -> {
                // Landing here after a permission request means the camera is still
                // unavailable. Record that semantic state so the rationale API can
                // distinguish a soft denial from a permanently blocked request.
                LaunchedEffect(Unit) {
                    if (hasLaunchedOnce && ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionDeniedOnce = true
                    }
                }
                PermissionScreen(
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    permanentlyDenied = permanentlyDenied,
                )
            }
            else -> CameraScreen(viewModel, state)
        }
        // view_label's rendered-label page overlays whatever is
        // underneath (camera or detail screen) while the payload is
        // parked on UiState.  Chips on both screens can open it.
        state.renderedLabel?.let { rendered ->
            LabelPageScreen(
                label = rendered,
                onDismiss = viewModel::dismissRenderedLabel,
            )
        }
        // view_ad's 图文复现 page — same overlay pattern.
        state.renderedAd?.let { rendered ->
            AdPageScreen(
                ad = rendered,
                onDismiss = viewModel::dismissRenderedAd,
            )
        }
    }
}

/**
 * The permission screen handles two states: first-time or soft denial asks
 * the user to grant access; permanent denial offers a direct link to this
 * app's system permission page instead of repeating a dialog that cannot open.
 */
@Composable
private fun PermissionScreen(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    permanentlyDenied: Boolean,
) {
    val palette = IntentCamTheme.palette
    Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要相机权限才能识别意图", color = palette.onSurface)
            if (permanentlyDenied) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "权限已被永久拒绝。请到系统设置里手动开启。",
                    color = palette.onSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("授予权限") }
            if (permanentlyDenied) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenSettings) { Text("去系统设置") }
            }
        }
    }
}

@Composable
private fun CameraScreen(viewModel: AppViewModel, state: UiState) {
    val busy by viewModel.busy.collectAsState()
    // See ADR docs/adr/2026-07-12-shutter-counter-8-cycle-model.md.
    // The obsolete restart control is intentionally absent: queue slots release
    // as cycles finish or time out. `restartScanning()` remains an internal path
    // for returning from settings with a clean state and fresh LLM configuration.
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
                actionRegistry = viewModel.actionRegistry,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Live preview + translucent top overlay + bubbles + the
            // shutter button.  No motion-sensor trigger — recognition
            // fires when the user taps the shutter.
            CameraPreview(viewModel)
            // Keep the top overlay and error banner in one measured Column.
            // A fixed top offset breaks when scene text wraps, font scale changes,
            // or a display cutout changes the overlay height.
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                TopOverlay(
                    state = state,
                    onSettings = viewModel::openSettings,
                )
                // Surface user-facing errors directly below the TopOverlay.
                // Dismissal clears only the banner, preserving all in-flight
                // cycles and their visible cards.
                state.error?.let { msg ->
                    ErrorBanner(
                        message = msg,
                        onDismiss = viewModel::clearError,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
                // 2026-07-14 C-cleanup: analyzer-error panel is shown
                //  INDEPENDENTLY of the debugEnabled toggle so a
                //  FrameAnalyzer OOM / decode failure is always
                //  visible.  Red border to distinguish from the
                //  debug log.  Only renders when non-empty.
                if (state.analyzerErrorLog.isNotEmpty() && state.phase == Phase.SCANNING) {
                    AnalyzerErrorPanel(
                        logs = state.analyzerErrorLog,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ShutterButton(
                    // See ADR docs/adr/2026-07-16-producer-consumer-pipeline.md.
                    // SCANNING keeps the shutter available until the queued +
                    // in-flight depth reaches CYCLE_QUEUE_DEPTH. Terminal cycles
                    // release slots immediately and are evicted separately by the
                    // total-history cap, which is not a user-facing gate.
                    enabled = state.phase == Phase.SCANNING,
                    busy = busy,
                    remaining = (UiState.CYCLE_QUEUE_DEPTH -
                        state.activeCycleCount).coerceAtLeast(0),
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
        // See ADR docs/adr/2026-07-10-intent-action-framework.md.
        // Actions marked `requiresConfirmation` are parked behind an explicit
        // confirm/cancel gate; a persisted grant avoids redundant prompts.
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
 * Large round shutter button.  Disabled outside of SCANNING so we
 * don't dispatch while the user is reading a bubble detail.
 *
 * The tap triggers a long-press haptic so tactile feedback aligns with the
 * actual capture rather than press-down.
 *
 * Keep the remaining-slot count visible even while recognition is active.
 * Per-cycle BubbleCard spinners already communicate processing, so replacing
 * the number with another spinner or ring would hide capacity and add noise.
 *
 * See ADR docs/adr/2026-07-12-shutter-counter-8-cycle-model.md.
 * The button dims and disables at zero until a queued or in-flight cycle
 * releases a slot. [busy] affects only the TalkBack description.
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    busy: Boolean = false,
    remaining: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = IntentCamTheme.palette
    val haptics = LocalHapticFeedback.current
    val noSlots = remaining <= 0
    val finalEnabled = enabled && !noSlots
    // P1 fix: surfaceMuted (dark debug-panel bg) for the
    //  background when disabled, onSurfaceMuted for the "0"
    //  text.  Matches the visual language of the disabled
    //  DebugLogPanel header.
    val finalColor = if (noSlots) palette.surfaceMuted else palette.accentDelegate
    val finalTextColor = if (noSlots) palette.onSurfaceMuted else Color.White
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            enabled = finalEnabled,
            shape = CircleShape,
            color = finalColor,
            modifier = Modifier
                .size(72.dp)
                // TalkBack reads the counter so a screen-reader
                //  user knows the cap state ("还可以拍 3 张",
                //  "已满, 等待识别完成").  The `busy` branch is
                //  for accessibility — a screen-reader user still
                //  hears "正在识别, 还可以拍 N 张" so they know
                //  work is happening, even though sighted users
                //  see no visual change at the button itself.
                .semantics {
                    contentDescription = when {
                        busy -> "正在识别, 还可以拍 $remaining 张"
                        noSlots -> "已满, 等待识别完成以释放"
                        else -> "识别当前画面, 还可以拍 $remaining 张"
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                // Keep the number visible for every available slot, including
                // while other cycles are in flight. BubbleCard owns the single
                // visual processing indicator, avoiding redundant spinner UI.
                Text(
                    "$remaining",
                    color = finalTextColor,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(viewModel: AppViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    // Explicitly release CameraX when this composable leaves composition.
    // Use cases bind to the activity lifecycle, so navigating through the detail
    // screen can otherwise leave a detached PreviewView bound while a new one
    // requests the same outputs. A brief rebind flicker is preferable to a stale
    // surface combination that leaves the returning preview black.
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        providerFuture.addListener({
            val provider = providerFuture.get()
            // Clean slate — drop any use cases left bound by a
            // previous composition that didn't unbind (e.g. an
            // interrupted process or a previous app session that
            // crashed without proper cleanup).
            provider.unbindAll()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val targetSize = pickLargestAnalysisSize(
                provider = provider,
                selector = CameraSelector.DEFAULT_BACK_CAMERA,
            )
            val analysis = ImageAnalysis.Builder()
                // See ADR docs/adr/2026-07-10-on-device-sensor-resolution.md.
                // CameraX otherwise defaults ImageAnalysis to VGA, making the
                // configured encoder dimensions and zoom crops ineffective.
                // Request the largest supported 4:3 analysis size so each device
                // supplies useful sensor detail without a hardcoded resolution.
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
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                    // CameraX bind failures belong in the user-facing ErrorBanner
                    // and logcat, not the FrameAnalyzer error buffer. Keeping the
                    // channels separate prevents one-time camera initialization
                    // faults from being mistaken for per-frame decode failures.
                    android.util.Log.w(
                        "IntentCam",
                        "CameraX bind failed",
                        e
                    )
                    viewModel.setError(
                        "相机初始化失败: ${e.message?.take(40) ?: "未知"}"
                    )
                }
            }, ContextCompat.getMainExecutor(context))
            // Release all use cases before a future CameraScreen re-entry binds.
            onDispose {
                runCatching {
                    val provider = providerFuture.get()
                    provider.unbindAll()
                }
                executor.shutdown()
            }
    }

AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { previewView },
)
}

@Composable
private fun TopOverlay(
    state: UiState,
    onSettings: () -> Unit,
) {
    val palette = IntentCamTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background.copy(alpha = 0.6f))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // Render the scene text (or the placeholder when no scene has
            //  been emitted yet).  The in-flight signal is surfaced
            //  by every active BubbleCard's trailing spinner at the
            //  bottom of the screen, so the TopOverlay's separate
            //  spinner was redundant visual noise.
            Text(
                text = state.scene.ifBlank { "对准物体，点击下方按钮识别…" },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
        }
    }
}

/**
 * Red banner for [UiState.error], including service failures, tool errors,
 * and capture exceptions. The trailing dismiss action clears only the error,
 * preserving in-flight cycles and the existing camera layout.
 *
 * It stays at the top over a translucent backdrop so failures are prominent
 * and readable without displacing the bottom debug, shutter, or bubble UI.
 */
@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = IntentCamTheme.palette
    Surface(
        color = palette.danger.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.danger),
        modifier = modifier.padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "⚠ $message",
                color = palette.danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭错误提示",
                    tint = palette.danger,
                )
            }
        }
    }
}

@Composable
private fun IntentBubbles(
    state: UiState,
    onPick: (Bubble) -> Unit,
    viewModel: AppViewModel,
) {
    val palette = IntentCamTheme.palette
    // Cap bubble history at half the screen and scroll overflow. This keeps the
    // shutter visible while preserving access to older or long-detail results.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxBubbleHeight = (screenHeightDp * 0.5f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxBubbleHeight)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Live-UI mode: iterate every [com.example.intentcam.CycleSnapshot]
        //  and react to its `bubble` + `status` flows.  Chips transition
        //  Spinner → Validated in real time as the cycle's bubble flow
        //  updates.
        val snapshotCards = state.cycles.values.toList()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "识别中的意图（实时更新）",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // Surface the live cycle count so the user knows how many
            //  captures are still being tracked.  Captures queue up to
            //  CYCLE_QUEUE_DEPTH=8 (queued+in-flight) and process
            //  CYCLE_CONCURRENCY=2 at a time; the visible count includes
            //  queued, in-flight, and already-completed cycles still on
            //  screen.
            Text(
                "共 ${snapshotCards.size} 张",
                color = palette.onSurfaceMuted.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // Newest-first so the most-recent capture is at the top —
        //  the user just took that photo, so they want to see it
        //  first.  Cycles with no bubble yet (PENDING / early
        //  IN_FLIGHT) are rendered as a placeholder card so the
        //  card list stays stable while the LLM is thinking.
        snapshotCards
            .sortedByDescending { it.capturedAtMs }
            .forEach { snap ->
                // `key(snap.id)` gives each cycle a STABLE composable
                //  identity.  Without it, this Column's `.forEach`
                //  matches BubbleCards positionally — so when a
                //  COMPLETE cycle is evicted (producer/consumer
                //  total-cap eviction), the remaining slots re-bind
                //  to different cycles, each slot's `thumbnail`
                //  param changes, its `LaunchedEffect(thumbnail)`
                //  recycles the bitmap a neighbouring slot may still
                //  be drawing → native "trying to use a recycled
                //  bitmap" crash.  A stable key means eviction only
                //  removes that one card; every other card keeps its
                //  identity + bitmap untouched.
                key(snap.id) {
                    val bubble by snap.bubble.collectAsState()
                    val status by snap.status.collectAsState()
                    // Always render a BubbleCard, even when
                    //  `bubble == null` — the captured thumbnail
                    //  (from `snap.thumbnail`) keeps the card's
                    //  shape stable from shutter-tap onward, and only
                    //  the title / detail / action-chip row /
                    //  confidence slot fill in as the LLM emits.
                    val actionDefs = bubble?.actions?.mapNotNull {
                        viewModel.actionRegistry.get(it)
                    } ?: emptyList()
                    BubbleCard(
                        bubble = bubble,
                        thumbnail = snap.thumbnail,
                        cycleStatus = status,
                        onPick = onPick,
                        actionDefs = actionDefs,
                        onActionTap = { actionId ->
                            bubble?.let { viewModel.runAction(actionId, it.id) }
                        },
                        accent = bubble?.let {
                            bubbleAccentActions(it, palette, viewModel.actionRegistry)
                        } ?: palette.accentDelegate,
                    )
                }
            }
    }
}


/**
 * One bubble card with two render modes (see [UiState.activeCycleCount]
 * for the active-cycle gauge that drives the shutter counter).
 *
 *  - **Final** (`bubble != null`): full result — title, detail,
 *    action chips, confidence percentage.  Tappable.
 *
 *  - **Loading** (`bubble == null`): the cycle is alive but the
 *    LLM hasn't emitted yet (PENDING / IN_FLIGHT) OR errored
 *    before any emit (ERRORED).  Same Surface / Row / Column
 *    layout — captured thumbnail (from [thumbnail]) is decoded
 *    into the image slot, title becomes "识别中…" or "识别超时,
 *    请再拍一张", detail / actions / confidence suppressed, a
 *    small spinner takes the confidence slot.  Non-tappable.
 *
 * Use the same card shape before and after the bubble arrives. Only the inner
 * content and confidence slot change, preventing a visible layout jump after
 * the shutter.
 *
 * @param bubble    finalized bubble, or null while loading.
 *                  When non-null, `bubble.imageBytes` is the same
 *                  bytes as [thumbnail] (ToolUseLoop sets both).
 * @param thumbnail captured frame's thumbnail bytes, always present.
 * @param cycleStatus drives SUPERSEDED dim and ERRORED title.
 */
@Composable
private fun BubbleCard(
    bubble: Bubble?,
    thumbnail: ByteArray,
    cycleStatus: JobStatus,
    onPick: (Bubble) -> Unit,
    actionDefs: List<ActionDef> = emptyList(),
    onActionTap: (actionId: String) -> Unit = {},
    accent: Color,
) {
    val isLoading = bubble == null
    val isErrored = isLoading && cycleStatus == JobStatus.ERRORED
    // See ADR docs/adr/2026-07-16-producer-consumer-pipeline.md.
    // PENDING means queued for a worker, not actively processing. Render it as
    // "排队中…" with a static marker; IN_FLIGHT gets the live spinner.
    val isQueued = isLoading && cycleStatus == JobStatus.PENDING
    val isSuperseded = cycleStatus == JobStatus.SUPERSEDED
    val titleText = when {
        isErrored -> "识别超时, 请再拍一张"
        isQueued -> "排队中…"
        isLoading -> "识别中…"
        else -> bubble?.title?.ifBlank { "未命名" } ?: "未命名"
    }
    val palette = IntentCamTheme.palette
    // Decode the replacement before recycling the previous bitmap. Recycling
    // from a key-changing DisposableEffect can observe the newly assigned value
    // and invalidate the image Compose is about to render; a local `prev` makes
    // the swap atomic from the renderer's perspective.
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(thumbnail) {
        val prev = displayBitmap
        val next = withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeScaled(thumbnail, DecodedSize.Thumbnail)
        }
        displayBitmap = next
        if (prev !== next && prev?.takeIf { !it.isRecycled } != null) {
            prev.recycle()
        }
    }
    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isSuperseded) 0.45f else 1f)
            .semantics(mergeDescendants = true) {
                contentDescription = when {
                    isErrored -> "识别超时, 请再拍一张"
                    isQueued -> "排队中, 等待识别"
                    isLoading -> "识别中"
                    bubble != null -> buildString {
                        append("识别结果:")
                        if (bubble.title.isNotBlank()) append(" ").append(bubble.title)
                        append("。置信度 ").append((bubble.confidence * 100).toInt()).append("%")
                        if (actionDefs.isNotEmpty()) {
                            append("。").append(actionDefs.size).append(" 个可执行操作")
                        }
                        if (isSuperseded) append("。已替换")
                    }
                    else -> ""
                }
            },
        // Loading cards have no detail to open. Gate the Surface through
        // `enabled` so Material also removes its ripple and click target.
        onClick = { bubble?.let { onPick(it) } },
        enabled = bubble != null,
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (displayBitmap != null) {
                val bmp = displayBitmap!!
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (isSuperseded) {
                    Text(
                        "已替换",
                        color = palette.onSurfaceMuted.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        titleText,
                        color = if (isErrored) palette.danger else Color.White,
                        fontWeight = if (isLoading) FontWeight.Normal
                                     else FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = true),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The trailing confidence-slot spinner already communicates
                    // loading. Avoid a second title-row spinner; render the intent
                    // chip there only after the bubble is available.
                    if (!isLoading && bubble != null) {
                        val chipLabel = bubble.type
                        if (chipLabel.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            IntentChip(label = chipLabel, accent = accent)
                        }
                    }
                }
                if (!isLoading && bubble != null && bubble.detail.isNotBlank()) {
                    Text(
                        bubble.detail,
                        color = palette.onSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!isLoading && bubble != null && bubble.needsUserInput) {
                    Text(
                        "需要补充信息",
                        color = palette.warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!isLoading && !isErrored && bubble != null && actionDefs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Key chip-state derivation by the stable bubble inputs and
                        // a compact action-set fingerprint. This avoids allocating an
                        // ID list on every recomposition; endpoint collisions are an
                        // acceptable trade-off because resolution is cheap and action
                        // sets with equal size and endpoints are exceptionally rare.
                        val chipStates = remember(
                            bubble.id,
                            actionDefs.size,
                            actionDefs.firstOrNull()?.id,
                            actionDefs.lastOrNull()?.id,
                            cycleStatus,
                            bubble.validatedInputs,
                        ) {
                            actionDefs.associateWith {
                                resolveChipState(bubble, it, cycleStatus)
                            }
                        }
                        // Filter Hidden chips before layout so transparent states do
                        // not reserve gaps in the horizontally scrolling row.
                        actionDefs.forEach { def ->
                            val state = chipStates[def] ?: ChipState.Hidden
                            if (state == ChipState.Hidden) return@forEach
                            ActionChip(
                                label = def.label,
                                state = state,
                                onClick = { onActionTap(def.id) },
                            )
                        }
                    }
                }
            }
            when {
                // See ADR docs/adr/2026-07-16-producer-consumer-pipeline.md.
                // A queued cycle gets a muted static dot rather than the IN_FLIGHT
                // spinner, making "waiting for a worker" visually distinct from
                // active recognition without relying on determinate progress APIs.
                isQueued -> Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(palette.onSurfaceMuted.copy(alpha = 0.5f))
                )
                isLoading && !isErrored ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = palette.accentDelegate,
                    )
                isErrored -> androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Close,
                    contentDescription = null,
                    tint = palette.danger,
                    modifier = Modifier.size(20.dp),
                )
                bubble != null -> Text(
                    "${(bubble.confidence * 100).toInt()}%",
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
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
 *
 * See ADR docs/adr/2026-07-10-intent-action-framework.md.
 * [state] drives each action chip's color and tappability:
 *   - Validated  → blue solid (default look), tappable
 *   - Ghost      → gray translucent, tappable (body handles "未发现
 *                  号码" Toast for dial_number / etc.)
 *   - Spinner    → yellow with a tiny spinner, NOT tappable (the
 *                  cycle hasn't finished validating yet)
 *   - Hidden     → not rendered (caller skips via mapNotNull)
 *
 * The visual transition is observable in real time as
 * [resolveChipState]'s inputs change — Compose recomposes the chip
 * row when `bubble.validatedInputs` updates (which happens on
 * every `onProgress` from the cycle).
 */
@Composable
private fun ActionChip(label: String, state: ChipState, onClick: () -> Unit) {
    val palette = IntentCamTheme.palette
    // Animate chip colors so validation progress reads as a transition rather
    // than a one-frame flicker. Hidden remains immediate because it leaves layout.
    val targetBg = when (state) {
        is ChipState.Validated -> palette.accentDelegate.copy(alpha = 0.80f)
        is ChipState.Ghost     -> Color.White.copy(alpha = 0.20f)
        is ChipState.Spinner   -> palette.warning.copy(alpha = 0.40f)
        is ChipState.Hidden    -> Color.Transparent
    }
    val targetFg = when (state) {
        is ChipState.Validated -> palette.onSurface
        is ChipState.Ghost     -> Color.White.copy(alpha = 0.80f)
        is ChipState.Spinner   -> palette.warning.copy(alpha = 0.70f)
        is ChipState.Hidden    -> Color.Transparent
    }
    val bg by animateColorAsState(targetValue = targetBg, animationSpec = tween(300), label = "chipBg")
    val fg by animateColorAsState(targetValue = targetFg, animationSpec = tween(300), label = "chipFg")
    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        // Announce the interaction state separately from the chip label so
        // TalkBack can distinguish executable, incomplete, and validating actions.
        modifier = Modifier.semantics {
            stateDescription = when (state) {
                is ChipState.Validated -> "可执行"
                is ChipState.Ghost -> "需要补充信息"
                is ChipState.Spinner -> "正在校验"
                is ChipState.Hidden -> "隐藏"
            }
        },
        // Ghost chips stay tappable (the body shows a Toast); Spinner
        // chips are non-tappable to avoid firing an action whose
        // requiredInputs haven't been validated yet.
        onClick = if (state is ChipState.Spinner) ({}) else onClick,
        enabled = state !is ChipState.Spinner,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state is ChipState.Spinner) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = fg,
                )
                Spacer(Modifier.size(4.dp))
            }
            Text(
                label,
                color = fg,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Small pill naming the intent id that produced this bubble
 *  (e.g. "phone", "payment_qr", "warning_safety").  Accent color is
 *  the actions-driven color computed by [bubbleAccentActions] in
 *  [BubbleCard], so the chip visually matches the bubble's left
 *  accent dot. */
@Composable
private fun IntentChip(label: String, accent: Color = IntentCamTheme.palette.success) {
    Box(
        Modifier
            .background(accent.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            color = accent,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Named presets prevent thumbnail and full-detail decode dimensions from being
 * swapped at call sites. `decodeScaled(bytes, DecodedSize.Full)` documents intent
 * more clearly than a raw pixel value.
 */
enum class DecodedSize(val maxDim: Int) {
    /** 400px — bubble card thumbnail.  56dp at xxxhdpi = 224px on
     *  the screen, so 400 is ~2x oversampling for sharp scaling. */
    Thumbnail(400),
    /** 1600px — detail screen full image.  Plenty for a Fit view
     *  on a phone screen (typically 1080-1440px wide); 1/4 the
     *  ARGB footprint of a 3200px full decode. */
    Full(1600),
}

/** Decode [bytes] downscaled so the long side is ≈ [preset]'s
 *  [DecodedSize.maxDim], via a power-of-2 `inSampleSize`.  Avoids
 *  decoding a 3200 px thumbnail into a full ~30 MB ARGB bitmap
 *  just to render it small (card) or Fit-scaled to a ~1080 px
 *  screen (detail).  Returns null on empty/undecodable input. */
private fun decodeScaled(bytes: ByteArray, preset: DecodedSize): Bitmap? {
    val targetMaxDim = preset.maxDim
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    // Pick a power-of-2 inSampleSize that's >= the floor we
    //  need; this guarantees the decoded bitmap won't exceed
    //  targetMaxDim by more than 2x before the optional
    //  secondary scale pass below.
    var sample = 1
    while (longest / (sample * 2) >= targetMaxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull() ?: return null
    // Power-of-two sampling can leave the decode up to 2x over target. Apply
    // one exact bilinear scale to cap memory, recycling the intermediate only
    // when createScaledBitmap allocated a distinct bitmap.
    val rawLongest = maxOf(raw.width, raw.height)
    if (rawLongest <= targetMaxDim) return raw
    val scale = targetMaxDim.toFloat() / rawLongest
    val scaledWidth = (raw.width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (raw.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(raw, scaledWidth, scaledHeight, true)
    if (scaled !== raw) raw.recycle()
    return scaled
}

/**
 * See ADR docs/adr/2026-07-15-v4-action-first-composite.md.
 * Resolves a bubble's accent color from its [Bubble.actions] list (the canonical post-
 * resolver chip set), not from [Bubble.type] (the now-deprecated
 * 14-bucket id).  The v4 thesis: most intents resolve to the same
 * `share` chip, so the action set is the primary user-visible
 * discriminator — accent should follow it.
 *
 * ## Behaviour clusters
 *
 * - **EXECUTE** — actions in {dial_number, scan_to_pay, redact_id}.
 *   Pink accent (was `phone`/`payment_qr` override); these are the
 *   consent-gated chips — visually the highest-leverage actions.
 * - **DELEGATE** — actions in {open_in_maps, share}. Blue accent
 *   (was OBSERVE-family base); the OS chooser is the consent step.
 * - **CLARIFY** — empty actions list, or all actions unmapped.
 *   Gray accent (was `info`/`solve` null family); pure info bubble,
 *   no actionable chip.
 *
 * EXECUTE wins over DELEGATE when both classes are present
 * (priority = "consent-required first, then consent-free").
 *
 * ## Fail-loud
 *
 * Unknown action ids (registered as neither EXECUTE nor DELEGATE)
 * return gray — better than silently pretending an unknown id is
 * a known behaviour cluster.
 */
private fun bubbleAccentActions(
    bubble: Bubble,
    palette: IntentCamPalette,
    actionRegistry: ActionRegistry,
): Color {
    val actions = bubble.actions.mapNotNull { actionRegistry.get(it) }
    val execute = actions.any { it.accent == AccentCluster.EXECUTE }
    val delegate = actions.any { it.accent == AccentCluster.DELEGATE }
    return when {
        execute -> palette.accentExecute
        delegate -> palette.accentDelegate
        else -> palette.accentClarify
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreen(
    bubble: Bubble,
    onRestart: () -> Unit,
    onActionTap: (actionId: String) -> Unit,
    actionDefs: List<ActionDef>,
    actionRegistry: ActionRegistry,
    modifier: Modifier,
) {
    // Decode the full image on IO to avoid blocking the detail-screen transition.
    // Manage replacement in one LaunchedEffect: decode first, atomically swap the
    // value Compose renders, then recycle only the locally captured previous bitmap.
    var fullImage by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bubble.imageBytes) {
        val prev = fullImage
        val next = withContext(kotlinx.coroutines.Dispatchers.IO) {
            // The bubble carries the ~3200 px display thumbnail; decode it
            // downscaled to ~1600 px — plenty for a Fit view on a phone
            // screen, and ~1/4 the ARGB footprint of a full decode.
            decodeScaled(bubble.imageBytes, DecodedSize.Full)
        }
        fullImage = next
        if (prev !== next && prev?.takeIf { !it.isRecycled } != null) {
            prev.recycle()
        }
    }
    val palette = IntentCamTheme.palette
    val accent = bubbleAccentActions(bubble, palette, actionRegistry)
    // The still image fills the screen with a collapsible result sheet over its
    // lower half. This preserves visual cross-checking while keeping long details
    // scrollable and the dismiss control independent of sheet height.
    //
    // Derive the initial sheet state from content length and save user toggles
    // across rotation: short results favor the image, long results expose text.
    val initialExpanded = remember(bubble.id) {
        (bubble.title.length + bubble.detail.length) > 60
    }
    var textExpanded by rememberSaveable(bubble.id) { mutableStateOf(initialExpanded) }
    val textScroll = rememberScrollState()
    Box(
        modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        // Layer 1 — fullscreen image.  When bytes are missing (rare), the
        // Box's black background stands in.
        if (fullImage != null) {
            // Snapshot the delegated mutable state after the null check because
            // Kotlin cannot smart-cast a property whose value may change.
            val bmp = fullImage!!
            Image(
                bitmap = bmp.asImageBitmap(),
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
                // Apply the status-bar inset only to readable overlay content;
                // the underlying image remains full-bleed. This keeps titles clear
                // of display cutouts without shrinking the photo.
                .statusBarsPadding()
        ) {
            // Sheet header — always visible, tap to collapse/expand.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surfaceOverlay)
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
                    color = palette.onSurface,
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
                    tint = palette.onSurfaceMuted,
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
                        .background(palette.surfaceOverlay)
                        .verticalScroll(textScroll)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (bubble.detail.isNotBlank()) {
                        Text(
                            bubble.detail,
                            color = palette.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (bubble.needsUserInput) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "需要补充信息（点击下方退出后重拍画面）",
                            color = palette.warning,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (bubble.details.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "图片细节",
                            color = palette.onSurfaceMuted,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        DetailsTable(bubble.details)
                    }
                    // See ADR docs/adr/2026-07-10-intent-action-framework.md.
                    // Detail actions use the same resolved ActionDefs and tap handler
                    // as the bubble card; an empty list produces no section.
                    if (actionDefs.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "可执行操作",
                            color = palette.onSurfaceMuted,
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
                                // Filter Hidden chips before FlowRow layout so they
                                // do not reserve invisible spacing in the detail view.
                                val chipState = resolveChipState(bubble, def, JobStatus.COMPLETE)
                                if (chipState == ChipState.Hidden) return@forEach
                                ActionChip(
                                    label = def.label,
                                    state = chipState,
                                    onClick = { onActionTap(def.id) },
                                )
                            }
                        }
                    }
                }
            }
            // Keep dismissal as a top-right floating control. It follows the
            // fullscreen-media convention and cannot be pushed off-screen by the
            // half-height result sheet on short or landscape displays.
        }
        // Top-right 退出 button — drawn AFTER the bottom Column so
        //  it sits on top in the Box's z-order, and outside the
        //  statusBarsPadding so it clears the notch.
        Surface(
            onClick = onRestart,
            shape = CircleShape,
            color = palette.surfaceMuted,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                // Use Material's 48dp minimum touch target for reliable one-handed
                // dismissal on compact screens.
                .size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "退出",
                    tint = palette.onSurface,
                )
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
    val palette = IntentCamTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
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
                    color = palette.onSurfaceSubtle,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(60.dp),
                )
                Text(
                    d.label,
                    color = palette.onSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(110.dp),
                )
                Text(
                    d.value,
                    color = palette.onSurface,
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
                            .background(palette.success, shape = CircleShape)
                    )
                }
            }
            if (i < details.size - 1) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.divider)
                )
            }
        }
    }
}

/** Pill-shaped action button.  Distinct from [IntentChip] (which is a
 *  passive label) — this is a tappable button. */
@OptIn(ExperimentalLayoutApi::class)
// ActionChipButton removed — action_chips deferred.  Re-add when
// chips come back to the emit_bubble schema.

/**
 * Shared scrolling list for [DebugLogPanel] and [AnalyzerErrorPanel]. It owns
 * append-following behavior while callers retain their own headers and styling.
 */
@Composable
private fun LogList(
    logs: List<DebugLogEntry>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
) {
    val listState = rememberLazyListState()
    // Stop following new entries while the user reads older rows. Resume only
    // after they return to the actual bottom; a "near bottom" threshold is too
    // aggressive in the shorter analyzer panel.
    var userScrolledUp by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        var prevLastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { newLastVisible ->
            // Upward scroll: user moved away from the bottom.
            //  Detect by lastVisible decreasing — a list growth
            //  (new entry) doesn't decrease lastVisible, it shifts
            //  the visible window up but the user hasn't actively
            //  scrolled, so we don't update the flag.
            if (newLastVisible < prevLastVisible) {
                userScrolledUp = true
            }
            // Down to the bottom → re-enable auto-scroll.
            if (newLastVisible >= logs.size - 1) {
                userScrolledUp = false
            }
            prevLastVisible = newLastVisible
        }
    }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && !userScrolledUp) {
            listState.scrollToItem(logs.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        items(logs, key = { it.seq }) { entry ->
            DebugLogRow(entry)
        }
    }
}

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
 *
 * Collapsed by default to preserve vertical space on compact screens; tapping
 * the header exposes the full 200dp log. The state is intentionally local so
 * this debug-only panel returns to its compact form after recreation.
 */
@Composable
private fun DebugLogPanel(
    logs: List<DebugLogEntry>,
    modifier: Modifier = Modifier,
) {
    val palette = IntentCamTheme.palette
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surfaceMuted)
            .clickable { expanded = !expanded },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "调试日志 (${logs.size})",
                color = palette.onSurfaceMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) {
                    androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp
                } else {
                    androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "折叠调试日志" else "展开调试日志",
                tint = palette.onSurfaceMuted,
            )
        }
        if (expanded) {
            LogList(
                logs = logs,
                modifier = Modifier.heightIn(max = 200.dp),
            )
        }
    }
}

@Composable
private fun DebugLogRow(entry: DebugLogEntry) {
    val palette = IntentCamTheme.palette
    val time = remember(entry.timestampMs) {
        TIME_FORMAT.get()!!.format(Date(entry.timestampMs))
    }
    Text(
        text = "$time  [${entry.tag}] ${entry.message}",
        color = palette.onSurfaceMuted,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

/**
 * Always-visible panel for `FrameAnalyzer` errors (OOM, decode
 * failure, etc.) — INDEPENDENT of the debugEnabled toggle so a
 * user with the panel off can still surface analyzer faults.
 * 2026-07-14 C-cleanup: split from `DebugLogPanel` for the
 * above reason; only renders when `logs` is non-empty.
 */
@Composable
private fun AnalyzerErrorPanel(
    logs: List<DebugLogEntry>,
    modifier: Modifier = Modifier,
) {
    val palette = IntentCamTheme.palette
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.danger.copy(alpha = 0.10f))
            .border(1.dp, palette.danger, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "⚠ FrameAnalyzer errors (${logs.size})",
            color = palette.danger,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        LogList(
            logs = logs,
            modifier = Modifier.heightIn(max = 120.dp),
        )
    }
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
    var text by rememberSaveable(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("补充信息")
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
                    // Let the IME's Done action submit through the same non-blank
                    // guard as the dialog button, avoiding an extra keyboard-dismiss step.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (text.isNotBlank()) onSubmit(text.trim())
                        }
                    ),
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
    val palette = IntentCamTheme.palette
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
                            color = palette.onSurfaceSubtle,
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
 * See ADR docs/adr/2026-07-10-intent-action-framework.md.
 * Yes/no confirmation dialog parked by an [ActionDef]
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
    // See ADR docs/adr/2026-07-10-on-device-sensor-resolution.md.
    // Clamp to CameraX ImageAnalysis's effective 4096px long-side ceiling.
    // Requesting larger sensor output adds conversion and allocation cost before
    // CameraX downscales to the same result; proportional scaling preserves 4:3.
    val maxLong = 4096
    val longest = maxOf(picked.width, picked.height)
    return if (longest <= maxLong) picked else {
        val scale = maxLong.toFloat() / longest
        Size(
            (picked.width * scale).toInt().coerceAtLeast(1),
            (picked.height * scale).toInt().coerceAtLeast(1),
        )
    }
}