# IntentCam

A real-time intent-recognition Android app: point the back camera at
something, the model figures out what you want to do with it.

The system blends three signal sources in a single captured frame:

1. **On-device ML Kit OCR** (Latin + Chinese, bundled models) — text ground truth
2. **On-device ML Kit object detection** (single-image mode + ImageNet-style
   classification, bundled model) — object ground truth
3. **A vision LLM** accessed via an Anthropic-compatible `/v1/messages`
   endpoint, driving a **3-round prompt protocol** (BROAD → VERIFY → DECIDE)
   with CoT-forced scene description and streamed SSE responses

The user picks an intent bubble, the captured frame is sent back to the same
model with the answer prompt, and the answer streams back while the live
camera preview continues rolling.

---

## High-level data flow

```
                ┌──────────────────────────────────────────────┐
                │            Camera (back, 30 fps)            │
                └────────────────────┬─────────────────────────┘
                                     │ ImageProxy (RGBA_8888)
                                     ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  FrameAnalyzer  (single-thread executor, ~120 Hz sample)        │
   │  ─ buffer-only luma sample, 24×24 grid, mean-abs-diff vs prev  │
   │  ─ stability check (diff < 8f) gates dual-JPEG emit             │
   │  ─ on stable + not busy + ≥ 500ms throttle: emit FrameJpegs   │
   │      ├ analyze: 512 px max, q70   ≈ 30 KB                      │
   │      └ answer:  768 px max, q75   ≈ 70 KB                      │
   │  ─ diff > 40f + debounce → onSceneChange()                      │
   └─────────────────────────────────────────────────────────────────┘
                                     │ FrameJpegs
                                     ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  AppViewModel  (one-shot capture-gate, 1s stability window)    │
   │  stableSinceMs + lastFrame flag: capture only once per SCANNING │
   │  ─ on capture → clears intents/scene/selected bubbles in state  │
   │  ─ launches runAnalysisCycle(myCycle, analyze, answer)           │
   └─────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼  (parallel on Dispatchers.Default)
   ┌──────────────────────┐  ┌───────────────────────┐  ┌──────────────┐
   │  OcrEngine            │  │  ObjectDetector       │  │  cropCenter  │
   │  Latin + Chinese, in  │  │  SINGLE_IMAGE_MODE +  │  │  65% center  │
   │  parallel; merged by  │  │  classification on;  │  │  crop + q80  │
   │  bounding-box IoU     │  │  labels sorted by     │  │  re-encode   │
   │  ≥ 0.30 with confidence  │  position top→bottom       │              │
   └──────────┬────────────┘  └──────────┬──────────┘  └──────┬───────┘
              ▼                       ▼                     ▼
              └───────────────────────┴─────────────────────┘
                                     │ OcrResult + ObjectResult + zoomJpeg
                                     ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  LlmClient.analyzeRefined  (suspend, Dispatchers.IO)            │
   │  for level in BROAD → VERIFY → DECIDE:                          │
   │     build prompt (CoT + OCR + obj + history)                    │
   │     okhttp-sse stream → extract partial scene per delta         │
   │     parseAnalysis() → AnalysisResult (scene + intents[])         │
   │     decideToStop(intents, level) → break / continue / forced    │
   └─────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
              ┌──────────────────────────────────────────┐
              │  UiState.intents → bubbles appear (Compose) │
              │  user taps one → selectIntent(intent)    │
              └──────────────┬───────────────────────────┘
                                     │
                                     ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  LlmClient.answerStream  (suspend)                              │
   │  same SSE plumbing, longer max_tokens (900), answer-only prompt │
   │  streamed back into UiState.answer (Compose)                    │
   └─────────────────────────────────────────────────────────────────┘
```

---

## State machine

```
                                                  onPermissionsGranted
   NEED_PERMISSION ──────────────────────────────────────► SCANNING
                                                            │
                                          ┌──── capture after 1s stability ────┐
                                          │ (intents currently empty)          │
                                          ▼                                      │
                                                   (intents populated, bubbles show)│
                                          SCANNING (with bubbles)                 │
                                          ▲                       │              │
                          restartScanning│                       │ tap bubble   │
                                          │                       ▼              │
                                          │               selectIntent → ANSWERING
                                          │                                         │
                                          │                            stream done  │
                                          │                                         ▼
                                          │                                    ANSWER
                                          │                       ┌────── user taps 重新扫描 ─────┐
                                          └───────────────────────┘                                  │
                                                                                                   │
                                                                                       (back to SCANNING) ┘

  Phase transitions:
    NEED_PERMISSION → SCANNING          (onPermissionsGranted)
    SCANNING (live, no bubbles) → SCANNING (live, with bubbles)
                                   (runAnalysisCycle commits intents + scene)
    SCANNING (with bubbles) → ANSWERING   (selectIntent)
    ANSWERING → ANSWER                   (answerStream completes)
    any → SETTINGS                       (openSettings; closeSettings → restartScanning)
```

