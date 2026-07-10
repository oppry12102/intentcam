# IntentCam — Architecture

> Companion doc to [README.md](README.md).  Quick start, build,
> and at-a-glance summary live there; this file is the deep-dive.
> See [CONFIG.md](CONFIG.md) for every tunable constant.

## 1. The three-tool design (Phase 2, 2026-07-11)

The previous design had 12 specialized tools
(`default_describe`, `identify_product`, `navigate_to_block`, ...).
It forced the LLM to *categorize* the image first, leading to wrong
picks on anything it didn't recognize.  Phase 2 (2026-07-11) collapsed
to **three tools** — `read_text` was retired because every `zoom_in`
crop now auto-runs OCR (see §4.2):

| Tool | What it does |
|---|---|
| `zoom_in` | crop a region of the (current) image at native resolution + auto-OCR |
| `compare_text` | pure on-device string diff (model reading vs OCR hint) |
| `emit_bubble` | end the cycle with a structured answer |

That's it.  The LLM looks at the image, calls `zoom_in` to clarify
unclear details (and gets verbatim OCR text from each crop), and
ends with `emit_bubble` when satisfied.

`zoom_in` is the **default mode** — the system prompt says
"无论图是否清楚，先调 1-2 次 zoom_in 看清楚细节" (whether
or not the image is clear, first call 1-2 zoom_in to clarify
details).

```
zoom_in(x, y, w, h, source, focus)
  - x, y, w, h:  归一化坐标 ∈ [0, 1]
  - source:  "original" (default — sibling) or "last" (chain)
  - focus:  一句话描述要找什么
```

The client's `BitmapRegionDecoder` crops the **2048-px fullRes JPEG**
(kept in memory by `FrameAnalyzer`) at the requested region, returns
the crop as a `followUpJpeg`.  The orchestrator **auto-runs OCR on
the crop** (via `OcrEngine.recognize(cropBytes)`) and attaches both
the image and the formatted crop hint to the next user message —
the model sees the high-detail region and the verbatim characters
in one round-trip, with no need for a separate `read_text` call.

- **Sibling mode** (default `source="original"`): crops the original
  2048-px fullRes.  Coords are absolute.  This is the **default**
  because the round-1 thumbnail is 1568-px (downsampled from 2048-px);
  cropping the thumbnail would only downsample again.  Sibling mode
  is the "real magnifier."
- **Chain mode** (`source="last"`): crops whatever was just produced.
  Use for deep zoom on a single region across multiple rounds.
- **Multi-zoom in one round**: every `zoom_in` call returns its
  own `followUpJpeg`; the orchestrator auto-OCRs each one and
  attaches all of them to the next user message in call order.
  A single round can produce 2-5 crops that the model sees together.

```
emit_bubble(
  content,        // 一两句话整体描述 — must include all visible text
  intent,         // 用户想做什么（动宾短语，≤30字）
  type,           // info | location | solve
  intent_focus?,  // 可空
  confidence,     // 0.0~1.0
  details: [      // 详情页表格行
    {kind, label, value, bbox?}, ...
  ]
)
```

**`content` is critical** — the system prompt forces the model to
list all visible text/numbers/brands/dates/prices in the content
field.  Example: for a tea package, the model must write
"包装文字: 品名 '工夫红茶', 净含量 '250g', 生产日期 '2020-12-01'".
**Source priority for verbatim characters** (Phase 2 prompt):
1. **zoom crop OCR** (highest fidelity — Phase 2 auto-OCR)
2. **round-1 OCR** (full image, may be less precise on small text)
3. **model's own vision** (for OCR-missed regions; "?" placeholder
   for characters it can't read)

**`details` is the structured table** rendered in the DetailScreen.
Each `{kind, label, value}` row matches a real piece of text in the
image.  `kind` ∈ {text, number, object, color, shape, logo, date,
price, brand, location, person, ...}.  `bbox` is the 4-corner
position in the **original image frame** (zoom crop bboxes are in
crop frame; the prompt tells the model to offset by zoom's x/y/w/h
to map back to original).

