# Configuration Inventory

> Single source of truth for every **current** tunable knob in the
> IntentCam pipeline.  When you change a value, update the table +
> run an eval to confirm.
>
> **Last reviewed:** 2026-07-19 — full rewrite for v3.6 (action-first
> architecture + `view_label` + settings rework).  Historical tuning
> rationale and retired values live in [CHANGELOG.md](CHANGELOG.md)
> and [docs/adr/](docs/adr/README.md); this file intentionally
> documents only what is live today.

---

## A. Image pipeline

| Constant | Value | File | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_DIM` | `3200` | `shared/ImagePipeline.kt` | Thumbnail long edge (LLM input + bubble display) | k3 vision limit ≤4K; detail suffices at 3200 |
| `QUALITY` | `90` | `shared/ImagePipeline.kt` | Thumbnail JPEG quality | Size vs legibility balance |
| `MAX_FULL_DIM` | `4096` | `shared/ImagePipeline.kt` | Full-res long edge (zoom_in crop source) | Crop detail headroom |
| `FULL_QUALITY` | `95` | `shared/ImagePipeline.kt` | Full-res JPEG quality | Crops must survive re-encode |

## B. OCR

| Piece | Value | File | What |
|---|---|---|---|
| Device backend | HMS ML Kit offline zh+Latin | `app/AndroidOcrEngine.kt` | Round-1 hint + per-crop auto-OCR |
| Eval backend cascade | PP-OCRv4 local → Huawei Cloud → blind | `shared/eval/EvalMain.kt`, `profiling/pp_ocrv4_runner.py` | Local engine at `/home/oppry/work/pp_ocrv4_mobile_engine` auto-detected |

No OCR tool: `read_text` removed 2026-07-11 — OCR runs automatically
on round 1 and every `zoom_in` crop.

## C. LLM (Anthropic-compatible SSE)

| Constant | Value | File | What it controls | Why |
|---|---|---|---|---|
| `MAX_TOKENS` | `8192` | `shared/LlmClient.kt` | Response cap | k3 mandatory thinking counts against it; 3072 caused empty bubbles (`3c5d3a1`) |
| `REQUEST_TEMPERATURE` | `0.0` | `shared/LlmClient.kt` | Sampling | Determinism for eval |
| `TOTAL_TIMEOUT_MS` | `180_000` | `shared/LlmClient.kt` | Overall HTTP timeout | Thinking models are slow |
| `llmTimeoutMs` | `90_000` | `app/CycleManager.kt` | Per-cycle soft cap | Hung cycle → ERRORED, last good bubble preserved |
| Message order | tool_result first | `shared/LlmClient.kt` | First user block of a round | k3 strict validator 400s on image-first (`9407ec5`) |
| Defaults | `api.minimaxi.com/anthropic` / `MiniMax-M3` | `shared/Models.kt` (`LlmConfig`) | Runtime default endpoint | Baked token from `secrets.properties` → `BuildConfig.DEFAULT_AUTH_TOKEN` |
| Tool rounds | `maxRounds = 4` | `app/ActionOrchestrator.kt` | Tool-use loop bound | Empirical: enough for zoom+nudge, bounds latency |

Eval endpoint (not the runtime default): `ANTHROPIC_BASE_URL=
https://api.kimi.com/coding`, `ANTHROPIC_MODEL=k3` (the `k3[1m]`
alias was dropped 2026-07-19).

## D. Cycle pipeline

| Constant | Value | File | What it controls |
|---|---|---|---|
| `CYCLE_QUEUE_DEPTH` | `8` | `shared/Models.kt` (`UiState`) | Queued+in-flight cycles (shutter backpressure) |
| `CYCLE_CONCURRENCY` | `2` | `shared/Models.kt` | Concurrent LLM streams |
| `CYCLES_MAX_TOTAL` | `8` | `shared/Models.kt` | Terminal-job retention (FIFO eviction) |

## E. Action system

