# Configuration Inventory

> Single source of truth for every tunable knob in the IntentCam
> pipeline.  When you change a value, update the table + run an
> eval to confirm.

**Last reviewed:** 2026-07-11 (post Phase 2 ship;
current prod-mirror ceiling @100 = **0.868**).

**Baseline chain:** 0.820 (over-hedged @20) → 0.838 (softened
prompt @20) → 0.841 (1568 + nudge @20) → 0.853 (1568 + nudge
@100, conclusive) → 0.898 (`MAX_FULL_DIM 4096→2048`) → 0.874
(Phase 2a: auto-OCR + workflow prompt) → **0.868 (Phase 2:
`read_text` retired; ship)**.  All run on the same OCR-enabled
Kotlin `:shared:eval`; the deltas are architectural, not noise.

---

## A. Image pipeline

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_DIM` | 1568 | `app/.../FrameAnalyzer.kt:159` | Round-1 thumbnail max-dim for LLM | Bumped 768→1568 on 2026-07-10 round 3. Original 2026-07-10 round 1 1568 test (no-OCR, zoom_in=last) regressed -0.050, but with OCR hint + zoom_in=original + "thumbnail ≠ 原图，密集文字必须 zoom_in" nudge, 1568 = 0.841 vs 768 = 0.838 (composite flat, secondary signals strictly better). Also matches Claude vision's native internal grid (no internal downsample) |
| `QUALITY` | 90 | `app/.../FrameAnalyzer.kt:160` | Round-1 thumbnail JPEG quality | q90 vs q80 +0.015-0.017 reproducible |
| `MAX_FULL_DIM` | 2048 | `app/.../FrameAnalyzer.kt:167` | Full-res JPEG cap (source for `zoom_in=original`) | Bumped 4096→2048 on 2026-07-10 round 3. Counter-intuitive win: with 1568 thumb + 2048 fullRes, zoom_in crops are more focused (2× linear / 4× area magnifier still holds; 4096 source was over-resolving 1568-output crops). r2_text strict +0.042, r2_type +0.075 in eval @20 (12W/8L/0T). Eval-side mirrored. |
| `FULL_QUALITY` | 95 | `app/.../FrameAnalyzer.kt:168` | Full-res JPEG quality | Visually lossless → all subsequent crops also lossless |
| `CROP_OUTPUT_MAX_DIM` | 1568 | `shared/.../ImageOps.kt:70` | Max-dim cap on `cropJpegRegion` output (zoom_in) | Claude vision encoder's internal grid max; going higher gets downscaled anyway. Also: ≥ 768 thumbnail (2× linear / 4× area) so zoom_in is always a real magnifier |
| `DEFAULT_CROP_QUALITY` | 90 | `shared/.../ImageOps.kt:60` | Zoom crop output quality | q90 keeps edge detail for small text; q80 smudged at 1568-cap re-encode |
| Region min w/h | 0.05f | `shared/.../ToolImplementations.kt:81,82` | Min crop region (normalized) | Below 5% the crop has too little info to be useful |

## B. OCR pipeline

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_OCR_HINT_LINES` | 30 | `shared/.../OcrEngine.kt:92` | Top-N OCR blocks injected into round-1 user message | Keeps prompt bounded (~2 KB); "details: 5-8" leaves headroom. Tested 20 (2026-07-10 round 2): regressed r2_text_fuzzy -0.042, reverted |
| `MAX_CROP_OCR_HINT_LINES` | 10 | `shared/.../OcrEngine.kt:103` | Top-N OCR blocks per zoom_in crop hint | **Phase 2 (2026-07-11)**. Crops are smaller regions; 10 lines covers the dense case without blowing the multi-zoom token budget (3 zooms × ~1 KB = 3 KB vs 3 × ~2 KB at 30) |
| `LOW_CONFIDENCE_THRESHOLD` | 0.5 | `shared/.../OcrEngine.kt:81` | conf<0.5 → mark `[LOW]` in hint | Standard; below 0.5 OCR often misreads |
| `MAX_BITMAP_DIM` | 1920 | `app/.../AndroidOcrEngine.kt:70` | Decoded bitmap cap for HMS ML Kit OCR | Standard for ML Kit; larger gets downscaled anyway |
| `PRIMARY_LANGUAGE` | "zh" | `app/.../AndroidOcrEngine.kt:64` | HMS ML Kit OCR language | Current test set is RCTW (Chinese) |
| OCR endpoint | cn-north-4 | `profiling/ocr_huaweicloud.py:84` | Huawei Cloud OCR region | Fixed by user's project region. The Kotlin side `JvmHuaweiCloudOcrEngine` (line 59) shells out to this Python helper. |
| `detect_direction` | true | `profiling/ocr_huaweicloud_runner.py:44` (imports OcrRegion) | Rotation detection | Phone photos can be sideways; true is the right default |
| `language` | "zh" | `profiling/ocr_huaweicloud_runner.py` (calls into ocr_huaweicloud.py) | Cloud OCR language | Mirrors HMS; matches test set |
| Per-call subprocess | ~2-3s | runtime | OCR latency on JVM eval | SDK init + HTTPS roundtrip; 20 fixtures × 3s ≈ 60s overhead |

