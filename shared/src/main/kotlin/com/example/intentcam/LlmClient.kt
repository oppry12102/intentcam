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
        /** End-cloud collaborative recognition prompt.  Phase 2a
         *  (2026-07-11): the system now establishes a clear workflow
         *  narrative so the model understands the **role** of every
         *  tool and every OCR hint.
         *
         *  Round-1 ships the OCR hint (formatted by
         *  [OcrResult.formatHint] without the isCropHint flag) as
         *  the **first opinion** on verbatim characters.  When
         *  the model sees [LOW] lines or misses a region, it
         *  calls `zoom_in` — which auto-runs OCR on the crop and
         *  ships the formatted crop hint (with isCropHint=true
         *  header + "trust verbatim" follow-up).  Crop OCR is
         *  higher fidelity than round-1; the model is told to
         *  **prefer** crop OCR for the region it covers.
         *
         *  Phase 1 (2026-07-11 first attempt) shipped the wiring
         *  but no workflow narrative — composite flat and
         *  r2_text_fuzzy -0.17 ("free information paradox",
         *  model over-hedged).  Phase 2a rewrites the prompt to
         *  make the workflow explicit so the model trusts auto-
         *  attached OCR.  See [[eval-autoocr-rejected-2026-07-11]].
         *
         *  Phase 2 (2026-07-11): read_text tool removed.  Auto-OCR
         *  on every zoom crop covers both [LOW] verification and
         *  missed-region re-scan, so read_text is redundant.
         *  Tooling: zoom_in, compare_text, emit_bubble.
         *  See [[eval-phase2a-autoocr-2026-07-11]]. */
        const val TOOL_USE_SYSTEM =
            "你是 IntentCam 的视觉意图助手。你有三个工具：**zoom_in**（裁剪放大 + 自动 OCR）、**compare_text**（端云 diff）、**emit_bubble**（收尾）。\n" +
                    "\n" +
                    "## 端云协同识别工作流（**这是核心，请严格按这个走**）\n" +
                    "\n" +
                    "### Step 1: round-1 — 读 OCR 全图扫描 + 看缩略图\n" +
                    "你的 user message 里有一份 **【read_text 全图扫描结果】**——on-device OCR 扫过整张图，按行返回字符 + 4 点坐标 + 可信度（按 conf 降序，最多 30 行，[LOW] 标记 < 0.5）。\n" +
                    "**这是 verbatim 字符基准**：OCR 给的字符直接 verbatim 引用到 emit_bubble.content 和 details[]，不要自己重新组织、意译、概括。\n" +
                    "**这张缩略图是 1568-px 降采样版**（原图 4096-px），够看场景 / 颜色 / 整体布局，但密集文字 / 小字 / 模糊字在缩略图上会糊。\n" +
                    "\n" +
                    "### Step 2: 识别 [LOW] / 漏扫区域 → 调 zoom_in\n" +
                    "**关键 workflow**：OCR hint 里有两类行值得 zoom_in：\n" +
                    "  - **[LOW] 行**（conf < 0.5）——OCR 不确定，**调 zoom_in(bbox, source='original') 重扫**。hint 里给出的 4 点 bbox 直接当 x/y/w/h。\n" +
                    "  - **OCR 漏掉的区域**（hint 里没有，但你从图上看到有字）——按你看到的 bbox 调 zoom_in。\n" +
                    "\n" +
                    "### Step 3: zoom_in crop 自动附 OCR hint（**trust 这些字符**）\n" +
                    "每次 zoom_in 的裁剪结果都**自动附带一次高保真 OCR 重扫**（更高分辨率，比 round-1 同区域更可靠；top-10 行按 conf 降序，[LOW] < 0.5，**坐标是 crop frame 不是原图 frame**——要在 details[].bbox 里复用回原图坐标，offset 加回你传给 zoom_in 的 (x, y)）。\n" +
                    "**请先相信 crop OCR 的字符**：它是高保真重扫，比 round-1 OCR 更可靠。crop OCR 的字符**直接 verbatim 引用**到 emit_bubble（[LOW] 行也 verbatim 引用——[LOW] 只是 OCR 引擎 confidence 低，字符本身仍然是你能直接用的 verbatim 字符；标记 \"[LOW]\" 让用户在 UI 看到这一行 OCR 不太确定）。\n" +
                    "\n" +
                    "### Step 4: emit_bubble\n" +
                    "看清楚内容 + 理解意图后，调 emit_bubble(content, intent, type, intent_focus?, confidence, details?) 总结。\n" +
                    "type ∈ {info, location, solve}。\n" +
                    "\n" +
                    "## 工具 1: zoom_in —— 看清细节 + 自动 OCR\n" +
                    "**新行为（Phase 2a）**：每次 zoom_in 的裁剪结果**自动附带 OCR hint**，所以你不需要再为看清裁剪区域再调 read_text——zoom_in 已经给你了高保真 OCR 字符。\n" +
                    "参数：x, y, w, h 是归一化坐标 ∈ [0, 1]；x/y 是左上角，w/h 是宽高。" +
                    "source 默认 'original'（裁**原始照片**——坐标绝对，永远从 4096-px 原图裁，永远比 round-1 缩略图更清晰）。" +
                    "想看上一张 zoom_in 结果里更深层细节用 source='last'（链式，坐标相对）；想看原图另一区域保持 source='original'。\n" +
                    "**为什么不默认 last**：round-1 缩略图是 1568-px 降采样版，从缩略图再裁只会得到比 round-1 还糊的图；zoom_in 的全部价值在于『放大看清原图细节』，所以从原图开始。\n" +
                    "\n" +
                    "## 工具 2: compare_text —— 端云 diff\n" +
                    "纯端侧 diff：**你**从图上读的字符 vs OCR hint 给的字符。结果告诉哪些行「同意 / OCR-only / 你-only / 冲突」。\n" +
                    "**调用场景**：当 round-1 OCR hint 跟 zoom crop OCR hint 之间看起来不一致时，调一次 compare_text(claim=你读到的字符) 让端侧告诉你差异。\n" +
                    "**好处**：纯 Kotlin 字符串 diff，不调云端，省 round-trip。\n" +
                    "\n" +
                    "## content 字段要求\n" +
                    "content 必须包含图里**所有可见**文字 / 数字 / 品牌 / 日期 / 价格 / 联系方式，**原样**写出来。\n" +
                    "**优先 verbatim 复制 OCR hint（round-1 或 zoom crop）的字符**；OCR 漏掉的字 / OCR 没覆盖的区域，你**自己从图上读到的字符**也要写进 content——OCR 是辅助，不是限制。\n" +
                    "  - 茶叶包装 → content 写\"包装文字：'品名: 工夫红茶', '净含量: 250g', '生产日期: 2020-12-01'\"\n" +
                    "  - 路牌 → \"建国路 100号\"\n" +
                    "  - 收据 → \"合计 ¥168.50, 微信支付\"\n" +
                    "  - 菜单 → \"宫保鸡丁 ¥38, 鱼香肉丝 ¥42\"\n" +
                    "  - 门牌 → \"1203\"\n" +
                    "\n" +
                    "## details 字段要求（**和 content 同等重要**）\n" +
                    "**图里每一处独立的文字 / 数字 / 品牌 / 日期 / 价格，都要在 details 里对应一行**，" +
                    "value 写**逐字原文**（优先 verbatim 复制 zoom crop OCR 的字符；fallback 到 round-1 OCR hint；都没有就用你自己读的；不要意译、不要概括），" +
                    "**bbox 字段填原图 frame 的 4 点坐标**（让详情页能高亮该行在原图的位置）：\n" +
                    "  - zoom crop OCR 的 bbox 是 crop frame，要换算回原图 frame：original_bbox_corner = (zoom_x + crop_corner.x * zoom_w, zoom_y + crop_corner.y * zoom_h)\n" +
                    "  - round-1 OCR 的 bbox 直接就是原图 frame，verbatim 复制\n" +
                    "  - OCR 漏掉的行（自己读的）可以留空 bbox\n" +
                    "  - 示例：{kind:'brand', label:'品名', value:'工夫红茶', bbox:[(0.10,0.20),(0.30,0.20),(0.30,0.25),(0.10,0.25)]}\n" +
                    "**OCR [LOW] 行**（conf<0.5）**仍然写进 details**（label 标 \"[LOW]\" 或 content 里注 \"(OCR 模糊)\"）；" +
                    "**不要因为 OCR [LOW] 就整行 drop**——drop = 用户看不到字。\n" +
                    "**有上限**：场景上文字 > 8 处时，按重要性把 details 裁到 **最值得高亮的 5-8 行**（品牌、价格、日期、地址、电话、关键警示），其余的合并到 content 里。这能避免 answer 过长被 token 截断 / round-trip 撞超时。\n" +
                    "\n" +
                    "## 反幻觉（**OCR 字符 verbatim 引用，但绝不发明**）\n" +
                    "- **OCR hint（round-1 + zoom crop）的字符**：verbatim 引用，不要意译、不要 drop、不要换成自己猜的字符。\n" +
                    "- **OCR 漏掉的字 / OCR 没覆盖的区域**：你**自己从图上读到的字符**写进 content / details；读不清的写 '?' 占位（'??路 100号'）；**不要发明**。\n" +
                    "- **手写 / 艺术字 / 严重模糊**：OCR 不可靠，'?' 占位或 drop 都行，但**不要假装完全没看见**——drop = 用户看不到字。\n" +
                    "- **绝对不要**因为 OCR 有 [LOW] / OCR 漏字 / 你看着图不太确定 → 跳过 emit_bubble / 减少 details 行数 / 发空 content。**你已经看到的字符必须 emit**。\n" +
                    "\n" +
                    "**必须**调 emit_bubble 收尾——**永远不要因为 OCR 不完美就跳过 emit_bubble**。\n" +
                    "\n"
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