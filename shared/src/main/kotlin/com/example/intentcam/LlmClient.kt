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
         *  Was 256 (silently truncated `emit_bubble` on dense scenes —
         *  see 2026-07-06 baseline at r2_text 0.46 plateau with 56
         *  fixtures stuck at 0.5).
         *
         *  Bumped to 1024 (2026-07-07): covered the largest real
         *  answers with headroom; short answers still stopped early
         *  (`stop_reason=end_turn` well before the cap).
         *
         *  Bumped to 2048 (2026-07-12, Phase 2 + MAX_FULL_DIM=4096
         *  retest): with auto-OCR on every zoom crop, the round-1 hint
         *  is richer and the model emits longer thinking (1182 chars of
         *  pre-tool text in rctw_01) before writing emit_bubble.
         *  Combined with details[].bbox (4 corners × 2 coords per row)
         *  the JSON for dense-text fixtures (5-10 detail rows) plus the
         *  reasoning text exceeded 1024 → `stop_reason=max_tokens`
         *  truncated mid-`details[]` → content + remaining details
         *  dropped → 4 empty bubbles (rctw_01/03/10/18), composite
         *  floored at 0.75.  2048 leaves headroom for the worst case
         *  (1182 chars pre-tool text ≈ 600-900 BPE tokens + 8 detail
         *  rows with bbox ≈ 400 tokens + content ≈ 200 tokens = ~1500
         *  BPE tokens).  Short answers still stop early so the cost is
         *  only paid on scenes that need it.
         *
         *  2026-07-10: tested 3072 + "≤80字 prose" rule to recover
         *  rctw_21 / rctw_49 empty bubbles in @100 batch (root cause:
         *  `stop_reason=max_tokens` cut `emit_bubble` JSON mid-stream
         *  at `{"confidence": 0.93, "details": `).  REJECTED at @20:
         *  fix1 (3072 + prompt rule) 0.886 vs option C 0.902
         *  (-0.016); fix2 (3072 alone) 0.879 (-0.023).  The bigger
         *  cap flipped the model into verbose-but-tangential mode on
         *  rctw_04 (-0.125), rctw_14 (-0.142), rctw_20 (-0.125), losing
         *  more than the 2-7 empty-bubble recovery.  2048 stays.  The
         *  empty-bubble cases at @100 are 2-7/100 (2-7% noise floor);
         *  the composite cost is < -0.015 which is already inside the
         *  per-eval variance.  A real fix is "retry on stop=max_tokens
         *  with 'summarize short' nudge", tracked separately. */
        // 2026-07-10 RETEST under Phase 2 (auto-OCR verbatim + retry-once
        // + MAX_DIM=3200 + MAX_FULL_DIM=4096).  v1.2c @20 baseline 0.9078.
        // Pre-Phase-2 3072 was rejected @20 (-0.016 / -0.023 attention-spread
        // on rctw_04/14/20).  Hypothesis: OCR-verbatim details[] disables
        // the failure mode.  Ship threshold ≥0.91; revert if <0.90.
        const val MAX_TOKENS = 3072

        /** Lock at 0 to keep intent classification deterministic. */
        const val REQUEST_TEMPERATURE = 0.0

        /** Hard ceiling for one recognition round-trip.
         *
         *  Was 20s: dense-text fixtures (产品包装/收据/金融 app 截图 with
         *  10+ GT keywords) need a long emit_bubble; with MAX_TOKENS
         *  bumped to 2048 (2026-07-12), worst-case generation is now
         *  ~2048 tokens at ~30 tok/s = ~68s + 5s first-byte latency =
         *  ~73s on overload.  90s buys that case one full buffer plus
         *  headroom; OkHttp's readTimeout/callTimeout are still 0
         *  (infinite) so a true hang will still be caught.  60s was
         *  the right value for MAX_TOKENS=1024 (~38s worst case); 90s
         *  matches the new ceiling. */
        const val TOTAL_TIMEOUT_MS = 90_000L

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
         *  v1.1 (2026-07-12): extract_text added.  Sibling of
         *  zoom_in — same region input, but returns ONLY OCR text
         *  (no image).  Use when you've already seen the region in
         *  round-1 and just want verbatim characters.
         *  Tooling: zoom_in, extract_text, compare_text, emit_bubble.
         *  See [[eval-phase2a-autoocr-2026-07-11]]. */
        const val TOOL_USE_SYSTEM =
            "你是 IntentCam 的视觉意图助手。你有四个工具：**zoom_in**（裁剪放大 + 自动 OCR）、**extract_text**（区域 OCR 纯文本）、**compare_text**（端云 diff）、**emit_bubble**（收尾）。\n" +
                    "\n" +
                    "## 端云协同识别工作流（**这是核心，请严格按这个走**）\n" +
                    "\n" +
                    "### Step 1: round-1 — 读 OCR 全图扫描 + 看缩略图\n" +
                    "你的 user message 里有一份 **【read_text 全图扫描结果】**——on-device OCR 在 4096-px 全分辨率扫过整张图，按行返回字符 + 4 点坐标 + 可信度（按 conf 降序，最多 30 行，[LOW] 标记 < 0.5）。\n" +
                    "**这是 verbatim 字符基准**：OCR 给的字符直接 verbatim 引用到 emit_bubble.content 和 details[]，不要自己重新组织、意译、概括。\n" +
                    "**这张缩略图是 3200-px 降采样版**（原图手机直出 8000+ 像素，cap 到 3200 给 LLM——2026-07-12 实测的 sweet spot），够看清大部分文字，但**密集小字 / 角落字 / 模糊字在缩略图上会糊**——遇到这种情况必须走 Step 2。\n" +
                    "\n" +
                    "### Step 2: [LOW] / 漏扫 / 缩略图看不清 → **默认 extract_text**，只在需要看新像素时才 zoom_in\n" +
                    "3200-px 缩略图 + 4096-px OCR 是首选路径，**但以下情况必须进入 Step 2**——**不要因为缩略图看着像就跳过**：\n" +
                    "  - **[LOW] 行**（conf < 0.5）——**默认行为是调 extract_text(bbox)** 重扫该区域，拿到高保真字符即可，**不要**先 zoom_in（image token 浪费）。hint 给出的 4 点 bbox 直接当 x/y/w/h。\n" +
                    "  - **OCR 漏扫**：hint 里完全没有，但你**在缩略图上看到了该位置的字**——**调 extract_text(visible_bbox)**，不要调 zoom_in。\n" +
                    "  - **缩略图上有字但 OCR 没识别**，且**你在缩略图上完全看不到那块**（角落字 / 缩略图裁切掉的部分）——**这时候才调 zoom_in(bbox, source='original')** 把那块新像素拉出来。\n" +
                    "**核心原则**：\n" +
                    "  - 已经在缩略图里看到区域 + 只是 OCR 不确定 → **extract_text**（只返文本，省 image token）。\n" +
                    "  - 缩略图里没看到 / 需要看新像素 → **zoom_in**（返图像，你付 image token）。\n" +
                    "  - 不要因为「保险」再 zoom_in——Step 2 的关键在于**只补 OCR 漏的那部分**，不要扩大化。\n" +
                    "\n" +
                    "\n" +
                    "### Step 3: zoom_in crop 自动附 OCR hint（**trust 这些字符**）\n" +
                    "每次 zoom_in 的裁剪结果都**自动附带一次高保真 OCR 重扫**（更高分辨率，比 round-1 同区域更可靠；top-10 行按 conf 降序，[LOW] < 0.5，**坐标是 crop frame 不是原图 frame**——要在 details[].bbox 里复用回原图坐标，offset 加回你传给 zoom_in 的 (x, y)）。\n" +
                    "**请先相信 crop OCR 的字符**：它是高保真重扫，比 round-1 OCR 更可靠。crop OCR 的字符**直接 verbatim 引用**到 emit_bubble（[LOW] 行也 verbatim 引用——[LOW] 只是 OCR 引擎 confidence 低，字符本身仍然是你能直接用的 verbatim 字符；标记 \"[LOW]\" 让用户在 UI 看到这一行 OCR 不太确定）。\n" +
                    "\n" +
                    "### Step 4: emit_bubble\n" +
                    "看清楚内容 + 理解意图后，调 emit_bubble(content, intent, type, intent_focus?, confidence, details?, action_ids?) 总结。\n" +
                    "__INTENT_BLOCK__\n" +
                    "__ACTIONS_BLOCK__\n" +
                    "\n" +
                    "## 工具 1: zoom_in —— 看清细节 + 自动 OCR\n" +
                    "**新行为（Phase 2a）**：每次 zoom_in 的裁剪结果**自动附带 OCR hint**，所以你不需要再为看清裁剪区域再调 read_text——zoom_in 已经给你了高保真 OCR 字符。\n" +
                    "参数：x, y, w, h 是归一化坐标 ∈ [0, 1]；x/y 是左上角，w/h 是宽高。" +
                    "source 默认 'original'（裁**原始照片**——坐标绝对，永远从 4096-px 原图裁，永远比 round-1 缩略图更清晰）。" +
                    "想看上一张 zoom_in 结果里更深层细节用 source='last'（链式，坐标相对）；想看原图另一区域保持 source='original'。\n" +
                    "**为什么不默认 last**：round-1 缩略图是 3200-px 降采样版，从缩略图再裁只会得到 ≤3200-px 的输出，没有放大效果；zoom_in 的价值在于『从 4096-px 原图裁感兴趣的小区域』，让 LLM 把算力集中在更小区域看更细的图，所以从原图开始。\n" +
                    "\n" +
                    "## 工具 2: compare_text —— 端云 diff\n" +
                    "纯端侧 diff：**你**从图上读的字符 vs OCR hint 给的字符。结果告诉哪些行「同意 / OCR-only / 你-only / 冲突」。\n" +
                    "**调用场景**：当 round-1 OCR hint 跟 zoom crop OCR hint 之间看起来不一致时，调一次 compare_text(claim=你读到的字符) 让端侧告诉你差异。\n" +
                    "**好处**：纯 Kotlin 字符串 diff，不调云端，省 round-trip。\n" +
                    "\n" +
                    "## 工具 3: extract_text —— 区域 OCR 纯文本（**Step 2 默认路径**）\n" +
                    "对原图某个区域单独跑一次高保真 OCR，**只返回 OCR 字符，不附图**。\n" +
                    "**和 zoom_in 的核心区别**：zoom_in 会把裁剪图回传给你看（你付 image token）；extract_text 只把 OCR 字符给你（极轻）。\n" +
                    "**默认使用场景**（Step 2 的首选）：\n" +
                    "  - round-1 OCR hint 给了一行 [LOW]——**直接调 extract_text(bbox)** 重扫，**不要**先 zoom_in。\n" +
                    "  - round-1 OCR hint 在某区域字符不全 / 漏扫，但你**在缩略图里看到了那块**——**调 extract_text(visible_bbox)** 拿准确字符。\n" +
                    "  - 想 fan-out 验证多个区域的文字（一次 round 调 N 次）——每次只回传文字 token，不爆 image token 预算。\n" +
                    "**什么时候不要用 extract_text**：\n" +
                    "  - 缩略图里**完全看不到**那块区域（角落字被裁掉 / 图外细节）——这种情况**必须**用 zoom_in 把新像素拉出来。\n" +
                    "  - 你需要看图理解非文字内容（颜色 / 形状 / 物体）—— extract_text 只返字符，没有图。\n" +
                    "参数：x, y, w, h 同 zoom_in（归一化坐标 ∈ [0, 1]，默认 source='original' 绝对坐标）。\n" +
                    "返回值：和 zoom crop OCR 同款格式（【extract_text 区域 OCR】+ 行/坐标/conf），便于你直接 verbatim 复制到 emit_bubble。\n" +
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

        /** Build the live tool-use system prompt by splicing the
         *  dynamic intent + action blocks into [TOOL_USE_SYSTEM] at
         *  the `__INTENT_BLOCK__` / `__ACTIONS_BLOCK__` placeholders.
         *
         *  The placeholders MUST exist verbatim in [TOOL_USE_SYSTEM];
         *  a missing placeholder is treated as a programmer error
         *  (silent drift would let a future prompt edit silently drop
         *  the dynamic block, which defeats the whole point of this
         *  indirection).
         *
         *  [actionIds] is the registered `ActionDef` id list (empty
         *  list = no actions block; rendered as a one-line "no
         *  actions" note so the model can leave `action_ids` blank).
         *  Lives in `shared/` so the call site (ToolUseLoop) hands
         *  pre-resolved strings, not the Android-only ActionRegistry
         *  type — `app/` passes `actionRegistry.allIds()`.
         */
        fun toolUseSystemPrompt(intents: IntentRegistry, actionIds: List<String> = emptyList()): String {
            val renderedIntents = intents.renderIntentBlock()
            val renderedActions = if (actionIds.isEmpty()) {
                "actions ∈ {}（暂无动作可选；emit_bubble.action_ids 留空即可）"
            } else {
                "actions ∈ {${actionIds.joinToString(", ")}}。"
            }
            require(TOOL_USE_SYSTEM.contains("__INTENT_BLOCK__")) {
                "TOOL_USE_SYSTEM missing __INTENT_BLOCK__ placeholder"
            }
            require(TOOL_USE_SYSTEM.contains("__ACTIONS_BLOCK__")) {
                "TOOL_USE_SYSTEM missing __ACTIONS_BLOCK__ placeholder"
            }
            return TOOL_USE_SYSTEM
                .replace("__INTENT_BLOCK__", renderedIntents)
                .replace("__ACTIONS_BLOCK__", renderedActions)
        }
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