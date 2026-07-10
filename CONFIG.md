# Configuration Inventory

> Single source of truth for every tunable knob in the IntentCam
> pipeline.  When you change a value, update the table + run an
> eval to confirm.

**Last reviewed:** 2026-07-12 v1.0.0 release (cold-start camera
fix + DetailScreen UX overhaul + debug-log enhancements).
On-device pipeline now actually delivers full sensor-resolution
frames (was silently 640×480 — see §A.6).  Architecture stable;
next round of experiments will be on prompt + tool routing.

**Baseline chain:** 0.820 (over-hedged @20) → 0.838 (softened
prompt @20) → 0.841 (1568 + nudge @20) → 0.853 (1568 + nudge
@100, conclusive) → 0.898 (@20, `MAX_FULL_DIM 4096→2048`) → 0.874
(Phase 2a: auto-OCR + workflow prompt @100) → 0.868 (Phase 2:
`read_text` retired @100) → 0.889 (@20, 4096 + `MAX_TOKENS 1024→2048`;
empty-bubble fix) → 0.902 (@20, 3200+4096 strict Step 2) →
0.891 (@100, 3200+4096 strict Step 2, separate r2_text) →
**0.903 (@100, 3200+4096 strict Step 2 + union r2_text;
current prod-mirror ceiling)**.  All run on the same OCR-enabled
Kotlin `:shared:eval`; the deltas are architectural, not noise.

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
| `MAX_OCR_HINT_LINES` | 30 | `shared/.../OcrEngine.kt:92` | Top-N OCR blocks injected into round-1 user message | Keeps prompt bounded (~2 KB); "details: 5-8" leaves headroom. Tested 20 (2026-07-10 round 2): regressed r2_text_fuzzy -0.042, reverted |
| `MAX_CROP_OCR_HINT_LINES` | 10 | `shared/.../OcrEngine.kt:103` | Top-N OCR blocks per zoom_in crop hint | **Phase 2 (2026-07-11)**. Crops are smaller regions; 10 lines covers the dense case without blowing the multi-zoom token budget (3 zooms × ~1 KB = 3 KB vs 3 × ~2 KB at 30) |
| `LOW_CONFIDENCE_THRESHOLD` | 0.5 | `shared/.../OcrEngine.kt:81` | conf<0.5 → mark `[LOW]` in hint | Standard; below 0.5 OCR often misreads |
| `MAX_BITMAP_DIM` | **4096** | `app/.../AndroidOcrEngine.kt:70` | Decoded bitmap cap for HMS ML Kit OCR | **2026-07-12 shift: 1920→4096** alongside MAX_DIM=3200. Round-1 OCR now reads the full 4096-px fullRes directly, so [LOW] rate drops and the model has fewer reasons to zoom_in just to verify OCR. Within HMS's reported working range. |
| `PRIMARY_LANGUAGE` | "zh" | `app/.../AndroidOcrEngine.kt:64` | HMS ML Kit OCR language | Current test set is RCTW (Chinese) |
| OCR endpoint | cn-north-4 | `profiling/ocr_huaweicloud.py:84` | Huawei Cloud OCR region | Fixed by user's project region. The Kotlin side `JvmHuaweiCloudOcrEngine` (line 59) shells out to this Python helper. |
| `detect_direction` | true | `profiling/ocr_huaweicloud_runner.py:44` (imports OcrRegion) | Rotation detection | Phone photos can be sideways; true is the right default |
| `language` | "zh" | `profiling/ocr_huaweicloud_runner.py` (calls into ocr_huaweicloud.py) | Cloud OCR language | Mirrors HMS; matches test set |
| Per-call subprocess | ~2-3s | runtime | OCR latency on JVM eval | SDK init + HTTPS roundtrip; 20 fixtures × 3s ≈ 60s overhead |

