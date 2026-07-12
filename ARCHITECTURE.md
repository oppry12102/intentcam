# IntentCam — Architecture

> Companion doc to [README.md](README.md).  Quick start, build,
> and at-a-glance summary live there; this file is the deep-dive.
> See [CONFIG.md](CONFIG.md) for every tunable constant.

## 1. The four-tool design (v1.1, 2026-07-12)

Phase 2 (2026-07-11) collapsed the previous 12-tool design to
three.  v1.1 added a fourth: `extract_text` — a text-only sibling
of `zoom_in` for cases where the model has already seen the region
in the round-1 thumbnail and only wants verbatim characters.

| Tool | What it does |
|---|---|
| `zoom_in` | crop a region at native resolution + auto-OCR + return image |
| `extract_text` | crop a region + OCR, return ONLY text (no image) |
| `compare_text` | pure on-device string diff (model reading vs OCR hint) |
| `emit_bubble` | end the cycle with a structured answer |

**Routing rule (v1.1)**: Step 2 of the workflow now defaults to
`extract_text` for [LOW] / 漏扫 / 已见区域 cases.  `zoom_in` is
reserved for when the model needs to **see new pixels** (corner
text not in the round-1 thumbnail, or shape/color/object details
that the LLM needs to look at directly).  See §4.4 for why.

`extract_text` was adopted by the model in **5-7 of 20 fixtures**
(25-35%) in the v1.1 eval runs — typically cases where the model
had already seen the region in the round-1 thumbnail and just
needed a fresh high-fidelity OCR scan.  Composite @20 was
statistically flat vs v1.0 (0.883 mean across 3 runs vs 0.887
baseline, in the noise band).

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
  type,           // 11 intent ids — see §15 / CONFIG §H.1
                  //   OBSERVE family: info | location | warning_safety
                  //                  | menu_food | hours_schedule
                  //   ACT_ON family:  solve | phone | real_estate_rental
                  //                  | recruit_hiring | payment_qr | id_document
  intent_focus?,  // 可空
  confidence,     // 0.0~1.0
  action_ids?,    // 可空 — Intent↔Action framework (Phase A+, 2026-07-10)
                  //   list of canonical action ids the model recommends:
                  //   dial_number / copy_listing / save_posting /
                  //   scan_to_pay / redact_id / open_in_maps /
                  //   copy_warning / copy_menu / copy_hours
  details: [      // 详情页表格行
    {kind, label, value, bbox?}, ...
  ]
)
```

The expanded `type` vocabulary (11 ids vs the original 3) is
backed by the **Intent↔Action framework** — see §15 for the
full design, CONFIG §H for the registry knobs, and §7 below for
how the resulting `Bubble` carries `actionIds` through the UI.

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

### 4.4 v1.1 (2026-07-12): `extract_text` and the routing rule

Pre-v1.1 Step 2 of the workflow said "调 zoom_in" for any
unclear region.  This was wasteful when the model had already
seen the region in the round-1 thumbnail — it was paying
~1 image-equivalent in tokens just to get a fresh OCR scan of
text it had already localized.

v1.1 splits Step 2 into two paths:

- **`extract_text(x, y, w, h)`** — the default when:
  - the region is `[LOW]` in the round-1 OCR hint (re-scan the
    same bbox at fullRes, get the verbatim characters back)
  - the region is `OCR-漏扫` but the model **already sees it**
    in the round-1 thumbnail (just want verbatim chars)
  - you want to fan-out: re-scan multiple regions in one round
    without paying image-token cost for each
- **`zoom_in(x, y, w, h, source)`** — required when:
  - the region is **not in the round-1 thumbnail** at all
    (corner text that was cropped off, or new details the
    round-1 view didn't show)
  - you need to see the image to understand non-text content
    (color / shape / object identification)

Implementation: `extract_text` reuses `cropJpegRegion` +
`OcrEngine.recognize` (the same path `zoom_in` follow-ups take
for auto-OCR), then formats the result with the same
`formatHint(isCropHint=true)` shape.  The only difference vs
`zoom_in`'s follow-up is that `extract_text` does NOT attach the
cropped image to the next round's user message — only the
formatted OCR text.  That single change is the whole value prop.

**Adoption**: model picked `extract_text` in 5-7 of 20 fixtures
(25-35%) in v1.1 eval runs, vs 0/20 in v1.0 (where the tool
didn't exist).  The model self-routes correctly — it uses
`extract_text` for character-only queries and `zoom_in` for
"show me new pixels" queries, with no apparent confusion.

**Composite @20**: v1.1 mean = 0.883 (3 runs: 0.880/0.862/0.908,
std 0.023) vs v1.0 baseline 0.887.  In the noise band; no
measurable regression.  v1.1 is a capability unlock, not a
ceiling bump — the gain is structural (model has more routing
options) rather than numerical.

**Scorer fix**: EvalRunner's first-tool scorer was treating
`extract_text` as the "other tool" fallback (pickScore 0.7).  Fixed
in v1.1 — `extract_text` is now a valid recon tool (pickScore
1.0), matching the production tool surface.

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
    val type: String,            // 11 intent ids — see §15 / CONFIG §H.1
    val title: String,            // user-facing intent, ≤30 chars
    val detail: String,           // content description
    val confidence: Float,
    val imageBytes: ByteArray,   // 4096-px fullRes JPEG for detail view
    val createdAtMs: Long,
    val toolName: String? = null,
    val needsUserInput: Boolean = false,
    val intentFocus: String? = null,
    val details: List<Detail> = emptyList(),  // details table rows
    val actionIds: List<String> = emptyList(),  // Phase A+, 2026-07-10
    val llmProposedActions: List<String>? = null,  // raw model emit (audit trail)
)
```

