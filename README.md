# IntentCam

A real-time intent-recognition Android app: point the back camera at
something, the model figures out what you want to do with it.

The system is **LLM-driven end-to-end**. There is **no on-device CV**
(OCR / object detection / barcode scanning were all tried and removed
— see commit history). The model itself reads the image, picks a
specialized tool, and writes a structured final answer. If the
initial frame doesn't carry enough detail, the model can ask the
client to crop a region at native pixels and look again.

```
                ┌──────────────────────────────────┐
                │  Camera (back, preview only)     │
                └────────────────┬─────────────────┘
                                 │ tap shutter
                                 ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  FrameAnalyzer → two JPEGs                                      │
   │    thumbnail:  768 max-dim, q80   (sent to LLM, ~70 KB)         │
   │    fullRes:    native, q95        (kept in memory for crops)    │
   └─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  ToolUseLoop.runCycle  (multi-round, suspend)                  │
   │                                                                 │
   │  Round 1:  LLM sees thumbnail, calls one interpret_image tool  │
   │  Round 2:  tool body returns; LLM calls emit_bubble (or another │
   │            tool, including zoom_in)                            │
   │  …                                                                │
   │  Done when: emit_bubble fires  |  ask_user asks for clarification│
   │            |  error timeout  |  max rounds hit                  │
   └─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  AppViewModel  → UiState.bubbles (Compose)                       │
   │  Detail view shows "via {toolName}" + action_chips              │
   │  Tap a chip → new chip-direct cycle via runWithTool              │
   └─────────────────────────────────────────────────────────────────┘
```

---

## 12-tool architecture

| Tool | What it does | When LLM picks it |
|---|---|---|
| `default_describe` | fallback, free-form | truly ambiguous scenes |
| `identify_animal_or_plant` | animal / plant ID | picture of a pet, flower, etc |
| `identify_product` | product label | branded item (food, drink) |
| `navigate_to_block` | location / street | road sign, map, street scene |
| `scan_qr_code` | QR decode | image has a QR code |
| `translate_text` | foreign text | English/Japanese text in image |
| `solve_problem` | math / logic | equations, formulas |
| `read_screen` | phone screen | notifications, app UI |
| `read_manual` | manual / recipe / contract | multi-line documents |
| `read_device_reading` | BP / BMI / temp | health-device display |
| `ask_user` | **generic clarification** | "I'm not sure what you want" |
| `emit_bubble` | **final answer** | end the cycle with structured fields |

Every tool body is currently a **thin pass-through** that returns a
`toolSummary` string. The actual vision work happens in **round 2**
where the LLM re-reasons over the image with the tool result as
context. This is intentional: it pushes the heavy lifting to the LLM
and keeps the local code trivially auditable.

The two exceptions are:

- **`navigate_to_block`** with empty `destination` — returns
  `needsUserInput = true`, the UI shows a dialog, the typed answer
  flows back as `userText` in the next round.
- **`emit_bubble`** — its body extracts the structured fields
  (`scene / intent / type / confidence / action_chips`) from its own
  input and returns them as a final Bubble. The orchestrator pulls
  them straight through to the UI without parsing JSON.

### ask_user — generic clarification

`ask_user(question: string)` is the **general-purpose** form of
"need more info from the user". Any tool body (or the model itself
when uncertain) can return `needsUserInput = true` and a
`userInputPrompt`. The orchestrator surfaces a dialog; the typed
answer resumes the cycle with `userText` set. Used by
`navigate_to_block` for destination lookup; the same plumbing
covers any future "I need to ask" use case.

### action_chips — model-suggested follow-ups

