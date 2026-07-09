package com.example.intentcam

import org.json.JSONArray
import org.json.JSONObject

/**
 * Simplified tool set: only TWO tools.
 *
 *   1. zoom_in    — crop a region of the (current chain) image at
 *                   native resolution, return the crop as a
 *                   followUpJpeg so the LLM can re-look at the
 *                   detail on the next round.
 *
 *   2. emit_bubble — end the cycle with a structured final answer
 *                   (content + intent + intent_type + intent_focus
 *                   + confidence).  No chips / no follow-up actions
 *                   for now (deferred per the design call).
 *
 * The 10 specialized "interpret_image_<x>" tools from the previous
 * architecture are gone.  They were forcing the LLM to first
 * *categorize* the image, which made it fall back to
 * default_describe on anything it didn't recognize.  Now the LLM
 * just looks at the image, zooms for detail, and emits a bubble.
 * Less ceremony, more of the LLM's real capability on the table.
 */
fun ToolRegistry.registerDefaultTools() {

    // ── 1. zoom_in ───────────────────────────────────────────────
    // Detail-on-demand.  The LLM asks for a sub-region of whatever
    // it's currently looking at (the original full-res, or the result
    // of the previous zoom_in).  The body returns a real crop, the
    // orchestrator feeds it back as a followUpJpeg.
    register(
        ToolDef(
            name = "zoom_in",
            description = "画面里某个区域你看不清/需要更细的细节时调这个。" +
                "用归一化坐标 (x, y, w, h) ∈ [0, 1] 给出区域，x/y 是左上角，w/h 是宽高。" +
                "右上角四分之一 = {x: 0.5, y: 0, w: 0.5, h: 0.5}。" +
                "**source 字段**：默认 \"original\"（裁**原始照片**——坐标绝对，永远从最高分辨率开始放大）；" +
                "选 \"last\" 时裁上一张你看到的图（链式放大 — 坐标相对，适合继续在 zoom_in 结果里钻细节）。" +
                "focus 字段是你想在那个区域找什么（一句话，便于在工具结果里复读）。" +
                "**为什么不默认链式**：round 1 你看到的是 768-px 缩略图，链式放大会从这张小图再裁，裁出来的反而比原图更糊。" +
                "默认 source=\"original\" 是因为 zoom_in 99% 的用法是『让我看看图上某块的细节』，从 4096-px 原图裁出来总是比从 768-px 缩略图裁更清晰。" +
                "**链式放大**：想继续在上一张裁剪结果上钻（同一区域更深一层的细节），调第二次时显式传 source=\"last\"。" +
                "**不要**用纯文本说『让我再看看』 — 必须用 tool_use 触发真正的裁剪。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().apply {
                        put("type", "number"); put("minimum", 0); put("maximum", 1)
                        put("description", "左边缘归一化坐标 [0, 1]（相对于 source 指定的图）")
                    })
                    put("y", JSONObject().apply {
                        put("type", "number"); put("minimum", 0); put("maximum", 1)
                        put("description", "上边缘归一化坐标 [0, 1]")
                    })
                    put("w", JSONObject().apply {
                        put("type", "number"); put("minimum", 0); put("maximum", 1)
                        put("description", "宽度归一化坐标 [0, 1]")
                    })
                    put("h", JSONObject().apply {
                        put("type", "number"); put("minimum", 0); put("maximum", 1)
                        put("description", "高度归一化坐标 [0, 1]")
                    })
                    put("source", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("original").put("last"))
                        put("description", "裁哪张图。original=原始照片（默认，坐标绝对，最高分辨率）；last=上一张你看到的（链式，坐标相对）")
                    })
                    put("focus", JSONObject().apply {
                        put("type", "string")
                        put("description", "这个区域里你想找什么（一句话）")
                    })
                })
                put("required", JSONArray().put("x").put("y").put("w").put("h"))
                put("additionalProperties", false)
            },
            body = { ctx, input ->
                val x = input.optDouble("x", 0.0).toFloat()
                val y = input.optDouble("y", 0.0).toFloat()
                val w = input.optDouble("w", 0.0).toFloat().coerceAtLeast(0.05f)
                val h = input.optDouble("h", 0.0).toFloat().coerceAtLeast(0.05f)
                val focus = input.optString("focus", "").ifBlank { "细节" }
                // Default to "original" (crop the 4096-wide fullRes),
                // not "last" (crop the 3200-wide round-1 thumbnail).
                // With "last" as the default a 50% region on a 3200
                // thumbnail would give the LLM a 1600-wide crop —
                // strictly less detail than round 1's view.  Defaulting
                // to "original" keeps zoom_in a real magnifier: any
                // region you ask for comes from the full-resolution
                // original (capped at CROP_OUTPUT_MAX_DIM=3200 on
                // output, matching the round-1 thumbnail resolution).
                val source = input.optString("source", "original")
                val sourceJpeg = if (source == "original") ctx.originalFullRes else ctx.jpeg
                val crop = cropJpegRegion(sourceJpeg, x, y, w, h, quality = 90)
                if (crop == null) {
                    ToolResult(
                        toolSummary = "zoom_in 失败：无法裁剪 source=$source (${x}, ${y}, ${w}, ${h})",
                        finalBubble = false,
                    )
                } else {
                    ToolResult(
                        toolSummary = "已放大区域 (${x}, ${y}, ${w}, ${h}) source=$source，" +
                            "focus=$focus，请用更高分辨率查看这部分并继续回答。",
                        finalBubble = false,
                        followUpJpeg = crop,
                    )
                }
            },
        )
    )

    // ── 2. compare_text ────────────────────────────────────────────
    // Pure on-device diff between the model's own reading and the
    // round-1 OCR hint.  No LLM round-trip — runs in-process on the
    // cached [OcrResult] from [ToolContext.ocrCache].
    //
    // Why: the round-1 OCR hint ships as the "first opinion" on
    // verbatim characters; the model still looks at the image
    // itself and may spot mismatches (OCR missed a line, OCR
    // misread a character, OCR hallucinated a word).  Instead of
    // the model re-reading the whole image, it sends its own
    // reading as `claim` and gets back a per-row conflict map
    // (agreed / ocr_only / llm_only / disagree) + a recommendation
    // (trust_ocr / trust_llm / zoom_in_required).  Cheaper than
    // another LLM call, and the structured output is easier for
    // the model to reason about than a free-form re-read.
    //
    // Implementation: pure Kotlin string diff.  We don't need a
    // heavy NLP dependency for this — the OCR blocks are already
    // sentence/word granularity and `claim` is what the model
    // emitted verbatim into its own reasoning.  Match by normalized
    // Levenshtein similarity per block, classify the relationship,
    // emit JSON.
    register(
        ToolDef(
            name = "compare_text",
            description = "把**你**从图上读到的字符 vs 第 1 轮 OCR hint 里的字符做端侧 diff（不调云端，省 round-trip）。\n" +
                "**什么时候调**：你看完图后发现 OCR hint 的某些行和你自己读的不一致——OCR 漏字 / 错字 / 编字 / 你对某行不确定。\n" +
                "**参数**：\n" +
                "  - claim: 你从图上**自己读到的**字符串（一段文字，整段或部分）\n" +
                "  - ocr_text: （可选）你想对比的 OCR hint 某行 / 某几行文字。如果不传，默认对**全部** OCR hint 做 diff。\n" +
                "**返回值**：每行的 conflict 标记 + 推荐动作 —\n" +
                "  - agreed: 你俩读的字一致 → trust_ocr（直接 verbatim 用 OCR 字符）\n" +
                "  - ocr_only: OCR 有但你没提到 → zoom_in_required（可能 OCR 错或你漏看）\n" +
                "  - llm_only: 你有但 OCR 没有 → zoom_in_required（可能你幻觉或 OCR 漏）\n" +
                "  - disagree: 你俩都有但内容不一样 → trust_llm（你图上看到的优先）或 zoom_in_required\n" +
                "**用法**：调完一次拿到的 diff 结果决定哪些行要 zoom_in 重扫 / 直接 drop。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("claim", JSONObject().apply {
                        put("type", "string")
                        put("description", "你从图上读到的字符串（整段或部分）")
                    })
                    put("ocr_text", JSONObject().apply {
                        put("type", "string")
                        put("description", "（可选）OCR hint 的某行 / 某几行文字。不传则对全部 OCR hint 做 diff。")
                    })
                })
                put("required", JSONArray().put("claim"))
                put("additionalProperties", false)
            },
            body = { ctx, input ->
                val claim = input.optString("claim", "").trim()
                if (claim.isBlank()) {
                    ToolResult(
                        toolSummary = "compare_text: claim 为空，没有可对比的内容"
                    )
                } else {
                    val ocrText = input.optString("ocr_text", "").trim()
                    // Select OCR blocks: explicit ocr_text → substring
                    // match against OCR blocks; otherwise full cache.
                    val ocrBlocks: List<OcrBlock> = if (ocrText.isNotBlank()) {
                        val needle = ocrText.lowercase()
                        ctx.ocrCache.blocks.filter { it.text.lowercase().contains(needle) }
                            .ifEmpty { ctx.ocrCache.blocks }
                    } else {
                        ctx.ocrCache.blocks
                    }
                    if (ocrBlocks.isEmpty()) {
                        ToolResult(
                            toolSummary = "compare_text: OCR cache 为空（OCR 后端未安装或图上无文字）。" +
                                " 你读到的 claim 是：'$claim'。"
                        )
                    } else {
                        val diffs = compareClaimAgainstBlocks(claim, ocrBlocks)
                        val sb = StringBuilder()
                        sb.append("compare_text diff:\n")
                        for (d in diffs) {
                            sb.append("  • [${d.conflict}] ${d.text}")
                            if (d.ocrBlock != null) {
                                val c = d.ocrBlock
                                sb.append(" | conf=${"%.2f".format(c.confidence)}")
                                if (c.confidence < OcrResult.LOW_CONFIDENCE_THRESHOLD) sb.append(" [LOW]")
                            }
                            sb.append(" → ${d.recommendation}\n")
                        }
                        val summary = "agreed=${diffs.count { it.conflict == "agreed" }} " +
                            "ocr_only=${diffs.count { it.conflict == "ocr_only" }} " +
                            "llm_only=${diffs.count { it.conflict == "llm_only" }} " +
                            "disagree=${diffs.count { it.conflict == "disagree" }}"
                        sb.append("summary: ").append(summary)
                        ToolResult(toolSummary = sb.toString())
                    }
                }
            },
        )
    )

    // ── 4. emit_bubble ───────────────────────────────────────────
    // End the cycle with a structured final answer.  Two stages:
    //   - content:        what you see in the image (after any zoom_ins)
    //   - intent:         what the user probably wants to do with it
    //   - type:           info / location / solve — coarse intent category
    //   - intent_focus:   which area of the image informs the intent
    //                     (optional; helps the detail view highlight it)
    //   - confidence:     0.0 .. 1.0
    register(
        ToolDef(
            name = "emit_bubble",
            description = "当你完全理解了图片内容和用户意图后，调这个工具结束识别循环。" +
                "**content** 字段：详细描述你看到了什么（一两句话），并原样写出所有可见文字。" +
                "**intent** 字段：用户想用这张图做什么（动宾短语，≤30字）。" +
                "**type** 字段：info（信息查询）/ location（位置相关）/ solve（求解/操作）" +
                "**intent_focus** 字段：哪一块图像区域支持这个意图（可空）。" +
                "**details** 字段（**重要**）：图里每一处独立的文字/数字/品牌/日期/价格都要有一行，" +
                "value 写逐字原文（勿意译、勿概括）。图里有文字却 details 为空 = 没完成任务。" +
                "**confidence** 字段：0.0~1.0 的置信度。" +
                "**action_chips** 字段暂缓实现，先不填。" +
                "**必须**调这个工具结束循环，不能用纯文本收尾。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "图片内容的详细描述（一两句话）")
                    })
                    put("intent", JSONObject().apply {
                        put("type", "string")
                        put("description", "用户想做什么（动宾短语，≤30字）")
                    })
                    put("type", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("info").put("location").put("solve"))
                        put("description", "意图大类")
                    })
                    put("intent_focus", JSONObject().apply {
                        put("type", "string")
                        put("description", "支撑意图的图像区域（可空）")
                    })
                    put("details", JSONObject().apply {
                        put("type", "array")
                        put("description", "细节列表，详情页表格行")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("kind", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "text / number / object / color / shape / logo / date / price / ...")
                                })
                                put("label", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "人类可读的名字")
                                })
                                put("value", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "从图中读到的值（verbatim 复制 OCR hint 的字符）")
                                })
                                put("bbox", JSONObject().apply {
                                    put("type", "array")
                                    put("description", "（可选）4 个角点坐标 [(x1,y1),(x2,y2),(x3,y3),(x4,y4)] 归一化 [0,1]，" +
                                        "顺序: 左上→右上→右下→左下。直接 verbatim 复制 OCR hint 给的 bbox，" +
                                        "详情页可高亮该行在原图的位置。")
                                    put("items", JSONObject().apply {
                                        put("type", "array")
                                        put("minItems", 2)
                                        put("maxItems", 2)
                                        put("items", JSONObject().apply {
                                            put("type", "number"); put("minimum", 0); put("maximum", 1)
                                        })
                                    })
                                    put("minItems", 4)
                                    put("maxItems", 4)
                                })
                            })
                            put("required", JSONArray().put("kind").put("label").put("value"))
                            put("additionalProperties", false)
                        })
                    })
                    put("confidence", JSONObject().apply {
                        put("type", "number")
                        put("description", "置信度 0.0~1.0")
                    })
                })
                put("required", JSONArray().put("content").put("intent").put("type").put("confidence"))
                put("additionalProperties", false)
            },
            body = { _, input ->
                val details = mutableListOf<Detail>()
                val detArr = input.optJSONArray("details")
                if (detArr != null) {
                    for (i in 0 until detArr.length()) {
                        val d = detArr.optJSONObject(i) ?: continue
                        val kind = d.optString("kind", "text").ifBlank { "text" }
                        val label = d.optString("label", "").trim()
                        val value = d.optString("value", "").trim()
                        if (label.isEmpty() || value.isEmpty()) continue
                        // Parse bbox: 4 corners × 2 coords.  Optional —
                        // when the model didn't echo the OCR hint's
                        // bbox (e.g. generic visual details with no
                        // textual anchor) we just leave it null.
                        val bbox = parseBbox(d.optJSONArray("bbox"))
                        details.add(
                            Detail(kind = kind, label = label, value = value, bbox = bbox)
                        )
                    }
                }
                ToolResult(
                    toolSummary = "emit_bubble 收尾（${details.size} 条 detail）",
                    finalBubble = true,
                    type = input.optString("type", "info").ifBlank { "info" },
                    title = input.optString("intent", "").ifBlank { "查看图片细节" },
                    detail = input.optString("content", ""),
                    confidence = input.optDouble("confidence", 0.7).toFloat().coerceIn(0f, 1f),
                    details = details,
                )
            },
        )
    )
}

