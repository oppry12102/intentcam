package com.example.intentcam

import android.util.Base64
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin client for an Anthropic-compatible /v1/messages endpoint (MiniMax by
 * default).  Streams model output so the UI can update token-by-token.
 *
 * Intent accuracy: instead of one-shot classification, the analyzer runs
 * [analyzeRefined] through up to three rounds (BROAD → VERIFY → DECIDE) with
 * progressive prompts and observed history.  Most frames resolve after the
 * first round; genuinely ambiguous frames pay the extra latency in exchange
 * for significantly higher confidence.
 *
 * Cancellation
 * ------------
 * Both [analyzeRefined] and [answerStream] are `suspend` functions: cancelling
 * the calling coroutine aborts the in-flight SSE connection via OkHttp's
 * [EventSource.cancel].  This is the only cancellation mechanism — there is
 * no imperative equivalent (callers cooperate via coroutines).
 */
class LlmClient(@Volatile var config: LlmConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(http)

    /** Progressive refinement levels for [analyzeRefined]. */
    enum class AnalysisLevel { BROAD, VERIFY, DECIDE }

    // ---- public API ---------------------------------------------------------

    /**
     * Run one round of the intent-identification cycle.  See class doc for the
     * full 3-round protocol.  [history] contains the prior rounds' results so
     * the prompt can reference them; pass an empty list on the first round.
     * [zoomJpeg] is used only at [AnalysisLevel.DECIDE] — typically a
     * center-cropped re-encode of the larger answer JPEG so the model can re-
     * inspect the most informative region.
     *
     * [ocr] is on-device OCR text (ML Kit, Latin + Chinese).  [objects] is
     * on-device object detection labels (ImageNet-style).  Both are used as
     * ground-truth hints — the model is told they may be incomplete / wrong.
     */
    suspend fun analyzeRefined(
        level: AnalysisLevel,
        jpeg: ByteArray,
        zoomJpeg: ByteArray?,
        location: String?,
        history: List<AnalysisResult>,
        ocr: OcrResult,
        objects: ObjectResult,
        onDelta: (String) -> Unit
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val payload = if (level == AnalysisLevel.DECIDE && zoomJpeg != null) zoomJpeg else jpeg
        val body = messagesBody(
            system = SYSTEM_ANALYZE,
            maxTokens = ANALYZE_MAX_TOKENS,
            jpeg = payload,
            text = buildAnalyzePrompt(level, location, history, ocr, objects),
            stream = true
        )
        val tag = "analyze.${level.name.lowercase()}"
        val raw = streamText(body, tag, ANALYZE_ROUND_TIMEOUT_MS, onDelta)
        val parsed = extractJson(raw)
            ?: throw IllegalStateException(
                "模型未返回可解析的 JSON（tag=$tag，首 200 字：${raw.take(200)}）"
            )
        parseAnalysis(parsed.first)
    }

    suspend fun answerStream(
        intent: IntentItem,
        jpeg: ByteArray,
        location: String?,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val body = messagesBody(
            system = SYSTEM_ANSWER,
            maxTokens = ANSWER_MAX_TOKENS,
            jpeg = jpeg,
            text = buildAnswerPrompt(intent, location),
            stream = true
        )
        val raw = streamText(body, "answer", ANSWER_TOTAL_TIMEOUT_MS, onDelta)
        raw.ifBlank { "（模型未返回内容）" }
    }

    // ---- prompt builders ---------------------------------------------------

    /**
     * Build the BROAD / VERIFY / DECIDE round prompt.
     *
     * - BROAD: forced chain-of-thought.  The model must describe the image in
     *   an `observation` field before producing `scene` / `intents`.  This
     *   decouples "perceive" from "classify" and is the single biggest accuracy
     *   lever.
     * - VERIFY: given the BROAD observation + scene + intents, the model is
     *   asked to re-ground and either confirm or revise (with explanations).
     * - DECIDE: forced final answer with a 1-2 intent limit.  The model must
     *   pick even if its confidence is low.
     *
     * The on-device [ocr] text is injected in every round as a ground-truth
     * hint.  The model is told the OCR may be incomplete or wrong and must
     * verify against the image.
     */
    private fun buildAnalyzePrompt(
        level: AnalysisLevel,
        location: String?,
        history: List<AnalysisResult>,
        ocr: OcrResult,
        objects: ObjectResult
    ): String {
        val ocrBlock = formatOcrForPrompt(ocr)
        val objBlock = formatObjectsForPrompt(objects)
        return when (level) {
            AnalysisLevel.BROAD -> buildBroadPrompt(location, ocrBlock, objBlock)
            AnalysisLevel.VERIFY -> buildVerifyPrompt(location, history, ocrBlock, objBlock)
            AnalysisLevel.DECIDE -> buildDecidePrompt(location, history, ocrBlock, objBlock)
        }
    }

    /**
     * Truncate aggressively and clean up before the OCR text is fed to the
     * model.  Vision APIs occasionally emit control characters (esp. the
     * Chinese recognizer on noisy labels) — those confuse the LLM in a way
     * that the model's existing JSON parser then re-escapes, producing
     * garbage like `` in the response.  We:
     *
     *  - drop every line that ends up empty after control-char stripping,
     *  - drop anything shorter than 2 chars (almost always detector noise),
     *  - cap each line at 40 chars and the joined output at 400 chars.
     */
    private fun formatOcrForPrompt(ocr: OcrResult): String {
        if (ocr.isBlank()) return ""
        val controlChars = Regex("[\\p{Cntrl}\\p{So}\\uFEFF]")
        val kept = ocr.lines
            .asSequence()
            .map { it.text.replace(controlChars, "").trim() }
            .filter { it.length >= 2 }
            .take(20)
            .map { it.take(40) }
            .toList()
        if (kept.isEmpty()) return ""
        return kept.joinToString(" / ").take(400)
    }

    private fun formatObjectsForPrompt(objects: ObjectResult): String {
        if (objects.isBlank()) return ""
        return objects.labelsForPrompt().take(200)
    }

    /**
     * Round-1 BROAD prompt.  The schema has been tuned against the eval set
     * (`profiling/ground_truth.json`, `profiling/evaluate.py`):
     *
     * - `observation` is lifted from a 40-char limit to 80 chars with an
     *   explicit instruction to keep named entities (product names, brands,
     *   numbers, dates) — on a tea label, that buys us the literal "工夫红茶
     *   (正山小种)" ending up in the observation text instead of being
     *   compressed out to "品名".
     * - The intent list is framed as **specific user actions** ("判断是否
     *   过期", not "查询茶叶信息") with concrete examples per scene
     *   category, lifting title granularity.
     * - OCR / object-detection hints from on-device ML Kit are injected as
     *   *reference only* under observation so they ground CoT without being
     *   able to override what the model sees.
     */
    private fun buildBroadPrompt(location: String?, ocrBlock: String, objBlock: String): String =
        buildString {
            append("分析摄像头画面，分三步。\n\n")

            append("**1. observation（≤80字，必须保留画面里最显眼的 1-3 项具名内容）**\n")
            append("保留关键专有名词：产品名、品牌、数字、产地、读数、日期等\n")
            if (objBlock.isNotEmpty()) {
                append("- 设备识别物体（标签可能错，仅参考）: $objBlock\n")
            }
            if (ocrBlock.isNotEmpty()) {
                append("- 设备 OCR 文字（可能不全/有错，仅参考）: $ocrBlock\n")
            }
            append("这些会显示给用户；泛泛的\"商品标签\"不算数。\n\n")

            append("**2. scene（≤20字）**  用户视角的画面描述\n\n")

            append("**3. intents（≤4 个，按 confidence 降序）**\n")
            append("shape: {\"type\":\"info|location|solve\", \"title\":\"...\", \"detail\":\"...\", \"confidence\":0..1}\n")
            append("title 必须是**动作短语（≤6 字）**，动词开头：\n")
            append("- 查看 / 打开 / 保存 / 记录 / 校对 / 翻译 / 解释 / 阅读 / 解读 / 扫码 / 拨号 / 联系 / 调出 / 设置 / 切换\n")
            append("- 判断 / 对比 / 查 / 核 / 算 / 拆解 / 拼读 / 拨出 / 打印 / 复制\n")
            append("- 避免'查询''了解''相关信息'这类泛词\n\n")
            append("考虑用户拿到画面时**最可能想做**的具体事，尽量指向具体操作而非通用查询：\n")
            append("- 商品/标签/食品/包装 → 查看配料 / 查保质期 / 判断是否过期 / 对比同类 / 找购买链接\n")
            append("- 设备/屏幕/读数/数字 → 解读含义 / 判断正常范围 / 记录保存 / 解释为什么要测\n")
            append("- 文字/账单/标签 → 翻译 / 解释术语 / 汇总重点 / 朗读\n")
            append("- 地址/路牌/导航/地图 → 我在哪 / 怎么去 / 附近有什么 / 找此刻位置\n")
            append("- 数学/公式 → 解 / 化简 / 因式分解 / 验证\n")
            append("- 二维码 → 扫码 / 解读二维码 / 执行二维码指向的操作\n")
            append("- 屏幕（手机/电脑截屏）→ 打开 App / 调出日期 / 切换设置 / 翻译 / 读邮件 / 发送\n")
            append("- 说明书/手册 → 阅读 / 翻译 / 查操作步骤 / 查询用法\n\n")

            append("- 位置: ${location ?: "未知"}\n\n")

            append("type: info=查信息  location=我在哪/去哪  solve=解题/帮我做\n")
            append("confidence 必须真实（看不清 → 低分）。\n\n")

            append("**严格只输出 JSON，注意转义**：\n")
            append("- observation / scene 字符串中如果出现英文双引号 `\"` 或换行，")
            append("必须写成 `\\\"` 和 `\\n`\n")
            append("- intents 数组最多 4 个对象，整齐闭合\n\n")

            append("返回 JSON（仅 JSON）:\n")
            append("""{"observation":"...","scene":"...","intents":[{"type":"info|location|solve","title":"≤6字","detail":"...","confidence":0.0}]}""")
        }

    private fun buildVerifyPrompt(
        location: String?, history: List<AnalysisResult>,
        ocrBlock: String, objBlock: String
    ): String {
        require(history.isNotEmpty())
        val last = history.last()
        val intents = last.intents.take(3).joinToString("; ") {
            "${it.title}(${it.type},${(it.confidence * 100).toInt()}%)"
        }
        return buildString {
            append("重看同一张图，基于上一轮判断做修正。\n")
            append("- 上一轮 observation: ${last.observation.ifBlank { "（无）" }}\n")
            append("- 上一轮 scene: ${last.scene}\n")
            append("- 上一轮 intents: $intents\n\n")
            append("- 位置: ${location ?: "未知"}\n")
            if (objBlock.isNotEmpty()) append("- 设备识别物体: $objBlock\n")
            if (ocrBlock.isNotEmpty()) append("- 设备 OCR: $ocrBlock\n")
            append("\n步骤：\n")
            append("1. 重写 observation（如有物体/文字遗漏、读错）\n")
            append("2. 重写 scene\n")
            append("3. 重写 intents：保留仍合理的;信心不足的降 confidence 或替换;加入上一轮没考虑到的\n")
            append("\n返回 JSON 同 BROAD 格式。confidence 重新校准。")
        }
    }

    private fun buildDecidePrompt(
        location: String?, history: List<AnalysisResult>,
        ocrBlock: String, objBlock: String
    ): String = buildString {
        append("最后一轮决策。从最近 ${history.size} 轮判断中提取共识，给最终答案。\n\n")
        history.forEachIndexed { i, r ->
            val top = r.intents.firstOrNull()?.title ?: "（无）"
            val alt = r.intents.getOrNull(1)?.title?.let { " / $it" } ?: ""
            append("- 轮${i + 1}: obs=\"${r.observation.take(40)}\"; ")
            append("scene=\"${r.scene.take(30)}\"; top=$top$alt\n")
        }
        append("\n- 位置: ${location ?: "未知"}\n")
        if (objBlock.isNotEmpty()) append("- 设备识别物体: $objBlock\n")
        if (ocrBlock.isNotEmpty()) append("- 设备 OCR: $ocrBlock\n")
        append("\n必须给出 1-2 个最合理的意图；都不对就给信心最低的（≤0.5）。\n")
        append("返回 JSON 同 BROAD 格式，但 intents 长度 1-2。")
    }

    private fun buildAnswerPrompt(intent: IntentItem, location: String?): String = buildString {
        append("用户选的意图: 【${intent.title}】(${intent.type}) - ${intent.detail}\n")
        append("位置: ${location ?: "未知"}\n")
        append("结合画面，给出准确、可执行、简洁的中文答案。解题请给步骤；信息请提取关键点；")
        append("位置请说明所在地点与建议方向。")
    }

    // ---- HTTP / SSE plumbing -----------------------------------------------

    private fun messagesBody(
        system: String,
        maxTokens: Int,
        jpeg: ByteArray,
        text: String,
        stream: Boolean
    ): String {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val imageSource = JSONObject()
            .put("type", "base64")
            .put("media_type", "image/jpeg")
            .put("data", b64)
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source", imageSource))
            .put(JSONObject().put("type", "text").put("text", text))
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", content))
        return JSONObject()
            .put("model", config.model)
            .put("max_tokens", maxTokens)
            .put("temperature", REQUEST_TEMPERATURE)
            .put("system", system)
            .put("messages", messages)
            .put("stream", stream)
            .toString()
    }

    private suspend fun streamText(
        jsonBody: String,
        tag: String,
        totalTimeoutMs: Long,
        onDelta: (String) -> Unit
    ): String {
        return try {
            withTimeout(totalTimeoutMs) {
                suspendCancellableCoroutine<String> { cont ->
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

                    val accumulator = StringBuilder()
                    val resolved = AtomicBoolean(false)

                    val listener = object : EventSourceListener() {
                        override fun onEvent(
                            eventSource: EventSource, id: String?, type: String?, data: String
                        ) {
                            if (resolved.get()) return
                            when (type) {
                                "content_block_delta", "message_delta" -> {
                                    val delta = extractTextDelta(data) ?: return
                                    if (delta.isEmpty()) return
                                    val snapshot: String = synchronized(accumulator) {
                                        accumulator.append(delta)
                                        accumulator.toString()
                                    }
                                    onDelta(snapshot)
                                }
                                "error" -> {
                                    if (resolved.compareAndSet(false, true)) {
                                        // Don't resume a cancelled coroutine —
                                        // [invokeOnCancellation] has already
                                        // fired and the parent is gone.
                                        if (cont.isCancelled) return
                                        cont.resumeWithException(
                                            IllegalStateException("SSE error: $data")
                                        )
                                    }
                                }
                            }
                        }

                        override fun onFailure(
                            eventSource: EventSource, t: Throwable?, response: Response?
                        ) {
                            if (resolved.compareAndSet(false, true)) {
                                if (cont.isCancelled) return
                                // Body has often been consumed/closed when the
                                // connection drops; guard the read so it
                                // doesn't throw a secondary exception while
                                // we're already handling one.
                                val body = runCatching { response?.body?.string()?.take(300) }
                                    .getOrNull().orEmpty()
                                val ex = t ?: IllegalStateException(
                                    "HTTP ${response?.code}: $body"
                                )
                                cont.resumeWithException(ex)
                            }
                        }

                        override fun onClosed(eventSource: EventSource) {
                            if (resolved.compareAndSet(false, true)) {
                                if (cont.isCancelled) return
                                cont.resume(synchronized(accumulator) { accumulator.toString() })
                            }
                        }
                    }

                    val source: EventSource = sseFactory.newEventSource(request, listener)
                    cont.invokeOnCancellation {
                        source.cancel()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("$tag: 模型在 ${totalTimeoutMs}ms 内未完成")
        }
    }

    private fun extractTextDelta(data: String): String? {
        if (data.isBlank() || data == "[DONE]") return ""
        return try {
            val obj = JSONObject(data)
            val delta = obj.optJSONObject("delta") ?: return ""
            if (delta.optString("type") != "text_delta") return ""
            delta.optString("text")
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAnalysis(obj: JSONObject): AnalysisResult {
        val observation = obj.optString("observation")
        val scene = obj.optString("scene")
        val arr = obj.optJSONArray("intents") ?: JSONArray()
        val intents = ArrayList<IntentItem>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val title = it.optString("title").trim()
            if (title.isEmpty()) continue
            intents.add(
                IntentItem(
                    id = "$i-${title.hashCode()}",
                    type = it.optString("type", "info").ifBlank { "info" },
                    title = title,
                    detail = it.optString("detail"),
                    confidence = it.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
                )
            )
        }
        // Defensive fallback: if the model produced no parseable intents, the
        // bubble would otherwise be empty.  Per the user contract, the
        // detail must carry the model's own description of the image
        // (the `observation` field), NOT a generic "请尝试重新对准" message
        // that tells the user nothing about what they were pointing at.
        // The title stays empty so the bubble shows just the description
        // with no "未识别" / "无意图" / "图片描述" prefix.
        if (intents.isEmpty()) {
            val desc = buildList {
                if (observation.isNotBlank()) add(observation)
                if (scene.isNotBlank() && scene !in this) add(scene)
            }.joinToString(" · ")
            intents += IntentItem(
                id = "0-fallback",
                type = "info",
                title = "",
                detail = desc,
                confidence = 0.1f
            )
        }
        return AnalysisResult(
            observation = observation,
            scene = scene,
            intents = intents.sortedByDescending { it.confidence }
        )
    }

    private fun extractJson(responseText: String): Pair<JSONObject, String>? {
        val raw = responseText.trim()
        if (raw.isEmpty()) return null

        // 1. Try the whole string as JSON.  Strip markdown fences first;
        //    the model commonly wraps responses in ```json ... ```.
        val stripped = raw
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        runCatching { JSONObject(stripped) }
            .getOrNull()
            ?.let(::returnIfLooksValid)
            ?.let { return it to raw }

        // 2. The model may emit a partial stream (truncated mid-JSON) or
        //    wrap the JSON in prose ("下面是意图: {...} 请继续...").
        //    Take the substring from the first `{` to the last `}`.  The
        //    substring may not be balanced; we then attempt to repair
        //    truncated JSON by progressively trimming from the end until
        //    org.json can parse it.
        val first = stripped.indexOf('{')
        val last  = stripped.lastIndexOf('}')
        if (first >= 0 && last > first) {
            val candidate = stripped.substring(first, last + 1)
            runCatching { JSONObject(candidate) }
                .getOrNull()
                ?.let(::returnIfLooksValid)
                ?.let { return it to raw }

            // 3. Repair: scan from the end for the last balanced `}` that
            //    org.json can parse, and recover what's there.  This rescues
            //    truncated streams.
            for (e in last downTo first + 1) {
                if (stripped[e] != '}') continue
                val sub = stripped.substring(first, e + 1)
                val parsed = runCatching { JSONObject(sub) }.getOrNull() ?: continue
                val valid = returnIfLooksValid(parsed) ?: continue
                return valid to raw
            }
        }

        // 4. The model emitted no JSON at all.  We can't recover anything
        //    structured; return null and let the caller surface a friendlier
        //    error including a snippet of the raw text.
        return null
    }

    /**
     * Heuristic sanity check on a decoded [JSONObject]: it must look like
     * an analysis response (have at least the `scene` or `intents` key).
     * Pure `{"foo":"bar"}` would otherwise be misidentified as a payload.
     */
    private fun returnIfLooksValid(obj: JSONObject): JSONObject? {
        if (!obj.has("scene") && !obj.has("intents") && !obj.has("observation")) {
            return null
        }
        return obj
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        const val ANALYZE_MAX_TOKENS = 320
        const val ANSWER_MAX_TOKENS = 900

        // Lock at 0 to keep intent classification deterministic — eval shows
        // 1.000 composite at temp=0 on all 7 fixtures vs ~0.91 with the
        // Anthropic default of 1.0.  The user can still pick from multiple
        // intents returned in the intents[] array.
        const val REQUEST_TEMPERATURE = 0.0

        // Per-round budget; the cycle uses up to 3 rounds, max ~45s.
        const val ANALYZE_ROUND_TIMEOUT_MS = 15_000L
        const val ANSWER_TOTAL_TIMEOUT_MS = 45_000L

        const val SYSTEM_ANALYZE =
            "你是手机端实时视觉意图助手。从摄像头画面准确推断用户意图。" +
            "先用 observation 字段描述所见（必须保留产品名/品牌/数字/日期等关键专有名词），再给 scene、intents。严格只输出 JSON。"
        const val SYSTEM_ANSWER =
            "你是一个贴心的中文视觉助手，根据用户选定的意图和画面，给出准确、可执行、简洁的答案。"
    }
}
