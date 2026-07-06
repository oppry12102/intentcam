package com.example.intentcam

import org.json.JSONArray
import org.json.JSONObject

/**
 * Orchestrates one recognition cycle: round 1 picks a tool, round 2+
 * finalizes the answer.
 *
 * Flow:
 *  1. Round 1 — `streamToolUse` with `tools[]` and the user image.
 *  2. If model emitted any `tool_use` blocks, dispatch each via the
 *     registered [ToolDef.body] and collect results.
 *  3. If any tool returned `finalBubble = true`, surface that bubble
 *     immediately and stop.
 *  4. If any tool returned `needsUserInput = true`, return
 *     [Outcome.PendingUserInput] and pause.
 *  5. Otherwise append the tool_results to the message history and
 *     run another round.  Stop when the model emits no more
 *     `tool_use` blocks (treat its text as the final answer) or
 *     after [MAX_ROUNDS].
 *
 * Logging hooks let the caller surface per-round progress on the
 * debug overlay without coupling this class to UI state.
 */
class ToolUseLoop(
    private val client: LlmClient,
    private val registry: ToolRegistry,
    private val log: (tag: String, msg: String) -> Unit = { _, _ -> },
) {

    /** Outcome of one [runCycle].  Exactly one of these is set per call. */
    sealed class Outcome {
        /** The name of the first non-[FINAL_BUBBLE_TOOL] tool the model
         *  invoked this cycle, if any.  Exposed so callers (notably the
         *  eval) can tell "model did reconnaissance before answering"
         *  apart from "model went straight to emit_bubble". */
        abstract val firstToolName: String?

        /** A regular final bubble (no more rounds needed). */
        data class Bubble(
            val bubble: com.example.intentcam.Bubble,
            override val firstToolName: String? = null,
        ) : Outcome()

        /** A placeholder bubble plus a request for user input.
         *  Resume by calling [runCycle] again with non-null [userText]. */
        data class PendingUserInput(
            val placeholder: com.example.intentcam.Bubble,
            val request: UserInputRequest,
            override val firstToolName: String? = null,
        ) : Outcome()

        /** The cycle errored out (LLM timeout, parse failure, etc.).
         *  Caller surfaces a fallback bubble. */
        data class Error(val message: String) : Outcome() {
            override val firstToolName: String? = null
        }
    }

    /**
     * Run one cycle starting with a pre-picked tool.  Used by
     * `runChip` — when the user taps a follow-up chip on a bubble,
     * we skip the model-picks-tool round and seed the conversation
     * with the chip's tool+input, so the model goes straight to
     * round 2 (call emit_bubble).
     *
     * @param jpeg the captured image (same one the user just looked at)
     * @param toolName name of the tool to seed; must be registered
     * @param toolInputJson raw JSON for the tool's input
     */
    suspend fun runWithTool(
        thumbnail: ByteArray,
        fullRes: ByteArray,
        toolName: String,
        toolInputJson: String,
    ): Outcome {
        val def = registry.get(toolName)
            ?: return Outcome.Error("未知工具: $toolName")
        val parsedInput = runCatching { JSONObject(toolInputJson) }
            .getOrElse { JSONObject() }
        val config = client.config
        val messages = JSONArray()
            .put(client.userImageMessage(thumbnail, ""))
        // Synthesize round 1: assistant emits a tool_use for the chip's
        // tool, then user returns the tool_result.  The model then
        // sees a complete prior round and calls emit_bubble in round 2.
        val seedId = "chip-${System.currentTimeMillis()}"
        val seedAssistant = JSONObject()
            .put("role", "assistant")
            .put(
                "content",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", seedId)
                            .put("name", def.name)
                            .put("input", parsedInput)
                    )
            )
        messages.put(seedAssistant)
        val toolResult = try {
            def.body(ToolContext(jpeg = fullRes, originalFullRes = fullRes, thumbnail = thumbnail, userText = "", config = config), parsedInput)
        } catch (e: Throwable) {
            log("TOOL_ERR", "${def.name}: ${formatThrowable(e)}")
            ToolResult(toolSummary = "工具执行失败：${e.message?.take(80) ?: "未知错误"}")
        }
        val seedUser = JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", seedId)
                            .put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", toolResult.toolSummary)
                                    )
                            )
                    )
            )
        messages.put(seedUser)
        log("TOOL", "→ chip 触发 ${def.name} (skip round 1)")

        // Now run round 2 (and possibly more) starting from this seeded state.
        val toolsJson = registry.toAnthropicToolsJson()
        var lastRound: RoundSnapshot? = null
        var pendingUserInput: PendingUserInput? = null
        var anyFinalBubble: ToolResult? = null
        for (round in 1..MAX_ROUNDS) {
            log("TOOL", "→ chip 第 $round 轮（messages=${messages.length()}）")
            val response: ToolUseResponse = try {
                client.streamToolUse(
                    system = LlmClient.TOOL_USE_SYSTEM,
                    messages = messages,
                    toolsJson = toolsJson,
                )
            } catch (e: Throwable) {
                log("TOOL_ERR", formatThrowable(e))
                return Outcome.Error(e.message ?: "LLM 失败")
            }
            log(
                "TOOL",
                "← text=${response.text.length}字 blocks=${response.toolBlocks.size} stop=${response.stopReason}"
            )
            lastRound = RoundSnapshot(round, response.text, response.toolBlocks)
            messages.put(reconstructAssistantMessage(response))
            if (response.toolBlocks.isEmpty()) break
            val toolResults = JSONArray()
            var anyNeedsInput = false
            anyFinalBubble = null
            // Chain state for multi-zoom in one round.
            var currentImage: ByteArray = fullRes
            val followUps = mutableListOf<ByteArray>()
            for (block in response.toolBlocks) {
                val bdef = registry.get(block.name)
                if (bdef == null) {
                    toolResults.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", block.id)
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "text").put("text", "未知工具：${block.name}"))
                            )
                            .put("is_error", true)
                    )
                    continue
                }
                val bpInput = runCatching { JSONObject(block.inputJson) }.getOrElse { JSONObject() }
                val bres = try {
                    bdef.body(
                        ToolContext(
                            jpeg = currentImage,
                            originalFullRes = fullRes,
                            thumbnail = thumbnail,
                            userText = "",
                            config = config,
                        ),
                        bpInput,
                    )
                } catch (e: Throwable) {
                    ToolResult(toolSummary = "工具执行失败：${e.message?.take(80) ?: "未知错误"}")
                }
                if (bres.needsUserInput) {
                    anyNeedsInput = true
                    pendingUserInput = PendingUserInput(
                        toolName = bdef.name,
                        prompt = bres.userInputPrompt.ifBlank { "请补充信息" },
                        bubble = buildPlaceholder(
                            jpeg = fullRes,
                            toolName = bdef.name,
                            detail = bres.toolSummary,
                        ),
                    )
                }
                if (bres.finalBubble && anyFinalBubble == null) anyFinalBubble = bres
                if (bres.followUpJpeg != null) {
                    currentImage = bres.followUpJpeg
                    followUps.add(bres.followUpJpeg)
                }
                toolResults.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.id)
                        .put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", bres.toolSummary))
                        )
                )
            }
            // Build next user message: prepend followUpJpegs, then tool_results.
            val nextContent = JSONArray()
            if (followUps.isNotEmpty()) {
                followUps.forEachIndexed { i, img ->
                    val b64 = java.util.Base64.getEncoder().encodeToString(img)
                    nextContent.put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "image/jpeg")
                                    .put("data", b64 as Any)
                            )
                    )
                    val hint = if (followUps.size == 1) {
                        "已放大你刚才要求的区域（更高分辨率），请用这张图继续回答。"
                    } else {
                        "放大区域 #${i + 1}/${followUps.size}，请用这些图继续回答。"
                    }
                    nextContent.put(JSONObject().put("type", "text").put("text", hint))
                }
            }
            for (i in 0 until toolResults.length()) {
                nextContent.put(toolResults.get(i))
            }
            messages.put(JSONObject().put("role", "user").put("content", nextContent))
            if (anyFinalBubble != null) break
            if (anyNeedsInput && pendingUserInput != null) {
                val pui = pendingUserInput
                return Outcome.PendingUserInput(pui.bubble, UserInputRequest(pui.toolName, pui.prompt))
            }
        }
        if (anyFinalBubble != null) {
            val tb = anyFinalBubble
            return Outcome.Bubble(
                com.example.intentcam.Bubble(
                    id = "bubble-${System.currentTimeMillis()}",
                    type = tb.type,
                    title = tb.title.ifBlank { "未识别" },
                    detail = tb.detail,
                    confidence = tb.confidence,
                    imageBytes = fullRes,
                    createdAtMs = System.currentTimeMillis(),
                    toolName = def.name,
                    details = tb.details,
                ),
                firstToolName = def.name,
            )
        }
        val finalText = lastRound?.text.orEmpty()
        val parsed = parseFinalAnswer(finalText)
        return Outcome.Bubble(
            com.example.intentcam.Bubble(
                id = "bubble-${System.currentTimeMillis()}",
                type = parsed.type,
                title = parsed.intent.ifBlank { "未识别" },
                detail = if (parsed.scene.isNotBlank()) parsed.scene else finalText.take(200),
                confidence = parsed.confidence,
                imageBytes = fullRes,
                createdAtMs = System.currentTimeMillis(),
                toolName = def.name,
            )
        )
    }

    /**
     * Run one cycle.
     *
     * @param thumbnail the small JPEG sent to the LLM as the
     *   round-1 overview.
     * @param quadrants four high-detail crops (top-left, top-right,
     *   bottom-left, bottom-right) bundled with the thumbnail in
     *   round 1.  Empty list falls back to the single-image message.
     * @param fullRes the original full-resolution JPEG; preserved
     *   across rounds so zoom_in can crop from it on demand.
     * @param userText optional follow-up text from a prior
     *   [Outcome.PendingUserInput]; pass "" on round 1.
     */
    suspend fun runCycle(
        thumbnail: ByteArray,
        fullRes: ByteArray,
        userText: String,
        quadrants: List<ByteArray> = emptyList(),
    ): Outcome {
        val config = client.config
        val maxRounds = MAX_ROUNDS
        val toolsJson = registry.toAnthropicToolsJson()
        val messages = JSONArray()
            .put(
                if (quadrants.isEmpty()) client.userImageMessage(thumbnail, userText)
                else client.userImageWithQuadrants(thumbnail, quadrants, userText)
            )

        var lastRound: RoundSnapshot? = null
        var pendingUserInput: PendingUserInput? = null
        var chosenToolName: String? = null

        for (round in 1..maxRounds) {
            log("TOOL", "→ 第 $round 轮（messages=${messages.length()}）")
            val response: ToolUseResponse = try {
                client.streamToolUse(
                    system = LlmClient.TOOL_USE_SYSTEM,
                    messages = messages,
                    toolsJson = toolsJson,
                )
            } catch (e: Throwable) {
                log("TOOL_ERR", formatThrowable(e))
                return Outcome.Error(e.message ?: "LLM 失败")
            }
            log(
                "TOOL",
                "← text=${response.text.length}字 blocks=${response.toolBlocks.size} stop=${response.stopReason}"
            )

            lastRound = RoundSnapshot(
                round = round,
                text = response.text,
                toolBlocks = response.toolBlocks,
            )

            // Persist the assistant message exactly as the model sent
            // it — this is required by Anthropic's protocol so the
            // next round can quote tool_use_id back.
            messages.put(reconstructAssistantMessage(response))

            if (response.toolBlocks.isEmpty()) {
                // Model produced text without tool_use: that's our
                // final answer.
                break
            }

            // Dispatch tools.
            val toolResults = JSONArray()
            var anyNeedsInput = false
            var anyFinalBubble: ToolResult? = null
            // Chain state: each zoom_in (with default source="last")
            // crops the previously produced image, allowing iterative
            // zoom-in.  We start with the original full-res photo.
            var currentImage: ByteArray = fullRes
            // All follow-up images in call order, attached to the
            // next user message in the same order.  Multi-zoom in
            // one round produces a list of N images.
            val followUps = mutableListOf<ByteArray>()
            for (block in response.toolBlocks) {
                val def = registry.get(block.name)
                // Track the FIRST non-finalizing tool the model picked.
                // emit_bubble is the final-answer tool, not the
                // interpretation tool; its name shouldn't overwrite the
                // tool that did the actual work (e.g. read_device_reading).
                if (block.name != FINAL_BUBBLE_TOOL && chosenToolName == null) {
                    chosenToolName = block.name
                }
                if (def == null) {
                    log("TOOL", "未知工具 ${block.name}，回 error 提示")
                    toolResults.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", block.id)
                            .put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", "未知工具：${block.name}")
                                    )
                            )
                            .put("is_error", true)
                    )
                    continue
                }
                log("TOOL", "→ ${def.name}(${block.inputJson.take(120)})")
                val parsedInput = runCatching { JSONObject(block.inputJson) }
                    .getOrElse { JSONObject() }
                val toolResult = try {
                    // Build a fresh ToolContext per call.  The body
                    // sees the chain-up-to-this-point via ctx.jpeg;
                    // the original is always available via
                    // ctx.originalFullRes for source="original" mode.
                    def.body(
                        ToolContext(
                            jpeg = currentImage,
                            originalFullRes = fullRes,
                            thumbnail = thumbnail,
                            userText = userText,
                            config = config,
                        ),
                        parsedInput,
                    )
                } catch (e: Throwable) {
                    log("TOOL_ERR", "${def.name}: ${formatThrowable(e)}")
                    ToolResult(toolSummary = "工具执行失败：${e.message?.take(80) ?: "未知错误"}")
                }
                log("TOOL", "← ${def.name} final=${toolResult.finalBubble} needsInput=${toolResult.needsUserInput} followUp=${toolResult.followUpJpeg != null}")
                if (toolResult.needsUserInput) {
                    anyNeedsInput = true
                    pendingUserInput = PendingUserInput(
                        toolName = def.name,
                        prompt = toolResult.userInputPrompt.ifBlank { "请补充信息" },
                        bubble = buildPlaceholder(
                            jpeg = fullRes,
                            toolName = def.name,
                            detail = toolResult.toolSummary,
                        ),
                    )
                }
                if (toolResult.finalBubble && anyFinalBubble == null) {
                    anyFinalBubble = toolResult
                }
                // Advance the chain.  A followUpJpeg becomes the
                // source for any subsequent zoom_in in the same
                // round (with source="last") and for the next round
                // entirely.
                if (toolResult.followUpJpeg != null) {
                    currentImage = toolResult.followUpJpeg
                    followUps.add(toolResult.followUpJpeg)
                }
                toolResults.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.id)
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", toolResult.toolSummary)
                                )
                        )
                )
            }
            // Build next user message.  If any tool returned a
            // follow-up image (zoom_in), prepend an image content
            // block + a hint per image so the model sees the
            // high-detail regions in addition to the tool_results.
            val nextContent = JSONArray()
            if (followUps.isNotEmpty()) {
                followUps.forEachIndexed { i, img ->
                    val b64 = java.util.Base64.getEncoder().encodeToString(img)
                    nextContent.put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "image/jpeg")
                                    .put("data", b64 as Any)
                            )
                    )
                    val hint = if (followUps.size == 1) {
                        "已放大你刚才要求的区域（更高分辨率），请用这张图继续回答。"
                    } else {
                        "放大区域 #${i + 1}/${followUps.size}，请用这些图继续回答。"
                    }
                    nextContent.put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", hint)
                    )
                }
            }
            for (i in 0 until toolResults.length()) {
                nextContent.put(toolResults.get(i))
            }
            // If the model didn't emit_bubble this round, nudge it
            // to wrap up.  Keeps the loop bounded: the model can
            // zoom_in as many times as it likes, but at the end of
            // each round we ask for a final summary.
            if (anyFinalBubble == null) {
                nextContent.put(JSONObject().put("type", "text").put(
                    "text",
                    "你已经 zoom_in ${followUps.size} 次。如果还有看不清的，可以再 zoom_in；如果内容已经清楚，" +
                        "**必须**调 emit_bubble 总结 (content / intent / type / confidence)。"
                ))
            }
            messages.put(JSONObject().put("role", "user").put("content", nextContent))

            if (anyFinalBubble != null) {
                val tb = anyFinalBubble
                return Outcome.Bubble(
                    com.example.intentcam.Bubble(
                        id = "bubble-${System.currentTimeMillis()}",
                        type = tb.type,
                        title = tb.title.ifBlank { "未识别" },
                        detail = tb.detail,
                        confidence = tb.confidence,
                        imageBytes = fullRes,
                        createdAtMs = System.currentTimeMillis(),
                        toolName = chosenToolName,
                        intentFocus = null,  // emit_bubble body doesn't carry it yet
                        // emit_bubble body populates tb.details from
                        // the model's details[] JSON array.  Without
                        // this, every Bubble from runCycle has an
                        // empty details list even when the model did
                        // emit the rows — the r2_text plateau's
                        // companion symptom.
                        details = tb.details,
                    ),
                    firstToolName = chosenToolName,
                )
            }
            if (anyNeedsInput && pendingUserInput != null) {
                val pui = pendingUserInput
                return Outcome.PendingUserInput(
                    pui.bubble,
                    UserInputRequest(pui.toolName, pui.prompt),
                    firstToolName = chosenToolName,
                )
            }
        }

        // Hit MAX_ROUNDS without the model emitting_bubble.  Take
        // whatever the last round's text said as the description, and
        // synthesize a default emit_bubble from it.  Better than
        // failing the whole cycle.
        val finalText = lastRound?.text.orEmpty()
        return Outcome.Bubble(
            com.example.intentcam.Bubble(
                id = "bubble-${System.currentTimeMillis()}",
                type = "info",
                title = finalText.take(40).ifBlank { "未识别" },
                detail = finalText.take(200).ifBlank { "（模型未给出内容描述）" },
                confidence = 0.5f,
                imageBytes = fullRes,
                createdAtMs = System.currentTimeMillis(),
                toolName = chosenToolName,
            ),
            firstToolName = chosenToolName,
        )
    }

    private fun buildPlaceholder(
        jpeg: ByteArray,
        toolName: String,
        detail: String,
    ): com.example.intentcam.Bubble = com.example.intentcam.Bubble(
        id = "bubble-${System.currentTimeMillis()}",
        type = "info",
        title = "需要补充信息",
        detail = detail.ifBlank { "via $toolName" },
        confidence = 0.5f,
        imageBytes = jpeg,
        createdAtMs = System.currentTimeMillis(),
        toolName = toolName,
        needsUserInput = true,
    )

    /**
     * Reconstruct the assistant message exactly as the model emitted
     * it — a `content` array of text blocks + tool_use blocks.  This
     * is what Anthropic requires for the next round to quote back
     * `tool_use_id`.
     */
    private fun reconstructAssistantMessage(response: ToolUseResponse): JSONObject {
        val content = JSONArray()
        if (response.text.isNotEmpty()) {
            content.put(JSONObject().put("type", "text").put("text", response.text))
        }
        for (block in response.toolBlocks) {
            val input = runCatching { JSONObject(block.inputJson) }
                .getOrElse { JSONObject() }
            content.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", block.id)
                    .put("name", block.name)
                    .put("input", input)
            )
        }
        return JSONObject().put("role", "assistant").put("content", content)
    }

    private data class RoundSnapshot(
        val round: Int,
        val text: String,
        val toolBlocks: List<ToolUseBlock>,
    )

    private data class PendingUserInput(
        val toolName: String,
        val prompt: String,
        val bubble: com.example.intentcam.Bubble,
    )

    private data class ParsedAnswer(
        val scene: String,
        val intent: String,
        val type: String,
        val confidence: Float,
    )

    private fun parseFinalAnswer(raw: String): ParsedAnswer {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val first = cleaned.indexOf('{')
        val last = cleaned.lastIndexOf('}')
        if (first >= 0 && last > first) {
            val parsed = runCatching {
                JSONObject(cleaned.substring(first, last + 1))
            }.getOrNull()
            if (parsed != null) {
                val scene = parsed.optString("scene", "")
                val intent = parsed.optString("intent", "")
                val type = parsed.optString("type", "info").ifBlank { "info" }
                val conf = parsed.optDouble("confidence", 0.7).toFloat().coerceIn(0f, 1f)
                return ParsedAnswer(scene, intent, type, conf)
            }
        }
        // Fallback: use first sentence as intent.
        val firstSentence = cleaned.take(40).trim().ifBlank { "未识别" }
        return ParsedAnswer(scene = cleaned.take(160), intent = firstSentence, type = "info", confidence = 0.5f)
    }

    private companion object {
        /** Soft cap on rounds per recognition cycle.  With the
         *  two-stage content-then-intent flow + iterative zoom_in,
         *  the model can need 5-10 rounds to converge on dense
         *  images.  30 is plenty for normal use; bigger is fine for
         *  debugging. */
        const val MAX_ROUNDS = 30

        /** The tool name the model uses to emit the final Bubble.
         *  Tracked separately so we don't overwrite the interpreting
         *  tool's name in the Bubble's `toolName` field. */
        const val FINAL_BUBBLE_TOOL = "emit_bubble"
    }
}