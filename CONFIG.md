# Configuration Inventory

> Single source of truth for every tunable knob in the IntentCam
> pipeline.  When you change a value, update the table + run an
> eval to confirm.

**Last reviewed:** 2026-07-12 §H-L sync — Intent↔Action framework
+ 10-pass verifier + post-guard + C3 v3 prompt + GT schema
dual-read (composite jump pii_20 0.8794 → **0.9631** cumulative;
Phase G 15-fixture 0.973).  Prior: 2026-07-10 v1.3 ship (A2
scorer fix: `info ↔ location` promoted to full credit, lifts
6/20 floored fixtures +0.12 each composite 0.9078 → **0.9391
@20**; B+C2 prompt experiment rejected @20 -0.007 net, fixture
14 regression traced to extra prompt jargon confusing model on
bilingual fixture).  v1.1.0 (2026-07-12) added `extract_text` +
Step 2 routing; v1.0 critical fix set sensor-max 4:3
ImageAnalysis + 3s capture timeout + 4096/3200/4096 image
pipeline.  On-device architecture stable; tool-routing
experiment in noise band but unlocked 25-30% extraction
adoption.  Next round: r2_text ceiling (~0.74 strict, but A2
closed the type floor — only true text-miss fixtures remain).

**Baseline chain:** 0.820 → 0.838 → 0.841 → 0.853 → 0.868 →
0.887 (unionscore @20) → 0.902 (option C @20) → 0.903 (@100
unionscore) → **0.939 (v1.3 @20, A2 scorer + MAX_TOKENS 3072)**.

**Baseline chain:** 0.820 (over-hedged @20) → 0.838 (softened
prompt @20) → 0.841 (1568 + nudge @20) → 0.853 (1568 + nudge
@100, conclusive) → 0.898 (@20, `MAX_FULL_DIM 4096→2048`) → 0.874
(Phase 2a: auto-OCR + workflow prompt @100) → 0.868 (Phase 2:
`read_text` retired @100) → 0.889 (@20, 4096 + `MAX_TOKENS 1024→2048`;
empty-bubble fix) → 0.902 (@20, 3200+4096 strict Step 2) →
0.891 (@100, 3200+4096 strict Step 2, separate r2_text) →
0.903 (@100, 3200+4096 strict Step 2 + union r2_text) →
**0.939 (@20, v1.3: A2 scorer `info↔location=1.0` + MAX_TOKENS 3072;
9W/4L/7T)**.  All run on the same OCR-enabled Kotlin
`:shared:eval`; the deltas are architectural, not noise.

---

