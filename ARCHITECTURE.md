# IntentCam — Architecture

> Companion doc to [README.md](README.md).  Quick start, build,
> and at-a-glance summary live there; this file is the deep-dive.
> See [CONFIG.md](CONFIG.md) for every tunable constant.

> **⚠️ Currency note (2026-07-18):** the registered intent taxonomy
> (14 intent ids, `IntentDecl`, 2-register lockstep — §15) was
> **retired on 2026-07-17** (commit `f522053`).  The current
> architecture is **action-first**: the LLM emits a free-form
> `intent` phrase (UX glue, not scored) + first-class `action_ids`;
> chips = LLM proposals ∩ enabled set + content-rescue
> (`shared/.../ActionRescue.kt`), minus the share precision-gate.
> Scoring is ScorerV3 (`0.55·r_actions + 0.30·r_text + 0.15·r_inputs`)
> over 6 action-first suites (`dial_number` / `open_in_maps` /
> `share` / `redact_id` / `scan_to_pay` / `none`), with
> `over_fire_rate` as the precision signal.  §15 is kept as history;
> treat §1–§14 + §16 as current except where they reference intent
> ids / ScorerV2.  See `docs/adr/2026-07-14-v3-inversion.md`,
> `docs/adr/2026-07-18-eval-prod-parity.md`, and CHANGELOG
> [2026-07-17] entries.

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

The client's `BitmapRegionDecoder` crops the **4096-px fullRes JPEG**
(kept in memory by `FrameAnalyzer`) at the requested region, returns
the crop as a `followUpJpeg`.  The orchestrator **auto-runs OCR on
the crop** (via `OcrEngine.recognize(cropBytes)`) and attaches both
the image and the formatted crop hint to the next user message —
the model sees the high-detail region and the verbatim characters
in one round-trip, with no need for a separate OCR call.

- **Sibling mode** (default `source="original"`): crops the original
  4096-px fullRes.  Coords are absolute.  This is the **default**
  because the round-1 thumbnail is 3200-px (downsampled from 4096-px);
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
  type,           // 14 intent ids — see §15 / CONFIG §H.1
                  //   OBSERVE family: info | location | warning_safety
                  //                  | menu_food | hours_schedule
                  //                  | route_to | service_institution
                  //                  | shopping_promo
                  //   ACT_ON family:  solve | phone | real_estate_rental
                  //                  | recruit_hiring | payment_qr | id_document
  confidence,     // 0.0~1.0
  action_ids?,    // 可空 — Intent↔Action framework (Phase A+, 2026-07-10)
                  //   list of canonical action ids the model recommends:
                  //   dial_number / scan_to_pay / redact_id /
                  //   open_in_maps / share
  details: [      // 详情页表格行
    {kind, label, value, bbox?}, ...
  ]
)
```

The expanded `type` vocabulary (14 ids vs the original 3) is
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
  messages = [user(thumbnail 3200-px + OCR hint + "调用工具。")]
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
  - 30 rounds hit (兜底 Bubble — uses last good details, 0.5 score)
  - 60s timeout (per round; hardcoded in LlmClient.TOTAL_TIMEOUT_MS)
```

