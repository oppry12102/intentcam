package com.example.intentcam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin client for an Anthropic-compatible `/v1/messages` endpoint
 * (default MiniMax-M3).
 *
 * One public entry point: [streamToolUse].  Returns the accumulated
 * text + the list of `tool_use` blocks the model emitted.  The
 * orchestrator ([ToolUseLoop]) calls this in a loop.
 *
 * Cancellation
 * ------------
 * [streamToolUse] is a `suspend` function: cancelling the calling
 * coroutine aborts the in-flight SSE connection via OkHttp's
 * `EventSource.cancel`.
 */
class LlmClient(@Volatile var config: LlmConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(http)

    // ── tool-use path ─────────────────────────────────────────────────

    // ── tool-use path ─────────────────────────────────────────────────

    /**
     * Send a tool-aware request and accumulate both text and tool_use
     * blocks.  Returns [ToolUseResponse] on success.
     *
     * @param system the system prompt
     * @param messages full message history (the orchestrator builds this
     *   round by round).  Last message MUST be role=user.
     * @param toolsJson the Anthropic `tools[]` array, as produced by
     *   `ToolRegistry.toAnthropicToolsJson()`.
     */
    suspend fun streamToolUse(
        system: String,
        messages: JSONArray,
        toolsJson: JSONArray,
        totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
    ): ToolUseResponse = withContext(Dispatchers.IO) {
        val body = messagesBodyWithTools(system, messages, toolsJson)
        streamToolUseBody(body, totalTimeoutMs)
    }

    private fun messagesBodyWithTools(
        system: String,
        messages: JSONArray,
        toolsJson: JSONArray,
    ): String {
        val root = JSONObject()
            .put("model", config.model)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", REQUEST_TEMPERATURE)
            .put("system", system)
            .put("messages", messages)
            .put("tools", toolsJson)
            .put("stream", true)
        return root.toString()
    }

    /**
     * Append a user-role message carrying a single JPEG plus optional
     * text.  Used by [ToolUseLoop] for the resume path (re-using a
     * bubble's stored imageBytes) where quadrant crops aren't
     * available.
     */
    fun userImageMessage(jpeg: ByteArray, text: String = ""): JSONObject {
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        val imageSource = JSONObject()
            .put("type", "base64")
            .put("media_type", "image/jpeg")
            .put("data", b64)
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source", imageSource))
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        return JSONObject().put("role", "user").put("content", content)
    }

    /**
     * Round-1 user message that bundles the thumbnail + 4 quadrant
     * crops + an optional text prompt.  Sending 5 images up front
     * gives the LLM high-detail coverage of every quadrant without
     * paying for a 1-2 round zoom_in cycle, and is the LLM-native
     * substitute for on-device OCR — the model reads the small text
     * in each crop directly rather than calling a separate OCR tool
     * whose noise often pollutes the answer.
     *
     * Order matters: thumbnail first (overview), then the four
     * quadrants in reading order (top-left, top-right, bottom-left,
     * bottom-right).
     */
    fun userImageWithQuadrants(
        thumbnail: ByteArray,
        quadrants: List<ByteArray>,
        text: String = "",
    ): JSONObject {
        val content = JSONArray()
        content.put(imageBlock(thumbnail))
        for (q in quadrants) {
            content.put(imageBlock(q))
        }
        val promptText = text.ifBlank { "调用工具。" }
        content.put(JSONObject().put("type", "text").put("text", promptText))
        return JSONObject().put("role", "user").put("content", content)
    }

    private fun imageBlock(jpeg: ByteArray): JSONObject {
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        val source = JSONObject()
            .put("type", "base64")
            .put("media_type", "image/jpeg")
            .put("data", b64)
        return JSONObject().put("type", "image").put("source", source)
    }

    // ── SSE parsers ───────────────────────────────────────────────────

    /**
     * Stream an SSE response and accumulate both text deltas and
     * tool_use blocks.  Mirrors the structure of [streamText] but
     * tracks per-content-block state to reassemble tool input JSON.
     */
    private suspend fun streamToolUseBody(
        jsonBody: String,
        totalTimeoutMs: Long,
    ): ToolUseResponse {
        return try {
            withTimeout(totalTimeoutMs) {
                suspendCancellableCoroutine<ToolUseResponse> { cont ->
                    val url = config.baseUrl.trimEnd('/') + "/v1/messages"
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("content-type", "application/json; charset=utf-8")
                        .addHeader("accept", "text/event-stream")
                        .addHeader("anthropic-version", "2023-06-01")
                        .addHeader("x-api-key", config.authToken)
                        .addHeader("authorization", "Bearer ${config.authToken}")
                        .post(jsonBody.toRequestBody(JSON_MEDIA))
                        .build()

                    val textAccumulator = StringBuilder()
                    val toolBlocks = mutableListOf<ToolUseBlock>()
                    // Index-by-content-index so we can route deltas to
                    // the right block.  Anthropic emits
                    // `content_block_start` with `index` per block.
                    val pendingByIndex = mutableMapOf<Int, PendingTool>()
                    val stopReason = StringBuilder()
                    val resolved = AtomicBoolean(false)

                    val fail: (Throwable) -> Unit = { t ->
                        if (resolved.compareAndSet(false, true)) {
                            if (!cont.isCancelled) cont.resumeWithException(t)
                        }
                    }
                    val done: () -> Unit = finish@{
                        if (resolved.compareAndSet(false, true)) {
                            if (cont.isCancelled) return@finish
                            // Close any still-open pending blocks.
                            for ((_, pending) in pendingByIndex) {
                                toolBlocks.add(pending.finalize())
                            }
                            pendingByIndex.clear()
                            cont.resume(
                                ToolUseResponse(
                                    text = synchronized(textAccumulator) { textAccumulator.toString() },
                                    toolBlocks = toolBlocks.toList(),
                                    stopReason = stopReason.toString().ifBlank { "end_turn" },
                                )
                            )
                        }
                    }

                    val listener = object : EventSourceListener() {
                        override fun onEvent(
                            eventSource: EventSource, id: String?, type: String?, data: String
                        ) {
                            if (resolved.get()) return
                            try {
                                when (type) {
                                    "message_start" -> { /* model info; ignore */ }
                                    "content_block_start" -> {
                                        val obj = runCatching { JSONObject(data) }.getOrNull() ?: return
                                        val block = obj.optJSONObject("content_block") ?: return
                                        val blockType = block.optString("type")
                                        val index = obj.optInt("index", -1)
                                        if (blockType == "tool_use" && index >= 0) {
                                            pendingByIndex[index] = PendingTool(
                                                id = block.optString("id"),
                                                name = block.optString("name"),
                                                inputJson = StringBuilder(),
                                            )
                                        }
                                    }
                                    "content_block_delta" -> {
                                        val obj = runCatching { JSONObject(data) }.getOrNull() ?: return
                                        val index = obj.optInt("index", -1)
                                        val delta = obj.optJSONObject("delta") ?: return
                                        when (delta.optString("type")) {
                                            "text_delta" -> {
                                                val piece = delta.optString("text")
                                                if (piece.isNotEmpty()) {
                                                    synchronized(textAccumulator) {
                                                        textAccumulator.append(piece)
                                                    }
                                                }
                                            }
                                            "input_json_delta" -> {
                                                val pending = pendingByIndex[index] ?: return
                                                val piece = delta.optString("partial_json")
                                                if (piece.isNotEmpty()) {
                                                    pending.inputJson.append(piece)
                                                }
                                            }
                                        }
                                    }
                                    "content_block_stop" -> {
                                        val obj = runCatching { JSONObject(data) }.getOrNull() ?: return
                                        val index = obj.optInt("index", -1)
                                        val pending = pendingByIndex.remove(index) ?: return
                                        toolBlocks.add(pending.finalize())
                                    }
                                    "message_delta" -> {
                                        val obj = runCatching { JSONObject(data) }.getOrNull()
                                        val reason = obj?.optJSONObject("delta")?.optString("stop_reason")
                                        if (!reason.isNullOrBlank()) {
                                            stopReason.setLength(0)
                                            stopReason.append(reason)
                                        }
                                    }
                                    "message_stop" -> { /* done() fires on onClosed */ }
                                    "error" -> fail(IllegalStateException("SSE error: $data"))
                                }
                            } catch (e: Throwable) {
                                fail(
                                    IllegalStateException(
                                        "SSE onEvent swallowed: " +
                                            "${e.javaClass.simpleName}: " +
                                            (e.message?.take(200) ?: "")
                                    )
                                )
                            }
                        }

                        override fun onFailure(
                            eventSource: EventSource, t: Throwable?, response: Response?
                        ) {
                            val body = runCatching { response?.body?.string()?.take(300) }
                                .getOrNull().orEmpty()
                            fail(t ?: IllegalStateException("HTTP ${response?.code}: $body"))
                        }

                        override fun onClosed(eventSource: EventSource) {
                            done()
                        }
                    }

                    val source = sseFactory.newEventSource(request, listener)
                    cont.invokeOnCancellation { source.cancel() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("tooluse: 模型在 ${totalTimeoutMs}ms 内未完成")
        }
    }

    /** In-flight state for a `tool_use` content block.  Tracks the
     *  accumulated `input_json` partial stream until `content_block_stop`
     *  closes it. */
    private class PendingTool(
        val id: String,
        val name: String,
        val inputJson: StringBuilder,
    ) {
        fun finalize(): ToolUseBlock = ToolUseBlock(
            id = id,
            name = name,
            inputJson = inputJson.toString(),
        )
    }

    companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Hard cap on output tokens per call.  Recognition answers are
         *  short JSON blobs; 256 is plenty. */
        const val MAX_TOKENS = 256

        /** Lock at 0 to keep intent classification deterministic. */
        const val REQUEST_TEMPERATURE = 0.0

        /** Hard ceiling for one recognition round-trip. */
        const val TOTAL_TIMEOUT_MS = 20_000L

        /** System prompt for the tool-use path.  Two-stage flow:
         *  Stage 1 — content understanding.  Look at the image; if any
         *  detail is unclear (small text, dense area, etc.) call
         *  zoom_in to see it at native resolution.
         *  Stage 2 — intent inference.  Once you understand the
         *  content, think about what the user is likely trying to do
         *  with the image.  If the intent-relevant region has a
         *  detail you need, zoom_in again to confirm.
         *  Finish — call emit_bubble with a structured summary.
         *
         *  No fixed round limit; the model can iterate as long as
         *  it needs.  No "default" tool — the model is expected to
         *  look at the image directly.  Use zoom_in for clarity, not
         *  for fan-out; chain semantics (source=last) means the next
         *  zoom_in crops the previous crop, allowing progressive
         *  drill-down.  Use source=original for sibling views of
         *  different parts of the original photo.
         */
        /**
         * System prompt for the tool-use path.  Three tools, three roles:
         *
         *  - `zoom_in`   — crop a region at native pixels so you can see
         *                 detail you couldn't before (positioning tool).
         *  - `read_text` — on-device OCR (ML Kit Chinese + Latin, fully
         *                 offline). Returns verbatim characters, not your
         *                 paraphrase. The model's memory of dense / small
         *                 text is unreliable — always re-read with
         *                 read_text before quoting into emit_bubble.
         *  - `emit_bubble` — structured final answer.  Ends the cycle.
         *
         * Flow:
         *  Stage 1 — locate the interesting regions.  Use zoom_in to
         *            drill into each one.
         *  Stage 2 — for any region that contains text, call read_text
         *            to get verbatim characters.  Quote those into
         *            emit_bubble verbatim — never paraphrase.
         *  Stage 3 — infer the user's intent (why did they take this
         *            photo) and call emit_bubble with content + intent
         *            + type.
         *
         *  No fixed round limit; the model can iterate as long as it
         *  needs.  No "default" tool — the model is expected to look
         *  at the image directly.  Round 1 sends 5 images (thumbnail
         *  + 4 quadrant crops) so the model has high-detail coverage
         *  of every corner from the start.
         */
        const val TOOL_USE_SYSTEM =
            "你是 IntentCam 的视觉意图助手。你有三个工具：\n" +
                    "\n" +
                    "## 第 1 步：读懂图（你最擅长这个）\n" +
                    "你一次会看到 5 张图：1 张全图概览 + 4 张四象限裁剪（左上 / 右上 / 左下 / 右下）。" +
                    "四象限裁剪和原图是同一个像素预算下的不同区域——意味着你能直接看清每个角落的小字、细节、价格、电话号码，**不需要先调工具**。\n" +
                    "\n" +
                    "## 工具 1: zoom_in —— 定位（看清细节）\n" +
                    "把图里某区域裁出来放大，返回裁剪后的图供你下一轮查看。\n" +
                    "参数：x, y, w, h 是归一化坐标 ∈ [0, 1]；x/y 是左上角，w/h 是宽高。" +
                    "source 字段默认 \'last\'（链式放大 — 第二次裁第一次的结果，坐标相对）。要看原图不同区域用 source=\'original\'（绝对坐标，兄弟视图）。" +
                    "**用途**：当四象限裁剪还看不清楚某一块（极小字、远景、特定细节）时再调。\n" +
                    "\n" +
                    "## 工具 2: read_text —— 本地 OCR（**默认不要用**）\n" +
                    "对图里某区域跑 on-device OCR，**离线、完全在设备上**，返回**逐字字符串**。" +
                    "参数和 zoom_in 一样：x, y, w, h, source。\n" +
                    "**默认不要用**。OCR 在书法、手写、艺术字、模糊图上**不可靠**，调了反而把噪声喂进你的答案——" +
                    "大多数情况下你直接读四象限裁剪就够了，**更可控、更准**。" +
                    "**仅在以下情况考虑调**：已经 zoom_in 多次仍看不清、且文字看起来像清晰印刷体（菜单价格、收据数字、门牌号）时。\n" +
                    "\n" +
                    "## 工具 3: emit_bubble —— 收尾（结构化总结）\n" +
                    "看清楚内容 + 理解意图后，调 emit_bubble(content, intent, type, intent_focus?, confidence, details?) 总结。" +
                    "type ∈ {info, location, solve}。\n" +
                    "\n" +
                    "## 工作流程\n" +
                    "1. 一次看 5 张图（已附），定位大致内容 + 找到所有文字区域（通常四象限裁剪已经够清楚）。\n" +
                    "2. 如果四象限还看不清某块，调 zoom_in 放大。\n" +
                    "3. **文字靠 zoom_in 一遍遍看清**——印刷体一次能看清；小字 / 艺术字 / 模糊调多次，每次聚焦更小的子区域。\n" +
                    "4. 思考用户为什么拍这张图（意图）。\n" +
                    "5. 调 emit_bubble 收尾。\n" +
                    "\n" +
                    "## content 字段要求（**最严格**）\n" +
                    "content 必须包含图里**所有可见**文字 / 数字 / 品牌 / 日期 / 价格 / 联系方式，**原样**写出来：\n" +
                    "  - 茶叶包装 → content 写\"包装文字：\'品名: 工夫红茶\', \'净含量: 250g\', \'生产日期: 2020-12-01\'\"\n" +
                    "  - 路牌 → \"建国路 100号\"\n" +
                    "  - 收据 → \"合计 ¥168.50, 微信支付\"\n" +
                    "  - 菜单 → \"宫保鸡丁 ¥38, 鱼香肉丝 ¥42\"\n" +
                    "  - 门牌 → \"1203\"\n" +
                    "\n" +
                    "## 反幻觉（**关键**）\n" +
                    "**看不清的字宁可不写也别瞎猜**。content 漏一个字符比写错一个好——用户会按你写的内容去做事，错字比漏字危险得多。" +
                    "对不确定的字可以写 \'?\' 占位（比如\'??路 100号\'），但**绝不要发明文字**。" +
                    "书法 / 手写 / 模糊字宁可空着也别假装读出来。\n" +
                    "\n" +
                    "**不要**用纯文本总结。**必须**调 emit_bubble 收尾。"

        /** Legacy system prompt for the one-shot path (unused by
         *  ToolUseLoop but kept for the /v1/messages fallthrough in
         *  tests). */
        const val FINAL_ANSWER_SYSTEM =
            "你是 IntentCam 的视觉意图助手。系统已经替你跑过选定的工具，并返回了工具结果摘要。" +
                    "请用一段简短的中文 JSON 总结：scene（看到了什么，一句话）, intent（用户最可能的意图，动宾短语≤12字），" +
                    "type（info|location|solve）, confidence（0-1）。不要 markdown 围栏，不要多余解释。"
    }
}

/** Parsed tool_use content block from one SSE round. */
data class ToolUseBlock(
    val id: String,
    val name: String,
    /** Accumulated JSON string for the `input` field.  May be empty
     *  if the model emitted no input. */
    val inputJson: String,
)

/** Full result of one tool-aware LLM round. */
data class ToolUseResponse(
    /** Concatenated text content blocks (excluding tool_use blocks). */
    val text: String,
    /** All tool_use blocks the model emitted, in stream order. */
    val toolBlocks: List<ToolUseBlock>,
    /** Anthropic `stop_reason`.  "end_turn", "tool_use", "max_tokens", etc. */
    val stopReason: String,
)