## C. LLM pipeline

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_TOKENS` | 2048 | `shared/.../LlmClient.kt:342` | Output cap per round | Bumped 1024→2048 on 2026-07-12. Same fix pattern as 256→1024 (2026-07-07): under Phase 2 (richer round-1 OCR + auto crop OCR + per-row details[] with bbox), 5-10 detail rows + thinking text exceeded 1024 BPE, `stop_reason=max_tokens` cut emit_bubble JSON mid-`details[]` → empty content. With `MAX_FULL_DIM=4096` (more OCR chars), emit grew further. 2048 covers worst case with headroom. Watch for new `max_tokens` stops — if any fixture shows them, next bump is 3072. |
| `REQUEST_TEMPERATURE` | 0.0 | `shared/.../LlmClient.kt:336` | Sampling temperature | Lock at 0 for deterministic intent classification |
| `TOTAL_TIMEOUT_MS` | 90_000 | `shared/.../LlmClient.kt:358` | Per-round LLM timeout | Bumped 60s→90s on 2026-07-12. 60s was right for `MAX_TOKENS=1024` (~38s worst case); with `MAX_TOKENS=2048`, worst-case read on overload approaches 73s. 90s covers that with margin. |
| `connectTimeout` | 15s | `shared/.../LlmClient.kt:41` | HTTP connect | Standard |
| `writeTimeout` | 30s | `shared/.../LlmClient.kt:43` | HTTP write | Standard |
| `readTimeout` / `callTimeout` | 0 (infinite) | `shared/.../LlmClient.kt:42,44` | HTTP read/call | SSE streaming requires infinite; real hangs caught by `TOTAL_TIMEOUT_MS` |
| `MAX_ROUNDS` | 30 | `shared/.../ToolUseLoop.kt:874` | Per-cycle iteration cap | Allows iterative drill-down; most fixtures converge in 5-10. Tested 15 (2026-07-10): at least one fixture hit cap, 兜底 empty → -0.257 single fixture. Reverted. |
| `BUBBLE_MAX` | 4 | `shared/.../Models.kt:80` | Bubbles kept in UI | Reasonable history depth |
| `DEBUG_LOG_MAX` | 40 | `shared/.../Models.kt:82` | Debug log cap | Reasonable debug history |
| `DEFAULT_TOKEN` | "REPLACE_AT_RUNTIME" | `shared/.../Models.kt:135` | Token placeholder at build time | Real builds need runtime Settings entry or env-var injection (TODO) |
| `DEFAULT_MODEL` | "MiniMax-M3" | `shared/.../Models.kt:136` | LLM model name | Sandbox default per env; user-overridable in Settings |
| capture timeout | **3000 ms** | `app/.../AppViewModel.kt:212-220` | How long to wait for the analyzer's next frame | **v1.0 bumped 500ms→3000ms.** Two reasons: (1) Cold start: even with pre-warming (see A.6), CameraX `bindToLifecycle` on first launch takes 200-800ms — `analyze()` doesn't fire at all until binding completes, so first shutter tap needs >500ms headroom. (2) Larger encodes: `MAX_FULL_DIM=4096` JPEG q95 takes ~200-400ms on its own. 3s covers both, with no impact on warm path (typical wait 50-80ms). The CAP log line shows the actual `waited=` value so cold vs warm is visible at a glance. |

## D. Tool behavior

| Parameter | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| zoom_in default source | "original" | `shared/.../ToolImplementations.kt:93` | First zoom crops 2048-px fullRes | Pre-2026-07-10 was "last" — broke because 50% of 768 = 384 < round-1 view; default="original" makes zoom always a magnifier |
| zoom_in crop OCR auto | always on (prod) | `shared/.../ToolUseLoop.kt:699` | Every followUpJpeg auto-OCR | **Phase 2 (2026-07-11)**. See §G workflow narrative. Crop OCR result attached as a text content block alongside the image. |
| compare_text cache | round-1 OCR only | `shared/.../ToolImplementations.kt:179` (passes `ctx.ocrCache`) | diff scope | Crop OCR is not in `ocrCache`; only round-1 result. If model needs crop-level diff, future extension (out of scope). |
| Region w/h minimum | 0.05f | (see A. above) | Same as crop min | Same |
| details cap (prompt) | "5-8 行" | `shared/.../LlmClient.kt:439` | Hint to model on details array size | Prevents over-long answers; 8 covers most scenes |
| `cropOcrCap` | 0 (unlimited) | `shared/.../ToolUseLoop.kt:409,418` | Per-cycle cap on followUpJpeg OCRs | **Phase 2 (2026-07-11)**. Fast-iter knob: 0 = unlimited (prod), 1 = round-1 + first crop only (~2-min/20-fixture pace). Set via `--crop-ocr-cap N` eval arg. |

## E. Eval scoring + CLI

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `--resize` | **3200** | `shared/.../eval/EvalMain.kt:219` | eval thumbnail dim (mirrors `MAX_DIM`) | **2026-07-12 option C ship: 1568→3200.** 1:1 mirror of prod. **Option D (4096) tested and REVERTED.** |
| `--quality` | 90 | `shared/.../eval/EvalMain.kt:220` | eval thumbnail quality (mirrors `QUALITY`) | 1:1 mirror of prod |
| `--limit` | 20 | `shared/.../eval/EvalMain.kt:199` | Default fixtures per run | Per user rule 2026-07-08: 20 for iteration, `--limit 0` for conclusive |
| `--crop-ocr-cap` | 0 | `shared/.../eval/EvalMain.kt:213` | Max followUpJpeg OCRs per cycle | **Phase 2 (2026-07-11)**. Default 0=unlimited; N>0 caps crop OCR for fast iter |
| `--debug-fixtures` | (none) | `shared/.../eval/EvalMain.kt` (2026-07-12) | Comma-separated fixture ids whose ToolUseLoop logs forward to stderr | **2026-07-12**. Diagnostic mode: orchestrator hot-loop stays silent for the rest, named fixtures print full debug trace. Use during targeted reruns on regressions. |
| `--fixtures` | (none) | `shared/.../eval/EvalMain.kt` (2026-07-12) | Comma-separated fixture ids to run, in GT order | **2026-07-12**. Restricts the run to a curated id set — iterate on a small subset without rebuilding the whole 20- or 100-fixture run. Useful alongside `--debug-fixtures`. |
| `skipReconScore` | 0.85 (text fixtures), 1.0 (no-text) | `shared/.../eval/EvalRunner.kt:275` | r1 score when model skips recon | Bumped 0.5→0.85 in endcloud era (2026-07-08); OCR hint makes skip-recon legitimate |
| `CHAR_OVERLAP_THRESHOLD` | 0.67 | `shared/.../eval/EvalRunner.kt:240` | Fuzzy-match char-overlap fallback | Mirrors Python aligned4; below 0.67 = no hit |
| **r2_text haystack (union)** | **content + " " + all details values** | `shared/.../eval/EvalRunner.kt:350-355` | Both textScore (GT keywords) and detailScore (GT detail values) match against the UNION of content + details | **2026-07-12 ship: union-scoring r2_text.** Old: textScore checked content alone, detailScore checked details alone, average → empty content floored r2_text even when details had the verbatim text. Union makes both checks pool content+details, so a hit anywhere in the model output registers. @100: composite 0.891 → 0.903 (+0.012, re-score predicted +0.019), empty 7/100 → 2/100. |
| r1/r2 composite weights | 0.50 / 0.50 | `shared/.../eval/EvalRunner.kt:103` | Composite formula | 2026-07-10 round 3: tested 0.40/0.60 — dropped headline 0.898→0.829 (-0.069) because r1=0.96 (near-ceiling) got less weight. Pure score rebalance, not behavior change. User reverted 2026-07-10: headline tracking > honest r2 surfacing. |
| r2 text/type weights | 0.50 / 0.50 | `shared/.../eval/EvalRunner.kt:421` | r2 internal split | Same |
| type three-way partial | 1.0 / 0.5 / 0.0 | `shared/.../eval/EvalRunner.kt:413-419` | Type score (right / valid-wrong / empty) | 2026-07-07 fix; 0.5 saves the 9/100 store/restaurant fixtures where model picked valid-but-not-GT-locked "info" |
| text hybrid scoring | fuzzy ∪ char-overlap≥0.67 | `shared/.../eval/EvalRunner.kt:520-531` | Per-keyword match strategy | Catches "建国路 100号" vs "建国路100号" splits the strict scorer misses |

## F. System prompt behavior knobs (qualitative)

These are paragraph-level behaviors in `TOOL_USE_SYSTEM`
(`shared/.../LlmClient.kt:395-460`), not numeric constants.  The
prompt is a 4-step **workflow narrative** (Step 1 → 4, lines
400-415) + tool descriptions + content/details/anti-hallucination
guidance.  Each is a tradeoff:

| Behavior | Current guidance | Why | Risk of changing |
|---|---|---|---|
| OCR = "first opinion" | OCR hint is primary verbatim source; LLM can also use own vision for OCR-missed text | Maximizes text coverage; balanced against hallucination | If too strict, model over-hedges (empty emit); if too loose, OCR errors propagate |
| Step 2 strength | **Option C strict (2026-07-12): [LOW] / 漏扫 / 缩略图看不清 → 必须 zoom_in** | "不要因为缩略图看着像就跳过" — 3200 thumbnail 让人产生"看清了"的错觉，要 hard-rule 拉回 [LOW]→zoom_in 路径 | The 3200+4096 architecture needs the strict version; the "soft" version (Phase 1-style "必要时才 zoom") caused free-information-paradox and the rctw_14/19/15 empty regressions |
| Workflow narrative | Step 1 (read OCR) → Step 2 ([LOW]→zoom_in) → Step 3 (trust crop OCR) → Step 4 (emit_bubble) | **Phase 2 (2026-07-11)**. The load-bearing piece. Without the explicit "trust crop OCR" Step 3, auto-attached OCR was treated as low-confidence (Phase 1 attempt rejected, r2_text_fuzzy -0.17). | If Step 3 wording weakens, model hedges crop OCR. |
| Crop-frame bbox caveat | "zoom crop hint 的 bbox 是 crop frame — 要在 details[].bbox 里复用，offset 加回传给 zoom_in 的 (x, y)" | Crop bboxes are normalized in the crop's [0,1], not the original photo's | If model skips the offset, details table can't highlight the row in original frame |
| Anti-hallucination | "OCR 字符 verbatim 引用，但绝不发明" + '?' placeholder for hand-written/unreadable | User-safety: wrong text > no text > fabricated text | Same |
| details cap | "5-8 行" for scenes with >8 text regions | Bounds answer length under 1024-token cap | Loose guidance; model often emits 1-3 details instead of 8 |
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

## To-try-next (priority order, with rejection notes)

1. ~~**`MAX_OCR_HINT_LINES = 30` → 20`**~~ — **TESTED 2026-07-10 round 2, REJECTED.** r2_text_fuzzy -0.042 (real signal). The model was using those lines; cutting to 20 truncates text the model was verifying. Reverted.
2. ~~**`MAX_ROUNDS = 30` → 15`**~~ — **TESTED 2026-07-10 round 2, REJECTED.** At least one fixture (rctw_default_10) hit the 15-round cap; 兜底 Bubble fired with empty content → r2_text_fuzzy 0.0, composite -0.257. Tighter cap not safe. Reverted.
3. ~~**r1/r2 weights 0.50/0.50 → 0.40/0.60**~~ — **TESTED 2026-07-10 round 3, REVERTED.** Dropped headline 0.898→0.829 (-0.069) because r1=0.96 (near-ceiling) got less weight. Pure score rebalance, not behavior change. User chose headline tracking over honest r2 surfacing.
4. ~~**read_text default source → "original"**~~ — REMOVED 2026-07-11 (Phase 2). read_text tool itself removed; auto-OCR on every zoom_in crop is now sufficient.
5. ~~**`MAX_FULL_DIM = 4096` → 2048`**~~ — **TESTED 2026-07-10 round 3, KEPT — then REVERSED on 2026-07-12.** User overrode the 2026-07-10 conclusion after Phase 2 shipped: under auto-OCR + workflow prompt, 4096 source pixels now feed crop OCR more raw chars which the model "trust verbatim" (Step 3). With `MAX_TOKENS 1024→2048` (paired fix), composite @20 went 0.850 → **0.889** (+0.039, 9W/3L/8T). The 2048 setting is no longer in prod — see row A.4 above for current rationale.
6. ~~**Phase 1: auto-OCR wiring only, no workflow prompt**~~ — REJECTED 2026-07-11. Composite flat, r2_text_fuzzy -0.17. "Free information paradox" — auto-attached OCR treated as low-confidence vs requested OCR. Phase 2a fixed by adding 4-step workflow narrative.
7. ~~**Phase 2b: bump `LOW_CONFIDENCE_THRESHOLD` 0.5 → 0.7**~~ — **TESTED 2026-07-11 @20, REJECTED.** Hypothesis was inverted — the threshold controls the *upper bound of [LOW]* (chars with `conf < THRESHOLD` are tagged). Raising 0.5→0.7 flips the 0.5-0.7 conf range from high-fidelity to [LOW], giving the model MORE reason to hedge, not less. Result with OCR-on @20: composite 0.854→0.840 (-0.014), r2_text_fuzzy 0.734→0.552 (-0.182, real), 7/20 empty vs 0/20 baseline. 0.5 stays. Lesson: the right direction to *reduce* [LOW] count is lowering the threshold, not raising.
8. ~~**Option D: `MAX_DIM` 3200→4096 + `CROP_OUTPUT_MAX_DIM` 3200→4096**~~ — **TESTED 2026-07-12 @20, REVERTED.** "摸一摸天花板" — user asked if 4096 (model max) unlocks a higher ceiling than 3200. Result: composite 0.902 → **0.885** (-0.017), r2_text_fuzzy 0.821 → 0.766 (**-0.055, real signal**), empty 0/20 → 1/20 (rctw_15 went empty again). The **attention-spread failure pattern**, identical to 2026-07-10 1568 regression: pushing image dim to model max makes the model lose focus on text regions. **3200 is the sweet spot.** Two independent confirmations (1568 once, 4096 once) make this a stable conclusion, not noise.