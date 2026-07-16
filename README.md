# IntentCam

A camera-based Android app that recognises user intent from a single
phone photo.  The image goes through an on-device pipeline (3200-px
thumbnail + 4096-px full-res + on-device OCR hint) and a
multi-round LLM tool-use protocol; the result is a structured
**bubble** with intent type, free-form Chinese summary, and a set
of tappable action chips.

| | |
|---|---|
| **Version** | 3.0 (architectural refactor) |
| **Min Android** | API 26 (Android 8.0) |
| **LLM** | Anthropic-compatible streaming (configurable base URL + token) |
| **OCR backend** | Local PP-OCRv4 mobile (cascade: local → Huawei Cloud → blind) |
| **APK size** | 25 MB debug / 17 MB release |
| **Eval entry point** | `./gradlew :shared:eval --args="..."` (JVM, no device) |

---

## Architecture at a glance

```
                        ┌────────────────────────────────────┐
                        │            AppViewModel            │
                        │                                    │
   FrameAnalyzer ─►─────┤  captureLatestFrame()              │
   (CameraX, 3200px     │       │                            │
    thumb + 4096px      │       ▼                            │
    fullRes, on-device  │  CycleManager                      │
    OCR hint)           │   ├─ pendingQueue (FIFO, n=8)      │
                        │   ├─ worker pool (m=2)             │
                        │   └─ allJobs / busy (StateFlows)  │
                        │       │                            │
                        │       ▼                            │
                        │  ToolUseLoop.runCycle()           │
                        │   └─ multi-round tool-use         │
                        │       (zoom_in / extract_text /    │
                        │        compare_text / emit_bubble) │
                        │       │                            │
                        │       ▼                            │
                        │  ActionOrchestrator                │
                        │   (per-emit validateInputs +       │
                        │    shouldFinalize gate)            │
                        │       │                            │
                        │       ▼                            │
                        │  Bubble → ActionResolver           │
                        │           → ActionRegistry        │
                        │               → chips on screen   │
                        └────────────────────────────────────┘
```

The **single source of truth** for "is the camera busy" is
`CycleManager.busy: StateFlow<Boolean>` — a derived flow over the
focused job's status (PENDING + IN_FLIGHT only).  The **single
source of truth** for rendered bubbles is
`CycleManager.allJobs: StateFlow<Map<String, CycleJob>>` — the
live UI iterates this directly; no shadow list exists.

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full design
(intent↔action framework, multi-round protocol, producer/consumer
split, OCR cascade).

See **[CONFIG.md](CONFIG.md)** for every tunable constant.

---

## Intent↔Action framework

Each `emit_bubble` carries:

- **`type`** — one of 14 intent ids (`info` / `location` /
  `solve` / `phone` / `payment_qr` / `id_document` /
  `real_estate_rental` / `recruit_hiring` / `warning_safety` /
  `menu_food` / `hours_schedule` / `route_to` /
  `service_institution` / `shopping_promo`).  Defaults to
  `FALLBACK_ID = "info"` if the LLM doesn't pick one.
- **`intent`** — free-form Chinese phrase (≤30 chars, e.g.
  "拨打联系电话", "导航去这家店").  Replaces the hardcoded type
  enum starting in v3.0.
- **`action_ids`** — list of user-facing action ids the model
  recommends (`dial_number` / `open_in_maps` / `scan_to_pay` /
  `redact_id` / `share`).  Each is rendered as a chip on the
  bubble.

Adding a new intent = register in **2 lockstep sites**:

1. `app/.../IntentDecl.kt` `registerDefaultIntents()` — declare the
   IntentDecl (id, label, family, llmHint)
2. `app/.../ActionDecl.kt` `registerDefaultActions()` — declare any
   actions that apply to this intent

Drift across these = silent chip miss for that intent.  The eval
pipeline auto-checks via `profiling/baselines.json`.

---

## Headline (Kotlin eval, prod-mirror, local PP-OCRv4)

OCR backend swapped 2026-07-13 from Huawei Cloud to local
PP-OCRv4 mobile (`pp_ocrv4_mobile_engine`, 12 MB, ~2.4 s/img CPU).
Huawei Cloud numbers retained as historical reference only.

