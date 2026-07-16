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
import androidx.compose.material.icons.filled.Build
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
    //
    // [2026-07-16 P3 fix] `permissionDeniedOnce: Boolean` instead
    //  of the previous `permissionDeniedCount: Int`.  The only
    //  read site checks `> 0`; the unbounded counter was
    //  incidental state with no semantic value (every visit to
    //  NEED_PERMISSION incremented forever, never reset).  The
    //  boolean captures the same signal ("has the user denied
    //  at least once") and never grows.
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
            // [2026-07-15 UI polish] Mark "denied at least once"
            //  whenever the user lands back on the permission
            //  screen with the camera still ungranted.  Combined
            //  with `shouldShowRequestPermissionRationale`, this
            //  gives us the "permanently denied" signal the
            //  single-attempt launcher couldn't surface.  [2026-07-16
            //  P3] replaced the unbounded count++ with a boolean
            //  flip — see AppRoot above for rationale.
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
    val busy by viewModel.busy.collectAsState()
    // [2026-07-15 P1 removal] The TopOverlay "重新扫描" button +
    //  confirmation dialog are gone.  With the active-cycle
    //  counter (`activeCycleCount` + `CYCLE_MAX_CONCURRENT`),
    //  the shutter auto-releases as cycles complete — the user
    //  never needs to manually reset to take more photos.  For
    //  a stuck cycle (LLM hang), the 90s `llmTimeoutMs` budget
    //  marks it ERRORED and frees the slot on its own.
    //
    //  `restartScanning()` itself is kept as an internal method
    //  because `closeSettings()` calls it — when the user
    //  changes LLM config and comes back to the camera, a clean
    //  state (no in-flight cycles holding the old config) is
    //  the right behavior.  That path is implicit and doesn't
    //  need a UI affordance.
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
            // [2026-07-15 P2 fix] Stack TopOverlay + ErrorBanner in a
            //  Column so the banner sits directly below the
            //  overlay without a magic-number `padding-top`
            //  offset.  Previous version hardcoded 56dp to
            //  approximate the overlay's rendered height —
            //  fragile when the overlay grew to a multi-line
            //  scene text on small phones (status-bar + 2 lines
            //  of labelLarge easily exceeds 56dp).  The Column
            //  reports its actual measured height, so the
            //  banner always lands flush against the overlay
            //  regardless of content / font scale / notch.
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                TopOverlay(
                    state = state,
                    debugEnabled = state.debugEnabled,
                    onToggleDebug = { viewModel.setDebugEnabled(!state.debugEnabled) },
                    onSettings = viewModel::openSettings,
                )
                // [2026-07-15 UI polish] Surfaced error banner —
                //  `AppViewModel` has been writing `error` for
                //  four places since Phase B (LLM 529 storms,
                //  ToolUseLoop throwables, etc.) but no
                //  composable ever read it.  Now it sits
                //  directly below the TopOverlay (in the
                //  Column above), dismissable via the trailing
                //  ✕ (which calls `viewModel.clearError()`
                //  instead of a full restartScanning, so an
                //  in-flight cycle list survives the
                //  dismissal).
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
                    // [2026-07-14 Phase B] Camera button is always
                    //  enabled when phase == SCANNING.  CycleManager
                    //  queues captures up to UiState.CYCLE_QUEUE_DEPTH
                    //  (queued+in-flight) and processes them
                    //  CYCLE_CONCURRENCY-at-a-time; the shutter dims
                    //  when the queue is full.
                    // The counter is `CYCLE_QUEUE_DEPTH - activeCycleCount`,
                    //  where activeCycleCount = queued + in-flight (PENDING +
                    //  IN_FLIGHT).  COMPLETE / ERRORED / SUPERSEDED
                    //  bubbles don't count, so the counter ticks back
                    //  up the instant a cycle finishes (the "释放出
                    //  一个" semantics).  The total-map cap
                    //  (CYCLES_MAX_TOTAL) is enforced inside
                    //  CycleManager via terminal-only FIFO eviction —
                    //  a memory-protection mechanism, not a user gate.
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
        // [2026-07-15 P1 removal] Restart-confirmation dialog
        //  removed.  See the comment at the top of
        //  CameraScreen for the rationale: the active-cycle
        //  counter auto-releases the shutter as cycles
        //  complete, so a manual "wipe everything" affordance
        //  has no remaining UX purpose.  Power-user reset
        //  (clearing bubble history + aborting in-flight
        //  cycles after a config change) is handled implicitly
        //  by `closeSettings()` → `restartScanning()`.
    }
}