`Bubble` is FIFO-capped at 4 in `UiState.bubbles`; older bubbles
evict when a new one arrives. `imageBytes` is the 4096-px fullRes
JPEG (not the 3200-px thumbnail), so the detail view can show a
sharper image.

The `details` list drives the structured table in the DetailScreen
(kind | label | value columns). Each row is something the LLM
extracted from the image — text, numbers, brand names, dates, etc.
The LLM populates these in `emit_bubble`'s `details` input field.

`actionIds` is populated by **either** the model's `action_ids`
emit (C3 v3 prompt table — see §15.4) or by the verifier's
additive inject path (`IntentVerifier.actionFor(type)` —
Phase F invariant: never delete, only add).  This is the
per-bubble action surface the chip UI renders in the detail
screen header — see §15 for the full design.

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
| `extract_text` (v1.1) | text-only OCR | `ToolImplementations.kt:210-330` | v1.1: new tool. Same crop path as `zoom_in` follow-up but no `followUpJpeg` — returns only OCR text. Model picks it for 25-30% of fixtures when the region is already visible in the round-1 thumbnail. |
| `IntentDecl.registerDefaultIntents()` | **11 ids** | `shared/.../IntentDecl.kt:82-182` | Phase G — what the user wants (intent classification). The 3 v1.0 ids (`info`/`location`/`solve`) plus 4 PII (real_estate/recruit/payment_qr/id_document) plus 3 OBSERVE Phase G (warning/menu/hours) plus `phone` (Phase A). |
| `ActionDecl.registerDefaultActions()` | **10 defs** | `app/.../ActionDecl.kt:158-415` | what the app can do per-intent. 5 carry `userPrefKey` (SettingsStore consent toggle, OFF default). `scan_to_pay` and `redact_id` are Toast-only by design. |
| `IntentVerifier` | **10 passes + post-guard** | `shared/.../IntentVerifier.kt` | post-emit_bubble regex flip — `info`/`location` → `phone`/`payment_qr`/`recruit`/`real_estate`/`id_document`/`warning`/`menu`/`hours` based on corpus signal. Phase F invariant: modifies `bubble.type` only, never `bubble.actionIds`. |
| `actionFor(type)` | **11 type → 9 canonical action maps** | `IntentVerifier.kt:156-167` | Phase F — ToolUseLoop additive inject. **3-register lockstep** when adding a new intent: ActionDecl + EvalRunner.defaultActionIds + actionFor(). Drift = silent r3 regression. |
| **3-register lockstep** | invariant | Phase F (2026-07-11) | Adding a new intent requires lockstep edits in 3 files (or 4 if you also add a C3 v3 prompt row). See §15.5 / CONFIG §J.1. |

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

