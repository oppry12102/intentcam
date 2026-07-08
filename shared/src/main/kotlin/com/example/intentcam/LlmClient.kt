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
     * text.  This is the only round-1 message shape we ship — 1-only
     * image strategy since 2026-07-06.  If the model needs to drill
     * into a region of the original photo, it calls `zoom_in` and
     * gets a follow-up image in round 2+.
     *
     * @param ocrHint optional pre-computed OCR hint (output of
     *   [com.example.intentcam.OcrResult.formatHint]).  When
     *   non-blank, injected verbatim as a separate text content
     *   block.  The hint already includes the
     *   `【read_text 全图扫描结果】` marker and structured per-line
     *   text + bbox + confidence rows, so we don't re-wrap it here.
     */
    fun userImageMessage(
        jpeg: ByteArray,
        text: String = "",
        ocrHint: String = "",
    ): JSONObject {
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        val imageSource = JSONObject()
            .put("type", "base64")
            .put("media_type", "image/jpeg")
            .put("data", b64)
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source", imageSource))
        if (ocrHint.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", ocrHint))
        }
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
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

                    // Register the cancellation handler BEFORE creating
                    // the EventSource so a cancellation landing between
                    // newEventSource() returning and the next statement
                    // still cancels the in-flight SSE.  Without this, the
                    // EventSource + HTTP connection leak (readTimeout /
                    // callTimeout are both 0 → infinite).  The handler
                    // captures `source` by reference so a cancellation
                    // arriving before source is assigned is a no-op (we
                    // then call cancel() ourselves once source is set).
                    var source: EventSource? = null
                    cont.invokeOnCancellation { source?.cancel() }
                    val src = sseFactory.newEventSource(request, listener)
                    source = src
                    // Belt-and-suspenders: if cancellation landed during
                    // the two statements above, the handler fired with a
                    // null source.  Cancel the just-created source now so
                    // it doesn't leak until OkHttp eventually closes it.
                    if (cont.isCancelled) {
                        src.cancel()
                        return@suspendCancellableCoroutine
                    }
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

        /** Hard cap on output tokens per call.
         *
         *  Was 256, which silently truncated `emit_bubble` on dense
         *  scenes: the tool is required to put *all* visible text in
         *  `content` **and** emit one `details[]` row per text region.
         *  A busy storefront / receipt / menu blows past 256 tokens
         *  (~150 CJK chars once you count the JSON scaffolding), so the
         *  stream got cut mid-`details`, dropping both keywords and
         *  rows — the dominant cause of the r2_text 0.46 plateau and
         *  the 56-fixture cluster stuck at exactly 0.5.
         *
         *  1024 covers the largest real answers with headroom; short
         *  answers still stop early (the model emits `stop_reason=
         *  end_turn` well before the cap), so the cost is only paid on
         *  scenes that actually need it. */
        const val MAX_TOKENS = 1024

        /** Lock at 0 to keep intent classification deterministic. */
        const val REQUEST_TEMPERATURE = 0.0

        /** Hard ceiling for one recognition round-trip.
         *
         *  Was 20s: dense-text fixtures (产品包装/收据/金融 app 截图 with
         *  10+ GT keywords) need 1024-token emit_bubble; at ~30 tok/s that's
         *  ~34s of generation + 5s first-byte latency = 38s+ on overload.
         *  12/100 fixtures in the 2026-07-06 1-only @100 run timed out
         *  exactly here — every dense-text scene locked the cycle to
         *  Outcome.Error and zeroed its composite.  60s buys the worst
         *  case one full buffer; OkHttp's readTimeout/callTimeout are
         *  still 0 (infinite) so a true hang will still be caught. */
        const val TOTAL_TIMEOUT_MS = 60_000L

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
        /** End-cloud collaborative recognition prompt.  Round-1 ships
         *  the OCR hint (formatted by [OcrResult.formatHint]) as
         *  the **first opinion** on verbatim characters; the model
         *  reasons about intent + structure on top.  Four tools:
         *  zoom_in (visual drill-down), read_text (sub-region OCR
         *  re-scan on [LOW] lines), compare_text (pure on-device
         *  diff, no cloud round-trip), emit_bubble (final answer).
         *  The legacy "1-5 张图" / "默认不要用 read_text" guidance
         *  is gone — 1-only mode is production since 2026-07-06,
         *  and OCR is now the verbatim ground truth. */
        const val TOOL_USE_SYSTEM =
            "你是 IntentCam 的视觉意图助手。你有四个工具：\n" +
                    "\n" +
                    "## 关键原则：OCR 是「第一意见」，不是「兜底」\n" +
                    "第 1 轮你的 user message 已经被注入一份 **【read_text 全图扫描结果】**：on-device OCR（中英离线，HMS ML Kit）扫过整张图，按行返回字符 + 4 点坐标 + 可信度（按 conf 降序，最多 30 行）。" +
                    "这是你**直接可用**的字符基准——**verbatim 引用到 emit_bubble.content 和 details[]**，不要自己重新组织、意译、概括。\n" +
                    "\n" +
                    "## 工具 1: zoom_in —— 看清细节\n" +
                    "把图里某区域裁出来放大，返回裁剪后的图供你下一轮查看。\n" +
                    "参数：x, y, w, h 是归一化坐标 ∈ [0, 1]；x/y 是左上角，w/h 是宽高。" +
                    "source 默认 'original'（裁**原始照片**——坐标绝对，永远从 4096-px 原图裁，永远比 round-1 缩略图更清晰）。" +
                    "看上一张 zoom_in 结果里更深层细节用 source='last'（链式，坐标相对）；想看原图另一区域保持 source='original'。\n" +
                    "**为什么不默认 last**：round-1 缩略图是 1568-px 降采样版（不是 4096-px 原图），从缩略图再裁只会得到比 round-1 还糊的图；zoom_in 的全部价值在于『放大看清原图细节』，所以从原图开始。\n" +
                    "**用途**：OCR hint 里 [LOW] 行的 bbox，你想自己看清确认；或对**不在 OCR hint 里**的视觉区域（比如招牌图案、产品外观）你看不到的细节；或 round-1 缩略图上看不清字（密集文字 / 小字 / 模糊字），**必须**调 zoom_in 才能逐字确认。\n" +
                    "\n" +
                    "## 工具 2: read_text —— 局部 OCR 重扫\n" +
                    "对图里某区域重新跑 on-device OCR（**仅在以下场景调**）：\n" +
                    "  1. **OCR hint 里 [LOW] 的行**（conf<0.5）你想验证，调用前直接用 OCR hint 给的 bbox 作为 x/y/w/h。\n" +
                    "  2. **OCR hint 没识别到的区域**，但你在图上看到有文字（菜单上小字、被遮挡的下半行），用 bbox 重扫。\n" +
                    "参数：x, y, w, h, source（和 zoom_in 一样）。\n" +
                    "**不要**在 OCR hint 已经很清晰的印刷体上重复调 read_text——浪费 round-trip。\n" +
                    "**不要**在书法 / 手写 / 模糊图上调 read_text——OCR 不可靠，会喂噪声进你的答案。\n" +
                    "\n" +
                    "## 工具 3: compare_text —— 端云 diff\n" +
                    "纯端侧 diff：**你**读的字符 vs OCR hint 给的字符。结果告诉哪些行「同意 / OCR-only / 你-only / 冲突」。\n" +
                    "**调用场景**：当你读完图后发现 OCR hint 的某些行和你自己读的不一致（比如 OCR 漏字 / 编字 / 错字），调一次 compare_text(claim=你读的字符) 让端侧告诉你差异。\n" +
                    "**好处**：纯 Kotlin 字符串 diff，不调云端，省 round-trip。\n" +
                    "\n" +
                    "## 工具 4: emit_bubble —— 收尾\n" +
                    "看清楚内容 + 理解意图后，调 emit_bubble(content, intent, type, intent_focus?, confidence, details?) 总结。\n" +
                    "type ∈ {info, location, solve}。\n" +
                    "\n" +
                    "## 工作流程\n" +
                    "1. 读 user message 里的 OCR 全图扫描结果——这是文字**第一意见**（不是唯一意见）。\n" +
                    "2. 看图，确认场景 / 结构 / 布局（OCR 不会告诉你图里**非文字**的东西；也可能漏掉 OCR 没识别的文字）。\n" +
                    "3. **thumbnail 不是原图**：你看到的是 1568-px 降采样版（原图 4096-px）。视觉结构 / 颜色 / 整体布局够用，但**密集文字 / 小字 / 模糊字**在缩略图上会糊——" +
                        "如果你看到图上有字但**逐字看不清楚**（不是「知道大概是什么」而是「看不清每个字符」），**必须**调 zoom_in 用 bbox 看清楚再写进 content / details。" +
                        "不要因为缩略图看起来够大就跳过 zoom_in——1568 还不到原图的 40%，密集文字经常糊。\n" +
                    "4. **冲突检查**：OCR hint 里的字和你图上看到的字对得上吗？" +
                        "对不上 → 调 compare_text 让端侧告诉你差异，对 [LOW] / 冲突行 → 调 zoom_in 用 bbox 看细节 → 如果是高保真印刷体可考虑 read_text 重扫。\n" +
                    "5. 思考用户为什么拍这张图（意图）。\n" +
                    "6. 调 emit_bubble：content + details 都要填，**不要因为 OCR 不完美就空 emit**。\n" +
                    "\n" +
                    "## content 字段要求\n" +
                    "content 必须包含图里**所有可见**文字 / 数字 / 品牌 / 日期 / 价格 / 联系方式，**原样**写出来。\n" +
                    "OCR hint 是**优先**来源（直接 verbatim 复制）；**但 OCR 漏掉的字 / OCR 没覆盖的区域**，" +
                    "你**自己从图上读到的字符也要写进 content**——OCR 是辅助，不是限制。\n" +
                    "  - 茶叶包装 → content 写\"包装文字：'品名: 工夫红茶', '净含量: 250g', '生产日期: 2020-12-01'\"\n" +
                    "  - 路牌 → \"建国路 100号\"\n" +
                    "  - 收据 → \"合计 ¥168.50, 微信支付\"\n" +
                    "  - 菜单 → \"宫保鸡丁 ¥38, 鱼香肉丝 ¥42\"\n" +
                    "  - 门牌 → \"1203\"\n" +
                    "\n" +
                    "## details 字段要求（**和 content 同等重要**）\n" +
                    "**图里每一处独立的文字 / 数字 / 品牌 / 日期 / 价格，都要在 details 里对应一行**，" +
                    "value 写**逐字原文**（OCR hint 优先，OCR 漏的用你自己读的；不要意译、不要概括），" +
                    "**bbox 字段填 OCR hint 给的 4 点坐标**（让详情页能高亮该行在原图的位置；OCR 漏掉的行可以留空 bbox）：\n" +
                    "  - {kind:'brand', label:'品名', value:'工夫红茶', bbox:[(0.10,0.20),(0.30,0.20),(0.30,0.25),(0.10,0.25)]}\n" +
                    "  - {kind:'number', label:'净含量', value:'250g', bbox:[(0.10,0.26),(0.30,0.26),(0.30,0.31),(0.10,0.31)]}\n" +
                    "  - {kind:'price', label:'合计', value:'¥168.50', bbox:[(0.40,0.50),(0.60,0.50),(0.60,0.55),(0.40,0.55)]}\n" +
                    "**OCR hint [LOW] 行**（conf<0.5）**仍然写进 details**（带低置信标记或在 content 里注 \"(OCR 模糊)\"）；" +
                    "**不要因为 OCR [LOW] 就整行 drop**——drop = 用户看不到字。\n" +
                    "**能看清多少文字就写多少行**——但**有上限**：场景上文字 > 8 处时，" +
                    "按重要性把 details 裁到 **最值得高亮的 5-8 行**（品牌、价格、日期、地址、电话、关键警示），" +
                    "其余的合并到 content 里。这能避免 answer 过长被 token 截断 / round-trip 撞超时。\n" +
                    "\n" +
                    "## 反幻觉（**关键，但不要矫枉过正**）\n" +
                    "**看不清的字宁可不写也别瞎猜**——但**不要把这个理解成「不确定就空 emit」**。" +
                    "看不清的字可以写 '?' 占位（比如'??路 100号'），但**绝不要发明文字**。" +
                    "OCR hint 是参考，**但 OCR [LOW] 仍然是有用的信息**——写进 details，让用户在 UI 看到「这一行 OCR 不太确定」。" +
                    "书法 / 手写 / 模糊字**自己读到的**也可以写（带 '?' 占位），不要假装完全没看见。\n" +
                    "\n" +
                    "**不要**用纯文本总结。**必须**调 emit_bubble 收尾——**永远不要因为 OCR 不完美就跳过 emit_bubble**。"

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