// ── emit_bubble bbox helper ────────────────────────────────────────

/** Parse the optional `bbox` field from an emit_bubble details[]
 *  item.  Returns null if the field is missing / malformed /
 *  wrong-length so the model can leave it blank for visual rows
 *  without positional anchors. */
private fun parseBbox(arr: org.json.JSONArray?): List<OcrPoint>? {
    if (arr == null || arr.length() != 4) return null
    val points = mutableListOf<OcrPoint>()
    for (i in 0 until 4) {
        val pair = arr.optJSONArray(i) ?: return null
        if (pair.length() != 2) return null
        val x = pair.optDouble(0, Double.NaN).toFloat()
        val y = pair.optDouble(1, Double.NaN).toFloat()
        if (x.isNaN() || y.isNaN()) return null
        points.add(OcrPoint(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f)))
    }
    return points
}

// ── compare_text helpers ───────────────────────────────────────────

/** One row in the compare_text diff result.  Pure data; the tool
 *  body formats it as a multi-line string the model reads. */
private data class CompareDiff(
    /** "agreed" | "ocr_only" | "llm_only" | "disagree" */
    val conflict: String,
    /** The text fragment this row is about.  For "ocr_only" / "disagree"
     *  this is the OCR block's text; for "llm_only" it's a substring
     *  of `claim` not matched by any OCR block; for "agreed" it's
     *  the (normalized-equal) shared fragment. */
    val text: String,
    /** The OCR block this row corresponds to, if any.  Null for
     *  "llm_only" rows. */
    val ocrBlock: OcrBlock?,
    /** "trust_ocr" | "trust_llm" | "zoom_in_required" — what the
     *  model should do next for this row. */
    val recommendation: String,
)