| Suite | composite | n |
|---|---:|---:|
| `phone_20` | **0.907** | 20 |
| `pii_20` | **0.947** | 18 |
| `direction_arrow_20` | **0.985** | 20 |
| `service_institution_60` | **0.976** | 67 |
| `shopping_promo_20` | **0.943** | 20 |
| `recruit_hiring_11` | **0.970** | 11 |
| `real_estate_rental_11` | **0.957** | 10 |
| `phaseG_15` | **0.959** | 15 |

The **15-suite regression net** (`scripts/run_regression.sh`)
auto-checks Δ ≥ 0.05 absolute against current baselines; exits
non-zero on regression.

The eval runs on the JVM (`shared/eval/`) against RCTW-171 train
images.  It does **not** need an Android device — the production
camera/ML Kit code paths are mocked; only the LLM call and the
visual pipeline run for real.

---

## Quick start

### Build (JDK 17 + Gradle 8.5)

```bash
JAVA_HOME=/path/to/jdk17 /path/to/gradle clean :app:assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`;
it's also copied to `./intentcam.apk` at the project root for
easy sideloading.

### Run on device

1. Grant camera permission on first launch
2. Tap the green **识别** button to capture a frame
3. Wait ~1-2 s for round 1 (OCR + LLM) + 0-2 zoom/extract rounds
4. Tap the resulting bubble to see the image (full-screen,
   scrollable text below, header tap to collapse for more image)
5. Tap **退出** to dismiss and start a new capture

### Capture logs while reproducing a bug

```bash
./scripts/capture_logs.sh                # install + filtered logcat
./scripts/capture_logs.sh --no-install   # capture only
```

Filter includes `IntentCam:V`, `AndroidRuntime:E`, `System.err:W`,
and `DEBUG:V` (in-app overlay entries).  Output lands in
`./intentcam.log`.

---

## Eval

### 1. One-time setup — local PP-OCRv4 OCR backend

```bash
# Clone the PP-OCRv4 mobile engine (sibling directory, NOT imported)
git clone https://github.com/PaddlePaddle/PaddleOCR /home/oppry/work/pp_ocrv4_mobile_engine
cd /home/oppry/work/pp_ocrv4_mobile_engine
pip install paddleocr==2.7.3 opencv-python-headless pillow
```

The eval invokes it via the long-lived `profiling/pp_ocrv4_runner.py`
JSON-RPC subprocess.  **First run takes 5-30 s** for PaddleOCR to
load weights; subsequent calls reuse the cached engine.

### 2. Run a single suite

```bash
# Standard 20-fixture iteration run (~2-3 min with local OCR)
JAVA_HOME=/path/to/jdk17 /path/to/gradle :shared:eval \
    --args="--limit 20 --gt profiling/ground_truth_phone_20.json \
            --json-out profiling/eval_phone_20.json"

# Conclusive 60-fixture run (~6-10 min)
JAVA_HOME=/path/to/jdk17 /path/to/gradle :shared:eval \
    --args="--limit 60 --gt profiling/ground_truth_phone_60.json"
```

### 3. OCR backend cascade (3 tiers)

| Tier | When | How to enable |
|---|---|---|
| **Local PP-OCRv4** (default, 2026-07-13+) | sibling repo at `/home/oppry/work/pp_ocrv4_mobile_engine` | auto-detected; first run loads model |
| Huawei Cloud fallback | local install/init fails | set `HUAWEICLOUD_SDK_{AK,SK,PROJECT_ID}` env vars |
| Blind (no OCR) | both fail | unset env vars + no sibling repo |

Inspect with `--backend local|huawei|blind` to force a tier for ablation.

### 4. Run the regression net (5+ suites, ~30 min)

```bash
./scripts/run_regression.sh                # all 9 suites, auto-compare
./scripts/run_regression.sh --no-build phone_20 pii_20   # subset
```

Exits non-zero if any suite drops ≥ 0.05 absolute from its
baseline in `profiling/baselines.json`.  See
`profiling/README_regression.md`.

JSON dumps in `profiling/eval_*.json` document the baseline chain.
Compare two runs with `profiling/diff_eval.py`.

---

## Repository layout

