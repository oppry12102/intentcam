# IntentCam — Architecture

> Companion doc to [README.md](README.md).  Quick start, build,
> and at-a-glance summary live there; this file is the deep-dive.

## 1. The two-tool design

The previous design had 12 specialized tools
(`default_describe`, `identify_product`, `navigate_to_block`, ...).
It forced the LLM to *categorize* the image first, leading to wrong
picks on anything it didn't recognize.  We collapsed to **two tools**:

| Tool | What it does |
|---|---|
| `zoom_in` | crop a region of the (current) image at native resolution |
| `emit_bubble` | end the cycle with a structured answer |

That's it.  The LLM looks at the image, calls `zoom_in` to clarify
unclear details, then calls `emit_bubble` when satisfied.

`zoom_in` is the **default mode** — the system prompt explicitly
says "无论图是否清楚，先调 1-2 次 zoom_in 看清楚细节" (whether
or not the image is clear, first call 1-2 zoom_in to clarify
details).

```
zoom_in(x, y, w, h, source, focus)
  - x, y, w, h:  归一化坐标 ∈ [0, 1]
  - source:  "last" (chain, default) or "original" (sibling)
  - focus:  一句话描述要找什么
```

The client's `BitmapRegionDecoder` crops the **full-resolution**
JPEG (kept in memory by `FrameAnalyzer`) at the requested region,
returns the crop as a `followUpJpeg`.  The orchestrator attaches
it to the next user message so the model sees the high-detail
region alongside the original.

- **Chain mode** (default `source="last"`): the second `zoom_in`
  call crops whatever was just produced.  Calling twice with
  default source gives a chained crop of a crop — iterative
  zoom-in.
- **Sibling mode** (`source="original"`): the second call crops
  the original full-res photo.  Coords are absolute.  Use this
  when the model wants to see two different parts of the original
  in the same round.
- **Multi-zoom in one round**: every `zoom_in` call returns its
  own `followUpJpeg`; the orchestrator attaches all of them to the
  next user message in call order.  The first one is processed;
  the rest get a "duplicate skipped" message so the chain advances
  deterministically.  A single round can produce 2-5 crops that
  the model sees together.

```
emit_bubble(
  content,        // 一两句话整体描述 — must include all visible text
  intent,         // 用户想做什么（动宾短语，≤30字）
  type,           // info | location | solve
  intent_focus?,  // 可空
  confidence,     // 0.0~1.0
  details: [      // 详情页表格行
    {kind, label, value}, ...
  ]
)
```

**`content` is critical** — the system prompt forces the model to
list all visible text/numbers/brands/dates/prices in the content
field.  Example: for a tea package, the model must write
"包装文字: 品名 '工夫红茶', 净含量 '250g', 生产日期 '2020-12-01'".

**`details` is the structured table** rendered in the DetailScreen.
Each `{kind, label, value}` row matches a real piece of text in the
image.  `kind` ∈ {text, number, object, color, shape, logo, date,
price, brand, location, person, ...}.

## 2. Multi-round protocol

```
Round 1:
  messages = [user(image + "调用工具。")]
  LLM → tool_use (zoom_in or emit_bubble)
  body runs locally → ToolResult
  if body returned followUpJpeg:
    the real crop goes into the next user message

Round 2..30:
  LLM sees the crop, decides: zoom_in again (chain) or emit_bubble
  if emit_bubble:
    extract structured fields, build Bubble, end cycle
  if 2+ zooms already done:
    force emit_bubble in the next-round nudge

Stop when:
  - emit_bubble fires → final Bubble
  - 30 rounds hit (configurable in ToolUseLoop.MAX_ROUNDS,
    effectively unlimited for normal use)
  - timeout (20s per round, hardcoded in
    LlmClient.TOTAL_TIMEOUT_MS)
```

The image the LLM sees in round 1 is the **thumbnail** (768/q80).
The full-res photo stays in client memory and is only sent when a
`zoom_in` tool body crops a region.  The LLM therefore sees
**both** views when it asks for detail — the original (in round-1
context) and the crop (in the next user message).

