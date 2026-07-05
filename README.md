# IntentCam

A real-time Android app that points the back camera at something and
produces a structured **"image details"** answer: text content,
numbers, brand, dates, prices, contact info — all in a table the user
can read.

The system is **LLM-driven end-to-end**. The model sees the photo,
calls `zoom_in` for unclear details, then `emit_bubble` with the
final answer. There is **no on-device CV** — no ML Kit, no OCR, no
barcode detector. The model does it all.

The current benchmark scores **0.652** average composite on 100 real
RCTW-17 street-view photos (default intent = image description,
single action = tap bubble → see image + details table).

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full design
(2-tool architecture, multi-round protocol, bubble model, eval
scoring, tunables).

---

## Quick start

```bash
# Build (JDK17 + Gradle 8.5)
JAVA_HOME=/path/to/jdk17 /path/to/gradle clean :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ./intentcam.apk

# Run the 100-fixture benchmark
python3 -u profiling/eval_rctw_v2.py --resize 768 --quality 80
```

Output: `intentcam.apk` (~10 MB). Default settings assume
`ANTHROPIC_BASE_URL=https://api.minimaxi.com/anthropic` and a
`MiniMax-M3` model. Override via the Settings screen or replace
`Models.kt` `DEFAULT_TOKEN` placeholder before building.

## Run on device

1. Camera permission on first launch
2. Tap the green **识别** button to capture a frame
3. Wait ~1-2 s for the LLM response
4. Tap the resulting bubble to see the image + details table
5. Tap **退出** to dismiss and start a new capture

## Eval benchmark (100 real RCTW-17 photos)

```
$ python3 -u profiling/eval_rctw_v2.py --resize 768 --quality 80
fixtures: 100
average composite: 0.652
```

- `r1_score 0.79` — model reliably picks `zoom_in` first
- `r2_type 1.00` — `emit_bubble.type = 'info'` consistent
- `r2_text 0.00-0.50` — model paraphrases instead of transcribing
  visible text verbatim; next iteration will close this gap

See ARCHITECTURE.md "Eval benchmark" for full details.

## Repository layout

```
app/src/main/java/com/example/intentcam/   — app source
  AppViewModel.kt          capture + runCycle
  FrameAnalyzer.kt         dual-JPEG capture + zoom-in crop
  LlmClient.kt             Anthropic-compatible streaming client
  MainActivity.kt          Compose UI (preview, debug overlay, detail)
  Models.kt                Bubble / Detail / UiState
  Tools.kt                 ToolDef / ToolRegistry / ToolContext
  ToolImplementations.kt   zoom_in + emit_bubble bodies
  ToolUseLoop.kt           multi-round orchestrator

profiling/
  eval_rctw_v2.py          100-fixture benchmark
  ground_truth_rctw.json  100 scenes' expected keywords + details
  fetch_real_imgs.py       older Picsum-based 50-fixture set
  gen_img100.py            older synthetic 100-fixture generator
  runs/                    measurement trail of past eval runs
```

## Build

```bash
# standard
JAVA_HOME=/path/to/jdk17 /path/to/gradle clean :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ./intentcam.apk
```

APK is ~10 MB at 768/q80 + native-q95 for zoom crops. Run `clean`
before measuring size — incremental builds accumulate stale native
libs and inflate the artifact.

## License

Public repository — no license file yet. The LLM API token in
`Models.kt` is a placeholder (`REPLACE_AT_RUNTIME`); real builds
need either a runtime Settings entry or an env-var-injected
default.