```
app/src/main/java/com/example/intentcam/   — app source (Android-coupled)
  AppViewModel.kt           state, capture orchestration, settings API
  CycleManager.kt           producer (shutter tap) → queue (n=8) →
                            worker pool (m=2) → allJobs / busy (StateFlows)
  FrameAnalyzer.kt          dual-JPEG capture (3200 thumb + 4096 fullRes)
  AndroidOcrEngine.kt       OCR backend (HMS ML Kit on device)
  AndroidImageOps.kt        Android ImageOps impl (BitmapRegionDecoder)
  LlmClient.kt              Anthropic-compatible streaming client
                            + toolUseSystemPrompt() (splices action ids)
  MainActivity.kt           Compose UI (preview, debug overlay, detail,
                            bubble cards, action chips, dialogs)
  Models.kt                 Bubble / Detail / UiState / CycleSnapshot /
                            JobStatus / Phase / LlmConfig
  IntentDecl.kt             14-id IntentDecl registry (family + LLM hint)
  ActionDecl.kt             5-action registry (dial_number / open_in_maps
                            / scan_to_pay / redact_id / share)
  ActionOrchestrator.kt     per-emit input validation + finalize gate
  ChipStateMapper.kt        chip state resolution (Validated / Ghost /
                            Spinner / Hidden)
  Theme.kt / Palette.kt     dark-only Material3 theme + IntentCamPalette
  SettingsStore.kt          SharedPreferences-backed config + PII gates
  SettingsScreen.kt         Compose settings UI
  CycleJob.kt               one in-flight cycle's reactive surface
  AndroidOcrEngine.kt       HMS ML Kit OCR backend install

shared/src/main/kotlin/com/example/intentcam/  — :shared module (Kotlin/JVM)
  ToolUseLoop.kt            multi-round orchestrator (auto-OCR on follow-ups)
  LlmClient.kt              (also packaged in :app)
  Models.kt                 (also packaged in :app)
  CapturedFrame.kt          (thumbnail, fullRes) carrier
  ImagePipeline.kt          ImageQuality constants (MAX_DIM, MAX_FULL_DIM)
  ImageOps.kt               cross-platform image ops interface
  OcrEngine.kt              strategy holder + formatHint for round-1
  Tools.kt                  ToolDef / ToolRegistry / ToolContext
  ToolImplementations.kt    zoom_in + compare_text + extract_text +
                            emit_bubble bodies
  IntentDecl.kt             (also packaged in :app)
  ActionDecl.kt             ActionRegistry / ActionDef / ActionInputSpec /
                            ActionOrchestrator return types
  ActionArgs.kt             RequestArgs / PendingAction data carriers
  CropStrategy.kt           cropJpegRegion() top-level fn (ImageOps impl)
  FormatThrowable.kt        formatThrowable(e) cross-platform helper

shared/src/main/kotlin/com/example/intentcam/eval/  — eval pipeline
  EvalMain.kt               CLI entry point
  EvalRunner.kt             per-fixture runner + composite scorer +
                            GT schema dual-read (expected_top_intent_type)
  ScorerV2.kt               composite_v2 (0.40·r_actions_recall +
                            0.30·r_inputs_complete + 0.15·r_rounds_efficiency +
                            0.10·r_intent_derived + 0.05·r_text)
  ScorerV3.kt               action-first composite (dual-run with v2)
  JvmLocalOcrEngine.kt      local PP-OCRv4 backend for the eval
  JvmHuaweiCloudOcrEngine.kt  Cloud OCR backend for the eval (legacy)

profiling/
  ground_truth_*.json       100+ real-photo fixtures (RCTW-171)
  pp_ocrv4_runner.py        subprocess helper for eval-side OCR
  eval_*.json               measurement trail (100+ JSON dumps)
  diff_eval.py              two-run side-by-side comparator
  baselines.json            8+ suite baselines (regression net threshold)
  run_regression.sh         8-suite regression runner
  check_regression.py       post-run regression threshold checker

scripts/
  capture_logs.sh           adb install + filtered logcat to file
  run_regression.sh         8-suite regression net
  check_regression.py       exit-code gating vs baselines.json

CONFIG.md                   every tunable constant + rationale
ARCHITECTURE.md             deep dive on the design
CHANGELOG.md                release-by-release change log
```

---

## License

Public repository — no license file yet.