`emit_bubble` schema includes an optional `action_chips` array of
`{label, tool, tool_input}` items. The LLM uses this to suggest
0-3 follow-up actions at final-answer time (e.g. for a tea label
it might emit:

```json
"action_chips": [
  {"label": "看配料", "tool": "identify_product", "tool_input": {"focus": "ingredients"}},
  {"label": "查保质期", "tool": "read_manual", "tool_input": {}}
]
```

The detail view renders them as tappable Surface chips. Tapping
one calls `ToolUseLoop.runWithTool` to invoke the saved tool+input
directly — skipping round 1's tool-pick entirely.

### zoom_in — detail-on-demand

When the LLM can't read a region at 768px (small text, dense layout),
it calls `zoom_in({x, y, w, h, focus})` with **normalized** coords.
The client's `BitmapRegionDecoder` crops the **full-resolution**
JPEG that FrameAnalyzer kept in memory, returns the crop as a
`followUpJpeg`. The orchestrator attaches it to the next user
message so the model sees a high-detail region alongside the original.

**Chain mode** (default `source = "last"`): the second `zoom_in`
call crops whatever was just produced. Calling it twice with default
source gives a chained crop of a crop — iterative zoom-in.

**Sibling mode** (`source = "original"`): the second call crops
the original full-res photo instead. Coords are absolute. Use this
when the model wants to see two different parts of the original
in the same round.

**Multi-zoom in one round**: every `zoom_in` call returns its own
`followUpJpeg`; the orchestrator attaches **all of them** to the
next user message in call order. A single round can produce 2-5
crops that the model sees together.

End-to-end demo on `IMG2.jpg` (a real phone photo of a tea label
with dense fine text):
- R1: 3 `zoom_in` calls covering different label regions
- R2: 2 more `zoom_in` calls drilling into sub-regions
- R3: 1 `emit_bubble` with every previously-missed detail
  (production address, seller name, insurance line) now correct

### Frame resolution benchmark

`profiling/eval_resize.py` simulates FrameAnalyzer's JPEG rescaling
end-to-end against the 9-fixture eval set (3 runs, temp = 0).
Average composite scores (lower is worse, 1.0 is perfect):

| Config | base64 / frame | Real phone photo |
|---|---|---|
| 256 / q50 | ~7 KB | 主品类都错（红茶→花生），SC 编号乱码，**不可用** |
| 384 / q60 | ~15 KB | 主字段对，小字漏（生产地址漏、销售商错） |
| 512 / q75 | ~40 KB | 几乎全对 |
| 768 / q80 | ~70 KB | 几乎全对（**当前**） |
| 768 + zoom_in | ~70 KB + 跟随 | **全对 + 所有细节** |

768 won because the LLM internally downsamples to its ViT patch
grid anyway; packing more original detail into the same number of
patches helps. Adding `zoom_in` on top closes the last ~10% gap
on real-world dense text.

---

## Multi-round protocol

```
Round 1:
  messages = [user(image + "调用工具。")]
  LLM → tool_use block (one of 12 tools)
  body runs locally → ToolResult

Round 2..N:
  if body returned followUpJpeg:
    messages += user(cropped image + "放大区域" + tool_result)
  else:
    messages += user(tool_result)
  LLM → next tool_use (zoom_in / emit_bubble / another tool)
  …

Stop when:
  - emit_bubble fires → final Bubble
  - ask_user / needsUserInput fires → UI dialog → user types → resume
  - 4 rounds hit (configurable via LlmConfig.maxToolRounds, hardcoded
    in ToolUseLoop.MAX_ROUNDS)
  - LLM returns text without tool_use → fallback parse (rare)
  - timeout (20s per round, hardcoded in LlmClient.TOTAL_TIMEOUT_MS)
```

The image the LLM sees in round 1 is the **thumbnail** (768/q80).
The full-res photo stays in client memory and is only sent when a
`zoom_in` tool body crops a region. The LLM therefore sees
**both** views when it asks for detail — the original (in round-1
context) and the crop (in the next user message).

---

## State machine

```
NEED_PERMISSION ──── onPermissionsGranted ────► SCANNING
                                                      │
                                          tap shutter  │
                                                      ▼
                                                  ANALYZING
                                                      │
                            ┌─── Outcome.Bubble ─────┤─── Outcome.PendingUserInput ───► USER_INPUT
                            │                         │
                            ▼                         │
                       (bubble shown)                 │  submitUserInput(text) → resume
                            │                         │
                  tap bubble│                         │
                            ▼                         │
                     SHOWING_DETAIL                   │
                            │                         │
              tap 退出  /  tap chip                    │
                            │                         │
                            └────────► (back to SCANNING) ◄───── cancel ◄─────┘
                                                      
                  any → SETTINGS (openSettings / closeSettings → restartScanning)
```

State writes go through `MutableStateFlow` which is thread-safe via
CAS. Every `analyzing` write is paired with the corresponding
`analyzing: AtomicBoolean` so the camera analyzer's `isBusy()`
callback always sees the latest value.

---

## Frame capture (the shutter path)

There is **no stability gate** anymore. The previous 1-second
"wait for scene to settle" gate was removed in commit `9cc4746`
when the architecture moved from "passive scene detection" to
"active user trigger":

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
    toolUseLoop.runCycle(thumb, full, userText)
      ↳  Outcome.Bubble / PendingUserInput / Error
```

`latestFrame` is cleared on tap; the next tap can fire a fresh
capture immediately. There is no longer a notion of "scene changed"
— the user is in control.

The camera preview keeps running independently of the analyzer,
so the live feed is unaffected by recognition cycles. The preview
+ analyzer are both CameraX subscribers on the same ImageAnalysis
output.

---

## Cancellation & concurrency model

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
| `Executors.newSingleThreadExecutor` | FrameAnalyzer | CameraX analyzer thread; we own the loop |
| `Dispatchers.IO` | LlmClient.streamToolUse | OkHttp Sockets I/O + DNS |
| `viewModelScope` (Main) | AppViewModel.runToolUseCycle, runChip | UI state writes + Compose recomposition |
| `Main` | Compose recomposition | The whole app is single-Composable-Activity |

---

## Bubble model

```kotlin
data class Bubble(
    val id: String,
    val type: String,         // "info" | "location" | "solve"
    val title: String,         // user-facing intent, ≤12 chars
    val detail: String,        // scene description
    val confidence: Float,     // 0.0 .. 1.0
    val imageBytes: ByteArray,  // high-res JPEG for detail view
    val createdAtMs: Long,
    val toolName: String? = null,   // e.g. "read_device_reading"
    val needsUserInput: Boolean = false,  // placeholder pending reply
    val chips: List<ActionChip> = emptyList(),  // model-suggested follow-ups
)
```

`Bubble` is FIFO-capped at 4 in `UiState.bubbles`; older bubbles
evict when a new one arrives. `imageBytes` is the original
high-res photo (the fullRes JPEG), not the 768/q80 thumbnail, so
the detail view can show a sharper image.

`ActionChip(label, toolName, toolInputJson)` carries the raw
JSON string of the chip's saved input; the orchestrator
re-parses it when the user taps the chip.

---

## Debug overlay

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
"Key X was already used" and killing the process. With `seq`
every key is unique, the panel scrolls cleanly.

---

## Configuration

The settings screen (top-right gear icon) lets you override

- `ANTHROPIC_BASE_URL` — default `https://api.minimaxi.com/anthropic`
- `ANTHROPIC_AUTH_TOKEN` — **field is always blank in the UI**;
  blank saves preserve whatever token is currently active (default
  if blank, custom otherwise). The real token never appears on
  screen. `Models.kt` ships a `REPLACE_AT_RUNTIME` placeholder;
  real builds need either a runtime token (Settings) or an env
  var set at compile time.
- `ANTHROPIC_MODEL` — default `MiniMax-M3`

Values are persisted to `SharedPreferences` via `SettingsStore`.

---

## Build

The dev APK is built with:

```bash
JAVA_HOME=/path/to/jdk17 /path/to/gradle clean :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ./intentcam.apk
```

Always run `clean` before measuring APK size — incremental builds
accumulate stale native libs and inflate the artifact from ~10 MB
to ~100 MB. The clean artifact is the real one.

`intentcam.apk` (~10 MB) is the dev build; release would benefit
from `isMinifyEnabled = true` and an actual signing config (neither
wired up yet — see TODO at bottom).

---

## Tuning

| Knob | Default | Where | Purpose |
|---|---|---|---|
| `MAX_DIM` (thumbnail) | `768` | `FrameAnalyzer.kt` | max-dim cap for the LLM-facing image |
| `QUALITY` (thumbnail) | `80` | `FrameAnalyzer.kt` | JPEG quality for the LLM-facing image |
| `MAX_FULL_DIM` | `4096` | `FrameAnalyzer.kt` | cap for the in-memory full-res JPEG |
| `FULL_QUALITY` | `95` | `FrameAnalyzer.kt` | JPEG quality for the in-memory full-res JPEG |
| `MAX_ROUNDS` | `4` | `ToolUseLoop.kt` | hard cap on rounds per recognition cycle |
| `TOTAL_TIMEOUT_MS` | `20_000L` | `LlmClient.kt` | per-round SSE timeout |
| `MAX_TOKENS` | `256` | `LlmClient.kt` | output token cap per round |
| `REQUEST_TEMPERATURE` | `0.0` | `LlmClient.kt` | locked at 0 for deterministic routing |
| `capture timeout` | `500L` ms | `AppViewModel.captureLatestFrame` | how long to wait for the analyzer's next frame |
| `BUBBLE_MAX` | `4` | `Models.kt` | FIFO cap on bubble list |
| `DEBUG_LOG_MAX` | `40` | `Models.kt` | ring-buffer cap on debug log |

---

## Eval benchmark

`profiling/evaluate_tooluse.py` is the per-fixture composite scorer.
For each of 9 fixtures it computes:

```
composite = 0.50 × round1_score + 0.50 × round2_score
round1_score = 0.70 × (tool_pick_correct) + 0.30 × (input_valid)
round2_score = keyword match against expected `must_have` /
               `acceptable_intent_keywords` in the model's final text
```

`profiling/eval_resize.py` adds `--resize N --quality Q` flags that
simulate FrameAnalyzer's JPEG rescaling before the LLM call, used
to benchmark 256/384/512/768 against each other. Latest averages
(3 runs, temp = 0):

| Config | Average composite |
|---|---|
| 256 / q50 | 0.858 |
| 384 / q60 | 0.840 |
| 512 / q75 | 0.750 |
| 768 / q80 | **0.827** |
| 768 / q80 + zoom_in | **0.819** |

Synth fixtures are clean test images — eval differences between
configs are within temp=0 variance. The real story is the
end-to-end OCR test on `IMG2.jpg` (real phone photo), where
768 + `zoom_in` recovers every previously-missed fine-text field
(see "Frame resolution benchmark" above).

### Real-photo benchmark (50 fixtures)

`profiling/ground_truth_real.json` defines 50 fixtures across 10
real-world categories (5 each): `food_label`, `device_reading`,
`math`, `receipt`, `street_sign`, `menu`, `qr_code`, `map`,
`english_text`, `screen_capture`.  Images are pulled from Picsum
(no-auth CDN) and overlaid with category-relevant text via Pillow
in `fetch_real_imgs.py`; the script is deterministic per seed so
the set is reproducible.

`profiling/eval_real.py` scores these against the tool-use
protocol.  Per-fixture `expected_tool` is derived from
`category` (e.g. `food_label → identify_product`,
`street_sign → navigate_to_block`).  Round-2 scoring
is the average of (a) emit_bubble's `type` matching
`expected_top_intent_type` and (b) the final text matching
`must_have_in_scene_or_observation` AND
`acceptable_intent_keywords` OR-groups.  Latest run
(`--resize 384 --quality 60`, see `profiling/runs/real_run_v1.txt`):

| Config | Average composite | Best categories |
|---|---|---|
| 256 / q50 | 0.712 | map (0.71), math (0.83), food_label (0.80) |
| **384 / q60** | **0.747** | **map (0.99), math (0.84), screen_capture (0.83)** |
| 512 / q75 | 0.710 | math (0.85), street_sign (0.88), map (0.69) |
| 768 / q80 | 0.642 | math (0.83), screen_capture (0.69), map (0.65) |

`384 / q60` wins on real photos, contradicting the synth-fixture
result.  Two reasons: (1) Picsum test images are 800×600 with
text overlaid at a fixed pixel position; resizing the image
to 384 makes the overlay text a *larger fraction* of the frame
and easier for the LLM to read, (2) per-category variance
(high) swamps the global average, especially for `receipt` and
`menu` where the model often picks the wrong tool.  On real
phone photos (no synthetic overlay, native resolution) the
relative size of the text is preserved, so this quirk doesn't
recur — `768 / q80` is still the right config for production.

Categories where the model is consistently weak:
- **`receipt` (0.48-0.57)**: model often picks `default_describe`
  instead of `read_manual` / `read_device_reading`; pricing
  questions on receipts are ambiguous between "read the total" and
  "reconcile the line items".
- **`menu` (0.48-0.63)**: model picks `read_manual` (Chinese
  menu) but the GT expects `translate_text` semantics.
- **`qr_code` (0.52-0.79)**: model often picks `translate_text`
  (the surrounding caption) instead of decoding the QR.

The 50-photo set is the main signal for tool-routing
regressions.  Re-run after any change to tool descriptions
or system prompts.

`profiling/runs/` keeps a measurement trail of past evals so
future changes can be compared against the same fixtures.

---

## Key files

```
app/src/main/java/com/example/intentcam/
├── AppViewModel.kt          — capture → runCycle → bubble, user-input, chip-direct
├── FrameAnalyzer.kt         — CameraX → dual JPEGs (thumbnail + fullRes), crop helper
├── LlmClient.kt             — Anthropic-compatible SSE, tool_use content block parsing
├── MainActivity.kt          — Camera preview + Compose UI (incl. detail view with chips)
├── Models.kt                — Bubble / ActionChip / UiState / LlmConfig
├── SettingsScreen.kt        — Compose settings sheet
├── SettingsStore.kt         — SharedPreferences wrapper
├── Theme.kt                 — Compose theme
├── Tools.kt                 — ToolDef / ToolRegistry / ToolContext / ToolResult
├── ToolImplementations.kt   — 12 default tool bodies (10 interpret + ask_user + zoom_in + emit_bubble)
└── ToolUseLoop.kt           — orchestrator: round-trip, dispatch, followUpJpeg chain

profiling/
├── evaluate_tooluse.py      — composite scorer, 9 fixtures
├── eval_resize.py           — same + --resize/--quality for FrameAnalyzer simulation
├── ground_truth_tooluse.json — 9 fixtures (T_BP_METER, T_TEA_LABEL, …)
├── test_tooluse.py           — one-shot smoke test of model + tools[]
├── bench_pipeline.py         — Pillow microbenchmark of pipeline stages
├── fetch_real_imgs.py        — pulls Picsum photos for real-world eval
├── gen_img100.py             — generates 100 synthetic test fixtures
└── runs/                     — measurement trail of past eval runs
```

---

## TODOs

- Release signing config + `isMinifyEnabled = true` (currently debug-only)
- Plumb `ANTHROPIC_AUTH_TOKEN` env var into the default token at
  build time so the debug APK works out-of-the-box without manual
  Settings entry
- CI: run `eval_resize.py --resize 768 --quality 80` on every
  commit; flag regressions > 0.05 in average composite
- The 9-fixture eval set is dominated by clear synth images; build
  a real-photo benchmark set with hand-tagged ground truth (the
  current `IMG1.jpg` / `IMG2.jpg` are 2 of those; need ~20 more)