/**
 * Large round shutter button.  Disabled outside of SCANNING so we
 * don't dispatch while the user is reading a bubble detail.
 *
 * [2026-07-15 UI polish] Triggers a long-press haptic on tap so the
 * user gets the standard camera-shutter tactile feedback.  Fire on
 * tap (not on press-down) so the haptic aligns with the actual
 * capture; `HapticFeedbackType.LongPress` is the conventional
 * "physical button press" variant across Android camera apps.
 *
 * [2026-07-16 P2 fix — "转圈动画遮住 cycle 计数"]  Previously the
 *  inner content swapped to a 28dp `CircularProgressIndicator`
 *  while a cycle was in flight, completely hiding the remaining-
 *  cycle count number — users couldn't tell "how many more
 *  photos can I take" without waiting for the cycle to finish
 *  and the number to reappear.  Tried as a thin progress RING
 *  around the button edge in the intermediate fix; **dropped
 *  entirely** in [2026-07-16 P3] because the ring was a third
 *  redundant "in flight" signal on top of (1) TopOverlay's
 *  "识别中…" + 14dp spinner at the top of the screen, and (2)
 *  `BubbleCard`'s own spinners (in both the title row and the
 *  trailing confidence slot) for every active cycle at the
 *  bottom of the screen.  Three simultaneous spinning widgets
 *  was visual noise; one (the per-cycle BubbleCard spinner) is
 *  enough because each active cycle's card already tells the
 *  user "this one is being processed".  The shutter button's
 *  sole job is the remaining-cycle count, which is now
 *  unconditionally visible whenever `noSlots` is false (the
 *  user explicitly called out that the count should not be
 *  obscured).  When noSlots, the number ("0") dims + disables
 *  as before.
 *
 *  [2026-07-15 P1 fix] "还可以拍几个" counter.  The button
 *   shows the number of cycles the user can still take before
 *   hitting CYCLE_QUEUE_DEPTH (8 queued+in-flight cycles).  Starts
 *   at 8, decreases by 1 per tap, and when it hits 0 the
 *   button visually DIMS (palette.surfaceMuted background +
 *   onSurfaceMuted foreground) AND is disabled — the user
 *   waits for at least one in-flight cycle to complete
 *   (the active counter auto-decrements; see the
 *   [UiState.activeCycleCount] docstring).
 *   The [busy] parameter is still consumed by the
 *   `contentDescription` so TalkBack announces the in-flight
 *   state, but no visual ring is rendered. */
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
                // The number is ALWAYS visible when the button has
                // slots — including while a cycle is in flight.
                // [2026-07-16 P2 fix] for the "转圈动画遮住
                // cycle 计数" bug; [2026-07-16 P3] dropped the
                // alternative thin-ring solution in favor of
                // pure-text rendering because the ring was
                // redundant with BubbleCard's spinner.
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

    // [2026-07-16 P0 fix] `unbindAll()` in DisposableEffect.onDispose
    //  instead of relying on `bindToLifecycle`'s implicit unbind.
    //  Repro: photo → tap bubble → enter detail → tap 退出 → camera
    //  preview stays black, ErrorBanner shows "相机初始化失败: No
    //  supported surface combination is found".
    //
    //  Cause: bindToLifecycle binds use cases to the ACTIVITY
    //  lifecycle, not to the CameraPreview composable's lifecycle.
    //  When the user enters DetailScreen, CameraPreview leaves
    //  composition (AndroidView disposed, PreviewView detached from
    //  the window) but the use cases (Preview#1, Analysis#1)
    //  remain bound to the activity.  When the user returns,
    //  CameraPreview re-enters composition; the AndroidView
    //  factory creates Preview#2, Analysis#2 and calls
    //  bindToLifecycle again.  bindToLifecycle SHOULD unbind the
    //  old use cases internally, but in practice there's a
    //  transient window where Preview#1's surface provider (now
    //  pointing at a detached PreviewView) and Preview#2's surface
    //  provider (pointing at the new PreviewView) both try to
    //  claim the camera's output, and the camera configuration
    //  fails — "No supported surface combination is found".
    //
    //  Fix: explicitly unbindAll() when CameraPreview leaves
    //  composition (DisposableEffect.onDispose) so the camera
    //  resource is fully released before the new bind.  The cost
    //  is the same ~100-300ms black-frame flicker on
    //  navigation that the [2026-07-15 P2] comment warned
    //  about — accepted trade-off, because the broken-camera
    //  bug is much worse than the flicker.  On re-entry the
    //  factory binds fresh use cases against an empty provider
    //  state, which succeeds every time.
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
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                    // [2026-07-15 P0 fix] Previously this catch was
                    //  empty (`} catch (_: Exception) {}`), silently
                    //  swallowing CameraX bind failures (camera in
                    //  use by another app, HAL fault, mid-launch
                    //  permission revoke).
                    //
                    //  [2026-07-16 P2 fix] Drop the
                    //   `logAnalyzerError` call — that buffer
                    //   powers the "FrameAnalyzer errors" panel,
                    //   which is meant for `FrameAnalyzer.analyze
                    //   ()` exceptions (OOM mid-decode, image
                    //   format failures, etc.), NOT for one-time
                    //   CameraX init/bind failures.  Mixing the
                    //   two made the panel render "[ANALYZER]
                    //   CameraX bind failed: ..." entries that
                    //   users mistook for per-frame analysis
                    //   crashes, especially after a single shutter
                    //   tap that triggered a transient bind issue
                    //   earlier in the session.  The init error
                    //   now surfaces through two channels:
                    //   (1) `state.error` → ErrorBanner at the
                    //   top of the screen (user-facing,
                    //   dismissable), and (2) `android.util.Log.w`
                    //   to logcat so devs running `adb logcat` see
                    //   the same stack trace FrameAnalyzer logs
                    //   for its own swallowed exceptions.  Both
                    //   are the right audiences for "the camera
                    //   failed to start".
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
            // [2026-07-16 P0 fix] Unbind on dispose so the next
            //  CameraScreen re-entry gets a clean slate.  See the
            //  block-level comment at the top of CameraPreview
            //  for the full repro + rationale.
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
    debugEnabled: Boolean,
    onToggleDebug: () -> Unit,
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
        // [2026-07-15 P1 removal] Restart-scanning button
        //  removed.  See the comment at the top of
        //  CameraScreen: the active-cycle counter auto-
        //  releases the shutter as cycles complete, so a
        //  manual "wipe everything" affordance has no
        //  remaining UX purpose.  Stuck cycles are bounded
        //  by the 90s `llmTimeoutMs` in CycleManager.
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
 * [2026-07-15 P1 fix — "shutter 后 bubble 框跳动"] Pre-fix the UI
 *  routed to a smaller `InFlightCard` while `bubble == null`,
 *  then swapped to a wider `BubbleCard` when the bubble arrived.
 *  Every cycle produced a visible layout jump.  With the unified
 *  shape the card height is stable from shutter-tap onward;
 *  only inner-column content + the trailing confidence slot
 *  change as the cycle progresses.
 *
 * [2026-07-15 P1 fix — "读秒没有意义"] Removed the 1Hz "已等待 N 秒"
 *  counter that lived in the previous InFlightCard.
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
    // [2026-07-16 producer/consumer split] A PENDING cycle is
    //  waiting in the pending queue for a free worker slot — it is
    //  NOT yet processing.  Render it distinctly ("排队中…" + a
    //  muted static dot) so the user can tell "waiting its turn"
    //  from "actively being recognized" (IN_FLIGHT → "识别中…" +
    //  live spinner).
    val isQueued = isLoading && cycleStatus == JobStatus.PENDING
    val isSuperseded = cycleStatus == JobStatus.SUPERSEDED
    val titleText = when {
        isErrored -> "识别超时, 请再拍一张"
        isQueued -> "排队中…"
        isLoading -> "识别中…"
        else -> bubble?.title?.ifBlank { "未命名" } ?: "未命名"
    }
    val palette = IntentCamTheme.palette
    // [2026-07-16 P0 fix] Replace `produceState + DisposableEffect`
    //  with an explicit `LaunchedEffect` that decodes the new
    //  thumbnail BEFORE recycling the previous bitmap.  The old
    //  pattern had a re-entrancy race: when `thumbnail` changed
    //  (cycle progress update swapped `bubble.imageBytes`),
    //  `produceState` ran first and wrote the NEW bitmap into
    //  `displayBitmap`; then `DisposableEffect`'s key change fired
    //  `onDispose`, which read `displayBitmap` — the NEW bitmap —
    //  and recycled it.  The `Image` composable then rendered a
    //  recycled bitmap (black/garbage, occasional
    //  IllegalStateException on access).  Holding the previous
    //  bitmap in a local `prev` and only recycling after the new
    //  decode completes makes the swap atomic from the renderer's
    //  POV: `displayBitmap` is replaced in a single
    //  read-modify-write, and the old bitmap is freed after.
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
        // [2026-07-15 P1 fix] Disable tap while loading — there's
        //  no bubble to show in detail.  Material3's clickable
        //  Surface takes a non-nullable onClick so we pass a
        //  no-op lambda and gate via `enabled`; when disabled
        //  the ripple + click target are removed by the framework.
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
                    // [2026-07-16 P2 UI polish] Drop the
                    //  14dp spinner that lived here next to the
                    //  title.  The trailing 20dp spinner below
                    //  (the one that occupies the confidence % slot
                    //  when loading) already conveys the same
                    //  in-flight state, so this one was a duplicate
                    //  small spinner stacked against the big one —
                    //  user reported it as "一大一小" visual
                    //  noise.  When the cycle finishes and bubble
                    //  is non-null, render the IntentChip here in
                    //  the title row as before.
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
                        // [2026-07-16 P3 fix] Switch the remember
                        //  key from `actionDefs.map { it.id }`
                        //  (a fresh List every recomposition, so
                        //  the cache always misses and
                        //  `resolveChipState` re-runs for every
                        //  chip on every recomposition) to a
                        //  size + first/last ref fingerprint.
                        //  Distinct action sets with the same
                        //  (size, firstId, lastId) is vanishingly
                        //  rare in practice and the chip mapper
                        //  is cheap, so an occasional false hit
                        //  costs less than per-recomp work.
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
                        // [2026-07-16 P3 fix] Skip Hidden chips
                        //  at the call site so they don't reserve
                        //  horizontal space inside the scrolling
                        //  row.  Previous version rendered every
                        //  def with `state ?: Hidden`; Hidden's
                        //  bg/fg are transparent but the Row
                        //  still laid out the slot, leaving a
                        //  visible gap that other chips couldn't
                        //  collapse into.  Matches the
                        //  `ChipState.Hidden` docstring
                        //  contract ("not rendered").
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
                // [2026-07-16] Queued (waiting for a worker) → a
                //  muted static dot so it reads as "parked, not
                //  spinning".  Distinct from the active spinner the
                //  IN_FLIGHT branch below shows.  A plain Box dot
                //  avoids any Material3-version-specific determinate
                //  CircularProgressIndicator overload.
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
    // [2026-07-15 P2 fix] Power-of-2 inSampleSize overshoots
    //  by up to 2x for non-power-of-2 sources — a 4000 px
    //  source with a 1600 px target decodes to 2000 px
    //  (~1.5 MB ARGB) when we'd prefer 1600 px (~1 MB).
    //  Apply a secondary exact scale so the decoded bitmap's
    //  longest side matches targetMaxDim exactly.  Bilinear
    //  filter is fine — both surfaces render the bitmap at
    //  its native resolution, the scale is purely a memory
    //  optimization.  createScaledBitmap returns the source
    //  bitmap unchanged when dimensions already match (no
    //  allocation), so the recycle guard is a no-op in that
    //  case.
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
    // [2026-07-15 P1 fix] Same async-decode pattern as
    //  BubbleCard's thumbnail.  DetailScreen enters with a
    //  full-resolution bitmap (~1600 px → ~10 MB ARGB) which
    //  used to decode synchronously on the UI thread for
    //  ~100-300 ms — visible jank on the "tap to view" tap.
    //  produceState fires the decode on Dispatchers.IO and the
    //  Image composable renders nothing until it's ready (the
    //  Box's black background stands in).
    // [2026-07-16 P0 fix] Same `LaunchedEffect`-managed swap as
    //  BubbleCard above — the old `produceState + DisposableEffect`
    //  pattern recycled the NEW bitmap because `onDispose` ran
    //  AFTER `produceState` had already replaced the value with the
    //  freshly decoded one.  Hold `prev` locally, decode the next
    //  on IO, swap atomically into `fullImage`, then recycle `prev`
    //  only if it's not the same reference and not yet recycled.
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
            // [2026-07-15 P1 fix] Local val snapshot for
            //  smart-cast — `fullImage` is a delegated
            //  property (produceState).  See BubbleCard for
            //  the same pattern.  `!!` because we've null-
            //  checked but the compiler can't propagate
            //  through the assignment.
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
                                // [2026-07-16 P3 fix] Skip Hidden
                                //  chips here too — DetailScreen
                                //  previously rendered them as
                                //  invisible-but-spacing chips in
                                //  the FlowRow, matching the
                                //  BubbleCard bug.
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
                // [2026-07-15 P1 fix] 40dp → 48dp per Material
                //  Design's minimum touch target.  The 40dp version
                //  was hard to hit on small (5") phones, especially
                //  when holding the device one-handed.
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
    // [2026-07-15 P1 fix] Track whether the user has manually
    //  scrolled away from the bottom.  When `true`, new log
    //  entries don't snap-scroll (so the user isn't yanked
    //  away from history they're reading).  Resets to `false`
    //  when the user scrolls back to the bottom.  Previous
    //  heuristic (`lastVisible >= logs.size - 2`) yanks users
    //  in small panels — a 120dp AnalyzerErrorPanel with 3-4
    //  visible rows treats every "second-to-last" position as
    //  "near bottom" and scrolls the user away from item
    //  they're reading.
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
                    // [2026-07-15 P1 fix] Wire the IME's Enter
                    //  key to the confirm button so users can
                    //  submit without dismissing the keyboard
                    //  first.  Previous version had no imeAction
                    //  → keyboard's "Done" / "Send" button did
                    //  nothing.  Reuses the same
                    //  onSubmit-if-not-blank guard as the
                    //  TextButton click handler.
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
    // [2026-07-16 P3 fix] Clamp to CameraX ImageAnalysis's
    //  hard upper bound (~4096 on the long side; see
    //  ImageAnalysis.Builder.setTargetResolution javadoc —
    //  sizes above this get silently downscaled by the
    //  pipeline, wasting an ARGB_8888 YUV read + YUV→RGBA
    //  conversion + downscale for nothing).  Without this,
    //  a 50 MP sensor (Samsung 8160×6120, Huawei 8192×6144)
    //  tells us "use 8160×6120", we oblige, and CameraX
    //  internally downscales — same final image quality as
    //  asking for 4096×3072 directly, but ~3× the per-frame
    //  allocation cost.  Preserves the 4:3 aspect ratio by
    //  scaling both dimensions proportionally.
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