## C. LLM pipeline

| Constant | Value | File:line | What it controls | Why this value |
|---|---|---|---|---|
| `MAX_TOKENS` | 1024 | `shared/.../LlmClient.kt:333` | Output cap per round | 256 was truncating dense emit_bubble; 1024 covers largest answers with headroom |
| `REQUEST_TEMPERATURE` | 0.0 | `shared/.../LlmClient.kt:336` | Sampling temperature | Lock at 0 for deterministic intent classification |
| `TOTAL_TIMEOUT_MS` | 60_000 | `shared/.../LlmClient.kt:348` | Per-round LLM timeout | 20s was hitting 12/100 dense fixtures; 60s covers worst case |
| `connectTimeout` | 15s | `shared/.../LlmClient.kt:41` | HTTP connect | Standard |
| `writeTimeout` | 30s | `shared/.../LlmClient.kt:43` | HTTP write | Standard |
| `readTimeout` / `callTimeout` | 0 (infinite) | `shared/.../LlmClient.kt:42,44` | HTTP read/call | SSE streaming requires infinite; real hangs caught by `TOTAL_TIMEOUT_MS` |
| `MAX_ROUNDS` | 30 | `shared/.../ToolUseLoop.kt:874` | Per-cycle iteration cap | Allows iterative drill-down; most fixtures converge in 5-10. Tested 15 (2026-07-10): at least one fixture hit cap, 兜底 empty → -0.257 single fixture. Reverted. |
| `BUBBLE_MAX` | 4 | `shared/.../Models.kt:80` | Bubbles kept in UI | Reasonable history depth |
| `DEBUG_LOG_MAX` | 40 | `shared/.../Models.kt:82` | Debug log cap | Reasonable debug history |
| `DEFAULT_TOKEN` | "REPLACE_AT_RUNTIME" | `shared/.../Models.kt:135` | Token placeholder at build time | Real builds need runtime Settings entry or env-var injection (TODO) |
| `DEFAULT_MODEL` | "MiniMax-M3" | `shared/.../Models.kt:136` | LLM model name | Sandbox default per env; user-overridable in Settings |
| capture timeout | 500 ms | `app/.../AppViewModel.kt:192` | How long to wait for the analyzer's next frame | Shutter tap → 500 ms window; "[CAP] 500ms 内没拿到帧" if missed |

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
| `--resize` | 1568 | `shared/.../eval/EvalMain.kt:207` | eval thumbnail dim (mirrors `MAX_DIM`) | 1:1 mirror of prod |
| `--quality` | 90 | `shared/.../eval/EvalMain.kt:208` | eval thumbnail quality (mirrors `QUALITY`) | 1:1 mirror of prod |
| `--limit` | 20 | `shared/.../eval/EvalMain.kt:199` | Default fixtures per run | Per user rule 2026-07-08: 20 for iteration, `--limit 0` for conclusive |
| `--crop-ocr-cap` | 0 | `shared/.../eval/EvalMain.kt:213` | Max followUpJpeg OCRs per cycle | **Phase 2 (2026-07-11)**. Default 0=unlimited; N>0 caps crop OCR for fast iter |
| `skipReconScore` | 0.85 (text fixtures), 1.0 (no-text) | `shared/.../eval/EvalRunner.kt:275` | r1 score when model skips recon | Bumped 0.5→0.85 in endcloud era (2026-07-08); OCR hint makes skip-recon legitimate |
| `CHAR_OVERLAP_THRESHOLD` | 0.67 | `shared/.../eval/EvalRunner.kt:240` | Fuzzy-match char-overlap fallback | Mirrors Python aligned4; below 0.67 = no hit |
| r1/r2 composite weights | 0.50 / 0.50 | `shared/.../eval/EvalRunner.kt:103` | Composite formula | 2026-07-10 round 3: tested 0.40/0.60 — dropped headline 0.898→0.829 (-0.069) because r1=0.96 (near-ceiling) got less weight. Pure score rebalance, not behavior change. User reverted 2026-07-10: headline tracking > honest r2 surfacing. |
| r2 text/type weights | 0.50 / 0.50 | `shared/.../eval/EvalRunner.kt:387` | r2 internal split | Same |
| type three-way partial | 1.0 / 0.5 / 0.0 | `shared/.../eval/EvalRunner.kt:380-385` | Type score (right / valid-wrong / empty) | 2026-07-07 fix; 0.5 saves the 9/100 store/restaurant fixtures where model picked valid-but-not-GT-locked "info" |
| text hybrid scoring | fuzzy ∪ char-overlap≥0.67 | `shared/.../eval/EvalRunner.kt:490-501` | Per-keyword match strategy | Catches "建国路 100号" vs "建国路100号" splits the strict scorer misses |

