package com.example.intentcam

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.graphics.Color
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
        // Live preview is ALWAYS on screen.  When the camera analyzer sees
        // ≥ 1 s of stability, the captured JPEG is sent through OCR + object
        // detection + multi-round LLM as a background pipeline; the UI never
        // freezes.  Result bubbles slide in at the bottom when the cycle
        // completes.
        CameraPreview(viewModel)

        // Top bar: scene text + analysing indicator + settings.
        TopOverlay(state = state, onSettings = viewModel::openSettings)

        // Bottom content depends on phase.
        when (state.phase) {
            Phase.SCANNING -> IntentBubbles(
                state = state,
                onPick = viewModel::selectIntent,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            Phase.ANSWERING -> AnsweringPanel(
                state.selected?.title ?: "",
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            Phase.ANSWER -> AnswerPanel(
                state = state,
                onRestart = viewModel::restartScanning,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            else -> Unit
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
                Text(
                    text = state.scene.ifBlank { "对准物体，正在识别你的意图…" },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2
                )
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
private fun IntentBubbles(state: UiState, onPick: (IntentItem) -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        state.error?.let {
            Text("⚠ $it", color = Color(0xFFFFB4A9), style = MaterialTheme.typography.labelSmall)
        }
        if (state.intents.isNotEmpty()) {
            Text(
                "识别到的意图（点击选择）",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        // Top few intents as tappable bubbles.
        state.intents.take(4).forEach { intent ->
            IntentBubble(intent, onPick)
        }
    }
}

@Composable
private fun IntentBubble(intent: IntentItem, onPick: (IntentItem) -> Unit) {
    val accent = when (intent.type) {
        "location" -> Color(0xFF37D399)
        "solve" -> Color(0xFFFFAF54)
        else -> Color(0xFF4F8CFF)
    }
    Surface(
        color = Color(0xE6161C2E),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = { onPick(intent) }
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(accent, RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(intent.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                if (intent.detail.isNotBlank()) {
                    Text(
                        intent.detail,
                        color = Color(0xFFB9C4DE),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                }
            }
            Text(
                "${(intent.confidence * 100).toInt()}%",
                color = accent,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun AnsweringPanel(title: String, modifier: Modifier) {
    Surface(
        color = Color(0xE6161C2E),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding()
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(16.dp))
            Text("正在处理：$title", color = Color.White)
        }
    }
}

@Composable
private fun AnswerPanel(state: UiState, onRestart: () -> Unit, modifier: Modifier) {
    Surface(
        color = Color(0xF2161C2E),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .padding(16.dp)
            .navigationBarsPadding()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                state.selected?.title ?: "结果",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                state.error?.let {
                    Text("⚠ $it", color = Color(0xFFFFB4A9))
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    state.answer.ifBlank { if (state.error == null) "（无内容）" else "" },
                    color = Color(0xFFE7ECF7)
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                Text("重新扫描")
            }
        }
    }
}