The RCTW-100 column is the legacy OCR-aware baseline;
`phone_20` / `pii_20` / Phase G are the Intent↔Action framework's
**true validation suites** (RCTW's `expected_type="info"` doesn't
exercise intent diversity — see CONFIG §L).  All suites run on
the same `:shared:eval` Kotlin code path; the deltas are
architectural, not noise.

| Date | RCTW @100 | phone_20 @20 | pii_20 @20 | Phase G @15 | Key change |
|---|---|---|---|---|---|
| 2026-07-06 | 0.652 | — | — | — | 1-only image strategy landed |
| 2026-07-07 | 0.819 | — | — | — | timeout 60s + 兜底 Bubble + details cap + type-partial-credit |
| 2026-07-08 | 0.835 | — | — | — | OCR-aware collaboration (端云协同) |
| 2026-07-10 | 0.838 | — | — | — | softened prompt (no over-hedge on imperfect OCR) |
| 2026-07-10 | 0.841 | — | — | — | 1568 thumb + zoom_in=original + "thumbnail ≠ 原图" nudge |
| 2026-07-10 | **0.853** | — | — | — | @100 verification (12W/8L/0T) |
| 2026-07-10 | 0.898 | — | — | — | `MAX_FULL_DIM` 4096→2048 (counter-intuitive win) |
| 2026-07-11 | 0.874 | — | — | — | **Phase 2a**: auto-OCR on every zoom_in crop + 4-step workflow prompt |
| 2026-07-11 | **0.868** | — | — | — | **Phase 2**: `read_text` tool removed |
| 2026-07-10 | 0.939 | 0.933 | — | — | **v1.3 ship (A2 scorer fix)**: `info↔location=1.0`; MAX_TOKENS=3072 |
| 2026-07-11 | 0.951 | — | — | — | Step 2-5 ship: SettingsStore enabledActionIds + chip UI + open_in_maps |
| 2026-07-11 | — | 0.941 | 0.872 | — | **Phase A+B**: phone + 4 PII intents shipped; C2 nudge |
| 2026-07-11 | — | 0.963 | 0.864 | — | **C2 prompt**: "action_ids 默认应填" lifts phone r3 0.75→0.85 |
| 2026-07-11 | — | 0.917 | 0.853 | — | **Phase E**: 6-rule verifier post-emit_bubble flip; image_1359 lifted |
| 2026-07-11 | — | 0.929 | 0.852 | — | **E3**: rule 8 `real_estate_rental + MOBILE + !REAL_ESTATE → phone` |
| 2026-07-11 | — | **0.9394** | 0.864 | — | **Phase F**: actionFor() + additive inject — phone history-high |
| 2026-07-11 | — | 0.9394 | **0.8794** | — | **C3 v3**: prompt table 6→9 replaces soft C2 nudge; +0.015 pii_20 |
| 2026-07-12 | — | 0.9344 | 0.8794 | 0.973 | **Phase G**: warning/menu/hours + verifier Pass 8/9/10 + post-guard |
| 2026-07-12 | — | — | **0.9631** | 0.973 | **GT schema dual-read** (cumulative Phase F + C3 v3 + this fix) |

**Lift provenance** for the pii_20 headline jump
(`0.8794 → 0.9631 = +0.0837`):

- Phase F (verifier flips): ~ +0.005-0.01
- C3 v3 (prompt table): ~ +0.015
- **GT schema dual-read (this fix)**: image_3285 alone lifted `r2_type
  0.5 → 1.0` = +0.225 on that fixture; ~80% of the headline delta
- The RCTW @100 column is **unchanged** by Phase F/C3/G (RCTW uses
  `expected_type="info"` and was already read correctly) — only
  intent-diverse suites (`phone_20` / `pii_20` / Phase G) carry
  the Intent↔Action lift.

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
- Verify post-guard phone_20 lift (`-0.026` net at 0.9081) — pending
  decision (a) add LANDLINE to Pass 1, (b) add `!MOBILE` guard to
  Pass 8, (c) revert post-guard, (d) accept + monitor. See
  `eval-phone20-postguard-2026-07-12.md`.
