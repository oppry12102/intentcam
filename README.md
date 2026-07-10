# IntentCam

A camera-based Android app that recognises intent from a single
phone photo using a 3-tool LLM protocol (`zoom_in` for visual
drill-down + auto-OCR, `compare_text` for end-cloud diff,
`emit_bubble` for the structured answer).  Round-1 ships the
**3200-px thumbnail + on-device OCR hint** (HMS ML Kit, offline);
the LLM drills into regions with `zoom_in` and ends with
`emit_bubble`.  Every `zoom_in` crop auto-runs a higher-fidelity
OCR scan and ships the result alongside the image, so the model
sees verbatim characters at every zoom level without a second
round-trip.  The user taps the bubble to see the image (filling
the screen at full aspect ratio) and a `details` table of every
visible text / number / brand / date / price the model read.

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full design and
**[CONFIG.md](CONFIG.md)** for every tunable constant.

## Headline (Kotlin eval, prod-mirror)

| metric | value |
|---|---|
| **composite @ 100 fixtures (with OCR)** | **0.903** (v1.0: 3200+4096 + union r2_text) |
| composite @ 20 fixtures (with OCR) | 0.883 mean (v1.1: 3 runs 0.880/0.862/0.908) |
| baseline chain | 0.652 → 0.819 → 0.835 → 0.853 → 0.868 → 0.887 → 0.903 |

The 0.903 ceiling is the same code path as production: 1-only image
strategy + auto-OCR on every zoom_in crop + workflow prompt
("round-1 → extract_text on [LOW] → trust crop OCR → emit") +
3200-px thumbnail / 4096-px fullRes.  v1.1 added the
`extract_text` tool (text-only sibling of zoom_in) without moving
the @20 composite — see `profiling/eval_v12c_20_ocr.json`.

## v1.1 release notes (2026-07-12)

Adds the **`extract_text`** tool — a text-only sibling of `zoom_in`
that runs OCR on a region and returns just the characters (no
image re-attach).  Adopted by the model on **~25-30% of fixtures**
(5-7/20 in the v1.1 eval runs) for cases where the model has
already seen the region in the round-1 thumbnail and only wants
verbatim characters.  Composite @20 mean: **0.883** across 3 runs
(0.880/0.862/0.908, std 0.023) — statistically equivalent to v1.0
baseline 0.887, no regression, new capability unlocked.

Workflow change: Step 2 now defaults to `extract_text` for
[LOW] /漏扫 / 已见区域 cases, with `zoom_in` reserved for when
the model needs to see new pixels.  The system prompt and tool
descriptions are updated accordingly.

Eval-scorer fix: `extract_text` is now recognized as a valid
recon tool (pickScore 1.0) — previously it was scored at 0.7
under the "other tool" fallback, which understated r1 for the
~25% of fixtures that adopted it.  See
`profiling/eval_v12*_20_ocr.json` for the three runs.

A 4×4 spatial grid summary in the round-1 OCR hint (originally
part of v1.1) was **reverted** before tagging — eval showed it
diluted r2_text fuzzy without helping the model, which already
infers spatial layout from per-line bbox.

## v1.0 release notes (2026-07-12)

Three production-critical fixes:

1. **On-device image pipeline now delivers full sensor resolution.**
   CameraX was defaulting `ImageAnalysis` to 640×480 VGA, so
   `MAX_DIM=3200` / `MAX_FULL_DIM=4096` were no-ops.  The LLM was
   receiving a thumbnail smaller than a website favicon.  Fixed
   via `pickLargestAnalysisSize()` — queries the back camera's
   `StreamConfigurationMap.getOutputSizes(YUV_420_888)`, picks
   the largest 4:3, passes to `ResolutionSelector`.  v1.0 eval
   still scores 0.903 (eval uses raw RCTW fixtures, not the
   device pipeline) — but on-device, every fixture now sees the
   architecture the model was tuned for.

2. **Cold-start camera race fixed.**  `ProcessCameraProvider`
   is now pre-warmed in `AppViewModel` construction (during
   `onCreate`), so by the time the user grants permission and
   taps shutter, the provider is ready and `bindToLifecycle`
   completes in ~150ms.  Capture timeout raised 500ms → 3000ms
   as a safety net for slow devices.

3. **DetailScreen UX overhaul.**  Image now fills the upper
   area at full aspect ratio (`weight(1f)` + `ContentScale.Fit`),
   text/details live in a scrollable panel directly below,
   header row is a tap-to-collapse toggle, 退出 button is sticky
   at the bottom.  Long bubbles and many detail rows no longer
   push content off-screen.

Plus debug-log enhancements for hunting regressions:

- `[CAP]` now prints actual wait time (`waited=47ms thumb=380KB
  full=620KB src=4032x3024`) — confirms the sensor-res fix landed.
- `[OCR]` now prefixes every status with `ocr_hit=true|false`
  and dumps confidence stats (avg/min/max) when blocks found.

## Quick start

```bash
# Build (JDK17 + Gradle 8.5)
JAVA_HOME=/path/to/jdk17 /path/to/gradle clean :app:assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`;
it's also copied to `./intentcam.apk` at the project root for easy
sideloading.

## Run on device

1. Camera permission on first launch
2. Tap the green **识别** button to capture a frame
3. Wait ~1-2 s for round 1 (OCR + LLM) + 0-2 zoom/extract rounds
4. Tap the resulting bubble to see the image (full-screen, scrollable
   text below, header tap to collapse for more image)
5. Tap **退出** to dismiss and start a new capture

## Debug overlay & log capture

The on-screen debug overlay (green bug icon, top-right) streams the
recognition pipeline live: `[CAM]` provider ready time, `[CAP]`
capture timing + actual sensor source size (`src=WxH`),
`[TOOL]` per-round dispatch, `[TOOL_ERR]` / `[FATAL]` / `[ANALYZER]`
exceptions, `[OCR]` round-1 OCR status with `ocr_hit=true|false` +
confidence stats, `[BUBBLE]` state transitions.  Each entry is
auto-wrapped — long stack traces are not truncated.

To capture logs to a file while reproducing a bug on a real device:

```bash
./scripts/capture_logs.sh                # install + filtered logcat
./scripts/capture_logs.sh --no-install   # capture only
```

Filter includes `IntentCam:V`, `AndroidRuntime:E`, `System.err:W`, and
`DEBUG:V` (in-app overlay entries).  Output lands in `./intentcam.log`.

## Run the eval (no device needed)

```bash
# Standard 20-fixture iteration run (~2 min)
JAVA_HOME=/path/to/jdk17 /path/to/gradle :shared:eval --args="--limit 20"

# Conclusive 100-fixture run (~30 min)
JAVA_HOME=/path/to/jdk17 /path/to/gradle :shared:eval --args="--limit 0 --json-out profiling/eval_run.json"

# Set HUAWEICLOUD_SDK_{AK,SK,PROJECT_ID} before invoking for the
# real OCR-hint baseline (otherwise runs blind — useful for ablation
# but not the prod-mirror number)
```

JSON dumps in `profiling/eval_*.json` document the baseline chain.
Compare two runs with `profiling/diff_eval.py`.

## Repository layout

```
app/src/main/java/com/example/intentcam/   — app source
  AppViewModel.kt          capture + runCycle + camera pre-warm
  FrameAnalyzer.kt         dual-JPEG capture (3200 thumb + 4096 fullRes)
  AndroidOcrEngine.kt      HMS ML Kit OCR backend
  AndroidImageOps.kt       Android ImageOps impl (BitmapRegionDecoder)
  LlmClient.kt             Anthropic-compatible streaming client
  MainActivity.kt          Compose UI (preview, debug overlay, detail)
                            + ResolutionSelector + pickLargestAnalysisSize
  Models.kt                Bubble / Detail / UiState
  Tools.kt                 ToolDef / ToolRegistry / ToolContext
  ToolImplementations.kt   zoom_in + compare_text + emit_bubble bodies
  ToolUseLoop.kt           multi-round orchestrator (auto-OCR on followUps)

shared/src/main/kotlin/com/example/intentcam/  — :shared module
  CapturedFrame.kt         frame = (thumbnail, fullRes)
  ImageOps.kt              CROP_OUTPUT_MAX_DIM=3200 + Android/JVM dispatch
  OcrEngine.kt             strategy holder + formatHint for round-1
  LlmClient.kt             (also in :app; same file, just packaged twice)
  ToolUseLoop.kt           (also in :app)
  eval/                    the eval pipeline — runs real prod code
    EvalMain.kt            CLI entry point
    EvalRunner.kt          per-fixture runner + composite scorer
    JvmHuaweiCloudOcrEngine.kt  Cloud OCR backend for the eval

profiling/
  ground_truth_rctw.json   100 real-photo fixtures (RCTW-17)
  ocr_huaweicloud_runner.py  subprocess helper for eval-side OCR
  eval_*.json              measurement trail (100+ JSON dumps)
  diff_eval.py             two-run side-by-side comparator

scripts/
  capture_logs.sh          adb install + filtered logcat to file

CONFIG.md                  every tunable constant + rationale
ARCHITECTURE.md            deep dive on the design
```

## License

Public repository — no license file yet.