---

## Frame capture (the stability gate)

```
FrameAnalyzer.analyze() runs on a single-thread executor fed by
CameraX ImageAnalysis at the device frame rate (~30 fps).  Each frame:

  if elapsed since last sample < 120 ms → skip
  if isBusy() (analyzing or phase != SCANNING) → skip CPU work entirely
  read buffer sample (24×24 stride-8 luma grid, ~1 ms)
  diff = mean-abs-delta vs previous sample
  if diff > 40 + debounce 1.5 s → onSceneChange()
                                       return                  ──┐
  if diff < 8 (stable) AND ≥500 ms since last emit AND not busy:    │
    emit dual JPEG via buffer → Bitmap → scale → JPEG             │
    onStableFrame(FrameJpegs)                                     │
    return                                                        │
  return                                                          │
                                                                   │
AppViewModel.onStableFrame():                                     │
  if lastFrame != null → skip (already captured this SCANNING)    ◄┘
  if stableSinceMs == -1L → stableSinceMs = now; return
  if now − stableSinceMs < 1000 ms → return
  else (≥ 1 s accumulated stability):
    clear scene/intents/selected
    lastFrame = jpegs
    spawn runAnalysisCycle
```

After the first capture, the analyzer keeps sampling but every emit is
discarded (`lastFrame != null`). To get a new capture, the user must tap
**重新扫描**, which clears `lastFrame` + `stableSinceMs` and returns to
SCANNING.

---

## The 3-round intent protocol

Each round hits the same `/v1/messages` endpoint with a different prompt and
the same image. The cycle short-circuits on clarity.

```
Round 1 ─── BROAD  ─── force CoT, full image
                         system: "你是手机端实时视觉意图助手…先用
                                  observation 字段描述所见…"
                         user:   "1. observation: 描述画面 (≤40字)
                                 2. scene: 用户视角画面描述 (≤20字)
                                 3. intents: [info|location|solve]
                                 - 设备识别物体 (object detection labels)
                                 - 设备 OCR 文字
                                 - 位置 (地理)
                                 JSON only, no prose."
                         image:  analyzeJpeg (512 px q70)
                         parse:  AnalysisResult { scene, intents[4] }
                         STOP if top ≥ 0.70 AND gap ≥ 0.25
                         else CONTINUE

Round 2 ─── VERIFY ─── self-review against prior round
                         system: same BROAD system prompt
                         user:   "重看同一张图，基于上一轮判断做修正…
                                 上一轮 observation: …
                                 上一轮 scene: …
                                 上一轮 intents: …(top 3 with conf)
                                 1. 重写 observation
                                 2. 重写 scene
                                 3. 重写 intents (校准 confidence)"
                         image:  analyzeJpeg (same)
                         STOP if top ≥ 0.60 AND gap ≥ 0.18
                         else CONTINUE

Round 3 ─── DECIDE ─── forced final
                         system: same BROAD system prompt
                         user:   "最后一轮决策。从最近 N 轮判断中提取共识。
                                 {round1: obs, scene, top}
                                 {round2: obs, scene, top}
                                 …
                                 必须给出 1-2 个最合理的意图；都不对
                                 就给信心最低的（≤0.5）。"
                         image:  zoomJpeg (center-cropped 65 %, q80)
                         FORCED → stop regardless

Total cycle time per round: per-round timeout 15 s.
The first round resolves > ~70 % of scenes on its own; the second round
typically pays another ~25 %; only ~5 % reach the third.
```

The history passed to round N+1 contains **all** prior rounds' results. The
image stays the same across all rounds — only the prompt shape changes. R3
sends a tighter center-crop JPEG of the answer-sized image (still 768 px, q80
re-encoded to recover detail), which gives the model a closer look at the
visual center for ambiguous scenes.

---

## On-device ground-truth fusion

Both ML Kit engines take the **answerJpeg** (768 × N q75) as input, decode it
with `inSampleSize = 2` (≈ 384 × N), run their respective model, and return
text/objects with bounding boxes in the JPEG's original coordinate space
(`OcrEngine` and `ObjectDetector` both multiply box coordinates by 2 to undo
the downsample).

The two OCR recognizers — Latin and Chinese — run **in parallel**
(`coroutineScope { async {} async {} }`). Results are merged with an
**IoU-based dedup**: if two lines' bboxes overlap by ≥ 30 % of their union area,
the higher-confidence line wins. For tea-label-style scenes (mixed Chinese
and English), both recognizers fire on the same region but the higher
confidence wins per region, not per recognizer.