| Piece | Value | File | What |
|---|---|---|---|
| Action vocabulary | `open_in_maps, dial_number, scan_to_pay, redact_id, share, view_label` | `app/ActionDecl.kt` + `shared/eval/EvalRunner.kt` (mirror) | Chips the LLM may propose |
| Required inputs | maps:`query`, dial:`phone_number`, share:`text`, view_label:`label_markdown` | same + `shared/InputParsers.kt` | Ghost/spinner chip states + finalize gate + r_inputs |
| Rescue rules | phone / id / paymentQr / **labelMarkdown-present** | `shared/ActionRescue.kt` | Add-only chip rescue (maps/share deliberately not rescued) |
| Consent gates | **OFF (dev phase)** | `app/ActionDecl.kt` | `requiresConfirmation`/`userPrefKey` removed 2026-07-19; mechanism dormant, re-arm per action before end-user builds |
| Chip states | Validated / Spinner (mid-flight, incl. computed-false) / Ghost (terminal) | `app/ChipStateMapper.kt` | Spinner = non-tappable while cycle runs |

## F. `view_label` rendered page

| Constant | Value | File | What it controls | Why |
|---|---|---|---|---|
| viewport meta | `width=device-width, initial-scale=1` | `shared/LabelHtml.kt` | CSS px = dp | Without it WebView lays out at physical px width (15px ≈ 5dp on 3×) |
| `enableSlowWholeDocumentDraw` | on | `app/LabelPageScreen.kt` | Whole-document layout | Required to draw below the fold in the share capture |
| `MAX_CAPTURE_HEIGHT_PX` | `6000` | `app/LabelPageExporter.kt` | PNG height ceiling | Bounds transient bitmap (1080×6000 ≈ 25 MB) |
| Capture path | on-screen WebView resize→draw→restore + blank guard | `app/LabelPageExporter.kt` | Share-as-image | Off-screen WebViews rasterize unreliably (2 blank-PNG iterations) |
| FileProvider | `cache/label_pages` | `app/src/main/res/xml/file_paths.xml` | image/png share URI | No storage permission anywhere |

Debug hook: `adb shell am start -n com.example.intentcam/.MainActivity
--ez dev_label_page true` (DEBUG only) opens the page with canned
content.

## G. Eval

| Constant | Value | File | What |
|---|---|---|---|
| composite | `0.55·r_actions + 0.30·r_text + 0.15·r_inputs` | `shared/eval/ScorerV3.kt` | Sole canonical scorer |
| Threshold | `0.05` | `profiling/baselines.json` | Regression gate (per-component relaxed 0.15) |
| `--limit` default | `20` (0 = all) | `shared/eval/EvalMain.kt` | Iteration default; conclusive = 0 |
| `--resize` / `--quality` | `3200` / `90` | same | Mirror of the prod thumbnail |
| Suites | dial 29 · maps 34 · share 38 · scan 6 · none 16 · view_label 30 (+ redact reference-only) | `profiling/ground_truth_*.json` | Built by `build_action_suites.py --only <action>` |
| Baselines | dial 0.9049 · maps 0.9144 · share 0.9587 · scan 0.8444 · none 0.9474 · view_label 0.6711 | `profiling/baselines.json` | k3, 2026-07-19 |

## H. Settings / persistence

| Piece | Value | File | What |
|---|---|---|---|
| Baked default token | `secrets.properties` → `BuildConfig.DEFAULT_AUTH_TOKEN` | `app/build.gradle.kts`, `app/SettingsStore.kt` | Used when prefs have no token key |
| Save rule | persist only if LLM fields dirty | `app/SettingsScreen.kt` | No-op visit writes nothing → baked token (rotates with APKs) keeps applying |
| `debug_enabled` default | `false` | `app/SettingsStore.kt`, `shared/Models.kt` | 调试日志 switch (in 设置 since 2026-07-19) |

---

## Recently retired (pointers, not values)

Intent taxonomy (14 ids) + `IntentDecl.kt` · ScorerV2 · `read_text`
tool · consent-gate defaults (dial/scan/redact userPrefKey) ·
MediaStore save paths in `LabelPageExporter` · `SettingsStore.reset()`
+ 恢复默认 button · top-bar debug toggle.  Details: CHANGELOG
`[unreleased]` + `## [2026-07-1*]` sections.