The image the LLM sees in round 1 is the **3200-px thumbnail** (with
OCR text in the same message).  The full-res photo (4096-px) stays
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
    captureArmed = true  (CAS — FrameAnalyzer's next frame is captured)
    viewModelScope.launch { wait for frame, up to 3000ms }

FrameAnalyzer.analyze() next frame:
  if !isArmed → return  (common case — user just looking)
  encode full + thumbnail JPEGs
  onFrame(CapturedFrame(thumb, full))
  armed = false  (only one frame captured per tap)

AppViewModel captures:
  if latestFrame == null → log "[CAP] 3000ms 内没拿到帧（等了 Xms）"
  else → cycleManager.startCycle(frame)
    toolUseLoop.runCycle(thumb, full, "", cycleId=job.id)
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

Round-1 ships **one** image — the 3200-px thumbnail.  No 4-quadrant
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
The "is the camera busy" signal is now a derived flow
(`CycleManager.busy: StateFlow<Boolean>`) — see §6.1 — replacing
the legacy manually-managed `UiState.analyzing` field.

### 6.1 CycleManager.busy — derived spinner signal

The camera spinner / ShutterButton a11y hint read
`viewModel.busy: StateFlow<Boolean>`, which is a flatMapLatest
chain over `CycleManager._focusedJobId` and the focused job's
`status` flow.  `true` iff the focused job is PENDING or
IN_FLIGHT — ERRORED / SUPERSEDED stay false (the per-cycle
BubbleCard surfaces the "识别超时" affordance via its own
status flow instead of a global spinner).  Built once via
`stateIn(scope, SharingStarted.Eagerly, false)`; no manual
`_state.copy(analyzing = …)` writes anywhere.

## 7. Bubble model

```kotlin
data class Detail(
    val kind: String,    // text | number | object | color | shape | ...
    val label: String,   // human-readable name
    val value: String,   // the extracted content
)

data class Bubble(
    val id: String,
    val cycleId: String,  // owning CycleJob id (Phase B+; defaults to bubble.id for legacy path)
    val type: String,            // free-form label (default "info"); NOT a registered enum since 2026-07-17
    val intent: String,          // free-form Chinese phrase (≤30 chars), v3.0+
    val title: String,            // user-facing title (动宾短语)
    val detail: String,           // content description
    val confidence: Float,
    val imageBytes: ByteArray,   // 3200-px thumbnail JPEG (display + detail view source)
    val createdAtMs: Long,
    val toolName: String? = null,
    val needsUserInput: Boolean = false,
    val details: List<Detail> = emptyList(),  // details table rows
    val actions: List<String> = emptyList(),  // post-resolve chip list
    val llmProposedActions: List<String>? = null,  // raw model emit (audit trail)
    val validatedInputs: Map<String, Boolean> = emptyMap(),  // per-action validation status
    val pendingInputs: List<String> = emptyList(),  // cross-action missing-input keys
)
```

Bubbles are surfaced via `CycleManager.allJobs: StateFlow<Map<String, CycleJob>>`
— the live UI iterates this directly.  `imageBytes` is the
3200-px thumbnail JPEG (same bytes `CycleJob.frame.thumbnail`
already holds), so the detail view can show a sharp image without
re-fetching from anywhere.

The `details` list drives the structured table in the DetailScreen
(kind | label | value columns). Each row is something the LLM
extracted from the image — text, numbers, brand names, dates, etc.
The LLM populates these in `emit_bubble`'s `details` input field.

`actions` is the post-resolve chip list — populated by
`ActionResolver.suggestIds(bubble)` from the LLM's
`llmProposedActions` intersected with the user's enabled-action set,
plus content-rescue additions (`ActionRescue`, also ∩ enabled).
This is the per-bubble action surface the chip UI renders on the
BubbleCard.  See the currency note at the top for the post-2026-07-17
routing design.

## 8. Cancellation & concurrency

- `viewModelScope` is the only coroutine scope; tearing down the
  ViewModel cancels every in-flight recognition.
- `CycleManager.cancelAll(reason)` cancels every cycle's coroutine
  on user restart / leave-screen so we don't keep billing for LLM
  calls whose results would never reach the UI.
- `captureArmed: AtomicBoolean` CAS-guards the camera analyzer so
  a rapid double-tap of the shutter is a no-op.
- The OkHttp `EventSource.cancel()` is invoked from
  `suspendCancellableCoroutine.invokeOnCancellation` so the SSE
  connection drops the moment the cycle's coroutine is cancelled.
- Per-cycle soft timeout (`CycleManager.llmTimeoutMs = 90_000 ms`)
  wraps the LLM call; stalled servers surface as `Outcome.Error`
  → ERRORED cycle status.

| Thread | Where | Why |
|---|---|---|
| `Executors.newSingleThreadExecutor` | `FrameAnalyzer.analyze` | CameraX guarantees serial execution; we own the loop on the analyzer thread |
| `Dispatchers.IO` | `LlmClient.streamToolUse` | OkHttp Sockets I/O + DNS |
| `viewModelScope` (Main) | `CycleManager.runCycleLoop` (worker pool) | UI state writes + Compose recomposition |
| `viewModelScope` (Main.immediate) | `CycleManager.startCycle` / `pump` / `cancelAll` | Single-threaded for `pendingQueue` + `runningWorkers` race-free updates |
| `Main` | Compose recomposition | The whole app is single-Composable-Activity |

## 9. Tuning

> Single source of truth: **[CONFIG.md](CONFIG.md)**.  This table
> is a summary; CONFIG.md has file:line + rationale + "Recently
> retired" + "To-try-next" sections.

| Knob | Value | Where | Purpose |
|---|---|---|---|
| `MAX_DIM` (thumbnail) | `3200` | `ImagePipeline.kt` | max-dim cap for the LLM-facing image |
| `QUALITY` (thumbnail) | `90` | `ImagePipeline.kt` | JPEG quality for the LLM-facing image |
| `MAX_FULL_DIM` | `4096` | `ImagePipeline.kt` | cap for the in-memory full-res JPEG |
| `FULL_QUALITY` | `95` | `ImagePipeline.kt` | JPEG quality for the in-memory full-res JPEG |
| `CROP_OUTPUT_MAX_DIM` | `3200` | `ImageOps.kt` | max-dim cap on `zoom_in` crops |
| `DEFAULT_CROP_QUALITY` | `90` | `ImageOps.kt` | JPEG quality for crops |
| `ResolutionSelector` | sensor max 4:3 | `MainActivity.kt:596-628` | tells CameraX to deliver full sensor res to ImageAnalysis (was 640×480 default pre-v1.0) |
| `camera pre-warm` | viewmodel init | `AppViewModel.cameraProviderFuture` | kicks off `ProcessCameraProvider.getInstance()` during onCreate |
| `MAX_OCR_HINT_LINES` | `30` | `OcrEngine.kt` | top-N OCR blocks injected into round-1 user message |
| `LOW_CONFIDENCE_THRESHOLD` | `0.5` | `OcrEngine.kt` | OCR conf < 0.5 → mark `[LOW]` in hint |
| `MAX_ROUNDS` | `30` | `ToolUseLoop.kt` | soft cap; 兜底 Bubble on hit |
| `TOTAL_TIMEOUT_MS` | `90_000` | `LlmClient.kt` | per-round SSE timeout |
| `MAX_TOKENS` | `2048` | `LlmClient.kt` | output token cap per round |
| `REQUEST_TEMPERATURE` | `0.0` | `LlmClient.kt` | locked at 0 for deterministic routing |
| `capture timeout` | `3000` ms | `AppViewModel.captureLatestFrame` | bumped 500→3000ms for cold-start + sensor-res encode |
| `llmTimeoutMs` | `90_000` ms | `CycleManager.kt` | per-cycle soft cap on the LLM call |
| `CYCLE_QUEUE_DEPTH` | `8` | `UiState` companion | max queued+in-flight cycles (shutter dim threshold) |
| `CYCLE_CONCURRENCY` | `2` | `UiState` companion | worker pool size |
| `CYCLES_MAX_TOTAL` | `8` | `UiState` companion | terminal FIFO cap on the cycles map |
| `DEBUG_LOG_MAX` | `40` | `UiState` companion | ring-buffer cap on debug log |
| `extract_text` | text-only OCR | `ToolImplementations.kt` | new tool (v1.1). Same crop path as `zoom_in` follow-up but no `followUpJpeg` — returns only OCR text. Model picks it for 25-30% of fixtures when the region is already visible in the round-1 thumbnail. |
| `IntentDecl.registerDefaultIntents()` | **14 ids** | `shared/.../IntentDecl.kt` | 3 v1.0 ids (`info`/`location`/`solve`) + `phone` (Phase A) + 4 PII Phase B (real_estate/recruit/payment_qr/id_document) + 3 OBSERVE Phase G (warning/menu/hours) + `route_to` Phase H + `service_institution` Phase I + `shopping_promo` Phase J. |
| `ActionDecl.registerDefaultActions()` | **5 defs** | `app/.../ActionDecl.kt` | 3 carry `userPrefKey` (SettingsStore consent toggle, OFF default): `dial_number`, `scan_to_pay`, `redact_id`. `scan_to_pay` and `redact_id` are Toast-only by design. `share` is the unified share-text action across 7 OBSERVE/ACT_ON intents — no `userPrefKey`, default ON, body fires `ACTION_SEND text/plain` share-sheet (capped 600 chars). `open_in_maps` applies to `location` / `route_to` / `service_institution`. |
| **2-register lockstep** | invariant | Phase A+ (2026-07-10) | Adding a new intent requires lockstep edits in 2 files: IntentDecl + ActionDecl. See §15. |

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
- Persist `cycles` map to DataStore on `onStop`, rehydrate on cold
  start.  Currently in-memory only — wiped on process death.

## 15. Intent↔Action framework (v3.0 inversion, 2026-07-14)

> **⛔ SUPERSEDED 2026-07-17 (commit `f522053`).**  The registered
> intent taxonomy described in this section — 14 intent ids,
> `IntentDecl` / `IntentRegistry` / `IntentFamily`, the 2-register
> lockstep (§15.4), the type→canonical-action table, and the
> soft-hint prompt block — was retired.  Intent is now a free-form
> LLM phrase (UX glue, not scored); `action_ids` is the sole
> routing signal; content-rescue (`shared/.../ActionRescue.kt`) is
> the add-only safety net; ScorerV3 (recall) replaced ScorerV2.
> This section is kept as ship-history only — do NOT implement
> against it.  Current design: see the currency note at the top.

Each `emit_bubble` carries:

- **`type`** — one of 14 intent ids (replaces the original
  3-bucket `info | location | solve` triplet).  Kept for
  UI accent + eval `r_type` backwards-compat; the LLM is no
  longer forced to pick a specific id and may emit a free-form
  `intent` instead.
- **`intent`** — free-form Chinese phrase (≤30 chars, e.g.
  "拨打联系电话", "导航去这家店").  The new canonical
  user-visible discriminator (v3.0+).
- **`action_ids`** — list of canonical action ids the LLM
  recommends (e.g. `dial_number` for phone, `share` for share-
  text intents, `open_in_maps` for navigation).  Renders as
  tappable chips.

### 15.1 Three-layer architecture

The framework is a thin three-layer boundary; the LLM drives the
loop, a pure-function orchestrator validates inputs:

```
                  ┌────────────────────────────────────┐
                  │  1. PROMPT FRAMING                 │
                  │     ActionOrchestrator             │
                  │     .frameAvailableActions()       │
                  │     → spliced into system prompt   │
                  │       via __ACTIONS_BLOCK__        │
                  └──────────────┬─────────────────────┘
                                 │ LLM picks + emits
                                 ▼
                  ┌────────────────────────────────────┐
                  │  2. PER-EMIT VALIDATOR             │
                  │     ActionOrchestrator             │
                  │     .validateInputs(bubble)        │
                  │     → missing per required input   │
                  │     → bubble.validatedInputs map   │
                  └──────────────┬─────────────────────┘
                                 │
                                 ▼
                  ┌────────────────────────────────────┐
                  │  3. FINALIZER                      │
                  │     ActionOrchestrator             │
                  │     .shouldFinalize(bubble, round) │
                  │     → CONTINUE (missing) inject    │
                  │       input-missing nudge + retry  │
                  │     → FINALIZE (complete / cap)    │
                  │       cycle ends with this bubble  │
                  └────────────────────────────────────┘
```

The orchestrator is a **pure boundary** — no UI types, no
Android imports, just data classes + simple regex parsers.  Lives
in `app/.../ActionOrchestrator.kt` because it closes over
`ActionRegistry` (Android-coupled via `ActionDef.body`'s
`android.content.Context` param).

### 15.2 IntentDecl — 14 ids, 2 families

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
| `service_institution` | OBSERVE | 机构: 医院/学校/政府机关/银行/邮局/法院/派出所/大使馆 |
| `shopping_promo` | OBSERVE | 促销: 特价/打折/满减/秒杀/亏本/清仓/甩卖/红包/限时/抢购 |

**Family equivalence** (eval-side `r_type` grading, 2026-07-15
formula): exact match 1.0 / same family 0.7 / cross-family 0.3 /
empty·unknown 0.0.  Same-family collapse means a bubble classified
as `route_to` scores 0.7 against `location` GT (both OBSERVE),
not 0.0 — important because the LLM is no longer forced to pick
a precise id and may emit a free-form `intent` instead.

### 15.3 ActionDecl — 5 defs, 3 user-consented, 2 Toast-only

```kotlin
data class ActionDef(
    val id: String,
    val label: String,
    val applicableIntents: Set<String>,         // OR-semantics with families
    val applicableFamilies: Set<IntentFamily>,  // broader filter
    val requiresConfirmation: Boolean,
    val userPrefKey: String? = null,            // SettingsStore-backed opt-in
    val requiredInputs: List<ActionInputSpec>,  // validators per input key
    val accent: ActionAccent,                   // EXECUTE / DELEGATE / CLARIFY
    val body: suspend (Application, Bubble, Map<String, String>) -> ActionOutcome,
)
```

| Action id | Applicable to | Confirmation | Default | Notes |
|---|---|---|---|---|
| `open_in_maps` | location / route_to / service_institution | no | ON | `geo:0,0?q={title}` |
| `dial_number` | phone | yes | **OFF** | `ACTION_DIAL` via `PhoneExtractor.firstMatch`; needs `phone_number` input |
| `scan_to_pay` | payment_qr | yes | **OFF** | **Toast only — never auto-launch payment** |
| `redact_id` | id_document | yes | **OFF** | **Toast only — real redaction is a follow-up** |
| `share` | real_estate_rental / recruit_hiring / warning_safety / menu_food / hours_schedule / service_institution / shopping_promo | no | ON | `ACTION_SEND text/plain` share-sheet (capped 600 chars) — unified across 7 intents; needs `text` input |

**Safety contract**: `scan_to_pay` is deliberately Toast-only
even with consent — the QR could be in a screenshot / phishing
context; even with consent we route the user to physically scan
a *new* code, never the one in the photo.  `redact_id` is
Toast-only as the safest first ship; real redaction (mask
middle 6 of 18-digit 身份证) is a future ship.

**Applicability filter** (`ActionResolver.suggestIds(bubble)`):
OR-semantics — `intent ∈ applicableIntents || intent.family ∈
applicableFamilies` matches.  Both empty = applies to nothing
(misconfiguration guard).

### 15.4 ⚠️ 2-register lockstep

When adding a new intent, **TWO sites** must be updated in the
same commit:

1. **`shared/.../IntentDecl.kt`** `registerDefaultIntents()` —
   add the `IntentDecl` (id, label, family, llmHint)
2. **`app/.../ActionDecl.kt`** `registerDefaultActions()` —
   add any `ActionDef`s that apply to this intent (or widen
   an existing action's `applicableIntents` / `applicableFamilies`)

The third site — `IntentVerifier.actionFor()` — was **retired in
Phase E (commit 59c1128, 2026-07-14)** as part of the v3.0
architectural refactor.  v3.0 trade-off: phone suites lift
(LLM picks `dial_number` without the verifier crutch) but the
OBSERVE-family + PII cluster drops because the LLM is less
reliable at emitting the canonical `share` action.  See
`release-2026-07-14f.md` for the full ship notes.

**Drift = silent chip miss for the new intent**: the bubble
surfaces without its chip and r_actions_recall drops in the eval.

### 15.5 SettingsStore — 3 consent toggles

`app/.../SettingsStore.kt` backs 3 PII consent gates; default
OFF (user must opt-in once in Settings screen).  `share` and
`open_in_maps` have no `userPrefKey` → default ON (the
share-sheet / map picker is its own consent step).  Keys:
`action_dial_number_enabled`, `action_scan_to_pay_enabled`,
`action_redact_id_enabled`.

### 15.6 Eval-side wiring

The eval pipeline (`EvalRunner.kt`) does three things the prod
side doesn't:

1. **GT schema dual-read** — reads
   `expected_top_intent_type` first, falls back to `expected_type`
   so the new intent-diverse suites (phone_20 / pii_20 / Phase G)
   are scored correctly.  RCTW-171 stays on `expected_type` for
   backward compat (don't re-tag 8034 images).
2. **Composite formula** — `composite_v2 = 0.40·r_actions_recall
   + 0.30·r_inputs_complete + 0.15·r_rounds_efficiency +
   0.10·r_intent_derived + 0.05·r_text`.  Production doesn't
   need the r_actions / r_inputs components (the chip UI runs
   the actions).
3. **Action applicability filter bypass** —
   `EvalRunner.defaultActionIds` returns ALL 5 ids; the prod
   `ActionResolver.suggestIds(bubble)` filters by
   applicableIntents.
4. **v3 dual-run** — `ScorerV3Result.compute` runs side-by-side
   with `ScorerV2Result.compute` for `composite_v3 =
   0.55·r_actions + 0.30·r_text + 0.15·r_inputs` (action-first
   weights).  Eval JSON dumps both `overall_composite_v2` and
   `overall_composite_v3`; `composite_v2` remains the regression-
   gating headline until IntentCam Dev signs off on v3 stability.

### 15.7 Phase ship timeline

| Phase | Date | Ship | Lift |
|---|---|---|---|
| A — phone | 2026-07-10 | IntentDecl + 7 literal `"info"`→FALLBACK_ID | composite phone_20 0.933 (noise) |
| Step 2-5 | 2026-07-10 | ActionDecl + SettingsStore + chip UI + open_in_maps | @20 0.951 (+0.018 noise) |
| B — 4 PII | 2026-07-11 | 4 PII intents + 4 actions | pii_20 baseline 0.872 |
| C2 — action_ids prompt nudge | 2026-07-11 | "默认应填 action_ids" in C3 prompt | phone r3 0.75→0.85 |
| C3 v3 — prompt table | 2026-07-11 | type→action table 6→9 rows | pii_20 +0.015 |
| G — 3 OBSERVE | 2026-07-12 | warning / menu / hours intents + copy actions | Phase G 15-fixture 0.973 |
| GT schema dual-read | 2026-07-12 | EvalRunner reads expected_top_intent_type | pii_20 +0.0837 cumulative |
| H — `route_to` | 2026-07-12 | 12th intent OBSERVE | direction_arrow_20 v2 0.9850 |
| I — `service_institution` | 2026-07-12 | 13th intent OBSERVE (32-keyword regex v2) | service_institution_60 0.9664 |
| J — `shopping_promo` | 2026-07-13 | 14th intent OBSERVE + copy_promo action | shopping_promo_20 0.918 |
| r3 verifier fix | 2026-07-13 | `actionFor()` injection broadened to missing-canonical (`355c001`) | shopping_promo_20 r3 0.35→0.45 |
| **v3.0 inversion** | 2026-07-14 | `IntentVerifier.kt` DELETED (513 lines); LLM authoritative for type/action; `composite_v2` formula replaces legacy composite; ScorerV3 dual-run ships | phone_20 lifts, OBSERVE/PII clusters drop (planned) |
| canonical-action injection robustness | 2026-07-14 | `6456839` — verifier injection covers both flip + missing-canonical cases | recruit_hiring +0.032; real_estate +0.058 |
| Producer/consumer split | 2026-07-16 | `CycleManager` enqueue + worker pool (n=8 queue, m=2 workers) | UX: rapid multi-shot, in-flight cap auto-releases |
| `busy` derived flow | 2026-07-16 | `CycleManager.busy: StateFlow<Boolean>` replaces manual `UiState.analyzing` writes | zero imperative state sync |
| Single bubble pipeline | 2026-07-16 | `UiState.bubbles` + `runToolUseCycle` + `pendingFullRes` deleted; `cycles.values.mapNotNull { it.bubble.value }` is the single source | -440 lines net |
| Verifier Pass 4b | 2026-07-14 | menu_food\|location + recruit POSTER + ≥1 job-title word → recruit_hiring (job-title gate prevents over-fire on restaurant menus with passing 招聘 text) | recruit_hiring_13 → recruit_hiring_11 |

### 15.8 Planned follow-ups

- **Re-introduce type→canonical as soft system-prompt hint** — the
  retired verifier's role (steer the LLM toward `dial_number` for
  phone bubbles) was partially absorbed by the C3 v3 prompt table,
  but the LLM still misses `share` on OBSERVE bubbles sometimes.
  A one-line soft hint at the end of the actions block is being
  tested in `eval-action-first-ship` (not yet shipped).
- **ScorerV3 canonical switch** — gated on
  `composite_v2` regression PASS + `composite_v3` week-over-week
  |Δ| ≤ 0.03 across all production suites, plus manual sign-off.
  Until then, every eval run prints both numbers; no canonical
  flip.

---

## 16. v3.0 producer/consumer pipeline (2026-07-16)

The CycleManager is the producer/consumer pipeline that owns
concurrent recognition cycles:

```
                    ┌──────────────────────────────────┐
                    │       CycleManager               │
   shutter tap ───► │   startCycle(frame)               │
   (producer)       │     │                            │
                    │     ▼                            │
                    │   pendingQueue (FIFO, n=8)        │
                    │     │                            │
                    │     ▼  pump() loop               │
                    │   workers (m=2 coroutines)       │
                    │     │  runCycleLoop              │
                    │     │   ├─ ToolUseLoop.runCycle  │
                    │     │   ├─ ActionOrchestrator    │
                    │     │   └─ CycleJob.bubble flow  │
                    │     ▼                            │
                    │   allJobs: Map<id, CycleJob>      │
                    │     │                            │
                    │     ▼                            │
                    │   IntentBubbles (live UI)        │
                    │     iterates allJobs.values      │
                    └──────────────────────────────────┘
```

Two independent bounds (`UiState.CYCLE_QUEUE_DEPTH = 8` for
queued+in-flight, `UiState.CYCLE_CONCURRENCY = 2` for active
worker pool) cap the pipeline:

- **Backpressure** — when queued+in-flight reaches the queue
  depth, the shutter dims (`remaining = CYCLE_QUEUE_DEPTH -
  activeCycleCount`) and `startCycle` rejects further taps.
- **Concurrency** — at most 2 LLM+OCR pipelines run at once
  (caps concurrent Anthropic SSE streams; bounds peak device
  memory; avoids on-device OCR analyzer contention).

### 16.1 CycleJob — per-cycle reactive surface

Each cycle is a `CycleJob` whose fields are `StateFlow`s the UI
can `collectAsState` independently — updating one job's bubble
doesn't force other jobs' cards to recompose:

```kotlin
data class CycleJob(
    val id: String,                       // UUID
    val frame: CapturedFrame,             // captured JPEGs
    val status: MutableStateFlow<JobStatus>,
    val bubble: MutableStateFlow<Bubble?>,   // null while PENDING/IN_FLIGHT
    val nRounds: MutableStateFlow<Int>,
    val validatedInputs: MutableStateFlow<Map<String, Boolean>>,
    val pendingInputs: MutableStateFlow<List<String>>,
    val createdAtMs: Long,
    var coroutine: Job? = null,           // for supersede / cancelAll
)
```

`UiState.cycles: Map<String, CycleSnapshot>` mirrors the
underlying `CycleJob` references (Snapshot fields = `StateFlow`
refs, not values) so Compose can subscribe per-cycle.

### 16.2 JobStatus state machine

```
                  startCycle
        NONE ─────────────────► PENDING  ──── pump() ────► IN_FLIGHT
                                                                     │
                       ┌─────────────────────────────────────────────┤
                       │                                             │
                       ▼                                             ▼
                  SUPERSEDED                                   COMPLETE
              (newer photo taken;                              (cycle done;
               keeps running but                              bubble ready)
               demoted in UI)                                     │
                                                                     │ exception /
                                                                     │ 90s timeout
                                                                     ▼
                                                                 ERRORED
                                                          (last good bubble +
                                                           "识别超时" affordance)
```

The `busy` spinner signal (`CycleManager.busy: StateFlow<Boolean>`)
is `true` only when focused job is PENDING or IN_FLIGHT.
ERRORED / SUPERSEDED stay false — the per-cycle BubbleCard
surfaces the "识别超时" affordance via its own status flow.

### 16.3 Cancellation surface

| Op | Trigger | Effect |
|---|---|---|
| `cancelCycle(cycleId, reason)` | User cancels input dialog | Mark SUPERSEDED + cancel coroutine + remove from map |
| `cancelAll(reason)` | User "重新扫描" / leave screen | Cancel every non-COMPLETE job + clear map |
| `supersedeCurrent()` | (reserved for future auto-evict) | Demote focused job to SUPERSEDED (keeps running) |
| `llmTimeoutMs` (90s) | Hung API call | Mark ERRORED + clear slot |

Cancelled jobs' coroutines are stopped so we don't keep billing
for LLM calls whose results would never reach the UI.