```
compare_text(claim, ocr_text?)
  - claim: 你从图上自己读到的字符串
  - ocr_text: （可选）OCR hint 的某行文字
  - 返回: 每行 conflict 标记 (agreed/ocr_only/llm_only/disagree) + 推荐动作
```

`compare_text` is a pure on-device string diff against the round-1
OCR cache.  The LLM uses it when it has read the image differently
from the round-1 OCR hint (e.g. spots a missing line) and wants
confirmation before deciding whether to trust its own reading or
zoom_in for a fresh re-scan.  No LLM round-trip — runs in ~1ms.

## 2. Multi-round protocol

```
Round 1:
  messages = [user(thumbnail 1568-px + OCR hint + "调用工具。")]
  LLM → tool_use (zoom_in or emit_bubble)
  body runs locally → ToolResult
  if body returned followUpJpeg:
    the real crop goes into the next user message

Round 2..15:
  LLM sees the crop, decides: zoom_in again (chain) or emit_bubble
  if emit_bubble:
    extract structured fields, build Bubble, end cycle
  if 2+ zooms already done:
    force emit_bubble in the next-round nudge

Stop when:
  - emit_bubble fires → final Bubble
  - 15 rounds hit (兜底 Bubble — uses last good details, 0.5 score)
  - 60s timeout (per round; hardcoded in LlmClient.TOTAL_TIMEOUT_MS)
```

The image the LLM sees in round 1 is the **1568-px thumbnail** (with
OCR text in the same message).  The full-res photo (2048-px) stays
in client memory and is only sent when a `zoom_in` tool body crops
a region.  The LLM therefore sees **both** views when it asks for
detail — the round-1 thumbnail (in context) and the crop (in the
next user message).

## 3. Frame capture

`FrameAnalyzer` produces two encodings per capture:

- `thumbnail` (3200 px max-dim, q90) — sent to the LLM as the
  round-1 image.  Balances bandwidth against recognition accuracy.
- `fullRes` (4096 px max-dim, q95) — kept in memory so `zoom_in`
  can crop from it.

### 3.1 Camera buffer sizing (v1.0 critical)

The `ImageAnalysis` use case must be configured with an explicit
`ResolutionSelector` — **otherwise CameraX defaults to 640×480 VGA**.
This was the silent bug fixed in v1.0: even with `MAX_DIM=3200`
and `MAX_FULL_DIM=4096` configured correctly, the `FrameAnalyzer`
was receiving 640×480 frames because nothing told CameraX to
deliver larger buffers.  `encodeBitmap` only downscales (never
upscales), so both JPEGs came out at 640×480 — meaning the LLM
saw a thumbnail smaller than a typical website favicon, and
`zoom_in` crops were *downsampling*, not magnifying.

Fix: `pickLargestAnalysisSize()` queries the back camera's
`StreamConfigurationMap.getOutputSizes(YUV_420_888)` via
`Camera2CameraInfo`, picks the largest 4:3 (fall back to
largest-by-area), passes to `ResolutionStrategy(size,
FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`.  Works on every
CameraX-supported device without hardcoding a number that would
crash analyzers on 50MP sensors or waste resolution on 5MP ones.

### 3.2 Cold start (v1.0 critical)

On first launch, the user grants permission → `CameraScreen`
composes → `AndroidView` factory runs → `ProcessCameraProvider`
init + `bindToLifecycle` happen.  This sequence takes 200-800ms
on real devices; the analyzer receives its first frame *after*
binding completes, so any shutter tap within that window hits
the timeout.

Fix: kick off `ProcessCameraProvider.getInstance(app)` at
`AppViewModel` construction (i.e. during `onCreate`).  The
provider service connection is then established in parallel
with permission grant and UI render.  By the time the user
taps shutter, the provider is usually already ready and
`bindToLifecycle` completes in ~150ms.