## 3. Frame capture

`FrameAnalyzer` produces two encodings per capture:

- `thumbnail` (768 px max-dim, q80) — sent to the LLM as the
  round-1 image.  Balances bandwidth against recognition accuracy.
- `fullRes` (native, q95) — kept in memory so `zoom_in` can crop
  from it at near-original resolution.

There is **no stability gate** — the user controls when to capture
via a shutter tap, not "wait for scene to settle".

```
User taps shutter:
  captureLatestFrame()
    armed = true  (CAS — FrameAnalyzer's next frame is captured)
    state.analyzing = true

FrameAnalyzer.analyze() next frame:
  if !isArmed → return  (common case — user just looking)
  encode full + thumbnail JPEGs
  onFrame(CapturedFrame(thumb, full))
  armed = false  (only one frame captured per tap)

AppViewModel captures:
  if latestFrame == null → wait up to 500 ms
  runRecognitionCycle(frame)
    toolUseLoop.runCycle(thumb, full, "")
```

## 4. State machine

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

## 5. Bubble model

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
    val imageBytes: ByteArray,   // high-res JPEG for detail view
    val createdAtMs: Long,
    val toolName: String? = null,
    val needsUserInput: Boolean = false,
    val intentFocus: String? = null,
    val details: List<Detail> = emptyList(),  // details table rows
)
```

`Bubble` is FIFO-capped at 4 in `UiState.bubbles`; older bubbles
evict when a new one arrives. `imageBytes` is the original
high-res photo (the fullRes JPEG), not the 768/q80 thumbnail, so
the detail view can show a sharper image.

The `details` list drives the structured table in the DetailScreen
(kind | label | value columns). Each row is something the LLM
extracted from the image — text, numbers, brand names, dates, etc.
The LLM populates these in `emit_bubble`'s `details` input field.

## 6. Cancellation & concurrency

- `viewModelScope` is the only coroutine scope; tearing down the
  ViewModel cancels every in-flight recognition.
- `analyzing: AtomicBoolean` gates the camera analyzer so the next
  shutter tap is a no-op while a cycle is in flight.
- The OkHttp `EventSource.cancel()` is invoked from
  `suspendCancellableCoroutine.invokeOnCancellation` so the SSE
  connection drops the moment the coroutine is cancelled.
- Per-round timeout (20s) wraps the whole stream; stalled servers
  surface as a friendly `IllegalStateException("tooluse: 模型在 Nms 内未完成")`.

| Thread | Where | Why |
|---|---|---|
| `Executors.newSingleThreadExecutor` | `FrameAnalyzer.analyze` | CameraX guarantees serial execution; we own the loop on the analyzer thread |
| `Dispatchers.IO` | `LlmClient.streamToolUse` | OkHttp Sockets I/O + DNS |
| `viewModelScope` (Main) | `AppViewModel.runToolUseCycle` | UI state writes + Compose recomposition |
| `Main` | Compose recomposition | The whole app is single-Composable-Activity |

## 7. Tuning

| Knob | Default | Where | Purpose |
|---|---|---|---|
| `MAX_DIM` (thumbnail) | `768` | `FrameAnalyzer.kt` | max-dim cap for the LLM-facing image |
| `QUALITY` (thumbnail) | `80` | `FrameAnalyzer.kt` | JPEG quality for the LLM-facing image |
| `MAX_FULL_DIM` | `4096` | `FrameAnalyzer.kt` | cap for the in-memory full-res JPEG |
| `FULL_QUALITY` | `95` | `FrameAnalyzer.kt` | JPEG quality for the in-memory full-res JPEG |
| `MAX_ROUNDS` | `30` | `ToolUseLoop.kt` | soft cap; effectively unlimited for normal use |
| `TOTAL_TIMEOUT_MS` | `20_000L` | `LlmClient.kt` | per-round SSE timeout |
| `MAX_TOKENS` | `256` | `LlmClient.kt` | output token cap per round |
| `REQUEST_TEMPERATURE` | `0.0` | `LlmClient.kt` | locked at 0 for deterministic routing |
| `capture timeout` | `500L` ms | `AppViewModel.captureLatestFrame` | how long to wait for the analyzer's next frame |
| `BUBBLE_MAX` | `4` | `Models.kt` | FIFO cap on bubble list |
| `DEBUG_LOG_MAX` | `40` | `Models.kt` | ring-buffer cap on debug log |

## 8. Debug overlay

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

## 9. Configuration

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

## 10. Eval benchmark

`profiling/eval_rctw_v2.py` is the per-fixture composite scorer.
For 100 real RCTW-17 images (`profiling/ground_truth_rctw.json`,
GT parsed from the dataset's XML labels), it computes:

```
composite = 0.50 × r1_score + 0.50 × r2_score
r1_score  = 0.70 × (tool_pick == zoom_in/emit_bubble ? 1.0 : 0)
          + 0.30 × (input_valid)