- Ship C3 v3's `copy_menu` 600-char cap into the eval scorer
  (Phase G action body has it but the LLM isn't advised about it
  in the prompt).

## 15. Intent↔Action framework (2026-07-10 → 2026-07-12)

Added on top of the visual pipeline as a separate classification +
action layer.  Each `emit_bubble` now carries an 11-vocabulary
intent `type` and a list of canonical `action_ids` the app can
render as chips.  A 10-pass verifier + post-guard silently
correct mis-classifications using on-image signals.

### 15.1 Module map

```
                    ┌─────────────────────────────────────────────┐
                    │ IntentDecl.kt (shared)                      │
                    │   registerDefaultIntents() — 11 ids        │
                    │   family / label / LLM hint / fallback     │
                    └────────────────┬────────────────────────────┘
                                     │ IntentDecl.byId(...)
                                     │
   User photo  ─►  LLM  ─emit_bubble(type, action_ids)─►       Bubble
                                              │                ▲
                                              ▼                │
                    ┌─────────────────────────────────────────┐  │
                    │ IntentVerifier.kt (shared)               │  │
                    │   post-emit_bubble — silent type flip    │──┘
                    │   pass 1-1e (location source)             │
                    │   pass 2-10 (info source)                │
                    │   pass 7 (real_estate MOBILE guard)      │
                    │   post-guard (final LANDLINE / SERVICE)  │
                    │   actionFor(type) — canonical injection  │
                    └────────────────┬──────────────────────────┘
                                     │ flip + additive inject
                                     ▼
                    ┌─────────────────────────────────────────┐
                    │ ActionDecl.kt (app)                      │
                    │   registerDefaultActions() — 10 defs    │
                    │   applicableIntents / applicableFamilies │
                    │   userPrefKey (consent toggle, OFF def.) │
                    │   Toast-only for scan_to_pay, redact_id │
                    └────────────────┬──────────────────────────┘
                                     │
                                     ▼
                              Bubble UI chips
                              (runAction on tap)
```

**`shared` vs `app` split is intentional**: `IntentDecl` and
`IntentVerifier` live in `:shared` because `:shared:eval`
imports them; `ActionDecl` lives in `:app` because action
intents (dial / share sheet) are Android-platform-specific
(Toast, `ACTION_SEND`, `ACTION_DIAL`).

### 15.2 IntentDecl — 11 ids, 2 families

| Intent id | Family | LLM hint (Chinese) |
|---|---|---|
| `info` (`FALLBACK_ID`) | OBSERVE | 描述信息（默认）: 物体/文字/数字/概念 |
| `location` | OBSERVE | 定位: 路标/地名/找这家店 |
| `solve` | ACT_ON | 解决问题: 翻译/公式/解题 |
| `phone` | ACT_ON | 拨号: 手机号/座机/400电话/服务热线 |
| `real_estate_rental` | ACT_ON | 租房: 出租/二手房/房源/中介 |
| `recruit_hiring` | ACT_ON | 招聘: 招工/求职/兼职/高薪 |
| `payment_qr` | ACT_ON | 支付: 扫一扫/收款码/付款码/转账 |
| `id_document` | ACT_ON | 证件: 身份证/营业执照/车牌 |
| `warning_safety` | OBSERVE | 警示: 请勿/禁止/警告/危险/注意 |
| `menu_food` | OBSERVE | 菜单: 菜品/套餐/招牌菜/主厨推荐/价格表 |
| `hours_schedule` | OBSERVE | 营业时间: 营业中/HH:MM-HH:MM/营业时段 |
| `route_to` | OBSERVE | 导航: 箭头/方位词/步行 N 米/步行 N 分钟/前方/出口/入口 |

**Family equivalence** (scoring): same family → 1.0;
cross-family (OBSERVE↔ACT_ON) → 0.5; empty → 0.0.  v1.3's A2
fix promoted `info ↔ location` 0.5 → 1.0; Phase G extends the
OBSERVE family with 3 more ids (`warning_safety` / `menu_food`
/ `hours_schedule`) so they interchange with `info` for full
credit too.

### 15.3 ActionDecl — 10 defs, 5 user-consented, 2 Toast-only

