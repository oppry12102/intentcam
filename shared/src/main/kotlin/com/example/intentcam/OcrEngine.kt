package com.example.intentcam

/**
 * Strategy holder for on-device OCR.  Lives in `:shared` so the
 * auto-OCR path (round-1 pre-pass + per-zoom_in crop) can call it
 * without an Android dependency.
 *
 * The implementation strategy is set once at startup:
 *   - Android app: `MainActivity.onCreate` installs an HMS ML Kit-backed
 *     recognizer that decodes the JPEG bytes to a Bitmap internally
 *     and returns text + 4-point coordinates + confidence per block.
 *   - JVM eval:    no implementation installed by default.  When the
 *     HUAWEICLOUD_SDK_AK/SK/PROJECT_ID env vars are set, [eval] installs
 *     a cloud OCR backend that mirrors HMS ML Kit semantics.  Without
 *     it, [OcrResult.EMPTY] is returned (no OCR hint, no auto-OCR on
 *     crops).
 *
 * If `impl` is null, `recognize` returns [OcrResult.EMPTY] — fail-closed.
 */
object OcrEngine {

    /** One OCR backend.  Returns the structured recognition result. */
    fun interface Impl {
        suspend fun recognize(jpegBytes: ByteArray): OcrResult
    }

    @JvmStatic
    var impl: Impl? = null

    suspend fun recognize(jpegBytes: ByteArray): OcrResult =
        impl?.recognize(jpegBytes) ?: OcrResult.EMPTY
}

/**
 * One recognized text block (a word, line, or paragraph depending on
 * the underlying engine).  HMS ML Kit's [com.huawei.hms.mlsdk.text.MLText.Base]
 * exposes both a bounding `Rect` AND 4 `Point` corners; we keep the
 * 4-point form here because it survives rotation / non-axis-aligned
 * crops (zoom_in often produces these).
 *
 * [corners] is ordered top-left → top-right → bottom-right →
 * bottom-left (the convention HMS ML Kit returns them in via
 * [com.huawei.hms.mlsdk.text.MLText.Base.getCornerPoints]).
 */
data class OcrBlock(
    val text: String,
    val corners: List<OcrPoint>,
    /** 0.0 .. 1.0 — HMS ML Kit reports no per-block score, so we
     *  synthesize 1.0 when the engine doesn't.  The Chinese model
     *  likewise doesn't expose confidence; the API surface here is
     *  the one the LLM tooling expects, not what the engine emits. */
    val confidence: Float,
)

/** A normalized [0, 1] (x, y) point in the input image's coordinate
 *  system.  Conversion from pixel coordinates is the impl's job so
 *  the tool body can compare blocks regardless of the input image
 *  size. */
data class OcrPoint(val x: Float, val y: Float)

/**
 * Aggregated OCR result for one input image.  [fullText] is the
 * blocks joined with spaces (single-line behavior).  [blocks] is the
 * structured per-block list with positions + confidences — used by
 * the auto-OCR hint formatting ([formatHint]) to return spatial
 * detail.
 */
