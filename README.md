# IntentCam (意图相机)

A camera-based Android app that turns a single photo into actionable
next steps.  A frame goes through an on-device pipeline (dual JPEG +
on-device OCR hint) and a multi-round LLM tool-use protocol; the
result is a structured **bubble** with a free-form Chinese summary
and a set of tappable **action chips** — open in maps, dial, share,
view a rendered label page, and more.

| | |
|---|---|
| **Version** | 3.6 (versionCode 10) |
| **Min Android** | API 26 (Android 8.0), arm64-v8a |
| **LLM** | Anthropic-compatible streaming (default MiniMax-M3 @ `api.minimaxi.com/anthropic`; user-configurable in 设置) |
| **OCR (device)** | Huawei HMS ML Kit, offline Chinese + Latin |
| **OCR (eval)** | Local PP-OCRv4 mobile (cascade: local → Huawei Cloud → blind) |
| **APK size** | ~25 MB debug / ~17 MB release |
| **Eval entry** | `gradle :shared:eval --args="..."` (JVM, no device) |

---

## Architecture at a glance

```
  Shutter tap
      │
      ▼
  FrameAnalyzer ── dual JPEG (3200px thumb + 4096px fullRes)
      │              + on-device OCR hint (HMS ML Kit)
      ▼
  CycleManager ──── pending FIFO queue (n=8)
      │              worker pool (m=2) · allJobs/busy StateFlows
      ▼
  ToolUseLoop ───── multi-round tool-use (max 4 rounds)
      │              zoom_in · compare_text · extract_text · emit_bubble
      │              (Anthropic-compatible SSE, temperature 0,
      │               MAX_TOKENS 8192)
      ▼
  Bubble ────────── title + detail + details[] + confidence
      │              + llmProposedActions + labelMarkdown?
      ▼
  ActionResolver ── LLM 提议 ∩ 已注册 ∩ 已启用
      │  ActionOrchestrator ── requiredInputs 校验 + finalize gate
      │  ActionRescue ── 内容兜底补 chip(电话/证件/收款码/标签)
      ▼
  Chips (ChipState: Validated / Spinner / Ghost)
      │
      ├─ open_in_maps → geo: Intent        ├─ dial_number → ACTION_DIAL
      ├─ share → ACTION_SEND text          ├─ scan_to_pay / redact_id → Toast
      └─ view_label → LabelPageScreen (WebView 渲染整页,可分享图片/文字)
```

Deep dive: **[ARCHITECTURE.md](ARCHITECTURE.md)** · every tunable:
**[CONFIG.md](CONFIG.md)** · decisions: **[docs/adr/](docs/adr/README.md)**

---

## Action model (action-first, since 2026-07-17)

There is **no intent taxonomy** (retired 2026-07-17, `f522053`).
The LLM picks user-facing action ids directly in
`emit_bubble.action_ids`; the resolver whitelists them against the
registry and intersects with the user's enabled set.  A content-based
rescue adds chips the LLM missed (phone / ID / payment-QR patterns,
and `view_label` whenever a `label_markdown` transcription exists).

| id | chip | 作用 | required input |
|---|---|---|---|
| `open_in_maps` | 在地图中打开 | `geo:` Intent + chooser(支持按名称搜索) | `query`(地点/地址) |
| `dial_number` | 拨号 | `ACTION_DIAL`(拨号器里再按拨打) | `phone_number` |
| `share` | 分享文本 | `ACTION_SEND text/plain` | `text` |
| `view_label` | 查看标签 | 整页渲染标签(见下) | `label_markdown` |
| `scan_to_pay` | 扫码支付 | 安全提示 Toast(永不自动起支付) | — |
| `redact_id` | 遮挡证件号 | 提示 Toast | — |

**Dev phase (2026-07-19):** all consent gates are OFF — no default-off
toggles, no confirmation dialogs.  The mechanism
(`requiresConfirmation` / `userPrefKey`) stays dormant in code and is
re-armed per-action before any end-user build.

Adding a new action = one `ActionDef` in
`app/.../ActionDecl.kt#registerDefaultActions` + one mapping sentence
in `emit_bubble`'s description + eval mirror in
`EvalRunner.defaultActionIds`.  See ADR
`docs/adr/2026-07-19-view-label-action.md` for a worked example.

---

## `view_label` — 标签识别整页渲染

When the LLM recognizes a label-like structured text block
(商品标签/价签/吊牌/合格证/快递面单/票据/铭牌), it transcribes the
full label into `emit_bubble.label_markdown`.  The chip opens a
full-screen page that renders the markdown (hand-rolled subset →
HTML converter `shared/LabelHtml.kt` + WebView) and offers:

- **分享图片** — full-page PNG captured from the on-screen WebView
  (`enableSlowWholeDocumentDraw` + resize-draw-restore), shared via
  FileProvider.
- **分享文字** — the markdown source as `text/plain`.

Zero new dependencies, zero storage permissions.

---

## Eval (Kotlin, prod-mirror, ScorerV3)

Single canonical scorer:

```
composite = 0.55 · r_actions(recall) + 0.30 · r_text + 0.15 · r_inputs
```

Current baselines (`profiling/baselines.json`, measured on **kimi k3**
@ `api.kimi.com/coding`, 2026-07-19):