## A. Image pipeline

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_DIM` | **3200** | `app/.../FrameAnalyzer.kt:159` | Round-1 thumbnail max-dim for LLM | **2026-07-12 option C ship: 1568→3200**, the LLM sweet spot. Round-1 thumbnail is the LLM's first view; at 1568 dense text was unreadable in round-1 and the model was forced to drill-down for almost every fixture. At 3200, round-1 + 4096-px OCR can resolve most fixtures directly — fewer rounds, less latency. **Option D (4096) was tested 2026-07-12 and REVERTED** — pushing to model max caused "attention-spread" regression (composite 0.902 → 0.885). 3200 stays in the focused-attention band. |
| `QUALITY` | 90 | `app/.../FrameAnalyzer.kt:160` | Round-1 thumbnail JPEG quality | q90 vs q80 +0.015-0.017 reproducible |
| `MAX_FULL_DIM` | 4096 | `app/.../FrameAnalyzer.kt:176`, `shared/.../eval/EvalRunner.kt:103` | Full-res JPEG cap (source for `zoom_in=original`) | Restored 2048→4096 on 2026-07-12 after user overrode the 2026-07-10 round 3 conclusion. Reason: under Phase 2 (auto-OCR on every zoom crop + workflow prompt), 4096 source pixels feed crop OCR more raw chars which the model now explicitly "trust verbatim" — the optical-depth trade-off shifted. Combined with `MAX_TOKENS 1024→2048` (same commit), recovered 4 empty bubbles → composite 0.850 → **0.889** (+0.039 @20, 9W/3L/8T). |
| `FULL_QUALITY` | 95 | `app/.../FrameAnalyzer.kt:177` | Full-res JPEG quality | Visually lossless → all subsequent crops also lossless |
| `CROP_OUTPUT_MAX_DIM` | **3200** | `shared/.../ImageOps.kt:70` | Max-dim cap on `cropJpegRegion` output (zoom_in) | **2026-07-12 option C ship: 1568→3200** to match `MAX_DIM`. A 50% region on 4096-px source is 2048-px output, a 100% zoom is 3200-px output — both ≥ the round-1 thumbnail's effective area, so zoom_in is still a magnifier (or at minimum equal-resolution focused on less content). **Option D (4096) tested and REVERTED** — same attention-spread issue. |
| `DEFAULT_CROP_QUALITY` | 90 | `shared/.../ImageOps.kt:60` | Zoom crop output quality | q90 keeps edge detail for small text; q80 smudged at 1568-cap re-encode (note: at 3200-cap the q80 smear is even worse — keep q90) |
| Region min w/h | 0.05f | `shared/.../ToolImplementations.kt:81,82` | Min crop region (normalized) | Below 5% the crop has too little info to be useful |
| ImageAnalysis `ResolutionSelector` | **sensor max 4:3, dynamic** | `app/.../MainActivity.kt:253-292` (call site), `pickLargestAnalysisSize()` `MainActivity.kt:830-880` | Size of the `ImageProxy` buffer CameraX delivers to `FrameAnalyzer.analyze()` | **v1.0 critical fix.** Without an explicit `ResolutionSelector`, CameraX defaults to **640×480 VGA** for `ImageAnalysis` — meaning `MAX_DIM=3200` and `MAX_FULL_DIM=4096` in FrameAnalyzer were no-ops (encodeBitmap only downscales, never upscales). The LLM was receiving a 640×480 JPEG; zoom_in crops from this source were dying (50% of 640 = 320 = a "magnified" image that's smaller than round-1 thumbnail). Now: query the back camera's `StreamConfigurationMap.getOutputSizes(YUV_420_888)` via `Camera2CameraInfo`, pick the largest 4:3 (fall back to largest-by-area), pass to `ResolutionStrategy(size, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`. Works on every Android device that CameraX supports. |
| `ProcessCameraProvider` pre-warm | viewmodel init | `app/.../AppViewModel.kt:50-57` | Kick off `ProcessCameraProvider.getInstance(app)` at viewmodel construction | **v1.0 critical fix.** Previously called only inside the AndroidView factory (which runs AFTER permission grant + recomposition), so the first shutter tap raced `getInstance` (100-300ms) + `bindToLifecycle` (100-300ms) + first `analyze()` call (33ms+). At 500ms timeout, first tap always failed. Now: pre-warm in viewmodel.init runs during `onCreate`, so by the time the user grants permission and taps shutter, the provider service connection is already established. Verified via `CAM` log line "provider ready after Xms" — typically 50-150ms by the time the user reacts. |

## B. OCR pipeline

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_OCR_HINT_LINES` | 30 | `shared/.../OcrEngine.kt:100` | Top-N OCR blocks injected into round-1 user message | Keeps prompt bounded (~2 KB); "details: 5-8" leaves headroom. Tested 20 (2026-07-10 round 2): regressed r2_text_fuzzy -0.042, reverted |
| `MAX_CROP_OCR_HINT_LINES` | 10 | `shared/.../OcrEngine.kt:111` | Top-N OCR blocks per zoom_in crop hint | **Phase 2 (2026-07-11)**. Crops are smaller regions; 10 lines covers the dense case without blowing the multi-zoom token budget (3 zooms × ~1 KB = 3 KB vs 3 × ~2 KB at 30) |
| `LOW_CONFIDENCE_THRESHOLD` | 0.5 | `shared/.../OcrEngine.kt:89` | conf<0.5 → mark `[LOW]` in hint | Standard; below 0.5 OCR often misreads |
| `MAX_BITMAP_DIM` | **4096** | `app/.../AndroidOcrEngine.kt:73` | Decoded bitmap cap for HMS ML Kit OCR | **2026-07-12 shift: 1920→4096** alongside MAX_DIM=3200. Round-1 OCR now reads the full 4096-px fullRes directly, so [LOW] rate drops and the model has fewer reasons to zoom_in just to verify OCR. Within HMS's reported working range. |
| `PRIMARY_LANGUAGE` | "zh" | `app/.../AndroidOcrEngine.kt:64` | HMS ML Kit OCR language | Current test set is RCTW (Chinese) |
| OCR endpoint | cn-north-4 | `profiling/ocr_huaweicloud.py:84` | Huawei Cloud OCR region | Fixed by user's project region. The Kotlin side `JvmHuaweiCloudOcrEngine` (line 59) shells out to this Python helper. |
| `detect_direction` | true | `profiling/ocr_huaweicloud_runner.py:44` (imports OcrRegion) | Rotation detection | Phone photos can be sideways; true is the right default |
| `language` | "zh" | `profiling/ocr_huaweicloud_runner.py` (calls into ocr_huaweicloud.py) | Cloud OCR language | Mirrors HMS; matches test set |
| Per-call subprocess | ~2-3s | runtime | OCR latency on JVM eval | SDK init + HTTPS roundtrip; 20 fixtures × 3s ≈ 60s overhead |

### B.1 Local OCR backend (PP-OCRv4) — primary eval backend (2026-07-13)

Huawei Cloud OCR was deemed too expensive at scale.  Switched the eval
pipeline to a local PP-OCRv4 (PaddleOCR 2.7.3) wrapper at
`/home/oppry/work/pp_ocrv4_mobile_engine/` (referenced, not imported —
per user instruction).  **Eval baselines are invalidated** as a result;
all 9 prior suites re-baselined under the new backend (2026-07-13+).
Pre-PP-OCRv4 numbers stay in memory as historical reference.

**Cascade in `EvalMain.kt:38-55`** (priority order):
1. `JvmLocalOcrEngine.installIfConfigured()` — primary
2. `JvmHuaweiCloudOcrEngine.installIfConfigured()` — fallback (kept for emergency)
3. `OcrEngine.impl == null` — blind eval (matches pre-OCR baseline)

**Env vars** (all optional, defaults shown):

| Env var | Default | Notes |
|---|---|---|
| `OCR_PYTHON` | `python3` | Python interpreter for `pp_ocrv4_runner.py` |
| `OCR_PYTHONPATH` | `/home/oppry/work` | Augmented onto PYTHONPATH so `pp_ocrv4_mobile_engine` is importable. Set to empty string to skip augmentation. |
| `LOCAL_OCR_KIND` | `mobile` | `mobile` (12 MB, 2.4 s/img CPU) or `server` (450 MB, 27 s/img CPU, +10% F1) |
| `LOCAL_OCR_MAX_LONG` | `4096` | matches `MAX_FULL_DIM` in `FrameAnalyzer.kt:176` |
| `LOCAL_OCR_JPG_QUALITY` | `90` | JPEG recompress quality inside the runner. Engine validates 50..100; 90 is the F1 sweet spot (95 hurts F1 per `pp_ocrv4_mobile_engine` docstring). |
| `LOCAL_OCR_USE_GPU` | `0` | `1` to enable PaddleOCR GPU path (untested on this ROCm box per the engine's README) |

**Architecture**:
- Long-lived Python subprocess (`profiling/pp_ocrv4_runner.py`) keeps the
  PaddleOCR model resident for the entire eval run; per-call cost is the
  PaddleOCR recognition pass itself, not Python startup.
- Line-delimited JSON-RPC over stdin/stdout: requests
  `{"id": N, "method": "recognize", "params": {"image_b64": "..."}}`,
  responses `{"id": N, "result": {"blocks": [...], "full_text": "..."}}`.
- Output `blocks` schema matches `JvmHuaweiCloudOcrEngine`'s so the
  same `OcrBlock` shape feeds the eval scorer — `corners: [[x, y], ...]`
  4 corners TL→TR→BR→BL normalized to [0, 1] in the preprocessed-image
  coordinate system (equivalent to source-image coords because the
  engine's `preprocess_image` preserves aspect ratio).
- **Per-call timeout**: 30 s mobile / 120 s server. On timeout or
  subprocess crash: restart + retry once; on second failure return
  `OcrResult.EMPTY` (eval keeps running).

## C. LLM pipeline

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_TOKENS` | 3072 | `shared/.../LlmClient.kt:361` | Output cap per round | **3072 on 2026-07-10 per user decision.** Architecture vs prior 3072 test (2026-07-10 reject @20: 0.879, -0.023 attention-spread on rctw_04/14/20) differs: Phase 2 auto-OCR + retry-once + MAX_DIM=3200 + union r2_text scoring. **Cap-removal retest at 3072 confirmed the failure mode persists with cap removed** (no_cap @20 = 0.871, -0.037; rctw_12/19/20 -0.27 to -0.30 individual regressions), so cap stays at "5-8 行" (see §D). With cap, 3072 vs 2048 @20 = 0.906 vs 0.9078 = -0.002 (in noise). 3072 kept per user; if 2048 re-measured diverges more than noise, revisit. |
| `REQUEST_TEMPERATURE` | 0.0 | `shared/.../LlmClient.kt:364` | Sampling temperature | Lock at 0 for deterministic intent classification |
| `TOTAL_TIMEOUT_MS` | 90_000 | `shared/.../LlmClient.kt:377` | Per-round LLM timeout | Bumped 60s→90s on 2026-07-12. 60s was right for `MAX_TOKENS=1024` (~38s worst case); with `MAX_TOKENS=2048`, worst-case read on overload approaches 73s. 90s covers that with margin. **Under MAX_TOKENS=3072 + cap-restored, observed 0/20 timeouts in today's @20 run** (typical emit ~500 tokens well under cap); keep 90s. Re-derive if cap is relaxed. |
| `connectTimeout` | 15s | `shared/.../LlmClient.kt:41` | HTTP connect | Standard |
| `writeTimeout` | 30s | `shared/.../LlmClient.kt:43` | HTTP write | Standard |
| `readTimeout` / `callTimeout` | 0 (infinite) | `shared/.../LlmClient.kt:42,44` | HTTP read/call | SSE streaming requires infinite; real hangs caught by `TOTAL_TIMEOUT_MS` |
| `MAX_ROUNDS` | 30 | `shared/.../ToolUseLoop.kt` | Per-cycle iteration cap | Allows iterative drill-down; most fixtures converge in 5-10. Tested 15 (2026-07-10): at least one fixture hit cap, 兜底 empty → -0.257 single fixture. Reverted. |
| `CYCLE_QUEUE_DEPTH` | 8 | `UiState` companion | Max queued+in-flight cycles (shutter dim threshold) | Producer/consumer split (2026-07-16). 8 captures fits the typical user burst without unbounded memory growth. |
| `CYCLE_CONCURRENCY` | 2 | `UiState` companion | Worker pool size | Caps concurrent Anthropic SSE streams; bounds peak device memory; avoids on-device OCR analyzer contention. |
| `CYCLES_MAX_TOTAL` | 8 | `UiState` companion | Terminal FIFO cap on the cycles map | Prevents memory growth from long sessions; oldest COMPLETE/ERRORED/SUPERSEDED is evicted. |
| `llmTimeoutMs` | 90_000 ms | `CycleManager` constructor | Per-cycle soft cap on the LLM call | Generous enough for 2-3-round cycles on slow networks (median ~2-3s/round × 30 rounds); tight enough that a true hang surfaces within ~90s. |
| `DEBUG_LOG_MAX` | 40 | `UiState` companion | Debug log cap | Reasonable debug history |
| `DEFAULT_TOKEN` | "REPLACE_AT_RUNTIME" | `LlmConfig.DEFAULT_TOKEN` | Token placeholder at build time | Real builds need runtime Settings entry or env-var injection (TODO) |
| `DEFAULT_MODEL` | "MiniMax-M3" | `LlmConfig.DEFAULT_MODEL` | LLM model name | Sandbox default per env; user-overridable in Settings |
| capture timeout | **3000 ms** | `AppViewModel.captureLatestFrame` | How long to wait for the analyzer's next frame | **v1.0 bumped 500ms→3000ms.** Two reasons: (1) Cold start: even with pre-warming (see A.6), CameraX `bindToLifecycle` on first launch takes 200-800ms — `analyze()` doesn't fire at all until binding completes, so first shutter tap needs >500ms headroom. (2) Larger encodes: `MAX_FULL_DIM=4096` JPEG q95 takes ~200-400ms on its own. 3s covers both, with no impact on warm path (typical wait 50-80ms). The CAP log line shows the actual `waited=` value so cold vs warm is visible at a glance. |

## D. Tool behavior

| Parameter | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| zoom_in default source | "original" | `shared/.../ToolImplementations.kt:93` | First zoom crops 2048-px fullRes | Pre-2026-07-10 was "last" — broke because 50% of 768 = 384 < round-1 view; default="original" makes zoom always a magnifier |
| zoom_in crop OCR auto | always on (prod) | `shared/.../ToolUseLoop.kt:699` | Every followUpJpeg auto-OCR | **Phase 2 (2026-07-11)**. See §G workflow narrative. Crop OCR result attached as a text content block alongside the image. |
| `extract_text` (v1.1) | text-only OCR | `shared/.../ToolImplementations.kt:210-330` | **v1.1 (2026-07-12)**. New tool. Same crop path as `zoom_in` follow-up (cropJpegRegion + OcrEngine.recognize + formatHint) but **no followUpJpeg** — returns only formatted OCR text. Adopted by the model in 5-7/20 fixtures (~25-35%) for [LOW] / 漏扫 / 已见区域 cases. Composite @20 mean 0.883 vs v1.0 0.887 (in noise). |
| `extract_text` routing rule | "default for [LOW]" | `shared/.../LlmClient.kt:431-441` (Step 2 paragraph) | **v1.1 (2026-07-12)**. Step 2 of the workflow now defaults to `extract_text` for [LOW] / 漏扫 / 已见区域 cases, with `zoom_in` reserved for "need to see new pixels" (corner text not in thumbnail, non-text content). Without this routing rule the model picked `extract_text` 0% of the time (v1.1 first attempt). |
| compare_text cache | round-1 OCR only | `shared/.../ToolImplementations.kt:179` (passes `ctx.ocrCache`) | diff scope | Crop OCR is not in `ocrCache`; only round-1 result. If model needs crop-level diff, future extension (out of scope). |
| Region w/h minimum | 0.05f | (see A. above) | Same as crop min | Same |
| details cap (prompt) | "5-8 行" | `shared/.../LlmClient.kt:501` | Hint to model on details array size | Prevents over-long answers; 8 covers most scenes. **2026-07-10 retest of cap-removed: rejected at @20 = 0.871 (-0.037 attention-spread)**; cap restoration required. |
| `cropOcrCap` | 0 (unlimited) | `shared/.../ToolUseLoop.kt:409,418` | Per-cycle cap on followUpJpeg OCRs | **Phase 2 (2026-07-11)**. Fast-iter knob: 0 = unlimited (prod), 1 = round-1 + first crop only (~2-min/20-fixture pace). Set via `--crop-ocr-cap N` eval arg. |

## E. Eval scoring + CLI

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `--resize` | **3200** | `shared/.../eval/EvalMain.kt:232` | eval thumbnail dim (mirrors `MAX_DIM`) | **2026-07-12 option C ship: 1568→3200.** 1:1 mirror of prod. **Option D (4096) tested and REVERTED.** |
| `--quality` | 90 | `shared/.../eval/EvalMain.kt:233` | eval thumbnail quality (mirrors `QUALITY`) | 1:1 mirror of prod |
| `--limit` | 20 | `shared/.../eval/EvalMain.kt:231` | Default fixtures per run | Per user rule 2026-07-08: 20 for iteration, `--limit 0` for conclusive |
| `--crop-ocr-cap` | 0 | `shared/.../eval/EvalMain.kt:235` | Max followUpJpeg OCRs per cycle | **Phase 2 (2026-07-11)**. Default 0=unlimited; N>0 caps crop OCR for fast iter |
| `--debug-fixtures` | (none) | `shared/.../eval/EvalMain.kt:236` (2026-07-12) | Comma-separated fixture ids whose ToolUseLoop logs forward to stderr | **2026-07-12**. Diagnostic mode: orchestrator hot-loop stays silent for the rest, named fixtures print full debug trace. Use during targeted reruns on regressions. |
| `--fixtures` | (none) | `shared/.../eval/EvalMain.kt:239` (2026-07-12) | Comma-separated fixture ids to run, in GT order | **2026-07-12**. Restricts the run to a curated id set — iterate on a small subset without rebuilding the whole 20- or 100-fixture run. Useful alongside `--debug-fixtures`. |
| `skipReconScore` | 0.85 (text fixtures), 1.0 (no-text) | `shared/.../eval/EvalRunner.kt:305` | r1 score when model skips recon | Bumped 0.5→0.85 in endcloud era (2026-07-08); OCR hint makes skip-recon legitimate |
| `CHAR_OVERLAP_THRESHOLD` | 0.67 | `shared/.../eval/EvalRunner.kt:270` | Fuzzy-match char-overlap fallback | Mirrors Python aligned4; below 0.67 = no hit |
| **r2_text haystack (union)** | **content + " " + all details values** | `shared/.../eval/EvalRunner.kt:350-355` | Both textScore (GT keywords) and detailScore (GT detail values) match against the UNION of content + details | **2026-07-12 ship: union-scoring r2_text.** Old: textScore checked content alone, detailScore checked details alone, average → empty content floored r2_text even when details had the verbatim text. Union makes both checks pool content+details, so a hit anywhere in the model output registers. @100: composite 0.891 → 0.903 (+0.012, re-score predicted +0.019), empty 7/100 → 2/100. |
| r1/r2 composite weights | 0.50 / 0.50 | `shared/.../eval/EvalRunner.kt:103` | Composite formula | 2026-07-10 round 3: tested 0.40/0.60 — dropped headline 0.898→0.829 (-0.069) because r1=0.96 (near-ceiling) got less weight. Pure score rebalance, not behavior change. User reverted 2026-07-10: headline tracking > honest r2 surfacing. |
| r2 text/type weights | 0.50 / 0.50 | `shared/.../eval/EvalRunner.kt:421` | r2 internal split | Same |
| type scoring (v1.3) | right / **`info↔location=1.0`** / solve-mismatch=0.5 / empty=0.0 | `shared/.../eval/EvalRunner.kt:413-433` | Type match with three buckets: exact, info↔location interchangeable, solve stays partial | **v1.3 (2026-07-10)**: signs / storefronts / 商户招牌 (e.g. "大懒人冒菜", "FJ儿童业态") are BOTH "read the sign" (info) AND "find this place" (location).  Previous 0.5 floor held 6/20 fixtures at composite 0.82 in v12c even with 100% keyword match.  Promoting to 1.0 unblocked those → composite 0.9078 → **0.9391 @20** (+0.031, 9W/4L/7T).  solve stays partial because "solve this problem" is a different intent class. |
| text hybrid scoring | fuzzy ∪ char-overlap≥0.67 | `shared/.../eval/EvalRunner.kt:520-531` | Per-keyword match strategy | Catches "建国路 100号" vs "建国路100号" splits the strict scorer misses |

## F. System prompt behavior knobs (qualitative)

These are paragraph-level behaviors in `TOOL_USE_SYSTEM`
(`shared/.../LlmClient.kt:428-510`), not numeric constants.  The
prompt is a 4-step **workflow narrative** (Step 1 → 4, lines
433-453) + tool descriptions + content/details/anti-hallucination
guidance.  Each is a tradeoff:

| Behavior | Current guidance | Why | Risk of changing |
|---|---|---|---|
| OCR = "first opinion" | OCR hint is primary verbatim source; LLM can also use own vision for OCR-missed text | Maximizes text coverage; balanced against hallucination | If too strict, model over-hedges (empty emit); if too loose, OCR errors propagate |
| Step 2 strength | **Option C strict (2026-07-12): [LOW] / 漏扫 / 缩略图看不清 → 必须 zoom_in** | "不要因为缩略图看着像就跳过" — 3200 thumbnail 让人产生"看清了"的错觉，要 hard-rule 拉回 [LOW]→zoom_in 路径 | The 3200+4096 architecture needs the strict version; the "soft" version (Phase 1-style "必要时才 zoom") caused free-information-paradox and the rctw_14/19/15 empty regressions |
| Workflow narrative | Step 1 (read OCR) → Step 2 ([LOW]→zoom_in) → Step 3 (trust crop OCR) → Step 4 (emit_bubble) | **Phase 2 (2026-07-11)**. The load-bearing piece. Without the explicit "trust crop OCR" Step 3, auto-attached OCR was treated as low-confidence (Phase 1 attempt rejected, r2_text_fuzzy -0.17). | If Step 3 wording weakens, model hedges crop OCR. |
| Crop-frame bbox caveat | "zoom crop hint 的 bbox 是 crop frame — 要在 details[].bbox 里复用，offset 加回传给 zoom_in 的 (x, y)" | Crop bboxes are normalized in the crop's [0,1], not the original photo's | If model skips the offset, details table can't highlight the row in original frame |
| Anti-hallucination | "OCR 字符 verbatim 引用，但绝不发明" + '?' placeholder for hand-written/unreadable | User-safety: wrong text > no text > fabricated text | Same |
| details cap | "5-8 行" for scenes with >8 text regions | Bounds answer length under 3072-token cap | Loose guidance; model often emits 1-3 details instead of 8; cap-removal test (2026-07-10) regressed @20 -0.037 attention-spread |
| Confidence tagging | OCR `[LOW]` lines still emitted verbatim, marked in details | User sees "OCR 不太确定" rather than nothing | If dropped, user can't tell uncertain text from missing text |
| Multi-round drill-down | Nudge "你已经 zoom_in N 次；如果内容已清楚必须 emit_bubble" | Bounds cycles; prevents runaway loops | Too aggressive = premature emit; too lax = wasted LLM calls |
| Never skip emit_bubble | "**绝对不要**...跳过 emit_bubble / 减少 details 行数 / 发空 content" | Phase 2 re-emphasizes — the workflow narrative + trust-first language still saw ~5-10% empty emits; the hard "never" guard counters it | If removed, empty emits climb |

## G. Phase 2 (2026-07-11) — auto-OCR on every zoom crop

A load-bearing architectural change.  Three pieces:

1. **ToolUseLoop wiring** — every `zoom_in` followUpJpeg auto-runs
   `OcrEngine.recognize(cropBytes)` and attaches the formatted
   crop hint to the next user message.  Silent drop on exception
   (matches round-1 contract), `OCR_ERR_CROP` log tag (distinct
   from round-1's `OCR_ERR` to keep the 40-line ring buffer
   clean).
2. **`OcrResult.formatHint` `isCropHint` flag** — switches the
   header text ("扫过整张图" vs "高保真重扫") and the [LOW]
   follow-up advice ("workflow: 调 zoom_in 重扫" vs
   "**trust 这些字符 verbatim**").  This is the contextual cue
   that lets the model treat crop OCR as high-fidelity verbatim
   instead of ambient low-confidence data.
3. **4-step workflow narrative in TOOL_USE_SYSTEM** — establishes
   the pattern: round-1 OCR → zoom on [LOW] → trust crop OCR →
   emit.  Without this (Phase 1 attempt), the model defaulted to
   hedging ("free information paradox").  The narrative gives the
   model explicit permission to trust the auto-attached OCR.

Effect: composite @100 went from 0.853 (baseline 1568_nudge) →
0.874 (Phase 2a) → 0.868 (Phase 2, read_text removed).  In
noise on composite, but secondary signals massively better
(r2_text_fuzzy +0.148, empty rate 27→19/100, content_avg
+13).  Plan at `~/.claude/plans/lazy-toasting-anchor.md`.  See
memory `eval-phase2a-autoocr-2026-07-11.md` and
`eval-phase2-noreadtext-2026-07-11.md`.

---

## H. Intent↔Action framework (2026-07-10 ~ 2026-07-12)

Two parallel registries — **what the user wants** (IntentDecl) +
**what the app can do** (ActionDef) — wired through the
verifier (§I), the prompt (§K), and the eval scorer's r2_type +
r3 logic.  This section is the cross-cutting config that didn't
exist before Phase A (2026-07-10).  **Adding a new intent =
register in BOTH registries + update three lockstep sites
(§J).**

### H.1 Intent registry — 11 ids

`shared/.../IntentDecl.kt:82-182` `registerDefaultIntents()`.

| Intent id | Family | Label | LLM hint | Source phase |
|---|---|---|---|---|
| `info` | OBSERVE | 信息 | 描述信息（默认）: 物体/文字/数字/概念 | v1.0 (`FALLBACK_ID`) |
| `location` | OBSERVE | 定位 | 定位: 路标/地名/找这家店 | v1.0 |
| `solve` | ACT_ON | 解答 | 解决问题: 翻译/公式/解题 | v1.0 |
| `phone` | ACT_ON | 电话 | 拨号: 手机号/座机/400电话/服务热线 | A (2026-07-10) |
| `real_estate_rental` | ACT_ON | 租房 | 租房: 出租/二手房/房源/中介 | B (2026-07-11) |
| `recruit_hiring` | ACT_ON | 招聘 | 招聘: 招工/求职/兼职/高薪 | B (2026-07-11) |
| `payment_qr` | ACT_ON | 支付 | 支付: 扫一扫/收款码/付款码/转账 | B (2026-07-11) |
| `id_document` | ACT_ON | 证件 | 证件: 身份证/营业执照/车牌 | B (2026-07-11) |
| `warning_safety` | OBSERVE | 警示 | 警示: 请勿/禁止/警告/危险/注意 | G (2026-07-12) |
| `menu_food` | OBSERVE | 菜单 | 菜单: 菜品/套餐/招牌菜/主厨推荐/价格表 | G (2026-07-12) |
| `hours_schedule` | OBSERVE | 营业 | 营业时间: 营业中/HH:MM-HH:MM/营业时段 | G (2026-07-12) |
| `route_to` | OBSERVE | 导航 | 导航: 箭头/方位词/步行 N 米/步行 N 分钟/前方/出口/入口 标记 | **H (2026-07-12)** |

**Family equivalence** (`EvalRunner.kt:413-433`): same family →
1.0; cross-family (OBSERVE↔ACT_ON) → 0.5; empty → 0.0.
v1.3 A2 fix promoted `info↔location` 0.5→1.0; Phase G extends
OBSERVE with warning/menu/hours so they interchange with `info`
1.0.

### H.2 Action registry — 5 defs

`app/.../ActionDecl.kt:158-415` `registerDefaultActions()`.

| Action id | Applicable to | Consent | userPrefKey | Default | Body |
|---|---|---|---|---|---|
| `open_in_maps` | location / route_to / service_institution | no | — | ON | `geo:0,0?q={title}` → maps |
| `dial_number` | phone | yes | `action_dial_number_enabled` | **OFF** | `ACTION_DIAL` via `PhoneExtractor.firstMatch` |
| `scan_to_pay` | payment_qr | yes | `action_scan_to_pay_enabled` | **OFF** | **Toast only — never auto-launch payment** |
| `redact_id` | id_document | yes | `action_redact_id_enabled` | **OFF** | Toast only — chip = audit-trail marker |
| `share` | real_estate_rental / recruit_hiring / warning_safety / menu_food / hours_schedule / service_institution / shopping_promo | no | — | ON | `ACTION_SEND text/plain` share-sheet (capped 600 chars) — unified across 7 OBSERVE/ACT_ON intents; chooser title + fallback vary by `bubble.type` |

**`scan_to_pay` is intentionally Toast-only** — the QR could be
in a screenshot/phishing context; even with consent we route the
user to physically scan a NEW code, never the one in the photo.
**`redact_id`** similarly Toast-only in v1 — Phase B ships the
simplest safe thing first; real redaction (mask middle 6 of 18-
digit 身份证) is Phase C.

### H.3 Applicability filter (2026-07-11)

`ActionDef.applicableIntents: Set<String>` +
`applicableFamilies: Set<IntentFamily>` are OR-semantics; a
bubble matches when `intent ∈ applicableIntents || intent.family
∈ applicableFamilies`.  Both empty = applies to nothing
(misconfiguration guard).  All 5 actions use
`applicableIntents` only; no action uses `applicableFamilies`
yet — reserved for future genuinely family-universal actions.

### H.4 LLM proposedActions override (2026-07-13)

`ActionResolver.suggestIds(bubble)` (ActionDecl.kt:501-521):
when `bubble.llmProposedActions != null` AND non-empty, the
resolver whitelists by id instead of running the applicability
filter.  Empty / null = fall back to applicability.  Acts as a
feature flag: when the prompt isn't updated to ask for
`action_ids`, behavior is unchanged.

### H.5 SettingsStore 3 consent toggles

`app/.../SettingsStore.kt` backs 3 PII consent gates; default
OFF (user must opt-in once in Settings screen).  `share` and
`open_in_maps` have no `userPrefKey` → default ON (share-sheet
/ map picker is its own consent step).  The 3 userPrefKeys:
`action_dial_number_enabled`,
`action_scan_to_pay_enabled`,
`action_redact_id_enabled`.


---

## K. C3 v3 — type→canonical-action 强制映射 in prompt (2026-07-11)

`LlmClient.kt` system prompt's Step 2 paragraph now contains
an explicit type → action mapping table (commit `668ec6f`).
Replaces C2's soft "默认应填" prompt (rejected 2026-07-10 —
single-line nudge wasn't enough; see
[[eval-action-ids-nudge-C2-2026-07-11]]).

The table mirrored §J's `actionFor()` map exactly.  By
construction, the prompt table and the verifier injection
**didn't conflict** (both shipped 2026-07-11):
- Prompt table → model emits the right `action_ids` from start
- Verifier injection → covered cases where the model missed one
- Net effect: r3 (action recall) was monotonic with intent
  coverage

**Historical note (v3.0 inversion, 2026-07-14)**: §K shipped
alongside the verifier (§I, archived) and the actionFor map
(§J, archived) — both of which have since been retired.  The
prompt table is now the **sole** source of truth for the model's
intent→action guidance; the LLM is authoritative for action
selection and no plumbing-side injection runs after emit_bubble.

**Ship verification** @20: `pii_20` 0.8644 → **0.8794 (+0.015)**,
3+ fixtures real-lift, no r2_type regression, D-reject warning
(verbosity distracts OCR) did not recur.  Pair commit
`c27dd7a` for the data(eval) validation.  See
[[eval-c3-v3-ship-2026-07-11]].

---

## L. GT schema dual-read (2026-07-12)

`EvalRunner.kt:520-530` reads `expected_top_intent_type` first,
falls back to `expected_type`:

```kotlin
"expected_type",
scene.optString("expected_top_intent_type", IntentRegistry.FALLBACK_ID),
```

### L.1 Why both fields exist

- **New ground truth** (pii_20 / phone_20 / location_20 /
  real_estate_20 / Phase G 15-fixture) uses
  `expected_top_intent_type` — semantic name reflecting the
  Intent↔Action framework
- **Old RCTW-171 ground truth** still uses `expected_type` —
  pre-framework naming, 8034 images unchanged

Before 2026-07-12 fix: pii20/phone20/location20/real fixtures'
`expected_top_intent_type` was not read by EvalRunner → all
fallback to `FALLBACK_ID="info"` → r2_type systematically 0.5
across the whole suite.  Not a model bug — a measurement bug.

### L.2 Lift provenance

The composite jump `pii_20` 0.8794 → **0.9631** (+0.0837) is
*cumulative* across:
- Phase F (verifier flips; ~+0.005-0.01)
- C3 v3 (prompt table; ~+0.015)
- **GT schema dual-read (this fix)** — image_3285 lifted
  0.5*0.45 → 1.0 alone = +0.225 on that fixture

Per image_3285 attribution: ~80% of the headline lift is the
GT fix (was being scored as `info` instead of
`real_estate_rental`).  RCTW @100 stays at 0.9349 — RCTW uses
`expected_type` so it was always read correctly; no
measurement gain there.

**Future ground truth MUST use `expected_top_intent_type`.**
Old field kept for backward compat with RCTW-171 (don't re-tag
8034 images).  See [[eval-gt-schema-mismatch-2026-07-12]].

---

## M. v3.0 producer/consumer pipeline (2026-07-16)

`CycleManager` (in `app/.../CycleManager.kt`) is the bounded
producer/consumer pipeline that owns concurrent recognition
cycles.  Two independent bounds:

| Bound | Value | Where | What |
|---|---|---|---|
| `CYCLE_QUEUE_DEPTH` | `8` | `UiState` companion | Max queued+in-flight cycles (shutter dim threshold). Backpressure — `startCycle` rejects when saturated. |
| `CYCLE_CONCURRENCY` | `2` | `UiState` companion | Worker pool size. Caps concurrent Anthropic SSE streams; bounds peak device memory; avoids OCR contention. |
| `CYCLES_MAX_TOTAL` | `8` | `UiState` companion | Terminal FIFO cap on the cycles map (oldest COMPLETE/ERRORED/SUPERSEDED evicted). |
| `llmTimeoutMs` | `90_000` ms | `CycleManager` constructor | Per-cycle soft cap on the LLM call. ERRORED on hit; bubble from last good emit preserved. |

`busy: StateFlow<Boolean>` (also in `CycleManager`) is the
derived "spinner should show" signal — `true` iff the focused
job is PENDING or IN_FLIGHT.  Built by `flatMapLatest` over
`_focusedJobId` and the focused job's `status` flow.  The UI
reads `viewModel.busy.collectAsState()` instead of a manually-
managed `UiState.analyzing` field (deleted 2026-07-16).

### M.1 2026-07-16 cleanup

The 2026-07-16 refactor (commits `35c71a5` + `8458906`) closed
two legacy paths and consolidated state:

1. **`runToolUseCycle` + `runRecognitionCycle` deleted** —
   legacy single-cycle path reachable only when
   `pendingCycleId == null` at submit time; production invariant
   (`handlePendingUserInput` always sets it) made the path
   unreachable.  `runRecognitionCycle` had zero callers.
2. **`UiState.analyzing` field deleted** — replaced by the
   derived `busy` flow.  12 manual `_state.copy(analyzing = …)`
   writes collapsed to zero; `enterAnalyzing()` + `isBusy()`
   helpers + the private `analyzing` getter all removed.

Net: **~440 lines removed** across 9 files.  No behavior change
in the live path; legacy paths were verified unreachable before
removal.  See `commit 8458906` for the full diff and rationale.

---

## N. `view_label` rendered-label page (2026-07-19)

The `view_label` action renders `emit_bubble.label_markdown` into a
WebView page (`shared/LabelHtml.kt` template + `app/LabelPageScreen.kt`
overlay) and exports it via `app/LabelPageExporter.kt`.

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_CAPTURE_HEIGHT_PX` | `6000` | `LabelPageExporter.kt:~40` | PNG height ceiling | Bounds transient bitmap (1080×6000 ARGB ≈ 25 MB); real labels are far shorter |
| viewport meta | `width=device-width, initial-scale=1` | `LabelHtml.kt` template | CSS px = dp mapping | Without it the WebView lays out at PHYSICAL px width — 15px type ≈ 5dp on a 3× display (the "页面很小" acceptance bug) |
| `enableSlowWholeDocumentDraw` | on | `LabelPageScreen.kt` (static, before load) | Whole-document layout | Required so the full-height share capture can draw below the fold; without it the WebView tiles content per-viewport and the capture is blank below the fold |

Page layout is full-screen (2026-07-19 acceptance redesign): WebView
fills the space between header and the two-button bar (分享图片 /
分享文字 only — save-to-gallery / save-as-file dropped per user
request).  No storage permission needed anywhere (share goes through
FileProvider).  Capture draws the ON-SCREEN WebView (off-screen
WebViews rasterize unreliably — two blank-PNG shipments), verified
on emulator via the `--ez dev_label_page true` debug hook.
ADR `docs/adr/2026-07-19-view-label-action.md`.

---

## Recently retired (kept here for one cycle, then delete)

| Constant | Was at | Removed | Why |
|---|---|---|---|
| capture timeout = **500 ms** | `app/.../AppViewModel.kt` | **v1.0 (2026-07-12)** | Insufficient for cold camera start + 4096-px JPEG encode. Bumped to 3000ms; see A.7 above. |
| Hardcoded `Size(4096, 4096)` ResolutionSelector | `app/.../MainActivity.kt` | **v1.0 (2026-07-12)** | User flagged: should query actual camera max instead of hardcoding. Now `pickLargestAnalysisSize(provider, selector)` reads `StreamConfigurationMap.getOutputSizes(YUV_420_888)` and picks the largest 4:3. |
| `read_text` tool | `shared/.../ToolImplementations.kt:230-332` | 2026-07-11 (Phase 2) | Auto-OCR on every zoom_in crop covers both [LOW] verification and missed-region re-scan. System prompt now lists 3 tools: zoom_in, compare_text, emit_bubble. See [[eval-phase2a-autoocr-2026-07-11]]. |
| `MAX_OCR_HINT_CHARS = 1500` | `shared/.../ToolUseLoop.kt:828` | 2026-07-08 | Replaced by `MAX_OCR_HINT_LINES`; pure dead code |
| `CROP_OUTPUT_MAX_DIM` (magic 1568) | inline in 2 files | 2026-07-10 | Was duplicated; extracted |
| `QUADRANT_MAX_DIM = 768` | `app/.../FrameAnalyzer.kt:197` | gone | 1-only mode since 2026-07-06; quadrants never produced |
| `QUADRANT_QUALITY = 85` | `app/.../FrameAnalyzer.kt:198` + `shared/.../eval/EvalRunner.kt:267` | gone | Same |
| `encodeQuadrant` (× 2) | `FrameAnalyzer.kt:141` + `EvalRunner.kt:234` | gone | Same |
| `userImageWithQuadrants` | `shared/.../LlmClient.kt:140` | gone | Same |
| `quadrants` field on `CapturedFrame` | `shared/.../CapturedFrame.kt:25` | gone | Same |
| `quadrants` param on `runCycle` | `shared/.../ToolUseLoop.kt:375` | gone | Same |
| `--quadrants` eval flag | `shared/.../eval/EvalMain.kt:217` | gone | Same |
| `EvalOpts.quadrants` + `EvalConfig.quadrants` | `shared/.../eval/EvalMain.kt:190,247` | gone | Same |
| details-cap removal (prompt: "details 不设上限") | `LlmClient.kt:501` | 2026-07-10 EOD (rejected) | Per user "3072不变，完全删除cap" test @20 = 0.871 (-0.037 attention-spread). Cap MUST stay at "5-8 行"; one real win (rctw_15 +0.17, a dense menu fixture) lost in 11 net regressions. See [[eval-maxt3072-2026-07-10]]. Single-fixture wins were the lure; aggregate regression is the truth. |
| v1.3 prompt experiment: "[LOW] 优先级 > 5-8 cap" + "竖排中文阅读顺序" sections (B+C2) | `LlmClient.kt:501` (added then reverted) | **2026-07-10 (rejected, B+C2 reverted)** | Bundled test @20 = 0.9324 (vs A2 alone = 0.9391, -0.007).  Fixture 14 (bilingual parking sign) regressed 0.99 → 0.75 with 0 details + English content — extra prompt jargon confused the model on bilingual fixtures.  The pure-scorer A2 alone (info↔location=1.0) is cleaner: same lift on the 6 floored fixtures, no LLM-side risk.  B+C2 reverted; A2 shipped as v1.3.  See [[eval-v13-prompt-rejected-2026-07-10]]. |

## To-try-next (priority order, with rejection notes)

1. ~~**`MAX_OCR_HINT_LINES = 30` → 20`**~~ — **TESTED 2026-07-10 round 2, REJECTED.** r2_text_fuzzy -0.042 (real signal). The model was using those lines; cutting to 20 truncates text the model was verifying. Reverted.
2. ~~**`MAX_ROUNDS = 30` → 15`**~~ — **TESTED 2026-07-10 round 2, REJECTED.** At least one fixture (rctw_default_10) hit the 15-round cap; 兜底 Bubble fired with empty content → r2_text_fuzzy 0.0, composite -0.257. Tighter cap not safe. Reverted.
3. ~~**r1/r2 weights 0.50/0.50 → 0.40/0.60**~~ — **TESTED 2026-07-10 round 3, REVERTED.** Dropped headline 0.898→0.829 (-0.069) because r1=0.96 (near-ceiling) got less weight. Pure score rebalance, not behavior change. User chose headline tracking over honest r2 surfacing.
4. ~~**read_text default source → "original"**~~ — REMOVED 2026-07-11 (Phase 2). read_text tool itself removed; auto-OCR on every zoom_in crop is now sufficient.
5. ~~**`MAX_FULL_DIM = 4096` → 2048`**~~ — **TESTED 2026-07-10 round 3, KEPT — then REVERSED on 2026-07-12.** User overrode the 2026-07-10 conclusion after Phase 2 shipped: under auto-OCR + workflow prompt, 4096 source pixels now feed crop OCR more raw chars which the model "trust verbatim" (Step 3). With `MAX_TOKENS 1024→2048` (paired fix), composite @20 went 0.850 → **0.889** (+0.039, 9W/3L/8T). The 2048 setting is no longer in prod — see row A.4 above for current rationale.
6. ~~**Phase 1: auto-OCR wiring only, no workflow prompt**~~ — REJECTED 2026-07-11. Composite flat, r2_text_fuzzy -0.17. "Free information paradox" — auto-attached OCR treated as low-confidence vs requested OCR. Phase 2a fixed by adding 4-step workflow narrative.
7. ~~**Phase 2b: bump `LOW_CONFIDENCE_THRESHOLD` 0.5 → 0.7**~~ — **TESTED 2026-07-11 @20, REJECTED.** Hypothesis was inverted — the threshold controls the *upper bound of [LOW]* (chars with `conf < THRESHOLD` are tagged). Raising 0.5→0.7 flips the 0.5-0.7 conf range from high-fidelity to [LOW], giving the model MORE reason to hedge, not less. Result with OCR-on @20: composite 0.854→0.840 (-0.014), r2_text_fuzzy 0.734→0.552 (-0.182, real), 7/20 empty vs 0/20 baseline. 0.5 stays. Lesson: the right direction to *reduce* [LOW] count is lowering the threshold, not raising.
8. ~~**Option D: `MAX_DIM` 3200→4096 + `CROP_OUTPUT_MAX_DIM` 3200→4096**~~ — **TESTED 2026-07-12 @20, REVERTED.** "摸一摸天花板" — user asked if 4096 (model max) unlocks a higher ceiling than 3200. Result: composite 0.902 → **0.885** (-0.017), r2_text_fuzzy 0.821 → 0.766 (**-0.055, real signal**), empty 0/20 → 1/20 (rctw_15 went empty again). The **attention-spread failure pattern**, identical to 2026-07-10 1568 regression: pushing image dim to model max makes the model lose focus on text regions. **3200 is the sweet spot.** Two independent confirmations (1568 once, 4096 once) make this a stable conclusion, not noise.
9. ~~**4×4 spatial grid in round-1 OCR hint**~~ — **TESTED 2026-07-12, REVERTED before v1.1 tag.** Hypothesis was that an ASCII 4×4 grid (cells = OCR block count per quadrant) would help the model see spatial layout without parsing 30 line entries. Result @20 with strong-nudge `extract_text` prompt: composite 0.888 blind (no signal), 0.890 with OCR (in noise vs 0.887), but r2_text_fuzzy -0.137 (real signal — the grid took token space away from verbatim copy). The per-line bbox already gives the model the spatial info it needs; the grid added nothing. Code removed; the `computeSpatialGrid` helper was deleted from `OcrEngine.kt` rather than left as dead code.
10. **`extract_text` without explicit Step 2 routing rule** — also REJECTED (same TEST, @20). Without "Step 2 默认 extract_text, only zoom_in for new pixels" the model picked `extract_text` **0/20 fixtures** in the v1.1 first attempt (the tool was registered but never used). With the routing rule, adoption jumped to 5-7/20 (25-35%) across 3 runs. **Lesson**: adding a new tool to the registry isn't enough — the prompt must explicitly say when to use it vs the existing alternative.