Verified via `CAM` debug log line: `provider ready after Xms
(cold start)` — typically 50-150ms by the time the user reacts.
Capture timeout was also raised 500ms → 3000ms (see §9).

### 3.3 Capture flow

There is **no stability gate** — the user controls when to capture
via a shutter tap, not "wait for scene to settle".

```
User taps shutter:
  captureLatestFrame()
    armed = true  (CAS — FrameAnalyzer's next frame is captured)
    state.analyzing = true
    viewModelScope.launch { wait for frame, up to 3000ms }

FrameAnalyzer.analyze() next frame:
  if !isArmed → return  (common case — user just looking)
  encode full + thumbnail JPEGs
  onFrame(CapturedFrame(thumb, full))
  armed = false  (only one frame captured per tap)

AppViewModel captures:
  if latestFrame == null → log "[CAP] 3000ms 内没拿到帧（等了 Xms）"
  else → runRecognitionCycle(frame)
    toolUseLoop.runCycle(thumb, full, "")
```

## 4. OCR (端云协同 — endcloud collaboration)

### 4.1 Round-1: full-image OCR pre-pass

Round-1 ships the thumbnail + the **OCR hint** — a structured dump
of all on-device-recognized text.  The LLM treats this as the
**first opinion** for visible text (not exclusive — OCR can miss
text the LLM catches with its own vision, especially handwriting
and art).

**On-device**: HMS ML Kit's text recognizer, decodes the JPEG bytes
internally, returns text + 4-point coords + confidence per block.
The Kotlin side (`AndroidOcrEngine`) implements the `OcrEngine.Impl`
interface and gets installed at `MainActivity.onCreate`.

**Per-block confidence**: blocks with conf < 0.5 are marked `[LOW]`
in the round-1 hint.  The LLM can still emit them (with the marker
visible in the details table); the user sees "OCR 不太确定" rather
than nothing.

**Injection shape**: top-N blocks by confidence (N = 30), formatted
as a labeled block in the user message:
```
【read_text 全图扫描结果】on-device OCR 已扫过整张图... (marker name is historical — Phase 2 (2026-07-11) removed the `read_text` tool; OCR now runs automatically on round-1 + every zoom_in crop)
  line 1: '建国路 100号' | bbox=[(0.10,0.20),...] | conf=0.95
  line 2: '禁止停车' | bbox=[(0.50,0.60),...] | conf=0.62
  ...
```

The round-1 OCR result is cached for the entire cycle and passed
into every `ToolContext.ocrCache` so `compare_text` can diff
against it without re-running OCR.

### 4.2 Phase 2 (2026-07-11): auto-OCR on every zoom crop

Every `zoom_in` crop auto-runs `OcrEngine.recognize(cropBytes)` and
the formatted crop hint attaches to the next user message alongside
the image.  The model gets:

- The high-resolution crop (visual drill-down)
- The verbatim OCR text from that crop (higher fidelity than round-1
  for that region — round-1 ran on a downsampled thumbnail)

The crop hint uses `OcrResult.formatHint(blocks, maxLines=10,
isCropHint=true)` which:

- Renders top-10 blocks by confidence (vs round-1's 30 — crops are
  smaller regions, 10 lines is plenty)