```kotlin
data class ActionDef(
    val id: String,
    val label: String,
    val applicableIntents: Set<String>,         // OR-semantics with families
    val applicableFamilies: Set<IntentFamily>,  // reserved for universal actions
    val requiresConsent: Boolean,
    val userPrefKey: String? = null,            // SettingsStore backed
    val enabledByDefault: Boolean = false,
    val body: suspend (Bubble) -> Unit
)
```

| Action id | Applicable to | Consent | Default | Notes |
|---|---|---|---|---|
| `view_details` | info / location / solve | no | ON | no-op (card tap opens detail) |
| `open_in_maps` | location | no | ON | `geo:0,0?q={title}` |
| `dial_number` | phone | yes | **OFF** | `ACTION_DIAL` via `PhoneExtractor.firstMatch` |
| `copy_listing` | real_estate_rental | yes | **OFF** | share-sheet |
| `save_posting` | recruit_hiring | yes | **OFF** | share-sheet |
| `scan_to_pay` | payment_qr | yes | **OFF** | **Toast only — never auto-launch payment** |
| `redact_id` | id_document | yes | **OFF** | **Toast only — real redaction is Phase C** |
| `copy_warning` | warning_safety | no | ON | share-sheet |
| `copy_menu` | menu_food | no | ON | share-sheet (capped 600 chars) |
| `copy_hours` | hours_schedule | no | ON | share-sheet |

**Safety contract**: `scan_to_pay` is deliberately Toast-only
even with consent — the QR could be in a screenshot / phishing
context; even with consent we route the user to physically scan
a *new* code, never the one in the photo.  `redact_id` is
Toast-only in v1 as the safest first ship; real redaction
(mask middle 6 of 18-digit 身份证) is Phase C.

**Applicability filter** (`ActionResolver.suggestIds(bubble)`):
OR-semantics — `intent ∈ applicableIntents || intent.family ∈
applicableFamilies` matches.  Both empty = applies to nothing
(misconfiguration guard).

### 15.4 IntentVerifier — 10 passes + post-guard

Runs *post-emit_bubble* in `ToolUseLoop`; silently overwrites
`bubble.type` when a strong out-of-family signal fires.  **The
model's `proposedActions` array is NEVER modified** (Phase F
invariant: r3 recall monotonic — only the verifier's
auto-injection path adds actions).

| Pass | source → flip target | Trigger | Phase | Notes |
|---|---|---|---|---|
| 1 | `location` → `phone` | `MOBILE = 1[3-9]\d{9}` | E | Strongest signal; cell on storefronts |
| 1b | `location` → `phone` | `SERVICE = (?:400\|800)[\s-]?\d{3,4}[\s-]?\d{3,4}` | E | Service hotlines |
| 1b' | `location` → `phone` | `LANDLINE = \b0\d{2,3}[\s-]?\d{7,8}\b` | **(a) test** | Stub-only since F2 reject; promoted 2026-07-12 to rescue image_1359 027-87875310 where LLM emits `location` and post-guard can't reach. Single-var fix. |
| 1c | `location` → `real_estate_rental` | `REAL_ESTATE` | **F** | Location + 房源 keyword |
| 1d | `location` → `recruit_hiring` | `RECRUIT` | F | Location + 招聘 keyword |
| 1e | `location` → `id_document` | `ID_DOCUMENT` | F | Location + 证照 keyword |
| 2 | `info` → `payment_qr` | QR-payment language | E | 收款码 / 付款码 |
| 3 | `info` → `phone` | MOBILE | E | 售后电话 / 联系电话 prefix |
| 4 | `info` → `recruit_hiring` | RECRUIT | E | |
| 5 | `info` → `real_estate_rental` | REAL_ESTATE | E | |
| 6 | `info` → `id_document` | ID_DOCUMENT | E | |
| 7 | `real_estate_rental` → `phone` | MOBILE + **!REAL_ESTATE** | **E3** | `!REAL_ESTATE` guard prevents mis-fire on 吉房急售 + 手机号 |
| 8 | `info` → `warning_safety` | `WARNING` | **G** | 请勿 / 禁止 / 警告 / 危险 / 注意 |
| 9 | `info` → `menu_food` | `MENU` | G | 菜单 / 招牌菜 / 套餐 |
| 10 | `info` → `hours_schedule` | `HOURS \| HOUR_PATTERN` | G | 营业时间 / HH:MM-HH:MM |
| **post-guard** | `info`/`location` → `phone` | MOBILE \| LANDLINE \| SERVICE | **G (option c)** | Final safety net for landline + service lines |
| 1b' | `location` → `phone` | `LANDLINE` | **(a) SHIPPED** | Promoted from stub; post-guard (a) single-var test rescued post-guard (c)'s -0.026 phone_20 regression. Verified @20: 0.9081 → **0.9450 (+0.0369 net, 6 lifts / 1 drop bounded)**. Post-guard (c) kept as defense-in-depth. |
| 11 | `info`/`location` → `route_to` | `DIRECTION_ARROW` | **H** | New direction_arrow regex. Targets RCTW's largest untapped cluster (895 imgs, 11.1%). Action reuses `open_in_maps` (no new ActionDef). |