| Suite | composite | n | note |
|---|---:|---:|---|
| `dial_number` | **0.9049** | 29 | |
| `open_in_maps` | **0.9144** | 34 | |
| `share` | **0.9587** | 38 | |
| `scan_to_pay` | **0.8444** | 6 | strict-payment curation |
| `none` | **0.9474** | 16 | over-fire check |
| `view_label` | **0.6711** | 30 | first baseline; 8 a=0 = prompt lever |
| `redact_id` | — | 1 | reference-only (corpus lacks IDs) |

The regression net (`scripts/run_regression.sh`) flags |Δ| ≥ 0.05
against these baselines.

### Run it

```bash
export ANTHROPIC_AUTH_TOKEN=<key>            # eval-only credential
export ANTHROPIC_BASE_URL=https://api.kimi.com/coding
export ANTHROPIC_MODEL=k3                    # k3[1m] alias is dead

# single suite (~2 min/scene with k3 thinking)
gradle :shared:eval --args="--ground-truth profiling/ground_truth_view_label.json \
    --img-dir /home/oppry/RCTW-171/train_images --limit 0 \
    --resize 3200 --quality 90 --json-out /tmp/out.json"

# full regression (reads profiling/baselines.json)
./scripts/run_regression.sh
```

Gotchas: gradle's `--args` needs the `=` form; GT files use the
`scenes` key; build `:shared:jar` first when `shared/` changed;
`build_action_suites.py` must be run with `--only <action>` (a full
run clobbers hand-curated suites).

---

## Quick start

### Build (JDK 17 + local Gradle 8.5)

```bash
export PATH=/path/to/gradle-8.5/bin:$PATH
gradle :app:assembleDebug        # or :app:assembleRelease
```

APKs land in `app/build/outputs/apk/…` and are conventionally copied
to `./intentcam.apk` / `./intentcam-release.apk` (gitignored).  The
baked default LLM token comes from `secrets.properties`
(gitignored) → `BuildConfig.DEFAULT_AUTH_TOKEN`.

### Run on device

1. Grant camera permission on first launch.
2. Tap the shutter; wait for the multi-round recognition
   (up to 4 rounds, ~seconds each).
3. Tap a chip to fire an action; tap the bubble card for the
   detail view (full image + extraction table).
4. 设置 (top-right gear): LLM endpoint config (leave untouched to
   keep using the baked default — saves only when you actually edit),
   调试日志 switch (default off), 关于.

### Debug hook (emulator / dev)

```bash
adb shell am start -n com.example.intentcam/.MainActivity --ez dev_label_page true
```

opens the `view_label` page with canned content — exercises the
render / full-page capture / share path without a camera frame or
LLM round (DEBUG builds only).

---

## Repository layout

```
app/src/main/java/com/example/intentcam/     — Android app (Compose)
  MainActivity.kt          camera preview, bubble cards, DetailScreen,
                           dialogs, AppRoot phase routing + label overlay
  AppViewModel.kt          state, action dispatch (ActionOutcome),
                           settings save (dirty-check), dev hook
  CycleManager.kt          producer/consumer cycle pipeline
  FrameAnalyzer.kt         dual-JPEG capture + image analysis
  ActionDecl.kt            ActionOutcome (5 variants) + ActionRegistry
                           + 6 default actions
  ActionOrchestrator.kt    requiredInputs validation + finalize gate
  ChipStateMapper.kt       chip state resolution (Validated/Spinner/Ghost)
  LabelPageScreen.kt       view_label full-screen WebView page
  LabelPageExporter.kt     full-page PNG capture + share intents
  AndroidOcrEngine.kt      HMS ML Kit OCR backend
  AndroidImageOps.kt       BitmapRegionDecoder-based ImageOps
  SettingsStore.kt         SharedPreferences (config, debug, enabled set)
  SettingsScreen.kt        设置 (endpoint fields, 调试日志, 关于)
  Theme.kt / Palette.kt    dark Material3 theme + accent clusters

shared/src/main/kotlin/com/example/intentcam/   — pure JVM (prod+eval)
  ToolUseLoop.kt           multi-round tool-use orchestrator
  ToolImplementations.kt   zoom_in / compare_text / extract_text /
                           emit_bubble (schema + label_markdown)
  LlmClient.kt             SSE client + system prompt (action-id splice)
  Models.kt                Bubble / UiState / CycleSnapshot / LlmConfig
  ActionArgs.kt            RenderedLabel / PendingAction / ActionInputSpec
  ActionRescue.kt          content-rescue + visibleActions (prod+eval)
  InputParsers.kt          phone / location / text / id / paymentQr /
                           labelMarkdown — single source of truth
  LabelHtml.kt             markdown-subset → HTML label-page renderer
  Tools.kt / OcrEngine.kt / ImagePipeline.kt / ImageOps.kt /
  CapturedFrame.kt / CropStrategy.kt / FormatThrowable.kt

shared/src/main/kotlin/com/example/intentcam/eval/   — JVM eval
  EvalMain.kt / EvalRunner.kt / ScorerV3.kt
  JvmLocalOcrEngine.kt (PP-OCRv4) / JvmHuaweiCloudOcrEngine.kt

profiling/                 GT suites (ground_truth_*.json), baselines.json,
                           suite builder, OCR runner, regression dumps
scripts/                   run_regression.sh, check_regression.py,
                           migrate_gt_v2_to_v3.py, capture_logs.sh

ARCHITECTURE.md / CONFIG.md / CHANGELOG.md / docs/adr/
```

---

## License

Public repository — no license file yet.
