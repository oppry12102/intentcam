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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
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

    // [2026-07-15 UI polish] Track whether the user has denied the
    //  camera permission at least once, so we can offer a
    //  "去系统设置" path on the second+ visit.  Android's
    //  shouldShowRequestPermissionRationale returns true on a
    //  "soft" denial (the dialog's "Don't allow" button) and
    //  false once the user has selected "Don't ask again" or
    //  hit the system-level toggle off — at that point the
    //  launcher dialog won't even appear, so a manual jump to
    //  Settings is the only path forward.
    var permissionDeniedCount by remember { mutableStateOf(0) }
    var hasLaunchedOnce by remember { mutableStateOf(false) }
    val permanentlyDenied = remember(permissionDeniedCount, hasLaunchedOnce) {
        hasLaunchedOnce && permissionDeniedCount > 0 &&
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

    when (state.phase) {
        Phase.SETTINGS -> SettingsScreen(
            current = viewModel.config,
            piiPermissions = viewModel.piiActionPermissions(),
            onSave = viewModel::saveConfig,
            onResetDefault = viewModel::resetConfigToDefault,
            onClose = viewModel::closeSettings,
            onTogglePii = viewModel::setPiiActionPermission,
        )
        Phase.NEED_PERMISSION -> {
            // [2026-07-15 UI polish] Bump denial count whenever the
            //  user lands back on the permission screen with the
            //  camera still ungranted.  Each launcher.launch() is a
            //  re-prompt attempt — if the system dialog returns
            //  ungranted and the user lands back here, count++.
            //  Combined with `shouldShowRequestPermissionRationale`,
            //  this gives us the "permanently denied" signal the
            //  single-attempt launcher couldn't surface.
            LaunchedEffect(Unit) {
                if (hasLaunchedOnce && ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionDeniedCount++
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
}

/**
 * [2026-07-15 UI polish] Two states: first-time / shouldShowRationale
 *  asks the user to grant; permanently denied (shouldShowRationale
 *  == false after at least one denial) surfaces a "去系统设置" path
 *  so the user can flip the toggle manually instead of being stuck
 *  in a launcher-dialog loop.  `onOpenSettings` fires
 *  Settings.ACTION_APPLICATION_DETAILS_SETTINGS so the user lands on
 *  this app's permission page directly.
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
            TopOverlay(
                state = state,
                debugEnabled = state.debugEnabled,
                onToggleDebug = { viewModel.setDebugEnabled(!state.debugEnabled) },
                onSettings = viewModel::openSettings,
                onRestart = viewModel::restartScanning,
            )
            // [2026-07-15 UI polish] Surfaced error banner — `AppViewModel`
            //  has been writing `error` for four places since Phase B
            //  (LLM 529 storms, ToolUseLoop throwables, etc.) but no
            //  composable ever read it.  Now it sits between the top
            //  overlay and the rest of the screen, dismissable via
            //  the trailing ✕ (which calls `viewModel.clearError()`
            //  instead of a full restartScanning, so an in-flight
            //  cycle list survives the dismissal).  Padding-top
            //  clears the status-bar-padded TopOverlay below it.
            state.error?.let { msg ->
                ErrorBanner(
                    message = msg,
                    onDismiss = viewModel::clearError,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = 56.dp)
                )
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
                    // [2026-07-14 Phase B] Camera button is always
                    //  enabled when phase == SCANNING.  CycleManager
                    //  caps concurrent cycles at
                    //  UiState.CYCLE_MAX_CONCURRENT = 2; older
                    //  cycles are superseded when a 3rd tap arrives.
                    // [2026-07-15] Pass `analyzing` separately
                    //  so the inner content swaps to a spinner
                    //  when a cycle is in flight (was previously
                    //  the static "识别" text — user couldn't tell
                    //  the cycle was running without scrolling
                    //  down to the InFlightCard).  Button stays
                    //  tappable for the rapid 2-photo case; the
                    //  superseded cycle's LLM is now actually
                    //  cancelled (commit d2bb3e3) so a fast double
                    //  tap doesn't burn API quota.
                    // [2026-07-15] Pass `remaining` so the
                    //  button shows the "还可以拍几张" counter
                    //  (8 - state.cycles.size).  When remaining
                    //  hits 0 the button is grayed + disabled; the
                    //  user must tap "重新扫描" to clear the
                    //  session and start over.
                    enabled = state.phase == Phase.SCANNING,
                    analyzing = state.analyzing,
                    remaining = UiState.CYCLES_MAX_TOTAL - state.cycles.size,
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
 *
 * [2026-07-15 UI polish] Triggers a long-press haptic on tap so the
 * user gets the standard camera-shutter tactile feedback.  Fire on
 * tap (not on press-down) so the haptic aligns with the actual
 * capture; `HapticFeedbackType.LongPress` is the conventional
 * "physical button press" variant across Android camera apps.
 *
 * [2026-07-15 bug fix — "1s 连拍没反馈"]  Previously the inner
 *  branch was keyed on `enabled` alone, but `enabled = phase ==
 *  SCANNING` is true for the entire camera screen session — so
 *  the button always showed the static "识别" text, never a
 *  spinner.  The user couldn't tell that a cycle was in flight
 *  from the shutter alone (they had to look at the InFlightCard
 *  at the bottom).  New: the inner branch is keyed on
 *  `state.analyzing` separately.  The Surface is still tappable
 *  when a cycle is in flight (preserves the "rapid 2-photo"
 *  use case where the user intentionally supersedes), but
 *  the inner content swaps to a spinner so the user can see
 *  "识别中" without scanning down to the bubble card.
 *
 *  [2026-07-15] "还可以拍几个" counter.  The button now shows
 *   the number of cycles the user can still take before
 *   hitting CYCLES_MAX_TOTAL (8).  Starts at 8, decreases by
 *   1 per tap, and when it hits 0 the button visually grays
 *   out (palette.onSurfaceMuted) AND is disabled — the user
 *   must tap "重新扫描" in the top bar to clear the
 *   session and start over.  Analyzing still shows the
 *   spinner (replacing the number) so the user has a
 *   single "this is what's happening" affordance per state. */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    analyzing: Boolean = false,
    remaining: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = IntentCamTheme.palette
    val haptics = LocalHapticFeedback.current
    val noSlots = remaining <= 0
    val finalEnabled = enabled && !noSlots
    val finalColor = if (noSlots) palette.onSurfaceMuted else palette.accentDelegate
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
                // [2026-07-15 a11y] TalkBack reads the counter so
                //  a screen-reader user knows the cap state
                //  ("还可以拍 3 张", "已满").
                .semantics {
                    contentDescription = when {
                        analyzing -> "正在识别"
                        noSlots -> "已满, 需重新扫描"
                        else -> "识别当前画面, 还可以拍 $remaining 张"
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                when {
                    analyzing -> CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                    )
                    noSlots -> Text(
                        "0",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    else -> Text(
                        "$remaining",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
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
    onRestart: () -> Unit,
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
        // [2026-07-15 UI polish] Restart-scanning button.  Drops the
        //  whole bubble history + clears any errored cycle and returns
        //  the user to a clean SCANNING state.  Previously the only
        //  way to clear the bubble list was to swipe-kill the app.
        IconButton(onClick = onRestart) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "重新扫描",
                tint = Color.White,
            )
        }
        // Debug toggle.  Bug icon is green when the scrolling log overlay
        // is active (default), gray when it's muted.  Tap to flip; the
        // preference persists across launches via SettingsStore.
        IconButton(onClick = onToggleDebug) {
            Icon(
                Icons.Filled.Build,
                contentDescription = if (debugEnabled) "关闭调试输出" else "开启调试输出",
                tint = if (debugEnabled) palette.success else palette.onSurfaceMuted.copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
        }
    }
}

/**
 * [2026-07-15 UI polish] Red banner that surfaces [UiState.error] — the
 * value `AppViewModel` has been writing for a while (LLM 529 storms,
 * `ToolUseLoop.Outcome.Error` payloads, exception thrown out of
 * `captureLatestFrame`) but no composable was reading.  Renders a
 * short red strip with the error text and a trailing ✕ that calls
 * [onDismiss] (= `viewModel.clearError()`) without nuking in-flight
 * cycles.
 *
 * Pinned to the top of the screen so it's the first thing the user
 * notices; the bottom Column is left alone so the debug log + shutter
 * + bubbles keep their existing layout.  Wrapped in a translucent
 * dark backdrop so the text stays readable over the live preview.
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
    // [2026-07-13] Cap the bubble list at 50% of the screen height and
    //  scroll any overflow.  Previously the list was a plain Column
    //  with `forEach`, so a long bubble.detail would push later
    //  bubbles off the top of the screen and the user lost history.
    //  Capped height keeps the shutter button visible; the scroll
    //  gives back access to older bubbles.
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
        // [2026-07-14 Phase C — inversion v3.0] Two display modes:
        //   - **Phase B+ mode** (cycles non-empty): iterate every
        //     in-flight [com.example.intentcam.CycleSnapshot] and
        //     react to its `bubble` + `status` flows.  This is the
        //     "live UI" path — chips transition Spinner → Validated
        //     in real time as the cycle's bubble flow updates.
        //   - **Legacy mode** (cycles empty): fall back to
        //     [UiState.bubbles] so the rest of the codebase
        //     (debug overlay, submitUserInput text-input path)
        //     keeps working without an immediate refactor.
        val snapshotCards = state.cycles.values.toList()
        val legacyBubbles = state.bubbles
        if (snapshotCards.isNotEmpty()) {
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
                // [2026-07-15 UI polish] Surface the live cycle count
                //  so the user knows how many captures are still being
                //  tracked.  CYCLE_MAX_CONCURRENT=2 is the cap; when
                //  a 3rd tap arrives the oldest is silently superseded
                //  and a "已替换" pill appears on its card (see
                //  BubbleCard.superseded).  The visible count includes
                //  both still-running and already-superseded cycles.
                Text(
                    "共 ${snapshotCards.size} 张",
                    color = palette.onSurfaceMuted.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // Newest-first so the most-recent capture is at the top —
            // the user just took that photo, so they want to see it
            // first.  Cycles with no bubble yet (PENDING / early
            // IN_FLIGHT) are rendered as a placeholder card so the
            // card list stays stable while the LLM is thinking.
            snapshotCards
                .sortedByDescending { it.capturedAtMs }
                .forEach { snap ->
                    val bubble by snap.bubble.collectAsState()
                    val status by snap.status.collectAsState()
                    if (bubble == null) {
                        // Placeholder: the cycle is alive but the LLM
                        // hasn't emitted yet.  Renders a small
                        // card.  The status branch (SUPERSEDED /
                        // ERRORED / IN_FLIGHT) is handled inside
                        // InFlightCard — see its docstring.
                        InFlightCard(
                            capturedAtMs = snap.capturedAtMs,
                            cycleStatus = status,
                        )
                    } else {
                        // [Compose] Smart-cast doesn't work on delegated
                        //  properties — `bubble!!` would also work but
                        //  re-wraps a non-null Bubble; the explicit
                        //  `b` val keeps the call sites tidy.
                        val b = bubble!!
                        val actionDefs = b.actions.mapNotNull {
                            viewModel.actionRegistry.get(it)
                        }
                        BubbleCard(
                            bubble = b,
                            cycleStatus = status,
                            onPick = onPick,
                            actionDefs = actionDefs,
                            onActionTap = { actionId -> viewModel.runAction(actionId, b.id) },
                            accent = bubbleAccentActions(b, palette, viewModel.actionRegistry),
                        )
                    }
                }
        } else if (legacyBubbles.isNotEmpty()) {
            Text(
                "识别到的意图（点击查看详情）",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            legacyBubbles.forEach { bubble ->
                val actionDefs = bubble.actions.mapNotNull {
                    viewModel.actionRegistry.get(it)
                }
                BubbleCard(
                    bubble = bubble,
                    cycleStatus = JobStatus.COMPLETE,
                    onPick = onPick,
                    actionDefs = actionDefs,
                    onActionTap = { actionId -> viewModel.runAction(actionId, bubble.id) },
                    accent = bubbleAccentActions(bubble, palette, viewModel.actionRegistry),
                )
            }
        }
    }
}

/** [2026-07-14 Phase C] Placeholder card rendered for a CycleJob
 *  whose LLM hasn't emitted its first bubble yet (PENDING or
 *  early IN_FLIGHT).  Shows the captured timestamp + a small
 *  spinner so the user knows the capture is in flight and the
 *  cycle hasn't died.  When the bubble flow finally emits, the
 *  placeholder is swapped for a real [BubbleCard].
 *
 *  [2026-07-15 UI polish] `ageSec` previously froze at the value
 *  captured at first composition — re-renders only fire on
 *  `capturedAtMs` change (which never happens for a single
 *  in-flight cycle), so the count stayed at 0s forever.  Now
 *  driven by a 1Hz `LaunchedEffect` that bumps a state Int;
 *  caps at 99s so a long-waiting cycle doesn't churn digits.
 *
 *  [2026-07-15 bug fix — "in-flight 灰卡像卡死"]  Color was
 *  `Color.White.copy(alpha = 0.40f)` on a black camera
 *  background — that renders as light gray, visually a totally
 *  different shape from a [BubbleCard] (which uses dark
 *  `palette.surface`).  Users saw the InFlightCard next to a
 *  fresh BubbleCard and assumed the InFlightCard was a broken /
 *  dimmed BubbleCard that "stuck" at the loading state.  New
 *  color is `palette.surface.copy(alpha = 0.6f)` — same family
 *  as BubbleCard, just dimmer, so the card reads as "bubble
 *  ghost, still loading" instead of "stray gray panel".
 *
 *  Also takes [cycleStatus] so a SUPERSEDED cycle whose bubble
 *  hasn't emitted yet shows "已替换, 等待识别完成" instead of
 *  the active spinner.  A SUPERSEDED cycle is dead from the
 *  UI's POV (its result will never reach the user); a still-
 *  spinning spinner on it implies the user is waiting for a
 *  result that won't come.  The LLM may still be working in
 *  the background, but the user can't act on that, so we drop
 *  the spinner and dim the card. */
@Composable
private fun InFlightCard(capturedAtMs: Long, cycleStatus: JobStatus = JobStatus.IN_FLIGHT) {
    var ageSec by remember(capturedAtMs) {
        mutableStateOf(((System.currentTimeMillis() - capturedAtMs) / 1000).toInt())
    }
    LaunchedEffect(capturedAtMs) {
        while (true) {
            delay(1000L)
            ageSec = ((System.currentTimeMillis() - capturedAtMs) / 1000)
                .toInt()
                .coerceAtMost(99)
        }
    }
    val palette = IntentCamTheme.palette
    val isSuperseded = cycleStatus == JobStatus.SUPERSEDED
    val isErrored = cycleStatus == JobStatus.ERRORED
    val descriptionText = when {
        isSuperseded -> "已替换, 等待识别完成"
        isErrored -> "识别超时, 请再拍一张"
        else -> "正在识别, 已等待 ${ageSec} 秒"
    }
    Surface(
        color = palette.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isSuperseded) 0.45f else 1f)
            // [2026-07-15 a11y] TalkBack announces the cycle
            //  state alongside the wait time so a screen-reader
            //  user knows whether the cycle is still active,
            //  superseded, or errored.
            .semantics {
                contentDescription = descriptionText
            },
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSuperseded) {
                // No spinner — the cycle is dead from the UI's
                //  POV.  A spinning indicator would imply the
                //  user is still waiting for a result.
                Box(
                    Modifier
                        .size(20.dp)
                        .background(
                            palette.onSurfaceMuted.copy(alpha = 0.6f),
                            CircleShape,
                        ),
                )
            } else if (isErrored) {
                // Errored: a static warning icon.  Replaces the
                //  spinner so the user knows the cycle is dead
                //  and the result won't arrive.
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Close,
                    contentDescription = null,
                    tint = palette.danger,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = palette.accentDelegate,
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                when {
                    isSuperseded -> "已替换, 等待识别完成"
                    isErrored -> "识别超时, 请再拍一张"
                    else -> "识别中… ${ageSec}s"
                },
                color = when {
                    isSuperseded -> palette.onSurfaceMuted.copy(alpha = 0.6f)
                    isErrored -> palette.danger
                    else -> palette.onSurface
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BubbleCard(
    bubble: Bubble,
    cycleStatus: JobStatus,
    onPick: (Bubble) -> Unit,
    actionDefs: List<ActionDef> = emptyList(),
    onActionTap: (actionId: String) -> Unit = {},
    accent: Color,
) {
    // [2026-07-15 UI polish] A cycle that's been SUPERSEDED by a
    //  newer shutter tap is still in the snapshot list (capped at
    //  CYCLE_MAX_CONCURRENT=2) but its card is no longer the user's
    //  focus.  Dim the card and prepend a small "已替换" pill so
    //  the user understands why an old capture faded out — the
    //  previous version just silently dropped the oldest cycle
    //  from `allJobs` (CycleManager.kt:81-90) with no UI signal.
    val superseded = cycleStatus == JobStatus.SUPERSEDED
    val palette = IntentCamTheme.palette
    val thumbnail = remember(bubble.imageBytes) {
        // Decode on a worker thread; the result bitmap is cached for the
        // composition's lifetime so re-emits are cheap.  Downscaled to a
        // card-sized bitmap — no point decoding a 3200 px thumbnail full
        // for a 56 dp preview.
        decodeScaled(bubble.imageBytes, DecodedSize.Thumbnail)
    }
    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (superseded) 0.45f else 1f)
            // [2026-07-15 a11y] mergeDescendants so TalkBack
            //  reads the card as one announcement: "识别结果：
            //  <title>。置信度 N%。M 个可执行操作" instead of
            //  reading every child (title, type, detail, chips)
            //  separately.
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("识别结果:")
                    if (bubble.title.isNotBlank()) append(" ").append(bubble.title)
                    append("。置信度 ").append((bubble.confidence * 100).toInt()).append("%")
                    if (actionDefs.isNotEmpty()) {
                        append("。").append(actionDefs.size).append(" 个可执行操作")
                    }
                    if (superseded) append("。已替换")
                }
            },
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
                if (superseded) {
                    // Small pill above the title so the dimmed card
                    //  is at-a-glance labelled.
                    Text(
                        "已替换",
                        color = palette.onSurfaceMuted.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bubble.title.isNotBlank()) {
                        // [2026-07-15 UI polish] `fill = true` so the
                        //  title takes the full remaining width and
                        //  ellipsizes cleanly when next to a wide
                        //  IntentChip.  The previous `fill = false`
                        //  caused a 14-character title to truncate
                        //  prematurely when an `Open in Maps` chip
                        //  was present.
                        Text(
                            bubble.title,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = true),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val chipLabel = bubble.type
                    if (chipLabel.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        IntentChip(label = chipLabel, accent = accent)
                    }
                }
                if (bubble.detail.isNotBlank()) {
                    Text(
                        bubble.detail,
                        color = if (bubble.title.isBlank()) palette.onSurface
                               else palette.onSurfaceMuted,
                        fontWeight = if (bubble.title.isBlank()) FontWeight.Normal
                                     else FontWeight.Normal,
                        style = if (bubble.title.isBlank())
                                    MaterialTheme.typography.bodyMedium
                                 else MaterialTheme.typography.bodySmall,
                        maxLines = if (bubble.title.isBlank()) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (bubble.needsUserInput) {
                    Text(
                        "需要补充信息",
                        color = palette.warning,
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
                            val chipState = resolveChipState(bubble, def, cycleStatus)
                            ActionChip(
                                label = def.label,
                                state = chipState,
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
 *
 * [2026-07-14 Phase C] [state] drives the chip's color + tappability:
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
    // [2026-07-15 UI polish] Animate the background / foreground
    //  color when the chip transitions between Validated / Ghost
    //  / Spinner.  The previous version used `val bg = when (...)`
    //  so a single render-time change snapped the color
    //  instantly; now `animateColorAsState` interpolates over
    //  300ms so the Spinner → Validated transition (the most
    //  common case — happens as the orchestrator finishes its
    //  validateInputs pass) is visible as a fade rather than
    //  a flicker.  Hidden case stays instant since the chip
    //  disappears entirely.
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
        // [2026-07-15 a11y] `stateDescription` lets TalkBack
        //  announce the chip's current state separately from
        //  its label, so a screen-reader user hears
        //  "拨号, 需要补充信息" instead of just "拨号" for
        //  a ghost chip.
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
 * [2026-07-15 UI polish] Named presets for [decodeScaled].  The
 * raw `targetMaxDim: Int` argument is fine when there's only one
 * call site, but with two (BubbleCard thumbnail at 400px and
 * DetailScreen full at 1600px) the magic numbers were
 * collision-prone — easy to write 400 at the detail site and
 * ship a 400-pixel-wide "full" image.  A preset enum gives the
 * call site a name (`decodeScaled(bytes, DecodedSize.Full)`)
 * that's self-documenting and greppable.
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
    var sample = 1
    while (longest / (sample * 2) >= targetMaxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}

/**
 * [2026-07-15 v4 Step 3 — actions-driven accent] Resolves a bubble's
 * accent color from its [Bubble.actions] list (the canonical post-
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
    val fullImage = remember(bubble.imageBytes) {
        // The bubble carries the ~3200 px display thumbnail; decode it
        // downscaled to ~1600 px — plenty for a Fit view on a phone
        // screen, and ~1/4 the ARGB footprint of a full decode.
        decodeScaled(bubble.imageBytes, DecodedSize.Full)
    }
    val palette = IntentCamTheme.palette
    val accent = bubbleAccentActions(bubble, palette, actionRegistry)
    // [2026-07-13] fullscreen redesign: the image fills the entire screen
    // (ContentScale.Fit, black letterbox) so the user can see it clearly
    // and cross-check it against the results.  The result panel and the
    // 退出 button float on top of the image as a bottom overlay.  The panel
    // is a tap-to-collapse sheet — collapsed gives the image the whole
    // screen; expanded shows the (scrollable, half-screen-capped,
    // semi-transparent) content over the lower part of the image.
    //
    // [2026-07-15 UI polish] `textExpanded` is now a
    //  `rememberSaveable` so collapsing survives rotation, and the
    //  initial value is derived from the bubble's text length: a
    //  short bubble (title + 1-line detail) starts collapsed so the
    //  image gets the whole screen; a long one starts expanded so
    //  the user can immediately scroll the table.  Hardcoded
    //  `= true` (the previous default) forced a 30-char bubble to
    //  the bottom-sheet state where the user had to tap the chevron
    //  to see the photo.
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
                // [2026-07-15 UI polish] Respect the status-bar inset
                //  on the overlay.  The fullscreen image below keeps
                //  its `fillMaxSize()` so the user sees the full
                //  photo; only the overlay (which contains text the
                //  user needs to read) clears the status bar.  Without
                //  this, devices with a display cutout / dynamic island
                //  would have the title row drawn under the camera
                //  notch.
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
                    // [2026-07-10] Action chips in the detail panel —
                    //  same source of truth as the bubble card; the
                    //  caller passes the resolved [ActionDef]s and
                    //  the tap handler.  Empty list → no row, no
                    //  visual change.
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
                                val chipState = resolveChipState(bubble, def, JobStatus.COMPLETE)
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
            // [2026-07-15 UI polish] 退出 button — moved out of the
            //  bottom-anchored column into a top-right floating
            //  IconButton overlay.  The previous layout stacked the
            //  full-width 退出 button below the sheet inside the
            //  bottom Column; on short screens (e.g. 5" phones,
            //  landscape gestures) the half-screen sheet + button
            //  could push the title row off the top OR overlap the
            //  退出 button.  A top-right X follows the conventional
            //  fullscreen-media dismiss pattern (photos, videos)
            //  and never conflicts with the sheet content.
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
                .size(40.dp),
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
 * [2026-07-15 UI polish] Internal helper that owns the
 * auto-scroll-to-bottom-on-append behavior shared by
 * [DebugLogPanel] and [AnalyzerErrorPanel].  Both panels
 * duplicate the `rememberLazyListState` + `LaunchedEffect`
 * "snap to last when near the bottom" dance; this composable
 * packages it once.  Style (background, border, header) stays
 * at the caller — only the scrolling logic moves.
 */
@Composable
private fun LogList(
    logs: List<DebugLogEntry>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            // Snap to the newest entry when the user is already at
            //  (or near) the bottom; preserve the scroll position
            //  when they've scrolled up to read history.
            if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= logs.size - 2) {
                listState.scrollToItem(logs.size - 1)
            }
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
 * [2026-07-15 UI polish] Collapsible: a single-row header
 *  ("调试日志 (N) ▾ / ▴") by default, expanding to the full
 *  200dp log on tap.  Saves ~160dp of vertical space on
 *  small phones where the log was squeezing the shutter /
 *  bubbles.  State is component-local (not rememberSaveable) —
 *  the panel re-collapses on app restart, which is the right
 *  default for a debug tool.
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
    var text by remember(request) { mutableStateOf("") }
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