## F. System prompt behavior knobs (qualitative)

These are paragraph-level behaviors in `TOOL_USE_SYSTEM`
(`shared/.../LlmClient.kt:395-460`), not numeric constants.  The
prompt is a 4-step **workflow narrative** (Step 1 → 4, lines
400-415) + tool descriptions + content/details/anti-hallucination
guidance.  Each is a tradeoff:

| Behavior | Current guidance | Why | Risk of changing |
|---|---|---|---|
| OCR = "first opinion" | OCR hint is primary verbatim source; LLM can also use own vision for OCR-missed text | Maximizes text coverage; balanced against hallucination | If too strict, model over-hedges (empty emit); if too loose, OCR errors propagate |
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
5. ~~**`MAX_FULL_DIM = 4096` → 2048`**~~ — **TESTED 2026-07-10 round 3, KEPT.** Composite 0.841 → 0.898 with old weights (+0.057, 12W/8L/0T). r2_text strict +0.042, r2_type +0.075. Model is *better* with smaller fullRes — crops are more focused, less context dilution. Production code updated; eval-side mirrored.
6. ~~**Phase 1: auto-OCR wiring only, no workflow prompt**~~ — REJECTED 2026-07-11. Composite flat, r2_text_fuzzy -0.17. "Free information paradox" — auto-attached OCR treated as low-confidence vs requested OCR. Phase 2a fixed by adding 4-step workflow narrative.
7. **Phase 2b: bump `LOW_CONFIDENCE_THRESHOLD` 0.5 → 0.7** — Likely reduces the 5-7 fully-empty emits in Phase 2 (rctw_default_10, 13, 14, 79 all show det=0). Higher threshold = fewer `[LOW]` tags in the crop hint = less reason for the model to hedge. **Risk**: changes what counts as high-fidelity — at conf 0.7+, the model might trust characters that the engine is genuinely uncertain about. Ship requires @100 verification.