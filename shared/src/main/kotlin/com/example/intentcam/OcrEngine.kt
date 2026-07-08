package com.example.intentcam

/**
 * Strategy holder for on-device OCR.  Lives in `:shared` so the
 * `read_text` tool body can call it without an Android dependency.
 *
 * The implementation strategy is set once at startup:
 *   - Android app: `MainActivity.onCreate` installs an HMS ML Kit-backed
 *     recognizer that decodes the JPEG bytes to a Bitmap internally
 *     and returns text + 4-point coordinates + confidence per block.
 *   - JVM eval:    no implementation installed — the eval returns
 *     [OcrResult.EMPTY] from `read_text` since the eval machine has no
 *     on-device OCR.  (The system prompt steers the model to avoid
 *     `read_text` by default anyway.)
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
 * blocks joined with spaces (single-line behavior, matches what
 * `read_text`'s prior String return value carried).  [blocks] is the
 * structured per-block list with positions + confidences — used by
 * the tool to return spatial detail.
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
         *  the line entirely ("宁可不写也别编"). */
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

        /** Format the structured [blocks] list as the round-1 hint
         *  block injected into the user message.  Sorted by
         *  confidence descending, truncated to
         *  [MAX_OCR_HINT_LINES] rows.  Each row carries text + 4
         *  corner coords (normalized [0,1]) + confidence; [LOW]
         *  suffix on rows below [LOW_CONFIDENCE_THRESHOLD].
         *
         *  Pure data — no I/O, no LLM calls — so [ToolUseLoop] can
         *  format it cheaply on every cycle.  The result also
         *  drives the `compare_text` tool's input so the same shape
         *  appears on both sides. */
        fun formatHint(blocks: List<OcrBlock>): String {
            if (blocks.isEmpty()) return ""
            val sorted = blocks.sortedByDescending { it.confidence }
                .take(MAX_OCR_HINT_LINES)
            val sb = StringBuilder()
            sb.append("【read_text 全图扫描结果】on-device OCR 已扫过整张图，" +
                "下面按行给出字符+坐标+置信度（坐标归一化 [0,1]，顺序: 左上→右上→右下→左下）。\n")
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
                    .append("），可能是模糊/艺术字/手写。\n")
                sb.append("这些行不要直接 verbatim 复制——可以调 zoom_in (用上面的 bbox) 看细节，或直接放弃（宁可不写也别编）。\n")
            }
            return sb.toString().trimEnd()
        }

        private fun fmt(v: Float): String = "%.2f".format(v)
        private fun fmtConf(c: Float): String = "%.2f".format(c)
    }
}