# IntentCam

Camera-based intent recognition Android app. The camera analyzer detects a
stable frame, captures it, and feeds it through:

1. **On-device ML Kit OCR** (Latin + Chinese, bundled models) — text ground truth
2. **On-device ML Kit object detection** (bundled model, classification on) — object ground truth
3. **LLM 3-round protocol** (BROAD → VERIFY → DECIDE) over an Anthropic-compatible
   endpoint — the actual intent inference, with CoT-forced scene description
4. **Streaming SSE** so the user sees partial answers while the model is still
   generating

The user picks one of the candidate intent bubbles, the captured JPEG is sent
again to the same model with the answer prompt, and the answer streams back.

## Build

```
JAVA_HOME=/path/to/jdk17 /path/to/gradle :app:assembleDebug
```

The dev APK is copied to `intentcam.apk` at the project root by the build
script per project convention.

## Profiles

`profiling/bench_pipeline.py` is a Pillow microbenchmark for the image
processing pipeline stages. Run on PC to attribute slowness to specific
stages.

## Camera + lifecycle

- `FrameAnalyzer` runs on a single-thread `Executors.newSingleThreadExecutor()`
  bound to CameraX's `ImageAnalysis`. Stability is detected by per-pixel luma
  diff vs the previous sample; once the diff stays below a threshold for 1 s,
  the still is captured.
- On dramatic motion (diff > 40), a scene-change callback fires. Pre-capture
  this resets the stability timer; post-capture it's a no-op.
- The capture is **one-shot** per SCANNING phase: the user must tap 重新扫描
  to start a new capture. The frozen still is processed in the background;
  the live preview never freezes.

## Intent types

- `info` 获取信息 (parcel tracking, WiFi password, bus arrival etc.)
- `location` 我在哪 / 去哪 (street, mall info)
- `solve` 帮我解决问题 (homework etc.)

## Configuration

The settings screen (top-right gear icon) lets you override
`ANTHROPIC_BASE_URL` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_MODEL`. Defaults
are baked in (Minimax Anthropic-compatible endpoint, model `MiniMax-M3`).
Token field is **always blank** in the UI — the actual token never appears on
screen and is preserved across blank saves.

## Key files

- `FrameAnalyzer.kt` — stability detection + JPEG emit (analyze 512 q70 + answer 768 q75)
- `LlmClient.kt` — Anthropic-compatible streaming client with multi-round prompts
- `AppViewModel.kt` — capture → analyze → bubbles → pick → answer state machine
- `OcrEngine.kt` — ML Kit OCR (Latin + Chinese, parallel run, IoU dedup)
- `ObjectDetector.kt` — ML Kit object detection with classification
- `Tasks.kt` — `Task<T>.awaitCancellable()` helper for ML Kit Tasks
- `OcrEngine.kt` / `ObjectDetector.kt` — device-side ground-truth extraction

## Tuning

- Stability threshold / capture interval: `FrameAnalyzer(minIntervalMs=500,
  stableThreshold=8f, motionThreshold=40f)`.
- Capture-after-stable duration: `AppViewModel.CAPTURE_AFTER_MS = 1000L`.
- Per-round analyze timeout: `LlmClient.ANALYZE_ROUND_TIMEOUT_MS = 15s`.