**Pass ordering** (`IntentVerifier.kt` body): 1-1e run on
`location` source first, 2-10 on `info` source, Pass 7 last
on `real_estate_rental` source; post-guard runs AFTER all type
flips and re-checks the corpus for any phone signal that the
upstream passes missed.  **Ordering is load-bearing**:
multi-intent fixtures like "营业场所 禁止吸烟" must resolve to
`warning_safety` (Pass 8) not `hours_schedule` (Pass 10) — Pass
8 runs first because it's a stronger direct-safety signal.

**Why plumbing-only, not prompt-side**: per `eval-type-guide-D1-
rejected-2026-07-11`, a third attempt at prompt-side verbose type
descriptions was rejected (composite -0.035); the verifier
touches `bubble.type` only, never the LLM's text, so r2_type lift
stays distinct from r2_text lift — when a regression happens,
you can tell which pass went wrong from which signal moved.

**`actionFor(type)` map** (IntentVerifier.kt:156-167): the
canonical type → action id mapping the verifier + ToolUseLoop
use for additive injection:

```kotlin
"phone"              -> "dial_number"
"real_estate_rental" -> "copy_listing"
"recruit_hiring"     -> "save_posting"
"id_document"        -> "redact_id"
"payment_qr"         -> "scan_to_pay"
"location"           -> "open_in_maps"
"warning_safety"     -> "copy_warning"
"menu_food"          -> "copy_menu"
"hours_schedule"     -> "copy_hours"
"route_to"           -> "open_in_maps"   // [Phase H] reuses existing action
// "info", "solve" -> null (no canonical action; view_details is implicit)
```

### 15.5 ⚠️ 3-register lockstep (Phase F invariant)

When adding a new intent that maps to a canonical action,
the following THREE sites must be updated **in the same
commit**, or the eval scorer's `defaultActionIds` and the
verifier's auto-inject drift apart silently:

1. **`app/.../ActionDecl.kt`** `registerDefaultActions()` —
   add the `ActionDef`
2. **`shared/.../eval/EvalRunner.kt`** `defaultActionIds` —
   add the action id to the eval baseline (otherwise r3 baseline
   reference is wrong)
3. **`shared/.../IntentVerifier.kt`** `actionFor()` — add the
   `type → action` entry (otherwise auto-inject misses and r3
   recall drops)

Plus optionally:

4. **`shared/.../LlmClient.kt`** system prompt — the C3 v3
   type→action table so the model emits the right
   `action_ids` from round 1 (see §15.6)

**Drift = silent r3 recall regression on the new intent**:
eval thinks defaultActions doesn't include it; verifier doesn't
auto-inject it.  Phase F ship verification
(`pii_20 @20 = 0.8644` + `phone_20 @20 = 0.9394` history-high)
confirmed lockstep held across the F → C3 v3 chain.

### 15.6 C3 v3 — type→action table in prompt

`LlmClient.TOOL_USE_SYSTEM` Step 2 paragraph carries an
explicit type → action mapping table (commit `668ec6f`).
Replaces C2's soft "默认应填" prompt (rejected 2026-07-10 —
single-line nudge wasn't enough; see
`eval-action-ids-nudge-C2-2026-07-11.md`).