`ObjectDetector` runs in `SINGLE_IMAGE_MODE` with `enableClassification()`, so
each detection comes back with its ImageNet-style top label + confidence.

---

## Streaming pipeline

```
Anthropic /v1/messages with stream: true
└── LlmClient.streamText  (suspending)
     │
     │  okhhtp3 + okhttp-sse EventSource
     ▼
   EventSourceListener:
     onEvent  → if resolved → extract text_delta → snapshot → onDelta(snapshot)
                                                   │
                                                   ▼
                                          AppViewModel.onDelta
                                            extractPartialScene → partialScene
     onClosed → resolved → resume(full accumulated text)
     onFailure → resolved → resumeWithException(t or HTTP code)
     invokeOnCancellation → EventSource.cancel()
     ─────────────────────────────────────────
     also:  if (cont.isCancelled) skip resume  (guards against ISE on dead continuation)

Top-level guard:
  withTimeout(totalTimeoutMs) — server stalls abort with friendly error
  try/catch TimeoutCancellationException → IllegalStateException("tag: 模型在 …ms 内未完成")
```

`extractPartialScene` walks the partial JSON char-by-char, decoding JSON
string escapes (`\n`, `\t`, `\"`, `\\`, and **\uXXXX via `appendCodePoint`** so
the user sees "中" instead of literal `中`).

---

## Threading model

| Component | Thread | Reason |
|---|---|---|
| `FrameAnalyzer.analyze` | single-thread `Executors.newSingleThreadExecutor` | CameraX guarantees serial execution; we keep our work on the analyzer thread to avoid contention with main |
| `OcrEngine.recognizeFromBytes` | `Dispatchers.Default` | bitmap decode + ML Kit CPU |
| `ObjectDetector.recognizeFromBytes` | `Dispatchers.Default` | bitmap decode + ML Kit CPU |
| `cropCenter` | `Dispatchers.Default` | bitmap decode + crop + JPEG encode |
| `LlmClient.analyzeRefined` / `answerStream` | `Dispatchers.IO` (wrapped by `withContext`) | OkHttp Sockets I/O + DNS |
| `AppViewModel.runAnalysisCycle` | `viewModelScope` (Main by default) suspended over the I/O calls | UI state writes |
| `onDelta` callbacks from SSE listener | OkHttp dispatcher thread | acc + snapshot → `MutableStateFlow.value` is thread-safe |
| `extractPartialScene` | Same thread as onDelta | Cheap char scan |
| `_state.value = …` writes | Any thread | `MutableStateFlow` uses CAS; safe to set concurrently; Compose picks up changes |

---

## Cancellation model

Every async operation funnels through either coroutine cancellation or
structured concurrency:

- `AppViewModel.currentAnalyzeJob.cancel()` cancels the entire cycle → propagates
  via `coroutineScope { async { … } }` to OCR + objDetector + OkHttp call. The
  OkHttp `EventSource.cancel()` is invoked from
  `suspendCancellableCoroutine.invokeOnCancellation`.
- `Tasks.kt` provides `awaitCancellable()` for ML Kit `Task<T>`. The Task
  itself cannot be aborted (no public `cancel()` on GMS API), but the
  coroutine awaiting it returns cleanly and the result is discarded.
- `AppViewModel.runAnalysisCycle` catches `CancellationException` at the
  boundary, resets `analyzing`, and only writes to state if `myCycle ==
  analyzeCycleId.get()` — so a cancelled cycle never stomps later state.
- `AppViewModel.selectIntent` guards `if (_state.value.selected != null)`
  at the entry to prevent double-launching an answer when an auto-proceed
  race collides with a user tap.

---

## State fields (`UiState`)

| Field | Source | Used for |
|---|---|---|
| `phase` | various | gate `isBusy()` and select the bottom panel |
| `scene` | final round's `result.scene` | top-bar text after analysis |
| `intents` | final round's `result.intents` | bubble list |
| `analyzing` | `AtomicBoolean analyzing.get()` mirrored into UI | spinner in the top bar |
| `partialScene` | `extractPartialScene` of streamed JSON | live scene while analyzing |
| `selected` | `selectIntent()` | the user's pick; gates `selectIntent` re-entry |
| `answer` | streamed from `answerStream` | answer text panel |
| `error` | catch block of `runAnalysisCycle` / answerStream | top-bar error; if non-null, the bottom error hint shows too |
| `location` | `LocationHelper.currentLocationText()` cached 15 s | optional ground truth in prompt |
| `roundCount` | increments each completed cycle | telemetry |
| `lastSceneChangeMs` | `onSceneChange` | debug only |
| `ocrOverlay` | `OcrResult.lines` | (currently unused by Compose; held for future overlay) |
| `ocrEpoch` | bumps on each OCR run | Compose key to refresh |