- Uses a different header: `【zoom_in crop OCR 高保真重扫】` (vs
  round-1's `【read_text 全图扫描结果】扫过整张图`) so the model
  knows which OCR this is
- Echoes "**trust 这些字符 verbatim**" for [LOW] lines (vs
  round-1's "workflow: 调 zoom_in 重扫") since this IS the high-
  fidelity re-scan

**Crop bboxes are in crop frame** (normalized 0-1 of the crop, not
the original photo).  The system prompt tells the model:

> zoom crop hint 的 bbox 是 crop frame，不是原图 frame — 要在
> details[].bbox 里复用，offset 加回你传给 zoom_in 的 (x, y)

So `original_bbox_corner = (zoom_x + crop_corner.x * zoom_w,
zoom_y + crop_corner.y * zoom_h)`.

**Workflow prompt** (`LlmClient.TOOL_USE_SYSTEM`):

> **Step 1**: round-1 — read OCR full-image scan + look at thumbnail
> **Step 2**: identify [LOW] / missed → call `zoom_in(bbox, source='original')`
> **Step 3**: zoom_in crop auto-attaches OCR (trust verbatim)
> **Step 4**: emit_bubble

This workflow narrative is the **load-bearing piece** — without it
(Phase 1 attempt, 2026-07-11) the model defaulted to hedging the
auto-attached OCR ("free information paradox").  With it, the model
trusts the crop OCR as much as OCR it would have requested via
`read_text`.

### 4.3 Eval-side backend

On the JVM eval, the same `OcrEngine.Impl` interface is wired to
`JvmHuaweiCloudOcrEngine` when `HUAWEICLOUD_SDK_AK/SK/PROJECT_ID`
env vars are set.  The cloud backend mirrors HMS ML Kit semantics
(text + 4-corner coords + confidence) and runs the same
`formatHint` formatter, so eval and prod see the same hint shape.

## 5. 1-only image strategy (since 2026-07-06)

Round-1 ships **one** image — the 1568-px thumbnail.  No 4-quadrant
breakdown.  The LLM zooms into regions it needs.

Why: 1+4 (5-image round 1) was tested and benchmarked worse on
RCTW-17 (composite 0.68 vs 0.77 on a 20-fixture sample).  The
5-image burst blew past the 20s round budget on dense scenes
because the LLM has to read 5 large images per round.

The 1-only strategy lets the model **decide** where to spend its
visual attention (via `zoom_in`), instead of pre-paying for 4
quadrants up front.

## 6. State machine

```
                                                  onPermissionsGranted
   NEED_PERMISSION ──────────────────────────────────────► SCANNING
                                                            │
                                          tap shutter  │
                                                            ▼
                                                        ANALYZING
                                                            │
                            ┌─── Outcome.Bubble ─────┤
                            │                         │
                            ▼                         │
                  (bubble shown)                 │  user input typed
                            │                         │  → resume
                  tap bubble│                         │
                            ▼                         │
                     SHOWING_DETAIL                   │
                            │                         │
              tap 退出                              │
                            │                         │
                            └────────► (back to SCANNING) ◄───── cancel ◄─────┘

                  any → SETTINGS (openSettings / closeSettings → restartScanning)
```

State writes go through `MutableStateFlow` (thread-safe via CAS).
Every `analyzing` write is paired with the corresponding
`analyzing: AtomicBoolean` so the camera analyzer's `isBusy()`
callback always sees the latest value.

## 7. Bubble model

```kotlin
data class Detail(
    val kind: String,    // text | number | object | color | shape | ...
    val label: String,   // human-readable name
    val value: String,   // the extracted content
)

data class Bubble(
    val id: String,
    val type: String,            // "info" | "location" | "solve"
    val title: String,            // user-facing intent, ≤30 chars
    val detail: String,           // content description
    val confidence: Float,
    val imageBytes: ByteArray,   // 2048-px fullRes JPEG for detail view
    val createdAtMs: Long,
    val toolName: String? = null,
    val needsUserInput: Boolean = false,
    val intentFocus: String? = null,
    val details: List<Detail> = emptyList(),  // details table rows
)
```

`Bubble` is FIFO-capped at 4 in `UiState.bubbles`; older bubbles
evict when a new one arrives. `imageBytes` is the 2048-px fullRes
JPEG (not the 1568-px thumbnail), so the detail view can show a
sharper image.

The `details` list drives the structured table in the DetailScreen
(kind | label | value columns). Each row is something the LLM
extracted from the image — text, numbers, brand names, dates, etc.
The LLM populates these in `emit_bubble`'s `details` input field.

## 8. Cancellation & concurrency

- `viewModelScope` is the only coroutine scope; tearing down the
  ViewModel cancels every in-flight recognition.
- `analyzing: AtomicBoolean` gates the camera analyzer so the next
  shutter tap is a no-op while a cycle is in flight.
- The OkHttp `EventSource.cancel()` is invoked from
  `suspendCancellableCoroutine.invokeOnCancellation` so the SSE
  connection drops the moment the coroutine is cancelled.
- Per-round timeout (60s) wraps the whole stream; stalled servers
  surface as a friendly `IllegalStateException("tooluse: 模型在 Nms 内未完成")`.

| Thread | Where | Why |
|---|---|---|
| `Executors.newSingleThreadExecutor` | `FrameAnalyzer.analyze` | CameraX guarantees serial execution; we own the loop on the analyzer thread |
| `Dispatchers.IO` | `LlmClient.streamToolUse` | OkHttp Sockets I/O + DNS |
| `viewModelScope` (Main) | `AppViewModel.runToolUseCycle` | UI state writes + Compose recomposition |
| `Main` | Compose recomposition | The whole app is single-Composable-Activity |

## 9. Tuning

> Single source of truth: **[CONFIG.md](CONFIG.md)**.  This table
> is a summary; CONFIG.md has file:line + rationale + "Recently
> retired" + "To-try-next" sections.

| Knob | Value | Where | Purpose |
|---|---|---|---|
| `MAX_DIM` (thumbnail) | `3200` | `FrameAnalyzer.kt:159` | max-dim cap for the LLM-facing image |
| `QUALITY` (thumbnail) | `90` | `FrameAnalyzer.kt:160` | JPEG quality for the LLM-facing image |
| `MAX_FULL_DIM` | `4096` | `FrameAnalyzer.kt:176` | cap for the in-memory full-res JPEG |
| `FULL_QUALITY` | `95` | `FrameAnalyzer.kt:177` | JPEG quality for the in-memory full-res JPEG |
| `CROP_OUTPUT_MAX_DIM` | `3200` | `ImageOps.kt:70` | max-dim cap on `zoom_in` crops |
| `DEFAULT_CROP_QUALITY` | `90` | `ImageOps.kt:60` | JPEG quality for crops |
| `ResolutionSelector` | sensor max 4:3 | `MainActivity.kt:253-292` | v1.0: tells CameraX to deliver full sensor res to ImageAnalysis (was 640×480 default) |
| `camera pre-warm` | viewmodel init | `AppViewModel.kt:50-57` | v1.0: kicks off `ProcessCameraProvider.getInstance()` during `onCreate` |
| `MAX_OCR_HINT_LINES` | `30` | `OcrEngine.kt:92` | top-N OCR blocks injected into round-1 user message |
| `LOW_CONFIDENCE_THRESHOLD` | `0.5` | `OcrEngine.kt:89` | OCR conf < 0.5 → mark `[LOW]` in hint |
| `MAX_ROUNDS` | `30` | `ToolUseLoop.kt` | soft cap; 兜底 Bubble on hit |
| `TOTAL_TIMEOUT_MS` | `90_000` | `LlmClient.kt:358` | per-round SSE timeout |
| `MAX_TOKENS` | `2048` | `LlmClient.kt:342` | output token cap per round |
| `REQUEST_TEMPERATURE` | `0.0` | `LlmClient.kt:336` | locked at 0 for deterministic routing |
| `capture timeout` | **`3000` ms** | `AppViewModel.kt:212` | v1.0: bumped 500→3000ms for cold-start + sensor-res encode |
| `BUBBLE_MAX` | `4` | `Models.kt` | FIFO cap on bubble list |
| `DEBUG_LOG_MAX` | `40` | `Models.kt` | ring-buffer cap on debug log |

## 10. Debug overlay

`UiState.debugEnabled` (default ON) renders a translucent
scrolling log panel above the camera preview. Each
`DebugLogEntry` carries:

- `timestampMs: Long` — wall-clock display
- `seq: Long` — monotonic `AtomicLong.incrementAndGet()` per
  ViewModel; **used as the LazyColumn key** (not `timestampMs`)
- `tag: String` — short category ("CAP", "TOOL", "INPUT", "FINAL")
- `message: String` — single line, capped 160 chars

`seq` was added to fix a `LazyColumn` crash where multiple
`logDebug` calls in the same round (CAP → TOOL → TOOL → …) shared
the same `timestampMs` (millisecond resolution), causing
"Key X was already used" and killing the process.  With `seq`
every key is unique, the panel scrolls cleanly.

## 11. Configuration

The settings screen (top-right gear icon) lets you override

- `ANTHROPIC_BASE_URL` — default `https://api.minimaxi.com/anthropic`
- `ANTHROPIC_AUTH_TOKEN` — **field is always blank in the UI**;
  blank saves preserve whatever token is currently active
  (default if blank, custom otherwise).  The real token never
  appears on screen.  `Models.kt` ships a `REPLACE_AT_RUNTIME`
  placeholder; real builds need either a runtime Settings entry
  or an env-var-injected default.
- `ANTHROPIC_MODEL` — default `MiniMax-M3`

Values are persisted to `SharedPreferences` via `SettingsStore`.

## 12. Eval benchmark

The eval is the Kotlin `:shared:eval` task — it runs the **real**
`ToolUseLoop` + `LlmClient` + `OcrEngine` (no parallel
implementation).  Production code is exercised end-to-end against
real fixtures.

### Scoring

```
composite    = 0.50 × r1_score + 0.50 × r2_score
r1_score     = 0.70 × (tool_pick ∈ {zoom_in, compare_text, emit_bubble}
                            ? 1.0 : 0)
             + 0.30 × (input_valid)
r2_score     = 0.50 × text_match + 0.50 × type_match
text_match   = fuzzy_text  (CharSequence overlap, threshold 0.67)
             ∪ strict_text  (substring of expected keywords)
type_match   = right 1.0 | valid-wrong 0.5 | empty 0.0
兜底 Bubble  = if MAX_ROUNDS hit without emit, force bubble with
              last good details; r2_text = 0.5, r2_type = 0.5
```

### Fixtures

`profiling/ground_truth_rctw.json` has 100 scenes, all with
`category='default'` and `expected_type='info'`.  Images are
sampled from `/home/oppry/RCTW-171/train_images` (RCTW-17
ICDAR 2017 Chinese scene text dataset, 8034 train images).
Files renamed `rctw_default_NN.jpg`.  GT auto-parsed from the
dataset's XML labels.

### Baseline chain

| Date | composite | Δ | Key change |
|---|---|---|---|
| 2026-07-06 | 0.652 | — | (1-only image strategy landed) |
| 2026-07-07 | 0.819 | +0.167 | timeout 60s + 兜底 Bubble + details cap + type-partial-credit |
| 2026-07-08 | 0.835 | +0.016 | OCR-aware collaboration (端云协同) |
| 2026-07-10 | 0.838 | +0.003 | softened prompt (no over-hedge on imperfect OCR) |
| 2026-07-10 | 0.841 | +0.003 | 1568 thumb + zoom_in=original + "thumbnail ≠ 原图" nudge |
| 2026-07-10 | **0.853** | +0.012 | @100 verification of the above (12W/8L/0T) |
| 2026-07-10 | 0.898 | +0.045 | `MAX_FULL_DIM` 4096→2048 (counter-intuitive win) |
| 2026-07-11 | 0.874 | +0.021 | **Phase 2a**: auto-OCR on every zoom_in crop + 4-step workflow prompt + `isCropHint` flag on `OcrResult.formatHint` |
| 2026-07-11 | **0.868** | -0.006 | **Phase 2** (this release): `read_text` tool removed — auto-OCR + workflow prompt cover both [LOW] verification and missed-region re-scan. Net composite in noise; empty 27/100 → 19/100 (better); content 103 → 116 (better); per-fixture volatility +1/-1 (some Phase 2a successes go empty, some Phase 2a empties succeed). Ship. |

`profiling/eval_*.json` keeps every JSON dump; use
`profiling/diff_eval.py` to compare two runs side-by-side.

### Ablations (tested, rejected)

| Proposal | Δ | Why rejected |
|---|---|---|
| `MAX_OCR_HINT_LINES` 30→20 | -0.012 | r2_text_fuzzy -0.042; model was using those lines |
| `MAX_ROUNDS` 30→15 | -0.012 | rctw_default_10 hit cap → 兜底 empty → -0.257 |
| r1/r2 weights 0.50/0.50→0.40/0.60 | -0.069 | Pure rebalance; r1 near-ceiling; user chose headline tracking |
| Phase 1: auto-OCR wiring only, no workflow prompt | -0.17 r2_text_fuzzy | "Free information paradox" — auto-attached OCR treated as low-confidence vs requested OCR.  Reverted.  Phase 2a fixed by adding 4-step workflow narrative. |

## 13. DetailScreen (v1.0 UX overhaul)

Pre-v1.0: image was capped at `height(360.dp)` at the top with
the text/details in a translucent panel glued to the bottom —
text overflowed off-screen on long bubbles and there was no
way to see more image at the expense of text.

v1.0 layout:

```
┌─────────────────────────────┐
│                             │
│       原图 (fit)            │  ← weight(1f), fills remaining space
│                             │     ContentScale.Fit, black bars
│                             │
├─────────────────────────────┤
│ ● 标题            99%  ▾   │  ← header, tap to collapse/expand
├─────────────────────────────┤
│ 工具芯片                     │
│ 识别详情文字...              │  ← scrollable (verticalScroll)
│ [图片细节]                   │     long text/rows scroll here
│ kind label value •          │     退出 button never pushed off-screen
├─────────────────────────────┤
│      [ 退  出 ]             │  ← sticky bottom
└─────────────────────────────┘
```

- Image: `fillMaxWidth().weight(1f)` + `ContentScale.Fit`.  When
  text is collapsed, image gets back almost full screen.
- Text header: always visible.  Tap row to toggle
  `textExpanded: Boolean` state.  Arrow icon flips between
  `▾` (expanded) / `▴` (collapsed).
- Scrollable text body: `verticalScroll(rememberScrollState())`.
  Long bubbles + many detail rows scroll inside this region;
  退出 button stays anchored.
- 退出 button: `navigationBarsPadding()`, always at bottom.

## 14. TODOs

- Release signing config + `isMinifyEnabled = true` (currently debug-only)
- Plumb `ANTHROPIC_AUTH_TOKEN` env var into the default token at
  build time so the debug APK works out-of-the-box without manual
  Settings entry
- CI: run `profiling/eval_*` on every commit; flag regressions
  > 0.05 in average composite
- r2_text: lift the r2_text ceiling.  Current prod-mirror ceiling
  is **0.903 @100**; with v1.0's on-device sensor resolution
  finally matching the eval's 3200-px input, next experiments
  should target the text-heavy fixtures where r2_text_fuzzy still
  lags (≤0.74 strict).  Promising: text-region detection,
  per-region structured-text tool, or a higher `CROP_OUTPUT_MAX_DIM`.
- On-device eval verification: now that the device finally delivers
  sensor-res frames, install v1.0 and run a 20-fixture manual
  smoke test on a real photo set — confirm the eval @0.903 ceiling
  is reproducible in hand (not just `:shared:eval`).