The table mirrors §15.4's `actionFor()` map exactly.  By
construction, the prompt table and the verifier injection
**don't conflict**:
- Prompt table → model emits the right `action_ids` from start
- Verifier injection → covers cases where the model missed one
- Net effect: r3 (action recall) is monotonic with intent
  coverage

**Ship verification** @20: `pii_20` 0.8644 → **0.8794 (+0.015)**,
3+ fixtures real-lift, no r2_type regression.  See
`eval-c3-v3-ship-2026-07-11.md`.

### 15.7 SettingsStore — 5 consent toggles

`app/.../SettingsStore.kt` backs 5 PII consent gates; default
OFF (user must opt-in once in Settings screen).  Phase G's 3
`copy_*` actions have no `userPrefKey` → default ON (the
share-sheet is its own consent step).  Keys:
`action_dial_number_enabled`, `action_copy_listing_enabled`,
`action_save_posting_enabled`, `action_scan_to_pay_enabled`,
`action_redact_id_enabled`.

### 15.8 Eval-side wiring

The eval pipeline (`EvalRunner.kt`) does three things the prod
side doesn't:

1. **GT schema dual-read** — reads
   `expected_top_intent_type` first, falls back to `expected_type`
   so the new intent-diverse suites (phone_20 / pii_20 / Phase G)
   are scored correctly.  RCTW-171 stays on `expected_type` for
   backward compat (don't re-tag 8034 images).
2. **Composite formula** — `r2_score` weights `text ∪ type` each
   0.45 + `action_ids` (r3) 0.10; production doesn't need the
   r3 component (the chip UI runs the actions).
3. **Action applicability filter bypass** —
   `EvalRunner.defaultActionIds` returns ALL 10 ids; the prod
   `ActionResolver.suggestIds(bubble)` filters by
   applicableIntents.

### 15.9 Phase ship timeline

| Phase | Date | Ship | Lift |
|---|---|---|---|
| A — phone | 2026-07-10 | IntentDecl.kt + 7 literal `"info"`→FALLBACK_ID | composite phone_20 0.933 (noise) |
| Step 2-5 | 2026-07-11 | ActionDecl + SettingsStore + chip UI + open_in_maps | @20 0.951 (+0.018 noise) |
| B — 4 PII | 2026-07-11 | 4 PII intents + 4 actions | pii_20 r3 0.0 (smoke) |
| C2 — action_ids nudge | 2026-07-11 | single-line "默认应填" | phone r3 0.75→0.85 |
| D — type-guide verbose | 2026-07-11 | **REJECTED** | phone r3 +0.05 but composite -0.035 |
| E — verifier (6 rules) | 2026-07-11 | post-emit_bubble flip on 6 intents | pii_20 image_1359 r2_type 0.5→1.0 |
| E3 — Pass 7 guard | 2026-07-11 | `real_estate_rental + MOBILE + !REAL_ESTATE → phone` | phone_20 +0.012 |
| F — lockstep | 2026-07-11 | actionFor() + additive inject | phone_20 history-high 0.9394 |
| C3 v3 — prompt table | 2026-07-11 | type→action table 6→9 rows | pii_20 +0.015 |
| G — 3 OBSERVE | 2026-07-12 | warning / menu / hours + verifier Pass 8/9/10 + post-guard | Phase G 15-fixture 0.973 |
| GT schema dual-read | 2026-07-12 | EvalRunner reads expected_top_intent_type | pii_20 +0.0837 cumulative |
| post-guard option (a) — Pass 1b' | 2026-07-12 | LANDLINE in Pass 1 (was stub) | phone_20 0.9081 → **0.9450 (+0.037)** |
| H — `route_to` (architecture) | 2026-07-12 | new direction_arrow intent + Verifier Pass 11 + open_in_maps.applicableIntents widens + C3 v3 row 10→11 | (fixtures PENDING — pure add, regression risk LOW) |

**Why this stays plumbing-only** (per `eval-type-guide-D1-
rejected-2026-07-11.md`): the verifier changes `bubble.type`
only, never the LLM's text.  That keeps r2_type lift distinct
from r2_text lift — when a regression happens, you can tell
which pass went wrong from which signal moved.  Prompt-side
changes (C3 v3 in §15.6) shift r3 instead.