r2_score  = 0.5 × text_match + 0.5 × type_match
text_match = 0.5 × keyword_命中率 + 0.5 × detail_命中率
  - keyword:  expected_description_keywords 出现在 LLM 的
              content 文本 + details[].value
  - detail:   expected_details (kind, label, value) 任一字段
              fuzzy 匹配 LLM 提取的 details
type_match = emit_bubble.type == 'info' ? 1.0 : 0
```

### 100 real RCTW-17 images (default category)

`profiling/ground_truth_rctw.json` has 100 scenes, all with
`category='default'` and `expected_type='info'`.  Images are
sampled from `/home/oppry/RCTW-171/train_images` (RCTW-17
ICDAR 2017 Chinese scene text dataset, 8034 train images).
Files renamed `rctw_default_NN.jpg`.  GT auto-parsed from the
dataset's XML labels (`/home/oppry/RCTW-171/train_gts/image_N.txt`):
8 corner-coords + difficulty + `"text"` per line.  We keep only
`difficulty=0` (legible) lines and convert to:
- `expected_description_keywords`: union of all visible text
- `expected_details`: first 6 `{kind, label, value}` rows
  (`kind = 'number'` if any digit, else `'text'`)

The orchestrator drives 1-3 rounds:
- R1: model sees thumbnail, calls `zoom_in` (default)
- R2: model sees crop, calls `zoom_in` again (chain) or `emit_bubble`
- R3: hard-nudged to `emit_bubble` if still zooming

Latest run (--resize 768 --quality 80, 100 fixtures):

| Config | Average composite |
|---|---|
| **768 / q80** | **0.652** |

```
r1_score 0.79 — model reliably picks zoom_in first
r2_type 1.00 — emit_bubble.type = 'info' consistent
r2_text 0.00-0.50 — model emits natural-language descriptions
             ("storefront sign with red text") but does NOT yet
             include the exact Chinese characters the GT
             expects ("大懒人冒菜", "AN REN", etc.)
```

The remaining bottleneck is r2_text — the model needs to
transcribe visible text rather than paraphrase.  Future work
will add embedding-based fuzzy match in the eval scorer
and force emit_bubble to populate `details[*].value` with raw text
(the GT already has those strings).

`profiling/runs/` keeps a measurement trail of past evals.

## 11. TODOs

- Release signing config + `isMinifyEnabled = true` (currently debug-only)
- Plumb `ANTHROPIC_AUTH_TOKEN` env var into the default token at
  build time so the debug APK works out-of-the-box without manual
  Settings entry
- CI: run `eval_rctw_v2.py --resize 768 --quality 80` on every
  commit; flag regressions > 0.05 in average composite
- r2_text: model needs to transcribe visible text verbatim.  Future
  iteration will add embedding-based fuzzy match in the eval scorer
  and force emit_bubble to populate `details[*].value` with raw text
  (the GT already has those strings).