/**
 * Compute per-row diff between the model's `claim` and a list of
 * OCR blocks.  Pure Kotlin string algorithm — no LLM call.
 *
 * Heuristics (intentionally simple):
 *  - Normalize whitespace + lowercase for matching only.
 *  - For each OCR block, check if `claim` contains the block text
 *    (after normalization) → "agreed".
 *  - If `claim` is fully consumed → done.
 *  - Remaining OCR blocks → "ocr_only" (OCR has but LLM didn't).
 *  - Remaining `claim` fragments → "llm_only" (LLM has but OCR didn't).
 *  - "disagree" is reserved for the rare case where an OCR block
 *    has high similarity (>=0.5) to a `claim` fragment but they
 *    aren't substring-equal.
 *
 * Returns: ordered list of [CompareDiff] (OCR blocks in order,
 * followed by LLM-only fragments).
 */
private fun compareClaimAgainstBlocks(
    claim: String,
    ocrBlocks: List<OcrBlock>,
): List<CompareDiff> {
    val claimNorm = normalizeForMatch(claim)
    if (claimNorm.isEmpty() || ocrBlocks.isEmpty()) return emptyList()
    val results = mutableListOf<CompareDiff>()
    var remainingClaim = claimNorm

    for (block in ocrBlocks) {
        val blockNorm = normalizeForMatch(block.text)
        if (blockNorm.isEmpty()) continue
        when {
            // OCR text is fully inside the claim → agreed
            remainingClaim.contains(blockNorm) -> {
                results.add(
                    CompareDiff(
                        conflict = "agreed",
                        text = block.text,
                        ocrBlock = block,
                        recommendation = "trust_ocr",
                    )
                )
                // Strip the matched fragment so we don't double-count.
                remainingClaim = remainingClaim.replace(blockNorm, " ", ignoreCase = true)
            }
            // OCR text is high-similarity to a claim fragment (>=0.5)
            // → disagree (LLM and OCR both have something here but
            // the wording diverges).
            similarity(blockNorm, remainingClaim) >= 0.5 -> {
                results.add(
                    CompareDiff(
                        conflict = "disagree",
                        text = block.text,
                        ocrBlock = block,
                        recommendation = if (block.confidence >= OcrResult.LOW_CONFIDENCE_THRESHOLD) {
                            "zoom_in_required"
                        } else {
                            "trust_llm"
                        },
                    )
                )
            }
            // OCR text is unique to OCR (not in claim) → ocr_only
            else -> {
                results.add(
                    CompareDiff(
                        conflict = "ocr_only",
                        text = block.text,
                        ocrBlock = block,
                        // High-conf OCR: model probably missed this
                        // line → zoom_in_required.  Low-conf OCR:
                        // don't trust it, let the model decide
                        // whether to drop → trust_llm.
                        recommendation = if (block.confidence >= OcrResult.LOW_CONFIDENCE_THRESHOLD) {
                            "zoom_in_required"
                        } else {
                            "trust_llm"
                        },
                    )
                )
            }
        }
    }

    // Remaining `claim` content → llm_only (model hallucinated or
    // OCR missed).  We collapse whitespace and report as a single
    // fragment; the model can drill down on the specific span if
    // it cares.
    remainingClaim = remainingClaim.trim().replace(Regex("\\s+"), " ")
    if (remainingClaim.isNotEmpty()) {
        results.add(
            CompareDiff(
                conflict = "llm_only",
                text = remainingClaim,
                ocrBlock = null,
                recommendation = "zoom_in_required",
            )
        )
    }
    return results
}

/** Strip whitespace and lowercase for substring matching.  We
 *  intentionally do NOT normalize Unicode / punctuation — the goal
 *  is to catch verbatim differences, not paper over them. */
private fun normalizeForMatch(s: String): String =
    s.trim().lowercase()

/** Quick similarity ratio: 1 - (levenshtein / max(len)).  O(n*m)
 *  but fine for short claim / block strings (typically < 100 chars). */
private fun similarity(a: String, b: String): Float {
    if (a.isEmpty() && b.isEmpty()) return 1f
    if (a.isEmpty() || b.isEmpty()) return 0f
    val dist = levenshtein(a, b)
    val maxLen = maxOf(a.length, b.length)
    return 1f - dist.toFloat() / maxLen
}

/** Iterative Levenshtein distance.  Returns the number of single-
 *  character edits needed to turn `a` into `b`. */
private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(
                curr[j - 1] + 1,        // insertion
                prev[j] + 1,            // deletion
                prev[j - 1] + cost,     // substitution
            )
        }
        // Swap rows: prev becomes the row we just computed.
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
}