---

## Configuration

The settings screen (top-right gear icon) lets you override

- `ANTHROPIC_BASE_URL` — default `https://api.minimaxi.com/anthropic`
- `ANTHROPIC_AUTH_TOKEN` — **field is always blank in the UI**; blank saves
  preserve whatever token is currently active (default if blank, custom
  otherwise); the real token never appears on screen.
- `ANTHROPIC_MODEL` — default `MiniMax-M3`

Values are persisted to `SharedPreferences` via `SettingsStore`.

---

## Build

```bash
JAVA_HOME=/path/to/jdk17 /path/to/gradle :app:assembleDebug
```

The dev APK is copied to `intentcam.apk` at the project root by the build
script per project convention.

The Debug APK bundles the OCR (≈ 16 MB), Chinese OCR (≈ 7 MB), and Object
Detection (≈ 5 MB) ML Kit models. Total APK ≈ 107 MB. R8 + resource shrinking
on release would cut this roughly in half.

---

## Tuning

| Knob | Default | Where |
|---|---|---|
| Stability threshold | `8f` | `FrameAnalyzer.stableThreshold` |
| Motion / scene-change threshold | `40f` | `FrameAnalyzer.motionThreshold` |
| Motion debounce | `1500L` ms | `FrameAnalyzer.motionDebounceMs` |
| Sample cadence | `120L` ms | `FrameAnalyzer.SAMPLE_INTERVAL_MS` |
| Min interval between emits | `500L` ms | `FrameAnalyzer.minIntervalMs` |
| Capture-after-stable duration | `1000L` ms | `AppViewModel.CAPTURE_AFTER_MS` |
| Per-round analyze timeout | `15_000L` ms | `LlmClient.ANALYZE_ROUND_TIMEOUT_MS` |
| Total answer timeout | `45_000L` ms | `LlmClient.ANSWER_TOTAL_TIMEOUT_MS` |
| Analyze image max-dim | `512` px | `FrameAnalyzer.ANALYZE_MAX_DIM` |
| Answer image max-dim | `768` px | `FrameAnalyzer.ANSWER_MAX_DIM` |
| Analyze image quality | `70` | `FrameAnalyzer.ANALYZE_QUALITY` |
| Answer image quality | `75` | `FrameAnalyzer.ANSWER_QUALITY` |
| Analyze round max tokens | `320` | `LlmClient.ANALYZE_MAX_TOKENS` |
| Answer max tokens | `900` | `LlmClient.ANSWER_MAX_TOKENS` |
| Decide-round crop fraction | `0.65` (center) | `AppViewModel.cropCenter` |
| Decide-round crop quality | `80` | `AppViewModel.cropCenter` |
| OCR / Object det downsample | `2` | `OcrEngine.DOWNSAMPLE`, `ObjectDetector.DOWNSAMPLE` |
| OCR merge IoU threshold | `0.30f` | `OcrEngine.OVERLAP_THRESHOLD` |
| Stop-decision BROAD | `top ≥ 0.70 && gap ≥ 0.25` (or single-cand ≥ 0.55) | `AppViewModel.decideToStop` |
| Stop-decision VERIFY | `top ≥ 0.60 && gap ≥ 0.18` | `AppViewModel.decideToStop` |

## Profiling

`profiling/bench_pipeline.py` is a Pillow microbenchmark that simulates each
pipeline stage on the test fixtures (`IMG1.jpg`, `IMG2.jpg`). Run on PC to
attribute slowness to specific stages before changing production code.

## Key files

```
app/src/main/java/com/example/intentcam/
├── AppViewModel.kt     — capture → analyze → pick → answer state machine
├── FrameAnalyzer.kt    — stability detection + dual-JPEG emit
├── LlmClient.kt        — Anthropic-compatible streaming, 3-round prompts
├── OcrEngine.kt        — ML Kit OCR (Latin + Chinese), IoU-merged
├── ObjectDetector.kt   — ML Kit object detection
├── Tasks.kt            — Task<T>.awaitCancellable() helper for GMS
├── Models.kt           — IntentItem / AnalysisResult / UiState / LlmConfig
├── MainActivity.kt     — CameraX preview + Compose UI
├── SettingsScreen.kt   — Compose settings sheet
├── SettingsStore.kt    — SharedPreferences wrapper
├── LocationHelper.kt   — FusedLocationClient wrapper
└── Theme.kt            — Compose theme
```