data class OcrResult(
    val blocks: List<OcrBlock>,
    val fullText: String,
) {
    companion object {
        val EMPTY = OcrResult(blocks = emptyList(), fullText = "")

        /** Below this confidence, an OCR block is marked [LOW] in
         *  the round-1 hint so the LLM knows the chars are
         *  uncertain.  Tuned so high-confidence (>=0.5) blocks are
         *  treated as ground truth verbatim; below that the LLM
         *  is steered to either zoom_in for verification or skip
         *  the line entirely ("宁可不写也别编").
         *
         *  Phase 2b (2026-07-11): tested 0.5→0.7.  REJECTED @20.
         *  Hypothesis was inverted — raising the threshold marks
         *  MORE lines [LOW] (the 0.5-0.7 range flips from
         *  high-fidelity to [LOW]).  Result with OCR-on: composite
         *  0.854→0.840 (-0.014), r2_text_fuzzy 0.734→0.552
         *  (-0.182), 7/20 empty (vs 0/20 baseline).  The model
         *  hedged more on the inflated [LOW] count.  0.5 stays. */
        const val LOW_CONFIDENCE_THRESHOLD = 0.5f

        /** Hard cap on the number of OCR blocks injected into the
         *  round-1 user message.  By selecting the top-N by
         *  confidence we keep the prompt bounded (~2 KB) while
         *  keeping higher info density than a flat text dump.
         *  30 fits the "details: 5-8 row" guidance from the system
         *  prompt plus headroom for compare_text follow-ups.
         *  Tested 20 (2026-07-10 round 2): regressed r2_text_fuzzy
         *  -0.042 vs 30 lines; some text the model was verifying
         *  got truncated out of the hint.  Reverted. */
        const val MAX_OCR_HINT_LINES = 30

        /** Tighter cap for the per-zoom crop OCR hint.  Crops are
         *  smaller regions so they usually have far fewer lines;
         *  10 keeps the per-zoom hint ~1 KB and cuts token cost on
         *  multi-zoom chains (3 zooms × ~2 KB round-1 = 6 KB vs
         *  3 × ~1 KB crop = 3 KB saved).  The model still gets the
         *  high-conf lines via top-K; rare dense crops (>10 text
         *  regions) drop their low-conf lines which the model can
         *  pick up by chaining another zoom_in (the crop OCR runs
         *  automatically on every followUpJpeg). */
        const val MAX_CROP_OCR_HINT_LINES = 10

        /** Format the structured [blocks] list as the round-1 hint
         *  block injected into the user message.  Sorted by
         *  confidence descending, truncated to
         *  [maxLines] rows (default [MAX_OCR_HINT_LINES]).  Each
         *  row carries text + 4 corner coords (normalized [0,1]) +
         *  confidence; [LOW] suffix on rows below
         *  [LOW_CONFIDENCE_THRESHOLD].
         *
         *  Pure data — no I/O, no LLM calls — so [ToolUseLoop] can
         *  format it cheaply on every cycle.  The result also
         *  drives the `compare_text` tool's input so the same shape
         *  appears on both sides.
         *
         *  Phase 2a (2026-07-11):
         *  - [maxLines] lets callers pass a tighter cap for
         *    per-zoom crop hints (see [MAX_CROP_OCR_HINT_LINES]).
         *    Smaller crops → fewer text lines → cap to 10 keeps the
         *    hint concise and the model focused on high-conf lines.
         *  - [isCropHint] toggles the header + [LOW] follow-up
         *    advice: round-1 hint steers the model to call zoom_in
         *    on [LOW] bboxes; crop hint steers the model to
         *    **trust** the high-fidelity re-scan OCR verbatim. */
        fun formatHint(
            blocks: List<OcrBlock>,
            maxLines: Int = MAX_OCR_HINT_LINES,
            isCropHint: Boolean = false,
        ): String {
            if (blocks.isEmpty()) return ""
            val sorted = blocks.sortedByDescending { it.confidence }
                .take(maxLines)
            val sb = StringBuilder()
            sb.append(
                if (isCropHint) {
                    "【zoom_in crop OCR 高保真重扫】on-device OCR 已对该裁剪区域重新扫描（更高分辨率，比 round-1 hint 的同区域更可靠），下面按行给出字符+坐标+置信度（坐标是 crop frame，归一化 [0,1]，顺序: 左上→右上→右下→左下）。\n"
                } else {
                    "【on-device OCR 全图扫描结果】on-device OCR 已扫过整张图，下面按行给出字符+坐标+置信度（坐标归一化 [0,1]，顺序: 左上→右上→右下→左下）。\n"
                }
            )
            sorted.forEachIndexed { i, b ->
                val corners = b.corners
                val bbox = if (corners.size >= 4) {
                    val tl = corners[0]; val tr = corners[1]
                    val br = corners[2]; val bl = corners[3]
                    "[(" + fmt(tl.x) + "," + fmt(tl.y) + ")," +
                        "(" + fmt(tr.x) + "," + fmt(tr.y) + ")," +
                        "(" + fmt(br.x) + "," + fmt(br.y) + ")," +
                        "(" + fmt(bl.x) + "," + fmt(bl.y) + ")]"
                } else "[]"
                val lowSuffix =
                    if (b.confidence < LOW_CONFIDENCE_THRESHOLD) " [LOW]" else ""
                sb.append("  line ").append(i + 1).append(": '")
                    .append(b.text).append("' | bbox=").append(bbox)
                    .append(" | conf=").append(fmtConf(b.confidence))
                    .append(lowSuffix).append('\n')
            }
            val lowCount = sorted.count { it.confidence < LOW_CONFIDENCE_THRESHOLD }
            sb.append("------------------\n")
            sb.append("总共识别 ").append(sorted.size).append(" 行（按可信度排序）")
            if (lowCount > 0) {
                sb.append("；其中 ").append(lowCount).append(" 行 [LOW]（<")
                    .append(LOW_CONFIDENCE_THRESHOLD)
                    .append("）。\n")
                if (isCropHint) {
                    sb.append("**trust 这些字符 verbatim**——crop OCR 是高保真重扫，字符本身是你能直接用的 verbatim 字符，[LOW] 只是 OCR 引擎的 confidence 低，字符本身仍然可信。在 emit_bubble.content 和 details[] 里 verbatim 引用。\n")
                } else {
                    sb.append("[LOW] 行的字符 verbatim 引用到 emit_bubble（在 details[].label 标记 \"[LOW]\" 让用户知道这一行 OCR 不太确定）。\n")
                    sb.append("**workflow**：如果某行 [LOW] 影响你要 emit 的内容，**调 zoom_in(bbox) 重扫**——zoom_in 的 crop 会自动附带一次高保真 OCR。\n")
                }
            } else if (!isCropHint) {
                sb.append("，全部 verbatim 引用到 emit_bubble.content 和 details[]。\n")
            }
            return sb.toString().trimEnd()
        }

        private fun fmt(v: Float): String = "%.2f".format(v)
        private fun fmtConf(c: Float): String = "%.2f".format(c)
    }
}