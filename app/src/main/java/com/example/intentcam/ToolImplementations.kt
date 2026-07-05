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
                "**source 字段**：默认 \"last\"（裁上一张你看到的图 — 链式放大，坐标相对）；" +
                "选 \"original\" 时裁原始照片（sibling 视图，坐标绝对）。" +
                "focus 字段是你想在那个区域找什么（一句话，便于在工具结果里复读）。" +
                "**链式放大**：连续调 zoom_in（用默认 source），第二次会裁第一次的结果，可以无限迭代。" +
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
                        put("enum", JSONArray().put("last").put("original"))
                        put("description", "裁哪张图。last=上一张你看到的（链式，坐标相对），original=原图（sibling，坐标绝对）")
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
                val source = input.optString("source", "last")
                val sourceJpeg = if (source == "original") ctx.originalFullRes else ctx.jpeg
                val crop = cropJpegRegion(sourceJpeg, x, y, w, h)
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

    // ── 2. emit_bubble ───────────────────────────────────────────
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
                "**content** 字段：详细描述你看到了什么（一两句话）。" +
                "**intent** 字段：用户想用这张图做什么（动宾短语，≤30字）。" +
                "**type** 字段：info（信息查询）/ location（位置相关）/ solve（求解/操作）" +
                "**intent_focus** 字段：哪一块图像区域支持这个意图（可空）。" +
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
                                    put("description", "从图中读到的值")
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
                        if (label.isNotEmpty() && value.isNotEmpty()) {
                            details.add(Detail(kind = kind, label = label, value = value))
                        }
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