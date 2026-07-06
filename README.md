# IntentCam

A camera-based Android app that recognises intent from a single
phone photo using a 2-tool LLM protocol (`zoom_in` for detail,
`emit_bubble` for the structured answer). The user taps the
bubble to see the image and a `details` table of every visible
text / number / brand / date / price the model read.

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full design.

---

## Quick start

```bash
# Build (JDK17 + Gradle 8.5)
JAVA_HOME=/path/to/jdk17 /path/to/gradle clean :app:assembleDebug

## Run on device

1. Camera permission on first launch
2. Tap the green **识别** button to capture a frame
3. Wait ~1-2 s for the LLM response
4. Tap the resulting bubble to see the image + details table
5. Tap **退出** to dismiss and start a new capture

## Debug overlay & log capture

The on-screen debug overlay (green bug icon, top-right) streams the
recognition pipeline live: `[CAP]` capture timing, `[TOOL]` per-round
dispatch, `[TOOL_ERR]` / `[FATAL]` / `[ANALYZER]` exceptions, `[BUBBLE]`
state transitions.  Each entry is auto-wrapped — long stack traces are
not truncated.

To capture logs to a file while reproducing a bug on a real device:

```bash
./scripts/capture_logs.sh                # install + filtered logcat
./scripts/capture_logs.sh --no-install   # capture only
```

Filter includes `IntentCam:V`, `AndroidRuntime:E`, `System.err:W`, and
`DEBUG:V` (in-app overlay entries).  Output lands in `./intentcam.log`.

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

scripts/
  capture_logs.sh          adb install + filtered logcat to file

profiling/
  eval_rctw_v2.py          100-fixture benchmark
  ground_truth_rctw.json  100 scenes' expected keywords + details
  fetch_real_imgs.py       older Picsum-based 50-fixture set
  gen_img100.py            older synthetic 100-fixture generator
  runs/                    measurement trail of past eval runs
```

## License

Public repository — no license file yet. 
