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
            // overlay and an 退出 button to dismiss.  The camera preview
            // is hidden here so the user sees the still image they tapped on,
            // not a live feed.
            DetailScreen(
                bubble = state.selectedBubble,
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
                Text(
                    bubble.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                if (bubble.detail.isNotBlank()) {
                    Text(
                        bubble.detail,
                        color = Color(0xFFB9C4DE),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
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
            if (bubble.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    bubble.detail,
                    color = Color(0xFFB9C4DE),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        // 退出 button at the bottom — dismisses the detail view; the
        // main loop restarts from step 1 and starts a fresh stability
        // counter.
        Button(
            onClick = onRestart,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            Text("退出")
        }
    }
}

// Legacy answer/answering composables removed: the new flow is
// BubbleCard (thumbnail + title + detail) and DetailScreen (full image
// + title + detail + 退出 button).  The answer-flow composables that
// lived here are gone.
