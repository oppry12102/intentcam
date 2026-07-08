# IntentCam — Architecture

> Companion doc to [README.md](README.md).  Quick start, build,
> and at-a-glance summary live there; this file is the deep-dive.
> See [CONFIG.md](CONFIG.md) for every tunable constant.

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
the crop as a `followUpJpeg`.  The orchestrator attaches it to the
next user message so the model sees the high-detail region alongside
the original.

- **Sibling mode** (default `source="original"`): crops the original
  2048-px fullRes.  Coords are absolute.  This is the **default**
  because the round-1 thumbnail is 1568-px (downsampled from 2048-px);
  cropping the thumbnail would only downsample again.  Sibling mode
  is the "real magnifier."
- **Chain mode** (`source="last"`): crops whatever was just produced.
  Use for deep zoom on a single region across multiple rounds.
- **Multi-zoom in one round**: every `zoom_in` call returns its
  own `followUpJpeg`; the orchestrator attaches all of them to the
  next user message in call order.  A single round can produce 2-5
  crops that the model sees together.

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

- `thumbnail` (1568 px max-dim, q90) — sent to the LLM as the
  round-1 image.  Balances bandwidth against recognition accuracy.
- `fullRes` (2048 px max-dim, q95) — kept in memory so `zoom_in`
  can crop from it.  Counter-intuitive win (2026-07-10): smaller
  than 4096-px makes zoom_in *more* effective because crops are
  more focused, less context dilution.

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

## 4. OCR (端云协同 — endcloud collaboration)

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
【read_text 全图扫描结果】on-device OCR 已扫过整张图...
  line 1: '建国路 100号' | bbox=[(0.10,0.20),...] | conf=0.95
  line 2: '禁止停车' | bbox=[(0.50,0.60),...] | conf=0.62
  ...
```

The same hint drives the `compare_text` tool's input — the LLM
can ask the OCR backend to re-scan a region with higher detail.

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
| `MAX_DIM` (thumbnail) | `1568` | `FrameAnalyzer.kt` | max-dim cap for the LLM-facing image |
| `QUALITY` (thumbnail) | `90` | `FrameAnalyzer.kt` | JPEG quality for the LLM-facing image |
| `MAX_FULL_DIM` | `2048` | `FrameAnalyzer.kt` | cap for the in-memory full-res JPEG |
| `FULL_QUALITY` | `95` | `FrameAnalyzer.kt` | JPEG quality for the in-memory full-res JPEG |
| `CROP_OUTPUT_MAX_DIM` | `1568` | `ImageOps.kt` | max-dim cap on `zoom_in` / `read_text` crops |
| `DEFAULT_CROP_QUALITY` | `90` | `ImageOps.kt` | JPEG quality for crops |
| `MAX_OCR_HINT_LINES` | `30` | `OcrEngine.kt` | top-N OCR blocks injected into round-1 user message |
| `LOW_CONFIDENCE_THRESHOLD` | `0.5` | `OcrEngine.kt` | OCR conf < 0.5 → mark `[LOW]` in hint |
| `MAX_ROUNDS` | `30` | `ToolUseLoop.kt` | soft cap; 兜底 Bubble on hit |
| `TOTAL_TIMEOUT_MS` | `60_000` | `LlmClient.kt` | per-round SSE timeout |
| `MAX_TOKENS` | `1024` | `LlmClient.kt` | output token cap per round |
| `REQUEST_TEMPERATURE` | `0.0` | `LlmClient.kt` | locked at 0 for deterministic routing |
| `capture timeout` | `500` ms | `AppViewModel.captureLatestFrame` | how long to wait for the analyzer's next frame |
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
r1_score     = 0.70 × (tool_pick == zoom_in/emit_bubble ? 1.0 : 0)
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

`profiling/eval_*.json` keeps every JSON dump; use
`profiling/diff_eval.py` to compare two runs side-by-side.

### Ablations (tested, rejected)

| Proposal | Δ | Why rejected |
|---|---|---|
| `MAX_OCR_HINT_LINES` 30→20 | -0.012 | r2_text_fuzzy -0.042; model was using those lines |
| `MAX_ROUNDS` 30→15 | -0.012 | rctw_default_10 hit cap → 兜底 empty → -0.257 |
| r1/r2 weights 0.50/0.50→0.40/0.60 | -0.069 | Pure rebalance; r1 near-ceiling; user chose headline tracking |

## 13. TODOs

- Release signing config + `isMinifyEnabled = true` (currently debug-only)
- Plumb `ANTHROPIC_AUTH_TOKEN` env var into the default token at
  build time so the debug APK works out-of-the-box without manual
  Settings entry
- CI: run `profiling/eval_*` on every commit; flag regressions
  > 0.05 in average composite
- r2_text: lift the r2_text ceiling (currently 0.74 strict, 0.63
  fuzzy).  The model is at the limit of the 1568-px thumb + OCR
  hint; next experiments should explore higher resolution crops,
  text-region detection, or a tool that returns structured text
  per region.
